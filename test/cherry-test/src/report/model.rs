use std::collections::BTreeSet;
use std::path::{Path, PathBuf};

use anyhow::Result;
use serde_json::Value;

use super::diff_view::{build_project_diff_sections, ProjectDiffSection};
use super::test_view::{build_tests_report, TestsReport};
use super::{CoverageTotals, PackageCoverage};
use crate::config::TestKind;
use crate::coverage::{
    file_coverage_for_project, merge_summaries, parse_lcov_files, summary_for_files,
    ChangedFileCoverage, CoveragePaths, CoverageSummary,
};
use crate::coverage_ignore::CoverageIgnore;
use crate::crap::CrapReport;
use crate::discover::ProjectGroup;
use crate::git_diff;
use crate::preflight::PreflightPlan;
use crate::run_dir::RunDir;
use crate::runner::RunOutcome;
use crate::vitest_report;
use cherry_test_core::TestItConfig;

#[derive(Debug, Clone)]
pub struct ReportSnapshot {
    pub run_id: String,
    pub overall_ok: bool,
    pub packages_passed: usize,
    pub packages_total: usize,
    pub packages_failed: usize,
    pub integration_run: usize,
    pub integration_skipped: usize,
    pub integration_failed: usize,
    pub coverage_enabled: bool,
    pub totals: CoverageTotals,
    pub diff_coverage: CoverageTotals,
    pub projects: Vec<ProjectReport>,
    pub project_diff_sections: Vec<ProjectDiffSection>,
    pub integration_rows: Vec<IntegrationRow>,
    pub tests_report: TestsReport,
    pub crap: CrapReport,
    pub report_path: PathBuf,
}

#[derive(Debug, Clone)]
pub struct ProjectReport {
    pub project_id: String,
    pub package: String,
    pub tests_passed: bool,
    pub coverage: PackageCoverage,
    pub diff_coverage: CoverageTotals,
    pub changed_files: Vec<ChangedFileCoverage>,
}

#[derive(Debug, Clone)]
pub struct IntegrationRow {
    pub project: String,
    pub suite: String,
    pub test: String,
    pub full_name: String,
    pub scope: String,
    pub target: String,
    pub elapsed_ms: u64,
    pub status_label: String,
    pub http: String,
    pub reason: Option<String>,
    pub method: String,
    pub url: String,
    pub tags: Vec<String>,
    /// File and line the runner reported for a failure, when it named one.
    pub location: Option<(std::path::PathBuf, u32)>,
}

