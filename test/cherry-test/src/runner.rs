use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

use anyhow::{bail, Context, Result};
use cherry_test_common::adapter::{AdapterRequest, AdapterResponse, ExecutionMode};
use cherry_test_core::{Environment, PackageRuntime};

use crate::adapters;
use crate::config::{Config, ExternalRunner, PackageConfig};
use crate::coverage::{merge_lcov_files, CoveragePaths};
use crate::coverage_ignore::CoverageIgnore;
use crate::discover::ProjectGroup;
use crate::plan::{Job, JobKey};
use crate::preflight::PreflightPlan;
use crate::report::{package_coverage_from_paths, ProjectRunResult};
use crate::run_dir::RunDir;

pub struct RunOptions<'a> {
    pub workspace_root: &'a Path,
    pub run_dir: &'a RunDir,
    pub config: &'a Config,
    pub coverage: bool,
    pub quiet: bool,
    pub test_listing: TestListing,
    pub extra_test_args: &'a [String],
    pub kinds: &'a [String],
    /// The run's base environment: the process snapshot plus the workspace and run variables.
    pub env: Environment,
    pub parallelism: crate::scheduler::Parallelism,
}

/// When the terminal lists individual test results.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TestListing {
    AfterEachPhase,
    AtEndOfRun,
}

pub struct RunOutcome {
    pub results: Vec<ProjectRunResult>,
    pub lcov_files: Vec<PathBuf>,
    pub unit_lcov_files: Vec<PathBuf>,
    pub integration_lcov_files: Vec<PathBuf>,
    pub failed: bool,
    pub timings: Vec<(JobKey, Duration)>,
}

/// Keeps each job's terminal block together when jobs run at once.
static OUTPUT: std::sync::Mutex<()> = std::sync::Mutex::new(());

fn output_lock() -> std::sync::MutexGuard<'static, ()> {
    OUTPUT
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

/// What one finished job contributes to the run's results.
#[derive(Debug, Clone)]
pub struct JobOutcome {
    pub key: JobKey,
    pub success: bool,
    pub lcov: Option<PathBuf>,
    pub elapsed: Duration,
}

pub fn run_projects(
    projects: &[ProjectGroup],
    opts: &RunOptions<'_>,
    preflight: &PreflightPlan,
) -> Result<RunOutcome> {
    let mut plan = crate::plan::plan(projects, opts, preflight)?;
    if opts.parallelism.workers > 1 {
        crate::durations::Durations::load(&crate::durations::Durations::path(
            opts.workspace_root,
            &opts.config.defaults.runs_base,
        ))
        .estimate(&mut plan);
    }
    let finished = crate::scheduler::run(&plan, projects, opts, preflight)?;
    aggregate(opts, finished)
}

/// Runs one planned job: completes its request from the job environment, invokes the adapter and saves the result.
///
/// `setup` carries the error of booting the job's scopes, which fails the job without starting its adapter.
pub fn execute_job(
    job: &Job,
    env: &Environment,
    preflight: &PreflightPlan,
    setup: std::result::Result<(), String>,
    opts: &RunOptions<'_>,
) -> Result<JobOutcome> {
    let started = Instant::now();
    let adapter = job.adapter.as_str();
    let phase = job.key.phase.as_str();
    let package = job.key.package.as_str();
    let mut request = job.request.clone();
    if adapter == "cargo" && phase == "integration" {
        for skip in preflight.skipped.iter().filter(|s| s.package == package) {
            request
                .args
                .extend(["--skip".into(), skip.function.clone()]);
        }
    }
    if adapter == "maven" && phase == "integration" {
        if let Some(excludes) = maven_skip_excludes(preflight, package) {
            std::fs::create_dir_all(&request.output)?;
            let file = request.output.join("cherry-test-skips.txt");
            std::fs::write(&file, excludes)?;
            request
                .args
                .push(format!("-Dfailsafe.excludesFile={}", file.display()));
        }
    }
    let report_dir = job_report_dir(&request);
    std::fs::create_dir_all(&report_dir)
        .with_context(|| format!("create {}", report_dir.display()))?;
    let mut env = env.clone();
    env.insert(
        "CHERRY_TEST_REPORT_DIR".into(),
        report_dir.to_string_lossy().into_owned(),
    );
    let env = &env;
    request.env = crate::run_env::request_env(env);
    if !opts.quiet {
        let _output = output_lock();
        println!("==> {}/{package}/{phase} ({adapter})", job.key.project);
    }
    let setup = setup.and_then(|()| job.container_error.clone().map_or(Ok(()), Err));
    let result = match setup {
        Ok(()) => adapters::invoke(adapter, opts.config.adapters.get(adapter), &request, env)
            .map_err(|error| format!("{error:#}")),
        Err(message) => Err(message),
    };
    let mut response = match result {
        Ok(response) => response,
        Err(message) => AdapterResponse {
            success: false,
            diagnostics: vec![message],
            ..Default::default()
        },
    };
    if discovers_scopes(adapter) && phase == "integration" {
        record_skips(&mut response, preflight, package, request.strict);
    }
    let cov_paths = CoveragePaths::new(opts.run_dir, &job.key.project);
    cov_paths.ensure_dirs()?;
    let mut lcov_file = None;
    if !response.coverage.is_empty() {
        let lcov = cov_paths.lcov_dir.join(format!("{package}-{phase}.lcov"));
        if let Err(error) = normalize_coverage(&request, &response.coverage, &lcov) {
            response.success = false;
            response
                .diagnostics
                .push(format!("coverage import failed: {error:#}"));
        } else {
            lcov_file = Some(lcov);
        }
    }
    let response = adapters::save(&request, response, &opts.run_dir.reports)?;
    if !opts.quiet {
        let _output = output_lock();
        if opts.test_listing == TestListing::AfterEachPhase {
            crate::report::print_phase_results(&response.tests);
        }
        if !response.success {
            eprintln!("  failed: {}", failure_summary(&response));
        }
    }
    Ok(JobOutcome {
        key: job.key.clone(),
        success: response.success,
        lcov: lcov_file,
        elapsed: started.elapsed(),
    })
}

