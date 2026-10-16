use std::collections::{BTreeMap, HashMap};
use std::path::{Path, PathBuf};

use crate::preflight::{DiscoveredTest, PreflightPlan};

use super::model::IntegrationRow;

#[derive(Debug, Clone, Copy, Default)]
pub struct TestTimingStats {
    pub run: usize,
    pub ok: usize,
    pub fail: usize,
    pub skip: usize,
    pub total_ms: u64,
    pub avg_ms: u64,
    pub min_ms: u64,
    pub max_ms: u64,
}

#[derive(Debug, Clone)]
pub struct SnippetLine {
    pub line_no: usize,
    pub text: String,
    pub highlight: bool,
}

#[derive(Debug, Clone)]
pub struct TestCaseView {
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
    pub snippet: Vec<SnippetLine>,
    /// File the snippet was read from, which decides its syntax highlighting.
    pub snippet_path: Option<PathBuf>,
}

#[derive(Debug, Clone)]
pub struct SuiteTestView {
    pub suite: String,
    pub stats: TestTimingStats,
    pub cases: Vec<TestCaseView>,
}

#[derive(Debug, Clone)]
pub struct ProjectTestView {
    pub project: String,
    pub stats: TestTimingStats,
    pub suites: Vec<SuiteTestView>,
}

#[derive(Debug, Clone, Default)]
pub struct TestsReport {
    pub projects: Vec<ProjectTestView>,
    pub all_tags: Vec<String>,
}

pub fn build_tests_report(
    rows: &[IntegrationRow],
    preflight: &PreflightPlan,
    workspace_root: &Path,
) -> TestsReport {
    let source_index = build_source_index(&preflight.discovered);
    let mut by_project: BTreeMap<String, BTreeMap<String, Vec<TestCaseView>>> = BTreeMap::new();
    let mut tag_set = std::collections::BTreeSet::new();

    for row in rows {
        let case = build_case_view(row, &source_index, workspace_root);
        for tag in &case.tags {
            tag_set.insert(tag.clone());
        }
        by_project
            .entry(case.project.clone())
            .or_default()
            .entry(case.suite.clone())
            .or_default()
            .push(case);
    }

    let mut projects = Vec::new();
    for (project, suites_map) in by_project {
        let mut suites = Vec::new();
        let mut project_cases = Vec::new();
        for (suite, mut cases) in suites_map {
            cases.sort_by(|a, b| a.test.cmp(&b.test));
            let stats = compute_stats(&cases);
            project_cases.extend(cases.clone());
            suites.push(SuiteTestView {
                suite,
                stats,
                cases,
            });
        }
        suites.sort_by(|a, b| a.suite.cmp(&b.suite));
        let stats = compute_stats(&project_cases);
        projects.push(ProjectTestView {
            project,
            stats,
            suites,
        });
    }

    TestsReport {
        projects,
        all_tags: tag_set.into_iter().collect(),
    }
}

pub fn tests_in_change(report: &TestsReport, changed: &[String]) -> TestsReport {
    if changed.is_empty() {
        return TestsReport::default();
    }
    let mut projects = Vec::new();
    let mut tags = std::collections::BTreeSet::new();
    for project in &report.projects {
        let mut suites = Vec::new();
        let mut project_cases = Vec::new();
        for suite in &project.suites {
            let cases = suite
                .cases
                .iter()
                .filter(|case| {
                    case.snippet_path.as_ref().is_some_and(|path| {
                        crate::coverage::path_matches_changed(&path.to_string_lossy(), changed)
                    })
                })
                .cloned()
                .collect::<Vec<_>>();
            if cases.is_empty() {
                continue;
            }
            for case in &cases {
                for tag in &case.tags {
                    tags.insert(tag.clone());
                }
            }
            project_cases.extend(cases.iter().cloned());
            suites.push(SuiteTestView {
                suite: suite.suite.clone(),
                stats: compute_stats(&cases),
                cases,
            });
        }
        if suites.is_empty() {
            continue;
        }
        projects.push(ProjectTestView {
            project: project.project.clone(),
            stats: compute_stats(&project_cases),
            suites,
        });
    }
    TestsReport {
        projects,
        all_tags: tags.into_iter().collect(),
    }
}

