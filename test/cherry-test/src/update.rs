use std::path::{Path, PathBuf};
use std::process::Command;

use anyhow::{bail, Context, Result};
use clap::Args;

pub const DEFAULT_REPO: &str = "https://github.com/lmrrcc/test";
pub const DEFAULT_BRANCH: &str = "main";
pub const DEFAULT_CRATE_PATH: &str = "test/cherry-test";
pub const DEFAULT_PACKAGE: &str = "cherry-test";

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct InstallTarget {
    pub package: &'static str,
    pub bin: &'static str,
    pub crate_path: &'static str,
}

#[derive(Debug, Args)]
#[command(about = "Reinstall cherry-test from GitHub (same as install.sh)")]
pub struct UpdateCli {
    #[arg(long, help = "Git repository URL or local checkout path")]
    pub repo: Option<String>,

    #[arg(long, help = "Git branch to install (default: main)")]
    pub branch: Option<String>,

    #[arg(short, long)]
    pub quiet: bool,
}

pub fn install_targets() -> Vec<InstallTarget> {
    ["cherry-test", "cargo-cherry-test"]
        .into_iter()
        .map(|bin| InstallTarget {
            package: DEFAULT_PACKAGE,
            bin,
            crate_path: DEFAULT_CRATE_PATH,
        })
        .collect()
}

pub fn run_update(cli: UpdateCli) -> Result<i32> {
    if !command_available("cargo") {
        bail!("cargo not found; install Rust from https://rustup.rs");
    }
    let repo = cli
        .repo
        .or_else(|| std::env::var("CHERRY_TEST_REPO").ok())
        .unwrap_or_else(|| DEFAULT_REPO.to_string());
    let branch = cli
        .branch
        .or_else(|| std::env::var("CHERRY_TEST_VERSION").ok())
        .unwrap_or_else(|| DEFAULT_BRANCH.to_string());
    let install_root = cargo_install_root()?;
    if !cli.quiet {
        println!("{} update: {repo} @ {branch}", crate::COMMAND_NAME);
    }
    install_commands(&repo, &branch, &install_root)?;
    if !cli.quiet {
        let bin = install_root.join("bin").join(crate::COMMAND_NAME);
        println!("{} updated: {}", crate::COMMAND_NAME, bin.display());
    }
    Ok(0)
}

fn install_commands(repo: &str, branch: &str, install_root: &Path) -> Result<()> {
    let status = if repo.starts_with('/') || repo.starts_with("./") || repo.starts_with(".\\") {
        let manifest_path = PathBuf::from(repo).join(DEFAULT_CRATE_PATH);
        Command::new("cargo")
            .arg("install")
            .arg("--path")
            .arg(&manifest_path)
            .args(install_args(install_root))
            .status()
            .with_context(|| format!("cargo install --path {}", manifest_path.display()))?
    } else {
        Command::new("cargo")
            .arg("install")
            .arg("--git")
            .arg(repo)
            .arg("--branch")
            .arg(branch)
            .arg(DEFAULT_PACKAGE)
            .args(install_args(install_root))
            .status()
            .with_context(|| format!("cargo install --git {repo} {DEFAULT_PACKAGE}"))?
    };
    if !status.success() {
        bail!("{} update failed", crate::COMMAND_NAME);
    }
    Ok(())
}

fn install_args(install_root: &Path) -> Vec<String> {
    let mut args = vec![
        "--locked".into(),
        "--root".into(),
        install_root.to_string_lossy().into_owned(),
        "--force".into(),
    ];
    for target in install_targets() {
        args.extend(["--bin".into(), target.bin.into()]);
    }
    args
}

pub fn cargo_install_root() -> Result<PathBuf> {
    if let Ok(root) = std::env::var("CARGO_INSTALL_ROOT") {
        if !root.is_empty() {
            return Ok(PathBuf::from(root));
        }
    }
    if let Ok(dir) = std::env::var("INSTALL_DIR") {
        if !dir.is_empty() {
            let install_dir = PathBuf::from(dir);
            return install_dir
                .parent()
                .map(Path::to_path_buf)
                .context("INSTALL_DIR has no parent");
        }
    }
    let exe = std::env::current_exe().context("current_exe")?;
    let bin_dir = exe
        .parent()
        .context("cherry-test binary has no parent directory")?;
    if bin_dir.file_name().and_then(|n| n.to_str()) == Some("bin") {
        return bin_dir
            .parent()
            .map(Path::to_path_buf)
            .context("install bin directory has no parent");
    }
    Ok(bin_dir.to_path_buf())
}

fn command_available(name: &str) -> bool {
    Command::new(name)
        .arg("--version")
        .output()
        .map(|o| o.status.success())
        .unwrap_or(false)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cargo_install_root_from_bin_layout() {
        let root =
            cargo_install_root_from_exe(Path::new("/home/user/.local/bin/cherry-test")).unwrap();
        assert_eq!(root, PathBuf::from("/home/user/.local"));
    }

    #[test]
    fn cargo_install_root_from_flat_layout() {
        let root = cargo_install_root_from_exe(Path::new("/opt/cherry-test")).unwrap();
        assert_eq!(root, PathBuf::from("/opt"));
    }

    #[test]
    fn install_targets_include_standalone_and_cargo_commands() {
        let targets = install_targets();
        assert_eq!(
            targets.iter().map(|target| target.bin).collect::<Vec<_>>(),
            vec!["cherry-test", "cargo-cherry-test"]
        );
        assert_eq!(targets[0].package, "cherry-test");
    }

    fn cargo_install_root_from_exe(exe: &Path) -> Result<PathBuf> {
        let bin_dir = exe.parent().context("exe parent")?;
        if bin_dir.file_name().and_then(|n| n.to_str()) == Some("bin") {
            return bin_dir
                .parent()
                .map(Path::to_path_buf)
                .context("install bin directory has no parent");
        }
        Ok(bin_dir.to_path_buf())
    }
}
