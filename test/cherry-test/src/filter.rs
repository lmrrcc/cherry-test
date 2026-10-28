use std::collections::BTreeMap;

use anyhow::{bail, Result};

use crate::discover::{all_package_names, ProjectGroup};
use crate::preflight::glob_matches;

/// Selects a project id, a package name or, with `@name`, the packages of a configured group.
pub fn apply_filter(
    projects: Vec<ProjectGroup>,
    filter: Option<&str>,
    groups: &BTreeMap<String, Vec<String>>,
) -> Result<Vec<ProjectGroup>> {
    let Some(filter) = filter else {
        return Ok(projects);
    };
    if let Some(group) = filter.strip_prefix('@') {
        return select_group(projects, group, groups);
    }

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

/// The packages a group names, exactly or by `*`/`?` glob, kept in configuration order.
fn select_group(
    projects: Vec<ProjectGroup>,
    group: &str,
    groups: &BTreeMap<String, Vec<String>>,
) -> Result<Vec<ProjectGroup>> {
    let Some(patterns) = groups.get(group) else {
        let names: Vec<String> = groups.keys().map(|name| format!("@{name}")).collect();
        bail!(
            "unknown group '@{group}'. Valid groups: [{}]",
            names.join(", ")
        );
    };
    let packages = all_package_names(&projects);
    for pattern in patterns {
        let glob = pattern.contains(['*', '?']);
        if !glob && !packages.iter().any(|name| name == pattern) {
            bail!("group '@{group}' names unknown package '{pattern}'");
        }
    }
    let selected: Vec<ProjectGroup> = projects
        .into_iter()
        .filter_map(|project| {
            let members: Vec<_> = project
                .members
                .into_iter()
                .filter(|member| {
                    patterns
                        .iter()
                        .any(|pattern| glob_matches(pattern, &member.name))
                })
                .collect();
            (!members.is_empty()).then_some(ProjectGroup {
                id: project.id,
                members,
            })
        })
        .collect();
    if selected.is_empty() {
        bail!("group '@{group}' matches no packages");
    }
    Ok(selected)
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
        let out = apply_filter(projects.clone(), None, &BTreeMap::new()).unwrap();
        assert_eq!(out.len(), 1);
    }

    #[test]
    fn filter_by_project_id() {
        let out = apply_filter(sample_projects(), Some("mini"), &BTreeMap::new()).unwrap();
        assert_eq!(out[0].id, "mini");
    }

    #[test]
    fn filter_by_package_name() {
        let out = apply_filter(sample_projects(), Some("mini-rs-api"), &BTreeMap::new()).unwrap();
        assert_eq!(out[0].members[0].name, "mini-rs-api");
    }

    #[test]
    fn unknown_filter_errors() {
        let err = apply_filter(sample_projects(), Some("nope"), &BTreeMap::new()).unwrap_err();
        assert!(err.to_string().contains("unknown filter"));
    }

    fn two_projects() -> Vec<ProjectGroup> {
        [
            ("mini", ["mini-rs-api", "mini-ts-web"]),
            ("shop", ["shop-rs-api", "shop-ts-web"]),
        ]
        .into_iter()
        .map(|(id, names)| ProjectGroup {
            id: id.into(),
            members: names
                .into_iter()
                .map(|name| WorkspaceMember {
                    name: name.into(),
                    manifest_path: PathBuf::from(format!("{id}/{name}/Cargo.toml")),
                    project_id: id.into(),
                    runtime: PackageRuntime::Rust,
                })
                .collect(),
        })
        .collect()
    }

    #[test]
    fn filter_by_group_selects_named_and_globbed_packages() {
        let groups = BTreeMap::from([(
            "rust".to_string(),
            vec!["*-rs-*".to_string(), "mini-ts-web".to_string()],
        )]);
        let out = apply_filter(two_projects(), Some("@rust"), &groups).unwrap();
        let names: Vec<_> = out
            .iter()
            .flat_map(|p| p.members.iter().map(|m| m.name.as_str()))
            .collect();
        assert_eq!(names, ["mini-rs-api", "mini-ts-web", "shop-rs-api"]);
        assert_eq!(out.len(), 2);
    }

    #[test]
    fn unknown_or_empty_groups_error() {
        let groups = BTreeMap::from([
            ("web".to_string(), vec!["*-web".to_string()]),
            ("typo".to_string(), vec!["shop-rs-apii".to_string()]),
            ("none".to_string(), vec!["*-go-*".to_string()]),
        ]);
        let error = apply_filter(two_projects(), Some("@nope"), &groups).unwrap_err();
        assert_eq!(
            error.to_string(),
            "unknown group '@nope'. Valid groups: [@none, @typo, @web]"
        );
        let error = apply_filter(two_projects(), Some("@typo"), &groups).unwrap_err();
        assert_eq!(
            error.to_string(),
            "group '@typo' names unknown package 'shop-rs-apii'"
        );
        let error = apply_filter(two_projects(), Some("@none"), &groups).unwrap_err();
        assert_eq!(error.to_string(), "group '@none' matches no packages");
    }
}
