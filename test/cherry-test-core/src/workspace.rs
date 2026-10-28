use std::collections::{BTreeMap, BTreeSet, HashMap};
use std::path::{Path, PathBuf};
use std::process::Command;
use std::sync::Mutex;

use anyhow::{bail, Context, Result};
use once_cell::sync::Lazy;

use crate::config::{ServiceConfig, WorkspaceConfig};
use crate::scope::{is_none, resolve_projects, service_filter};
use crate::sidecar;

pub const COMPOSE_PROJECT: &str = "cherry-test";

/// An explicit process environment: the variables a child process receives, and nothing else.
pub type Environment = BTreeMap<String, String>;

/// Scopes the test process booted through [`boot_scope`]; a Cherry Test run keeps its own [`ScopeState`].
static PROCESS_SCOPES: Lazy<Mutex<ScopeState>> = Lazy::new(|| Mutex::new(ScopeState::default()));

#[derive(Debug, Clone)]
pub struct ServiceEndpoint {
    pub project: String,
    pub service: String,
    pub kind: String,
    pub url: Option<String>,
    pub host: String,
    pub port: u16,
}

pub type ServiceRegistry = HashMap<(String, String), ServiceEndpoint>;

/// The scopes one owner has started, the Compose files they used, in start order, and the image
/// service containers it started.
#[derive(Debug, Clone, Default)]
pub struct ScopeState {
    booted: BTreeSet<String>,
    published: BTreeSet<String>,
    compose_files: Vec<PathBuf>,
    containers: Vec<String>,
}

impl ScopeState {
    pub fn is_booted(&self, scope: &str) -> bool {
        self.booted.contains(scope)
    }

    /// Whether the scope's image services run with published ports for this owner.
    pub fn is_published(&self, scope: &str) -> bool {
        self.published.contains(scope)
    }

    /// Image service containers this owner started and has not removed yet.
    pub fn containers(&self) -> &[String] {
        &self.containers
    }

    pub fn compose_files(&self) -> &[PathBuf] {
        &self.compose_files
    }

    fn remember_files(&mut self, files: &[PathBuf]) {
        for file in files {
            if !self.compose_files.contains(file) {
                self.compose_files.push(file.clone());
            }
        }
    }

    fn clear(&mut self) {
        self.booted.clear();
        self.published.clear();
        self.compose_files.clear();
        self.containers.clear();
    }
}

/// Endpoints of one booted scope and the `CHERRY_TEST_{PROJECT}_{SERVICE}_*` variables that describe them.
#[derive(Debug, Clone, Default)]
pub struct ScopeBoot {
    pub registry: ServiceRegistry,
    pub exports: Vec<(String, String)>,
}

/// The current process environment as an [`Environment`]; variables that are not valid Unicode are left out.
pub fn process_environment() -> Environment {
    std::env::vars_os()
        .filter_map(|(key, value)| Some((key.into_string().ok()?, value.into_string().ok()?)))
        .collect()
}

pub(crate) fn command_in(program: &str, env: &Environment) -> Command {
    let mut command = Command::new(program);
    command.env_clear().envs(env);
    command
}

pub fn workspace_root() -> Result<std::path::PathBuf> {
    workspace_root_in(&process_environment())
}

/// The configuration root named by `CHERRY_TEST_WORKSPACE_ROOT` in `env`, or the one found from the current directory.
pub fn workspace_root_in(env: &Environment) -> Result<std::path::PathBuf> {
    if let Some(root) = env.get("CHERRY_TEST_WORKSPACE_ROOT") {
        if !root.is_empty() {
            let path = std::path::PathBuf::from(root);
            if path.is_dir() {
                return Ok(path);
            }
        }
    }
    crate::repo_root::resolve_repo_root(None)
}

pub fn docker_available() -> bool {
    Command::new("docker")
        .args(["info"])
        .output()
        .map(|o| o.status.success())
        .unwrap_or(false)
}

/// Like [`docker_available`], with `docker info` started in `env`.
pub fn docker_available_in(env: &Environment) -> bool {
    command_in("docker", env)
        .args(["info"])
        .output()
        .map(|o| o.status.success())
        .unwrap_or(false)
}

pub fn compose_project() -> String {
    compose_project_in(&process_environment())
}