/// The job's own `CHERRY_TEST_REPORT_DIR`: `report/` in its adapter output, writable in hardened containers.
///
/// The scheduler merges it into the run's report directory in plan order once the job has finished.
pub fn job_report_dir(request: &AdapterRequest) -> PathBuf {
    request.output.join("report")
}

/// Folds finished jobs, taken in plan order, into per-package results and coverage lists.
fn aggregate(opts: &RunOptions<'_>, finished: Vec<(usize, JobOutcome)>) -> Result<RunOutcome> {
    let mut finished = finished;
    finished.sort_by_key(|(order, _)| *order);
    let mut statuses = BTreeMap::new();
    let mut coverage = BTreeMap::<(String, String), Vec<PathBuf>>::new();
    let mut unit = Vec::new();
    let mut integration = Vec::new();
    let mut timings = Vec::new();
    for (_, outcome) in finished {
        let key = (outcome.key.project.clone(), outcome.key.package.clone());
        if let Some(lcov) = outcome.lcov {
            coverage.entry(key.clone()).or_default().push(lcov.clone());
            match outcome.key.phase.as_str() {
                "unit" => unit.push(lcov),
                "integration" => integration.push(lcov),
                _ => {}
            }
        }
        *statuses.entry(key).or_insert(true) &= outcome.success;
        timings.push((outcome.key, outcome.elapsed));
    }
    let ignore = CoverageIgnore::load(opts.workspace_root, opts.config);
    let mut lcov_files = Vec::new();
    let mut results = Vec::new();
    for ((project_id, package), tests_passed) in statuses {
        let paths = CoveragePaths::new(opts.run_dir, &project_id);
        if let Some(files) = coverage.get(&(project_id.clone(), package.clone())) {
            let merged = paths.lcov_file(&package);
            merge_lcov_files(files, &merged)?;
            lcov_files.push(merged);
        }
        results.push(ProjectRunResult {
            project_id,
            coverage: package_coverage_from_paths(&paths, &package, &ignore),
            package,
            tests_passed,
        });
    }
    let failed = adapters::process::cancelled()
        || results.iter().any(|r| !r.tests_passed)
        || opts.config.defaults.strict && results.is_empty();
    Ok(RunOutcome {
        results,
        lcov_files,
        unit_lcov_files: unit,
        integration_lcov_files: integration,
        failed,
        timings,
    })
}

