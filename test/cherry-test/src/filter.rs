use anyhow::{bail, Result};

use crate::discover::{all_package_names, ProjectGroup};

pub fn apply_filter(
    projects: Vec<ProjectGroup>,
    filter: Option<&str>,
) -> Result<Vec<ProjectGroup>> {
    let Some(filter) = filter else {
        return Ok(projects);
    };

    let by_project: Vec<ProjectGroup> = projects
        .iter()
        .filter(|p| p.id == filter)
        .cloned()
        .collect();
    if !by_project.is_empty() {
        return Ok(by_project);
    }

    let by_package: Vec<ProjectGroup> = projects
        .iter()
        .filter_map(|p| {
            let members: Vec<_> = p
                .members
                .iter()
                .filter(|m| m.name == filter)
                .cloned()
                .collect();
            if members.is_empty() {
                None
            } else {
                Some(ProjectGroup {
                    id: p.id.clone(),
                    members,
                })
            }
        })
        .collect();
    if !by_package.is_empty() {
        return Ok(by_package);
    }

    let project_ids: Vec<_> = projects.iter().map(|p| p.id.as_str()).collect();
    let packages = all_package_names(&projects);
    bail!(
        "unknown filter '{filter}'. Valid project groups: [{}]. Valid packages: [{}]",
        project_ids.join(", "),
        packages.join(", ")
    );
}

#[cfg(test)]
mod tests {
    use std::path::PathBuf;

    use super::*;
    use crate::discover::{ProjectGroup, WorkspaceMember};
    use cherry_test_core::PackageRuntime;

    fn sample_projects() -> Vec<ProjectGroup> {
        vec![ProjectGroup {
            id: "mini".into(),
            members: vec![WorkspaceMember {
                name: "mini-rs-api".into(),
                manifest_path: PathBuf::from("mini/mini-rs-api/Cargo.toml"),
                project_id: "mini".into(),
                runtime: PackageRuntime::Rust,
            }],
        }]
    }

    #[test]
    fn no_filter_returns_all() {
        let projects = sample_projects();
        let out = apply_filter(projects.clone(), None).unwrap();
        assert_eq!(out.len(), 1);
    }

    #[test]
    fn filter_by_project_id() {
        let out = apply_filter(sample_projects(), Some("mini")).unwrap();
        assert_eq!(out[0].id, "mini");
    }

    #[test]
    fn filter_by_package_name() {
        let out = apply_filter(sample_projects(), Some("mini-rs-api")).unwrap();
        assert_eq!(out[0].members[0].name, "mini-rs-api");
    }

    #[test]
    fn unknown_filter_errors() {
        let err = apply_filter(sample_projects(), Some("nope")).unwrap_err();
        assert!(err.to_string().contains("unknown filter"));
    }
}
