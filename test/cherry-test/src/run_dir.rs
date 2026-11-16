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

    /// `CHERRY_TEST_RUN_DIR` and `CHERRY_TEST_REPORT_DIR`, which every job of the run receives.
    pub fn env(&self) -> Vec<(String, String)> {
        vec![
            (
                "CHERRY_TEST_RUN_DIR".into(),
                self.root.to_string_lossy().into_owned(),
            ),
            (
                "CHERRY_TEST_REPORT_DIR".into(),
                self.reports.to_string_lossy().into_owned(),
            ),
        ]
    }

    /// Folds one job's report directory into the run's: `*.jsonl` files are appended, others copied over.
    /// The job directory is removed afterwards, so the run keeps the layout of a single shared directory.
    pub fn merge_job_report(&self, job_report: &Path) -> Result<()> {
        if !job_report.is_dir() {
            return Ok(());
        }
        let mut files = Vec::new();
        collect_files(job_report, &mut files)?;
        for file in files {
            let relative = file.strip_prefix(job_report)?;
            let target = self.reports.join(relative);
            if let Some(parent) = target.parent() {
                std::fs::create_dir_all(parent)
                    .with_context(|| format!("create {}", parent.display()))?;
            }
            if file.extension().and_then(|ext| ext.to_str()) == Some("jsonl") {
                use std::io::Write;
                let content =
                    std::fs::read(&file).with_context(|| format!("read {}", file.display()))?;
                std::fs::OpenOptions::new()
                    .create(true)
                    .append(true)
                    .open(&target)
                    .and_then(|mut out| out.write_all(&content))
                    .with_context(|| format!("append {}", target.display()))?;
            } else {
                std::fs::copy(&file, &target)
                    .with_context(|| format!("copy {} to {}", file.display(), target.display()))?;
            }
        }
        std::fs::remove_dir_all(job_report)
            .with_context(|| format!("remove {}", job_report.display()))
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

/// Regular files under `dir`, depth first in name order.
fn collect_files(dir: &Path, out: &mut Vec<PathBuf>) -> Result<()> {
    let mut entries: Vec<PathBuf> = std::fs::read_dir(dir)
        .with_context(|| format!("read {}", dir.display()))?
        .map(|entry| entry.map(|entry| entry.path()))
        .collect::<std::io::Result<_>>()?;
    entries.sort();
    for path in entries {
        if path.is_dir() {
            collect_files(&path, out)?;
        } else {
            out.push(path);
        }
    }
    Ok(())
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
    fn env_names_run_and_report_dirs() {
        let dir = tempfile::tempdir().unwrap();
        let run = RunDir::create(dir.path(), Path::new("runs")).unwrap();
        let env = run.env();
        assert_eq!(
            env,
            vec![
                (
                    "CHERRY_TEST_RUN_DIR".to_string(),
                    run.root.to_string_lossy().into_owned()
                ),
                (
                    "CHERRY_TEST_REPORT_DIR".to_string(),
                    run.reports.to_string_lossy().into_owned()
                ),
            ]
        );
        assert!(std::env::var("CHERRY_TEST_RUN_DIR")
            .map_or(true, |dir| dir != run.root.to_string_lossy()));
    }

    #[test]
    fn merges_job_reports_in_job_order() {
        let dir = tempfile::tempdir().unwrap();
        let run = RunDir::create(dir.path(), Path::new("runs")).unwrap();
        let first = run.reports.join("adapters/shop/api/unit/report");
        let second = run.reports.join("adapters/shop/web/unit/report");
        for (job, line, shot) in [(&first, "api\n", "api-v1"), (&second, "web\n", "web-v2")] {
            std::fs::create_dir_all(job.join("integration")).unwrap();
            std::fs::create_dir_all(job.join("screenshots")).unwrap();
            std::fs::write(job.join("integration/shop.jsonl"), line).unwrap();
            std::fs::write(job.join("screenshots/home.png"), shot).unwrap();
        }
        std::fs::write(second.join("screenshots/web.png"), "web").unwrap();
        run.merge_job_report(&first).unwrap();
        run.merge_job_report(&second).unwrap();
        run.merge_job_report(&run.reports.join("adapters/shop/none/unit/report"))
            .unwrap();
        assert_eq!(
            std::fs::read_to_string(run.reports.join("integration/shop.jsonl")).unwrap(),
            "api\nweb\n"
        );
        assert_eq!(
            std::fs::read_to_string(run.reports.join("screenshots/home.png")).unwrap(),
            "web-v2"
        );
        assert!(run.reports.join("screenshots/web.png").is_file());
        assert!(!first.exists());
        assert!(!second.exists());
    }
}
