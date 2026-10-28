//! Isolation on failure: after the main schedule each failed test runs alone, several times, each
//! time as a fresh job, and is classified `REAL`, `INTERFERENCE`, `FLAKY` or `INCONCLUSIVE`.
//!
//! Reruns never change a test's status or the run's exit code; they only explain the failure.

use std::path::Path;

use anyhow::{Context, Result};
use cherry_test_common::adapter::{
    AdapterConfig, AdapterRequest, AdapterResponse, Isolation, IsolationClass, TestResult,
    TestStatus,
};
use cherry_test_core::{teardown_scopes_in, IsolateConfig};
use serde::{Deserialize, Serialize};

use crate::adapters;
use crate::discover::ProjectGroup;
use crate::plan::{Job, Plan};
use crate::preflight::PreflightPlan;
use crate::runner::RunOptions;
use crate::scheduler::{setup_job, EnvLayers, RunState};

/// How many times each failed test reruns and how many failed tests are isolated.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct IsolationSettings {
    pub runs: u32,
    pub max_tests: u32,
}

impl IsolationSettings {
    /// `--isolate` or `--no-isolate` over `defaults.isolate.on_failure`; `None` when isolation is off.
    pub fn resolve(
        isolate: bool,
        no_isolate: bool,
        config: Option<&IsolateConfig>,
    ) -> Option<Self> {
        let config = config.cloned().unwrap_or_default();
        let enabled = if isolate {
            true
        } else if no_isolate {
            false
        } else {
            config.on_failure
        };
        enabled.then_some(Self {
            runs: config.runs,
            max_tests: config.max_tests,
        })
    }
}

/// How an adapter reruns one test.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Narrowing {
    /// A built-in adapter: the phase arguments that select the test.
    Args(Vec<String>),
    /// An adapter registered with `isolate: true`: the request's `select`.
    Select(Vec<String>),
    /// The adapter cannot run the test alone, and why.
    Unsupported(String),
}

/// What one rerun of a test showed.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Rerun {
    Passed,
    Failed,
    Infrastructure(String),
}

/// Classifies the reruns of one test: the class, how many passed, and the first infrastructure error.
pub fn classify(reruns: &[Rerun]) -> (IsolationClass, u32, Option<String>) {
    let passed = reruns
        .iter()
        .filter(|rerun| **rerun == Rerun::Passed)
        .count() as u32;
    let infrastructure = reruns.iter().find_map(|rerun| match rerun {
        Rerun::Infrastructure(message) => Some(message.clone()),
        _ => None,
    });
    let class = if infrastructure.is_some() || reruns.is_empty() {
        IsolationClass::Inconclusive
    } else if passed == 0 {
        IsolationClass::Real
    } else if passed as usize == reruns.len() {
        IsolationClass::Interference
    } else {
        IsolationClass::Flaky
    };
    (class, passed, infrastructure)
}

/// The arguments or selection that run `test` alone with `adapter`.
///
/// Cargo adds `<name> --exact`. Maven replaces `-Dtest` (unit) or `-Dit.test` (integration) with
/// `Class#method`, or with the whole class when it orders its methods or shares one instance.
/// Vitest adds the test file and an anchored `-t` pattern. An adapter registered in `adapters:`
/// must declare `isolate: true` to receive `select`; every other adapter cannot narrow.
pub fn narrow(
    adapter: &str,
    registered: Option<&AdapterConfig>,
    request: &AdapterRequest,
    test: &TestResult,
) -> Narrowing {
    if let Some(config) = registered {
        return if config.isolate {
            Narrowing::Select(vec![test.name.clone()])
        } else {
            Narrowing::Unsupported(format!(
                "adapter `{adapter}` does not declare `isolate: true`"
            ))
        };
    }
    match adapter {
        "cargo" => {
            let mut args = request.args.clone();
            args.extend([test.name.clone(), "--exact".to_string()]);
            Narrowing::Args(args)
        }
        "maven" => maven(request, &test.name),
        "vitest" => vitest(request, test),
        other => Narrowing::Unsupported(format!("adapter `{other}` cannot run a single test")),
    }
}