pub fn build_report_snapshot(
    workspace_root: &Path,
    run_dir: &RunDir,
    projects: &[ProjectGroup],
    outcome: &RunOutcome,
    preflight: &PreflightPlan,
    totals: &CoverageTotals,
    config: &TestItConfig,
    coverage_enabled: bool,
    report_crap: bool,
) -> Result<ReportSnapshot> {
    let changed_by_project = git_diff::changed_files_by_project(workspace_root, projects, config)?;
    let coverage_ignore = CoverageIgnore::load(workspace_root, config);

    let integration_rows = load_integration_rows(&run_dir.reports)?;
    let (integration_run, integration_skipped, integration_failed) =
        integration_stats(&integration_rows, preflight);
    let exercised_projects =
        exercised_projects_for_coverage(&integration_rows, config, &preflight.scopes_to_boot);

    let mut project_reports = Vec::new();
    for result in &outcome.results {
        let git_changed = changed_by_project
            .get(&result.project_id)
            .cloned()
            .unwrap_or_default();
        let project = projects
            .iter()
            .find(|p| p.id == result.project_id)
            .ok_or_else(|| anyhow::anyhow!("unknown project {}", result.project_id))?;
        let prefixes = git_diff::package_prefixes(workspace_root, config, project);
        let cov_paths = CoveragePaths::new(run_dir, &result.project_id);
        let unit_lcov = cov_paths.kind_lcov_file(&result.package, TestKind::Unit);
        let it_lcov = cov_paths.kind_lcov_file(&result.package, TestKind::Integration);
        let merged_lcov = cov_paths.lcov_file(&result.package);

        let unit_files = if unit_lcov.exists() {
            coverage_ignore.filter_file_coverage(parse_lcov_files(&unit_lcov)?)
        } else {
            Vec::new()
        };
        let it_files = if it_lcov.exists() {
            coverage_ignore.filter_file_coverage(parse_lcov_files(&it_lcov)?)
        } else {
            Vec::new()
        };
        let merged_files = if merged_lcov.exists() {
            coverage_ignore.filter_file_coverage(parse_lcov_files(&merged_lcov)?)
        } else {
            Vec::new()
        };

        let changed = if exercised_projects.contains(&result.project_id) {
            git_diff::expand_changed_with_lcov_sources(git_changed, &merged_files, &prefixes)
        } else {
            git_changed
        };

        let diff_unit = if changed.is_empty() {
            None
        } else {
            Some(summary_for_files(&unit_files, &changed, &coverage_ignore))
        };
        let diff_it = if changed.is_empty() {
            None
        } else {
            Some(summary_for_files(&it_files, &changed, &coverage_ignore))
        };
        let diff_merged = if changed.is_empty() {
            CoverageSummary::default()
        } else {
            summary_for_files(&merged_files, &changed, &coverage_ignore)
        };

        let changed_files = if coverage_enabled {
            file_coverage_for_project(
                unit_lcov.exists().then_some(unit_lcov.as_path()),
                it_lcov.exists().then_some(it_lcov.as_path()),
                merged_lcov.exists().then_some(merged_lcov.as_path()),
                &changed,
                &coverage_ignore,
            )?
        } else {
            changed
                .iter()
                .map(|path| ChangedFileCoverage {
                    path: path.clone(),
                    unit: None,
                    integration: None,
                    merged: CoverageSummary::default(),
                })
                .collect()
        };

        project_reports.push(ProjectReport {
            project_id: result.project_id.clone(),
            package: result.package.clone(),
            tests_passed: result.tests_passed,
            coverage: result.coverage.clone(),
            diff_coverage: CoverageTotals {
                unit: diff_unit,
                integration: diff_it,
                merged: diff_merged,
            },
            changed_files,
        });
    }

    let diff_coverage = aggregate_diff_coverage(&project_reports);
    let project_diff_sections = build_project_diff_sections(
        workspace_root,
        run_dir,
        config,
        projects,
        &project_reports,
        &coverage_ignore,
    )?;

    let packages_passed = outcome.results.iter().filter(|r| r.tests_passed).count();
    let packages_failed = outcome.results.len().saturating_sub(packages_passed);
    let tests_report = build_tests_report(&integration_rows, preflight, workspace_root);
    Ok(ReportSnapshot {
        run_id: run_dir.id.clone(),
        overall_ok: !outcome.failed,
        packages_passed,
        packages_total: outcome.results.len(),
        packages_failed,
        integration_run,
        integration_skipped,
        integration_failed,
        coverage_enabled,
        totals: totals.clone(),
        diff_coverage,
        projects: project_reports,
        project_diff_sections,
        integration_rows,
        tests_report,
        crap: crap_report(
            workspace_root,
            run_dir,
            &coverage_ignore,
            report_crap,
            config,
        )?,
        report_path: run_dir.report_html(),
    })
}

fn crap_report(
    workspace_root: &Path,
    run_dir: &RunDir,
    coverage_ignore: &crate::coverage_ignore::CoverageIgnore,
    report_crap: bool,
    config: &TestItConfig,
) -> Result<CrapReport> {
    if !report_crap {
        return Ok(CrapReport::default());
    }
    let files = coverage_ignore.filter_line_coverage(crate::coverage::parse_lcov_line_coverage(
        &run_dir.merged_lcov(),
    )?);
    Ok(crate::crap::score_report(
        workspace_root,
        &files,
        &crap_project_roots(workspace_root, config),
    ))
}

