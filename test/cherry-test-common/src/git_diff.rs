use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::process::Command;

use anyhow::{Context, Result};

use crate::config::ReviewChangedScope;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct NestedGitScope {
    pub module_id: String,
    pub git_root: PathBuf,
    pub path_prefix: String,
}

#[derive(Debug, Clone)]
pub struct ChangedFilesProject {
    pub id: String,
    pub diff_base: String,
    pub changed_scope: ReviewChangedScope,
    pub package_prefixes: Vec<String>,
    pub git_root: PathBuf,
    pub workspace_root: PathBuf,
    pub path_prefix: String,
    pub git_scopes: Vec<NestedGitScope>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum CollectChangedMode {
    BranchOnly,
    WorktreeOnly,
    BranchAndWorktree,
}

pub fn changed_files_by_project(
    _workspace_root: &Path,
    projects: &[ChangedFilesProject],
) -> Result<BTreeMap<String, Vec<String>>> {
    let nested_prefixes = nested_scope_prefixes(projects);
    let mut mapped = BTreeMap::new();
    for project in projects {
        let mut files = Vec::new();
        if project.git_scopes.is_empty() {
            let mode =
                collect_changed_mode_for_scope(project, &project.git_root, &project.path_prefix);
            let changed = collect_changed_files_inner(&project.git_root, &project.diff_base, mode)?;
            files.extend(
                changed
                    .iter()
                    .map(|path| prefix_workspace_path(&project.path_prefix, path))
                    .filter(|path| matches_package_prefix(path, &project.package_prefixes))
                    .filter(|path| {
                        !path_claimed_by_nested_project(path, &nested_prefixes, project)
                    }),
            );
        } else {
            for scope in &project.git_scopes {
                let mode =
                    collect_changed_mode_for_scope(project, &scope.git_root, &scope.path_prefix);
                let changed =
                    collect_changed_files_inner(&scope.git_root, &project.diff_base, mode)?;
                files.extend(
                    changed
                        .iter()
                        .map(|path| prefix_workspace_path(&scope.path_prefix, path))
                        .filter(|path| matches_package_prefix(path, &project.package_prefixes))
                        .filter(|path| {
                            !path_claimed_by_nested_project(path, &nested_prefixes, project)
                        }),
                );
            }
        }
        files.sort();
        files.dedup();
        if !files.is_empty() {
            mapped.insert(project.id.clone(), files);
        }
    }
    Ok(mapped)
}

fn nested_scope_prefixes(projects: &[ChangedFilesProject]) -> Vec<String> {
    let mut nested_prefixes = Vec::new();
    for project in projects {
        if !project.git_scopes.is_empty() {
            for scope in &project.git_scopes {
                if !scope.path_prefix.is_empty() {
                    nested_prefixes.push(scope.path_prefix.clone());
                }
            }
            continue;
        }
        if separate_repo_scope(project) && !project.path_prefix.is_empty() {
            nested_prefixes.push(project.path_prefix.clone());
        }
    }
    nested_prefixes.sort();
    nested_prefixes.dedup();
    nested_prefixes
}

fn collect_changed_mode_for_scope(
    project: &ChangedFilesProject,
    git_root: &Path,
    path_prefix: &str,
) -> CollectChangedMode {
    let workspace_root = project
        .workspace_root
        .canonicalize()
        .unwrap_or_else(|_| project.workspace_root.clone());
    let git_root = git_root
        .canonicalize()
        .unwrap_or_else(|_| git_root.to_path_buf());
    let separate = workspace_root != git_root || !path_prefix.is_empty();
    match project.changed_scope {
        ReviewChangedScope::Branch => CollectChangedMode::BranchOnly,
        ReviewChangedScope::Worktree => CollectChangedMode::WorktreeOnly,
        ReviewChangedScope::BranchAndWorktree => {
            if separate {
                CollectChangedMode::BranchOnly
            } else {
                CollectChangedMode::BranchAndWorktree
            }
        }
    }
}

pub fn discover_nested_git_scopes(
    config_root: &Path,
    project_root: &Path,
    module_ids: &[String],
    force_submodule: Option<bool>,
) -> Vec<NestedGitScope> {
    if force_submodule == Some(false) {
        return Vec::new();
    }
    let mut scopes = Vec::new();
    for module_id in module_ids {
        let module_root = project_root.join(module_id);
        if !module_root.is_dir() {
            continue;
        }
        let separate = match force_submodule {
            Some(true) => true,
            _ => is_separate_git_repository(config_root, &module_root),
        };
        if !separate {
            continue;
        }
        scopes.push(NestedGitScope {
            module_id: module_id.clone(),
            git_root: module_root.clone(),
            path_prefix: workspace_path_prefix(config_root, &module_root),
        });
    }
    if !scopes.is_empty() {
        return scopes;
    }
    if force_submodule == Some(true) || is_separate_git_repository(config_root, project_root) {
        let path_prefix = workspace_path_prefix(config_root, project_root);
        return vec![NestedGitScope {
            module_id: String::new(),
            git_root: project_root.to_path_buf(),
            path_prefix,
        }];
    }
    Vec::new()
}

pub fn resolve_git_scope_for_path<'a>(
    project: &'a ChangedFilesProject,
    workspace_path: &str,
) -> (&'a Path, &'a str) {
    let mut best: Option<(&Path, &str, usize)> = None;
    for scope in &project.git_scopes {
        if scope.path_prefix.is_empty() {
            continue;
        }
        if workspace_path.starts_with(&scope.path_prefix)
            && best
                .map(|(_, _, len)| scope.path_prefix.len() > len)
                .unwrap_or(true)
        {
            best = Some((
                &scope.git_root,
                scope.path_prefix.as_str(),
                scope.path_prefix.len(),
            ));
        }
    }
    if let Some((git_root, path_prefix, _)) = best {
        return (git_root, path_prefix);
    }
    (&project.git_root, project.path_prefix.as_str())
}