fn maven(request: &AdapterRequest, name: &str) -> Narrowing {
    let Some((class, method)) = name.split_once("::") else {
        return Narrowing::Unsupported(format!("`{name}` does not name a test class and method"));
    };
    let method = method.split(['(', '[']).next().unwrap_or(method);
    let selector = if runs_as_a_class(&request.cwd, class) {
        class.to_string()
    } else {
        format!("{class}#{method}")
    };
    let property = if request.phase == "integration" {
        "-Dit.test="
    } else {
        "-Dtest="
    };
    let mut args: Vec<String> = request
        .args
        .iter()
        .filter(|arg| !arg.starts_with(property))
        .cloned()
        .collect();
    args.push(format!("{property}{selector}"));
    args.push("-Dsurefire.failIfNoSpecifiedTests=false".to_string());
    if request.phase == "integration" {
        args.push("-Dit.failIfNoSpecifiedTests=false".to_string());
    }
    Narrowing::Args(args)
}

/// Whether a JUnit class orders its methods (`@TestMethodOrder`) or shares one instance
/// (`@TestInstance(PER_CLASS)`): its methods may depend on each other, so the whole class reruns.
pub fn runs_as_a_class(module: &Path, class: &str) -> bool {
    let outer = class.split('$').next().unwrap_or(class).replace('.', "/");
    for (root, extension) in [("src/test/java", "java"), ("src/test/kotlin", "kt")] {
        if let Ok(source) =
            std::fs::read_to_string(module.join(root).join(format!("{outer}.{extension}")))
        {
            return source.contains("@TestMethodOrder")
                || source.contains("@TestInstance") && source.contains("PER_CLASS");
        }
    }
    false
}

fn vitest(request: &AdapterRequest, test: &TestResult) -> Narrowing {
    let Some(source) = &test.source else {
        return Narrowing::Unsupported(format!(
            "Vitest did not report the file of `{}`",
            test.name
        ));
    };
    let file = adapters::builtins::source_path(request, &source.to_string_lossy());
    let file = file
        .strip_prefix(&request.cwd)
        .map(|relative| relative.to_string_lossy().into_owned())
        .unwrap_or_else(|_| file.to_string_lossy().into_owned());
    let mut args = request.args.clone();
    if crate::node_runtime::vitest_run_args(&args).is_empty() {
        args.push("run".to_string());
    }
    args.extend([
        file,
        "-t".to_string(),
        format!("^{}$", regex_escape(&test.name)),
    ]);
    Narrowing::Args(args)
}

/// Escapes the characters a JavaScript regular expression treats specially.
pub fn regex_escape(text: &str) -> String {
    let mut escaped = String::with_capacity(text.len());
    for ch in text.chars() {
        if "\\^$.|?*+()[]{}/".contains(ch) {
            escaped.push('\\');
        }
        escaped.push(ch);
    }
    escaped
}

/// One classified test in `reports/isolation.json`.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct IsolatedTest {
    pub project: String,
    pub package: String,
    pub phase: String,
    pub test: String,
    pub class: IsolationClass,
    pub runs: u32,
    pub passed: u32,
    pub logs: Vec<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub reason: Option<String>,
}

/// `reports/isolation.json`: the settings, how many tests failed, and the classified ones in plan order.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct IsolationReport {
    pub runs: u32,
    pub max_tests: u32,
    pub failed: usize,
    pub tests: Vec<IsolatedTest>,
}

impl IsolationReport {
    /// The terminal lines: a header, one line per classified test, and how many were left out.
    pub fn lines(&self) -> Vec<String> {
        let mut lines = vec![header(self.failed, self.tests.len(), self.runs)];
        lines.extend(self.tests.iter().map(line));
        lines.extend(self.leftover());
        lines
    }

