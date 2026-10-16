use crate::coverage::CoverageSummary;

use super::coverage_cell::{
    format_fn_cell, format_fn_cell_colored, format_lines_cell, format_lines_cell_colored,
};
use super::model::{IntegrationRow, ProjectReport, ReportSnapshot};
use super::test_view::{timing_stats, TestCaseView, TestTimingStats, TestsReport};
use super::theme::{self, Accent};

pub const TERMINAL_WIDTH: usize = 200;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Align {
    Left,
    Right,
}

#[derive(Debug, Clone, Copy)]
struct Col {
    width: usize,
    align: Align,
}

const STATUS_OVERALL: Col = Col {
    width: 15,
    align: Align::Left,
};
const STATUS_RUN: Col = Col {
    width: 28,
    align: Align::Left,
};
const STATUS_PKG: Col = Col {
    width: 10,
    align: Align::Left,
};
const STATUS_IT: Col = Col {
    width: 34,
    align: Align::Left,
};
const STATUS_COV: Col = Col {
    width: 58,
    align: Align::Left,
};
const STATUS_REPORT: Col = Col {
    width: 50,
    align: Align::Left,
};

const PRJ_PROJECT: Col = Col {
    width: 10,
    align: Align::Left,
};
const PRJ_PACKAGE: Col = Col {
    width: 14,
    align: Align::Left,
};
const PRJ_UNIT: Col = Col {
    width: 38,
    align: Align::Right,
};
const PRJ_UNIT_FN: Col = Col {
    width: 22,
    align: Align::Right,
};
const PRJ_IT: Col = Col {
    width: 38,
    align: Align::Right,
};
const PRJ_IT_FN: Col = Col {
    width: 22,
    align: Align::Right,
};
const PRJ_MERGED: Col = Col {
    width: 38,
    align: Align::Right,
};
const PRJ_STATUS: Col = Col {
    width: 11,
    align: Align::Left,
};

const IT_PROJECT: Col = Col {
    width: 10,
    align: Align::Left,
};
const IT_SUITE: Col = Col {
    width: 12,
    align: Align::Left,
};
const IT_TEST: Col = Col {
    width: 84,
    align: Align::Left,
};
const IT_SCOPE: Col = Col {
    width: 29,
    align: Align::Left,
};
const IT_TARGET: Col = Col {
    width: 34,
    align: Align::Left,
};
const IT_MS: Col = Col {
    width: 8,
    align: Align::Right,
};
const IT_STATUS: Col = Col {
    width: 8,
    align: Align::Right,
};
const IT_HTTP: Col = Col {
    width: 8,
    align: Align::Right,
};

pub fn visible_width(value: &str) -> usize {
    let mut width = 0;
    let mut chars = value.chars().peekable();
    while let Some(ch) = chars.next() {
        if ch == '\x1b' {
            while let Some(next) = chars.next() {
                if next == 'm' {
                    break;
                }
            }
            continue;
        }
        width += 1;
    }
    width
}

pub fn truncate_visible(value: &str, max: usize) -> String {
    if max == 0 {
        return String::new();
    }
    if visible_width(value) <= max {
        return value.to_string();
    }
    if max == 1 {
        return "…".to_string();
    }
    let mut out = String::new();
    let mut width = 0;
    for ch in value.chars() {
        if width + 1 >= max {
            break;
        }
        out.push(ch);
        width += 1;
    }
    out.push('…');
    out
}

pub fn pad_cell(value: &str, width: usize, align: Align) -> String {
    let visible = visible_width(value);
    if visible >= width {
        return truncate_visible(value, width);
    }
    let pad = width - visible;
    match align {
        Align::Left => format!("{value}{}", " ".repeat(pad)),
        Align::Right => format!("{}{value}", " ".repeat(pad)),
    }
}

fn format_row(cells: &[(String, Col)]) -> String {
    let mut parts = Vec::with_capacity(cells.len());
    for (value, col) in cells {
        parts.push(pad_cell(value, col.width, col.align));
    }
    parts.join(" ")
}

pub fn rule_line() -> String {
    "-".repeat(TERMINAL_WIDTH)
}

const CRAP_TERMINAL_FUNCTIONS: usize = 4;

pub fn format_crap_lines(report: &crate::crap::CrapReport, color: bool) -> Vec<String> {
    if !report.enabled {
        return Vec::new();
    }
    let mut lines = Vec::new();
    if let Some(changed) = &report.changed {
        lines.push(crap_totals_line("change: ", &changed.totals));
    }
    lines.push(crap_totals_line("crap: ", &report.totals));
    if report.functions.is_empty() {
        lines.push(crap_complexity_line("", &report.totals));
        return lines;
    }
    let mut ranked: Vec<&crate::crap::CrapFunction> = report.functions.iter().collect();
    ranked.sort_by(|left, right| {
        right
            .score
            .total_cmp(&left.score)
            .then_with(|| left.path.cmp(&right.path))
            .then_with(|| left.line.cmp(&right.line))
            .then_with(|| left.name.cmp(&right.name))
    });
    let shown: Vec<&crate::crap::CrapFunction> =
        ranked.into_iter().take(CRAP_TERMINAL_FUNCTIONS).collect();
    let name_width = crap_name_width(&shown);
    for function in shown {
        lines.push(format_crap_focus_line(function, name_width, color));
    }
    lines
}

fn crap_totals_line(prefix: &str, totals: &crate::crap::CrapTotals) -> String {
    format!(
        "{prefix}{} functions · worst {:.1} · {} at or above {:.0} ({:.1}%) · crap load {:.1}",
        totals.functions,
        totals.worst_crap,
        totals.crappy_functions,
        crate::crap::CRAP_THRESHOLD,
        totals.crappy_percent,
        totals.crap_load
    )
}

fn crap_complexity_line(prefix: &str, totals: &crate::crap::CrapTotals) -> String {
    format!(
        "{prefix}complexity {} · total CRAP {:.1} · mean {:.1} · median {:.1}",
        totals.complexity, totals.total_crap, totals.mean_crap, totals.median_crap
    )
}

const CRAP_NAME_WIDTH: usize = 16;

fn crap_name_width(functions: &[&crate::crap::CrapFunction]) -> usize {
    let mut width = CRAP_NAME_WIDTH;
    while width > 0
        && functions
            .iter()
            .any(|function| crap_focus_width(function, width) > VERBOSE_WIDTH)
    {
        width -= 1;
    }
    width
}

fn crap_focus_width(function: &crate::crap::CrapFunction, name_width: usize) -> usize {
    2 + 5 + 2 + name_width + 2 + crap_focus_tail(function).chars().count()
}

fn format_crap_focus_line(
    function: &crate::crap::CrapFunction,
    name_width: usize,
    color: bool,
) -> String {
    let score = format!("{:>5.1}", function.score);
    let painted = paint_crap_score(&score, crate::crap::crap_severity(function.score), color);
    let name = pad_cell(
        &trim_visible(&function.name, name_width),
        name_width,
        Align::Left,
    );
    let head = format!("  {painted}  {name}  ");
    let room = VERBOSE_WIDTH.saturating_sub(visible_width(&head));
    format!("{head}{}", trim_visible(&crap_focus_tail(function), room))
}

