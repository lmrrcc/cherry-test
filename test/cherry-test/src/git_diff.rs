use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::process::Command;

use anyhow::{Context, Result};
use cherry_test_core::TestItConfig;

use crate::discover::ProjectGroup;

pub fn changed_files_by_project(
    workspace_root: &Path,
    projects: &[ProjectGroup],
    config: &TestItConfig,
) -> Result<BTreeMap<String, Vec<String>>> {
    let config_base = config.config_base();
    let changed_projects: Vec<cherry_test_common::git_diff::ChangedFilesProject> = projects
        .iter()
        .map(|project| {
            let root = project_root(workspace_root, config, project);
            let (git_root, path_prefix) =
                cherry_test_common::git_diff::resolve_project_git_scope(&config_base, &root, None);
            cherry_test_common::git_diff::ChangedFilesProject {
                id: project.id.clone(),
                diff_base: config.diff_base_for_project(&project.id),
                changed_scope: cherry_test_common::config::ReviewChangedScope::Branch,
                package_prefixes: package_prefixes(workspace_root, config, project),
                git_root,
                workspace_root: config_base.clone(),
                path_prefix,
                git_scopes: Vec::new(),
            }
        })
        .collect();
    cherry_test_common::git_diff::changed_files_by_project(workspace_root, &changed_projects)
}

fn project_root(workspace_root: &Path, config: &TestItConfig, group: &ProjectGroup) -> PathBuf {
    config
        .projects
        .get(&group.id)
        .and_then(|project| project.path.as_ref())
        .map(|path| workspace_root.join(path))
        .unwrap_or_else(|| workspace_root.join(&group.id))
}

pub fn collect_changed_files_for_base(
    workspace_root: &Path,
    diff_base: &str,
) -> Result<Vec<String>> {
    collect_changed_files(workspace_root, diff_base)
}

pub fn git_unified_diff(
    workspace_root: &Path,
    path: &str,
    diff_base: &str,
    context: u32,
) -> Result<String> {
    let Some(git_root) = git_toplevel(workspace_root) else {
        return Ok(String::new());
    };
    let git_path = workspace_path_in_git_repo(workspace_root, &git_root, path);
    let u = format!("-U{context}");
    let full = workspace_root.join(path);
    if !full.exists() {
        return Ok(String::new());
    }
    if is_untracked(&git_root, &git_path)? {
        let null = if cfg!(windows) { "NUL" } else { "/dev/null" };
        let out = git_output(
            &git_root,
            &[
                "diff",
                &u,
                "--no-color",
                "--no-index",
                "--",
                null,
                &git_path,
            ],
        )?;
        if !out.trim().is_empty() {
            return Ok(out);
        }
    }
    let out = git_output(
        &git_root,
        &["diff", &u, "--no-color", "--cached", "--", &git_path],
    )?;
    if !out.trim().is_empty() {
        return Ok(out);
    }
    let out = git_output(&git_root, &["diff", &u, "--no-color", "--", &git_path])?;
    if !out.trim().is_empty() {
        return Ok(out);
    }
    if head_exists(&git_root) {
        if is_empty_tree_base(diff_base) {
            let null = if cfg!(windows) { "NUL" } else { "/dev/null" };
            let out = git_output(
                &git_root,
                &[
                    "diff",
                    &u,
                    "--no-color",
                    "--no-index",
                    "--",
                    null,
                    &git_path,
                ],
            )?;
            if !out.trim().is_empty() {
                return Ok(out);
            }
        } else if let Some(base) = resolve_diff_base(&git_root, diff_base) {
            let range = format!("{base}...HEAD");
            let out = git_output(
                &git_root,
                &["diff", &u, "--no-color", &range, "--", &git_path],
            )?;
            if !out.trim().is_empty() {
                return Ok(out);
            }
        }
        let out = git_output(
            &git_root,
            &["diff", &u, "--no-color", "HEAD", "--", &git_path],
        )?;
        if !out.trim().is_empty() {
            return Ok(out);
        }
    }
    Ok(String::new())
}