    fn leftover(&self) -> Option<String> {
        let left = self.failed.saturating_sub(self.tests.len());
        (left > 0).then(|| {
            format!(
                "  {left} more failed {} not isolated (max_tests {})",
                plural(left, "test", "tests"),
                self.max_tests
            )
        })
    }

    /// How many tests each class holds, in class order, leaving out empty classes.
    pub fn counts(&self) -> Vec<(IsolationClass, usize)> {
        [
            IsolationClass::Real,
            IsolationClass::Interference,
            IsolationClass::Flaky,
            IsolationClass::Inconclusive,
        ]
        .into_iter()
        .map(|class| {
            (
                class,
                self.tests.iter().filter(|test| test.class == class).count(),
            )
        })
        .filter(|(_, count)| *count > 0)
        .collect()
    }
}

/// `==> isolate N failed tests: each alone, R runs`, or the line for a run without failures.
fn header(failed: usize, selected: usize, runs: u32) -> String {
    if failed == 0 {
        return "==> isolate: no failed tests".to_string();
    }
    format!(
        "==> isolate {selected} failed {}: each alone, {runs} {}",
        plural(selected, "test", "tests"),
        plural(runs as usize, "run", "runs")
    )
}

/// One classified test: class, job, test name, and how many reruns passed or why none could run.
fn line(test: &IsolatedTest) -> String {
    let detail = match &test.reason {
        Some(reason) if test.runs == 0 => reason.clone(),
        Some(reason) => format!("{}/{} passed alone; {reason}", test.passed, test.runs),
        None => format!("{}/{} passed alone", test.passed, test.runs),
    };
    format!(
        "  {:<12}  {}/{}/{}  {}  {detail}",
        test.class.as_str(),
        test.project,
        test.package,
        test.phase,
        test.test
    )
}

fn plural<'a>(count: usize, one: &'a str, many: &'a str) -> &'a str {
    if count == 1 {
        one
    } else {
        many
    }
}

/// Reads `reports/isolation.json`, or `None` when the run did not isolate failures.
pub fn read_report(reports: &Path) -> Result<Option<IsolationReport>> {
    let path = reports.join("isolation.json");
    if !path.is_file() {
        return Ok(None);
    }
    let text = std::fs::read(&path).with_context(|| format!("read {}", path.display()))?;
    Ok(Some(
        serde_json::from_slice(&text).with_context(|| format!("parse {}", path.display()))?,
    ))
}

/// The report class of an isolation badge: the colour of a failure, a skip or plain text.
pub fn badge_class(class: IsolationClass) -> &'static str {
    match class {
        IsolationClass::Real => "isolation fail",
        IsolationClass::Flaky => "isolation skip",
        IsolationClass::Interference => "isolation neutral",
        IsolationClass::Inconclusive => "isolation muted",
    }
}

/// Where a job's `result.json` lives in the run's reports.
fn result_path(opts: &RunOptions<'_>, job: &Job) -> std::path::PathBuf {
    opts.run_dir
        .reports
        .join("adapters")
        .join(&job.key.project)
        .join(&job.key.package)
        .join(&job.key.phase)
        .join("result.json")
}

fn read_result(path: &Path) -> Result<AdapterResponse> {
    let document: serde_json::Value = serde_json::from_slice(
        &std::fs::read(path).with_context(|| format!("read {}", path.display()))?,
    )?;
    Ok(serde_json::from_value(document["result"].clone())?)
}

/// The entry `save` adds when an adapter failed without naming a failed test; it is not a test.
fn is_runner_entry(test: &TestResult, response: &AdapterResponse) -> bool {
    test.name == "runner"
        && !response.diagnostics.is_empty()
        && test.message.as_deref() == Some(response.diagnostics.join("\n").as_str())
}

