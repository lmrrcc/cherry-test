//! Image services: containers Cherry Test starts from `projects.<id>.services.<name>.image`.
//!
//! A hardened container job gets its own: a holder container owns a private network namespace,
//! every service and the job itself join it, and each service listens on `127.0.0.1:<port>`.
//! In local execution a scope's image services publish their port on a random `127.0.0.1` port.

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::process::Output;
use std::time::{Duration, Instant};

use anyhow::{bail, Context, Result};
use cherry_test_common::container::{
    normalize_path, sanitize_name, BindMount, HealthCheck, NetworkMode, PullPolicy, SidecarService,
    SidecarSpec, DEFAULT_HEALTH_TIMEOUT_SECS, JOB_LABEL, PID_LABEL, RUN_LABEL, SERVICE_LABEL,
};

use crate::config::{ServiceConfig, ServiceHealth, WorkspaceConfig};
use crate::scope::{resolve_projects, service_filter};
use crate::workspace::{command_in, service_env, Environment, ServiceEndpoint};

/// Variable the runner sets to the comma-separated scopes it provisioned for a job; a test process
/// reads the endpoints of these scopes from its environment instead of starting anything.
pub const SCOPE_PROVISIONED_ENV: &str = "CHERRY_TEST_SCOPE_PROVISIONED";

/// How long to wait between two health probes.
const PROBE_INTERVAL: Duration = Duration::from_millis(500);

/// The labels every container of a job, or of a scope, carries.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Labels {
    pub run: String,
    pub job: String,
    pub owner_pid: u32,
}

/// Where an image service listens: in a job's private network, or on a published `127.0.0.1` port.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ServiceNetwork<'a> {
    Holder(&'a str),
    Published,
}

/// Whether `scope` is listed in [`SCOPE_PROVISIONED_ENV`].
pub fn provisioned(env: &Environment, scope: &str) -> bool {
    env.get(SCOPE_PROVISIONED_ENV)
        .is_some_and(|value| value.split(',').any(|entry| entry == scope))
}

/// Image services the scopes select, as `(project, service, config)`, each once, in scope order and
/// then in project and service name order.
pub fn image_services(
    config: &WorkspaceConfig,
    scopes: &[String],
) -> Result<Vec<(String, String, ServiceConfig)>> {
    let mut found: Vec<(String, String, ServiceConfig)> = Vec::new();
    for scope in scopes {
        for project_name in resolve_projects(config, scope)? {
            let Some(project) = config.projects.get(&project_name) else {
                continue;
            };
            let services: BTreeMap<&String, &ServiceConfig> = project.services.iter().collect();
            for (name, service) in services {
                if !service.is_image() || !service_filter(config, scope, &project_name, name) {
                    continue;
                }
                if !found
                    .iter()
                    .any(|(project, known, _)| *project == project_name && known == name)
                {
                    found.push((project_name.clone(), name.clone(), service.clone()));
                }
            }
        }
    }
    Ok(found)
}

/// The resolved settings of one image service; mount sources resolve against `root`.
pub fn resolve_service(
    root: &Path,
    project: &str,
    service: &str,
    config: &ServiceConfig,
    name: String,
    default_pull: PullPolicy,
) -> SidecarService {
    let (exec, timeout) = match &config.health {
        Some(ServiceHealth::Probe(probe)) => (
            probe.exec.clone().unwrap_or_default(),
            probe.timeout.map(|timeout| timeout.0),
        ),
        _ => (Vec::new(), None),
    };
    SidecarService {
        project: project.to_string(),
        service: service.to_string(),
        name,
        image: config.image.clone().unwrap_or_default(),
        pull: config.pull.unwrap_or(default_pull),
        port: config.port,
        kind: config.kind.clone().unwrap_or_else(|| "http".to_string()),
        env: config.env.clone(),
        tmpfs: config.tmpfs.clone(),
        mounts: config
            .mounts
            .iter()
            .map(|mount| BindMount {
                source: normalize_path(&root.join(&mount.source)),
                target: mount.target.clone(),
                read_only: true,
            })
            .collect(),
        health: HealthCheck {
            exec,
            timeout_secs: timeout.unwrap_or(DEFAULT_HEALTH_TIMEOUT_SECS),
        },
    }
}

