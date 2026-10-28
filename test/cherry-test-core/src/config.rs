use std::collections::{BTreeMap, HashMap};
use std::path::{Path, PathBuf};

use anyhow::{bail, Context, Result};
use serde::{Deserialize, Serialize};

pub use cherry_test_common::adapter::{AdapterConfig, ExecutionMode};
pub use cherry_test_common::container::ContainerConfig;

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
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub parallel: Option<ParallelConfig>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub container: Option<ContainerConfig>,
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
            parallel: None,
            container: None,
        }
    }
}

/// How many jobs run at once: a fixed number, or `auto` to follow the CPU budget.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Jobs {
    Auto,
    Count(u32),
}

impl std::str::FromStr for Jobs {
    type Err = String;

    fn from_str(value: &str) -> std::result::Result<Self, Self::Err> {
        if value == "auto" {
            return Ok(Jobs::Auto);
        }
        match value.parse::<u32>() {
            Ok(count) if count > 0 => Ok(Jobs::Count(count)),
            _ => Err("expected a positive number or `auto`".into()),
        }
    }
}

impl std::fmt::Display for Jobs {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Jobs::Auto => f.write_str("auto"),
            Jobs::Count(count) => write!(f, "{count}"),
        }
    }
}

impl Serialize for Jobs {
    fn serialize<S: serde::Serializer>(
        &self,
        serializer: S,
    ) -> std::result::Result<S::Ok, S::Error> {
        match self {
            Jobs::Auto => serializer.serialize_str("auto"),
            Jobs::Count(count) => serializer.serialize_u32(*count),
        }
    }
}

impl<'de> Deserialize<'de> for Jobs {
    fn deserialize<D: serde::Deserializer<'de>>(
        deserializer: D,
    ) -> std::result::Result<Self, D::Error> {
        match ScalarValue::deserialize(deserializer)? {
            ScalarValue::Integer(count) => u32::try_from(count)
                .ok()
                .filter(|count| *count > 0)
                .map(Jobs::Count)
                .ok_or_else(|| serde::de::Error::custom("expected a positive number or `auto`")),
            ScalarValue::Text(text) => text.parse().map_err(serde::de::Error::custom),
            ScalarValue::Float(_) => Err(serde::de::Error::custom(
                "expected a positive number or `auto`",
            )),
        }
    }
}

/// A CPU amount in thousandths of a CPU; YAML gives it as a number such as `2` or `0.5`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Cpus(pub u64);

impl Serialize for Cpus {
    fn serialize<S: serde::Serializer>(
        &self,
        serializer: S,
    ) -> std::result::Result<S::Ok, S::Error> {
        serializer.serialize_f64(self.0 as f64 / 1000.0)
    }
}

impl<'de> Deserialize<'de> for Cpus {
    fn deserialize<D: serde::Deserializer<'de>>(
        deserializer: D,
    ) -> std::result::Result<Self, D::Error> {
        let value = match ScalarValue::deserialize(deserializer)? {
            ScalarValue::Integer(count) => count as f64,
            ScalarValue::Float(value) => value,
            ScalarValue::Text(_) => {
                return Err(serde::de::Error::custom(
                    "expected a positive number of CPUs",
                ))
            }
        };
        let millicpus = (value * 1000.0).round();
        if !value.is_finite() || millicpus < 1.0 {
            return Err(serde::de::Error::custom(
                "expected a positive number of CPUs",
            ));
        }
        Ok(Cpus(millicpus as u64))
    }
}

/// A memory amount in bytes; YAML gives it as bytes or with a binary unit such as `512m` or `8g`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Memory(pub u64);