fn path_claimed_by_nested_project(
    path: &str,
    nested_prefixes: &[String],
    project: &ChangedFilesProject,
) -> bool {
    if separate_repo_scope(project) {
        return false;
    }
    nested_prefixes
        .iter()
        .any(|prefix| path.starts_with(prefix))
}

pub fn resolve_project_git_scope(
    config_root: &Path,
    project_root: &Path,
    force_submodule: Option<bool>,
) -> (PathBuf, String) {
    let separate = match force_submodule {
        Some(false) => false,
        Some(true) => true,
        None => is_separate_git_repository(config_root, project_root),
    };
    let path_prefix = if separate {
        workspace_path_prefix(config_root, project_root)
    } else {
        String::new()
    };
    let git_root = if separate {
        project_root.to_path_buf()
    } else {
        config_root.to_path_buf()
    };
    (git_root, path_prefix)
}

pub fn is_separate_git_repository(config_root: &Path, project_root: &Path) -> bool {
    let Some(config_top) = git_toplevel(config_root) else {
        return false;
    };
    let Some(project_top) = git_toplevel(project_root) else {
        return false;
    };
    config_top != project_top
}

pub fn workspace_path_prefix(config_root: &Path, project_root: &Path) -> String {
    let config_root = config_root
        .canonicalize()
        .unwrap_or_else(|_| config_root.to_path_buf());
    let project_root = project_root
        .canonicalize()
        .unwrap_or_else(|_| project_root.to_path_buf());
    project_root
        .strip_prefix(&config_root)
        .ok()
        .map(|rel| {
            let rel = rel.to_string_lossy().replace('\\', "/");
            if rel.is_empty() {
                String::new()
            } else {
                format!("{rel}/")
            }
        })
        .unwrap_or_default()
}

pub fn prefix_workspace_path(prefix: &str, rel_path: &str) -> String {
    let rel_path = rel_path.strip_prefix("./").unwrap_or(rel_path);
    if prefix.is_empty() {
        return rel_path.replace('\\', "/");
    }
    let prefix = prefix.strip_suffix('/').unwrap_or(prefix);
    format!("{prefix}/{rel_path}").replace('\\', "/")
}

pub fn strip_workspace_path_prefix(prefix: &str, workspace_path: &str) -> String {
    let workspace_path = workspace_path.strip_prefix("./").unwrap_or(workspace_path);
    if prefix.is_empty() {
        return workspace_path.replace('\\', "/");
    }
    let prefix = prefix.strip_suffix('/').unwrap_or(prefix);
    if workspace_path == prefix {
        return String::new();
    }
    let with_slash = format!("{prefix}/");
    if let Some(rest) = workspace_path.strip_prefix(&with_slash) {
        return rest.replace('\\', "/");
    }
    workspace_path.replace('\\', "/")
}

pub fn collect_changed_files_for_base(
    workspace_root: &Path,
    diff_base: &str,
) -> Result<Vec<String>> {
    collect_changed_files_inner(
        workspace_root,
        diff_base,
        CollectChangedMode::BranchAndWorktree,
    )
}

fn separate_repo_scope(project: &ChangedFilesProject) -> bool {
    if !project.git_scopes.is_empty() {
        return true;
    }
    let workspace_root = project
        .workspace_root
        .canonicalize()
        .unwrap_or_else(|_| project.workspace_root.clone());
    let git_root = project
        .git_root
        .canonicalize()
        .unwrap_or_else(|_| project.git_root.clone());
    workspace_root != git_root
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
    git_unified_diff_scoped(workspace_root, &git_root, "", path, diff_base, context)
}