/// The private network of a hardened job: holder `<container>-net`, services `<container>-<project>-<service>`.
pub fn job_sidecars(
    root: &Path,
    container: &str,
    services: &[(String, String, ServiceConfig)],
    default_pull: PullPolicy,
) -> Option<SidecarSpec> {
    if services.is_empty() {
        return None;
    }
    Some(SidecarSpec {
        holder: sanitize_name(&format!("{container}-net")),
        services: services
            .iter()
            .map(|(project, service, config)| {
                resolve_service(
                    root,
                    project,
                    service,
                    config,
                    sanitize_name(&format!("{container}-{project}-{service}")),
                    default_pull,
                )
            })
            .collect(),
    })
}

/// The `CHERRY_TEST_{PROJECT}_{SERVICE}_*` variables of a service a job reaches at `127.0.0.1:<port>`.
pub fn sidecar_env(service: &SidecarService) -> Vec<(String, String)> {
    service_env(&ServiceEndpoint {
        project: service.project.clone(),
        service: service.service.clone(),
        kind: service.kind.clone(),
        url: (service.kind == "http").then(|| format!("http://127.0.0.1:{}", service.port)),
        host: "127.0.0.1".into(),
        port: service.port,
    })
}

/// The prefix `CHERRY_TEST_{PROJECT}_{SERVICE}_` of a service's variables.
pub fn env_prefix(project: &str, service: &str) -> String {
    format!(
        "CHERRY_TEST_{}_{}_",
        project.to_uppercase(),
        service.to_uppercase()
    )
}

fn push(argv: &mut Vec<String>, option: &str, value: impl Into<String>) {
    argv.push(option.to_string());
    argv.push(value.into());
}

fn push_labels(argv: &mut Vec<String>, labels: &Labels) {
    push(argv, "--label", format!("{RUN_LABEL}={}", labels.run));
    push(argv, "--label", format!("{JOB_LABEL}={}", labels.job));
    push(argv, "--label", format!("{PID_LABEL}={}", labels.owner_pid));
}

/// `docker` arguments that start the holder: the job image running `sleep infinity` with no
/// capabilities on a read-only root, attached to the job's network mode.
pub fn holder_argv(
    holder: &str,
    image: &str,
    pull: PullPolicy,
    network: NetworkMode,
    labels: &Labels,
) -> Vec<String> {
    let mut argv = vec!["run".to_string(), "-d".to_string()];
    push(&mut argv, "--name", holder);
    push_labels(&mut argv, labels);
    push(&mut argv, "--pull", pull.as_str());
    push(&mut argv, "--network", network.as_str());
    if network != NetworkMode::None {
        push(&mut argv, "--add-host", "host.docker.internal:host-gateway");
    }
    push(&mut argv, "--cap-drop", "ALL");
    push(&mut argv, "--security-opt", "no-new-privileges");
    argv.push("--read-only".to_string());
    push(&mut argv, "--entrypoint", "sleep");
    argv.push(image.to_string());
    argv.push("infinity".to_string());
    argv
}

/// `docker` arguments that start one image service.
pub fn service_argv(
    service: &SidecarService,
    network: ServiceNetwork<'_>,
    labels: &Labels,
) -> Vec<String> {
    let mut argv = vec!["run".to_string(), "-d".to_string()];
    push(&mut argv, "--name", service.name.clone());
    push_labels(&mut argv, labels);
    push(
        &mut argv,
        "--label",
        format!("{SERVICE_LABEL}={}/{}", service.project, service.service),
    );
    push(&mut argv, "--pull", service.pull.as_str());
    match network {
        ServiceNetwork::Holder(holder) => {
            push(&mut argv, "--network", format!("container:{holder}"))
        }
        ServiceNetwork::Published => push(&mut argv, "-p", format!("127.0.0.1::{}", service.port)),
    }
    for path in &service.tmpfs {
        push(&mut argv, "--tmpfs", path.clone());
    }
    for mount in &service.mounts {
        push(
            &mut argv,
            "-v",
            format!("{}:{}:ro", mount.source.display(), mount.target),
        );
    }
    for (key, value) in &service.env {
        push(&mut argv, "-e", format!("{key}={value}"));
    }
    argv.push(service.image.clone());
    argv
}