impl Memory {
    /// Parses `8g`, `1.5G`, `512mi`, `512MiB`, `1024k` or a plain byte count; units are powers of 1024.
    pub fn parse(value: &str) -> std::result::Result<Self, String> {
        let error = || format!("invalid memory `{value}`; use bytes or a unit such as 512m or 8g");
        let text = value.trim().to_ascii_lowercase();
        let digits_end = text
            .find(|ch: char| !ch.is_ascii_digit() && ch != '.')
            .unwrap_or(text.len());
        let (number, unit) = text.split_at(digits_end);
        let unit = unit.trim();
        let unit = unit.strip_suffix('b').unwrap_or(unit);
        let unit = unit.strip_suffix('i').unwrap_or(unit);
        let multiplier: u64 = match unit {
            "" => 1,
            "k" => 1 << 10,
            "m" => 1 << 20,
            "g" => 1 << 30,
            "t" => 1 << 40,
            _ => return Err(error()),
        };
        if number.is_empty() || number.starts_with('.') || number.ends_with('.') {
            return Err(error());
        }
        let amount: f64 = number.parse().map_err(|_| error())?;
        let bytes = (amount * multiplier as f64).floor();
        if !bytes.is_finite() || bytes < 1.0 || bytes >= (1u64 << 63) as f64 {
            return Err(error());
        }
        Ok(Memory(bytes as u64))
    }
}

impl Serialize for Memory {
    fn serialize<S: serde::Serializer>(
        &self,
        serializer: S,
    ) -> std::result::Result<S::Ok, S::Error> {
        serializer.serialize_u64(self.0)
    }
}

impl<'de> Deserialize<'de> for Memory {
    fn deserialize<D: serde::Deserializer<'de>>(
        deserializer: D,
    ) -> std::result::Result<Self, D::Error> {
        match ScalarValue::deserialize(deserializer)? {
            ScalarValue::Integer(bytes) if bytes > 0 => Ok(Memory(bytes as u64)),
            ScalarValue::Text(text) => Memory::parse(&text).map_err(serde::de::Error::custom),
            _ => Err(serde::de::Error::custom(
                "expected bytes or a unit such as 512m or 8g",
            )),
        }
    }
}

#[derive(Deserialize)]
#[serde(untagged)]
enum ScalarValue {
    Integer(i64),
    Float(f64),
    Text(String),
}

/// `defaults.parallel`: how many jobs run at once and the CPU and memory they may share.
#[derive(Debug, Clone, Deserialize, Serialize, PartialEq)]
#[serde(deny_unknown_fields)]
pub struct ParallelConfig {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub jobs: Option<Jobs>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub cpus: Option<Cpus>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub memory: Option<Memory>,
    #[serde(default = "default_true")]
    pub phase_barrier: bool,
}

impl Default for ParallelConfig {
    fn default() -> Self {
        Self {
            jobs: None,
            cpus: None,
            memory: None,
            phase_barrier: true,
        }
    }
}

/// `packages.<name>.resources`: what one job of the package reserves while it runs.
#[derive(Debug, Clone, Default, Deserialize, Serialize, PartialEq)]
#[serde(deny_unknown_fields)]
pub struct ResourcesConfig {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub cpus: Option<Cpus>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub memory: Option<Memory>,
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
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub resources: Option<ResourcesConfig>,
    #[serde(default, skip_serializing_if = "is_false")]
    pub exclusive: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub container: Option<ContainerConfig>,
}