/// Reruns the failed tests of a finished schedule and records their classes; does nothing when
/// isolation is off or the run was cancelled.
pub fn run(
    plan: &Plan,
    projects: &[ProjectGroup],
    opts: &RunOptions<'_>,
    preflight: &PreflightPlan,
) -> Result<Option<IsolationReport>> {
    let Some(settings) = opts.isolate else {
        return Ok(None);
    };
    if adapters::process::cancelled() {
        return Ok(None);
    }
    let mut failures: Vec<(&Job, TestResult)> = Vec::new();
    for job in plan.jobs() {
        let path = result_path(opts, job);
        if !path.is_file() {
            continue;
        }
        let response = read_result(&path)?;
        for test in &response.tests {
            if test.status == TestStatus::Failed && !is_runner_entry(test, &response) {
                failures.push((job, test.clone()));
            }
        }
    }
    let layers = EnvLayers::load(opts, projects);
    let mut report = IsolationReport {
        runs: settings.runs,
        max_tests: settings.max_tests,
        failed: failures.len(),
        tests: Vec::new(),
    };
    let selected = failures.len().min(settings.max_tests as usize);
    if !opts.quiet {
        println!("{}", header(failures.len(), selected, settings.runs));
    }
    for (index, (job, test)) in failures.iter().take(selected).enumerate() {
        if adapters::process::cancelled() {
            break;
        }
        let narrowing = narrow(
            &job.adapter,
            opts.config.adapters.get(&job.adapter),
            &job.request,
            test,
        );
        let isolation = match narrowing {
            Narrowing::Unsupported(reason) => Isolation {
                class: IsolationClass::Inconclusive,
                runs: 0,
                passed: 0,
                logs: Vec::new(),
                reason: Some(reason),
            },
            narrowing => {
                let mut reruns = Vec::new();
                let mut logs = Vec::new();
                for attempt in 1..=settings.runs {
                    let relative = format!(
                        "isolation/{}/{}/{}/{}/{attempt}",
                        job.key.project,
                        job.key.package,
                        job.key.phase,
                        index + 1
                    );
                    let rerun = crate::plan::rerun_job(
                        job,
                        &narrowing,
                        opts.run_dir.reports.join(&relative),
                        &format!("isolate-{}-{attempt}", index + 1),
                        opts,
                    );
                    reruns.push(rerun_once(&rerun, &test.name, &layers, opts, preflight));
                    logs.push(relative);
                }
                let (class, passed, reason) = classify(&reruns);
                Isolation {
                    class,
                    runs: settings.runs,
                    passed,
                    logs,
                    reason,
                }
            }
        };
        let entry = IsolatedTest {
            project: job.key.project.clone(),
            package: job.key.package.clone(),
            phase: job.key.phase.clone(),
            test: test.name.clone(),
            class: isolation.class,
            runs: isolation.runs,
            passed: isolation.passed,
            logs: isolation.logs.clone(),
            reason: isolation.reason.clone(),
        };
        if !opts.quiet {
            println!("{}", line(&entry));
        }
        report.tests.push(entry);
    }
    if !opts.quiet {
        if let Some(leftover) = report.leftover() {
            println!("{leftover}");
        }
    }
    record(plan, opts, &report)?;
    Ok(Some(report))
}

