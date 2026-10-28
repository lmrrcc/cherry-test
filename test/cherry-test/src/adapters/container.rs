//! Hardened container execution: resolves the `container:` block for one package phase,
//! builds the `docker run` command line, and removes containers when runs end or die.

use std::path::{Component, Path, PathBuf};
use std::process::{Command, Stdio};

use anyhow::{bail, Context, Result};
use cherry_test_common::adapter::{AdapterRequest, ExecutionMode};
use cherry_test_common::container::{
    BindMount, CacheVolume, ContainerConfig, ContainerSpec, NetworkMode, SeedMode, WorkspaceAccess,
    WritableMount, DEFAULT_TIMEOUT_SECS,
};
use serde::Deserialize;

use super::process;

/// Label that carries the run id of every container Cherry Test starts.
pub const RUN_LABEL: &str = "cherry-test.run";
/// Label that carries the `project/package/phase` key of the job.
pub const JOB_LABEL: &str = "cherry-test.job";
/// Label that carries the process id of the Cherry Test run that owns the container.
pub const PID_LABEL: &str = "cherry-test.pid";
/// Environment variable that passes the owning run's process id to adapter processes.
pub const OWNER_PID_ENV: &str = "CHERRY_TEST_OWNER_PID";

/// Host-side facts for one container invocation that are not part of the resolved spec.
#[derive(Debug, Clone, Default, Deserialize, PartialEq, Eq)]
pub struct ContainerCall {
    pub program: String,
    #[serde(default)]
    pub args: Vec<String>,
    pub owner_pid: u32,
    #[serde(default)]
    pub user: Option<String>,
    #[serde(default)]
    pub auth_names: Vec<String>,
}

/// Resolves the container settings of a package phase, or `None` for local execution or
/// when neither the defaults nor the package declare a `container:` block.
pub fn resolve(
    defaults: Option<&ContainerConfig>,
    package: Option<&ContainerConfig>,
    request: &AdapterRequest,
    run_id: &str,
    run_root: &Path,
) -> Result<Option<ContainerSpec>> {
    if request.execution != ExecutionMode::Container {
        return Ok(None);
    }
    let Some(config) = ContainerConfig::merged(defaults, package) else {
        return Ok(None);
    };
    let mount_root = normalize(process::mount_root(request));
    let root = normalize(&request.root);
    let image = match config.image.clone().or_else(|| {
        request
            .options
            .get("image")
            .and_then(|value| value.as_str())
            .map(str::to_string)
    }) {
        Some(image) if !image.is_empty() && !image.starts_with('-') => image,
        Some(image) => bail!("container.image: invalid image `{image}`"),
        None => bail!("container execution requires container.image or options.image"),
    };
    let user = match config.user {
        Some(user) if user == "host" || is_user_name(&user) => Some(user),
        Some(user) => bail!("container.user: `{user}` is not `host` or a user[:group] name or id"),
        None => None,
    };
    let cap_drop = config.cap_drop.unwrap_or_else(|| vec!["ALL".to_string()]);
    for capability in &cap_drop {
        if capability.is_empty()
            || !capability
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b == b'_')
        {
            bail!("container.cap_drop: `{capability}` is not a capability name");
        }
    }
    let quantity = |field: &str, value: Option<String>| -> Result<Option<String>> {
        match value {
            Some(text) if is_quantity(&text) => Ok(Some(text)),
            Some(text) => bail!("container.{field}: `{text}` is not a number or size"),
            None => Ok(None),
        }
    };
    let cpus = quantity("cpus", config.cpus.map(|q| q.0))?;
    let memory = quantity("memory", config.memory.map(|q| q.0))?;
    let shm_size = quantity("shm_size", config.shm_size.map(|q| q.0))?;
    let tmpfs = config.tmpfs.unwrap_or_default();
    for path in &tmpfs {
        if !path.starts_with('/') || path.chars().any(char::is_whitespace) {
            bail!("container.tmpfs: `{path}` is not an absolute container path");
        }
    }
    let job_dir = run_root
        .join("jobs")
        .join(&request.project)
        .join(&request.package)
        .join(&request.phase)
        .join("w");
    let mut writable = Vec::new();
    for entry in config.writable.unwrap_or_default() {
        let relative = workspace_relative(&entry.path)
            .with_context(|| format!("container.writable: `{}`", entry.path))?;
        let origin = root.join(&relative);
        writable.push(WritableMount {
            target: container_target(&mount_root, &origin)
                .with_context(|| format!("container.writable: `{}`", entry.path))?,
            source: job_dir.join(&relative),
            origin,
            seed: entry.seed == Some(SeedMode::Copy),
        });
    }
    let mut mounts = Vec::new();
    for entry in config.mounts.unwrap_or_default() {
        if entry.source.is_empty() || entry.source.contains(':') {
            bail!("container.mounts: invalid source `{}`", entry.source);
        }
        let source = normalize(&root.join(&entry.source));
        let target = match &entry.target {
            Some(target) => absolute_target(target)
                .with_context(|| format!("container.mounts: target `{target}`"))?,
            None => default_mount_target(&mount_root, &source)
                .with_context(|| format!("container.mounts: source `{}`", entry.source))?,
        };
        mounts.push(BindMount {
            source,
            target,
            read_only: entry.read_only,
        });
    }
    let caches = match config.caches {
        Some(caches) => {
            let mut volumes = Vec::new();
            for cache in caches {
                if !is_volume_name(&cache.volume) {
                    bail!("container.caches: `{}` is not a volume name", cache.volume);
                }
                volumes.push(CacheVolume {
                    target: absolute_target(&cache.target)
                        .with_context(|| format!("container.caches: target `{}`", cache.target))?,
                    volume: cache.volume,
                });
            }
            volumes
        }
        None => default_caches(&image),
    };
    let env = config.env.unwrap_or_default();
    let env_pass = env.pass.unwrap_or_default();
    let env_set = env.set.unwrap_or_default();
    for name in env_pass.iter().chain(env_set.keys()) {
        if !is_env_name(name) {
            bail!("container.env: `{name}` is not a variable name");
        }
    }
    Ok(Some(ContainerSpec {
        name: container_name(run_id, &request.project, &request.package, &request.phase),
        run: run_id.to_string(),
        job: format!("{}/{}/{}", request.project, request.package, request.phase),
        image,
        pull: config.pull.unwrap_or_default(),
        network: config.network.unwrap_or_default(),
        user,
        cap_drop,
        no_new_privileges: config.no_new_privileges.unwrap_or(true),
        read_only_root: config.read_only_root.unwrap_or(false),
        cpus,
        memory,
        pids: config.pids,
        shm_size,
        tmpfs,
        timeout_secs: config.timeout.map_or(DEFAULT_TIMEOUT_SECS, |t| t.0),
        workspace: config.workspace.unwrap_or_default(),
        mount_root,
        outputs: vec![request.output.clone()],
        writable,
        mounts,
        caches,
        env_pass,
        env_set,
    }))
}