fn compose_project_in(env: &Environment) -> String {
    env.get("CHERRY_TEST_COMPOSE_PROJECT")
        .cloned()
        .unwrap_or_else(|| COMPOSE_PROJECT.to_string())
}

fn skip_docker(env: &Environment) -> bool {
    env.get("CHERRY_TEST_SKIP_DOCKER")
        .map(|v| v == "1" || v.eq_ignore_ascii_case("true"))
        .unwrap_or(false)
}

pub fn boot_workspace() -> Result<ServiceRegistry> {
    boot_scope("global")
}

/// Boots `scope` for the current process and exports its endpoints to the process environment.
pub fn boot_scope(scope: &str) -> Result<ServiceRegistry> {
    let env = process_environment();
    let mut state = PROCESS_SCOPES
        .lock()
        .map_err(|_| anyhow::anyhow!("compose state lock poisoned"))?;
    let boot = boot_scope_in(scope, &mut state, &env)?;
    for (key, value) in &boot.exports {
        std::env::set_var(key, value);
    }
    Ok(boot.registry)
}

/// Boots `scope` once for the owner of `state`; Docker runs in `env` and nothing global changes.
///
/// Compose services start with `docker compose up`; image services start with their ports
/// published on `127.0.0.1`. Each `docker compose port` call sees the variables of the services
/// resolved before it, as the process-wide [`boot_scope`] always did. A scope the runner lists in
/// `CHERRY_TEST_SCOPE_PROVISIONED` is read from `env` without starting anything.
pub fn boot_scope_in(scope: &str, state: &mut ScopeState, env: &Environment) -> Result<ScopeBoot> {
    boot_in(scope, state, env, true)
}

/// Like [`boot_scope_in`] for a job that runs the scope's image services in its own network:
/// only the Compose services start.
pub fn boot_compose_in(
    scope: &str,
    state: &mut ScopeState,
    env: &Environment,
) -> Result<ScopeBoot> {
    boot_in(scope, state, env, false)
}

fn boot_in(
    scope: &str,
    state: &mut ScopeState,
    env: &Environment,
    images: bool,
) -> Result<ScopeBoot> {
    if is_none(scope) {
        return Ok(ScopeBoot {
            registry: load_registry_from(env, None)?,
            exports: Vec::new(),
        });
    }

    let done = state.is_booted(scope) && (!images || state.is_published(scope));
    if skip_docker(env) || sidecar::provisioned(env, scope) || done {
        return Ok(ScopeBoot {
            registry: load_registry_from(env, Some(scope))?,
            exports: Vec::new(),
        });
    }

    if !docker_available_in(env) {
        bail!(
            "docker is not available; install Docker or set scope=none / CHERRY_TEST_SKIP_DOCKER=1"
        );
    }

    let root = workspace_root_in(env)?;
    let config = WorkspaceConfig::load(&root)?;
    let project_names = resolve_projects(&config, scope)?;
    let image_services = sidecar::image_services(&config, &[scope.to_string()])?;

    let mut registry = HashMap::new();
    let mut exports = Vec::new();
    let mut env_snapshot = serde_json::Map::new();
    let mut port_env = env.clone();
    let mut compose_ran = false;

    if !state.is_booted(scope) {
        let mut selected = Vec::new();
        for project_name in &project_names {
            if let Some(project) = config.projects.get(project_name) {
                for (name, service) in &project.services {
                    if !service.is_image() && service_filter(&config, scope, project_name, name) {
                        selected.push(service.service.clone());
                    }
                }
            }
        }
        if !selected.is_empty() || image_services.is_empty() {
            let compose_files = config.compose_files_for_projects(&project_names)?;
            let compose_project = compose_project_in(env);

            let mut cmd = command_in("docker", env);
            cmd.arg("compose").arg("-p").arg(&compose_project);
            for file in &compose_files {
                cmd.arg("-f").arg(file);
            }
            cmd.args(["up", "-d", "--build", "--wait"]);
            cmd.args(selected);
            cmd.current_dir(&root);

            state.remember_files(&compose_files);
            let status = cmd.status().context("docker compose up")?;
            if !status.success() {
                bail!("docker compose up failed for scope `{scope}`");
            }

            for project_name in &project_names {
                let Some(project) = config.projects.get(project_name) else {
                    continue;
                };
                for (service_name, service) in &project.services {
                    if service.is_image()
                        || !service_filter(&config, scope, project_name, service_name)
                    {
                        continue;
                    }
                    let endpoint = resolve_endpoint(
                        &root,
                        &compose_files,
                        &compose_project,
                        &port_env,
                        project_name,
                        service_name,
                        service,
                    )?;
                    for (key, value) in service_env(&endpoint) {
                        port_env.insert(key.clone(), value.clone());
                        exports.push((key, value));
                    }
                    snapshot_endpoint(&mut env_snapshot, &endpoint);
                    registry.insert((project_name.clone(), service_name.clone()), endpoint);
                }
            }
            state.remember_files(&compose_files);
            compose_ran = true;
        }
        state.booted.insert(scope.to_string());
    }

    let mut images_ran = false;
    if images && !state.is_published(scope) {
        let labels = sidecar::Labels {
            run: env
                .get("CHERRY_TEST_RUN_ID")
                .cloned()
                .unwrap_or_else(|| compose_project_in(env)),
            job: format!("scope/{scope}"),
            owner_pid: env
                .get(cherry_test_common::container::OWNER_PID_ENV)
                .and_then(|pid| pid.parse().ok())
                .unwrap_or_else(std::process::id),
        };
        let logs = services_log_dir(&root, env);
        for (project_name, service_name, service) in &image_services {
            let resolved = sidecar::resolve_service(
                &root,
                project_name,
                service_name,
                service,
                cherry_test_common::container::sanitize_name(&format!(
                    "ct-{}-{project_name}-{service_name}",
                    compose_project_in(env)
                )),
                cherry_test_common::container::PullPolicy::Never,
            );
            let endpoint = sidecar::start_published(env, &resolved, &labels, &logs)?;
            state.containers.push(resolved.name.clone());
            for (key, value) in service_env(&endpoint) {
                port_env.insert(key.clone(), value.clone());
                exports.push((key, value));
            }
            snapshot_endpoint(&mut env_snapshot, &endpoint);
            registry.insert((project_name.clone(), service_name.clone()), endpoint);
            images_ran = true;
        }
        state.published.insert(scope.to_string());
    }

    if compose_ran || images_ran {
        write_env_json(&root, env, &env_snapshot)?;
    }
    if !compose_ran {
        registry = load_registry_from(&port_env, Some(scope))?;
    }

    Ok(ScopeBoot { registry, exports })
}