pub fn format_timing_stats(stats: &TestTimingStats) -> String {
    let timing = if stats.run > 0 {
        format!(
            " · {}ms total · {}ms avg · {}ms fastest · {}ms slowest",
            stats.total_ms, stats.avg_ms, stats.min_ms, stats.max_ms
        )
    } else {
        String::new()
    };
    format!(
        "{} run · {} ok · {} fail · {} skip{timing}",
        stats.run, stats.ok, stats.fail, stats.skip
    )
}

pub fn format_timing_summary(label: &str, stats: &TestTimingStats) -> String {
    format!("{label} · {}", format_timing_stats(stats))
}

fn build_source_index(discovered: &[DiscoveredTest]) -> HashMap<String, &DiscoveredTest> {
    discovered.iter().map(|d| (d.function.clone(), d)).collect()
}

fn build_case_view(
    row: &IntegrationRow,
    source_index: &HashMap<String, &DiscoveredTest>,
    workspace_root: &Path,
) -> TestCaseView {
    let (snippet, snippet_path) = load_snippet(row, source_index, workspace_root);
    TestCaseView {
        project: row.project.clone(),
        suite: row.suite.clone(),
        test: row.test.clone(),
        full_name: row.full_name.clone(),
        scope: row.scope.clone(),
        target: row.target.clone(),
        elapsed_ms: row.elapsed_ms,
        status_label: row.status_label.clone(),
        http: row.http.clone(),
        reason: row.reason.clone(),
        method: row.method.clone(),
        url: row.url.clone(),
        tags: row.tags.clone(),
        snippet,
        snippet_path,
    }
}

const FAILURE_CONTEXT_LINES: usize = 6;

fn load_snippet(
    row: &IntegrationRow,
    source_index: &HashMap<String, &DiscoveredTest>,
    workspace_root: &Path,
) -> (Vec<SnippetLine>, Option<PathBuf>) {
    if let Some((source, line)) = &row.location {
        if let (Ok(content), Ok(line)) = (std::fs::read_to_string(source), usize::try_from(*line)) {
            return (
                extract_snippet(&content, line, FAILURE_CONTEXT_LINES),
                Some(source.clone()),
            );
        }
    }
    let Some(discovered) = source_index.get(&row.full_name) else {
        return (Vec::new(), None);
    };
    let path = workspace_root.join(&discovered.source_path);
    let content = match std::fs::read_to_string(&path) {
        Ok(c) => c,
        Err(_) => return (Vec::new(), None),
    };
    (
        extract_snippet(&content, discovered.fn_line, 20),
        Some(discovered.source_path.clone()),
    )
}

pub fn extract_snippet(content: &str, fn_line: usize, context: usize) -> Vec<SnippetLine> {
    let lines: Vec<&str> = content.lines().collect();
    if fn_line == 0 || fn_line > lines.len() {
        return Vec::new();
    }
    let center = fn_line - 1;
    let start = center.saturating_sub(context);
    let end = (center + context + 1).min(lines.len());
    (start..end)
        .map(|idx| SnippetLine {
            line_no: idx + 1,
            text: lines[idx].to_string(),
            highlight: idx + 1 == fn_line,
        })
        .collect()
}

fn compute_stats(cases: &[TestCaseView]) -> TestTimingStats {
    timing_stats(
        cases
            .iter()
            .map(|case| (case.status_label.as_str(), case.elapsed_ms)),
    )
}

pub(crate) fn timing_stats<'a>(items: impl IntoIterator<Item = (&'a str, u64)>) -> TestTimingStats {
    let mut stats = TestTimingStats::default();
    let mut timed_ms = Vec::new();
    for (status, elapsed_ms) in items {
        match status {
            "skipped" => stats.skip += 1,
            "FAIL" => {
                stats.run += 1;
                stats.fail += 1;
                if elapsed_ms > 0 {
                    timed_ms.push(elapsed_ms);
                }
            }
            _ => {
                stats.run += 1;
                stats.ok += 1;
                if elapsed_ms > 0 {
                    timed_ms.push(elapsed_ms);
                }
            }
        }
    }
    if timed_ms.is_empty() {
        return stats;
    }
    stats.total_ms = timed_ms.iter().sum();
    stats.min_ms = timed_ms.iter().copied().min().unwrap_or(0);
    stats.max_ms = timed_ms.iter().copied().max().unwrap_or(0);
    stats.avg_ms = stats.total_ms / timed_ms.len() as u64;
    stats
}