/// Builds the `docker run` arguments for one tool invocation. Pure: the same spec, request
/// and call always produce the same command line.
pub fn container_argv(
    spec: &ContainerSpec,
    request: &AdapterRequest,
    call: &ContainerCall,
) -> Vec<String> {
    let map = |value: &str| value.replace(spec.mount_root.to_string_lossy().as_ref(), "/workspace");
    let read_only = |flag: bool| if flag { ":ro" } else { "" };
    let mut argv = process::strings(&["run", "--rm", "--init"]);
    push(&mut argv, "--name", spec.name.clone());
    push(&mut argv, "--label", format!("{RUN_LABEL}={}", spec.run));
    push(&mut argv, "--label", format!("{JOB_LABEL}={}", spec.job));
    push(
        &mut argv,
        "--label",
        format!("{PID_LABEL}={}", call.owner_pid),
    );
    push(&mut argv, "--pull", spec.pull.as_str());
    push(&mut argv, "--network", spec.network.as_str());
    if spec.network != NetworkMode::None {
        push(&mut argv, "--add-host", "host.docker.internal:host-gateway");
    }
    if let Some(user) = &call.user {
        push(&mut argv, "--user", user.clone());
    }
    for capability in &spec.cap_drop {
        push(&mut argv, "--cap-drop", capability.clone());
    }
    if spec.no_new_privileges {
        push(&mut argv, "--security-opt", "no-new-privileges");
    }
    if spec.read_only_root {
        argv.push("--read-only".to_string());
    }
    if let Some(cpus) = &spec.cpus {
        push(&mut argv, "--cpus", cpus.clone());
    }
    if let Some(memory) = &spec.memory {
        push(&mut argv, "--memory", memory.clone());
    }
    if let Some(pids) = spec.pids {
        push(&mut argv, "--pids-limit", pids.to_string());
    }
    if let Some(shm_size) = &spec.shm_size {
        push(&mut argv, "--shm-size", shm_size.clone());
    }
    for path in &spec.tmpfs {
        push(&mut argv, "--tmpfs", path.clone());
    }
    push(
        &mut argv,
        "-v",
        format!(
            "{}:/workspace{}",
            spec.mount_root.display(),
            read_only(spec.workspace == WorkspaceAccess::Ro)
        ),
    );
    for output in &spec.outputs {
        let host = output.to_string_lossy();
        push(&mut argv, "-v", format!("{host}:{}", map(&host)));
    }
    for path in &spec.writable {
        push(
            &mut argv,
            "-v",
            format!("{}:{}", path.source.display(), path.target),
        );
    }
    for mount in &spec.mounts {
        push(
            &mut argv,
            "-v",
            format!(
                "{}:{}{}",
                mount.source.display(),
                mount.target,
                read_only(mount.read_only)
            ),
        );
    }
    for cache in &spec.caches {
        push(
            &mut argv,
            "-v",
            format!("{}:{}", cache.volume, cache.target),
        );
    }
    push(&mut argv, "-w", map(&request.cwd.to_string_lossy()));
    if spec.user.as_deref() == Some("host") {
        push(&mut argv, "-e", "HOME=/tmp/home");
    }
    for name in &call.auth_names {
        push(&mut argv, "-e", name.clone());
    }
    for (key, value) in &request.env {
        let value = map(value)
            .replace("127.0.0.1:", "host.docker.internal:")
            .replace("localhost:", "host.docker.internal:");
        push(&mut argv, "-e", format!("{key}={value}"));
    }
    for name in &spec.env_pass {
        push(&mut argv, "-e", name.clone());
    }
    for (key, value) in &spec.env_set {
        push(&mut argv, "-e", format!("{key}={value}"));
    }
    argv.push(spec.image.clone());
    argv.push(map(&call.program));
    argv.extend(call.args.iter().map(|arg| map(arg)));
    argv
}