fn is_untracked(workspace_root: &Path, path: &str) -> Result<bool> {
    let output = Command::new("git")
        .current_dir(workspace_root)
        .args(["ls-files", "--error-unmatch", path])
        .output()
        .context("git ls-files --error-unmatch")?;
    Ok(!output.status.success())
}

fn head_exists(workspace_root: &Path) -> bool {
    git_rev_parse(workspace_root, "HEAD").is_some()
}

fn collect_changed_files(workspace_root: &Path, diff_base: &str) -> Result<Vec<String>> {
    let Some(git_root) = git_toplevel(workspace_root) else {
        return Ok(Vec::new());
    };
    let mut files = Vec::new();
    if is_empty_tree_base(diff_base) {
        if head_exists(&git_root) {
            files.extend(git_name_only(
                &git_root,
                &["ls-tree", "-r", "--name-only", "HEAD"],
            )?);
        }
    } else if let Some(base) = resolve_diff_base(&git_root, diff_base) {
        if head_exists(&git_root) {
            let range = format!("{base}...HEAD");
            files.extend(git_name_only(&git_root, &["diff", "--name-only", &range])?);
        }
    }
    files.extend(git_name_only(&git_root, &["diff", "--name-only"])?);
    files.extend(git_name_only(
        &git_root,
        &["diff", "--cached", "--name-only"],
    )?);
    files.extend(git_name_only(
        &git_root,
        &["ls-files", "--others", "--exclude-standard"],
    )?);
    if head_exists(&git_root) {
        files.extend(git_name_only(&git_root, &["diff", "--name-only", "HEAD"])?);
    }
    files.sort();
    files.dedup();
    Ok(files
        .into_iter()
        .filter_map(|p| path_relative_to_workspace(workspace_root, &git_root, &p))
        .map(|p| normalize_changed_path(workspace_root, &p))
        .collect())
}

fn git_toplevel(workspace_root: &Path) -> Option<PathBuf> {
    let output = Command::new("git")
        .current_dir(workspace_root)
        .args(["rev-parse", "--show-toplevel"])
        .output()
        .ok()?;
    if !output.status.success() {
        return None;
    }
    let path = String::from_utf8(output.stdout).ok()?.trim().to_string();
    if path.is_empty() {
        return None;
    }
    Some(PathBuf::from(path))
}

fn path_relative_to_workspace(
    workspace_root: &Path,
    git_root: &Path,
    git_path: &str,
) -> Option<String> {
    let git_path = git_path.strip_prefix("./").unwrap_or(git_path);
    let workspace_root = workspace_root
        .canonicalize()
        .unwrap_or_else(|_| workspace_root.to_path_buf());
    let git_root = git_root
        .canonicalize()
        .unwrap_or_else(|_| git_root.to_path_buf());
    let full = git_root.join(git_path);
    let full = full.canonicalize().unwrap_or(full);
    full.strip_prefix(&workspace_root)
        .ok()
        .map(|rel| rel.to_string_lossy().replace('\\', "/"))
}

fn workspace_path_in_git_repo(
    workspace_root: &Path,
    git_root: &Path,
    workspace_relative_path: &str,
) -> String {
    let workspace_root = workspace_root
        .canonicalize()
        .unwrap_or_else(|_| workspace_root.to_path_buf());
    let git_root = git_root
        .canonicalize()
        .unwrap_or_else(|_| git_root.to_path_buf());
    let full = workspace_root.join(workspace_relative_path);
    full.strip_prefix(&git_root)
        .map(|rel| rel.to_string_lossy().replace('\\', "/"))
        .unwrap_or_else(|_| workspace_relative_path.replace('\\', "/"))
}