fn snapshot_endpoint(
    snapshot: &mut serde_json::Map<String, serde_json::Value>,
    endpoint: &ServiceEndpoint,
) {
    snapshot.insert(
        format!("{}.{}", endpoint.project, endpoint.service),
        serde_json::json!({
            "kind": endpoint.kind,
            "url": endpoint.url,
            "host": endpoint.host,
            "port": endpoint.port,
        }),
    );
}

/// Where the logs of an image service that failed to start are saved: `services/` in the run directory.
fn services_log_dir(root: &Path, env: &Environment) -> PathBuf {
    match env.get("CHERRY_TEST_RUN_DIR") {
        Some(run_dir) => PathBuf::from(run_dir).join("services"),
        None => root.join("target/cherry-test/services"),
    }
}

pub fn teardown_workspace() -> Result<()> {
    teardown_scopes()
}

/// Stops every scope the current process booted through [`boot_scope`].
pub fn teardown_scopes() -> Result<()> {
    let env = process_environment();
    let mut state = PROCESS_SCOPES
        .lock()
        .map_err(|_| anyhow::anyhow!("compose state lock poisoned"))?;
    teardown_scopes_in(&mut state, &env)
}

/// Stops every scope recorded in `state`: removes its image service containers, then runs one
/// `docker compose down -v` started in `env`.
pub fn teardown_scopes_in(state: &mut ScopeState, env: &Environment) -> Result<()> {
    if skip_docker(env) {
        state.clear();
        return Ok(());
    }

    let containers = std::mem::take(&mut state.containers);
    let removed = sidecar::remove(env, &containers);
    if state.compose_files.is_empty() {
        state.clear();
        return removed;
    }

    let root = workspace_root_in(env)?;
    let mut cmd = command_in("docker", env);
    cmd.arg("compose").arg("-p").arg(compose_project_in(env));
    for file in &state.compose_files {
        cmd.arg("-f").arg(file);
    }
    cmd.args(["down", "-v"]);
    cmd.current_dir(&root);
    let status = cmd.status().context("docker compose down")?;
    if !status.success() {
        bail!("Docker teardown failed");
    }

    state.clear();
    removed
}