/// `docker` arguments of one health probe: the `exec` command inside the service, or a read of the
/// listening sockets from `probe`, a container in the same network namespace.
pub fn probe_argv(service: &SidecarService, probe: &str) -> Vec<String> {
    let mut argv = vec!["exec".to_string()];
    if service.health.exec.is_empty() {
        argv.push(probe.to_string());
        argv.extend(["cat", "/proc/net/tcp", "/proc/net/tcp6"].map(String::from));
    } else {
        argv.push(service.name.clone());
        argv.extend(service.health.exec.iter().cloned());
    }
    argv
}

/// Whether `/proc/net/tcp` or `/proc/net/tcp6` content shows a socket listening on `port`.
pub fn listening(proc_net_tcp: &str, port: u16) -> bool {
    let wanted = format!("{port:04X}");
    proc_net_tcp.lines().any(|line| {
        let mut fields = line.split_whitespace();
        let local = fields.nth(1);
        let state = fields.nth(1);
        state == Some("0A")
            && local
                .and_then(|address| address.rsplit_once(':'))
                .is_some_and(|(_, hex)| hex.eq_ignore_ascii_case(&wanted))
    })
}

/// `docker` arguments that remove containers with their anonymous volumes.
pub fn remove_argv(names: &[String]) -> Vec<String> {
    let mut argv = vec!["rm".to_string(), "-f".to_string(), "-v".to_string()];
    argv.extend(names.iter().cloned());
    argv
}

/// Every container a job's private network uses: the holder first, then the services.
pub fn container_names(spec: &SidecarSpec) -> Vec<String> {
    std::iter::once(spec.holder.clone())
        .chain(spec.services.iter().map(|service| service.name.clone()))
        .collect()
}

/// The file a service's `docker logs` output is saved to.
pub fn log_path(logs: &Path, service: &SidecarService) -> PathBuf {
    logs.join(format!(
        "sidecar-{}.log",
        sanitize_name(&format!("{}-{}", service.project, service.service))
    ))
}

fn docker(env: &Environment, args: &[String]) -> Result<Output> {
    command_in("docker", env)
        .args(args)
        .stdin(std::process::Stdio::null())
        .output()
        .context("start `docker`")
}

fn stderr_line(output: &Output) -> String {
    let stderr = String::from_utf8_lossy(&output.stderr);
    let stdout = String::from_utf8_lossy(&output.stdout);
    let text = if stderr.trim().is_empty() {
        stdout
    } else {
        stderr
    };
    text.trim().lines().last().unwrap_or("").to_string()
}

fn start_container(env: &Environment, argv: &[String], what: &str) -> Result<()> {
    let output = docker(env, argv)?;
    if !output.status.success() {
        bail!("could not start {what}: {}", stderr_line(&output));
    }
    Ok(())
}

/// Polls `service` until its health check passes, it stops, its timeout expires or `cancelled` says so.
pub fn wait_healthy(
    env: &Environment,
    service: &SidecarService,
    probe: &str,
    logs: &Path,
    cancelled: &dyn Fn() -> bool,
) -> Result<()> {
    let label = format!(
        "image service `{}/{}` ({})",
        service.project, service.service, service.image
    );
    let deadline = Instant::now() + Duration::from_secs(service.health.timeout_secs);
    loop {
        if cancelled() {
            bail!("cancelled while waiting for {label}");
        }
        let output = docker(env, &probe_argv(service, probe))?;
        let healthy = if service.health.exec.is_empty() {
            listening(&String::from_utf8_lossy(&output.stdout), service.port)
        } else {
            output.status.success()
        };
        if healthy {
            return Ok(());
        }
        let state = docker(
            env,
            &[
                "inspect".to_string(),
                "--format".to_string(),
                "{{.State.Status}}".to_string(),
                service.name.clone(),
            ],
        )?;
        let status = String::from_utf8_lossy(&state.stdout).trim().to_string();
        if matches!(status.as_str(), "exited" | "dead") || !state.status.success() {
            bail!(
                "{label} stopped before it became healthy; see {}",
                log_path(logs, service).display()
            );
        }
        if Instant::now() >= deadline {
            let detail = if service.health.exec.is_empty() {
                format!("nothing listens on 127.0.0.1:{}", service.port)
            } else {
                let last = stderr_line(&output);
                if last.is_empty() {
                    format!("`{}` keeps failing", service.health.exec.join(" "))
                } else {
                    format!("`{}` keeps failing: {last}", service.health.exec.join(" "))
                }
            };
            bail!(
                "{label} did not become healthy within {}s: {detail}; see {}",
                service.health.timeout_secs,
                log_path(logs, service).display()
            );
        }
        std::thread::sleep(PROBE_INTERVAL);
    }
}