fn crap_project_roots(
    workspace_root: &Path,
    config: &TestItConfig,
) -> Vec<crate::crap::CrapProjectRoot> {
    config
        .projects
        .iter()
        .map(|(id, project)| crate::crap::CrapProjectRoot {
            id: id.clone(),
            path: workspace_root.join(project.path.as_deref().unwrap_or(id.as_str())),
        })
        .collect()
}

fn exercised_projects_for_coverage(
    integration_rows: &[IntegrationRow],
    config: &TestItConfig,
    scopes_to_boot: &[String],
) -> BTreeSet<String> {
    let mut out = BTreeSet::new();
    for row in integration_rows {
        if row.target == "-" {
            continue;
        }
        if let Some(project) = row.target.split('.').next() {
            if !project.is_empty() {
                out.insert(project.to_string());
            }
        }
    }
    for scope in scopes_to_boot {
        if let Ok(projects) = cherry_test_core::resolve_projects(config, scope) {
            out.extend(projects);
        }
    }
    out
}

fn aggregate_diff_coverage(projects: &[ProjectReport]) -> CoverageTotals {
    let unit: Vec<_> = projects
        .iter()
        .filter_map(|p| p.diff_coverage.unit.clone())
        .collect();
    let integration: Vec<_> = projects
        .iter()
        .filter_map(|p| p.diff_coverage.integration.clone())
        .collect();
    let merged: Vec<_> = projects
        .iter()
        .map(|p| p.diff_coverage.merged.clone())
        .collect();

    CoverageTotals {
        unit: if unit.is_empty() {
            None
        } else {
            Some(merge_summaries(&unit))
        },
        integration: if integration.is_empty() {
            None
        } else {
            Some(merge_summaries(&integration))
        },
        merged: merge_summaries(&merged),
    }
}

fn integration_stats(rows: &[IntegrationRow], preflight: &PreflightPlan) -> (usize, usize, usize) {
    let mut run = 0;
    let mut skipped = 0;
    let mut failed = 0;

    for row in rows {
        match row.status_label.as_str() {
            "skipped" => skipped += 1,
            "FAIL" => {
                run += 1;
                failed += 1;
            }
            _ => run += 1,
        }
    }

    if rows.is_empty() && !preflight.skipped.is_empty() {
        skipped = preflight.skipped.len();
    }

    (run, skipped, failed)
}

fn load_integration_rows(report_dir: &Path) -> Result<Vec<IntegrationRow>> {
    let mut rows = cherry_test_core::report::read_all_records(report_dir)?;
    rows.extend(vitest_report::read_unit_rows(report_dir)?);
    rows.extend(crate::adapters::read_rows(report_dir)?);
    let mut tests = std::collections::BTreeMap::<String, IntegrationRow>::new();
    for row in rows.into_iter().filter_map(parse_integration_row) {
        if let Some(test) = tests.get_mut(&row.full_name) {
            test.elapsed_ms = test.elapsed_ms.saturating_add(row.elapsed_ms);
            if row.status_label == "FAIL"
                || test.status_label != "FAIL" && row.status_label == "skipped"
            {
                test.status_label = row.status_label;
                test.reason = row.reason;
            }
            for (existing, value) in [(&mut test.method, row.method), (&mut test.url, row.url)] {
                if !value.is_empty() && !existing.split('\n').any(|part| part == value) {
                    if !existing.is_empty() {
                        existing.push('\n');
                    }
                    existing.push_str(&value);
                }
            }
        } else {
            tests.insert(row.full_name.clone(), row);
        }
    }
    Ok(tests.into_values().collect())
}

