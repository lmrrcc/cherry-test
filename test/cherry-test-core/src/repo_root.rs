use std::path::{Path, PathBuf};
use std::process::Command;

use anyhow::{Context, Result};

const CONFIG_NAMES: &[&str] = &["cherry-test.yaml", "cherry-test.yml", "cherry-test.toml"];

const SKIP_NESTED_DIR_NAMES: &[&str] = &[
    "target",
    "node_modules",
    ".git",
    ".idea",
    ".cursor",
    "dist",
    "build",
    "test",
];

pub fn resolve_repo_root(override_root: Option<&Path>) -> Result<PathBuf> {
    resolve_config_roots(override_root, None)?
        .into_iter()
        .next()
        .context("no cherry-test config root found")
}

pub fn resolve_config_roots(
    override_root: Option<&Path>,
    filter: Option<&str>,
) -> Result<Vec<PathBuf>> {
    if let Some(root) = override_root {
        let root = root
            .canonicalize()
            .with_context(|| format!("resolve repo root {}", root.display()))?;
        if !root.is_dir() {
            anyhow::bail!("repo root `{}` is not a directory", root.display());
        }
        return Ok(vec![root]);
    }
    if let Ok(root) = std::env::var("CHERRY_TEST_WORKSPACE_ROOT") {
        if !root.is_empty() {
            let path = PathBuf::from(root);
            if path.is_dir() {
                return Ok(vec![path]);
            }
        }
    }
    let start = std::env::current_dir().context("current directory")?;
    if let Some(root) = walk_up_for_config(&start) {
        return Ok(vec![root]);
    }
    if let Ok(cargo_ws) = try_cargo_workspace_root() {
        if has_config_file(&cargo_ws) {
            return Ok(vec![cargo_ws]);
        }
        let nested = find_nested_config_dirs(&cargo_ws);
        return select_nested_roots(&cargo_ws, &nested, filter);
    }
    let cwd = start
        .canonicalize()
        .with_context(|| format!("resolve repo root {}", start.display()))?;
    Ok(vec![cwd])
}

fn select_nested_roots(
    cargo_ws: &Path,
    nested: &[PathBuf],
    filter: Option<&str>,
) -> Result<Vec<PathBuf>> {
    if nested.is_empty() {
        return Ok(vec![cargo_ws.to_path_buf()]);
    }
    if let Some(filter) = filter {
        let filtered: Vec<PathBuf> = nested
            .iter()
            .filter(|dir| {
                dir.file_name()
                    .and_then(|n| n.to_str())
                    .map(|name| name == filter)
                    .unwrap_or(false)
            })
            .cloned()
            .collect();
        if !filtered.is_empty() {
            return Ok(filtered);
        }
    }
    if nested.len() == 1 {
        return Ok(vec![nested[0].clone()]);
    }
    Ok(nested.to_vec())
}

pub fn find_nested_config_dirs(cargo_workspace: &Path) -> Vec<PathBuf> {
    let mut out = Vec::new();
    let Ok(entries) = std::fs::read_dir(cargo_workspace) else {
        return out;
    };
    for entry in entries.flatten() {
        let path = entry.path();
        if !path.is_dir() {
            continue;
        }
        let name = path.file_name().and_then(|n| n.to_str()).unwrap_or("");
        if SKIP_NESTED_DIR_NAMES.contains(&name) {
            continue;
        }
        if has_config_file(&path) {
            out.push(path);
        }
    }
    out.sort();
    out
}

fn walk_up_for_config(start: &Path) -> Option<PathBuf> {
    let mut dir = start.to_path_buf();
    loop {
        if has_config_file(&dir) {
            return Some(dir);
        }
        if !dir.pop() {
            break;
        }
    }
    None
}

fn has_config_file(dir: &Path) -> bool {
    CONFIG_NAMES.iter().any(|name| dir.join(name).exists())
}

fn try_cargo_workspace_root() -> Result<PathBuf> {
    let output = Command::new("cargo")
        .args(["locate-project", "--workspace", "--message-format=plain"])
        .output()
        .context("failed to run cargo locate-project")?;
    if !output.status.success() {
        anyhow::bail!("cargo locate-project failed");
    }
    let manifest = String::from_utf8(output.stdout)?.trim().to_string();
    let manifest_path = PathBuf::from(manifest);
    Ok(manifest_path
        .parent()
        .context("workspace manifest has no parent")?
        .to_path_buf())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    #[test]
    fn finds_root_via_test_yaml_in_parent() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path();
        fs::create_dir_all(root.join("mini/pkg")).unwrap();
        fs::write(root.join("cherry-test.yaml"), "projects: {}\n").unwrap();
        let found = walk_up_for_config(&root.join("mini/pkg")).unwrap();
        assert_eq!(found, root);
    }

    #[test]
    fn finds_root_via_test_yaml_in_cwd() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path();
        fs::write(root.join("cherry-test.yaml"), "projects: {}\n").unwrap();
        let found = walk_up_for_config(root).unwrap();
        assert_eq!(found, root);
    }

    #[test]
    fn walk_up_returns_none_when_no_config() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path();
        fs::create_dir_all(root.join("sub")).unwrap();
        assert!(walk_up_for_config(&root.join("sub")).is_none());
    }

    #[test]
    fn find_nested_config_dirs_finds_immediate_children() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path();
        fs::create_dir_all(root.join("mini/pkg")).unwrap();
        fs::create_dir_all(root.join("other")).unwrap();
        fs::write(root.join("mini/cherry-test.yaml"), "projects: {}\n").unwrap();
        fs::write(root.join("other/cherry-test.yaml"), "projects: {}\n").unwrap();
        let found = find_nested_config_dirs(root);
        assert_eq!(found.len(), 2);
        assert!(found.iter().any(|p| p.ends_with("mini")));
        assert!(found.iter().any(|p| p.ends_with("other")));
    }

    #[test]
    fn find_nested_config_dirs_skips_target_and_test() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path();
        fs::create_dir_all(root.join("target")).unwrap();
        fs::create_dir_all(root.join("test")).unwrap();
        fs::write(root.join("target/cherry-test.yaml"), "projects: {}\n").unwrap();
        let found = find_nested_config_dirs(root);
        assert!(found.is_empty());
    }

    #[test]
    fn select_nested_roots_single_picks_it() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path();
        let mini = root.join("mini");
        fs::create_dir_all(&mini).unwrap();
        let nested = vec![mini.clone()];
        let selected = select_nested_roots(root, &nested, None).unwrap();
        assert_eq!(selected, vec![mini]);
    }

    #[test]
    fn select_nested_roots_filter_by_dir_name() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path();
        let mini = root.join("mini");
        let other = root.join("other");
        fs::create_dir_all(&mini).unwrap();
        fs::create_dir_all(&other).unwrap();
        let nested = vec![mini.clone(), other];
        let selected = select_nested_roots(root, &nested, Some("mini")).unwrap();
        assert_eq!(selected, vec![mini]);
    }

    #[test]
    fn select_nested_roots_multiple_without_filter() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path();
        let a = root.join("a");
        let b = root.join("b");
        fs::create_dir_all(&a).unwrap();
        fs::create_dir_all(&b).unwrap();
        let nested = vec![a.clone(), b.clone()];
        let selected = select_nested_roots(root, &nested, None).unwrap();
        assert_eq!(selected, vec![a, b]);
    }
}