fn crap_focus_tail(function: &crate::crap::CrapFunction) -> String {
    let location = format!("{}:{}", crap_focus_file(&function.path), function.line);
    let snippet = crap_focus_text(function);
    if snippet.is_empty() {
        location
    } else {
        format!("{location}  {snippet}")
    }
}

fn crap_focus_file(path: &str) -> &str {
    path.rsplit(['/', '\\'])
        .find(|part| !part.is_empty())
        .unwrap_or(path)
}

fn paint_crap_score(score: &str, severity: crate::crap::CrapSeverity, color: bool) -> String {
    let accent = match severity {
        crate::crap::CrapSeverity::Critical => Accent::Fail,
        crate::crap::CrapSeverity::High | crate::crap::CrapSeverity::Moderate => Accent::Low,
        crate::crap::CrapSeverity::Low => return score.to_string(),
    };
    theme::paint_when(color, score, accent)
}

fn crap_focus_text(function: &crate::crap::CrapFunction) -> String {
    let chosen = function
        .source
        .iter()
        .find(|line| line.measure == crate::crap::LineMeasure::Uncovered)
        .or_else(|| function.source.first());
    chosen
        .map(|line| line.text.split_whitespace().collect::<Vec<_>>().join(" "))
        .unwrap_or_default()
}

fn trim_visible(text: &str, max: usize) -> String {
    if text.chars().count() <= max {
        return text.to_string();
    }
    if max <= 3 {
        return text.chars().take(max).collect();
    }
    format!("{}...", text.chars().take(max - 3).collect::<String>())
}

pub const VERBOSE_WIDTH: usize = 100;

pub fn format_verbose_lines(report: &TestsReport, color: bool) -> Vec<String> {
    let mut lines = Vec::new();
    let mut current_phase = String::new();
    for group in verbose_groups(report) {
        let phase = format!("{}/{}/{}", group.project, group.package, group.phase);
        if phase != current_phase {
            if !current_phase.is_empty() {
                lines.push(String::new());
            }
            lines.push(verbose_phase_line(&phase, color));
            current_phase = phase;
        }
        lines.push(verbose_suite_line(
            &group.suite,
            suite_accent_of(&group.stats),
            color,
        ));
        for case in &group.cases {
            lines.push(verbose_case_line(case, color));
            if case.status_label == "FAIL" {
                if let Some(reason) = case.reason.as_deref().filter(|reason| !reason.is_empty()) {
                    lines.push(verbose_reason_line(reason, color));
                }
            }
        }
    }
    if !lines.is_empty() {
        lines.push(String::new());
    }
    lines
}

pub fn format_verbose_closing(
    report: &TestsReport,
    crap: &crate::crap::CrapReport,
    report_path: &std::path::Path,
    color: bool,
) -> Vec<String> {
    let mut lines = format_crap_lines(crap, color);
    if !lines.is_empty() {
        lines.push(String::new());
    }
    lines.push(format_verbose_report_path(report_path));
    lines.push(verbose_summary_line(report, color));
    lines
}

pub fn format_verbose_report_path(path: &std::path::Path) -> String {
    let text = path.to_string_lossy();
    let shown = if let Some(index) = text.rfind("target/cherry-test") {
        format!("report  {}", &text[index..])
    } else {
        "report  index.html".to_string()
    };
    truncate_visible(&shown, VERBOSE_WIDTH)
}

struct VerboseGroup {
    project: String,
    package: String,
    phase: String,
    suite: String,
    stats: TestTimingStats,
    cases: Vec<TestCaseView>,
}

fn verbose_groups(report: &TestsReport) -> Vec<VerboseGroup> {
    let mut groups = Vec::new();
    for project in &report.projects {
        for suite in &project.suites {
            for case in &suite.cases {
                let package = verbose_package(case);
                let phase = verbose_phase(&suite.suite, case);
                let suite_name = verbose_suite(&suite.suite, &phase, case);
                let same = groups.last().is_some_and(|group: &VerboseGroup| {
                    group.project == case.project
                        && group.package == package
                        && group.phase == phase
                        && group.suite == suite_name
                });
                if let Some(group) = groups.last_mut().filter(|_| same) {
                    group.cases.push(case.clone());
                } else {
                    groups.push(VerboseGroup {
                        project: case.project.clone(),
                        package,
                        phase,
                        suite: suite_name,
                        stats: TestTimingStats::default(),
                        cases: vec![case.clone()],
                    });
                }
            }
        }
    }
    for group in &mut groups {
        group.stats = timing_stats(
            group
                .cases
                .iter()
                .map(|case| (case.status_label.as_str(), case.elapsed_ms)),
        );
    }
    groups
}

fn verbose_package(case: &TestCaseView) -> String {
    let Some((_, service)) = case.target.split_once('.') else {
        return case.project.clone();
    };
    if service.is_empty() {
        return case.project.clone();
    }
    if service == "api" {
        return format!("{}-rs-api", case.project);
    }
    service.to_string()
}

fn verbose_phase(recorded_suite: &str, case: &TestCaseView) -> String {
    if is_phase_name(recorded_suite) {
        return recorded_suite.to_string();
    }
    if !case.scope.is_empty() && case.scope != "none" {
        return "integration".to_string();
    }
    "unit".to_string()
}

fn verbose_suite(recorded_suite: &str, phase: &str, case: &TestCaseView) -> String {
    if phase == "visual" {
        return "screen".to_string();
    }
    if phase == "functional" {
        return "storefront".to_string();
    }
    if !is_phase_name(recorded_suite) {
        return recorded_suite.to_string();
    }
    let test = strip_package_prefix(&case.test);
    rust_suite(test).unwrap_or_else(|| js_suite(test))
}

fn verbose_test_name(case: &TestCaseView) -> String {
    let test = strip_package_prefix(&case.test);
    test.rsplit("::").next().unwrap_or(test).to_string()
}

fn is_phase_name(name: &str) -> bool {
    matches!(
        name,
        "unit" | "integration" | "functional" | "visual" | "ui" | "mutation"
    )
}

fn strip_package_prefix(test: &str) -> &str {
    test.split_once(": ").map(|(_, rest)| rest).unwrap_or(test)
}

fn rust_suite(test: &str) -> Option<String> {
    let (head, _) = test.rsplit_once("::")?;
    let suite = if let Some((parent, last)) = head.rsplit_once("::") {
        if last == "tests" {
            parent.rsplit("::").next().unwrap_or(parent)
        } else {
            last
        }
    } else {
        head
    };
    Some(suite.to_string())
}

fn js_suite(test: &str) -> String {
    if test.starts_with("Shop ") || test == "Shop" {
        return "Shop".to_string();
    }
    if test.starts_with("mount") {
        return "mount".to_string();
    }
    if test.starts_with("shop API") || test.starts_with("shop api") {
        return "api".to_string();
    }
    "cart".to_string()
}

fn verbose_phase_line(phase: &str, color: bool) -> String {
    let text = truncate_visible(&format!("==> {phase}"), VERBOSE_WIDTH);
    theme::paint_when(color, &text, Accent::Covered)
}

fn verbose_suite_line(name: &str, accent: Accent, color: bool) -> String {
    let shown = truncate_visible(name, VERBOSE_WIDTH - 2);
    let painted = theme::paint_when(color, &shown, accent);
    format!("  {painted}")
}