fn resolve_endpoint(
    root: &Path,
    compose_files: &[std::path::PathBuf],
    compose_project: &str,
    env: &Environment,
    project: &str,
    service_name: &str,
    service: &ServiceConfig,
) -> Result<ServiceEndpoint> {
    let kind = service.kind.clone().unwrap_or_else(|| "http".to_string());
    let mapped = compose_port(
        root,
        compose_files,
        compose_project,
        env,
        &service.service,
        service.port,
    )?;
    let (host, mapped_port_str) = mapped
        .rsplit_once(':')
        .ok_or_else(|| anyhow::anyhow!("invalid docker compose port output: {mapped}"))?;
    let host = host.replace("0.0.0.0", "127.0.0.1");
    let mapped_port: u16 = mapped_port_str.parse().context("mapped port")?;

    let url = if kind == "http" {
        Some(format!("http://{host}:{mapped_port}"))
    } else {
        None
    };

    Ok(ServiceEndpoint {
        project: project.to_string(),
        service: service_name.to_string(),
        kind,
        url,
        host,
        port: mapped_port,
    })
}

fn compose_port(
    root: &Path,
    compose_files: &[std::path::PathBuf],
    compose_project: &str,
    env: &Environment,
    service: &str,
    port: u16,
) -> Result<String> {
    let mut cmd = command_in("docker", env);
    cmd.arg("compose").arg("-p").arg(compose_project);
    for file in compose_files {
        cmd.arg("-f").arg(file);
    }
    cmd.args(["port", service, &port.to_string()]);
    cmd.current_dir(root);

    let output = cmd.output().context("docker compose port")?;
    if !output.status.success() {
        bail!(
            "docker compose port {service} {port} failed: {}",
            String::from_utf8_lossy(&output.stderr)
        );
    }
    Ok(String::from_utf8(output.stdout)?.trim().to_string())
}

/// The `CHERRY_TEST_{PROJECT}_{SERVICE}_HOST`, `_PORT` and `_URL` variables of one endpoint, in that order.
pub fn service_env(endpoint: &ServiceEndpoint) -> Vec<(String, String)> {
    let prefix = format!(
        "CHERRY_TEST_{}_{}",
        endpoint.project.to_uppercase(),
        endpoint.service.to_uppercase()
    );
    let mut vars = vec![
        (format!("{prefix}_HOST"), endpoint.host.clone()),
        (format!("{prefix}_PORT"), endpoint.port.to_string()),
    ];
    if let Some(url) = &endpoint.url {
        vars.push((format!("{prefix}_URL"), url.clone()));
    }
    vars
}

fn write_env_json(
    root: &Path,
    env: &Environment,
    snapshot: &serde_json::Map<String, serde_json::Value>,
) -> Result<()> {
    let path = if let Some(run_dir) = env.get("CHERRY_TEST_RUN_DIR") {
        PathBuf::from(run_dir).join("env.json")
    } else {
        let dir = root.join("target/cherry-test");
        std::fs::create_dir_all(&dir)?;
        dir.join("env.json")
    };
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    std::fs::write(path, serde_json::to_string_pretty(snapshot)?)?;
    Ok(())
}