/// Runs one rerun job like a stage of its own: boots its scopes, invokes it, tears them down,
/// and reads the test's status from its response.
fn rerun_once(
    job: &Job,
    test: &str,
    layers: &EnvLayers<'_>,
    opts: &RunOptions<'_>,
    preflight: &PreflightPlan,
) -> Rerun {
    let mut state = RunState::new(preflight.clone());
    state.start_phase();
    let setup = setup_job(job, &mut state, layers, opts);
    let env = layers.job_env(&state, &job.key.phase, &job.key.project);
    let invocation = crate::runner::invoke_job(job, &env, &state.preflight, setup, opts);
    let teardown_env = layers.phase_env(&state, &job.key.phase);
    let teardown = teardown_scopes_in(&mut state.scopes, &teardown_env);
    let invocation = match invocation {
        Ok(invocation) => invocation,
        Err(error) => return Rerun::Infrastructure(format!("{error:#}")),
    };
    let written = adapters::write_result(
        &invocation.request.output.join("result.json"),
        &invocation.request,
        &invocation.response,
    );
    if let Some(message) = invocation.infrastructure {
        return Rerun::Infrastructure(message);
    }
    if let Err(error) = teardown {
        return Rerun::Infrastructure(format!("scope teardown failed: {error:#}"));
    }
    if let Err(error) = written {
        return Rerun::Infrastructure(format!("{error:#}"));
    }
    match invocation
        .response
        .tests
        .iter()
        .find(|result| result.name == test)
        .map(|result| &result.status)
    {
        Some(TestStatus::Passed) => Rerun::Passed,
        Some(TestStatus::Failed) => Rerun::Failed,
        Some(TestStatus::Skipped) => {
            Rerun::Infrastructure(format!("`{test}` was skipped when run alone"))
        }
        None => Rerun::Infrastructure(format!("`{test}` did not run alone")),
    }
}