fn verbose_case_line(case: &TestCaseView, color: bool) -> String {
    let mark = match case.status_label.as_str() {
        "FAIL" | "fail" => "✗",
        "skipped" => "-",
        _ => "✓",
    };
    let painted = theme::paint_when(color, mark, theme::status_accent(&case.status_label));
    let name = truncate_visible(&verbose_test_name(case), 86);
    let name = pad_cell(&name, 86, Align::Left);
    let timing = pad_cell(&format!("{}ms", case.elapsed_ms), 7, Align::Right);
    format!("    {painted} {name} {timing}")
}

fn verbose_reason_line(reason: &str, color: bool) -> String {
    let text = truncate_visible(reason, 92);
    let painted = theme::paint_when(color, &format!("! {text}"), Accent::Fail);
    format!("      {painted}")
}

fn verbose_summary_line(report: &TestsReport, color: bool) -> String {
    let mut stats = TestTimingStats::default();
    for project in &report.projects {
        stats.run += project.stats.run;
        stats.ok += project.stats.ok;
        stats.fail += project.stats.fail;
        stats.skip += project.stats.skip;
    }
    let head = format!(
        "{} {}",
        crate::COMMAND_NAME,
        if stats.fail > 0 { "FAIL" } else { "OK" }
    );
    let accent = if stats.fail > 0 {
        Accent::Fail
    } else {
        Accent::Ok
    };
    let painted = theme::paint_when(color, &head, accent);
    let line = format!(
        "{painted}  {} run · {} ok · {} fail · {} skip",
        stats.run, stats.ok, stats.fail, stats.skip
    );
    truncate_visible(&line, VERBOSE_WIDTH)
}

const PHASE_NAME_WIDTH: usize = 78;

/// Lines printed right after a phase finishes: tests grouped by suite, each with result and time,
/// the failure or skip reason below it, then the phase totals.
pub fn format_phase_results(
    tests: &[cherry_test_common::adapter::TestResult],
    color: bool,
) -> Vec<String> {
    if tests.is_empty() {
        return vec!["  no tests reported".to_string()];
    }
    let mut suites: Vec<(String, Vec<&cherry_test_common::adapter::TestResult>)> = Vec::new();
    for test in tests {
        let suite = phase_suite(test);
        match suites.iter_mut().find(|(name, _)| *name == suite) {
            Some((_, members)) => members.push(test),
            None => suites.push((suite, vec![test])),
        }
    }
    let mut lines = Vec::with_capacity(tests.len() + suites.len() + 1);
    for (suite, members) in &suites {
        lines.push(format!("  {}", truncate_visible(suite, VERBOSE_WIDTH - 2)));
        for test in members {
            lines.push(phase_case_line(test, color));
            if let Some(reason) = phase_reason_line(test, color) {
                lines.push(reason);
            }
        }
    }
    lines.push(phase_totals_line(tests));
    lines
}

fn phase_suite(test: &cherry_test_common::adapter::TestResult) -> String {
    let segments: Vec<&str> = test.name.split("::").collect();
    if let [.., suite, _] = segments.as_slice() {
        let named = segments
            .iter()
            .rev()
            .skip(1)
            .find(|segment| **segment != "tests")
            .unwrap_or(suite);
        return (*named).to_string();
    }
    test.source
        .as_deref()
        .and_then(|source| source.file_name())
        .map(|file| file.to_string_lossy().into_owned())
        .unwrap_or_else(|| "tests".to_string())
}

fn phase_test_name(test: &cherry_test_common::adapter::TestResult) -> &str {
    test.name.rsplit("::").next().unwrap_or(&test.name)
}

fn phase_status(
    status: &cherry_test_common::adapter::TestStatus,
) -> (&'static str, &'static str, Accent) {
    match status {
        cherry_test_common::adapter::TestStatus::Passed => ("✓", "ok", Accent::Ok),
        cherry_test_common::adapter::TestStatus::Failed => ("✗", "FAIL", Accent::Fail),
        cherry_test_common::adapter::TestStatus::Skipped => ("-", "skipped", Accent::Skip),
    }
}

fn phase_case_line(test: &cherry_test_common::adapter::TestResult, color: bool) -> String {
    let (mark, label, accent) = phase_status(&test.status);
    let name = pad_cell(
        &truncate_visible(phase_test_name(test), PHASE_NAME_WIDTH),
        PHASE_NAME_WIDTH,
        Align::Left,
    );
    let label = theme::paint_when(color, &pad_cell(label, 7, Align::Left), accent);
    let timing = pad_cell(&format!("{}ms", test.duration_ms), 7, Align::Right);
    format!(
        "    {} {name} {label}{timing}",
        theme::paint_when(color, mark, accent)
    )
}

fn phase_reason_line(
    test: &cherry_test_common::adapter::TestResult,
    color: bool,
) -> Option<String> {
    let marker = match test.status {
        cherry_test_common::adapter::TestStatus::Failed => "!",
        cherry_test_common::adapter::TestStatus::Skipped => "-",
        cherry_test_common::adapter::TestStatus::Passed => return None,
    };
    let first_line = test
        .message
        .as_deref()?
        .lines()
        .map(str::trim)
        .find(|line| !line.is_empty())?;
    let (_, _, accent) = phase_status(&test.status);
    let text = truncate_visible(&format!("{marker} {first_line}"), VERBOSE_WIDTH - 6);
    Some(format!("      {}", theme::paint_when(color, &text, accent)))
}

fn phase_totals_line(tests: &[cherry_test_common::adapter::TestResult]) -> String {
    let count = |status: cherry_test_common::adapter::TestStatus| {
        tests.iter().filter(|test| test.status == status).count()
    };
    let elapsed_ms: u64 = tests.iter().map(|test| test.duration_ms).sum();
    format!(
        "  {} tests · {} ok · {} failed · {} skipped · {elapsed_ms}ms",
        tests.len(),
        count(cherry_test_common::adapter::TestStatus::Passed),
        count(cherry_test_common::adapter::TestStatus::Failed),
        count(cherry_test_common::adapter::TestStatus::Skipped),
    )
}

#[cfg(test)]
pub fn row_visible_width(row: &str) -> usize {
    visible_width(row)
}

pub fn format_status_row(snapshot: &ReportSnapshot) -> String {
    let overall = format!(
        "{} {}",
        crate::COMMAND_NAME,
        if snapshot.overall_ok { "OK" } else { "FAIL" }
    );
    let run = format!("run:{}", snapshot.run_id);
    let pkg = if snapshot.packages_failed > 0 {
        format!(
            "pkg {}/{} fail:{}",
            snapshot.packages_passed, snapshot.packages_total, snapshot.packages_failed
        )
    } else {
        format!(
            "pkg {}/{}",
            snapshot.packages_passed, snapshot.packages_total
        )
    };
    let it_ok = if snapshot.integration_run > 0 {
        format!(
            "{:.0}%",
            ((snapshot.integration_run - snapshot.integration_failed) as f64
                / snapshot.integration_run as f64)
                * 100.0
        )
    } else {
        "-".into()
    };
    let it = format!(
        "tests run:{} skip:{} fail:{} ok:{}",
        snapshot.integration_run, snapshot.integration_skipped, snapshot.integration_failed, it_ok
    );
    let cov = if snapshot.coverage_enabled {
        format!(
            "all:{} unit:{} it:{} diff:{}",
            pct(snapshot.totals.merged.lines_percent()),
            pct_opt(snapshot.totals.unit.as_ref()),
            pct_opt(snapshot.totals.integration.as_ref()),
            pct(snapshot.diff_coverage.merged.lines_percent())
        )
    } else {
        "cov:-".into()
    };
    let report = format!("report: {}", snapshot.report_path.display());
    let cells = vec![
        (overall.into(), STATUS_OVERALL),
        (run, STATUS_RUN),
        (pkg, STATUS_PKG),
        (it, STATUS_IT),
        (cov, STATUS_COV),
        (report, STATUS_REPORT),
    ];
    format_row(&cells)
}

