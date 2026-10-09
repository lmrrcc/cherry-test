use std::collections::{BTreeMap, HashMap};
use std::path::{Path, PathBuf};

use anyhow::{bail, Context, Result};
use serde::{Deserialize, Serialize};

pub use cherry_test_common::adapter::{AdapterConfig, ExecutionMode};

const CONFIG_CANDIDATES: &[&str] = &["cherry-test.yaml", "cherry-test.yml", "cherry-test.toml"];

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct Defaults {
    #[serde(default)]
    pub execution: ExecutionMode,
    #[serde(default)]
    pub strict: bool,
    #[serde(default = "default_true")]
    pub coverage: bool,
    #[serde(default = "default_true")]
    pub html_report: bool,
    #[serde(default = "default_true")]
    pub open_report: bool,
    #[serde(default = "default_runs_base")]
    pub runs_base: PathBuf,
    #[serde(
        default = "default_report_dir",
        skip_serializing_if = "is_default_report_dir"
    )]
    pub report_dir: PathBuf,
    #[serde(default = "default_scope")]
    pub scope: String,
    #[serde(default = "default_diff_base")]
    pub diff_base: String,
    #[serde(default = "default_coverage_ignore")]
    pub coverage_ignore: PathBuf,
}

impl Default for Defaults {
    fn default() -> Self {
        Self {
            execution: ExecutionMode::Local,
            strict: false,
            coverage: default_true(),
            html_report: default_true(),
            open_report: default_true(),
            runs_base: default_runs_base(),
            report_dir: default_report_dir(),
            scope: default_scope(),
            diff_base: default_diff_base(),
            coverage_ignore: default_coverage_ignore(),
        }
    }
}

fn default_coverage_ignore() -> PathBuf {
    PathBuf::from(".coverageignore")
}

#[derive(Debug, Clone, Deserialize, Serialize, Default)]
pub struct ExternalRunner {
    #[serde(default)]
    pub adapter: Option<String>,
    #[serde(default)]
    pub scope: Option<String>,
    #[serde(default)]
    pub options: BTreeMap<String, serde_json::Value>,
    #[serde(default)]
    pub command: String,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub args: Vec<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub cwd: Option<PathBuf>,
}

#[derive(Debug, Clone, Deserialize, Serialize, Default)]
pub struct ServiceConfig {
    pub service: String,
    pub port: u16,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub health: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub kind: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize, Serialize, Default)]
#[serde(rename_all = "lowercase")]
pub enum PackageRuntime {
    #[default]
    Rust,
    Node,
}

impl PackageRuntime {
    pub fn is_rust(self) -> bool {
        self == PackageRuntime::Rust
    }
}

fn is_rust_runtime(runtime: &PackageRuntime) -> bool {
    *runtime == PackageRuntime::Rust
}

#[derive(Debug, Clone, Deserialize, Serialize, Default)]
pub struct PackageConfig {
    #[serde(default)]
    pub adapter: Option<String>,
    #[serde(default)]
    pub execution: Option<ExecutionMode>,
    #[serde(default)]
    pub options: BTreeMap<String, serde_json::Value>,
    #[serde(default)]
    pub integration: Option<ExternalRunner>,
    #[serde(default)]
    pub visual: Option<ExternalRunner>,
    #[serde(default, skip_serializing_if = "is_rust_runtime")]
    pub runtime: PackageRuntime,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub kinds: Vec<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub manifest: Option<PathBuf>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub unit: Option<ExternalRunner>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub functional: Option<ExternalRunner>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub ui: Option<ExternalRunner>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub mutation: Option<ExternalRunner>,
}

#[derive(Debug, Clone, Deserialize, Serialize, Default)]
pub struct ProjectConfig {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub path: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub diff_base: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub compose: Option<String>,
    #[serde(default, skip_serializing_if = "HashMap::is_empty")]
    pub services: HashMap<String, ServiceConfig>,
    #[serde(default, skip_serializing_if = "BTreeMap::is_empty")]
    pub packages: BTreeMap<String, PackageConfig>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub functional: Option<ExternalRunner>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub ui: Option<ExternalRunner>,
}