fn push(argv: &mut Vec<String>, option: &str, value: impl Into<String>) {
    argv.push(option.to_string());
    argv.push(value.into());
}

/// Runs one tool invocation in a container described by `spec`. On timeout or cancellation
/// the container is killed by name before the Docker client is stopped.
pub fn run(
    request: &AdapterRequest,
    spec: &ContainerSpec,
    program: &str,
    args: &[String],
    label: &str,
) -> Result<bool> {
    prepare(spec)?;
    let user = match spec.user.as_deref() {
        Some("host") => Some(host_user()?),
        other => other.map(str::to_string),
    };
    let mut auth_names: Vec<String> = std::env::vars()
        .map(|(key, _)| key)
        .filter(|key| key.starts_with("CHERRY_TEST_AUTH_"))
        .collect();
    auth_names.sort();
    let call = ContainerCall {
        program: program.to_string(),
        args: args.to_vec(),
        owner_pid: owner_pid(),
        user,
        auth_names,
    };
    let mut command = Command::new("docker");
    command
        .args(container_argv(spec, request, &call))
        .stdin(Stdio::null());
    let name = spec.name.clone();
    let stop = move || kill(&name);
    process::capture_with(
        command,
        &request.output.join(label),
        spec.timeout_secs,
        false,
        Some(&stop),
    )
}

/// Creates the output and per-job writable directories, seeds writable paths that ask for
/// it, and checks that extra mount sources exist.
pub fn prepare(spec: &ContainerSpec) -> Result<()> {
    for output in &spec.outputs {
        std::fs::create_dir_all(output).with_context(|| format!("create {}", output.display()))?;
    }
    for path in &spec.writable {
        if path.origin.exists() && !path.origin.is_dir() {
            bail!("writable path {} is not a directory", path.origin.display());
        }
        std::fs::create_dir_all(&path.origin)
            .with_context(|| format!("create {}", path.origin.display()))?;
        if !path.source.exists() {
            std::fs::create_dir_all(&path.source)
                .with_context(|| format!("create {}", path.source.display()))?;
            if path.seed {
                copy_tree(&path.origin, &path.source)?;
            }
        }
    }
    for mount in &spec.mounts {
        if !mount.source.exists() {
            bail!("mount source {} does not exist", mount.source.display());
        }
    }
    Ok(())
}

/// Docker container name for a job: `ct-<run>-<project>-<package>-<phase>`, sanitized.
pub fn container_name(run_id: &str, project: &str, package: &str, phase: &str) -> String {
    sanitize_name(&format!("ct-{run_id}-{project}-{package}-{phase}"))
}

/// Replaces every character Docker does not accept in a container name with `-`, and makes
/// sure the name starts with a letter or digit.
pub fn sanitize_name(raw: &str) -> String {
    let name: String = raw
        .chars()
        .map(|c| {
            if c.is_ascii_alphanumeric() || matches!(c, '_' | '.' | '-') {
                c
            } else {
                '-'
            }
        })
        .collect();
    if name.starts_with(|c: char| c.is_ascii_alphanumeric()) {
        name
    } else {
        format!("ct{name}")
    }
}