pub fn derive_tags(project: &str, suite: &str, scope: &str, test: &str) -> Vec<String> {
    let mut tags = vec![project.to_string(), suite.to_string(), scope.to_string()];
    for part in test.split('_') {
        if !part.is_empty() {
            tags.push(part.to_string());
        }
    }
    tags.sort();
    tags.dedup();
    tags
}

pub fn tags_attr(tags: &[String]) -> String {
    tags.join(" ")
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;

    #[test]
    fn tests_in_change_keep_cases_whose_source_file_is_in_the_diff() {
        let report = TestsReport {
            projects: vec![ProjectTestView {
                project: "shop".into(),
                stats: TestTimingStats::default(),
                suites: vec![SuiteTestView {
                    suite: "checkout".into(),
                    stats: TestTimingStats::default(),
                    cases: vec![
                        case(
                            "applies_the_mug_discount",
                            "shop-rs-api/tests/integration.rs",
                        ),
                        case("lists_seeded_products", "shop-rs-api/tests/integration.rs"),
                        case("formats_dollars", "shop-ts-lib-cart/src/cart.test.ts"),
                    ],
                }],
            }],
            all_tags: vec!["checkout".into()],
        };
        assert!(tests_in_change(&report, &[]).projects.is_empty());
        let changed = tests_in_change(&report, &["shop-rs-api/tests/integration.rs".into()]);
        assert_eq!(changed.projects.len(), 1);
        assert_eq!(changed.projects[0].suites[0].cases.len(), 2);
        assert_eq!(
            changed.projects[0].suites[0].cases[0].test,
            "applies_the_mug_discount"
        );
    }

    fn case(name: &str, path: &str) -> TestCaseView {
        TestCaseView {
            project: "shop".into(),
            suite: "checkout".into(),
            test: name.into(),
            full_name: name.into(),
            scope: "shop_checkout".into(),
            target: "shop.api".into(),
            elapsed_ms: 1,
            status_label: "ok".into(),
            http: "201".into(),
            reason: None,
            method: "POST".into(),
            url: "/v1/carts/1/checkout".into(),
            tags: vec!["checkout".into()],
            snippet: Vec::new(),
            snippet_path: Some(PathBuf::from(path)),
        }
    }

    fn sample_row(status: &str, ms: u64) -> IntegrationRow {
        IntegrationRow {
            project: "mini".into(),
            suite: "wallet".into(),
            test: "get_usdt".into(),
            full_name: "mini_project_wallet_suite_get_usdt_test".into(),
            scope: "mini_wallet".into(),
            target: "mini.api".into(),
            elapsed_ms: ms,
            status_label: status.into(),
            http: if status == "skipped" {
                "-".into()
            } else if status == "FAIL" {
                "500".into()
            } else {
                "200".into()
            },
            reason: if status == "skipped" {
                Some("no docker".into())
            } else if status == "FAIL" {
                Some("HTTP 500".into())
            } else {
                None
            },
            method: "GET".into(),
            url: "/health".into(),
            tags: derive_tags("mini", "wallet", "mini_wallet", "get_usdt"),
            location: None,
        }
    }

    #[test]
    fn format_timing_summary_includes_label_and_stats() {
        let stats = TestTimingStats {
            run: 2,
            ok: 1,
            fail: 1,
            skip: 0,
            total_ms: 40,
            avg_ms: 20,
            min_ms: 10,
            max_ms: 30,
        };
        assert_eq!(
            format_timing_stats(&stats),
            "2 run · 1 ok · 1 fail · 0 skip · 40ms total · 20ms avg · 10ms fastest · 30ms slowest"
        );
        assert_eq!(
            format_timing_summary("wallet", &stats),
            "wallet · 2 run · 1 ok · 1 fail · 0 skip · 40ms total · 20ms avg · 10ms fastest · 30ms slowest"
        );
    }

    #[test]
    fn groups_by_project_and_suite() {
        let rows = vec![
            sample_row("ok", 10),
            IntegrationRow {
                test: "other".into(),
                ..sample_row("FAIL", 20)
            },
        ];
        let report = build_tests_report(&rows, &PreflightPlan::default(), Path::new("."));
        assert_eq!(report.projects.len(), 1);
        assert_eq!(report.projects[0].suites.len(), 1);
        assert_eq!(report.projects[0].suites[0].cases.len(), 2);
    }

    #[test]
    fn stats_exclude_skipped_from_timing() {
        let rows = vec![
            sample_row("ok", 10),
            sample_row("skipped", 0),
            sample_row("FAIL", 30),
        ];
        let report = build_tests_report(&rows, &PreflightPlan::default(), Path::new("."));
        let stats = &report.projects[0].stats;
        assert_eq!(stats.run, 2);
        assert_eq!(stats.ok, 1);
        assert_eq!(stats.fail, 1);
        assert_eq!(stats.skip, 1);
        assert_eq!(stats.total_ms, 40);
        assert_eq!(stats.avg_ms, 20);
        assert_eq!(stats.min_ms, 10);
        assert_eq!(stats.max_ms, 30);
    }

    #[test]
    fn extract_snippet_highlights_fn_line() {
        let src = "line1\nline2\nline3\nline4\nline5\nline6\nline7\n";
        let snippet = extract_snippet(src, 4, 2);
        assert_eq!(snippet.len(), 5);
        assert!(snippet.iter().any(|l| l.highlight && l.line_no == 4));
    }

    #[test]
    fn snippet_shows_the_failed_assertion_line() {
        let dir = tempfile::tempdir().unwrap();
        let file = dir.path().join("tests.rs");
        let source: String = (1..=30).map(|n| format!("line {n}\n")).collect();
        std::fs::write(&file, source).unwrap();
        let mut row = sample_row("FAIL", 5);
        row.location = Some((file, 17));
        let report = build_tests_report(&[row], &PreflightPlan::default(), dir.path());
        let snippet = &report.projects[0].suites[0].cases[0].snippet;
        let marked: Vec<usize> = snippet
            .iter()
            .filter(|l| l.highlight)
            .map(|l| l.line_no)
            .collect();
        assert_eq!(marked, vec![17]);
    }

    #[test]
    fn snippet_loaded_from_discovered_source() {
        let dir = std::env::temp_dir().join(format!("cherry-test-snippet-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let rel = PathBuf::from("tests/integration.rs");
        let path = dir.join(&rel);
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        std::fs::write(
            &path,
            "#[cherry_test(scope = \"none\")]\nasync fn mini_project_wallet_suite_get_usdt_test() {}\n",
        )
        .unwrap();

        let preflight = PreflightPlan {
            discovered: vec![DiscoveredTest {
                package: "mini-rs-api".into(),
                function: "mini_project_wallet_suite_get_usdt_test".into(),
                attr_scope: None,
                attr_auth: None,
                effective_scope: "none".into(),
                project: "mini".into(),
                suite: "wallet".into(),
                test: "get_usdt".into(),
                source_path: rel,
                fn_line: 2,
            }],
            ..Default::default()
        };
        let row = sample_row("FAIL", 5);
        let report = build_tests_report(&[row], &preflight, &dir);
        let snippet = &report.projects[0].suites[0].cases[0].snippet;
        assert!(!snippet.is_empty());
        assert!(snippet.iter().any(|l| l.highlight));
        let ok_row = sample_row("ok", 5);
        let ok_report = build_tests_report(&[ok_row], &preflight, &dir);
        let ok_snippet = &ok_report.projects[0].suites[0].cases[0].snippet;
        assert!(!ok_snippet.is_empty());
        let _ = std::fs::remove_dir_all(&dir);
    }
}