#[derive(Debug, Clone, Deserialize, Serialize, Default)]
pub struct ScopeConfig {
    pub projects: Vec<String>,
    #[serde(default, skip_serializing_if = "HashMap::is_empty")]
    pub services: HashMap<String, Vec<String>>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct AuthProfileConfig {
    #[serde(default)]
    pub profile: String,
    pub issuer: String,
    pub client_id: String,
    pub client_secret: String,
    pub audience: String,
    pub email: String,
    #[serde(default = "default_access_ttl_secs")]
    pub access_ttl_secs: u64,
    #[serde(default = "default_refresh_ttl_secs")]
    pub refresh_ttl_secs: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub token_url: Option<String>,
    pub cache_path: PathBuf,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub okta_api_project: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub okta_api_service: Option<String>,
}

fn default_access_ttl_secs() -> u64 {
    3600
}

fn default_refresh_ttl_secs() -> u64 {
    28800
}

#[derive(Debug, Clone, Deserialize, Serialize, Default)]
struct RawConfig {
    #[serde(default)]
    adapters: BTreeMap<String, AdapterConfig>,
    #[serde(default)]
    defaults: Defaults,
    #[serde(default, skip_serializing_if = "BTreeMap::is_empty")]
    auth: BTreeMap<String, AuthProfileConfig>,
    #[serde(default)]
    projects: BTreeMap<String, ProjectConfig>,
    #[serde(default)]
    packages: BTreeMap<String, PackageConfig>,
    #[serde(default)]
    scopes: BTreeMap<String, ScopeConfig>,
}

#[derive(Debug, Clone)]
pub struct TestItConfig {
    pub adapters: BTreeMap<String, AdapterConfig>,
    pub workspace_root: PathBuf,
    pub config_path: PathBuf,
    pub deprecated_config: bool,
    pub defaults: Defaults,
    pub auth: BTreeMap<String, AuthProfileConfig>,
    pub projects: BTreeMap<String, ProjectConfig>,
    pub scopes: BTreeMap<String, ScopeConfig>,
}

pub type WorkspaceConfig = TestItConfig;

impl Default for TestItConfig {
    fn default() -> Self {
        Self {
            adapters: BTreeMap::new(),
            workspace_root: PathBuf::new(),
            config_path: PathBuf::new(),
            deprecated_config: false,
            defaults: Defaults::default(),
            auth: BTreeMap::new(),
            projects: BTreeMap::new(),
            scopes: BTreeMap::new(),
        }
    }
}

impl TestItConfig {
    /// Applies a command-line execution mode, which takes precedence over per-package settings.
    #[must_use]
    pub fn with_execution(mut self, execution: ExecutionMode) -> Self {
        self.defaults.execution = execution;
        for project in self.projects.values_mut() {
            for package in project.packages.values_mut() {
                package.execution = None;
            }
        }
        self
    }

    pub fn load(workspace_root: &Path) -> Result<Self> {
        let (path, deprecated) = locate_config(workspace_root)?;
        if !path.exists() {
            return Ok(Self {
                workspace_root: workspace_root.to_path_buf(),
                ..Default::default()
            });
        }

        if deprecated {
            eprintln!(
                "warning: {} is deprecated; migrate to cherry-test.yaml",
                path.file_name()
                    .and_then(|n| n.to_str())
                    .unwrap_or("cherry-test.toml")
            );
        }

        let contents =
            std::fs::read_to_string(&path).with_context(|| format!("read {}", path.display()))?;
        let raw = parse_raw_config(&path, &contents)?;
        let mut projects = raw.projects;
        merge_legacy_packages(&mut projects, raw.packages, workspace_root);

        for (id, project) in projects.iter_mut() {
            if project.path.is_none() {
                project.path = Some(id.clone());
            }
        }

        if contents.contains("report_dir:") || contents.contains("report_dir =") {
            eprintln!(
                "warning: defaults.report_dir is deprecated; use defaults.runs_base and per-run directories under {{runs_base}}/{{run-id}}/"
            );
        }

        Ok(Self {
            workspace_root: workspace_root.to_path_buf(),
            adapters: raw.adapters,
            config_path: path,
            deprecated_config: deprecated,
            defaults: raw.defaults,
            auth: raw.auth,
            projects,
            scopes: raw.scopes,
        })
    }

    pub fn auth_profile(&self, name: &str) -> Result<&AuthProfileConfig> {
        self.auth
            .get(name)
            .ok_or_else(|| anyhow::anyhow!("unknown auth profile `{name}` in cherry-test.yaml"))
    }

    pub fn default_scope(&self) -> String {
        self.defaults.scope.clone()
    }

    pub fn diff_base_for_project(&self, project_id: &str) -> String {
        self.projects
            .get(project_id)
            .and_then(|p| p.diff_base.clone())
            .unwrap_or_else(|| self.defaults.diff_base.clone())
    }