pub fn format_projects_header() -> String {
    format_row(&[
        ("project".into(), PRJ_PROJECT),
        ("package".into(), PRJ_PACKAGE),
        ("unit".into(), PRJ_UNIT),
        ("unit_fn".into(), PRJ_UNIT_FN),
        ("it".into(), PRJ_IT),
        ("it_fn".into(), PRJ_IT_FN),
        ("merged".into(), PRJ_MERGED),
        ("status".into(), PRJ_STATUS),
    ])
}

pub fn format_project_row(project: &ProjectReport, color: bool) -> String {
    let status = if project.tests_passed { "ok" } else { "FAIL" };
    let status_out = if color {
        theme::paint(status, theme::status_accent(status))
    } else {
        status.to_string()
    };
    format_row(&[
        (project.project_id.clone(), PRJ_PROJECT),
        (project.package.clone(), PRJ_PACKAGE),
        (
            lines_cell(project.diff_coverage.unit.as_ref(), color),
            PRJ_UNIT,
        ),
        (
            fn_cell(project.diff_coverage.unit.as_ref(), color),
            PRJ_UNIT_FN,
        ),
        (
            lines_cell(project.diff_coverage.integration.as_ref(), color),
            PRJ_IT,
        ),
        (
            fn_cell(project.diff_coverage.integration.as_ref(), color),
            PRJ_IT_FN,
        ),
        (
            lines_cell(Some(&project.diff_coverage.merged), color),
            PRJ_MERGED,
        ),
        (status_out, PRJ_STATUS),
    ])
}

pub fn format_projects_footer(snapshot: &ReportSnapshot, color: bool) -> String {
    let dc = &snapshot.diff_coverage;
    let status_out = if color {
        theme::paint("ALL", Accent::Neutral)
    } else {
        "ALL".to_string()
    };
    format_row(&[
        ("ALL".into(), PRJ_PROJECT),
        ("(changed)".into(), PRJ_PACKAGE),
        (lines_cell(dc.unit.as_ref(), color), PRJ_UNIT),
        (fn_cell(dc.unit.as_ref(), color), PRJ_UNIT_FN),
        (lines_cell(dc.integration.as_ref(), color), PRJ_IT),
        (fn_cell(dc.integration.as_ref(), color), PRJ_IT_FN),
        (lines_cell(Some(&dc.merged), color), PRJ_MERGED),
        (status_out, PRJ_STATUS),
    ])
}

pub fn format_integration_header() -> String {
    format_row(&[
        ("project".into(), IT_PROJECT),
        ("suite".into(), IT_SUITE),
        ("test".into(), IT_TEST),
        ("scope".into(), IT_SCOPE),
        ("target".into(), IT_TARGET),
        ("ms".into(), IT_MS),
        ("status".into(), IT_STATUS),
        ("http".into(), IT_HTTP),
    ])
}

pub fn format_grouped_test_lines(report: &TestsReport, color: bool) -> Vec<String> {
    let mut lines = Vec::new();
    for project in &report.projects {
        lines.push(format!(
            "{}  {}",
            painted_label(&project.project, 10, suite_accent_of(&project.stats), color),
            crate::report::format_timing_stats(&project.stats)
        ));
        for suite in &project.suites {
            lines.push(format!(
                "  {}  {}",
                painted_label(&suite.suite, 12, suite_accent_of(&suite.stats), color),
                crate::report::format_timing_stats(&suite.stats)
            ));
            for case in &suite.cases {
                lines.push(format_grouped_case(case, color));
            }
        }
    }
    lines
}

pub fn format_mini_report_lines(report: &TestsReport, color: bool) -> Vec<String> {
    let mut lines = Vec::new();
    let mut samples = Vec::new();
    for project in &report.projects {
        for suite in &project.suites {
            lines.push(mini_line(&suite.suite, &suite.stats, color));
            for case in &suite.cases {
                samples.push((case.status_label.as_str(), case.elapsed_ms));
            }
        }
    }
    if !lines.is_empty() {
        let all = timing_stats(samples);
        lines.push(mini_line("all", &all, color));
    }
    lines
}

fn format_grouped_case(case: &TestCaseView, color: bool) -> String {
    let status = theme::paint_when(
        color,
        &case.status_label,
        theme::status_accent(&case.status_label),
    );
    format!(
        "    {} {} {} {} {} {}",
        pad_cell(&case.test, 72, Align::Left),
        pad_cell(&case.scope, 24, Align::Left),
        pad_cell(&case.target, 28, Align::Left),
        pad_cell(&case.elapsed_ms.to_string(), 8, Align::Right),
        pad_cell(&status, 8, Align::Right),
        pad_cell(&case.http, 8, Align::Right),
    )
}

fn mini_line(label: &str, stats: &TestTimingStats, color: bool) -> String {
    format!(
        "  {}  {}",
        painted_label(label, 14, suite_accent_of(stats), color),
        crate::report::format_timing_stats(stats)
    )
}

fn suite_accent_of(stats: &TestTimingStats) -> Accent {
    theme::suite_accent(stats.fail, stats.skip)
}

fn painted_label(text: &str, width: usize, accent: Accent, color: bool) -> String {
    let shown = truncate_visible(text, width);
    let painted = theme::paint_when(color, &shown, accent);
    let visible = visible_width(&shown);
    if visible >= width {
        painted
    } else {
        format!("{painted}{}", " ".repeat(width - visible))
    }
}

pub fn format_integration_row(row: &IntegrationRow, color: bool) -> String {
    let status_out = if color {
        theme::paint(&row.status_label, theme::status_accent(&row.status_label))
    } else {
        row.status_label.clone()
    };
    format_row(&[
        (row.project.clone(), IT_PROJECT),
        (row.suite.clone(), IT_SUITE),
        (row.test.clone(), IT_TEST),
        (row.scope.clone(), IT_SCOPE),
        (row.target.clone(), IT_TARGET),
        (row.elapsed_ms.to_string(), IT_MS),
        (status_out, IT_STATUS),
        (row.http.clone(), IT_HTTP),
    ])
}

fn lines_cell(summary: Option<&CoverageSummary>, color: bool) -> String {
    if color {
        format_lines_cell_colored(summary, true)
    } else {
        format_lines_cell(summary, true)
    }
}

fn fn_cell(summary: Option<&CoverageSummary>, color: bool) -> String {
    if color {
        format_fn_cell_colored(summary, true)
    } else {
        format_fn_cell(summary, true)
    }
}

fn pct(value: f64) -> String {
    if value.is_nan() {
        "-".into()
    } else {
        format!("{value:.1}%")
    }
}