/// Saves each service's `docker logs` output beside the job's other logs; a service that never started is skipped.
pub fn save_logs(env: &Environment, services: &[SidecarService], logs: &Path) {
    for service in services {
        let Ok(output) = docker(env, &["logs".to_string(), service.name.clone()]) else {
            continue;
        };
        if !output.status.success() {
            continue;
        }
        let mut text = output.stdout;
        text.extend_from_slice(&output.stderr);
        let path = log_path(logs, service);
        if let Err(error) = std::fs::create_dir_all(logs).and_then(|()| std::fs::write(&path, text))
        {
            eprintln!("warning: could not save {}: {error}", path.display());
        }
    }
}

/// Removes containers and their anonymous volumes; containers that are already gone are not an error.
pub fn remove(env: &Environment, names: &[String]) -> Result<()> {
    if names.is_empty() {
        return Ok(());
    }
    let output = docker(env, &remove_argv(names))?;
    if !output.status.success() {
        let message = String::from_utf8_lossy(&output.stderr);
        let unexpected: Vec<&str> = message
            .lines()
            .filter(|line| !line.trim().is_empty() && !line.contains("No such container"))
            .collect();
        if !unexpected.is_empty() {
            bail!("docker rm failed: {}", unexpected.join("; "));
        }
    }
    Ok(())
}

/// Starts a job's private network: the holder, then every service, then waits for each to be healthy.
///
/// On failure the logs are saved, everything started is removed, and the error explains which service failed.
#[allow(clippy::too_many_arguments)]
pub fn start_job_network(
    env: &Environment,
    spec: &SidecarSpec,
    image: &str,
    pull: PullPolicy,
    network: NetworkMode,
    labels: &Labels,
    logs: &Path,
    cancelled: &dyn Fn() -> bool,
) -> Result<()> {
    let started = (|| -> Result<()> {
        start_container(
            env,
            &holder_argv(&spec.holder, image, pull, network, labels),
            &format!("the network holder from `{image}`"),
        )?;
        for service in &spec.services {
            start_container(
                env,
                &service_argv(service, ServiceNetwork::Holder(&spec.holder), labels),
                &format!(
                    "image service `{}/{}` ({})",
                    service.project, service.service, service.image
                ),
            )?;
        }
        for service in &spec.services {
            wait_healthy(env, service, &spec.holder, logs, cancelled)?;
        }
        Ok(())
    })();
    if let Err(error) = started {
        save_logs(env, &spec.services, logs);
        if let Err(cleanup) = remove(env, &container_names(spec)) {
            return Err(error.context(format!("cleanup also failed: {cleanup:#}")));
        }
        return Err(error);
    }
    Ok(())
}

/// Saves the services' logs and removes the job's private network.
pub fn stop_job_network(env: &Environment, spec: &SidecarSpec, logs: &Path) -> Result<()> {
    save_logs(env, &spec.services, logs);
    remove(env, &container_names(spec))
}

/// Starts one image service with its port published on `127.0.0.1` and returns its endpoint.
///
/// A container left with the same name by an earlier process is removed first.
pub fn start_published(
    env: &Environment,
    service: &SidecarService,
    labels: &Labels,
    logs: &Path,
) -> Result<ServiceEndpoint> {
    remove(env, std::slice::from_ref(&service.name))?;
    let started = (|| -> Result<ServiceEndpoint> {
        start_container(
            env,
            &service_argv(service, ServiceNetwork::Published, labels),
            &format!(
                "image service `{}/{}` ({})",
                service.project, service.service, service.image
            ),
        )?;
        wait_healthy(env, service, &service.name, logs, &|| false)?;
        let output = docker(
            env,
            &[
                "port".to_string(),
                service.name.clone(),
                format!("{}/tcp", service.port),
            ],
        )?;
        if !output.status.success() {
            bail!(
                "docker port {} {} failed: {}",
                service.name,
                service.port,
                stderr_line(&output)
            );
        }
        let mapped = String::from_utf8_lossy(&output.stdout);
        let first = mapped.lines().next().unwrap_or("").trim();
        let (host, port) = first
            .rsplit_once(':')
            .ok_or_else(|| anyhow::anyhow!("invalid docker port output: {first}"))?;
        let port: u16 = port.parse().context("mapped port")?;
        let host = host.replace("0.0.0.0", "127.0.0.1");
        Ok(ServiceEndpoint {
            project: service.project.clone(),
            service: service.service.clone(),
            kind: service.kind.clone(),
            url: (service.kind == "http").then(|| format!("http://{host}:{port}")),
            host,
            port,
        })
    })();
    if started.is_err() {
        save_logs(env, std::slice::from_ref(service), logs);
        remove(env, std::slice::from_ref(&service.name))?;
    }
    started
}

