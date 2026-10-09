use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};
use std::process::Command;
use std::sync::Mutex;

use anyhow::{bail, Context, Result};
use once_cell::sync::Lazy;

use crate::config::{ServiceConfig, WorkspaceConfig};
use crate::scope::{is_none, resolve_projects, service_filter};

pub const COMPOSE_PROJECT: &str = "cherry-test";

static BOOTED_SCOPES: Lazy<Mutex<HashSet<String>>> = Lazy::new(|| Mutex::new(HashSet::new()));
static BOOTED_COMPOSE_FILES: Lazy<Mutex<Vec<std::path::PathBuf>>> =
    Lazy::new(|| Mutex::new(Vec::new()));

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

pub fn workspace_root() -> Result<std::path::PathBuf> {
    if let Ok(root) = std::env::var("CHERRY_TEST_WORKSPACE_ROOT") {
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

pub fn compose_project() -> String {
    std::env::var("CHERRY_TEST_COMPOSE_PROJECT").unwrap_or_else(|_| COMPOSE_PROJECT.to_string())
}

pub fn boot_workspace() -> Result<ServiceRegistry> {
    boot_scope("global")
}

pub fn boot_scope(scope: &str) -> Result<ServiceRegistry> {
    if is_none(scope) {
        return load_registry_from_env(None);
    }

    if std::env::var("CHERRY_TEST_SKIP_DOCKER")
        .map(|v| v == "1" || v.eq_ignore_ascii_case("true"))
        .unwrap_or(false)
    {
        return load_registry_from_env(Some(scope));
    }

    {
        let booted = BOOTED_SCOPES.lock().unwrap();
        if booted.contains(scope) {
            return load_registry_from_env(Some(scope));
        }
    }

    if !docker_available() {
        bail!(
            "docker is not available; install Docker or set scope=none / CHERRY_TEST_SKIP_DOCKER=1"
        );
    }

    let root = workspace_root()?;
    let config = WorkspaceConfig::load(&root)?;
    let project_names = resolve_projects(&config, scope)?;
    let compose_files = config.compose_files_for_projects(&project_names)?;

    let mut cmd = Command::new("docker");
    cmd.arg("compose").arg("-p").arg(compose_project());
    for file in &compose_files {
        cmd.arg("-f").arg(file);
    }
    cmd.args(["up", "-d", "--build", "--wait"]);
    let mut selected = Vec::new();
    for project_name in &project_names {
        if let Some(project) = config.projects.get(project_name) {
            for (name, service) in &project.services {
                if service_filter(&config, scope, project_name, name) {
                    selected.push(service.service.clone());
                }
            }
        }
    }
    cmd.args(selected);
    cmd.current_dir(&root);

    {
        let mut files = BOOTED_COMPOSE_FILES
            .lock()
            .map_err(|_| anyhow::anyhow!("compose state lock poisoned"))?;
        for file in &compose_files {
            if !files.contains(file) {
                files.push(file.clone());
            }
        }
    }
    let status = cmd.status().context("docker compose up")?;
    if !status.success() {
        bail!("docker compose up failed for scope `{scope}`");
    }

    let mut registry = HashMap::new();
    let mut env_snapshot = serde_json::Map::new();

    for project_name in &project_names {
        let Some(project) = config.projects.get(project_name) else {
            continue;
        };
        for (service_name, service) in &project.services {
            if !service_filter(&config, scope, project_name, service_name) {
                continue;
            }
            let endpoint =
                resolve_endpoint(&root, &compose_files, project_name, service_name, service)?;
            export_service_env(&endpoint);
            env_snapshot.insert(
                format!("{}.{}", project_name, service_name),
                serde_json::json!({
                    "kind": endpoint.kind,
                    "url": endpoint.url,
                    "host": endpoint.host,
                    "port": endpoint.port,
                }),
            );
            registry.insert((project_name.clone(), service_name.clone()), endpoint);
        }
    }

    write_env_json(&root, &env_snapshot)?;

    BOOTED_SCOPES.lock().unwrap().insert(scope.to_string());
    let mut files = BOOTED_COMPOSE_FILES.lock().unwrap();
    for file in compose_files {
        if !files.contains(&file) {
            files.push(file);
        }
    }

    Ok(registry)
}

pub fn teardown_workspace() -> Result<()> {
    teardown_scopes()
}

pub fn teardown_scopes() -> Result<()> {
    if std::env::var("CHERRY_TEST_SKIP_DOCKER")
        .map(|v| v == "1" || v.eq_ignore_ascii_case("true"))
        .unwrap_or(false)
    {
        BOOTED_SCOPES.lock().unwrap().clear();
        BOOTED_COMPOSE_FILES.lock().unwrap().clear();
        return Ok(());
    }

    let files: Vec<_> = BOOTED_COMPOSE_FILES.lock().unwrap().clone();
    if files.is_empty() {
        return Ok(());
    }

    let root = workspace_root()?;
    let mut cmd = Command::new("docker");
    cmd.arg("compose").arg("-p").arg(compose_project());
    for file in &files {
        cmd.arg("-f").arg(file);
    }
    cmd.args(["down", "-v"]);
    cmd.current_dir(&root);
    let status = cmd.status().context("docker compose down")?;
    if !status.success() {
        bail!("Docker teardown failed");
    }

    BOOTED_SCOPES.lock().unwrap().clear();
    BOOTED_COMPOSE_FILES.lock().unwrap().clear();
    Ok(())
}

fn resolve_endpoint(
    root: &Path,
    compose_files: &[std::path::PathBuf],
    project: &str,
    service_name: &str,
    service: &ServiceConfig,
) -> Result<ServiceEndpoint> {
    let kind = service.kind.clone().unwrap_or_else(|| "http".to_string());
    let mapped = compose_port(root, compose_files, &service.service, service.port)?;
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
    service: &str,
    port: u16,
) -> Result<String> {
    let mut cmd = Command::new("docker");
    cmd.arg("compose").arg("-p").arg(compose_project());
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

fn export_service_env(endpoint: &ServiceEndpoint) {
    let prefix = format!(
        "CHERRY_TEST_{}_{}",
        endpoint.project.to_uppercase(),
        endpoint.service.to_uppercase()
    );
    std::env::set_var(format!("{prefix}_HOST"), &endpoint.host);
    std::env::set_var(format!("{prefix}_PORT"), endpoint.port.to_string());
    if let Some(url) = &endpoint.url {
        std::env::set_var(format!("{prefix}_URL"), url);
    }
}

fn write_env_json(
    root: &Path,
    snapshot: &serde_json::Map<String, serde_json::Value>,
) -> Result<()> {
    let path = if let Ok(run_dir) = std::env::var("CHERRY_TEST_RUN_DIR") {
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

fn load_registry_from_env(scope: Option<&str>) -> Result<ServiceRegistry> {
    let root = workspace_root()?;
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
            let url = std::env::var(format!("{prefix}_URL")).ok();
            let host =
                std::env::var(format!("{prefix}_HOST")).unwrap_or_else(|_| "127.0.0.1".into());
            let port = std::env::var(format!("{prefix}_PORT"))
                .ok()
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
