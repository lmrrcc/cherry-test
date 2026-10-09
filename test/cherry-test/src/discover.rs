use std::path::PathBuf;

use anyhow::Result;
use cherry_test_core::{resolve_config_roots, resolve_repo_root, PackageRuntime};

#[derive(Debug, Clone)]
pub struct WorkspaceMember {
    pub name: String,
    pub manifest_path: PathBuf,
    pub project_id: String,
    pub runtime: PackageRuntime,
}

#[derive(Debug, Clone)]
pub struct ProjectGroup {
    pub id: String,
    pub members: Vec<WorkspaceMember>,
}

pub fn workspace_root() -> Result<PathBuf> {
    resolve_repo_root(None)
}

pub fn workspace_root_from(override_root: Option<&std::path::Path>) -> Result<PathBuf> {
    resolve_repo_root(override_root)
}

pub fn config_roots_from(
    override_root: Option<&std::path::Path>,
    filter: Option<&str>,
) -> Result<Vec<PathBuf>> {
    resolve_config_roots(override_root, filter)
}

pub fn all_package_names(projects: &[ProjectGroup]) -> Vec<String> {
    projects
        .iter()
        .flat_map(|p| p.members.iter().map(|m| m.name.clone()))
        .collect()
}