/// Volumes kept for well-known toolchain images when `caches` is not configured.
pub fn default_caches(image: &str) -> Vec<CacheVolume> {
    [
        ("maven:", "cherry-test-polyglot-maven", "/root/.m2"),
        (
            "gradle:",
            "cherry-test-polyglot-gradle",
            "/home/gradle/.gradle",
        ),
        (
            "cherry-test-rust:",
            "cherry-test-polyglot-cargo",
            "/usr/local/cargo/registry",
        ),
    ]
    .into_iter()
    .filter(|(prefix, _, _)| image.starts_with(prefix))
    .map(|(_, volume, target)| CacheVolume {
        volume: volume.to_string(),
        target: target.to_string(),
    })
    .collect()
}

/// Process id recorded in the owner label: the run that started the adapter.
pub fn owner_pid() -> u32 {
    std::env::var(OWNER_PID_ENV)
        .ok()
        .and_then(|value| value.parse().ok())
        .unwrap_or_else(std::process::id)
}

/// Stops a container by name. A container that already ended is not an error.
pub fn kill(name: &str) {
    match Command::new("docker")
        .args(["kill", name])
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
    {
        Ok(status) if status.success() => {}
        Ok(_) => tracing::debug!(container = name, "container was not running"),
        Err(error) => tracing::warn!(container = name, error = %error, "could not run docker kill"),
    }
}

/// Run start: removes containers left behind by runs that died, and reports what it removed.
pub fn start_run(run_id: &str, quiet: bool) {
    match reap_stale(run_id) {
        Ok(0) => {}
        Ok(count) => {
            if !quiet {
                println!("==> removed {count} leftover containers of earlier runs");
            }
        }
        Err(error) => eprintln!("warning: could not remove leftover containers: {error:#}"),
    }
}

/// Run end: removes every container this run started that is still present.
pub fn finish_run(run_id: &str) {
    if let Err(error) = remove_run(run_id) {
        eprintln!("warning: could not remove containers of run {run_id}: {error:#}");
    }
}

/// Removes containers of earlier runs whose owning process is gone. Containers of the
/// current run and containers without an owner label are kept.
pub fn reap_stale(run_id: &str) -> Result<usize> {
    let listing = docker_output(&[
        "ps",
        "-a",
        "--filter",
        &format!("label={RUN_LABEL}"),
        "--format",
        &format!("{{{{.ID}}}}\t{{{{.Label \"{RUN_LABEL}\"}}}}\t{{{{.Label \"{PID_LABEL}\"}}}}"),
    ])?;
    let stale = stale_containers(&listing, run_id, process_alive);
    remove(&stale)?;
    Ok(stale.len())
}

/// Removes every container labelled with this run.
pub fn remove_run(run_id: &str) -> Result<usize> {
    let listing = docker_output(&[
        "ps",
        "-aq",
        "--filter",
        &format!("label={RUN_LABEL}={run_id}"),
    ])?;
    let ids: Vec<String> = listing
        .lines()
        .map(str::trim)
        .filter(|id| !id.is_empty())
        .map(str::to_string)
        .collect();
    remove(&ids)?;
    Ok(ids.len())
}

/// Selects container ids from `docker ps` lines `<id>\t<run>\t<pid>` that belong to another
/// run whose owner process is no longer alive.
pub fn stale_containers(listing: &str, run_id: &str, alive: impl Fn(u32) -> bool) -> Vec<String> {
    listing
        .lines()
        .filter_map(|line| {
            let mut fields = line.split('\t');
            let id = fields.next()?.trim();
            let run = fields.next()?.trim();
            let pid = fields.next()?.trim().parse::<u32>().ok()?;
            (!id.is_empty() && run != run_id && !alive(pid)).then(|| id.to_string())
        })
        .collect()
}

fn remove(ids: &[String]) -> Result<()> {
    if ids.is_empty() {
        return Ok(());
    }
    let mut args = vec!["rm".to_string(), "-f".to_string()];
    args.extend(ids.iter().cloned());
    let output = Command::new("docker")
        .args(&args)
        .stdin(Stdio::null())
        .output()
        .context("start `docker rm`")?;
    if !output.status.success() {
        bail!(
            "docker rm failed: {}",
            String::from_utf8_lossy(&output.stderr).trim()
        );
    }
    Ok(())
}

fn docker_output(args: &[&str]) -> Result<String> {
    let output = Command::new("docker")
        .args(args)
        .stdin(Stdio::null())
        .output()
        .context("start `docker ps`")?;
    if !output.status.success() {
        bail!(
            "docker ps failed: {}",
            String::from_utf8_lossy(&output.stderr).trim()
        );
    }
    Ok(String::from_utf8_lossy(&output.stdout).into_owned())
}