pub fn package_prefixes(
    workspace_root: &Path,
    config: &TestItConfig,
    project: &ProjectGroup,
) -> Vec<String> {
    let mut prefixes = Vec::new();
    if let Some(project_cfg) = config.projects.get(&project.id) {
        let base = project_cfg.path.as_deref().unwrap_or(project.id.as_str());
        for package_name in project_cfg.packages.keys() {
            if base == "." || base.is_empty() {
                prefixes.push(format!("{package_name}/"));
            } else {
                prefixes.push(format!("{base}/{package_name}/"));
            }
        }
    }
    for member in &project.members {
        if let Some(rel) = member.manifest_path.parent() {
            let rel = rel.to_string_lossy().replace('\\', "/");
            prefixes.push(format!("{rel}/"));
        }
        prefixes.push(format!("{}/", member.name));
    }
    if prefixes.is_empty() {
        prefixes.push(format!("{}/", project.id));
    }
    let _ = workspace_root;
    prefixes.sort();
    prefixes.dedup();
    prefixes
}

pub fn matches_package_prefix(path: &str, prefixes: &[String]) -> bool {
    prefixes
        .iter()
        .any(|prefix| path_matches_prefix(path, prefix))
}

pub fn is_instrumentable_source_path(path: &str) -> bool {
    let name = path.rsplit('/').next().unwrap_or(path);
    if name == "Cargo.toml" || name == "package.json" {
        return false;
    }
    matches!(
        path.rsplit('.').next().unwrap_or(""),
        "rs" | "ts"
            | "tsx"
            | "js"
            | "jsx"
            | "vue"
            | "kt"
            | "java"
            | "c"
            | "h"
            | "cpp"
            | "cc"
            | "cxx"
            | "hpp"
            | "hxx"
    )
}

pub fn expand_changed_with_lcov_sources(
    git_changed: Vec<String>,
    lcov_files: &[crate::coverage::FileCoverage],
    prefixes: &[String],
) -> Vec<String> {
    use std::collections::BTreeSet;
    let mut paths: BTreeSet<String> = git_changed.into_iter().collect();
    for file in lcov_files {
        if file.lines_found == 0 {
            continue;
        }
        if !is_instrumentable_source_path(&file.path) {
            continue;
        }
        let normalized = crate::coverage::normalize_path(&file.path);
        if !matches_package_prefix(&normalized, prefixes) {
            continue;
        }
        paths.insert(normalized);
    }
    paths.into_iter().collect()
}

fn path_matches_prefix(path: &str, prefix: &str) -> bool {
    path.starts_with(prefix)
        || path.starts_with(&format!("./{prefix}"))
        || format!("{path}/").starts_with(prefix)
}

pub fn module_path_for_file(path: &str, prefixes: &[String]) -> String {
    let normalized = path.strip_prefix("./").unwrap_or(path);
    let mut best: Option<&str> = None;
    for prefix in prefixes {
        if path_matches_prefix(normalized, prefix) {
            let module = prefix.trim_end_matches('/');
            if best
                .map(|current| module.len() > current.len())
                .unwrap_or(true)
            {
                best = Some(module);
            }
        }
    }
    best.map(str::to_string)
        .unwrap_or_else(|| normalized.split('/').take(2).collect::<Vec<_>>().join("/"))
}

const EMPTY_TREE: &str = "4b825dc642cb6eb9a060e54bf44d4eab43ad3e839";

fn is_empty_tree_base(diff_base: &str) -> bool {
    matches!(
        diff_base.trim().to_ascii_lowercase().as_str(),
        "empty" | "empty-tree" | "root"
    )
}

fn resolve_diff_base(workspace_root: &Path, diff_base: &str) -> Option<String> {
    if is_empty_tree_base(diff_base) {
        return Some(EMPTY_TREE.into());
    }
    let candidates = if diff_base.is_empty() {
        vec!["origin/main".into(), "main".into()]
    } else {
        vec![format!("origin/{diff_base}"), diff_base.to_string()]
    };
    for candidate in candidates {
        if git_rev_parse(workspace_root, &candidate).is_some() {
            return Some(candidate);
        }
    }
    None
}

fn git_rev_parse(workspace_root: &Path, rev: &str) -> Option<()> {
    let output = Command::new("git")
        .current_dir(workspace_root)
        .args(["rev-parse", "--verify", rev])
        .output()
        .ok()?;
    if output.status.success() {
        Some(())
    } else {
        None
    }
}