fn parse_integration_row(row: Value) -> Option<IntegrationRow> {
    let project = row["project"].as_str()?.to_string();
    let suite = row["suite"].as_str()?.to_string();
    let test = row["test"].as_str()?.to_string();
    let scope = row["scope"].as_str().unwrap_or("").to_string();
    let full_name = row["full_name"]
        .as_str()
        .map(str::to_string)
        .unwrap_or_else(|| format!("{project}_project_{suite}_suite_{test}_test"));
    let target_project = row["target_project"].as_str().unwrap_or("");
    let target_service = row["target_service"].as_str().unwrap_or("");
    let elapsed_ms = row["elapsed_ms"].as_u64().unwrap_or(0);
    let method = row["method"].as_str().unwrap_or("").to_string();
    let url = row["url"].as_str().unwrap_or("").to_string();
    let status_field = &row["status"];
    let tags = super::test_view::derive_tags(&project, &suite, &scope, &test);

    let target = if target_project.is_empty() {
        "-".to_string()
    } else {
        format!("{target_project}.{target_service}")
    };

    let (status_label, http, reason) = if status_field.as_str() == Some("skipped") {
        (
            "skipped".to_string(),
            row["reason"].as_str().unwrap_or("").to_string(),
            row["reason"]
                .as_str()
                .map(str::to_string)
                .filter(|r| !r.is_empty()),
        )
    } else if let Some(label) = status_field.as_str() {
        let reason = row["reason"]
            .as_str()
            .map(str::to_string)
            .filter(|r| !r.is_empty());
        (
            label.to_string(),
            if label == "ok" {
                "ok".into()
            } else {
                label.into()
            },
            reason,
        )
    } else {
        let code = status_field.as_u64().unwrap_or(0);
        let http = if code == 0 {
            "ERR".to_string()
        } else {
            code.to_string()
        };
        let label = match row["passed"].as_bool() {
            Some(true) => "ok".to_string(),
            Some(false) => "FAIL".to_string(),
            None => {
                if code >= 200 && code < 300 {
                    "ok".to_string()
                } else {
                    "FAIL".to_string()
                }
            }
        };
        let reason = if label == "FAIL" {
            row["reason"]
                .as_str()
                .map(str::to_string)
                .filter(|r| !r.is_empty())
                .or_else(|| fail_reason(code, row["response_body"].as_str().unwrap_or("")))
        } else {
            None
        };
        (label, http, reason)
    };

    let location = row["source"]
        .as_str()
        .zip(
            row["line"]
                .as_u64()
                .and_then(|line| u32::try_from(line).ok()),
        )
        .map(|(source, line)| (std::path::PathBuf::from(source), line));
    Some(IntegrationRow {
        project,
        suite,
        test,
        full_name,
        scope,
        target,
        elapsed_ms,
        status_label,
        http,
        reason,
        method,
        url,
        tags,
        location,
    })
}

fn fail_reason(code: u64, body: &str) -> Option<String> {
    let prefix = if code == 0 {
        "HTTP ERR".to_string()
    } else {
        format!("HTTP {code}")
    };
    let trimmed = body.trim();
    if trimmed.is_empty() {
        Some(prefix)
    } else {
        let snippet: String = trimmed.chars().take(500).collect();
        Some(format!("{prefix}: {snippet}"))
    }
}

pub fn format_status_line(snapshot: &ReportSnapshot, max_len: usize) -> String {
    let line = super::terminal::format_status_row(snapshot);
    truncate_line(&line, max_len)
}

