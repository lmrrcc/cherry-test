use std::path::{Path, PathBuf};
use std::process::Command;

pub fn vitest_run_args(runner_args: &[String]) -> Vec<String> {
    if runner_args.len() >= 3 && runner_args[0] == "exec" && runner_args[1] == "vitest" {
        return runner_args[2..].to_vec();
    }
    if runner_args.first().is_some_and(|arg| arg == "vitest") {
        return runner_args[1..].to_vec();
    }
    runner_args.to_vec()
}

pub fn playwright_run_args(runner_args: &[String]) -> Vec<String> {
    if runner_args.len() >= 3 && runner_args[0] == "exec" && runner_args[1] == "playwright" {
        return runner_args[2..].to_vec();
    }
    if runner_args.first().is_some_and(|arg| arg == "playwright") {
        return runner_args[1..].to_vec();
    }
    runner_args.to_vec()
}

pub fn local_vitest_mjs(cwd: &Path) -> Option<PathBuf> {
    let path = cwd.join("node_modules/vitest/vitest.mjs");
    path.is_file().then_some(path)
}

pub fn local_playwright_cli(cwd: &Path) -> Option<PathBuf> {
    let path = cwd.join("node_modules/@playwright/test/cli.js");
    path.is_file().then_some(path)
}

pub fn resolve_node_binary(cwd: &Path) -> Option<PathBuf> {
    if node_works("node") {
        return Some(PathBuf::from("node"));
    }
    for path in node_binary_candidates(cwd) {
        if node_works(&path) {
            return Some(path);
        }
    }
    None
}

pub fn augmented_path(cwd: &Path) -> String {
    let separator = if cfg!(windows) { ";" } else { ":" };
    let mut segments = Vec::new();
    push_unique(
        &mut segments,
        cwd.join("node_modules/.bin").to_string_lossy().as_ref(),
    );
    for candidate in node_binary_candidates(cwd) {
        if let Some(parent) = candidate.parent() {
            push_unique(&mut segments, &parent.to_string_lossy());
        }
    }
    if let Ok(path) = std::env::var("PATH") {
        push_unique(&mut segments, &path);
    }
    segments.join(separator)
}

fn push_unique(segments: &mut Vec<String>, value: &str) {
    if value.is_empty() {
        return;
    }
    if !segments.iter().any(|segment| segment == value) {
        segments.push(value.to_string());
    }
}

fn node_works(node: impl AsRef<Path>) -> bool {
    Command::new(node.as_ref())
        .arg("--version")
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .status()
        .map(|status| status.success())
        .unwrap_or(false)
}

fn node_binary_candidates(cwd: &Path) -> Vec<PathBuf> {
    let mut out = Vec::new();
    if let Ok(nvm_bin) = std::env::var("NVM_BIN") {
        out.push(PathBuf::from(nvm_bin).join("node"));
    }
    if let Ok(home) = std::env::var("HOME") {
        let nvm_root = PathBuf::from(home).join(".nvm/versions/node");
        if let Ok(entries) = std::fs::read_dir(&nvm_root) {
            let mut versions: Vec<PathBuf> = entries.flatten().map(|entry| entry.path()).collect();
            versions.sort();
            for version in versions.into_iter().rev() {
                out.push(version.join("bin/node"));
            }
        }
    }
    out.push(PathBuf::from("/opt/homebrew/bin/node"));
    out.push(PathBuf::from("/usr/local/bin/node"));
    let _ = cwd;
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn vitest_run_args_strips_pnpm_exec_prefix() {
        let args = vec![
            "exec".into(),
            "vitest".into(),
            "run".into(),
            "--coverage".into(),
        ];
        assert_eq!(
            vitest_run_args(&args),
            vec!["run".to_string(), "--coverage".to_string()]
        );
    }

    #[test]
    fn vitest_run_args_keeps_direct_args() {
        let args = vec!["run".into(), "--coverage".into()];
        assert_eq!(vitest_run_args(&args), args);
    }

    #[test]
    fn playwright_run_args_strips_pnpm_exec_prefix() {
        let args = vec!["exec".into(), "playwright".into(), "test".into()];
        assert_eq!(playwright_run_args(&args), vec!["test".to_string()]);
    }

    #[test]
    fn playwright_run_args_keeps_direct_args() {
        let args = vec!["test".into(), "--project".into(), "chromium".into()];
        assert_eq!(playwright_run_args(&args), args);
    }

    #[test]
    fn resolve_node_binary_finds_nvm_node_without_path() {
        let home = std::env::var("HOME").unwrap_or_default();
        let nvm_root = PathBuf::from(home).join(".nvm/versions/node");
        if !nvm_root.is_dir() {
            return;
        }
        let node = resolve_node_binary(Path::new("."));
        assert!(node.is_some());
    }
}
