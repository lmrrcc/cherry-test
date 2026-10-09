use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

use anyhow::{Context, Result};

#[derive(Debug, Clone)]
pub struct RunDir {
    pub id: String,
    pub root: PathBuf,
    pub reports: PathBuf,
    pub coverage: PathBuf,
}

impl RunDir {
    pub fn create(workspace_root: &Path, runs_base: &Path) -> Result<Self> {
        let id = new_run_id();
        let base = if runs_base.is_absolute() {
            runs_base.to_path_buf()
        } else {
            workspace_root.join(runs_base)
        };
        let root = base.join(&id);
        let reports = root.join("reports");
        let coverage = root.join("coverage");
        std::fs::create_dir_all(&reports)
            .with_context(|| format!("create {}", reports.display()))?;
        std::fs::create_dir_all(&coverage)
            .with_context(|| format!("create {}", coverage.display()))?;
        Ok(Self {
            id,
            root,
            reports,
            coverage,
        })
    }
    pub fn targets_dir(&self, project_id: &str) -> PathBuf {
        self.root.join("targets").join(project_id)
    }

    pub fn coverage_dir(&self, project_id: &str) -> PathBuf {
        self.coverage.join(project_id)
    }

    pub fn merged_lcov(&self) -> PathBuf {
        self.coverage.join("merged.lcov")
    }

    pub fn report_html(&self) -> PathBuf {
        self.reports.join("index.html")
    }

    pub fn env_json(&self) -> PathBuf {
        self.root.join("env.json")
    }

    pub fn export_env(&self) {
        std::env::set_var("CHERRY_TEST_RUN_DIR", self.root.to_string_lossy().as_ref());
        std::env::set_var(
            "CHERRY_TEST_REPORT_DIR",
            self.reports.to_string_lossy().as_ref(),
        );
    }

    pub fn update_latest_symlink(&self, workspace_root: &Path, runs_base: &Path) -> Result<()> {
        let base = if runs_base.is_absolute() {
            runs_base.to_path_buf()
        } else {
            workspace_root.join(runs_base)
        };
        let latest_parent = base.parent().unwrap_or(&base);
        let latest = latest_parent.join("latest");
        std::fs::create_dir_all(latest_parent)
            .with_context(|| format!("create {}", latest_parent.display()))?;
        let _ = std::fs::remove_file(&latest);
        #[cfg(unix)]
        std::os::unix::fs::symlink(&self.root, &latest)
            .with_context(|| format!("symlink {} -> {}", latest.display(), self.root.display()))?;
        #[cfg(not(unix))]
        {
            let _ = latest;
        }
        Ok(())
    }
}

fn new_run_id() -> String {
    let dur = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default();
    let secs = dur.as_secs();
    let (y, m, d, h, min, s) = utc_from_secs(secs);
    let hex = format!("{:06x}", dur.subsec_nanos() & 0xffffff);
    format!("{y:04}{m:02}{d:02}-{h:02}{min:02}{s:02}-{hex}")
}

fn utc_from_secs(secs: u64) -> (u64, u64, u64, u64, u64, u64) {
    let days = secs / 86400;
    let day_secs = secs % 86400;
    let hour = day_secs / 3600;
    let min = (day_secs % 3600) / 60;
    let sec = day_secs % 60;
    let mut y = 1970u64;
    let mut remaining = days;
    loop {
        let year_days = if is_leap(y) { 366 } else { 365 };
        if remaining < year_days {
            break;
        }
        remaining -= year_days;
        y += 1;
    }
    let month_days = if is_leap(y) {
        [31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]
    } else {
        [31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]
    };
    let mut m = 1u64;
    for &md in &month_days {
        if remaining < md {
            break;
        }
        remaining -= md;
        m += 1;
    }
    let d = remaining + 1;
    (y, m, d, hour, min, sec)
}

fn is_leap(y: u64) -> bool {
    y % 4 == 0 && (y % 100 != 0 || y % 400 == 0)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn run_id_has_expected_shape() {
        let id = new_run_id();
        assert_eq!(id.len(), 22);
        assert!(id.chars().filter(|c| *c == '-').count() == 2);
    }

    #[test]
    fn layout_paths_under_run_root() {
        let dir = tempfile::tempdir().unwrap();
        let ws = dir.path();
        let run = RunDir::create(ws, Path::new("runs")).unwrap();
        assert!(run.root.starts_with(ws.join("runs")));
        assert_eq!(run.targets_dir("mini"), run.root.join("targets/mini"));
        assert_eq!(run.coverage_dir("mini"), run.coverage.join("mini"));
        assert_eq!(run.merged_lcov(), run.coverage.join("merged.lcov"));
        assert_eq!(run.report_html(), run.reports.join("index.html"));
        assert!(run.reports.is_dir());
        assert!(run.coverage.is_dir());
    }

    #[test]
    fn unique_run_dirs() {
        let dir = tempfile::tempdir().unwrap();
        let a = RunDir::create(dir.path(), Path::new("runs")).unwrap();
        let b = RunDir::create(dir.path(), Path::new("runs")).unwrap();
        assert_ne!(a.root, b.root);
    }

    #[test]
    fn export_env_sets_vars() {
        let dir = tempfile::tempdir().unwrap();
        let run = RunDir::create(dir.path(), Path::new("runs")).unwrap();
        run.export_env();
        assert_eq!(
            std::env::var("CHERRY_TEST_RUN_DIR").unwrap(),
            run.root.to_string_lossy()
        );
        assert_eq!(
            std::env::var("CHERRY_TEST_REPORT_DIR").unwrap(),
            run.reports.to_string_lossy()
        );
    }
}
