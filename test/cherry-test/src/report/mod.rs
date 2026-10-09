mod adapter_view;
mod code_frame;
mod coverage_cell;
mod diff_view;
mod highlight;
mod html;
mod model;
mod terminal;
mod test_view;
mod theme;

pub use coverage_cell::{fn_cell_html, format_fn_cell, format_lines_cell, lines_cell_html};
pub use diff_view::{FileDiffSection, ModuleDiffSection, ProjectDiffSection};
pub use html::{open_html_report, should_open_report, write_html_report};
pub use model::{
    build_report_snapshot, format_status_line, IntegrationRow, ProjectReport, ReportSnapshot,
};
pub use test_view::{
    build_tests_report, derive_tags, format_timing_stats, format_timing_summary, tags_attr,
    TestCaseView, TestTimingStats, TestsReport,
};
pub use theme::{colorize_status_line, colors_enabled, html_class, Accent};

pub(crate) fn tree_depth_open(depth: u8) -> &'static str {
    if depth <= 2 {
        " open"
    } else {
        ""
    }
}

use std::path::Path;

use crate::config::TestKind;
use crate::coverage::{CoveragePaths, CoverageSummary};

#[derive(Debug, Clone, Default)]
pub struct PackageCoverage {
    pub unit: Option<CoverageSummary>,
    pub integration: Option<CoverageSummary>,
    pub merged: CoverageSummary,
}

#[derive(Debug, Clone, Default)]
pub struct CoverageTotals {
    pub unit: Option<CoverageSummary>,
    pub integration: Option<CoverageSummary>,
    pub merged: CoverageSummary,
}

#[derive(Debug, Clone, Default)]
pub struct ProjectRunResult {
    pub project_id: String,
    pub package: String,
    pub tests_passed: bool,
    pub coverage: PackageCoverage,
}

pub fn package_coverage_from_paths(
    cov_paths: &CoveragePaths,
    package: &str,
    ignore: &crate::coverage_ignore::CoverageIgnore,
) -> PackageCoverage {
    let unit_path = cov_paths.kind_lcov_file(package, TestKind::Unit);
    let integration_path = cov_paths.kind_lcov_file(package, TestKind::Integration);
    let merged_path = cov_paths.lcov_file(package);

    PackageCoverage {
        unit: optional_summary(&unit_path, ignore),
        integration: optional_summary(&integration_path, ignore),
        merged: if merged_path.exists() {
            crate::coverage::summary_for_lcov(&merged_path, ignore).unwrap_or_default()
        } else {
            CoverageSummary::default()
        },
    }
}

fn optional_summary(
    path: &Path,
    ignore: &crate::coverage_ignore::CoverageIgnore,
) -> Option<CoverageSummary> {
    if !path.exists() {
        return None;
    }
    Some(crate::coverage::summary_for_lcov(path, ignore).unwrap_or_default())
}

pub fn format_pct(summary: Option<&CoverageSummary>) -> String {
    match summary {
        Some(s) => format!("{:.1}%", s.lines_percent()),
        None => "-".into(),
    }
}

pub fn format_fn_pct(summary: Option<&CoverageSummary>) -> String {
    match summary {
        Some(s) => format!("{:.1}%", s.functions_percent()),
        None => "-".into(),
    }
}

pub fn format_summary_pct(summary: &CoverageSummary) -> String {
    if summary.lines_found == 0 && summary.functions_found == 0 {
        "-".into()
    } else {
        format!("{:.1}%", summary.lines_percent())
    }
}

/// Prints one finished phase's tests with their result, time and failure or skip reason.
pub fn print_phase_results(tests: &[cherry_test_common::adapter::TestResult]) {
    for line in terminal::format_phase_results(tests, theme::colors_enabled()) {
        println!("{line}");
    }
}