fn truncate_line(line: &str, max_len: usize) -> String {
    if line.chars().count() <= max_len {
        return line.to_string();
    }
    if max_len <= 3 {
        return "...".chars().take(max_len).collect();
    }
    format!("{}...", line.chars().take(max_len - 3).collect::<String>())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn status_line_respects_max_length() {
        let snapshot = ReportSnapshot {
            run_id: "20260523-143022-a1b2c3".into(),
            overall_ok: true,
            packages_passed: 1,
            packages_total: 1,
            packages_failed: 0,
            integration_run: 0,
            integration_skipped: 0,
            integration_failed: 0,
            coverage_enabled: false,
            totals: CoverageTotals::default(),
            diff_coverage: CoverageTotals::default(),
            projects: vec![],
            project_diff_sections: vec![],
            integration_rows: vec![],
            tests_report: TestsReport::default(),
            crap: crate::crap::CrapReport::default(),
            report_path: PathBuf::from(
                "target/cherry-test/runs/20260523-143022-a1b2c3/reports/index.html",
            ),
        };
        let line = format_status_line(&snapshot, 80);
        assert!(line.chars().count() <= 80);
    }

    #[test]
    fn keeps_the_failure_location_of_a_row() {
        let row = integration_row(serde_json::json!({
            "status": "FAIL", "source": "/w/src/tests.rs", "line": 217
        }));
        assert_eq!(row.location, Some((PathBuf::from("/w/src/tests.rs"), 217)));
    }

    #[test]
    fn leaves_a_row_without_a_line_unlocated() {
        let row =
            integration_row(serde_json::json!({ "status": "FAIL", "source": "/w/src/tests.rs" }));
        assert_eq!(row.location, None);
    }

    fn integration_row(extra: serde_json::Value) -> IntegrationRow {
        let mut row = serde_json::json!({
            "project": "mini",
            "suite": "wallet",
            "test": "bad_address_returns_400",
            "full_name": "mini_project_wallet_suite_bad_address_returns_400_test",
            "target_project": "mini",
            "target_service": "api",
            "method": "GET",
            "url": "http://127.0.0.1/x",
        });
        let map = row.as_object_mut().unwrap();
        for (key, value) in extra.as_object().unwrap() {
            map.insert(key.clone(), value.clone());
        }
        parse_integration_row(row).expect("row")
    }

    #[test]
    fn negative_test_with_4xx_response_is_ok_when_passed() {
        let row = integration_row(serde_json::json!({ "status": 400, "passed": true }));
        assert_eq!(row.status_label, "ok");
        assert_eq!(row.http, "400");
        assert!(row.reason.is_none());
    }

    #[test]
    fn counts_one_test_for_multiple_http_records_and_its_adapter_result() {
        let root = tempfile::tempdir().unwrap();
        let integration = root.path().join("integration");
        std::fs::create_dir(&integration).unwrap();
        let name = "demo_project_http_suite_health_test";
        let records = [
            serde_json::json!({"project":"demo","suite":"http","test":"health","full_name":name,"status":200,"passed":true,"elapsed_ms":3}),
            serde_json::json!({"project":"demo","suite":"http","test":"health","full_name":name,"status":500,"passed":false,"elapsed_ms":7,"reason":"unavailable"}),
        ];
        std::fs::write(
            integration.join("api.jsonl"),
            records
                .iter()
                .map(|record| format!("{record}\n"))
                .collect::<String>(),
        )
        .unwrap();
        let adapter = root.path().join("adapters/demo/api/integration");
        std::fs::create_dir_all(&adapter).unwrap();
        std::fs::write(adapter.join("result.json"), serde_json::json!({"project":"demo","package":"api","phase":"integration","result":{"protocol":1,"success":false,"tests":[{"name":name,"status":"failed","duration_ms":0,"message":"failed assertion","attachments":[]}],"coverage":[],"diagnostics":[]}}).to_string()).unwrap();
        let rows = load_integration_rows(root.path()).unwrap();
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].status_label, "FAIL");
        assert_eq!(rows[0].elapsed_ms, 10);
        assert_eq!(
            integration_stats(&rows, &PreflightPlan::default()),
            (1, 0, 1)
        );
    }

    #[test]
    fn failed_test_is_fail_with_reason_even_on_2xx() {
        let row = integration_row(serde_json::json!({
            "status": 200,
            "passed": false,
            "reason": "expected HTTP 201, got 200",
        }));
        assert_eq!(row.status_label, "FAIL");
        assert_eq!(row.reason.as_deref(), Some("expected HTTP 201, got 200"));
    }

    #[test]
    fn missing_passed_falls_back_to_status_heuristic() {
        let ok = integration_row(serde_json::json!({ "status": 200 }));
        assert_eq!(ok.status_label, "ok");
        let fail = integration_row(serde_json::json!({ "status": 500 }));
        assert_eq!(fail.status_label, "FAIL");
    }
}