/// Whether `name` is a valid environment variable name.
pub fn is_env_name(value: &str) -> bool {
    value.starts_with(|c: char| c.is_ascii_alphabetic() || c == '_')
        && value
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'_')
}

#[cfg(test)]
mod tests {
    use super::*;

    fn postgres() -> SidecarService {
        SidecarService {
            project: "shop".into(),
            service: "db".into(),
            name: "ct-r1-shop-api-integration-shop-db".into(),
            image: "postgres:16-alpine".into(),
            pull: PullPolicy::Never,
            port: 5432,
            kind: "postgres".into(),
            env: BTreeMap::from([("POSTGRES_PASSWORD".to_string(), "test".to_string())]),
            tmpfs: vec!["/var/lib/postgresql/data".into()],
            mounts: vec![BindMount {
                source: PathBuf::from("/repo/fixtures/sql"),
                target: "/docker-entrypoint-initdb.d".into(),
                read_only: true,
            }],
            health: HealthCheck {
                exec: vec!["pg_isready".into(), "-h".into(), "127.0.0.1".into()],
                timeout_secs: 60,
            },
        }
    }

    /// A `docker` stand-in on `PATH` that appends each call to `docker.log` and answers like a
    /// service whose health check fails and which then stops.
    #[cfg(unix)]
    fn fake_docker(dir: &Path) -> Environment {
        use std::os::unix::fs::PermissionsExt;
        let script = dir.join("docker");
        std::fs::write(
            &script,
            "#!/bin/sh\necho \"$*\" >> \"$FAKE_DOCKER_LOG\"\ncase \"$1\" in\n  run) echo started ;;\n  exec) exit 1 ;;\n  inspect) echo exited ;;\n  logs) echo \"FATAL: role does not exist\" ;;\nesac\nexit 0\n",
        )
        .unwrap();
        std::fs::set_permissions(&script, std::fs::Permissions::from_mode(0o755)).unwrap();
        Environment::from([
            (
                "PATH".to_string(),
                format!("{}:/bin:/usr/bin", dir.display()),
            ),
            (
                "FAKE_DOCKER_LOG".to_string(),
                dir.join("docker.log").display().to_string(),
            ),
        ])
    }

    fn job_network() -> SidecarSpec {
        SidecarSpec {
            holder: "ct-r1-shop-api-integration-net".into(),
            services: vec![postgres()],
        }
    }

    fn labels() -> Labels {
        Labels {
            run: "r1".into(),
            job: "shop/api/integration".into(),
            owner_pid: 7,
        }
    }

    #[cfg(unix)]
    #[test]
    fn a_service_that_stops_fails_the_job_network_and_is_removed() {
        let dir = tempfile::tempdir().unwrap();
        let env = fake_docker(dir.path());
        let logs = dir.path().join("output");
        let error = start_job_network(
            &env,
            &job_network(),
            "eclipse-temurin:25-jdk",
            PullPolicy::Never,
            NetworkMode::None,
            &labels(),
            &logs,
            &|| false,
        )
        .unwrap_err();
        assert_eq!(
            format!("{error:#}"),
            format!(
                "image service `shop/db` (postgres:16-alpine) stopped before it became healthy; see {}",
                logs.join("sidecar-shop-db.log").display()
            )
        );
        assert_eq!(
            std::fs::read_to_string(logs.join("sidecar-shop-db.log")).unwrap(),
            "FATAL: role does not exist\n"
        );
        let calls = std::fs::read_to_string(dir.path().join("docker.log")).unwrap();
        let calls: Vec<&str> = calls.lines().collect();
        assert_eq!(calls.len(), 6, "{calls:?}");
        assert!(calls[0].starts_with("run -d --name ct-r1-shop-api-integration-net "));
        assert!(calls[1].starts_with("run -d --name ct-r1-shop-api-integration-shop-db "));
        assert_eq!(
            calls[2],
            "exec ct-r1-shop-api-integration-shop-db pg_isready -h 127.0.0.1"
        );
        assert_eq!(
            calls[3],
            "inspect --format {{.State.Status}} ct-r1-shop-api-integration-shop-db"
        );
        assert_eq!(calls[4], "logs ct-r1-shop-api-integration-shop-db");
        assert_eq!(
            calls[5],
            "rm -f -v ct-r1-shop-api-integration-net ct-r1-shop-api-integration-shop-db"
        );
    }