fn normalize_coverage(request: &AdapterRequest, files: &[PathBuf], output: &Path) -> Result<()> {
    let mut result = String::new();
    for file in files {
        let text = std::fs::read_to_string(file)?;
        let mut include = true;
        for line in text.lines() {
            if let Some(source) = line.strip_prefix("SF:") {
                let path = adapters::builtins::source_path(request, source);
                if !path.starts_with(&request.root) {
                    include = false;
                    continue;
                }
                let canonical = path
                    .canonicalize()
                    .with_context(|| format!("coverage source {}", path.display()))?;
                include = canonical.starts_with(&request.root);
                if let Ok(relative) = canonical.strip_prefix(&request.root) {
                    result.push_str(&format!("SF:{}\n", relative.display()));
                } else {
                    tracing::debug!(source = %canonical.display(), "excluding coverage outside selected workspace");
                }
            } else if include {
                result.push_str(line);
                result.push('\n');
            }
        }
    }
    if !result.lines().any(|line| line.starts_with("DA:")) {
        bail!("no measured source lines inside selected workspace");
    }
    std::fs::write(output, result)?;
    merge_lcov_files(&[output.to_path_buf()], output)?;
    Ok(())
}

/// Adapters whose integration tests declare their scope in the source: `#[cherry_test]` (Cargo), `@CherryTest` (Maven).
pub(crate) fn discovers_scopes(adapter: &str) -> bool {
    adapter == "cargo" || adapter == "maven"
}

/// Failsafe exclusions for the scoped tests preflight skipped: one `Class#method` per line.
fn maven_skip_excludes(preflight: &PreflightPlan, package: &str) -> Option<String> {
    let lines: Vec<String> = preflight
        .skipped
        .iter()
        .filter(|skip| skip.package == package)
        .filter_map(|skip| skip.function.split_once("::"))
        .map(|(class, method)| format!("{}#{method}\n", class.replace('.', "/")))
        .collect();
    (!lines.is_empty()).then(|| lines.concat())
}

fn record_skips(
    response: &mut AdapterResponse,
    preflight: &PreflightPlan,
    package: &str,
    strict: bool,
) {
    for skip in preflight
        .skipped
        .iter()
        .filter(|skip| skip.package == package)
    {
        response.tests.push(adapters::formats::case(
            skip.function.clone(),
            cherry_test_common::adapter::TestStatus::Skipped,
            Some(skip.reason.clone()),
            0,
        ));
        if strict {
            response.success = false;
            response.diagnostics.push(format!(
                "required test {} skipped: {}",
                skip.function, skip.reason
            ));
        }
    }
}

/// One-line reason for a failed phase: adapter diagnostics first, then the failed test names.
fn failure_summary(response: &AdapterResponse) -> String {
    if !response.diagnostics.is_empty() {
        return response.diagnostics.join("; ");
    }
    let failed: Vec<&str> = response
        .tests
        .iter()
        .filter(|test| test.status == cherry_test_common::adapter::TestStatus::Failed)
        .map(|test| test.name.as_str())
        .collect();
    match failed.as_slice() {
        [] => "test runner exited with an error; see its logs in the run directory".into(),
        [only] => format!("1 test failed: {only}"),
        many => format!("{} tests failed: {}", many.len(), many.join(", ")),
    }
}

pub(crate) fn phases_for(kinds: &[String]) -> Vec<&'static str> {
    let mut phases = vec!["unit", "integration", "functional", "ui", "visual"];
    if kinds.iter().any(|kind| kind == "mutation") {
        phases.push("mutation");
    }
    phases
}

fn host_command_phase(phase: &str) -> bool {
    phase == "mutation"
}

fn default_adapter(phase: &str, runtime: PackageRuntime) -> &'static str {
    match phase {
        "ui" | "visual" => "playwright",
        "functional" | "mutation" => "command",
        _ if runtime == PackageRuntime::Node => "vitest",
        _ => "cargo",
    }
}

pub(crate) fn phase_adapter<'a>(
    phase: &str,
    runner: Option<&'a ExternalRunner>,
    package: &'a PackageConfig,
) -> &'a str {
    if host_command_phase(phase) {
        return runner
            .and_then(|runner| runner.adapter.as_deref())
            .unwrap_or("command");
    }
    runner
        .and_then(|runner| runner.adapter.as_deref())
        .or(package.adapter.as_deref())
        .unwrap_or(default_adapter(phase, package.runtime))
}

pub(crate) fn phase_execution(
    phase: &str,
    configured: Option<ExecutionMode>,
    default_mode: ExecutionMode,
) -> ExecutionMode {
    if host_command_phase(phase) {
        ExecutionMode::Local
    } else {
        configured.unwrap_or(default_mode)
    }
}

pub(crate) fn phase_collects_coverage(phase: &str, requested: bool) -> bool {
    requested && !host_command_phase(phase)
}

#[cfg(test)]
mod tests {
    use super::*;