    pub fn config_base(&self) -> PathBuf {
        self.config_path
            .parent()
            .filter(|path| !path.as_os_str().is_empty())
            .map(Path::to_path_buf)
            .unwrap_or_else(|| self.workspace_root.clone())
    }

    pub fn project_path(&self, project_id: &str) -> Result<PathBuf> {
        let project = self.project(project_id)?;
        Ok(self
            .workspace_root
            .join(project.path.as_deref().unwrap_or(project_id)))
    }

    pub fn project(&self, name: &str) -> Result<&ProjectConfig> {
        self.projects.get(name).ok_or_else(|| {
            anyhow::anyhow!("unknown cherry-test project `{name}` in cherry-test.yaml")
        })
    }

    pub fn package_kinds(&self, package_name: &str, project_id: &str) -> Vec<String> {
        if let Some(project) = self.projects.get(project_id) {
            if let Some(pkg) = project.packages.get(package_name) {
                if !pkg.kinds.is_empty() {
                    return pkg.kinds.clone();
                }
            }
        }

        if let Some(project) = self.projects.get(project_id) {
            let mut kinds = vec!["unit".into(), "integration".into()];
            if project.functional.is_some() {
                kinds.push("functional".into());
            }
            if project.ui.is_some() {
                kinds.push("ui".into());
            }
            return kinds;
        }

        vec![
            "unit".into(),
            "integration".into(),
            "functional".into(),
            "ui".into(),
        ]
    }

    pub fn functional_runner(
        &self,
        package_name: &str,
        project_id: &str,
    ) -> Option<&ExternalRunner> {
        self.projects
            .get(project_id)
            .and_then(|p| p.packages.get(package_name))
            .and_then(|p| p.functional.as_ref())
            .or_else(|| {
                self.projects
                    .get(project_id)
                    .and_then(|p| p.functional.as_ref())
            })
    }

    pub fn ui_runner(&self, package_name: &str, project_id: &str) -> Option<&ExternalRunner> {
        self.projects
            .get(project_id)
            .and_then(|p| p.packages.get(package_name))
            .and_then(|p| p.ui.as_ref())
            .or_else(|| self.projects.get(project_id).and_then(|p| p.ui.as_ref()))
    }

    pub fn unit_runner(&self, package_name: &str, project_id: &str) -> Option<&ExternalRunner> {
        self.projects
            .get(project_id)
            .and_then(|p| p.packages.get(package_name))
            .and_then(|p| p.unit.as_ref())
    }

    pub fn package_runtime(&self, package_name: &str, project_id: &str) -> PackageRuntime {
        self.projects
            .get(project_id)
            .and_then(|p| p.packages.get(package_name))
            .map(|p| p.runtime)
            .unwrap_or_default()
    }

    pub fn compose_files_for_projects(&self, project_names: &[String]) -> Result<Vec<PathBuf>> {
        let mut files = Vec::new();
        for name in project_names {
            let project = self.project(name)?;
            if let Some(compose) = &project.compose {
                files.push(self.workspace_root.join(compose));
            }
        }
        if files.is_empty() && !project_names.is_empty() {
            bail!("no compose files for scope projects");
        }
        Ok(files)
    }

    pub fn compose_files(&self) -> Result<Vec<PathBuf>> {
        let names: Vec<_> = self.projects.keys().cloned().collect();
        self.compose_files_for_projects(&names)
    }

    pub fn write(&self) -> Result<()> {
        let raw = RawConfig {
            adapters: self.adapters.clone(),
            defaults: self.defaults.clone(),
            auth: self.auth.clone(),
            projects: self.projects.clone(),
            packages: BTreeMap::new(),
            scopes: self.scopes.clone(),
        };
        let yaml = serde_yaml::to_string(&raw).context("serialize cherry-test.yaml")?;
        std::fs::write(&self.config_path, yaml)
            .with_context(|| format!("write {}", self.config_path.display()))?;
        Ok(())
    }

