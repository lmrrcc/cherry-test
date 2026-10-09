use std::collections::BTreeSet;
use std::path::{Path, PathBuf};

use anyhow::{bail, Result};
use cargo_metadata::MetadataCommand;
use cherry_test_core::{PackageRuntime, TestItConfig};

use crate::config::TestKind;
use crate::discover::{ProjectGroup, WorkspaceMember};
use crate::filter;
use crate::node_runtime::resolve_node_binary;
use crate::preflight::{self, DiscoveredTest, PreflightPlan};

#[derive(Debug, Clone)]
pub struct TestPlan {
    pub config: TestItConfig,
    pub projects: Vec<ProjectGroup>,
    pub discovered: Vec<DiscoveredTest>,
    pub preflight: PreflightPlan,
}

pub fn initialize(workspace_root: &Path, filter: Option<&str>, quiet: bool) -> Result<TestPlan> {
    let config = TestItConfig::load(workspace_root)?;
    initialize_with_config(workspace_root, config, filter, quiet)
}

pub fn initialize_with_config(
    workspace_root: &Path,
    config: TestItConfig,
    filter: Option<&str>,
    quiet: bool,
) -> Result<TestPlan> {
    if config.projects.is_empty() {
        bail!(
            "no projects configured; add projects to {}",
            config.config_path.display()
        );
    }

    let workspace_packages = load_workspace_package_names(workspace_root)?;
    let projects = resolve_projects_from_config(workspace_root, &config, &workspace_packages)?;

    let projects = filter::apply_filter(projects, filter)?;
    if projects.is_empty() {
        bail!("no testable workspace members found");
    }

    let preflight = preflight::build_preflight_plan(workspace_root, &projects, &config, quiet)?;
    warn_node_prerequisites(&projects, &config, quiet);
    print_init_summary(&projects, &config, &preflight, quiet);

    Ok(TestPlan {
        discovered: preflight.discovered.clone(),
        config,
        projects,
        preflight,
    })
}

fn resolve_projects_from_config(
    workspace_root: &Path,
    config: &TestItConfig,
    workspace_packages: &BTreeSet<String>,
) -> Result<Vec<ProjectGroup>> {
    let mut out = Vec::new();

    for (project_id, project) in &config.projects {
        let base = project.path.as_deref().unwrap_or(project_id.as_str());
        let project_dir = workspace_root.join(base);
        if !project_dir.is_dir() {
            bail!(
                "project `{project_id}` path `{}` does not exist",
                project_dir.display()
            );
        }

        if project.packages.is_empty() {
            bail!("project `{project_id}` has no packages configured");
        }

        let mut members = Vec::new();
        for (package_name, package_cfg) in &project.packages {
            let runtime = package_cfg.runtime;
            if package_cfg.adapter.is_none()
                && runtime == PackageRuntime::Rust
                && !workspace_packages.is_empty()
                && !workspace_packages.contains(package_name)
            {
                bail!(
                    "package `{package_name}` in project `{project_id}` is not a workspace member"
                );
            }

            let manifest = package_cfg.manifest.clone().unwrap_or_else(|| {
                if runtime == PackageRuntime::Node {
                    PathBuf::from(base).join(package_name).join("package.json")
                } else {
                    PathBuf::from(base).join(package_name).join("Cargo.toml")
                }
            });
            let manifest_path = if manifest.is_absolute() {
                manifest
            } else {
                workspace_root.join(manifest)
            };

            if !manifest_path.exists() {
                bail!(
                    "manifest for `{package_name}` not found at {}",
                    manifest_path.display()
                );
            }

            let rel = manifest_path
                .strip_prefix(workspace_root)
                .unwrap_or(&manifest_path)
                .to_path_buf();

            members.push(WorkspaceMember {
                name: package_name.clone(),
                manifest_path: rel,
                project_id: project_id.clone(),
                runtime,
            });
        }

        members.sort_by(|a, b| a.name.cmp(&b.name));
        out.push(ProjectGroup {
            id: project_id.clone(),
            members,
        });
    }

    Ok(out)
}

fn load_workspace_package_names(workspace_root: &Path) -> Result<BTreeSet<String>> {
    let manifest = workspace_root.join("Cargo.toml");
    if !manifest.exists() {
        return Ok(BTreeSet::new());
    }
    let contents = std::fs::read_to_string(&manifest)?;
    if !contents.contains("[workspace]") {
        return Ok(BTreeSet::new());
    }

    let metadata = match MetadataCommand::new().manifest_path(manifest).exec() {
        Ok(metadata) => metadata,
        Err(_) => return Ok(BTreeSet::new()),
    };

    let names: BTreeSet<String> = metadata
        .packages
        .iter()
        .filter(|p| metadata.workspace_members.contains(&p.id))
        .map(|p| p.name.clone())
        .collect();

    Ok(names)
}