    mod failure_summary {
        use super::*;
        use cherry_test_common::adapter::{TestResult, TestStatus};

        fn failed_test(name: &str) -> TestResult {
            crate::adapters::formats::case(name.into(), TestStatus::Failed, None, 0)
        }

        #[test]
        fn reports_adapter_diagnostics() {
            let response = AdapterResponse {
                success: false,
                diagnostics: vec!["CMake configuration failed".into()],
                ..Default::default()
            };
            assert_eq!(
                super::super::failure_summary(&response),
                "CMake configuration failed"
            );
        }

        #[test]
        fn names_failed_tests_when_there_are_no_diagnostics() {
            let response = AdapterResponse {
                success: false,
                tests: vec![failed_test("catalog::reports_a_price_mismatch")],
                ..Default::default()
            };
            assert_eq!(
                super::super::failure_summary(&response),
                "1 test failed: catalog::reports_a_price_mismatch"
            );
        }

        #[test]
        fn says_the_runner_failed_when_nothing_explains_it() {
            let response = AdapterResponse {
                success: false,
                ..Default::default()
            };
            assert_eq!(
                super::super::failure_summary(&response),
                "test runner exited with an error; see its logs in the run directory"
            );
        }
    }

    #[test]
    fn mutation_stays_out_of_a_default_run() {
        assert!(!phases_for(&[]).contains(&"mutation"));
        assert!(!phases_for(&["unit".into()]).contains(&"mutation"));
        assert!(phases_for(&["mutation".into()]).contains(&"mutation"));
        assert!(phases_for(&["unit".into(), "mutation".into()]).contains(&"mutation"));
    }

    #[test]
    fn mutation_runs_a_local_command_and_skips_coverage() {
        let package = PackageConfig {
            adapter: Some("maven".into()),
            execution: Some(ExecutionMode::Container),
            ..Default::default()
        };
        assert_eq!(phase_adapter("mutation", None, &package), "command");
        assert_eq!(phase_adapter("unit", None, &package), "maven");
        let runner = ExternalRunner {
            adapter: Some("command".into()),
            command: "mvn".into(),
            ..Default::default()
        };
        assert_eq!(
            phase_adapter("mutation", Some(&runner), &package),
            "command"
        );
        let node = PackageConfig {
            runtime: PackageRuntime::Node,
            ..Default::default()
        };
        assert_eq!(phase_adapter("unit", None, &node), "vitest");
        assert_eq!(phase_adapter("mutation", None, &node), "command");
        assert_eq!(
            phase_execution("mutation", package.execution, ExecutionMode::Container),
            ExecutionMode::Local
        );
        assert_eq!(
            phase_execution("unit", package.execution, ExecutionMode::Local),
            ExecutionMode::Container
        );
        assert!(!phase_collects_coverage("mutation", true));
        assert!(phase_collects_coverage("unit", true));
    }

    #[test]
    fn strict_preflight_skips_fail_even_when_other_tests_pass() {
        let mut response = AdapterResponse {
            success: true,
            ..Default::default()
        };
        response.tests.push(adapters::formats::case(
            "unscoped".into(),
            cherry_test_common::adapter::TestStatus::Passed,
            None,
            0,
        ));
        let mut preflight = PreflightPlan::default();
        preflight.skipped.push(crate::preflight::SkippedTest {
            package: "api".into(),
            function: "demo_project_http_suite_health_test".into(),
            scope: "demo".into(),
            project: "demo".into(),
            suite: "http".into(),
            test: "health".into(),
            reason: "no docker".into(),
        });
        record_skips(&mut response, &preflight, "api", true);
        assert!(!response.success);
        assert!(discovers_scopes("maven"));
        assert!(!discovers_scopes("vitest"));
        assert!(maven_skip_excludes(&preflight, "api").is_none());
        preflight.skipped.push(crate::preflight::SkippedTest {
            package: "api".into(),
            function: "com.example.WalletIT::demo_project_wallet_suite_balance_test".into(),
            scope: "demo".into(),
            project: "demo".into(),
            suite: "wallet".into(),
            test: "balance".into(),
            reason: "no docker".into(),
        });
        assert_eq!(
            maven_skip_excludes(&preflight, "api").as_deref(),
            Some("com/example/WalletIT#demo_project_wallet_suite_balance_test\n")
        );
        assert_eq!(response.tests.len(), 2);
        assert_eq!(
            response.tests[1].status,
            cherry_test_common::adapter::TestStatus::Skipped
        );
        assert!(response.diagnostics[0].contains("no docker"));
    }
}