    pub fn config_exists(workspace_root: &Path) -> bool {
        CONFIG_CANDIDATES
            .iter()
            .any(|name| workspace_root.join(name).exists())
    }
}

fn default_true() -> bool {
    true
}

fn default_runs_base() -> PathBuf {
    PathBuf::from("target/cherry-test/runs")
}

fn default_report_dir() -> PathBuf {
    PathBuf::from("target/cherry-test/reports")
}

fn is_default_report_dir(path: &PathBuf) -> bool {
    path.as_path() == Path::new("target/cherry-test/reports")
}

fn default_scope() -> String {
    "global".into()
}

fn default_diff_base() -> String {
    "main".into()
}

fn locate_config(workspace_root: &Path) -> Result<(PathBuf, bool)> {
    for name in CONFIG_CANDIDATES {
        let path = workspace_root.join(name);
        if path.exists() {
            return Ok((path, false));
        }
    }
    Ok((workspace_root.join("cherry-test.yaml"), false))
}

fn parse_raw_config(path: &Path, contents: &str) -> Result<RawConfig> {
    let ext = path
        .extension()
        .and_then(|e| e.to_str())
        .unwrap_or("")
        .to_lowercase();
    match ext.as_str() {
        "yaml" | "yml" => {
            serde_yaml::from_str(contents).with_context(|| format!("parse {}", path.display()))
        }
        "toml" => toml::from_str(contents).with_context(|| format!("parse {}", path.display())),
        _ => bail!("unsupported config format {}", path.display()),
    }
}

fn merge_legacy_packages(
    projects: &mut BTreeMap<String, ProjectConfig>,
    legacy_packages: BTreeMap<String, PackageConfig>,
    workspace_root: &Path,
) {
    for (package_name, package_cfg) in legacy_packages {
        if projects
            .values()
            .any(|p| p.packages.contains_key(&package_name))
        {
            continue;
        }
        let Some(project_id) = find_project_for_package(workspace_root, projects, &package_name)
        else {
            continue;
        };
        projects
            .entry(project_id)
            .or_default()
            .packages
            .insert(package_name, package_cfg);
    }
}

fn find_project_for_package(
    workspace_root: &Path,
    projects: &BTreeMap<String, ProjectConfig>,
    package_name: &str,
) -> Option<String> {
    for (id, project) in projects {
        let base = project.path.as_deref().unwrap_or(id.as_str());
        let manifest = workspace_root
            .join(base)
            .join(package_name)
            .join("Cargo.toml");
        if manifest.exists() {
            return Some(id.clone());
        }
        let node_manifest = workspace_root
            .join(base)
            .join(package_name)
            .join("package.json");
        if node_manifest.exists() {
            return Some(id.clone());
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_yaml_defaults_and_projects() {
        let yaml = r#"
defaults:
  scope: global
  diff_base: main
projects:
  mini:
    path: mini
    diff_base: main
    packages:
      mini-rs-api:
        kinds: [unit, integration]
"#;
        let raw: RawConfig = serde_yaml::from_str(yaml).unwrap();
        assert_eq!(raw.defaults.diff_base, "main");
        assert!(raw.projects.contains_key("mini"));
    }

    #[test]
    fn parses_a_mutation_runner() {
        let yaml = r#"
packages:
  mini-rs-lib-mcap:
    kinds: [mutation]
    mutation:
      adapter: command
      command: cargo
      args: [mutants, --package, mini-rs-lib-mcap]
"#;
        let raw: RawConfig = serde_yaml::from_str(yaml).unwrap();
        let package = &raw.packages["mini-rs-lib-mcap"];
        assert_eq!(package.kinds, ["mutation"]);
        let runner = package.mutation.as_ref().unwrap();
        assert_eq!(runner.adapter.as_deref(), Some("command"));
        assert_eq!(runner.command, "cargo");
        assert_eq!(runner.args, ["mutants", "--package", "mini-rs-lib-mcap"]);
    }

    #[test]
    fn diff_base_falls_back_to_defaults() {
        let mut cfg = TestItConfig::default();
        cfg.defaults.diff_base = "main".into();
        cfg.projects.insert(
            "mini".into(),
            ProjectConfig {
                diff_base: Some("develop".into()),
                ..Default::default()
            },
        );
        assert_eq!(cfg.diff_base_for_project("mini"), "develop");
    }

    mod with_execution {
        use super::*;

        fn config_with_container_package() -> TestItConfig {
            let mut cfg = TestItConfig::default();
            let mut packages = BTreeMap::new();
            packages.insert(
                "prices-c".into(),
                PackageConfig {
                    execution: Some(ExecutionMode::Container),
                    ..Default::default()
                },
            );
            cfg.projects.insert(
                "prices".into(),
                ProjectConfig {
                    packages,
                    ..Default::default()
                },
            );
            cfg
        }

        #[test]
        fn sets_the_default_execution() {
            let cfg = TestItConfig::default().with_execution(ExecutionMode::Container);
            assert_eq!(cfg.defaults.execution, ExecutionMode::Container);
        }

        #[test]
        fn overrides_package_execution() {
            let cfg = config_with_container_package().with_execution(ExecutionMode::Local);
            assert_eq!(cfg.projects["prices"].packages["prices-c"].execution, None);
        }
    }
}