fn print_init_summary(
    projects: &[ProjectGroup],
    config: &TestItConfig,
    preflight: &PreflightPlan,
    quiet: bool,
) {
    if quiet {
        return;
    }

    let package_count: usize = projects.iter().map(|p| p.members.len()).sum();
    let integration_count = preflight
        .discovered
        .iter()
        .filter(|t| t.suite != "unit")
        .count();
    let unit_count = preflight.discovered.len().saturating_sub(integration_count);
    let scoped = preflight
        .discovered
        .iter()
        .filter(|t| cherry_test_core::needs_docker(&t.effective_scope))
        .count();

    println!(
        "{} init: {} projects, {} packages, {} test(s) ({} unit, {} integration, {} scoped)",
        crate::COMMAND_NAME,
        projects.len(),
        package_count,
        preflight.discovered.len(),
        unit_count,
        integration_count,
        scoped
    );

    for project in projects {
        for member in &project.members {
            let kinds = config.package_kinds(&member.name, &project.id);
            let kind_labels: Vec<_> = kinds
                .iter()
                .filter_map(|k| TestKind::parse(k))
                .map(|k| k.as_str())
                .collect();
            let unit_count = preflight
                .discovered
                .iter()
                .filter(|t| t.package == member.name && t.suite == "unit")
                .count();
            let integration_count = preflight
                .discovered
                .iter()
                .filter(|t| t.package == member.name && t.suite != "unit")
                .count();
            let mut line = format!(
                "  {}/{} — {}",
                project.id,
                member.name,
                kind_labels.join(", ")
            );
            if unit_count > 0 {
                line.push_str(&format!(" ({unit_count} unit test(s))"));
            }
            if integration_count > 0 {
                line.push_str(&format!(" ({integration_count} integration test(s))"));
            }
            println!("{line}");
        }
    }
    println!();
}

fn warn_node_prerequisites(projects: &[ProjectGroup], config: &TestItConfig, quiet: bool) {
    if quiet {
        return;
    }
    let needs_node = projects.iter().any(|project| {
        project
            .members
            .iter()
            .any(|member| config.package_runtime(&member.name, &project.id) == PackageRuntime::Node)
    });
    if !needs_node {
        return;
    }
    if !command_available("node") && resolve_node_binary(Path::new(".")).is_none() {
        eprintln!("warning: node not found; node packages may fail");
    }
}

fn command_available(name: &str) -> bool {
    std::process::Command::new(name)
        .arg("--version")
        .output()
        .map(|o| o.status.success())
        .unwrap_or(false)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    #[test]
    fn resolve_projects_from_yaml_fixture() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path();
        fs::create_dir_all(root.join("mini/mini-rs-api")).unwrap();
        fs::write(root.join("Cargo.toml"), "[workspace]\nmembers = []\n").unwrap();
        fs::write(
            root.join("mini/mini-rs-api/Cargo.toml"),
            "[package]\nname = \"mini-rs-api\"\n",
        )
        .unwrap();
        fs::write(
            root.join("cherry-test.yaml"),
            r#"
projects:
  mini:
    path: mini
    packages:
      mini-rs-api:
        kinds: [unit]
"#,
        )
        .unwrap();

        let config = TestItConfig::load(root).unwrap();
        let mut names = BTreeSet::new();
        names.insert("mini-rs-api".into());
        let projects = resolve_projects_from_config(root, &config, &names).unwrap();
        assert_eq!(projects.len(), 1);
        assert_eq!(projects[0].members[0].name, "mini-rs-api");
    }

    #[test]
    fn missing_manifest_errors() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path();
        fs::create_dir_all(root.join("mini/mini-rs-api")).unwrap();
        fs::write(
            root.join("cherry-test.yaml"),
            r#"
projects:
  mini:
    path: mini
    packages:
      mini-rs-api:
        kinds: [unit]
"#,
        )
        .unwrap();

        let config = TestItConfig::load(root).unwrap();
        let mut names = BTreeSet::new();
        names.insert("mini-rs-api".into());
        let err = resolve_projects_from_config(root, &config, &names).unwrap_err();
        assert!(err.to_string().contains("manifest"));
    }
}