fn git_name_only(workspace_root: &Path, args: &[&str]) -> Result<Vec<String>> {
    let output = Command::new("git")
        .current_dir(workspace_root)
        .args(args)
        .output()
        .with_context(|| format!("git {}", args.join(" ")))?;
    if !output.status.success() {
        return Ok(Vec::new());
    }
    Ok(String::from_utf8(output.stdout)?
        .lines()
        .map(str::trim)
        .filter(|line| !line.is_empty())
        .map(str::to_string)
        .collect())
}

fn git_output(workspace_root: &Path, args: &[&str]) -> Result<String> {
    let output = Command::new("git")
        .current_dir(workspace_root)
        .args(args)
        .output()
        .with_context(|| format!("git {}", args.join(" ")))?;
    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        if stderr.contains("no such path") || stderr.contains("does not exist") {
            return Ok(String::new());
        }
        if stderr.contains("bad revision") {
            return Ok(String::new());
        }
    }
    Ok(String::from_utf8(output.stdout)?)
}

pub fn map_files_to_projects(
    workspace_root: &Path,
    projects: &[ProjectGroup],
    changed: &[String],
) -> BTreeMap<String, Vec<String>> {
    let mut out: BTreeMap<String, Vec<String>> = BTreeMap::new();
    for project in projects {
        out.entry(project.id.clone()).or_default();
        let prefix = format!("{}/", project.id);
        for path in changed {
            if path.starts_with(&prefix) || path.starts_with(&format!("./{prefix}")) {
                out.entry(project.id.clone())
                    .or_default()
                    .push(normalize_changed_path(workspace_root, path));
            }
        }
        if let Some(list) = out.get_mut(&project.id) {
            list.sort();
            list.dedup();
        }
    }
    out
}