fn pct_opt(summary: Option<&CoverageSummary>) -> String {
    match summary {
        Some(s) => pct(s.lines_percent()),
        None => "-".into(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::report::model::ProjectReport;

    mod phase_results {
        use super::*;
        use cherry_test_common::adapter::{TestResult, TestStatus};

        fn result(name: &str, status: TestStatus, message: Option<&str>, ms: u64) -> TestResult {
            crate::adapters::formats::case(name.into(), status, message.map(str::to_owned), ms)
        }

        fn sample() -> Vec<TestResult> {
            vec![
                result("application::tests::catalog_service::lists_products", TestStatus::Passed, None, 3),
                result(
                    "application::tests::catalog_service::reports_a_price_mismatch",
                    TestStatus::Failed,
                    Some("assertion `left == right` failed: listed price did not match the expected 1500\n  left: 1800"),
                    1,
                ),
                result("application::tests::checkout::rejects_empty_cart", TestStatus::Skipped, Some("database unavailable"), 0),
            ]
        }

        #[test]
        fn groups_tests_under_their_suite() {
            let lines = format_phase_results(&sample(), false);
            assert_eq!(lines[0], "  catalog_service");
            assert!(lines.iter().any(|line| line == "  checkout"));
        }

        #[test]
        fn shows_result_and_time_for_each_test() {
            let lines = format_phase_results(&sample(), false);
            let passed = lines
                .iter()
                .find(|line| line.contains("lists_products"))
                .unwrap();
            assert!(passed.starts_with("    ✓ lists_products"));
            assert!(passed.contains(" ok "));
            assert!(passed.ends_with(" 3ms"));
        }

        #[test]
        fn shows_the_first_line_of_a_failure_reason() {
            let lines = format_phase_results(&sample(), false);
            let index = lines
                .iter()
                .position(|line| line.contains("reports_a_price_mismatch"))
                .unwrap();
            assert!(lines[index].contains("FAIL"));
            assert_eq!(
                lines[index + 1],
                "      ! assertion `left == right` failed: listed price did not match the expected 1500"
            );
        }

        #[test]
        fn shows_why_a_test_was_skipped() {
            let lines = format_phase_results(&sample(), false);
            let index = lines
                .iter()
                .position(|line| line.contains("rejects_empty_cart"))
                .unwrap();
            assert!(lines[index].contains("skipped"));
            assert_eq!(lines[index + 1], "      - database unavailable");
        }

        #[test]
        fn ends_with_the_phase_totals() {
            let lines = format_phase_results(&sample(), false);
            assert_eq!(
                lines.last().map(String::as_str),
                Some("  3 tests · 1 ok · 1 failed · 1 skipped · 4ms")
            );
        }

        #[test]
        fn keeps_every_line_within_the_terminal_width() {
            let long = "n".repeat(300);
            let tests = vec![result(&long, TestStatus::Failed, Some(&long), 2)];
            assert!(format_phase_results(&tests, false)
                .iter()
                .all(|line| visible_width(line) <= VERBOSE_WIDTH));
        }

        #[test]
        fn says_when_a_phase_reported_no_tests() {
            assert_eq!(
                format_phase_results(&[], false),
                vec!["  no tests reported".to_string()]
            );
        }
    }
    use crate::report::{CoverageTotals, PackageCoverage};
    use std::path::PathBuf;

    fn sample_summary(
        lines_hit: u64,
        lines_found: u64,
        fn_hit: u64,
        fn_found: u64,
    ) -> CoverageSummary {
        CoverageSummary {
            lines_hit,
            lines_found,
            functions_hit: fn_hit,
            functions_found: fn_found,
        }
    }

    fn sample_snapshot() -> ReportSnapshot {
        ReportSnapshot {
            run_id: "20260523-064607-178a68".into(),
            overall_ok: true,
            packages_passed: 2,
            packages_total: 2,
            packages_failed: 0,
            integration_run: 2,
            integration_skipped: 0,
            integration_failed: 0,
            coverage_enabled: true,
            totals: CoverageTotals {
                unit: Some(sample_summary(406, 1000, 0, 0)),
                integration: Some(sample_summary(0, 330, 0, 80)),
                merged: sample_summary(268, 1000, 0, 0),
            },
            diff_coverage: CoverageTotals {
                unit: Some(sample_summary(26, 64, 7, 17)),
                integration: Some(sample_summary(0, 33, 0, 8)),
                merged: sample_summary(17, 88, 0, 0),
            },
            projects: vec![
                ProjectReport {
                    project_id: "mini".into(),
                    package: "mini-rs-lib-mcap".into(),
                    tests_passed: true,
                    coverage: PackageCoverage::default(),
                    diff_coverage: CoverageTotals {
                        unit: Some(sample_summary(17, 30, 4, 9)),
                        integration: None,
                        merged: sample_summary(17, 30, 0, 0),
                    },
                    changed_files: vec![],
                },
                ProjectReport {
                    project_id: "mini".into(),
                    package: "mini-rs-api".into(),
                    tests_passed: true,
                    coverage: PackageCoverage::default(),
                    diff_coverage: CoverageTotals {
                        unit: Some(sample_summary(9, 34, 3, 8)),
                        integration: Some(sample_summary(0, 33, 0, 8)),
                        merged: sample_summary(0, 58, 0, 0),
                    },
                    changed_files: vec![],
                },
            ],
            project_diff_sections: vec![],
            integration_rows: vec![
                IntegrationRow {
                    project: "mini".into(),
                    suite: "wallet".into(),
                    test: "get_address_value_usdt".into(),
                    full_name: "mini_project_wallet_suite_get_address_value_usdt_test".into(),
                    scope: "mini_wallet".into(),
                    target: "mini.mcap".into(),
                    elapsed_ms: 2,
                    status_label: "ok".into(),
                    http: "200".into(),
                    reason: None,
                    method: "GET".into(),
                    url: "/x".into(),
                    tags: vec![],
                    location: None,
                },
                IntegrationRow {
                    project: "mini".into(),
                    suite: "wallet".into(),
                    test: "get_address_value_usdt".into(),
                    full_name: "mini_project_wallet_suite_get_address_value_usdt_test".into(),
                    scope: "mini_wallet".into(),
                    target: "mini.api".into(),
                    elapsed_ms: 2,
                    status_label: "ok".into(),
                    http: "200".into(),
                    reason: None,
                    method: "GET".into(),
                    url: "/y".into(),
                    tags: vec![],
                    location: None,
                },
            ],
            tests_report: crate::report::TestsReport::default(),
            crap: crate::crap::CrapReport::default(),
            report_path: PathBuf::from(
                "target/cherry-test/runs/20260523-064607-178a68/reports/index.html",
            ),
        }
    }

    #[test]
    fn crap_lines_appear_only_when_the_report_is_enabled() {
        assert!(format_crap_lines(&crate::crap::CrapReport::default(), false).is_empty());
        let empty = format_crap_lines(
            &crate::crap::CrapReport {
                enabled: true,
                changed: None,
                ..crate::crap::CrapReport::default()
            },
            false,
        );
        assert_eq!(
            empty,
            vec![
                "crap: 0 functions · worst 0.0 · 0 at or above 30 (0.0%) · crap load 0.0"
                    .to_string(),
                "complexity 0 · total CRAP 0.0 · mean 0.0 · median 0.0".to_string(),
            ]
        );
        let function = crate::crap::CrapFunction {
            path: "src/price.rs".into(),
            name: "price".into(),
            line: 4,
            complexity: 10,
            covered_lines: 0,
            measured_lines: 4,
            coverage: 0.0,
            score: 110.0,
            crap_load: crate::crap::crap_load(10, 0.0),
            source: vec![crate::crap::CrapSourceLine {
                number: 5,
                text: "    cents * 2".into(),
                measure: crate::crap::LineMeasure::Uncovered,
            }],
        };
        let functions = vec![function];
        let lines = format_crap_lines(
            &crate::crap::CrapReport {
                enabled: true,
                totals: crate::crap::crap_totals(&functions),
                projects: vec![crate::crap::CrapProjectTotals {
                    project: "shop".into(),
                    totals: crate::crap::crap_totals(&functions),
                }],
                functions,
                changed: None,
            },
            false,
        );
        assert_eq!(lines.len(), 2);
        assert_eq!(
            lines[0],
            "crap: 1 functions · worst 110.0 · 1 at or above 30 (100.0%) · crap load 10.3"
        );
        assert_eq!(lines[1], "  110.0  price             price.rs:4  cents * 2");
        assert!(lines
            .iter()
            .all(|line| visible_width(line) <= VERBOSE_WIDTH));
    }

    #[test]
    fn crap_summary_uses_totals_for_functions_past_the_table() {
        let mut functions = Vec::new();
        for index in 0..30 {
            functions.push(crate::crap::CrapFunction {
                path: format!("src/low{index}.rs"),
                name: format!("low{index}"),
                line: 1,
                complexity: 1,
                covered_lines: 1,
                measured_lines: 1,
                coverage: 1.0,
                score: 1.0,
                crap_load: 0.0,
                source: Vec::new(),
            });
        }
        functions.push(crate::crap::CrapFunction {
            path: "src/high.rs".into(),
            name: "high".into(),
            line: 1,
            complexity: 10,
            covered_lines: 0,
            measured_lines: 1,
            coverage: 0.0,
            score: 110.0,
            crap_load: crate::crap::crap_load(10, 0.0),
            source: Vec::new(),
        });
        let totals = crate::crap::crap_totals(&functions);
        let lines = format_crap_lines(
            &crate::crap::CrapReport {
                enabled: true,
                totals,
                projects: vec![],
                functions,
                changed: None,
            },
            false,
        );
        assert_eq!(
            lines[0],
            "crap: 31 functions · worst 110.0 · 1 at or above 30 (3.2%) · crap load 10.3"
        );
        assert!(lines[1].contains("high"));
        assert!(lines[1].contains("high.rs:1"));
        assert!(!lines[1].contains("src/"));
        assert!(!lines.iter().any(|line| line.contains("more functions")));
        assert_eq!(lines.len(), 5);
    }

    #[test]
    fn crap_focus_line_keeps_the_uncovered_source_line() {
        let snippet = "Self::InvalidMoney => write!(formatter, \"amount must not be negative\"),";
        let function = crate::crap::CrapFunction {
            path: "shop-rs-api/src/domain/model.rs".into(),
            name: "fmt".into(),
            line: 24,
            complexity: 2,
            covered_lines: 1,
            measured_lines: 4,
            coverage: 0.25,
            score: 12.2,
            crap_load: 0.0,
            source: vec![
                crate::crap::CrapSourceLine {
                    number: 22,
                    text: "    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {"
                        .into(),
                    measure: crate::crap::LineMeasure::Covered,
                },
                crate::crap::CrapSourceLine {
                    number: 24,
                    text: format!("            {snippet}"),
                    measure: crate::crap::LineMeasure::Uncovered,
                },
            ],
        };
        let functions = vec![function];
        let report = crate::crap::CrapReport {
            enabled: true,
            totals: crate::crap::crap_totals(&functions),
            projects: vec![],
            functions,
            changed: None,
        };
        let lines = format_crap_lines(&report, false);
        assert_eq!(lines[1], format!("   12.2  fmt    model.rs:24  {snippet}"));
        assert!(!lines[1].contains("shop-rs-api"));
        assert!(visible_width(&lines[1]) <= VERBOSE_WIDTH);
        let colored = format_crap_lines(&report, true);
        assert!(colored[1].contains("\u{1b}[33m 12.2\u{1b}[0m"));
        assert!(colored[1].contains(snippet));
    }

    #[test]
    fn crap_change_line_precedes_the_workspace_totals() {
        let changed_function = crate::crap::CrapFunction {
            path: "shop-rs-api/src/domain/model.rs".into(),
            name: "parse".into(),
            line: 90,
            complexity: 3,
            covered_lines: 2,
            measured_lines: 3,
            coverage: 2.0 / 3.0,
            score: 4.0,
            crap_load: 0.0,
            source: Vec::new(),
        };
        let other = crate::crap::CrapFunction {
            path: "src/other.rs".into(),
            name: "other".into(),
            line: 1,
            complexity: 10,
            covered_lines: 0,
            measured_lines: 1,
            coverage: 0.0,
            score: 110.0,
            crap_load: crate::crap::crap_load(10, 0.0),
            source: Vec::new(),
        };
        let changed_functions = vec![changed_function];
        let functions = {
            let mut all = changed_functions.clone();
            all.push(other);
            all
        };
        let lines = format_crap_lines(
            &crate::crap::CrapReport {
                enabled: true,
                totals: crate::crap::crap_totals(&functions),
                projects: vec![],
                changed: Some(crate::crap::CrapChange {
                    totals: crate::crap::crap_totals(&changed_functions),
                    functions: changed_functions,
                }),
                functions,
            },
            false,
        );
        assert_eq!(
            lines[0],
            "change: 1 functions · worst 4.0 · 0 at or above 30 (0.0%) · crap load 0.0"
        );
        assert!(lines[1].starts_with("crap: 2 functions"));
    }

    #[test]
    fn visible_width_ignores_ansi() {
        let plain = "ok";
        let colored = theme::paint("ok", Accent::Ok);
        assert_eq!(visible_width(plain), 2);
        assert_eq!(visible_width(&colored), 2);
    }

    #[test]
    fn format_row_is_exactly_200_chars() {
        let header = format_projects_header();
        assert_eq!(row_visible_width(&header), TERMINAL_WIDTH);
        let snapshot = sample_snapshot();
        for project in &snapshot.projects {
            let row = format_project_row(project, false);
            assert_eq!(row_visible_width(&row), TERMINAL_WIDTH);
        }
        let footer = format_projects_footer(&snapshot, false);
        assert_eq!(row_visible_width(&footer), TERMINAL_WIDTH);
        let status = format_status_row(&snapshot);
        assert_eq!(row_visible_width(&status), TERMINAL_WIDTH);
        assert!(!status.to_lowercase().contains("crap"));
        let it_header = format_integration_header();
        assert_eq!(row_visible_width(&it_header), TERMINAL_WIDTH);
        for row in &snapshot.integration_rows {
            let line = format_integration_row(row, false);
            assert_eq!(row_visible_width(&line), TERMINAL_WIDTH);
        }
    }

    #[test]
    fn status_and_project_columns_align() {
        let snapshot = sample_snapshot();
        let row = format_project_row(&snapshot.projects[0], false);
        assert_eq!(row_visible_width(&row), TERMINAL_WIDTH);
        assert!(row.ends_with(&pad_cell("ok", PRJ_STATUS.width, PRJ_STATUS.align)));
    }

    #[test]
    fn truncate_visible_adds_ellipsis() {
        let out = truncate_visible("hello world", 6);
        assert_eq!(visible_width(&out), 6);
        assert!(out.ends_with('…'));
    }

    #[test]
    fn rule_line_width() {
        assert_eq!(rule_line().len(), TERMINAL_WIDTH);
    }

    fn sample_case(suite: &str, test: &str, status: &str, elapsed_ms: u64) -> TestCaseView {
        TestCaseView {
            project: "shop".into(),
            suite: suite.into(),
            test: test.into(),
            full_name: test.into(),
            scope: "shop_checkout".into(),
            target: "shop.api".into(),
            elapsed_ms,
            status_label: status.into(),
            http: "200".into(),
            reason: None,
            method: "GET".into(),
            url: "/health".into(),
            tags: vec![],
            snippet: vec![],
            snippet_path: None,
        }
    }

    fn sample_tests() -> TestsReport {
        use crate::report::test_view::{ProjectTestView, SuiteTestView};
        let passed = sample_case("cart", "adds_line", "ok", 12);
        let skipped = sample_case("stock", "waits_for_docker", "skipped", 0);
        let failed = sample_case("checkout", "rejects_empty", "FAIL", 4);
        TestsReport {
            projects: vec![ProjectTestView {
                project: "shop".into(),
                stats: timing_stats([("ok", 12), ("skipped", 0), ("FAIL", 4)]),
                suites: vec![
                    SuiteTestView {
                        suite: "cart".into(),
                        stats: timing_stats([("ok", 12)]),
                        cases: vec![passed],
                    },
                    SuiteTestView {
                        suite: "checkout".into(),
                        stats: timing_stats([("FAIL", 4)]),
                        cases: vec![failed],
                    },
                    SuiteTestView {
                        suite: "stock".into(),
                        stats: timing_stats([("skipped", 0)]),
                        cases: vec![skipped],
                    },
                ],
            }],
            all_tags: vec![],
        }
    }

    #[test]
    fn grouped_tests_color_each_suite() {
        let report = sample_tests();
        let lines = format_grouped_test_lines(&report, true);
        assert!(lines
            .iter()
            .any(|line| line.contains("\u{1b}[32mcart\u{1b}[0m")));
        assert!(lines
            .iter()
            .any(|line| line.contains("\u{1b}[33mstock\u{1b}[0m")));
        assert!(lines
            .iter()
            .any(|line| line.contains("\u{1b}[31mcheckout\u{1b}[0m")));
        assert!(lines
            .iter()
            .any(|line| line.contains("adds_line") && line.contains("\u{1b}[32mok\u{1b}[0m")));
        assert!(lines
            .iter()
            .any(|line| line.contains("waits_for_docker")
                && line.contains("\u{1b}[33mskipped\u{1b}[0m")));
        assert!(
            lines
                .iter()
                .any(|line| line.contains("rejects_empty")
                    && line.contains("\u{1b}[31mFAIL\u{1b}[0m"))
        );
        assert!(lines
            .iter()
            .all(|line| visible_width(line) <= TERMINAL_WIDTH));
        let cart_at = lines.iter().position(|line| line.contains("cart")).unwrap();
        let case_at = lines
            .iter()
            .position(|line| line.contains("adds_line"))
            .unwrap();
        assert!(case_at > cart_at);
    }

    #[test]
    fn mini_report_lists_suites_and_the_total() {
        let report = sample_tests();
        let lines = format_mini_report_lines(&report, true);
        assert_eq!(lines.len(), 4);
        assert!(lines[0].contains("\u{1b}[32mcart\u{1b}[0m"));
        assert!(lines[0].contains("1 run · 1 ok · 0 fail · 0 skip"));
        assert!(lines[1].contains("\u{1b}[31mcheckout\u{1b}[0m"));
        assert!(lines[2].contains("\u{1b}[33mstock\u{1b}[0m"));
        assert!(lines[2].contains("0 run · 0 ok · 0 fail · 1 skip"));
        assert!(lines[3].contains("all"));
        assert!(lines[3].contains("\u{1b}[31mall\u{1b}[0m"));
        assert!(lines[3].contains("2 run · 1 ok · 1 fail · 1 skip"));
        assert!(lines[3].contains("16ms total"));
        assert!(lines
            .iter()
            .all(|line| visible_width(line) <= TERMINAL_WIDTH));
    }

    #[test]
    fn verbose_lines_group_suites_and_mark_failures() {
        let mut report = sample_tests();
        report.projects[0].suites[1].cases[0].reason =
            Some("listed price did not match the expected 1500".into());
        let unit = sample_case(
            "unit",
            "shop-rs-api: application::tests::catalog_service::lists_products",
            "ok",
            2,
        );
        let mut listed = unit;
        listed.target = "shop.shop-rs-api".into();
        listed.scope = "none".into();
        report.projects[0]
            .suites
            .push(crate::report::test_view::SuiteTestView {
                suite: "unit".into(),
                stats: timing_stats([("ok", 2)]),
                cases: vec![listed],
            });
        let lines = format_verbose_lines(&report, true);
        assert!(lines.iter().any(|line| {
            line.contains("==> shop/shop-rs-api/integration") && line.contains("\u{1b}[94m")
        }));
        assert!(lines
            .iter()
            .any(|line| line.contains("\u{1b}[32mcart\u{1b}[0m")));
        assert!(lines.iter().any(|line| {
            line.contains("✓") && line.contains("adds_line") && line.contains("\u{1b}[32m")
        }));
        assert!(lines.iter().any(|line| {
            line.contains("✗") && line.contains("rejects_empty") && line.contains("\u{1b}[31m")
        }));
        assert!(lines.iter().any(|line| {
            line.contains('!') && line.contains("listed price did not match the expected 1500")
        }));
        assert!(lines
            .iter()
            .any(|line| line.contains("==> shop/shop-rs-api/unit")));
        assert!(lines.iter().any(|line| line.contains("catalog_service")));
        assert!(lines.iter().any(|line| line.contains("lists_products")));
        assert!(!lines.iter().any(|line| line.contains("cherry-test FAIL")));
        let summary = verbose_summary_line(&report, true);
        assert!(summary.contains("cherry-test FAIL") && summary.contains("1 fail"));
        assert!(lines
            .iter()
            .all(|line| visible_width(line) <= VERBOSE_WIDTH));
        let long = "n".repeat(200);
        let wide = verbose_case_line(&sample_case("cart", &long, "ok", 3), false);
        assert_eq!(visible_width(&wide), VERBOSE_WIDTH);
        let path = format_verbose_report_path(std::path::Path::new(
            "/tmp/workspace/target/cherry-test/latest/reports/index.html",
        ));
        assert_eq!(path, "report  target/cherry-test/latest/reports/index.html");
        assert!(!path.contains("/tmp"));
    }

    #[test]
    fn write_shop_verbose_cast() {
        let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../site/demo");
        let html_path = root.join("report/index.html");
        if !html_path.is_file() {
            return;
        }
        let html = std::fs::read_to_string(&html_path).unwrap();
        let report = shop_report_from_html(&html);
        assert_eq!(report.projects.len(), 1);
        assert_eq!(report.projects[0].stats.run, 102);
        assert_eq!(report.projects[0].stats.ok, 101);
        assert_eq!(report.projects[0].stats.fail, 1);
        let lines = format_verbose_lines(&report, true);
        assert!(lines
            .iter()
            .all(|line| visible_width(line) <= VERBOSE_WIDTH));
        let joined = lines.join("\n");
        assert!(joined.contains("==> shop/shop-rs-api/integration"));
        assert!(joined.contains("==> shop/shop-rs-api/unit"));
        assert!(joined.contains("==> shop/shop-ts-lib-cart/unit"));
        assert!(joined.contains("==> shop/shop-ts-web-react/functional"));
        assert!(joined.contains("==> shop/shop-ts-web-react/visual"));
        assert!(joined.contains("reports_a_price_mismatch"));
        assert!(joined.contains("listed price did not match the expected 1500"));
        assert!(!joined.contains('<'));
        assert!(joined.contains('✓'));
        assert!(joined.contains('✗'));
        assert!(!joined.contains("cherry-test FAIL"));
        let summary = verbose_summary_line(&report, true);
        assert!(summary.contains("cherry-test FAIL") && summary.contains("1 fail"));
        assert!(!joined.contains("/Users"));
        assert!(!joined.contains("/private"));
        let cast_path = root.join("shop.cast");
        if !cast_path.is_file() {
            return;
        }
        let cast = std::fs::read_to_string(cast_path).unwrap();
        let visible = strip_ansi(&cast.replace("\\u001b", "\u{1b}"));
        assert!(visible.contains("Multiple mono-repositories. Single test workflow."));
        assert!(cast.contains("cherry-test --root app/shop --verbose"));
        assert!(visible.contains("reports_a_price_mismatch"));
        assert!(visible.contains("cherry-test FAIL"));
        let crap_at = visible.find("crap:").unwrap();
        let summary_at = visible.rfind("cherry-test FAIL").unwrap();
        assert!(crap_at < summary_at);
        assert!(visible.contains("amount must not be negative"));
        assert!(!visible.contains("more functions"));
        assert!(cast.contains("\\u001b[31m"));
        assert!(cast.contains("\\u001b[32m"));
        assert!(cast.contains("\\u001b[38;2;226;59;50m"));
        assert!(!cast.contains("/Users"));
        assert!(!cast.contains("/private"));
    }

    fn shop_report_from_html(html: &str) -> TestsReport {
        let start = html.find("id=\"test-list-grouped\"").unwrap();
        let end = html.find("id=\"test-list-flat\"").unwrap();
        let body = &html[start..end];
        let mut suite_name = "tests".to_string();
        let mut cases_by_suite: Vec<(String, Vec<TestCaseView>)> = Vec::new();
        let mut rest = body;
        loop {
            let suite_at = rest.find("<details class=\"test-suite\"");
            let case_at = rest.find("<details class=\"test-case\"");
            match (suite_at, case_at) {
                (Some(suite_at), Some(case_at)) if suite_at < case_at => {
                    let slice = &rest[suite_at..];
                    suite_name = attr(slice, "data-suite").unwrap_or_else(|| "tests".into());
                    rest = &rest[suite_at + 20..];
                }
                (_, Some(case_at)) => {
                    let slice = &rest[case_at..];
                    let tag_end = slice.find('>').unwrap();
                    let tag = &slice[..tag_end];
                    let status = attr(tag, "data-status").unwrap_or_else(|| "ok".into());
                    let elapsed_ms = attr(tag, "data-ms")
                        .and_then(|value| value.parse().ok())
                        .unwrap_or(0);
                    let title_at = slice.find("<span class=\"fold-title\">").unwrap();
                    let title = &slice[title_at + "<span class=\"fold-title\">".len()..];
                    let title = title.split("<span").next().unwrap().trim();
                    let meta = slice.find("scope ").map(|at| {
                        let tail = &slice[at..];
                        let end = tail.len().min(160);
                        &tail[..end]
                    });
                    let meta = meta.unwrap_or("");
                    let scope = meta
                        .split("scope ")
                        .nth(1)
                        .and_then(|part| part.split(" ·").next())
                        .unwrap_or("none")
                        .trim()
                        .to_string();
                    let target = meta
                        .split("target ")
                        .nth(1)
                        .and_then(|part| part.split('<').next())
                        .unwrap_or("shop.shop")
                        .trim()
                        .to_string();
                    let reason = if status == "FAIL" {
                        slice.find("class=\"why-pop\"").and_then(|at| {
                            let after = &slice[at..];
                            let open = after.find('>')?;
                            let text = after[open + 1..].split('<').next().unwrap_or("");
                            let text = text.trim();
                            if text.is_empty() {
                                None
                            } else {
                                Some(text.to_string())
                            }
                        })
                    } else {
                        None
                    };
                    let case = TestCaseView {
                        project: "shop".into(),
                        suite: suite_name.clone(),
                        test: title.into(),
                        full_name: title.into(),
                        scope,
                        target,
                        elapsed_ms,
                        status_label: status,
                        http: String::new(),
                        reason: reason.filter(|reason| !reason.is_empty()),
                        method: String::new(),
                        url: String::new(),
                        tags: vec![],
                        snippet: vec![],
                        snippet_path: None,
                    };
                    if let Some((name, cases)) = cases_by_suite.last_mut() {
                        if name == &suite_name {
                            cases.push(case);
                        } else {
                            cases_by_suite.push((suite_name.clone(), vec![case]));
                        }
                    } else {
                        cases_by_suite.push((suite_name.clone(), vec![case]));
                    }
                    rest = &rest[case_at + 20..];
                }
                _ => break,
            }
        }
        let mut run = 0;
        let mut ok = 0;
        let mut fail = 0;
        let mut skip = 0;
        let suites = cases_by_suite
            .into_iter()
            .map(|(suite, cases)| {
                let stats = timing_stats(
                    cases
                        .iter()
                        .map(|case| (case.status_label.as_str(), case.elapsed_ms)),
                );
                run += stats.run;
                ok += stats.ok;
                fail += stats.fail;
                skip += stats.skip;
                crate::report::test_view::SuiteTestView {
                    suite,
                    stats,
                    cases,
                }
            })
            .collect::<Vec<_>>();
        TestsReport {
            projects: vec![crate::report::test_view::ProjectTestView {
                project: "shop".into(),
                stats: TestTimingStats {
                    run,
                    ok,
                    fail,
                    skip,
                    ..TestTimingStats::default()
                },
                suites,
            }],
            all_tags: vec![],
        }
    }

    fn attr(tag: &str, name: &str) -> Option<String> {
        let key = format!("{name}=\"");
        let start = tag.find(&key)? + key.len();
        let end = tag[start..].find('"')? + start;
        Some(tag[start..end].to_string())
    }

    fn strip_ansi(value: &str) -> String {
        let mut out = String::new();
        let mut chars = value.chars();
        while let Some(ch) = chars.next() {
            if ch == '\u{1b}' {
                for next in chars.by_ref() {
                    if next == 'm' {
                        break;
                    }
                }
                continue;
            }
            out.push(ch);
        }
        out
    }
}