fn process_alive(pid: u32) -> bool {
    Command::new("ps")
        .args(["-p", &pid.to_string()])
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .map(|status| status.success())
        .unwrap_or(true)
}

fn host_user() -> Result<String> {
    let id = |flag: &str| -> Result<String> {
        let output = Command::new("id")
            .arg(flag)
            .stdin(Stdio::null())
            .output()
            .context("start `id`")?;
        let value = String::from_utf8_lossy(&output.stdout).trim().to_string();
        if !output.status.success()
            || value.is_empty()
            || !value.bytes().all(|b| b.is_ascii_digit())
        {
            bail!("could not read the host user id with `id {flag}`");
        }
        Ok(value)
    };
    Ok(format!("{}:{}", id("-u")?, id("-g")?))
}

fn copy_tree(from: &Path, to: &Path) -> Result<()> {
    for entry in std::fs::read_dir(from).with_context(|| format!("read {}", from.display()))? {
        let entry = entry?;
        let kind = entry.file_type()?;
        let target = to.join(entry.file_name());
        if kind.is_dir() {
            std::fs::create_dir_all(&target)?;
            copy_tree(&entry.path(), &target)?;
        } else if kind.is_symlink() {
            #[cfg(unix)]
            std::os::unix::fs::symlink(std::fs::read_link(entry.path())?, &target)?;
            #[cfg(not(unix))]
            std::fs::copy(entry.path(), &target)?;
        } else {
            std::fs::copy(entry.path(), &target)
                .with_context(|| format!("copy {}", entry.path().display()))?;
        }
    }
    Ok(())
}

fn normalize(path: &Path) -> PathBuf {
    let mut result = PathBuf::new();
    for component in path.components() {
        match component {
            Component::CurDir => {}
            Component::ParentDir => {
                if !result.pop() {
                    result.push("..");
                }
            }
            other => result.push(other.as_os_str()),
        }
    }
    result
}

fn workspace_relative(path: &str) -> Result<PathBuf> {
    let candidate = Path::new(path);
    if path.is_empty() || path.contains(':') || candidate.is_absolute() {
        bail!("must be a path inside the workspace");
    }
    let mut relative = PathBuf::new();
    for component in candidate.components() {
        match component {
            Component::Normal(part) => relative.push(part),
            Component::CurDir => {}
            _ => bail!("must be a path inside the workspace"),
        }
    }
    if relative.as_os_str().is_empty() {
        bail!("must be a path inside the workspace");
    }
    Ok(relative)
}

fn container_target(mount_root: &Path, host: &Path) -> Result<String> {
    let relative = host
        .strip_prefix(mount_root)
        .map_err(|_| anyhow::anyhow!("must be a path inside the workspace"))?;
    Ok(format!("/workspace/{}", slash_path(relative)))
}

fn default_mount_target(mount_root: &Path, source: &Path) -> Result<String> {
    if let Ok(inside) = source.strip_prefix(mount_root) {
        if inside.as_os_str().is_empty() {
            bail!("is the workspace itself");
        }
        return Ok(format!("/workspace/{}", slash_path(inside)));
    }
    let sibling = mount_root
        .parent()
        .and_then(|parent| source.strip_prefix(parent).ok())
        .filter(|rest| !rest.as_os_str().is_empty());
    match sibling {
        Some(rest) => Ok(format!("/{}", slash_path(rest))),
        None => bail!("is more than one level above the workspace; set `target`"),
    }
}

fn absolute_target(target: &str) -> Result<String> {
    if !target.starts_with('/') || target.contains(':') {
        bail!("must be an absolute container path");
    }
    let normalized = normalize(Path::new(target));
    if normalized.components().any(|c| c == Component::ParentDir) {
        bail!("must be an absolute container path");
    }
    Ok(normalized.to_string_lossy().into_owned())
}

fn slash_path(path: &Path) -> String {
    path.components()
        .map(|component| component.as_os_str().to_string_lossy().into_owned())
        .collect::<Vec<_>>()
        .join("/")
}

fn is_user_name(value: &str) -> bool {
    let part = |text: &str| {
        text.starts_with(|c: char| c.is_ascii_alphanumeric() || c == '_')
            && text
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'_' | b'.' | b'-'))
    };
    match value.split_once(':') {
        Some((user, group)) => part(user) && part(group),
        None => part(value),
    }
}

fn is_quantity(value: &str) -> bool {
    value.starts_with(|c: char| c.is_ascii_digit())
        && value
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'.')
}

fn is_volume_name(value: &str) -> bool {
    value.starts_with(|c: char| c.is_ascii_alphanumeric())
        && value
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'_' | b'.' | b'-'))
}

fn is_env_name(value: &str) -> bool {
    value.starts_with(|c: char| c.is_ascii_alphabetic() || c == '_')
        && value
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'_')
}