/// Adds each classification to its test in the job's `result.json` and writes `reports/isolation.json`.
fn record(plan: &Plan, opts: &RunOptions<'_>, report: &IsolationReport) -> Result<()> {
    for job in plan.jobs() {
        let entries: Vec<&IsolatedTest> = report
            .tests
            .iter()
            .filter(|entry| {
                entry.project == job.key.project
                    && entry.package == job.key.package
                    && entry.phase == job.key.phase
            })
            .collect();
        if entries.is_empty() {
            continue;
        }
        let path = result_path(opts, job);
        let mut response = read_result(&path)?;
        for test in &mut response.tests {
            if let Some(entry) = entries.iter().find(|entry| entry.test == test.name) {
                test.isolation = Some(Isolation {
                    class: entry.class,
                    runs: entry.runs,
                    passed: entry.passed,
                    logs: entry.logs.clone(),
                    reason: entry.reason.clone(),
                });
            }
        }
        adapters::write_result(&path, &job.request, &response)?;
    }
    std::fs::create_dir_all(&opts.run_dir.reports)?;
    std::fs::write(
        opts.run_dir.reports.join("isolation.json"),
        serde_json::to_vec_pretty(report)?,
    )?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use cherry_test_common::adapter::ExecutionMode;
    use std::collections::BTreeMap;
    use std::path::PathBuf;

    fn request(root: &Path, phase: &str, args: &[&str]) -> AdapterRequest {
        AdapterRequest {
            protocol: 1,
            project: "shop".into(),
            package: "prices".into(),
            phase: phase.into(),
            root: root.into(),
            manifest: root.join("prices/pom.xml"),
            cwd: root.join("prices"),
            output: root.join("out"),
            execution: ExecutionMode::Local,
            coverage: true,
            strict: true,
            args: args.iter().map(|arg| arg.to_string()).collect(),
            env: BTreeMap::new(),
            options: BTreeMap::new(),
            container: None,
            select: None,
        }
    }

    fn failed(name: &str) -> TestResult {
        adapters::formats::case(name.into(), TestStatus::Failed, None, 0)
    }

    fn args(narrowing: Narrowing) -> Vec<String> {
        match narrowing {
            Narrowing::Args(args) => args,
            other => panic!("expected arguments, got {other:?}"),
        }
    }

    #[test]
    fn classifies_reruns_as_real_interference_flaky_or_inconclusive() {
        use Rerun::*;
        assert_eq!(
            classify(&[Failed, Failed, Failed]),
            (IsolationClass::Real, 0, None)
        );
        assert_eq!(
            classify(&[Passed, Passed, Passed]),
            (IsolationClass::Interference, 3, None)
        );
        assert_eq!(
            classify(&[Passed, Failed, Passed]),
            (IsolationClass::Flaky, 2, None)
        );
        assert_eq!(
            classify(&[Passed, Infrastructure("boot failed".into()), Failed]),
            (IsolationClass::Inconclusive, 1, Some("boot failed".into()))
        );
        assert_eq!(classify(&[]), (IsolationClass::Inconclusive, 0, None));
        assert_eq!(classify(&[Passed]), (IsolationClass::Interference, 1, None));
    }

    #[test]
    fn narrows_cargo_to_the_exact_test() {
        let root = Path::new("/work");
        let request = request(root, "unit", &["--nocapture"]);
        assert_eq!(
            args(narrow(
                "cargo",
                None,
                &request,
                &failed("pricing::tests::rounds")
            )),
            ["--nocapture", "pricing::tests::rounds", "--exact"]
        );
    }

    #[test]
    fn narrows_maven_to_the_method_or_the_ordered_class() {
        let temp = tempfile::tempdir().unwrap();
        let root = temp.path();
        let tests = root.join("prices/src/test/java/com/shop");
        std::fs::create_dir_all(&tests).unwrap();
        std::fs::write(tests.join("PriceTest.java"), "class PriceTest {}\n").unwrap();
        std::fs::write(
            tests.join("CheckoutIT.java"),
            "@TestMethodOrder(MethodOrderer.OrderAnnotation.class)\nclass CheckoutIT {}\n",
        )
        .unwrap();
        std::fs::write(
            tests.join("CartTest.java"),
            "@TestInstance(TestInstance.Lifecycle.PER_CLASS)\nclass CartTest {}\n",
        )
        .unwrap();
        let unit = request(root, "unit", &["-Dtest=Price*", "-q"]);
        assert_eq!(
            args(narrow(
                "maven",
                None,
                &unit,
                &failed("com.shop.PriceTest::rounds_half_even(int)[2]")
            )),
            [
                "-q",
                "-Dtest=com.shop.PriceTest#rounds_half_even",
                "-Dsurefire.failIfNoSpecifiedTests=false"
            ]
        );
        assert_eq!(
            args(narrow(
                "maven",
                None,
                &unit,
                &failed("com.shop.CartTest$Totals::adds")
            )),
            [
                "-q",
                "-Dtest=com.shop.CartTest$Totals",
                "-Dsurefire.failIfNoSpecifiedTests=false"
            ]
        );
        let integration = request(root, "integration", &["-Dit.test=CheckoutIT", "-Dtest=x"]);
        assert_eq!(
            args(narrow(
                "maven",
                None,
                &integration,
                &failed("com.shop.CheckoutIT::pays")
            )),
            [
                "-Dtest=x",
                "-Dit.test=com.shop.CheckoutIT",
                "-Dsurefire.failIfNoSpecifiedTests=false",
                "-Dit.failIfNoSpecifiedTests=false"
            ]
        );
        assert!(runs_as_a_class(&root.join("prices"), "com.shop.CheckoutIT"));
        assert!(!runs_as_a_class(&root.join("prices"), "com.shop.Missing"));
        assert_eq!(
            narrow("maven", None, &unit, &failed("suite setup")),
            Narrowing::Unsupported("`suite setup` does not name a test class and method".into())
        );
    }

    #[test]
    fn narrows_vitest_to_the_file_and_anchored_name() {
        let root = Path::new("/work");
        let mut request = request(root, "unit", &["exec", "vitest", "run", "--coverage"]);
        request.cwd = root.join("web");
        let mut test = failed("cart > adds (2) items");
        test.source = Some(PathBuf::from("/work/web/src/cart.test.ts"));
        assert_eq!(
            args(narrow("vitest", None, &request, &test)),
            [
                "exec",
                "vitest",
                "run",
                "--coverage",
                "src/cart.test.ts",
                "-t",
                "^cart > adds \\(2\\) items$"
            ]
        );
        request.args.clear();
        assert_eq!(
            args(narrow("vitest", None, &request, &test))[..2],
            ["run", "src/cart.test.ts"]
        );
        test.source = None;
        assert_eq!(
            narrow("vitest", None, &request, &test),
            Narrowing::Unsupported(
                "Vitest did not report the file of `cart > adds (2) items`".into()
            )
        );
        assert_eq!(regex_escape("a.b*c[d]/e"), "a\\.b\\*c\\[d\\]\\/e");
    }

    #[test]
    fn registered_adapters_opt_in_with_select_or_stay_inconclusive() {
        let root = Path::new("/work");
        let request = request(root, "functional", &[]);
        let mut config = AdapterConfig {
            command: "shop-adapter".into(),
            args: Vec::new(),
            timeout_secs: 60,
            isolate: false,
        };
        assert_eq!(
            narrow("shop", Some(&config), &request, &failed("checkout")),
            Narrowing::Unsupported("adapter `shop` does not declare `isolate: true`".into())
        );
        config.isolate = true;
        assert_eq!(
            narrow("shop", Some(&config), &request, &failed("checkout")),
            Narrowing::Select(vec!["checkout".into()])
        );
        for adapter in ["gradle", "cmake", "playwright", "command"] {
            assert_eq!(
                narrow(adapter, None, &request, &failed("x")),
                Narrowing::Unsupported(format!("adapter `{adapter}` cannot run a single test"))
            );
        }
    }

    #[test]
    fn resolves_isolation_from_flags_and_config() {
        let on = IsolateConfig {
            on_failure: true,
            runs: 5,
            max_tests: 2,
        };
        assert_eq!(IsolationSettings::resolve(false, false, None), None);
        assert_eq!(
            IsolationSettings::resolve(true, false, None),
            Some(IsolationSettings {
                runs: 3,
                max_tests: 10
            })
        );
        assert_eq!(
            IsolationSettings::resolve(false, false, Some(&on)),
            Some(IsolationSettings {
                runs: 5,
                max_tests: 2
            })
        );
        assert_eq!(IsolationSettings::resolve(false, true, Some(&on)), None);
    }

    fn entry(test: &str, class: IsolationClass, passed: u32, reason: Option<&str>) -> IsolatedTest {
        IsolatedTest {
            project: "shop".into(),
            package: "prices".into(),
            phase: "unit".into(),
            test: test.into(),
            class,
            runs: if reason.is_some() { 0 } else { 3 },
            passed,
            logs: if reason.is_some() {
                Vec::new()
            } else {
                (1..=3)
                    .map(|attempt| format!("isolation/shop/prices/unit/1/{attempt}"))
                    .collect()
            },
            reason: reason.map(str::to_string),
        }
    }

    #[test]
    fn writes_isolation_json_and_terminal_lines() {
        let report = IsolationReport {
            runs: 3,
            max_tests: 2,
            failed: 3,
            tests: vec![
                entry("b_needs_order", IsolationClass::Interference, 3, None),
                entry(
                    "login",
                    IsolationClass::Inconclusive,
                    0,
                    Some("adapter `playwright` cannot run a single test"),
                ),
            ],
        };
        assert_eq!(
            report.lines(),
            [
                "==> isolate 2 failed tests: each alone, 3 runs",
                "  INTERFERENCE  shop/prices/unit  b_needs_order  3/3 passed alone",
                "  INCONCLUSIVE  shop/prices/unit  login  adapter `playwright` cannot run a single test",
                "  1 more failed test not isolated (max_tests 2)",
            ]
        );
        assert_eq!(
            report.counts(),
            [
                (IsolationClass::Interference, 1),
                (IsolationClass::Inconclusive, 1)
            ]
        );
        let json = serde_json::to_string_pretty(&report).unwrap();
        assert_eq!(
            json,
            include_str!("../tests/golden/isolation.json").trim_end()
        );
        let none = IsolationReport {
            runs: 3,
            max_tests: 10,
            failed: 0,
            tests: Vec::new(),
        };
        assert_eq!(none.lines(), ["==> isolate: no failed tests"]);
    }
}