fn load_registry_from(env: &Environment, scope: Option<&str>) -> Result<ServiceRegistry> {
    let root = workspace_root_in(env)?;
    let config = WorkspaceConfig::load(&root)?;
    let project_names = match scope {
        Some(s) if !is_none(s) => resolve_projects(&config, s)?,
        _ => config.projects.keys().cloned().collect(),
    };

    let mut registry = HashMap::new();
    for project_name in project_names {
        let Some(project) = config.projects.get(&project_name) else {
            continue;
        };
        for (service_name, service) in &project.services {
            if let Some(s) = scope {
                if !service_filter(&config, s, &project_name, service_name) {
                    continue;
                }
            }
            let prefix = format!(
                "CHERRY_TEST_{}_{}",
                project_name.to_uppercase(),
                service_name.to_uppercase()
            );
            let url = env.get(&format!("{prefix}_URL")).cloned();
            let host = env
                .get(&format!("{prefix}_HOST"))
                .cloned()
                .unwrap_or_else(|| "127.0.0.1".into());
            let port = env
                .get(&format!("{prefix}_PORT"))
                .and_then(|p| p.parse().ok())
                .unwrap_or(service.port);
            let kind = service.kind.clone().unwrap_or_else(|| "http".to_string());

            registry.insert(
                (project_name.clone(), service_name.clone()),
                ServiceEndpoint {
                    project: project_name.clone(),
                    service: service_name.clone(),
                    kind,
                    url,
                    host,
                    port,
                },
            );
        }
    }

    Ok(registry)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn endpoint(url: Option<&str>) -> ServiceEndpoint {
        ServiceEndpoint {
            project: "mini".into(),
            service: "api".into(),
            kind: "http".into(),
            url: url.map(str::to_string),
            host: "127.0.0.1".into(),
            port: 49152,
        }
    }

    #[test]
    fn service_env_names_host_port_and_url() {
        assert_eq!(
            service_env(&endpoint(Some("http://127.0.0.1:49152"))),
            vec![
                (
                    "CHERRY_TEST_MINI_API_HOST".to_string(),
                    "127.0.0.1".to_string()
                ),
                ("CHERRY_TEST_MINI_API_PORT".to_string(), "49152".to_string()),
                (
                    "CHERRY_TEST_MINI_API_URL".to_string(),
                    "http://127.0.0.1:49152".to_string()
                ),
            ]
        );
        assert_eq!(service_env(&endpoint(None)).len(), 2);
    }

    #[test]
    fn provisioned_scope_reads_endpoints_without_docker() {
        let root = tempfile::tempdir().unwrap();
        std::fs::write(
            root.path().join("cherry-test.yaml"),
            "projects:\n  shop:\n    services:\n      db:\n        image: postgres:16-alpine\n        port: 5432\n        kind: postgres\nscopes:\n  checkout:\n    projects: [shop]\n  audit:\n    projects: [shop]\n",
        )
        .unwrap();
        let mut env = Environment::new();
        env.insert(
            "CHERRY_TEST_WORKSPACE_ROOT".into(),
            root.path().to_string_lossy().into(),
        );
        env.insert(
            "PATH".into(),
            root.path().join("no-docker").to_string_lossy().into(),
        );
        env.insert(
            crate::sidecar::SCOPE_PROVISIONED_ENV.into(),
            "checkout".into(),
        );
        env.insert("CHERRY_TEST_SHOP_DB_HOST".into(), "127.0.0.1".into());
        env.insert("CHERRY_TEST_SHOP_DB_PORT".into(), "5432".into());
        let mut state = ScopeState::default();
        let boot = boot_scope_in("checkout", &mut state, &env).unwrap();
        let db = &boot.registry[&("shop".to_string(), "db".to_string())];
        assert_eq!(
            (db.host.as_str(), db.port, db.kind.as_str()),
            ("127.0.0.1", 5432, "postgres")
        );
        assert_eq!(db.url, None);
        assert!(boot.exports.is_empty());
        assert!(!state.is_booted("checkout"));
        let error = boot_scope_in("audit", &mut state, &env).unwrap_err();
        assert!(
            error.to_string().starts_with("docker is not available"),
            "{error}"
        );
        teardown_scopes_in(&mut state, &env).unwrap();
    }

    #[test]
    fn skipped_docker_reuses_supplied_endpoints_without_global_state() {
        let root = tempfile::tempdir().unwrap();
        std::fs::write(
            root.path().join("cherry-test.yaml"),
            "projects:\n  mini:\n    services:\n      api:\n        service: api\n        port: 3000\n",
        )
        .unwrap();
        let mut env = Environment::new();
        env.insert(
            "CHERRY_TEST_WORKSPACE_ROOT".into(),
            root.path().to_string_lossy().into(),
        );
        env.insert("CHERRY_TEST_SKIP_DOCKER".into(), "1".into());
        env.insert("CHERRY_TEST_MINI_API_PORT".into(), "4100".into());
        let mut state = ScopeState::default();
        let boot = boot_scope_in("mini", &mut state, &env).unwrap();
        let api = &boot.registry[&("mini".to_string(), "api".to_string())];
        assert_eq!(api.port, 4100);
        assert!(boot.exports.is_empty());
        assert!(!state.is_booted("mini"));
        teardown_scopes_in(&mut state, &env).unwrap();
        assert!(state.compose_files().is_empty());
    }
}