#[cfg(test)]
mod tests {
    use super::*;
    use cherry_test_common::container::PullPolicy;
    use std::collections::BTreeMap;

    fn workspace() -> (tempfile::TempDir, PathBuf) {
        let temp = tempfile::tempdir().unwrap();
        let root = temp.path().canonicalize().unwrap().join("repo");
        std::fs::create_dir_all(root.join(".git")).unwrap();
        (temp, root)
    }

    fn request(root: &Path, execution: ExecutionMode) -> AdapterRequest {
        AdapterRequest {
            protocol: 1,
            project: "shop".into(),
            package: "prices".into(),
            phase: "unit".into(),
            root: root.into(),
            manifest: root.join("prices/pom.xml"),
            cwd: root.join("prices"),
            output: root.join("target/cherry-test/runs/r1/reports/adapters/shop/prices/unit"),
            execution,
            coverage: true,
            strict: false,
            args: Vec::new(),
            env: BTreeMap::new(),
            options: BTreeMap::new(),
            container: None,
        }
    }

    fn block(yaml: &str) -> ContainerConfig {
        serde_yaml::from_str(yaml).unwrap()
    }

    fn resolved(root: &Path, yaml: &str) -> Result<Option<ContainerSpec>> {
        resolve(
            Some(&block(yaml)),
            None,
            &request(root, ExecutionMode::Container),
            "r1",
            &root.join("target/cherry-test/runs/r1"),
        )
    }

    #[test]
    fn local_execution_or_absent_block_resolves_nothing() {
        let (_temp, root) = workspace();
        let config = block("image: alpine:3.20");
        let local = request(&root, ExecutionMode::Local);
        assert_eq!(
            resolve(Some(&config), None, &local, "r1", &root).unwrap(),
            None
        );
        let container = request(&root, ExecutionMode::Container);
        assert_eq!(resolve(None, None, &container, "r1", &root).unwrap(), None);
    }

    #[test]
    fn resolves_defaults_for_a_present_block() {
        let (_temp, root) = workspace();
        let spec = resolved(&root, "image: alpine:3.20").unwrap().unwrap();
        assert_eq!(spec.name, "ct-r1-shop-prices-unit");
        assert_eq!(spec.run, "r1");
        assert_eq!(spec.job, "shop/prices/unit");
        assert_eq!(spec.pull, PullPolicy::Never);
        assert_eq!(spec.network, NetworkMode::None);
        assert_eq!(spec.cap_drop, ["ALL"]);
        assert!(spec.no_new_privileges);
        assert!(!spec.read_only_root);
        assert_eq!(spec.timeout_secs, 1800);
        assert_eq!(spec.workspace, WorkspaceAccess::Ro);
        assert_eq!(spec.mount_root, root);
        assert_eq!(
            spec.outputs,
            [root.join("target/cherry-test/runs/r1/reports/adapters/shop/prices/unit")]
        );
        assert!(spec.caches.is_empty());
        let maven = resolved(&root, "image: maven:3.9.11-eclipse-temurin-25")
            .unwrap()
            .unwrap();
        assert_eq!(
            maven.caches,
            [CacheVolume {
                volume: "cherry-test-polyglot-maven".into(),
                target: "/root/.m2".into()
            }]
        );
    }

    #[test]
    fn image_falls_back_to_options_and_is_required() {
        let (_temp, root) = workspace();
        let mut with_option = request(&root, ExecutionMode::Container);
        with_option
            .options
            .insert("image".into(), "node:22-bookworm".into());
        let spec = resolve(
            Some(&block("network: none")),
            None,
            &with_option,
            "r1",
            &root,
        )
        .unwrap()
        .unwrap();
        assert_eq!(spec.image, "node:22-bookworm");
        let error = resolved(&root, "network: none").unwrap_err();
        assert_eq!(
            format!("{error:#}"),
            "container execution requires container.image or options.image"
        );
    }

    #[test]
    fn writable_paths_are_per_job() {
        let (_temp, root) = workspace();
        let spec = resolved(
            &root,
            "image: alpine:3.20\nwritable: [target, {path: ./prices/build, seed: copy}]",
        )
        .unwrap()
        .unwrap();
        let jobs = root.join("target/cherry-test/runs/r1/jobs/shop/prices/unit/w");
        assert_eq!(
            spec.writable,
            [
                WritableMount {
                    origin: root.join("target"),
                    source: jobs.join("target"),
                    target: "/workspace/target".into(),
                    seed: false,
                },
                WritableMount {
                    origin: root.join("prices/build"),
                    source: jobs.join("prices/build"),
                    target: "/workspace/prices/build".into(),
                    seed: true,
                },
            ]
        );
    }