pub fn print_aggregate_report(snapshot: &ReportSnapshot, use_color: bool, verbose: bool) {
    let color = use_color && theme::colors_enabled();
    if verbose && !snapshot.tests_report.projects.is_empty() {
        println!();
        for line in terminal::format_verbose_lines(&snapshot.tests_report, color) {
            println!("{line}");
        }
        for line in terminal::format_verbose_closing(
            &snapshot.tests_report,
            &snapshot.crap,
            &snapshot.report_path,
            color,
        ) {
            println!("{line}");
        }
        println!();
        return;
    }
    for line in terminal::format_crap_lines(&snapshot.crap, color) {
        println!("{line}");
    }
    println!();
    let plain = format_status_line(snapshot, terminal::TERMINAL_WIDTH);
    let line = if color {
        colorize_status_line(&plain, snapshot)
    } else {
        plain
    };
    println!("{line}");
    println!();

    println!("projects");
    println!("{}", terminal::rule_line());
    println!("{}", terminal::format_projects_header());
    println!("{}", terminal::rule_line());

    for project in &snapshot.projects {
        println!("{}", terminal::format_project_row(project, color));
    }

    if snapshot.coverage_enabled {
        println!("{}", terminal::rule_line());
        println!("{}", terminal::format_projects_footer(snapshot, color));
    }

    if !snapshot.tests_report.projects.is_empty() {
        println!();
        println!("tests");
        for line in terminal::format_grouped_test_lines(&snapshot.tests_report, color) {
            println!("{line}");
        }
        println!();
        println!("mini");
        for line in terminal::format_mini_report_lines(&snapshot.tests_report, color) {
            println!("{line}");
        }
    } else if !snapshot.integration_rows.is_empty() {
        println!();
        println!("tests");
        println!("{}", terminal::rule_line());
        println!("{}", terminal::format_integration_header());
        println!("{}", terminal::rule_line());
        for row in &snapshot.integration_rows {
            println!("{}", terminal::format_integration_row(row, color));
        }
    }

    println!();
}

#[cfg(test)]
mod tests {
    use super::*;
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

    #[test]
    fn format_lines_cell_uses_diff_coverage_semantics() {
        let summary = sample_summary(12, 45, 2, 8);
        assert_eq!(
            format_lines_cell(Some(&summary), true),
            "12/45·12c·33u·26.7%"
        );
    }

    #[test]
    fn aggregate_diff_pct_from_summed_hits() {
        let a = sample_summary(10, 20, 0, 0);
        let b = sample_summary(5, 30, 0, 0);
        let merged = crate::coverage::merge_summaries(&[a, b]);
        assert_eq!(merged.lines_hit, 15);
        assert_eq!(merged.lines_found, 50);
        assert!((merged.lines_percent() - 30.0).abs() < 0.1);
    }

    #[test]
    fn format_pct_shows_dash_when_missing() {
        assert_eq!(format_pct(None), "-");
        assert_eq!(format_fn_pct(None), "-");
    }

    #[test]
    fn format_pct_shows_percentage_when_present() {
        let summary = sample_summary(65, 100, 5, 8);
        assert_eq!(format_pct(Some(&summary)), "65.0%");
        assert_eq!(format_fn_pct(Some(&summary)), "62.5%");
    }

    #[test]
    fn status_line_truncates_at_limit() {
        let snapshot = ReportSnapshot {
            run_id: "20260523-143022-a1b2c3".into(),
            overall_ok: true,
            packages_passed: 2,
            packages_total: 2,
            packages_failed: 0,
            integration_run: 0,
            integration_skipped: 1,
            integration_failed: 0,
            coverage_enabled: true,
            totals: CoverageTotals {
                unit: Some(sample_summary(40, 100, 4, 10)),
                integration: Some(sample_summary(0, 10, 0, 5)),
                merged: sample_summary(30, 100, 3, 10),
            },
            diff_coverage: CoverageTotals {
                merged: sample_summary(5, 10, 1, 2),
                ..Default::default()
            },
            projects: vec![ProjectReport {
                project_id: "mini".into(),
                package: "mini-rs-api".into(),
                tests_passed: true,
                coverage: PackageCoverage {
                    unit: Some(sample_summary(65, 100, 5, 8)),
                    integration: Some(sample_summary(0, 12, 0, 8)),
                    merged: sample_summary(30, 100, 3, 10),
                },
                diff_coverage: CoverageTotals::default(),
                changed_files: vec![],
            }],
            integration_rows: vec![],
            project_diff_sections: vec![],
            tests_report: crate::report::TestsReport::default(),
            crap: crate::crap::CrapReport::default(),
            report_path: PathBuf::from(
                "target/cherry-test/runs/20260523-143022-a1b2c3/reports/index.html",
            ),
        };
        let line = format_status_line(&snapshot, terminal::TERMINAL_WIDTH);
        assert_eq!(terminal::row_visible_width(&line), terminal::TERMINAL_WIDTH);
        assert!(line.contains("cherry-test OK"));
    }
}
