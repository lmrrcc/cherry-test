use std::collections::{BTreeMap, BTreeSet};
use std::path::{Path, PathBuf};

use anyhow::{bail, Context, Result};
use cherry_test_common::adapter::{
    AdapterRequest, AdapterResponse, ExecutionMode, PROTOCOL_VERSION,
};
use cherry_test_core::PackageRuntime;

use crate::adapters;
use crate::config::{Config, ExternalRunner, PackageConfig, TestKind};
use crate::coverage::{merge_lcov_files, CoveragePaths};
use crate::coverage_ignore::CoverageIgnore;
use crate::discover::ProjectGroup;
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
    pub ran_integration: bool,
}

pub fn run_projects(
    projects: &[ProjectGroup],
    opts: &RunOptions<'_>,
    preflight: &PreflightPlan,
) -> Result<RunOutcome> {
    let mut preflight = preflight.clone();
    let mut statuses = BTreeMap::new();
    let mut coverage = BTreeMap::<(String, String), Vec<PathBuf>>::new();
    let mut unit = Vec::new();
    let mut integration = Vec::new();
    let mut ran_integration = false;
    for phase in phases_for(opts.kinds) {
        if !opts.kinds.is_empty() && !opts.kinds.iter().any(|kind| kind == phase) {
            continue;
        }
        std::env::set_var("CHERRY_TEST_PHASE", phase);
        let mut booted = BTreeSet::new();
        let mut auth_ready = false;
        for project in projects {
            apply_env_file(opts.workspace_root, &project.id);
            for member in &project.members {
                if adapters::process::cancelled() {
                    break;
                }
                let package = &opts.config.projects[&project.id].packages[&member.name];
                let configured = opts.config.package_kinds(&member.name, &project.id);
                for kind in &configured {
                    if TestKind::parse(kind).is_none() {
                        bail!("unknown test kind {kind}");
                    }
                }
                if !configured.iter().any(|kind| kind == phase) {
                    continue;
                }
                let runner = match phase {
                    "unit" => package.unit.as_ref(),
                    "integration" => package.integration.as_ref(),
                    "functional" => opts.config.functional_runner(&member.name, &project.id),
                    "ui" => opts.config.ui_runner(&member.name, &project.id),
                    "visual" => package.visual.as_ref(),
                    "mutation" => package.mutation.as_ref(),
                    _ => None,
                };
                let adapter = phase_adapter(phase, runner, package);
                let cwd = runner
                    .and_then(|r| r.cwd.as_ref())
                    .map(|p| opts.workspace_root.join(p))
                    .unwrap_or_else(|| {
                        opts.workspace_root
                            .join(&member.manifest_path)
                            .parent()
                            .unwrap_or(opts.workspace_root)
                            .to_path_buf()
                    });
                let output = opts
                    .run_dir
                    .reports
                    .join("adapters")
                    .join(&project.id)
                    .join(&member.name)
                    .join(phase);
                let mut options = package.options.clone();
                if let Some(runner) = runner {
                    options.extend(runner.options.clone());
                    if !runner.command.is_empty() {
                        options.insert("command".into(), runner.command.clone().into());
                    }
                }
                let mut args = runner.map(|r| r.args.clone()).unwrap_or_default();
                if adapter == "cargo" {
                    args.extend_from_slice(opts.extra_test_args);
                }
                let mut request = AdapterRequest {
                    protocol: PROTOCOL_VERSION,
                    project: project.id.clone(),
                    package: member.name.clone(),
                    phase: phase.into(),
                    root: opts.workspace_root.to_path_buf(),
                    manifest: opts.workspace_root.join(&member.manifest_path),
                    cwd,
                    output,
                    execution: phase_execution(
                        phase,
                        package.execution,
                        opts.config.defaults.execution,
                    ),
                    coverage: phase_collects_coverage(phase, opts.coverage),
                    strict: opts.config.defaults.strict,
                    args,
                    env: BTreeMap::new(),
                    options,
                };
                let mut scopes = Vec::new();
                if phase == "integration" && adapter == "cargo" {
                    scopes.extend(
                        preflight
                            .discovered
                            .iter()
                            .filter(|t| {
                                t.package == member.name
                                    && preflight.scopes_to_boot.contains(&t.effective_scope)
                            })
                            .map(|t| t.effective_scope.clone()),
                    );
                }
                if let Some(scope) = runner.and_then(|r| r.scope.as_ref()) {
                    if cherry_test_core::needs_docker(scope) {
                        scopes.push(scope.clone());
                    }
                }
                let setup = (|| -> Result<()> {
                    for scope in scopes {
                        ran_integration = true;
                        if !booted.contains(&scope) {
                            cherry_test_core::boot_scope(&scope)?;
                            booted.insert(scope);
                        }
                    }
                    if phase == "integration" && !auth_ready && !booted.is_empty() {
                        crate::auth_preflight::run_auth_preflight(
                            opts.workspace_root,
                            opts.config,
                            &mut preflight,
                            opts.quiet,
                        )?;
                        auth_ready = true;
                    }
                    Ok(())
                })();
                if adapter == "cargo" && phase == "integration" {
                    for skip in preflight
                        .skipped
                        .iter()
                        .filter(|s| s.package == member.name)
                    {
                        request
                            .args
                            .extend(["--skip".into(), skip.function.clone()]);
                    }
                }
                request.env = std::env::vars()
                    .filter(|(k, _)| {
                        k.starts_with("CHERRY_TEST_")
                            && !k.contains("AUTH")
                            && !k.contains("TOKEN")
                            && !k.contains("SECRET")
                    })
                    .collect();
                if !opts.quiet {
                    println!("==> {}/{}/{phase} ({adapter})", project.id, member.name);
                }
                let result = setup.and_then(|_| {
                    adapters::invoke(adapter, opts.config.adapters.get(adapter), &request)
                });
                let mut response = match result {
                    Ok(response) => response,
                    Err(error) => AdapterResponse {
                        success: false,
                        diagnostics: vec![format!("{error:#}")],
                        ..Default::default()
                    },
                };
                if adapter == "cargo" && phase == "integration" {
                    record_skips(&mut response, &preflight, &member.name, request.strict);
                }
                let key = (project.id.clone(), member.name.clone());
                let cov_paths = CoveragePaths::new(opts.run_dir, &project.id);
                cov_paths.ensure_dirs()?;
                if !response.coverage.is_empty() {
                    let lcov = cov_paths
                        .lcov_dir
                        .join(format!("{}-{phase}.lcov", member.name));
                    if let Err(error) = normalize_coverage(&request, &response.coverage, &lcov) {
                        response.success = false;
                        response
                            .diagnostics
                            .push(format!("coverage import failed: {error:#}"));
                    } else {
                        coverage.entry(key.clone()).or_default().push(lcov.clone());
                        match phase {
                            "unit" => unit.push(lcov),
                            "integration" => integration.push(lcov),
                            _ => {}
                        }
                    }
                }
                let response = adapters::save(&request, response, &opts.run_dir.reports)?;
                *statuses.entry(key).or_insert(true) &= response.success;
                if !opts.quiet {
                    if opts.test_listing == TestListing::AfterEachPhase {
                        crate::report::print_phase_results(&response.tests);
                    }
                    if !response.success {
                        eprintln!("  failed: {}", failure_summary(&response));
                    }
                }
            }
        }
        cherry_test_core::teardown_scopes()?;
        if adapters::process::cancelled() {
            break;
        }
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
        ran_integration,
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

fn phases_for(kinds: &[String]) -> Vec<&'static str> {
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

fn phase_adapter<'a>(
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

fn phase_execution(
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

fn phase_collects_coverage(phase: &str, requested: bool) -> bool {
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
        assert_eq!(response.tests.len(), 2);
        assert_eq!(
            response.tests[1].status,
            cherry_test_common::adapter::TestStatus::Skipped
        );
        assert!(response.diagnostics[0].contains("no docker"));
    }
}

fn apply_env_file(workspace_root: &Path, project_id: &str) {
    let local = workspace_root.join(".env.test");
    let nested = workspace_root.join(project_id).join(".env.test");
    let env_path = if local.is_file() {
        local
    } else if nested.is_file() {
        nested
    } else {
        return;
    };
    if let Ok(content) = std::fs::read_to_string(&env_path) {
        for line in content.lines() {
            let line = line.trim();
            if line.is_empty() || line.starts_with('#') {
                continue;
            }
            if let Some((key, value)) = line.split_once('=') {
                std::env::set_var(key.trim(), value.trim());
            }
        }
    }
}