fn is_false(value: &bool) -> bool {
    !*value
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
    #[serde(default, skip_serializing_if = "BTreeMap::is_empty")]
    groups: BTreeMap<String, Vec<String>>,
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
    /// `groups.<name>`: package names or globs that the filter `@name` selects.
    pub groups: BTreeMap<String, Vec<String>>,
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
            groups: BTreeMap::new(),
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

    /// Whether any package runs in a container with a `container:` block, so the run
    /// names, labels and cleans up its containers.
    pub fn container_settings_in_use(&self) -> bool {
        self.projects
            .values()
            .flat_map(|project| project.packages.values())
            .any(|package| {
                package.execution.unwrap_or(self.defaults.execution) == ExecutionMode::Container
                    && (package.container.is_some() || self.defaults.container.is_some())
            })
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
            groups: raw.groups,
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
            groups: self.groups.clone(),
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
    fn parses_parallel_defaults_package_resources_and_groups() {
        let yaml = r#"
defaults:
  parallel:
    jobs: auto
    cpus: 6
    memory: 12g
    phase_barrier: false
packages:
  api:
    resources:
      cpus: 0.5
      memory: 512m
    exclusive: true
groups:
  backend: [api, "mini-rs-*"]
"#;
        let raw: RawConfig = serde_yaml::from_str(yaml).unwrap();
        let parallel = raw.defaults.parallel.unwrap();
        assert_eq!(parallel.jobs, Some(Jobs::Auto));
        assert_eq!(parallel.cpus, Some(Cpus(6000)));
        assert_eq!(parallel.memory, Some(Memory(12 << 30)));
        assert!(!parallel.phase_barrier);
        let api = &raw.packages["api"];
        let resources = api.resources.clone().unwrap();
        assert_eq!(resources.cpus, Some(Cpus(500)));
        assert_eq!(resources.memory, Some(Memory(512 << 20)));
        assert!(api.exclusive);
        assert_eq!(raw.groups["backend"], ["api", "mini-rs-*"]);
        let defaults: RawConfig =
            serde_yaml::from_str("defaults:\n  parallel:\n    jobs: 4\n").unwrap();
        let parallel = defaults.defaults.parallel.unwrap();
        assert_eq!(parallel.jobs, Some(Jobs::Count(4)));
        assert!(parallel.phase_barrier);
        assert!(serde_yaml::to_string(&Defaults::default())
            .unwrap()
            .lines()
            .all(|line| !line.starts_with("parallel")));
    }

    #[test]
    fn rejects_unknown_parallel_fields_and_invalid_amounts() {
        for yaml in [
            "defaults:\n  parallel:\n    workers: 2\n",
            "packages:\n  api:\n    resources:\n      gpus: 1\n",
            "defaults:\n  parallel:\n    jobs: 0\n",
            "defaults:\n  parallel:\n    jobs: many\n",
            "defaults:\n  parallel:\n    cpus: 0\n",
            "defaults:\n  parallel:\n    memory: 8x\n",
        ] {
            assert!(serde_yaml::from_str::<RawConfig>(yaml).is_err(), "{yaml}");
        }
    }

    #[test]
    fn parses_memory_units_as_powers_of_1024() {
        assert_eq!(Memory::parse("1024"), Ok(Memory(1024)));
        assert_eq!(Memory::parse("1k"), Ok(Memory(1024)));
        assert_eq!(Memory::parse("512MiB"), Ok(Memory(512 << 20)));
        assert_eq!(Memory::parse("1.5G"), Ok(Memory(3 << 29)));
        assert_eq!(Memory::parse(" 2 gb "), Ok(Memory(2 << 30)));
        assert_eq!(Memory::parse("1t"), Ok(Memory(1 << 40)));
        for invalid in ["", "g", ".5g", "5.g", "-1g", "0", "1p", "1..5g"] {
            assert!(Memory::parse(invalid).is_err(), "{invalid}");
        }
        assert_eq!("auto".parse::<Jobs>(), Ok(Jobs::Auto));
        assert_eq!("3".parse::<Jobs>(), Ok(Jobs::Count(3)));
        assert!("0".parse::<Jobs>().is_err());
        assert_eq!(Jobs::Count(3).to_string(), "3");
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

    mod container {
        use super::*;
        use cherry_test_common::container::{
            NetworkMode, PullPolicy, Quantity, SeedMode, Timeout, WorkspaceAccess, WritableConfig,
        };

        fn parse(yaml: &str) -> Result<RawConfig> {
            parse_raw_config(Path::new("cherry-test.yaml"), yaml)
        }

        fn error(yaml: &str) -> String {
            format!("{:#}", parse(yaml).unwrap_err())
        }

        #[test]
        fn parses_a_container_block_in_defaults_and_packages() {
            let raw = parse(
                r#"
defaults:
  execution: container
  container:
    image: alpine:3.20
    pull: missing
    network: bridge
    user: host
    cap_drop: [ALL]
    no_new_privileges: true
    read_only_root: true
    cpus: 2
    memory: 2g
    pids: 256
    shm_size: 64m
    tmpfs: [/tmp]
    timeout: 30m
    workspace: ro
    writable: [target, {path: build, seed: copy}]
    mounts:
      - {source: ../sibling}
      - {source: ../data, target: /data, read_only: false}
    caches:
      - {volume: cherry-test-m2, target: /tmp/home/.m2}
    env:
      pass: [CI]
      set: {LANG: C.UTF-8}
packages:
  prices:
    container:
      network: none
      timeout: 90
"#,
            )
            .unwrap();
            let container = raw.defaults.container.unwrap();
            assert_eq!(container.image.as_deref(), Some("alpine:3.20"));
            assert_eq!(container.pull, Some(PullPolicy::Missing));
            assert_eq!(container.network, Some(NetworkMode::Bridge));
            assert_eq!(container.user.as_deref(), Some("host"));
            assert_eq!(container.cpus, Some(Quantity("2".into())));
            assert_eq!(container.memory, Some(Quantity("2g".into())));
            assert_eq!(container.pids, Some(256));
            assert_eq!(container.timeout, Some(Timeout(1800)));
            assert_eq!(container.workspace, Some(WorkspaceAccess::Ro));
            assert_eq!(
                container.writable.unwrap()[1],
                WritableConfig {
                    path: "build".into(),
                    seed: Some(SeedMode::Copy)
                }
            );
            let mounts = container.mounts.unwrap();
            assert_eq!(mounts[0].target, None);
            assert!(mounts[0].read_only);
            assert!(!mounts[1].read_only);
            assert_eq!(container.caches.unwrap()[0].target, "/tmp/home/.m2");
            assert_eq!(container.env.unwrap().pass, Some(vec!["CI".into()]));
            let package = raw.packages["prices"].container.clone().unwrap();
            assert_eq!(package.network, Some(NetworkMode::None));
            assert_eq!(package.timeout, Some(Timeout(90)));
            assert_eq!(package.image, None);
        }

        #[test]
        fn rejects_unknown_container_keys() {
            let message =
                error("defaults:\n  container:\n    image: alpine\n    privileged: true\n");
            assert!(message.contains("defaults.container: unknown field `privileged`, expected one of `image`, `pull`"), "{message}");
            let message = error("projects:\n  demo:\n    packages:\n      api:\n        container:\n          netwrok: none\n");
            assert!(message.contains("unknown field `netwrok`"), "{message}");
        }

        #[test]
        fn rejects_unknown_keys_in_container_entries() {
            for (yaml, field) in [
                ("writable: [{path: target, mode: rw}]", "mode"),
                ("mounts: [{source: ../data, readonly: true}]", "readonly"),
                ("caches: [{volume: m2, target: /m2, size: 1g}]", "size"),
                ("env: {passthrough: [CI]}", "passthrough"),
            ] {
                let message = error(&format!("defaults:\n  container:\n    {yaml}\n"));
                assert!(
                    message.contains(&format!("unknown field `{field}`")),
                    "{message}"
                );
            }
        }

        #[test]
        fn rejects_unknown_container_values() {
            for (yaml, expected) in [
                (
                    "network: host",
                    "unknown variant `host`, expected `none` or `bridge`",
                ),
                (
                    "pull: sometimes",
                    "unknown variant `sometimes`, expected one of `never`, `missing`, `always`",
                ),
                (
                    "workspace: readonly",
                    "unknown variant `readonly`, expected `ro` or `rw`",
                ),
                (
                    "writable: [{path: target, seed: link}]",
                    "unknown variant `link`, expected `copy`",
                ),
                ("timeout: soon", "invalid duration `soon`"),
            ] {
                let message = error(&format!("defaults:\n  container:\n    {yaml}\n"));
                assert!(message.contains(expected), "{message}");
            }
        }

        #[test]
        fn documented_container_examples_parse() {
            let mut examples = 0;
            for doc in [
                include_str!("../../../README.md"),
                include_str!("../../../docs/adapters.md"),
            ] {
                for block in doc.split("```yaml\n").skip(1) {
                    let yaml = block.split("```").next().unwrap();
                    if !yaml.contains("  container:\n") {
                        continue;
                    }
                    let raw = parse(yaml).unwrap();
                    assert!(raw.defaults.container.is_some());
                    examples += 1;
                }
            }
            assert_eq!(examples, 2);
        }

        #[test]
        fn container_settings_apply_only_with_container_execution() {
            let mut cfg = TestItConfig::default();
            cfg.projects.insert(
                "prices".into(),
                ProjectConfig {
                    packages: BTreeMap::from([("api".to_string(), PackageConfig::default())]),
                    ..Default::default()
                },
            );
            assert!(!cfg.container_settings_in_use());
            cfg.defaults.container = Some(ContainerConfig::default());
            assert!(!cfg.container_settings_in_use());
            let cfg = cfg.with_execution(ExecutionMode::Container);
            assert!(cfg.container_settings_in_use());
        }
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