    #[test]
    fn rejects_writable_paths_outside_the_workspace() {
        let (_temp, root) = workspace();
        for path in ["../elsewhere", "/etc", "", "a/../../b", "c:d"] {
            let error =
                resolved(&root, &format!("image: alpine:3.20\nwritable: [{path:?}]")).unwrap_err();
            assert_eq!(
                format!("{error:#}"),
                format!("container.writable: `{path}`: must be a path inside the workspace")
            );
        }
    }

    #[test]
    fn default_mount_target_keeps_relative_position() {
        let (_temp, root) = workspace();
        let spec = resolved(
            &root,
            "image: alpine:3.20\nmounts:\n  - {source: ../sibling}\n  - {source: docs, read_only: false}\n  - {source: ../data, target: /data/./set}",
        )
        .unwrap()
        .unwrap();
        let parent = root.parent().unwrap();
        assert_eq!(
            spec.mounts,
            [
                BindMount {
                    source: parent.join("sibling"),
                    target: "/sibling".into(),
                    read_only: true,
                },
                BindMount {
                    source: root.join("docs"),
                    target: "/workspace/docs".into(),
                    read_only: false,
                },
                BindMount {
                    source: parent.join("data"),
                    target: "/data/set".into(),
                    read_only: true,
                },
            ]
        );
        let error =
            resolved(&root, "image: alpine:3.20\nmounts: [{source: ../../far}]").unwrap_err();
        assert_eq!(
            format!("{error:#}"),
            "container.mounts: source `../../far`: is more than one level above the workspace; set `target`"
        );
    }

    #[test]
    fn rejects_unsafe_container_values() {
        let (_temp, root) = workspace();
        for (yaml, expected) in [
            (
                "image: --privileged",
                "container.image: invalid image `--privileged`",
            ),
            (
                "user: root;id",
                "container.user: `root;id` is not `host` or a user[:group] name or id",
            ),
            (
                "cap_drop: [--all]",
                "container.cap_drop: `--all` is not a capability name",
            ),
            (
                "memory: --privileged",
                "container.memory: `--privileged` is not a number or size",
            ),
            (
                "tmpfs: [tmp]",
                "container.tmpfs: `tmp` is not an absolute container path",
            ),
            (
                "caches: [{volume: -v, target: /c}]",
                "container.caches: `-v` is not a volume name",
            ),
            (
                "caches: [{volume: m2, target: relative}]",
                "container.caches: target `relative`: must be an absolute container path",
            ),
            (
                "env: {pass: [A-B]}",
                "container.env: `A-B` is not a variable name",
            ),
        ] {
            let image = if yaml.starts_with("image") {
                ""
            } else {
                "image: alpine:3.20\n"
            };
            let error = resolved(&root, &format!("{image}{yaml}")).unwrap_err();
            assert_eq!(format!("{error:#}"), expected);
        }
    }

    #[test]
    fn sanitizes_container_names() {
        assert_eq!(
            container_name("20261028-101500-abc123", "shop", "prices", "unit"),
            "ct-20261028-101500-abc123-shop-prices-unit"
        );
        assert_eq!(
            container_name("r1", "web", "@scope/ui kit", "visual"),
            "ct-r1-web--scope-ui-kit-visual"
        );
        assert_eq!(sanitize_name("ct-r1-caf\u{e9}-\u{e9}"), "ct-r1-caf---");
        assert_eq!(sanitize_name("-leading"), "ct-leading");
        assert_eq!(sanitize_name("a.b_c-d"), "a.b_c-d");
    }

    #[test]
    fn selects_containers_of_dead_runs() {
        let listing = "aaa\tr-old\t100\nbbb\tr-now\t100\nccc\tr-live\t200\nddd\tr-legacy\t\neee\tr-old\tnot-a-pid\n\n";
        assert_eq!(
            stale_containers(listing, "r-now", |pid| pid == 200),
            ["aaa"]
        );
    }

    #[test]
    fn prepare_seeds_writable_paths_once() {
        let (_temp, root) = workspace();
        std::fs::create_dir_all(root.join("build/nested")).unwrap();
        std::fs::write(root.join("build/nested/seed.txt"), "seed").unwrap();
        let spec = resolved(
            &root,
            "image: alpine:3.20\nwritable: [{path: build, seed: copy}, cache]",
        )
        .unwrap()
        .unwrap();
        prepare(&spec).unwrap();
        let build = &spec.writable[0].source;
        assert_eq!(
            std::fs::read_to_string(build.join("nested/seed.txt")).unwrap(),
            "seed"
        );
        assert!(root.join("cache").is_dir());
        assert!(spec.writable[1].source.is_dir());
        std::fs::write(build.join("nested/seed.txt"), "changed by the job").unwrap();
        prepare(&spec).unwrap();
        assert_eq!(
            std::fs::read_to_string(build.join("nested/seed.txt")).unwrap(),
            "changed by the job"
        );
        assert_eq!(
            std::fs::read_to_string(root.join("build/nested/seed.txt")).unwrap(),
            "seed"
        );
    }