fn normalize_changed_path(workspace_root: &Path, path: &str) -> String {
    let path = path.strip_prefix("./").unwrap_or(path);
    let full = workspace_root.join(path);
    if let Ok(rel) = full.strip_prefix(workspace_root) {
        rel.to_string_lossy().replace('\\', "/")
    } else {
        path.replace('\\', "/")
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use std::path::PathBuf;

    use crate::discover::{ProjectGroup, WorkspaceMember};
    use cherry_test_core::PackageRuntime;

    #[test]
    fn maps_changed_files_to_project_prefix() {
        let projects = vec![ProjectGroup {
            id: "mini".into(),
            members: vec![
                WorkspaceMember {
                    name: "mini-rs-api".into(),
                    manifest_path: PathBuf::from("mini/mini-rs-api/Cargo.toml"),
                    project_id: "mini".into(),
                    runtime: PackageRuntime::Rust,
                },
                WorkspaceMember {
                    name: "mini-rs-lib-mcap".into(),
                    manifest_path: PathBuf::from("mini/mini-rs-lib-mcap/Cargo.toml"),
                    project_id: "mini".into(),
                    runtime: PackageRuntime::Rust,
                },
            ],
        }];
        let changed = vec![
            "mini/mini-rs-api/src/api.rs".into(),
            "mini/mini-rs-lib-mcap/src/lib.rs".into(),
            "test/cherry-test/src/lib.rs".into(),
        ];
        let mapped = map_files_to_projects(Path::new("/ws"), &projects, &changed);
        assert_eq!(
            mapped.get("mini").unwrap(),
            &vec![
                "mini/mini-rs-api/src/api.rs".to_string(),
                "mini/mini-rs-lib-mcap/src/lib.rs".to_string(),
            ]
        );
    }

    #[test]
    fn collects_untracked_files_without_commits() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path();
        fs::create_dir_all(root.join("mini/mini-rs-api/src")).unwrap();
        fs::write(root.join("mini/mini-rs-api/src/lib.rs"), "fn main() {}\n").unwrap();
        Command::new("git")
            .current_dir(root)
            .args(["init"])
            .output()
            .unwrap();
        let files = collect_changed_files(root, "main").unwrap();
        assert!(files
            .iter()
            .any(|p| p.ends_with("mini/mini-rs-api/src/lib.rs")));
    }

    #[test]
    fn git_unified_diff_shows_untracked_file_without_commits() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path();
        fs::create_dir_all(root.join("mini/mini-rs-api/src")).unwrap();
        fs::write(root.join("mini/mini-rs-api/src/lib.rs"), "fn main() {}\n").unwrap();
        Command::new("git")
            .current_dir(root)
            .args(["init"])
            .output()
            .unwrap();
        let diff = git_unified_diff(root, "mini/mini-rs-api/src/lib.rs", "main", 5).unwrap();
        assert!(diff.contains("@@"));
        assert!(diff.contains("+fn main()"));
    }

    #[test]
    fn module_path_for_file_uses_longest_prefix() {
        let prefixes = vec![
            "mini/".into(),
            "mini/mini-rs-api/".into(),
            "mini/mini-ts-web-react/".into(),
        ];
        assert_eq!(
            module_path_for_file("mini/mini-rs-api/src/lib.rs", &prefixes),
            "mini/mini-rs-api"
        );
        assert_eq!(
            module_path_for_file("mini/mini-ts-web-react/src/App.tsx", &prefixes),
            "mini/mini-ts-web-react"
        );
    }

    #[test]
    fn module_path_for_file_falls_back_to_two_segments() {
        assert_eq!(
            module_path_for_file("other/pkg/src/main.rs", &[]),
            "other/pkg"
        );
    }

    #[test]
    fn package_prefix_filter_keeps_package_tree_only() {
        assert!(matches_package_prefix(
            "mini/mini-rs-api/src/lib.rs",
            &["mini/mini-rs-api/".into()]
        ));
        assert!(!matches_package_prefix(
            "mini/docker-compose.test.yml",
            &["mini/mini-rs-api/".into()]
        ));
    }

    #[test]
    fn expand_changed_adds_lcov_sources_for_exercised_project() {
        use crate::coverage::FileCoverage;
        let git_changed = vec!["mini/mini-rs-lib-mcap/Dockerfile".into()];
        let lcov_files = vec![FileCoverage {
            path: "mini/mini-rs-lib-mcap/src/lib.rs".into(),
            lines_hit: 8,
            lines_found: 10,
            ..Default::default()
        }];
        let prefixes = vec!["mini/mini-rs-lib-mcap/".into()];
        let expanded = expand_changed_with_lcov_sources(git_changed, &lcov_files, &prefixes);
        assert!(expanded.contains(&"mini/mini-rs-lib-mcap/Dockerfile".to_string()));
        assert!(expanded.contains(&"mini/mini-rs-lib-mcap/src/lib.rs".to_string()));
    }

    #[test]
    fn resolve_diff_base_empty_tree_aliases() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path();
        for base in ["empty", "empty-tree", "root", "EMPTY"] {
            assert!(is_empty_tree_base(base), "base={base}");
            assert_eq!(
                resolve_diff_base(root, base).as_deref(),
                Some(EMPTY_TREE),
                "base={base}"
            );
        }
    }

    #[test]
    fn empty_diff_base_lists_tracked_files_since_empty_tree() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path();
        fs::create_dir_all(root.join("pkg/src")).unwrap();
        fs::write(root.join("pkg/src/lib.rs"), "fn main() {}\n").unwrap();
        Command::new("git")
            .current_dir(root)
            .args(["init"])
            .output()
            .unwrap();
        Command::new("git")
            .current_dir(root)
            .args(["add", "."])
            .output()
            .unwrap();
        Command::new("git")
            .current_dir(root)
            .args([
                "-c",
                "user.email=test@test",
                "-c",
                "user.name=test",
                "commit",
                "-m",
                "init",
            ])
            .output()
            .unwrap();
        let files = collect_changed_files(root, "empty").unwrap();
        assert!(files.iter().any(|p| p.ends_with("pkg/src/lib.rs")));
    }
}
