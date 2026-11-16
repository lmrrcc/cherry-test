use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::time::Duration;

use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};

use crate::plan::{JobKey, Plan};

/// Runs a job's moving average spans: each run weighs one fifth once five runs are known.
const WINDOW: u64 = 5;

/// Recorded job durations, read from and written to `durations.json` beside the runs directory.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Durations {
    version: u32,
    jobs: BTreeMap<String, DurationEntry>,
}

/// One job's moving average in milliseconds and how many runs it covers.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct DurationEntry {
    pub millis: u64,
    pub runs: u64,
}

impl Durations {
    /// `durations.json` in the directory that holds the runs directory: `target/cherry-test/` by default.
    pub fn path(workspace_root: &Path, runs_base: &Path) -> PathBuf {
        let base = if runs_base.is_absolute() {
            runs_base.to_path_buf()
        } else {
            workspace_root.join(runs_base)
        };
        base.parent()
            .map(Path::to_path_buf)
            .unwrap_or(base)
            .join("durations.json")
    }

    /// Reads `path`; a missing or unreadable file gives no recorded durations.
    pub fn load(path: &Path) -> Self {
        let Ok(text) = std::fs::read_to_string(path) else {
            return Self::default();
        };
        match serde_json::from_str::<Durations>(&text) {
            Ok(durations) => durations,
            Err(error) => {
                tracing::warn!(path = %path.display(), error = %error, "ignoring unreadable durations file");
                Self::default()
            }
        }
    }

    pub fn save(&self, path: &Path) -> Result<()> {
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        let file = Durations {
            version: 1,
            jobs: self.jobs.clone(),
        };
        std::fs::write(path, serde_json::to_string_pretty(&file)?)
            .with_context(|| format!("write {}", path.display()))
    }

    pub fn get(&self, key: &JobKey) -> Option<DurationEntry> {
        self.jobs.get(&key.id()).copied()
    }

    pub fn is_empty(&self) -> bool {
        self.jobs.is_empty()
    }

    /// Folds one run's elapsed time into the job's moving average, rounding to the nearest millisecond.
    pub fn record(&mut self, key: &JobKey, elapsed: Duration) {
        let sample = u64::try_from(elapsed.as_millis()).unwrap_or(u64::MAX);
        let entry = self.jobs.entry(key.id()).or_insert(DurationEntry {
            millis: sample,
            runs: 0,
        });
        let weight = entry.runs.min(WINDOW - 1);
        let total = u128::from(entry.millis) * u128::from(weight) + u128::from(sample);
        let divisor = u128::from(weight + 1);
        entry.millis = u64::try_from((total + divisor / 2) / divisor).unwrap_or(u64::MAX);
        entry.runs = entry.runs.saturating_add(1);
    }

    /// The sum of every recorded phase of one package, or `None` when none was recorded.
    pub fn package_total(&self, project: &str, package: &str) -> Option<u64> {
        let prefix = format!("{project}/{package}/");
        let mut found = false;
        let mut total: u64 = 0;
        for (key, entry) in &self.jobs {
            if key
                .strip_prefix(&prefix)
                .is_some_and(|phase| !phase.contains('/'))
            {
                found = true;
                total = total.saturating_add(entry.millis);
            }
        }
        found.then_some(total)
    }

    /// Sets every job's estimate: its recorded average, or else the 75th percentile of the plan's known jobs.
    pub fn estimate(&self, plan: &mut Plan) {
        let known: Vec<u64> = plan
            .jobs()
            .filter_map(|job| self.get(&job.key).map(|entry| entry.millis))
            .collect();
        let fallback = percentile_75(&known);
        for phase in &mut plan.phases {
            for job in &mut phase.jobs {
                job.estimate_ms = self
                    .get(&job.key)
                    .map(|entry| entry.millis)
                    .unwrap_or(fallback);
            }
        }
    }
}

/// The nearest-rank 75th percentile of `values`, or 0 when there are none.
pub fn percentile_75(values: &[u64]) -> u64 {
    if values.is_empty() {
        return 0;
    }
    let mut sorted = values.to_vec();
    sorted.sort_unstable();
    let rank = (3 * sorted.len()).div_ceil(4);
    sorted[rank.saturating_sub(1)]
}