    #[cfg(unix)]
    #[test]
    fn stopping_the_job_network_saves_logs_and_removes_every_container() {
        let dir = tempfile::tempdir().unwrap();
        let env = fake_docker(dir.path());
        let logs = dir.path().join("output");
        stop_job_network(&env, &job_network(), &logs).unwrap();
        assert!(logs.join("sidecar-shop-db.log").is_file());
        let calls = std::fs::read_to_string(dir.path().join("docker.log")).unwrap();
        assert_eq!(
            calls,
            "logs ct-r1-shop-api-integration-shop-db\nrm -f -v ct-r1-shop-api-integration-net ct-r1-shop-api-integration-shop-db\n"
        );
        let cancelled = start_job_network(
            &env,
            &job_network(),
            "eclipse-temurin:25-jdk",
            PullPolicy::Never,
            NetworkMode::None,
            &labels(),
            &logs,
            &|| true,
        )
        .unwrap_err();
        assert_eq!(
            format!("{cancelled:#}"),
            "cancelled while waiting for image service `shop/db` (postgres:16-alpine)"
        );
    }

    #[test]
    fn listening_reads_proc_net_tcp() {
        let tcp = "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode\n   0: 0100007F:1538 00000000:0000 0A 00000000:00000000 00:00000000 00000000    70        0 1 1 0000000000000000 100 0 0 10 0\n   1: 0100007F:9C40 0100007F:1538 01 00000000:00000000 00:00000000 00000000     0        0 2 1 0000000000000000 20 4 30 10 -1\n";
        assert!(listening(tcp, 5432));
        assert!(!listening(tcp, 40000));
        assert!(!listening(tcp, 80));
        let tcp6 = "  sl  local_address                         remote_address                        st\n   0: 00000000000000000000000000000000:0050 00000000000000000000000000000000:0000 0A 00000000:00000000\n";
        assert!(listening(tcp6, 80));
        assert!(!listening("", 80));
    }

    #[test]
    fn provisioned_scopes_come_from_the_runner_variable() {
        let mut env = Environment::new();
        assert!(!provisioned(&env, "db"));
        env.insert(SCOPE_PROVISIONED_ENV.into(), "checkout,db".into());
        assert!(provisioned(&env, "db"));
        assert!(provisioned(&env, "checkout"));
        assert!(!provisioned(&env, "d"));
    }

    #[test]
    fn sidecar_env_points_at_loopback() {
        let mut db = postgres();
        assert_eq!(
            sidecar_env(&db),
            vec![
                (
                    "CHERRY_TEST_SHOP_DB_HOST".to_string(),
                    "127.0.0.1".to_string()
                ),
                ("CHERRY_TEST_SHOP_DB_PORT".to_string(), "5432".to_string()),
            ]
        );
        db.kind = "http".into();
        db.port = 8080;
        assert_eq!(
            sidecar_env(&db)[2],
            (
                "CHERRY_TEST_SHOP_DB_URL".to_string(),
                "http://127.0.0.1:8080".to_string()
            )
        );
        assert_eq!(env_prefix("shop", "db"), "CHERRY_TEST_SHOP_DB_");
    }

    #[test]
    fn probes_exec_in_the_service_or_read_sockets_from_the_holder() {
        let mut db = postgres();
        assert_eq!(
            probe_argv(&db, "holder"),
            [
                "exec",
                "ct-r1-shop-api-integration-shop-db",
                "pg_isready",
                "-h",
                "127.0.0.1"
            ]
        );
        db.health.exec.clear();
        assert_eq!(
            probe_argv(&db, "holder"),
            ["exec", "holder", "cat", "/proc/net/tcp", "/proc/net/tcp6"]
        );
        assert_eq!(
            remove_argv(&["a".into(), "b".into()]),
            ["rm", "-f", "-v", "a", "b"]
        );
        assert_eq!(
            log_path(Path::new("/out"), &db),
            Path::new("/out/sidecar-shop-db.log")
        );
    }
}