pub fn git_unified_diff_scoped(
    workspace_root: &Path,
    git_root: &Path,
    path_prefix: &str,
    workspace_relative_path: &str,
    diff_base: &str,
    context: u32,
) -> Result<String> {
    let git_path = if path_prefix.is_empty() {
        workspace_path_in_git_repo(workspace_root, git_root, workspace_relative_path)
    } else {
        strip_workspace_path_prefix(path_prefix, workspace_relative_path)
    };
    let u = format!("-U{context}");
    let full = workspace_root.join(workspace_relative_path);
    if !full.exists() {
        return Ok(String::new());
    }
    if is_untracked(git_root, &git_path)? {
        let null = if cfg!(windows) { "NUL" } else { "/dev/null" };
        let out = git_output(
            git_root,
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
        git_root,
        &["diff", &u, "--no-color", "--cached", "--", &git_path],
    )?;
    if !out.trim().is_empty() {
        return Ok(out);
    }
    let out = git_output(git_root, &["diff", &u, "--no-color", "--", &git_path])?;
    if !out.trim().is_empty() {
        return Ok(out);
    }
    if head_exists(git_root) {
        if is_empty_tree_base(diff_base) {
            let null = if cfg!(windows) { "NUL" } else { "/dev/null" };
            let out = git_output(
                git_root,
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
        } else if let Some(base) = resolve_diff_base(git_root, diff_base) {
            let range = branch_diff_range(&base);
            let out = git_output(
                git_root,
                &["diff", &u, "--no-color", &range, "--", &git_path],
            )?;
            if !out.trim().is_empty() {
                return Ok(out);
            }
        }
        let out = git_output(
            git_root,
            &["diff", &u, "--no-color", "HEAD", "--", &git_path],
        )?;
        if !out.trim().is_empty() {
            return Ok(out);
        }
    }
    Ok(String::new())
}

pub fn git_unified_diff_shell_command(
    workspace_root: &Path,
    git_root: &Path,
    path_prefix: &str,
    workspace_relative_path: &str,
    diff_base: &str,
    context: u32,
) -> Result<Option<String>> {
    let git_path = if path_prefix.is_empty() {
        workspace_path_in_git_repo(workspace_root, git_root, workspace_relative_path)
    } else {
        strip_workspace_path_prefix(path_prefix, workspace_relative_path)
    };
    let u = format!("-U{context}");
    let root = git_root.display().to_string();
    let full = workspace_root.join(workspace_relative_path);
    if !full.exists() {
        return Ok(None);
    }
    if is_untracked(git_root, &git_path)? {
        let null = if cfg!(windows) { "NUL" } else { "/dev/null" };
        let out = git_output(
            git_root,
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
            return Ok(Some(format!(
                "git -C {root} diff {u} --no-color --no-index -- /dev/null {git_path}"
            )));
        }
    }
    let out = git_output(
        git_root,
        &["diff", &u, "--no-color", "--cached", "--", &git_path],
    )?;
    if !out.trim().is_empty() {
        return Ok(Some(format!(
            "git -C {root} diff {u} --no-color --cached -- {git_path}"
        )));
    }
    let out = git_output(git_root, &["diff", &u, "--no-color", "--", &git_path])?;
    if !out.trim().is_empty() {
        return Ok(Some(format!(
            "git -C {root} diff {u} --no-color -- {git_path}"
        )));
    }
    if head_exists(git_root) {
        if is_empty_tree_base(diff_base) {
            let null = if cfg!(windows) { "NUL" } else { "/dev/null" };
            let out = git_output(
                git_root,
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
                return Ok(Some(format!(
                    "git -C {root} diff {u} --no-color --no-index -- /dev/null {git_path}"
                )));
            }
        } else if let Some(base) = resolve_diff_base(git_root, diff_base) {
            let range = branch_diff_range(&base);
            let out = git_output(
                git_root,
                &["diff", &u, "--no-color", &range, "--", &git_path],
            )?;
            if !out.trim().is_empty() {
                return Ok(Some(format!(
                    "git -C {root} diff {u} --no-color {range} -- {git_path}"
                )));
            }
        }
        let out = git_output(
            git_root,
            &["diff", &u, "--no-color", "HEAD", "--", &git_path],
        )?;
        if !out.trim().is_empty() {
            return Ok(Some(format!(
                "git -C {root} diff {u} --no-color HEAD -- {git_path}"
            )));
        }
    }
    Ok(None)
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

fn collect_changed_files_inner(
    git_cwd: &Path,
    diff_base: &str,
    mode: CollectChangedMode,
) -> Result<Vec<String>> {
    let Some(git_root) = git_toplevel(git_cwd) else {
        return Ok(Vec::new());
    };
    if mode == CollectChangedMode::BranchOnly {
        let files = collect_range_diff_files(&git_root, diff_base, true)?;
        return finish_changed_file_paths(git_cwd, &git_root, files);
    }
    if mode == CollectChangedMode::WorktreeOnly {
        let files = collect_worktree_files(&git_root)?;
        return finish_changed_file_paths(git_cwd, &git_root, files);
    }
    let mut files = Vec::new();
    if is_empty_tree_base(diff_base) {
        if head_exists(&git_root) {
            files.extend(git_name_only(
                &git_root,
                &["ls-tree", "-r", "--name-only", "HEAD"],
            )?);
        }
    } else if let Some(base) = resolve_diff_base_for_scope(&git_root, diff_base, false) {
        if head_exists(&git_root) {
            let range = branch_diff_range(&base);
            files.extend(git_name_only(&git_root, &["diff", "--name-only", &range])?);
        }
    }
    files.extend(collect_worktree_files(&git_root)?);
    finish_changed_file_paths(git_cwd, &git_root, files)
}

fn collect_worktree_files(git_root: &Path) -> Result<Vec<String>> {
    let mut files = Vec::new();
    files.extend(git_name_only(git_root, &["diff", "--name-only"])?);
    files.extend(git_name_only(
        git_root,
        &["diff", "--cached", "--name-only"],
    )?);
    files.extend(git_name_only(
        git_root,
        &["ls-files", "--others", "--exclude-standard"],
    )?);
    if head_exists(git_root) {
        files.extend(git_name_only(git_root, &["diff", "--name-only", "HEAD"])?);
    }
    Ok(files)
}

fn finish_changed_file_paths(
    workspace_root: &Path,
    git_root: &Path,
    files: Vec<String>,
) -> Result<Vec<String>> {
    let mut files = files;
    files.sort();
    files.dedup();
    Ok(files
        .into_iter()
        .filter_map(|p| path_relative_to_workspace(workspace_root, git_root, &p))
        .map(|p| normalize_changed_path(workspace_root, &p))
        .collect())
}

pub fn git_toplevel(workspace_root: &Path) -> Option<PathBuf> {
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

fn path_matches_prefix(path: &str, prefix: &str) -> bool {
    path.starts_with(prefix)
        || path.starts_with(&format!("./{prefix}"))
        || format!("{path}/").starts_with(prefix)
}

pub fn module_path_for_file(path: &str, prefixes: &[String]) -> String {
    let normalized = path.strip_prefix("./").unwrap_or(path);
    let mut best_prefix: Option<String> = None;
    for prefix in prefixes {
        if path_matches_prefix(normalized, prefix) {
            let stem = prefix.trim_end_matches('/').to_string();
            if best_prefix
                .as_ref()
                .map(|current| stem.len() > current.len())
                .unwrap_or(true)
            {
                best_prefix = Some(stem);
            }
        }
    }
    if let Some(prefix_stem) = best_prefix {
        return module_under_prefix(normalized, &prefix_stem);
    }
    normalized.split('/').take(2).collect::<Vec<_>>().join("/")
}

fn module_under_prefix(normalized: &str, prefix_stem: &str) -> String {
    if normalized == prefix_stem {
        return prefix_stem.to_string();
    }
    let with_slash = format!("{prefix_stem}/");
    if !normalized.starts_with(&with_slash) {
        return prefix_stem.to_string();
    }
    let rest = &normalized[with_slash.len()..];
    if rest.is_empty() || !rest.contains('/') {
        return prefix_stem.to_string();
    }
    if prefix_stem.contains('/') {
        return prefix_stem.to_string();
    }
    let segment = rest.split('/').next().unwrap_or(rest);
    format!("{prefix_stem}/{segment}")
}

pub fn is_empty_tree_base(diff_base: &str) -> bool {
    matches!(
        diff_base.trim().to_ascii_lowercase().as_str(),
        "empty" | "empty-tree" | "root"
    )
}

pub fn resolve_diff_base(workspace_root: &Path, diff_base: &str) -> Option<String> {
    resolve_diff_base_for_scope(workspace_root, diff_base, false)
}

pub fn diff_base_ref_candidates(diff_base: &str, range_only: bool) -> Vec<String> {
    let mut candidates = if diff_base.is_empty() {
        vec!["main".into(), "origin/main".into()]
    } else {
        vec![diff_base.to_string(), format!("origin/{diff_base}")]
    };
    if range_only {
        candidates.reverse();
        for extra in ["origin/master", "master", "origin/develop", "develop"] {
            if !candidates.iter().any(|c| c == extra) {
                candidates.push(extra.into());
            }
        }
    }
    candidates
}

pub fn resolve_diff_base_for_scope(
    git_root: &Path,
    diff_base: &str,
    range_only: bool,
) -> Option<String> {
    if is_empty_tree_base(diff_base) {
        if range_only {
            return None;
        }
        return Some(EMPTY_TREE.into());
    }
    for candidate in diff_base_ref_candidates(diff_base, range_only) {
        if git_rev_parse(git_root, &candidate).is_some() {
            return Some(candidate);
        }
    }
    None
}

fn branch_diff_range(base: &str) -> String {
    format!("{base}..HEAD")
}

pub fn branch_range_changed_files(git_root: &Path, base: &str) -> Result<(String, Vec<String>)> {
    let range = format!("{base}...HEAD");
    let cmd = format!("git -C {} diff --name-only {}", git_root.display(), range);
    if !head_exists(git_root) {
        return Ok((cmd, Vec::new()));
    }
    let files = git_name_only(git_root, &["diff", "--name-only", &range])?;
    Ok((cmd, files))
}

fn collect_range_diff_files(
    git_root: &Path,
    diff_base: &str,
    range_only: bool,
) -> Result<Vec<String>> {
    let Some(base) = resolve_diff_base_for_scope(git_root, diff_base, range_only) else {
        return Ok(Vec::new());
    };
    if base == EMPTY_TREE {
        if range_only {
            return Ok(Vec::new());
        }
        if head_exists(git_root) {
            return git_name_only(git_root, &["ls-tree", "-r", "--name-only", "HEAD"]);
        }
        return Ok(Vec::new());
    }
    Ok(branch_range_changed_files(git_root, &base)?.1)
}

pub const GIT_EMPTY_TREE: &str = "4b825dc642cb6eb9a060e54bf44d4eab43ad3e839";
const EMPTY_TREE: &str = GIT_EMPTY_TREE;

pub fn collect_changed_files_for_scope(
    git_cwd: &Path,
    diff_base: &str,
    changed_scope: ReviewChangedScope,
    separate_repo: bool,
) -> Result<Vec<String>> {
    let mode = review_collect_mode(changed_scope, separate_repo);
    collect_changed_files_inner(git_cwd, diff_base, mode)
}

fn review_collect_mode(
    changed_scope: ReviewChangedScope,
    separate_repo: bool,
) -> CollectChangedMode {
    match changed_scope {
        ReviewChangedScope::Branch => CollectChangedMode::BranchOnly,
        ReviewChangedScope::Worktree => CollectChangedMode::WorktreeOnly,
        ReviewChangedScope::BranchAndWorktree => {
            if separate_repo {
                CollectChangedMode::BranchOnly
            } else {
                CollectChangedMode::BranchAndWorktree
            }
        }
    }
}

pub fn collect_changed_files_shell_commands(
    git_cwd: &Path,
    diff_base: &str,
    changed_scope: ReviewChangedScope,
    separate_repo: bool,
) -> Vec<String> {
    let Some(git_root) = git_toplevel(git_cwd) else {
        return vec![format!(
            "# not a git repository (cwd {})",
            git_cwd.display()
        )];
    };
    let root = git_root.display().to_string();
    let mode = review_collect_mode(changed_scope, separate_repo);
    match mode {
        CollectChangedMode::BranchOnly => branch_only_shell_commands(&git_root, diff_base, true),
        CollectChangedMode::WorktreeOnly => worktree_shell_commands(&root),
        CollectChangedMode::BranchAndWorktree => {
            let mut cmds = branch_only_shell_commands(&git_root, diff_base, false);
            cmds.extend(worktree_shell_commands(&root));
            cmds
        }
    }
}

fn branch_only_shell_commands(git_root: &Path, diff_base: &str, range_only: bool) -> Vec<String> {
    let root = git_root.display().to_string();
    let Some(base) = resolve_diff_base_for_scope(git_root, diff_base, range_only) else {
        return vec![format!(
            "# no git ref resolves for diff_base={diff_base:?} under {root}"
        )];
    };
    if base == EMPTY_TREE {
        if range_only {
            return vec![format!(
                "# empty-tree diff_base ignored for submodule/range-only scope under {root}"
            )];
        }
        return vec![format!("git -C {root} ls-tree -r --name-only HEAD")];
    }
    let range = if range_only {
        format!("{base}...HEAD")
    } else {
        branch_diff_range(&base)
    };
    vec![format!("git -C {root} diff --name-only {range}")]
}

fn worktree_shell_commands(git_root_display: &str) -> Vec<String> {
    vec![
        format!("git -C {git_root_display} diff --name-only"),
        format!("git -C {git_root_display} diff --cached --name-only"),
        format!("git -C {git_root_display} ls-files --others --exclude-standard"),
        format!("# when HEAD exists: git -C {git_root_display} diff --name-only HEAD"),
    ]
}

pub fn git_ref_exists(git_root: &Path, rev: &str) -> bool {
    git_rev_parse(git_root, rev).is_some()
}

pub fn git_head_exists(git_root: &Path) -> bool {
    head_exists(git_root)
}

pub fn git_head_short(git_root: &Path) -> Option<String> {
    let output = Command::new("git")
        .current_dir(git_root)
        .args(["rev-parse", "--short", "HEAD"])
        .output()
        .ok()?;
    if !output.status.success() {
        return None;
    }
    let value = String::from_utf8(output.stdout).ok()?.trim().to_string();
    if value.is_empty() {
        None
    } else {
        Some(value)
    }
}

pub fn git_current_branch(git_root: &Path) -> Option<String> {
    let output = Command::new("git")
        .current_dir(git_root)
        .args(["branch", "--show-current"])
        .output()
        .ok()?;
    if !output.status.success() {
        return None;
    }
    let value = String::from_utf8(output.stdout).ok()?.trim().to_string();
    Some(value)
}

pub fn git_name_only_paths(git_root: &Path, args: &[&str]) -> Result<Vec<String>> {
    git_name_only(git_root, args)
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
    project_ids: &[String],
    changed: &[String],
) -> BTreeMap<String, Vec<String>> {
    let mut out: BTreeMap<String, Vec<String>> = BTreeMap::new();
    for project_id in project_ids {
        out.entry(project_id.clone()).or_default();
        let prefix = format!("{project_id}/");
        for path in changed {
            if path.starts_with(&prefix) || path.starts_with(&format!("./{prefix}")) {
                out.entry(project_id.clone())
                    .or_default()
                    .push(normalize_changed_path(workspace_root, path));
            }
        }
        if let Some(list) = out.get_mut(project_id) {
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
    use crate::config::ReviewChangedScope;
    use std::fs;
    use std::process::Command;

    #[test]
    fn maps_changed_files_to_project_prefix() {
        let project_ids = vec!["mini".into()];
        let changed = vec![
            "mini/mini-rs-api/src/api.rs".into(),
            "mini/mini-rs-lib-mcap/src/lib.rs".into(),
            "test/cherry-test/src/lib.rs".into(),
        ];
        let mapped = map_files_to_projects(Path::new("/ws"), &project_ids, &changed);
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
        let files = collect_changed_files_for_base(root, "main").unwrap();
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
    fn module_path_for_file_extends_coarse_project_prefix() {
        let prefixes = vec!["mini/".into()];
        assert_eq!(
            module_path_for_file("mini/mini-rs-api/src/lib.rs", &prefixes),
            "mini/mini-rs-api"
        );
        assert_eq!(
            module_path_for_file("mini/cherry-test.yaml", &prefixes),
            "mini"
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
    fn prefix_workspace_path_joins_prefix_and_relative_path() {
        assert_eq!(
            prefix_workspace_path("app/backoffice/", "src/lib.rs"),
            "app/backoffice/src/lib.rs"
        );
        assert_eq!(prefix_workspace_path("", "src/lib.rs"), "src/lib.rs");
    }

    #[test]
    fn strip_workspace_path_prefix_removes_prefix() {
        assert_eq!(
            strip_workspace_path_prefix("app/backoffice/", "app/backoffice/src/lib.rs"),
            "src/lib.rs"
        );
        assert_eq!(strip_workspace_path_prefix("", "src/lib.rs"), "src/lib.rs");
    }

    #[test]
    fn range_only_master_fallback_when_main_missing() {
        let dir = tempfile::tempdir().unwrap();
        let sub = dir.path().join("app/service");
        fs::create_dir_all(sub.join("src")).unwrap();
        fs::write(sub.join("src/lib.rs"), "fn v1() {}\n").unwrap();
        Command::new("git")
            .current_dir(&sub)
            .args(["init", "-b", "master"])
            .output()
            .unwrap();
        Command::new("git")
            .current_dir(&sub)
            .args(["add", "."])
            .output()
            .unwrap();
        Command::new("git")
            .current_dir(&sub)
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
        Command::new("git")
            .current_dir(&sub)
            .args(["checkout", "-b", "feature"])
            .output()
            .unwrap();
        fs::write(sub.join("src/lib.rs"), "fn v2() {}\n").unwrap();
        Command::new("git")
            .current_dir(&sub)
            .args(["add", "src/lib.rs"])
            .output()
            .unwrap();
        Command::new("git")
            .current_dir(&sub)
            .args([
                "-c",
                "user.email=test@test",
                "-c",
                "user.name=test",
                "commit",
                "-m",
                "change",
            ])
            .output()
            .unwrap();
        let files = collect_range_diff_files(&sub, "main", true).unwrap();
        assert_eq!(files, vec!["src/lib.rs"]);
        fs::write(sub.join("src/untracked.rs"), "fn extra() {}\n").unwrap();
        let range_only =
            collect_changed_files_inner(&sub, "main", CollectChangedMode::BranchOnly).unwrap();
        assert_eq!(range_only.len(), 1);
        let worktree =
            collect_changed_files_inner(&sub, "main", CollectChangedMode::BranchAndWorktree)
                .unwrap();
        assert!(worktree.iter().any(|p| p.ends_with("untracked.rs")));
    }

    #[test]
    fn range_only_unresolved_diff_base_returns_empty() {
        let dir = tempfile::tempdir().unwrap();
        let sub = dir.path().join("repo");
        fs::create_dir_all(sub.join("src")).unwrap();
        fs::write(sub.join("src/a.rs"), "fn a() {}\n").unwrap();
        Command::new("git")
            .current_dir(&sub)
            .args(["init", "-b", "feature-only"])
            .output()
            .unwrap();
        Command::new("git")
            .current_dir(&sub)
            .args(["add", "."])
            .output()
            .unwrap();
        Command::new("git")
            .current_dir(&sub)
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
        fs::write(sub.join("src/dirty.rs"), "fn dirty() {}\n").unwrap();
        let files = collect_range_diff_files(&sub, "main", true).unwrap();
        assert!(files.is_empty());
    }

    #[test]
    fn range_only_empty_tree_diff_base_returns_empty() {
        let dir = tempfile::tempdir().unwrap();
        let sub = dir.path().join("repo");
        fs::create_dir_all(sub.join("src")).unwrap();
        fs::write(sub.join("src/a.rs"), "fn a() {}\n").unwrap();
        Command::new("git")
            .current_dir(&sub)
            .args(["init", "-b", "main"])
            .output()
            .unwrap();
        Command::new("git")
            .current_dir(&sub)
            .args(["add", "."])
            .output()
            .unwrap();
        Command::new("git")
            .current_dir(&sub)
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
        let files = collect_range_diff_files(&sub, "empty-tree", true).unwrap();
        assert!(files.is_empty());
        let monorepo =
            collect_changed_files_inner(&sub, "empty-tree", CollectChangedMode::BranchAndWorktree)
                .unwrap();
        assert!(monorepo.iter().any(|p| p.ends_with("src/a.rs")));
    }

    #[test]
    fn range_only_dirty_worktree_ignored_when_range_empty() {
        let dir = tempfile::tempdir().unwrap();
        let sub = dir.path().join("app/backoffice");
        fs::create_dir_all(sub.join("src")).unwrap();
        fs::write(sub.join("src/tracked.rs"), "fn tracked() {}\n").unwrap();
        Command::new("git")
            .current_dir(&sub)
            .args(["init", "-b", "main"])
            .output()
            .unwrap();
        Command::new("git")
            .current_dir(&sub)
            .args(["add", "."])
            .output()
            .unwrap();
        Command::new("git")
            .current_dir(&sub)
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
        fs::write(sub.join("src/dirty.rs"), "fn dirty() {}\n").unwrap();
        fs::write(sub.join("src/untracked.rs"), "fn new() {}\n").unwrap();
        let files =
            collect_changed_files_inner(&sub, "main", CollectChangedMode::BranchOnly).unwrap();
        assert!(files.is_empty());
    }

    #[test]
    fn branch_range_empty_when_head_is_ancestor_of_diff_base() {
        let dir = tempfile::tempdir().unwrap();
        let repo = dir.path().join("repo");
        fs::create_dir_all(repo.join("src")).unwrap();
        fs::write(repo.join("src/lib.rs"), "fn sample() {}\n").unwrap();
        Command::new("git")
            .current_dir(&repo)
            .args(["init", "-b", "mini"])
            .output()
            .unwrap();
        Command::new("git")
            .current_dir(&repo)
            .args(["add", "."])
            .output()
            .unwrap();
        Command::new("git")
            .current_dir(&repo)
            .args([
                "-c",
                "user.email=test@test",
                "-c",
                "user.name=test",
                "commit",
                "-m",
                "full stack",
            ])
            .output()
            .unwrap();
        Command::new("git")
            .current_dir(&repo)
            .args(["branch", "-f", "main"])
            .output()
            .unwrap();
        Command::new("git")
            .current_dir(&repo)
            .args(["checkout", "main"])
            .output()
            .unwrap();
        fs::remove_file(repo.join("src/lib.rs")).unwrap();
        fs::write(repo.join("CHANGES"), "").unwrap();
        Command::new("git")
            .current_dir(&repo)
            .args(["add", "-A"])
            .output()
            .unwrap();
        Command::new("git")
            .current_dir(&repo)
            .args([
                "-c",
                "user.email=test@test",
                "-c",
                "user.name=test",
                "commit",
                "-m",
                "placeholder main",
            ])
            .output()
            .unwrap();
        Command::new("git")
            .current_dir(&repo)
            .args(["checkout", "mini"])
            .output()
            .unwrap();
        let files = collect_range_diff_files(&repo, "main", true).unwrap();
        assert!(
            files.is_empty(),
            "branch scope uses three-dot only; no tree-diff when HEAD has no commits ahead of base"
        );
        let (cmd, _) = branch_range_changed_files(&repo, "main").unwrap();
        assert!(cmd.contains("main...HEAD"));
    }

    #[test]
    fn resolve_diff_base_prefers_origin_before_local_main() {
        let dir = tempfile::tempdir().unwrap();
        let repo = dir.path();
        fs::create_dir_all(repo.join("src")).unwrap();
        fs::write(repo.join("src/base.rs"), "fn base() {}\n").unwrap();
        Command::new("git")
            .current_dir(repo)
            .args(["init", "-b", "main"])
            .output()
            .unwrap();
        Command::new("git")
            .current_dir(repo)
            .args(["add", "."])
            .output()
            .unwrap();
        Command::new("git")
            .current_dir(repo)
            .args([
                "-c",
                "user.email=test@test",
                "-c",
                "user.name=test",
                "commit",
                "-m",
                "base",
            ])
            .output()
            .unwrap();
        let origin_main = String::from_utf8(
            Command::new("git")
                .current_dir(repo)
                .args(["rev-parse", "HEAD"])
                .output()
                .unwrap()
                .stdout,
        )
        .unwrap()
        .trim()
        .to_string();
        Command::new("git")
            .current_dir(repo)
            .args(["update-ref", "refs/remotes/origin/main", &origin_main])
            .output()
            .unwrap();
        Command::new("git")
            .current_dir(repo)
            .args(["checkout", "-b", "feature"])
            .output()
            .unwrap();
        fs::write(repo.join("src/branch.rs"), "fn branch() {}\n").unwrap();
        Command::new("git")
            .current_dir(repo)
            .args(["add", "src/branch.rs"])
            .output()
            .unwrap();
        Command::new("git")
            .current_dir(repo)
            .args([
                "-c",
                "user.email=test@test",
                "-c",
                "user.name=test",
                "commit",
                "-m",
                "on branch",
            ])
            .output()
            .unwrap();
        Command::new("git")
            .current_dir(repo)
            .args(["checkout", "main"])
            .output()
            .unwrap();
        fs::write(repo.join("src/main_only.rs"), "fn main_only() {}\n").unwrap();
        Command::new("git")
            .current_dir(repo)
            .args(["add", "src/main_only.rs"])
            .output()
            .unwrap();
        Command::new("git")
            .current_dir(repo)
            .args([
                "-c",
                "user.email=test@test",
                "-c",
                "user.name=test",
                "commit",
                "-m",
                "advance local main",
            ])
            .output()
            .unwrap();
        Command::new("git")
            .current_dir(repo)
            .args(["checkout", "feature"])
            .output()
            .unwrap();
        let resolved = resolve_diff_base_for_scope(repo, "main", true).expect("resolved");
        assert_eq!(resolved, "origin/main");
        let files = collect_range_diff_files(repo, "main", true).unwrap();
        assert_eq!(files, vec!["src/branch.rs".to_string()]);
        let (cmd, _) = branch_range_changed_files(repo, "origin/main").unwrap();
        assert!(cmd.contains("origin/main...HEAD"));
    }

    #[test]
    fn shell_commands_match_branch_only_collect_mode() {
        let dir = tempfile::tempdir().unwrap();
        let sub = dir.path().join("app/service");
        fs::create_dir_all(sub.join("src")).unwrap();
        fs::write(sub.join("src/lib.rs"), "fn lib() {}\n").unwrap();
        Command::new("git")
            .current_dir(&sub)
            .args(["init", "-b", "main"])
            .output()
            .unwrap();
        Command::new("git")
            .current_dir(&sub)
            .args(["add", "."])
            .output()
            .unwrap();
        Command::new("git")
            .current_dir(&sub)
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
        let cmds =
            collect_changed_files_shell_commands(&sub, "main", ReviewChangedScope::Branch, true);
        assert!(cmds
            .iter()
            .any(|c| c.contains("diff --name-only") && c.contains("main")));
        assert!(!cmds.iter().any(|c| c.contains("ls-files --others")));
    }

    #[test]
    fn separate_repo_scope_uses_only_diff_base_range_not_worktree() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path();
        let sub = root.join("app/backoffice");
        fs::create_dir_all(sub.join("src")).unwrap();
        fs::write(sub.join("src/tracked.rs"), "fn tracked() {}\n").unwrap();
        fs::write(sub.join("src/dirty.rs"), "fn dirty() {}\n").unwrap();
        Command::new("git")
            .current_dir(&sub)
            .args(["init", "-b", "main"])
            .output()
            .unwrap();
        Command::new("git")
            .current_dir(&sub)
            .args(["add", "src/tracked.rs", "src/dirty.rs"])
            .output()
            .unwrap();
        Command::new("git")
            .current_dir(&sub)
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
        fs::write(sub.join("src/dirty.rs"), "fn dirty() { println!(); }\n").unwrap();
        fs::write(sub.join("src/untracked.rs"), "fn new() {}\n").unwrap();
        let (git_root, path_prefix) = resolve_project_git_scope(root, &sub, None);
        let project = ChangedFilesProject {
            id: "backoffice".into(),
            diff_base: "main".into(),
            changed_scope: ReviewChangedScope::Branch,
            package_prefixes: vec!["app/backoffice/".into()],
            git_root,
            workspace_root: root.to_path_buf(),
            path_prefix,
            git_scopes: vec![],
        };
        let mapped = changed_files_by_project(root, &[project]).unwrap();
        let files = mapped
            .get("backoffice")
            .map(|v| v.as_slice())
            .unwrap_or(&[]);
        assert!(files.is_empty());
        Command::new("git")
            .current_dir(&sub)
            .args(["checkout", "-b", "feature"])
            .output()
            .unwrap();
        fs::write(sub.join("src/tracked.rs"), "fn tracked() { println!(); }\n").unwrap();
        Command::new("git")
            .current_dir(&sub)
            .args(["add", "src/tracked.rs"])
            .output()
            .unwrap();
        Command::new("git")
            .current_dir(&sub)
            .args([
                "-c",
                "user.email=test@test",
                "-c",
                "user.name=test",
                "commit",
                "-m",
                "change",
            ])
            .output()
            .unwrap();
        let mapped = changed_files_by_project(
            root,
            &[ChangedFilesProject {
                id: "backoffice".into(),
                diff_base: "main".into(),
                changed_scope: ReviewChangedScope::Branch,
                package_prefixes: vec!["app/backoffice/".into()],
                git_root: sub.to_path_buf(),
                workspace_root: root.to_path_buf(),
                path_prefix: "app/backoffice/".into(),
                git_scopes: vec![],
            }],
        )
        .unwrap();
        let files = mapped.get("backoffice").expect("files");
        assert_eq!(files.len(), 1);
        assert_eq!(files[0], "app/backoffice/src/tracked.rs");
    }

    #[test]
    fn container_app_project_merges_module_git_scopes() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path();
        let app_root = root.join("app");
        let mini = app_root.join("mini");
        let backoffice = app_root.join("backoffice");
        fs::create_dir_all(mini.join("src")).unwrap();
        fs::create_dir_all(backoffice.join("src")).unwrap();
        fs::write(mini.join("src/lib.rs"), "fn mini() {}\n").unwrap();
        fs::write(backoffice.join("src/lib.rs"), "fn backoffice() {}\n").unwrap();
        Command::new("git")
            .current_dir(root)
            .args(["init", "-b", "main"])
            .output()
            .unwrap();
        for sub in [&mini, &backoffice] {
            Command::new("git")
                .current_dir(sub)
                .args(["init", "-b", "main"])
                .output()
                .unwrap();
            Command::new("git")
                .current_dir(sub)
                .args(["add", "."])
                .output()
                .unwrap();
            Command::new("git")
                .current_dir(sub)
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
            Command::new("git")
                .current_dir(sub)
                .args(["checkout", "-b", "feature"])
                .output()
                .unwrap();
        }
        fs::write(mini.join("src/lib.rs"), "fn mini() { println!(); }\n").unwrap();
        fs::write(
            backoffice.join("src/lib.rs"),
            "fn backoffice() { println!(); }\n",
        )
        .unwrap();
        Command::new("git")
            .current_dir(&mini)
            .args(["add", "src/lib.rs"])
            .output()
            .unwrap();
        Command::new("git")
            .current_dir(&mini)
            .args([
                "-c",
                "user.email=test@test",
                "-c",
                "user.name=test",
                "commit",
                "-m",
                "mini change",
            ])
            .output()
            .unwrap();
        Command::new("git")
            .current_dir(&backoffice)
            .args(["add", "src/lib.rs"])
            .output()
            .unwrap();
        Command::new("git")
            .current_dir(&backoffice)
            .args([
                "-c",
                "user.email=test@test",
                "-c",
                "user.name=test",
                "commit",
                "-m",
                "backoffice change",
            ])
            .output()
            .unwrap();
        let scopes = discover_nested_git_scopes(
            root,
            &app_root,
            &["mini".into(), "backoffice".into()],
            None,
        );
        assert_eq!(scopes.len(), 2);
        let project = ChangedFilesProject {
            id: "app".into(),
            diff_base: "main".into(),
            changed_scope: ReviewChangedScope::Branch,
            package_prefixes: vec!["app/mini/".into(), "app/backoffice/".into()],
            git_root: mini.clone(),
            workspace_root: root.to_path_buf(),
            path_prefix: "app/mini/".into(),
            git_scopes: scopes,
        };
        let mapped = changed_files_by_project(root, &[project]).unwrap();
        let files = mapped.get("app").expect("app files");
        assert!(files.iter().any(|p| p == "app/mini/src/lib.rs"));
        assert!(files.iter().any(|p| p == "app/backoffice/src/lib.rs"));
    }

    #[test]
    fn nested_git_repo_changed_files_are_prefixed_for_monorepo() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path();
        let sub = root.join("app/backoffice");
        fs::create_dir_all(sub.join("src")).unwrap();
        fs::write(sub.join("src/lib.rs"), "fn main() {}\n").unwrap();
        Command::new("git")
            .current_dir(root)
            .args(["init"])
            .output()
            .unwrap();
        Command::new("git")
            .current_dir(&sub)
            .args(["init"])
            .output()
            .unwrap();
        Command::new("git")
            .current_dir(&sub)
            .args(["add", "."])
            .output()
            .unwrap();
        Command::new("git")
            .current_dir(&sub)
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
        Command::new("git")
            .current_dir(&sub)
            .args(["checkout", "-b", "feature"])
            .output()
            .unwrap();
        fs::write(sub.join("src/lib.rs"), "fn main() { println!(); }\n").unwrap();
        Command::new("git")
            .current_dir(&sub)
            .args(["add", "src/lib.rs"])
            .output()
            .unwrap();
        Command::new("git")
            .current_dir(&sub)
            .args([
                "-c",
                "user.email=test@test",
                "-c",
                "user.name=test",
                "commit",
                "-m",
                "change",
            ])
            .output()
            .unwrap();
        let (git_root, path_prefix) = resolve_project_git_scope(root, &sub, None);
        assert!(is_separate_git_repository(root, &sub));
        assert_eq!(path_prefix, "app/backoffice/");
        let project = ChangedFilesProject {
            id: "backoffice".into(),
            diff_base: "main".into(),
            changed_scope: ReviewChangedScope::Branch,
            package_prefixes: vec!["app/backoffice/".into()],
            git_root,
            workspace_root: root.to_path_buf(),
            path_prefix,
            git_scopes: vec![],
        };
        let mapped = changed_files_by_project(root, &[project]).unwrap();
        let files = mapped.get("backoffice").expect("project files");
        assert!(files.iter().any(|p| p == "app/backoffice/src/lib.rs"));
    }

    #[test]
    fn git_unified_diff_scoped_reads_nested_repository() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path();
        let sub = root.join("app/backoffice");
        fs::create_dir_all(sub.join("src")).unwrap();
        fs::write(sub.join("src/lib.rs"), "fn main() {}\n").unwrap();
        Command::new("git")
            .current_dir(root)
            .args(["init"])
            .output()
            .unwrap();
        Command::new("git")
            .current_dir(&sub)
            .args(["init"])
            .output()
            .unwrap();
        Command::new("git")
            .current_dir(&sub)
            .args(["add", "."])
            .output()
            .unwrap();
        Command::new("git")
            .current_dir(&sub)
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
        fs::write(sub.join("src/lib.rs"), "fn main() { println!(); }\n").unwrap();
        let (git_root, path_prefix) = resolve_project_git_scope(root, &sub, None);
        let diff = git_unified_diff_scoped(
            root,
            &git_root,
            &path_prefix,
            "app/backoffice/src/lib.rs",
            "main",
            3,
        )
        .unwrap();
        assert!(diff.contains("println"));
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
        let files = collect_changed_files_for_base(root, "empty").unwrap();
        assert!(files.iter().any(|p| p.ends_with("pkg/src/lib.rs")));
    }
}