/// Records every finished job of a run in `path`, keeping other jobs' averages.
pub fn update(path: &Path, timings: &[(JobKey, Duration)]) -> Result<()> {
    if timings.is_empty() {
        return Ok(());
    }
    let mut durations = Durations::load(path);
    for (key, elapsed) in timings {
        durations.record(key, *elapsed);
    }
    durations.save(path)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn key(package: &str, phase: &str) -> JobKey {
        JobKey::new("shop", package, phase)
    }

    #[test]
    fn path_sits_beside_the_runs_directory() {
        assert_eq!(
            Durations::path(Path::new("/ws"), Path::new("target/cherry-test/runs")),
            Path::new("/ws/target/cherry-test/durations.json")
        );
        assert_eq!(
            Durations::path(Path::new("/ws"), Path::new("/ci/runs")),
            Path::new("/ci/durations.json")
        );
    }

    #[test]
    fn record_keeps_a_moving_average_over_five_runs() {
        let mut durations = Durations::default();
        let api = key("api", "unit");
        durations.record(&api, Duration::from_millis(1000));
        assert_eq!(
            durations.get(&api),
            Some(DurationEntry {
                millis: 1000,
                runs: 1
            })
        );
        durations.record(&api, Duration::from_millis(2001));
        assert_eq!(durations.get(&api).map(|e| e.millis), Some(1501));
        for _ in 0..10 {
            durations.record(&api, Duration::from_millis(6000));
        }
        let entry = durations.get(&api).unwrap();
        assert_eq!(entry.runs, 12);
        assert!(entry.millis > 5000 && entry.millis <= 6000);
        durations.record(&api, Duration::from_millis(1000));
        assert_eq!(
            durations.get(&api).map(|e| e.millis),
            Some((entry.millis * 4 + 1000 + 2) / 5)
        );
    }

    #[test]
    fn writes_and_reads_back_sorted_pretty_json() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("target/cherry-test/durations.json");
        update(
            &path,
            &[
                (key("web", "unit"), Duration::from_millis(300)),
                (key("api", "integration"), Duration::from_millis(4200)),
            ],
        )
        .unwrap();
        let text = std::fs::read_to_string(&path).unwrap();
        assert_eq!(
            text,
            "{\n  \"version\": 1,\n  \"jobs\": {\n    \"shop/api/integration\": {\n      \"millis\": 4200,\n      \"runs\": 1\n    },\n    \"shop/web/unit\": {\n      \"millis\": 300,\n      \"runs\": 1\n    }\n  }\n}"
        );
        let loaded = Durations::load(&path);
        assert_eq!(loaded.get(&key("web", "unit")).map(|e| e.millis), Some(300));
        assert_eq!(loaded.package_total("shop", "api"), Some(4200));
        assert_eq!(loaded.package_total("shop", "missing"), None);
        std::fs::write(&path, "not json").unwrap();
        assert!(Durations::load(&path).is_empty());
        assert!(Durations::load(&dir.path().join("absent.json")).is_empty());
    }

    #[test]
    fn unknown_jobs_are_estimated_at_the_75th_percentile_of_known_ones() {
        assert_eq!(percentile_75(&[]), 0);
        assert_eq!(percentile_75(&[7]), 7);
        assert_eq!(percentile_75(&[40, 10, 30, 20]), 30);
        assert_eq!(percentile_75(&[1, 2, 3, 4, 5]), 4);
        let (root, config, projects) = crate::plan::tests::fixture();
        let run_dir = crate::run_dir::RunDir::create(root.path(), Path::new("runs")).unwrap();
        let opts = crate::plan::tests::options(root.path(), &run_dir, &config, &[]);
        let mut plan = crate::plan::plan(
            &projects,
            &opts,
            &crate::preflight::PreflightPlan::default(),
        )
        .unwrap();
        Durations::default().estimate(&mut plan);
        assert!(plan.jobs().all(|job| job.estimate_ms == 0));
        let mut durations = Durations::default();
        durations.record(&key("api", "unit"), Duration::from_millis(100));
        durations.record(&key("web", "unit"), Duration::from_millis(900));
        durations.estimate(&mut plan);
        let estimates: Vec<_> = plan.jobs().map(|job| job.estimate_ms).collect();
        assert_eq!(estimates, [100, 900, 900]);
    }
}