    fn run_ids(run_id: &str) -> String {
        let output = Command::new("docker")
            .args([
                "ps",
                "-aq",
                "--filter",
                &format!("label={RUN_LABEL}={run_id}"),
            ])
            .output()
            .unwrap();
        assert!(output.status.success());
        String::from_utf8_lossy(&output.stdout).trim().to_string()
    }

    fn sh(request: &AdapterRequest, spec: &ContainerSpec, script: &str, label: &str) -> String {
        let ok = run(request, spec, "sh", &["-c".into(), script.into()], label).unwrap();
        assert!(
            ok,
            "{script}: {}",
            std::fs::read_to_string(request.output.join(format!("{label}.stderr"))).unwrap()
        );
        std::fs::read_to_string(request.output.join(format!("{label}.stdout"))).unwrap()
    }

    #[test]
    #[ignore = "needs Docker and the local alpine:3.20 image"]
    fn smoke_runs_hardened_containers() {
        let (_temp, root) = workspace();
        let run_id = format!("smoke-{}", std::process::id());
        let run_root = root.join("target/cherry-test/runs").join(&run_id);
        let job = |phase: &str, yaml: &str| {
            let mut request = request(&root, ExecutionMode::Container);
            request.phase = phase.into();
            request.cwd = root.clone();
            request.output = run_root.join("reports/adapters/shop/prices").join(phase);
            request.container =
                resolve(Some(&block(yaml)), None, &request, &run_id, &run_root).unwrap();
            request
        };
        let yaml = "image: alpine:3.20\nwritable: [scratch]\ntimeout: 3s";
        let unit = job("unit", yaml);
        let spec = unit.container.clone().unwrap();

        let network = sh(&unit, &spec, "echo routes=$(ip route | wc -l); if wget -q -T 3 -O /dev/null http://1.1.1.1; then echo online; else echo offline; fi",
            "network",
        );
        assert_eq!(network, "routes=0\noffline\n");

        let denied = sh(
            &unit,
            &spec,
            "touch /workspace/probe 2>&1; echo status=$?",
            "workspace",
        );
        assert!(denied.contains("Read-only file system"), "{denied}");
        assert!(denied.ends_with("status=1\n"), "{denied}");
        assert!(!root.join("probe").exists());

        let output = unit
            .output
            .to_string_lossy()
            .replace(root.to_string_lossy().as_ref(), "/workspace");
        let written = sh(&unit, &spec, &format!("echo unit > /workspace/scratch/job.txt && echo report > {output}/report.txt && cat /workspace/scratch/job.txt"), "writable");
        assert_eq!(written, "unit\n");
        assert_eq!(
            std::fs::read_to_string(spec.writable[0].source.join("job.txt")).unwrap(),
            "unit\n"
        );
        assert!(unit.output.join("report.txt").is_file());
        assert!(!root.join("scratch/job.txt").exists());

        let integration = job("integration", yaml);
        let other = integration.container.clone().unwrap();
        assert_ne!(other.writable[0].source, spec.writable[0].source);
        assert_eq!(
            sh(
                &integration,
                &other,
                "ls -A /workspace/scratch | wc -l",
                "fresh"
            )
            .trim(),
            "0"
        );
        assert_eq!(run_ids(&run_id), "");

        let started = std::time::Instant::now();
        let error = run(&unit, &spec, "sleep", &["60".into()], "timeout").unwrap_err();
        assert!(format!("{error:#}").contains("exceeded 3s"), "{error:#}");
        assert!(started.elapsed() < std::time::Duration::from_secs(25));
        assert_eq!(run_ids(&run_id), "");
        assert_eq!(remove_run(&run_id).unwrap(), 0);
    }

    #[derive(Deserialize)]
    struct GoldenCase {
        name: String,
        request: AdapterRequest,
        call: ContainerCall,
        argv: Vec<String>,
    }

    #[test]
    fn argv_matches_golden_cases() {
        let cases: Vec<GoldenCase> =
            serde_json::from_str(include_str!("../../tests/golden/container-argv.json")).unwrap();
        assert!(cases.len() >= 4);
        for case in cases {
            let spec = case.request.container.clone().unwrap();
            assert_eq!(
                container_argv(&spec, &case.request, &case.call),
                case.argv,
                "{}",
                case.name
            );
        }
    }
}
