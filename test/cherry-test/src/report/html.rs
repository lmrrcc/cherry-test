use std::fs;
use std::path::Path;
use std::process::Command;

use anyhow::Context;
use cherry_test_core::Defaults;

use crate::coverage::CoverageSummary;
use crate::report::diff_view::{
    DiffTag, FileDiffSection, LineCov, ModuleDiffSection, ProjectDiffSection,
};
use crate::report::highlight::{
    highlight_lang_for_path, highlight_linked, snippet_language, JumpIndex,
};
use crate::report::model::ReportSnapshot;
use crate::report::theme::{self, Accent};
use crate::report::{fn_cell_html, lines_cell_html, tree_depth_open};
use cherry_test_common::report_shell::diff_row_html;

pub fn write_html_report(snapshot: &ReportSnapshot) -> anyhow::Result<()> {
    let jumps = match snapshot.report_path.parent() {
        Some(parent) => {
            fs::create_dir_all(parent).with_context(|| format!("create {}", parent.display()))?;
            super::adapter_view::write(parent)?
        }
        None => JumpIndex::default(),
    };
    fs::write(&snapshot.report_path, render_report(snapshot, &jumps))
        .with_context(|| format!("write {}", snapshot.report_path.display()))?;
    Ok(())
}

pub fn is_ci() -> bool {
    ci_env_set("CI")
        || ci_env_set("GITHUB_ACTIONS")
        || ci_env_set("GITLAB_CI")
        || ci_env_set("BUILD_ID")
}

fn ci_env_set(name: &str) -> bool {
    std::env::var(name)
        .map(|v| !v.is_empty() && v != "0" && !v.eq_ignore_ascii_case("false"))
        .unwrap_or(false)
}

fn skip_open_env() -> bool {
    std::env::var("CHERRY_TEST_NO_OPEN")
        .map(|v| v == "1" || v.eq_ignore_ascii_case("true"))
        .unwrap_or(false)
}

pub fn should_open_report(no_open: bool, defaults: &Defaults) -> bool {
    should_open_report_with(no_open, defaults.open_report, is_ci(), skip_open_env())
}

pub fn should_open_report_with(
    no_open: bool,
    open_report: bool,
    ci: bool,
    no_open_env: bool,
) -> bool {
    if no_open || !open_report || no_open_env || ci {
        return false;
    }
    true
}

pub fn open_html_report(path: &Path) -> anyhow::Result<()> {
    if !path.exists() {
        anyhow::bail!("html report not found at {}", path.display());
    }
    let status = open_command(path)
        .status()
        .with_context(|| format!("open {}", path.display()))?;
    if !status.success() {
        anyhow::bail!("open {} failed", path.display());
    }
    Ok(())
}

fn open_command(path: &Path) -> Command {
    #[cfg(target_os = "macos")]
    {
        let mut cmd = Command::new("open");
        cmd.arg(path);
        return cmd;
    }
    #[cfg(target_os = "linux")]
    {
        let mut cmd = Command::new("xdg-open");
        cmd.arg(path);
        return cmd;
    }
    #[cfg(target_os = "windows")]
    {
        let mut cmd = Command::new("cmd");
        cmd.args(["/C", "start", ""]).arg(path);
        return cmd;
    }
    #[cfg(not(any(target_os = "macos", target_os = "linux", target_os = "windows")))]
    {
        let mut cmd = Command::new("xdg-open");
        cmd.arg(path);
        cmd
    }
}

fn render_html(snapshot: &ReportSnapshot) -> String {
    render_report(snapshot, &JumpIndex::default())
}

fn render_report(snapshot: &ReportSnapshot, jumps: &JumpIndex) -> String {
    let status = format_status_html(snapshot);
    let mut body = String::from("<section id=\"projects_summary\"><h2>projects</h2>");
    body.push_str(&render_projects_table(snapshot));
    body.push_str("</section><p><a href=\"phases.html\">Test phases, screenshots, logs and source coverage</a></p>");
    body.push_str(&render_tests_section(snapshot, jumps));
    body.push_str(&render_project_sections(snapshot, jumps));
    if snapshot.crap.enabled {
        body.push_str(&render_crap_section(snapshot));
    }
    cherry_test_common::report_shell::render_combined_report_document(
        &format!("{} report", crate::COMMAND_NAME),
        &status,
        &body,
    )
}

fn render_crap_section(snapshot: &ReportSnapshot) -> String {
    let totals = &snapshot.crap.totals;
    let mut html = String::from("<section id=\"crap\"><h2>CRAP</h2>");
    html.push_str("<p class=\"muted\">Cyclomatic complexity and the fraction of measured lines in each function. CRAP = complexity^2 * (1 - coverage)^3 + complexity. This is line coverage, not basis-path coverage. The aggregate LCOV percentage is not a CRAP score.</p>");
    html.push_str(&format!(
        "<p>{} functions · worst {:.1} · {} at or above {:.0} ({:.1}%) · crap load {:.1}</p>",
        totals.functions,
        totals.worst_crap,
        totals.crappy_functions,
        crate::crap::CRAP_THRESHOLD,
        totals.crappy_percent,
        totals.crap_load
    ));
    html.push_str(&format!(
        "<p>complexity {} · total CRAP {:.1} · mean {:.1} · median {:.1}</p>",
        totals.complexity, totals.total_crap, totals.mean_crap, totals.median_crap
    ));
    if !snapshot.crap.projects.is_empty() {
        html.push_str("<div class=\"table-wrap table-scroll\"><table class=\"table\"><thead><tr><th>Project</th><th>Functions</th><th>Complexity</th><th>Total CRAP</th><th>Mean</th><th>Median</th><th>Worst</th><th>At or above 30</th><th>Crap load</th></tr></thead><tbody>");
        for project in &snapshot.crap.projects {
            html.push_str(&format!(
                "<tr><td>{}</td><td>{}</td><td>{}</td><td>{:.1}</td><td>{:.1}</td><td>{:.1}</td><td>{:.1}</td><td>{} ({:.1}%)</td><td>{:.1}</td></tr>",
                html_escape(&project.project),
                project.totals.functions,
                project.totals.complexity,
                project.totals.total_crap,
                project.totals.mean_crap,
                project.totals.median_crap,
                project.totals.worst_crap,
                project.totals.crappy_functions,
                project.totals.crappy_percent,
                project.totals.crap_load
            ));
        }
        html.push_str("</tbody></table></div>");
    }
    if !snapshot.crap.functions.is_empty() {
        let (shown, rest) = snapshot
            .crap
            .functions
            .split_at(snapshot.crap.functions.len().min(CRAP_FUNCTIONS_SHOWN));
        html.push_str("<div class=\"crap-list\"><div class=\"crap-row crap-head\"><span>Score</span><span>Complexity</span><span>Coverage</span><span>Crap load</span><span>Function</span><span>Location</span></div>");
        for function in shown {
            html.push_str(&crap_function_html(function));
        }
        if !rest.is_empty() {
            html.push_str(&format!(
                "<details class=\"crap-more\"><summary>{} more functions</summary>",
                rest.len()
            ));
            for function in rest {
                html.push_str(&crap_function_html(function));
            }
            html.push_str("</details>");
        }
        html.push_str("</div>");
    }
    html.push_str("</section>");
    html
}

const CRAP_FUNCTIONS_SHOWN: usize = 30;

/// One scored function: a summary row that expands to the function's code with line coverage.
fn crap_function_html(function: &crate::crap::CrapFunction) -> String {
    let severity = match crate::crap::crap_severity(function.score) {
        crate::crap::CrapSeverity::Critical => "crap-critical",
        crate::crap::CrapSeverity::High => "crap-high",
        crate::crap::CrapSeverity::Moderate => "crap-moderate",
        crate::crap::CrapSeverity::Low => "crap-low",
    };
    let mut html = format!(
        "<details class=\"crap-fn {severity}\"><summary class=\"crap-row\"><span class=\"crap-score\">{:.1}</span><span>{}</span><span>{:.0}%</span><span>{:.1}</span><span class=\"crap-name\">{}</span><span class=\"crap-location\">{}:{}</span></summary>",
        function.score,
        function.complexity,
        function.coverage * 100.0,
        function.crap_load,
        html_escape(&function.name),
        html_escape(&function.path),
        function.line
    );
    if function.source.is_empty() {
        html.push_str("<p class=\"muted crap-no-source\">source not available</p>");
    } else {
        let lang = highlight_lang_for_path(&function.path);
        html.push_str("<div class=\"test-snippet diff\">");
        for line in &function.source {
            let class = match line.measure {
                crate::crap::LineMeasure::Covered => "ctx covered",
                crate::crap::LineMeasure::Uncovered => "ctx uncovered",
                crate::crap::LineMeasure::NotMeasured => "ctx",
            };
            let number = line.number.to_string();
            let code = highlight_linked(lang, &line.text, |_| None);
            html.push_str(&diff_row_html(class, &number, &number, "", &code));
        }
        html.push_str("</div>");
    }
    html.push_str("</details>");
    html
}

fn format_status_html(snapshot: &ReportSnapshot) -> String {
    build_status_html(snapshot)
}

fn build_status_html(snapshot: &ReportSnapshot) -> String {
    let overall = if snapshot.overall_ok { "OK" } else { "FAIL" };
    let overall_accent = if snapshot.overall_ok {
        Accent::Ok
    } else {
        Accent::Fail
    };
    let mut line = span_accent(
        &format!("{} {overall}", crate::COMMAND_NAME),
        overall_accent,
    );
    line.push_str(" | pkg ");
    line.push_str(&html_escape(&format!(
        "{}/{}",
        snapshot.packages_passed, snapshot.packages_total
    )));
    if snapshot.packages_failed > 0 {
        line.push(' ');
        line.push_str(&span_accent(
            &format!("pkg-fail:{}", snapshot.packages_failed),
            Accent::Fail,
        ));
    }
    line.push_str(" | tests run:");
    line.push_str(&html_escape(&snapshot.integration_run.to_string()));
    if snapshot.integration_skipped > 0 {
        let token = format!("skip:{}", snapshot.integration_skipped);
        line.push(' ');
        line.push_str(&span_accent(&token, Accent::Skip));
    } else {
        line.push_str(" skip:");
        line.push_str(&html_escape(&snapshot.integration_skipped.to_string()));
    }
    if snapshot.integration_failed > 0 {
        let token = format!("fail:{}", snapshot.integration_failed);
        line.push(' ');
        line.push_str(&span_accent(&token, Accent::Fail));
    } else {
        line.push_str(" fail:");
        line.push_str(&html_escape(&snapshot.integration_failed.to_string()));
    }
    let it_success = if snapshot.integration_run > 0 {
        format!(
            "{:.0}%",
            ((snapshot.integration_run - snapshot.integration_failed) as f64
                / snapshot.integration_run as f64)
                * 100.0
        )
    } else {
        "-".into()
    };
    line.push_str(" ok:");
    line.push_str(&html_escape(&it_success));
    if snapshot.coverage_enabled {
        line.push_str(" | ");
        line.push_str(&cov_segment_html(
            "all:",
            &status_pct(snapshot.totals.merged.lines_percent()),
            theme::coverage_accent(snapshot.totals.merged.lines_percent()),
        ));
        line.push(' ');
        line.push_str(&cov_segment_html(
            "unit:",
            &status_pct_opt(snapshot.totals.unit.as_ref()),
            theme::coverage_accent_opt(snapshot.totals.unit.as_ref()),
        ));
        line.push(' ');
        line.push_str(&cov_segment_html(
            "it:",
            &status_pct_opt(snapshot.totals.integration.as_ref()),
            theme::coverage_accent_opt(snapshot.totals.integration.as_ref()),
        ));
        line.push_str(" | diff:");
        line.push_str(&status_diff_lines_html(&snapshot.diff_coverage.merged));
    } else {
        line.push_str(" | cov:-");
    }
    line
}

fn status_diff_lines_html(summary: &CoverageSummary) -> String {
    if summary.lines_found == 0 {
        return html_escape("-");
    }
    lines_cell_html(Some(summary))
}

fn cov_segment_html(label: &str, value: &str, accent: Accent) -> String {
    format!("{label}{}", span_accent(value, accent))
}

fn status_pct(value: f64) -> String {
    if value.is_nan() {
        "-".into()
    } else {
        format!("{value:.1}%")
    }
}

fn status_pct_opt(summary: Option<&CoverageSummary>) -> String {
    match summary {
        Some(s) => status_pct(s.lines_percent()),
        None => "-".into(),
    }
}

fn render_projects_table(snapshot: &ReportSnapshot) -> String {
    let mut rows = String::new();
    rows.push_str("<table><thead><tr><th>project</th><th>package</th><th class=\"num cell-detail\">unit</th><th class=\"num cell-detail\">unit_fn</th><th class=\"num cell-detail\">it</th><th class=\"num cell-detail\">it_fn</th><th class=\"num cell-detail\">merged</th><th>status</th></tr></thead><tbody>");

    for project in &snapshot.projects {
        let status = if project.tests_passed { "ok" } else { "FAIL" };
        let status_cell = span_accent(status, theme::status_accent(status));
        let dc = &project.diff_coverage;
        rows.push_str(&format!(
            "<tr><td>{}</td><td>{}</td><td class=\"num cell-detail\">{}</td><td class=\"num cell-detail\">{}</td><td class=\"num cell-detail\">{}</td><td class=\"num cell-detail\">{}</td><td class=\"num cell-detail\">{}</td><td>{status_cell}</td></tr>",
            html_escape(&project.project_id),
            html_escape(&project.package),
            lines_cell_html(dc.unit.as_ref()),
            fn_cell_html(dc.unit.as_ref()),
            lines_cell_html(dc.integration.as_ref()),
            fn_cell_html(dc.integration.as_ref()),
            lines_cell_html(Some(&dc.merged)),
        ));
    }

    if snapshot.coverage_enabled {
        let dc = &snapshot.diff_coverage;
        let overall = if snapshot.overall_ok { "ok" } else { "FAIL" };
        let overall_cell = span_accent(overall, theme::status_accent(overall));
        rows.push_str(&format!(
            "<tr><td>ALL</td><td>(changed)</td><td class=\"num cell-detail\">{}</td><td class=\"num cell-detail\">{}</td><td class=\"num cell-detail\">{}</td><td class=\"num cell-detail\">{}</td><td class=\"num cell-detail\">{}</td><td>{overall_cell}</td></tr>",
            lines_cell_html(dc.unit.as_ref()),
            fn_cell_html(dc.unit.as_ref()),
            lines_cell_html(dc.integration.as_ref()),
            fn_cell_html(dc.integration.as_ref()),
            lines_cell_html(Some(&dc.merged)),
        ));
    }

    rows.push_str("</tbody></table>");
    format!("<div class=\"table-wrap table-scroll\">{rows}</div>")
}

fn render_tests_section(snapshot: &ReportSnapshot, jumps: &JumpIndex) -> String {
    let report = &snapshot.tests_report;
    if report.projects.is_empty() {
        return String::from(
            "<section id=\"tests\"><h2>tests</h2><p class=\"muted\">no integration tests recorded</p></section>",
        );
    }

    let mut html = String::from("<section id=\"tests\" data-view=\"grouped\"><h2>tests</h2>");
    html.push_str("<div class=\"test-toolbar\">");
    html.push_str("<button type=\"button\" id=\"test-view-toggle\" aria-label=\"toggle grouped and flat test views\">grouped</button>");
    html.push_str("<input type=\"search\" id=\"test-filter\" placeholder=\"filter text or tag\">");
    html.push_str("<select id=\"test-tag\"><option value=\"\">all tags</option>");
    for tag in &report.all_tags {
        html.push_str(&format!(
            "<option value=\"{}\">{}</option>",
            html_escape(tag),
            html_escape(tag)
        ));
    }
    html.push_str("</select>");
    html.push_str("<select id=\"test-status\"><option value=\"all\">all</option><option value=\"fail\">failures</option><option value=\"skip\">skipped</option></select>");
    html.push_str("<select id=\"test-sort\"><option value=\"default\">default order</option><option value=\"fast\">fastest first</option><option value=\"slow\">slowest first</option></select>");
    html.push_str("</div>");
    html.push_str("<div id=\"test-list-grouped\" class=\"test-view\">");

    for project in &report.projects {
        let stats = timing_stats_html(&project.stats);
        let title = fold_title(&project.project, project.stats.fail, project.stats.skip);
        html.push_str(&format!(
            "<details class=\"test-project\" data-project=\"{}\"{}>{}",
            html_escape(&project.project),
            tree_depth_open(1),
            fold_summary(&title, &stats),
        ));
        for suite in &project.suites {
            let suite_stats = timing_stats_html(&suite.stats);
            let suite_title = fold_title(&suite.suite, suite.stats.fail, suite.stats.skip);
            html.push_str(&format!(
                "<details class=\"test-suite\" data-suite=\"{}\"{}>{}",
                html_escape(&suite.suite),
                tree_depth_open(2),
                fold_summary(&suite_title, &suite_stats),
            ));
            for (order, case) in suite.cases.iter().enumerate() {
                html.push_str(&render_test_case(case, order, false, jumps));
            }
            html.push_str("</details>");
        }
        html.push_str("</details>");
    }

    html.push_str("</div><div id=\"test-list-flat\" class=\"test-view\">");
    let mut flat_order = 0usize;
    for project in &report.projects {
        for suite in &project.suites {
            for case in &suite.cases {
                html.push_str(&render_test_case(case, flat_order, true, jumps));
                flat_order += 1;
            }
        }
    }
    html.push_str("</div></section>");
    html
}

fn test_case_scope_target_html(case: &crate::report::TestCaseView) -> String {
    format!(
        " <span class=\"test-meta\">· scope {} · target {}</span>",
        html_escape(&case.scope),
        html_escape(&case.target),
    )
}

fn flat_test_title_html(case: &crate::report::TestCaseView) -> String {
    format!(
        "<span class=\"test-path\">{} &gt; {} &gt;</span> {}{}",
        html_escape(&case.project),
        html_escape(&case.suite),
        html_escape(&case.test),
        test_case_scope_target_html(case),
    )
}

fn grouped_test_title_html(case: &crate::report::TestCaseView) -> String {
    format!(
        "{}{}",
        html_escape(&case.test),
        test_case_scope_target_html(case),
    )
}

fn failure_why_html(reason: &str) -> String {
    format!(
        "<span class=\"why\"><button type=\"button\" class=\"flag bang\" aria-label=\"failed\">!</button><button type=\"button\" class=\"flag ask\" aria-expanded=\"false\" aria-label=\"why this failed\">?</button><span class=\"why-pop\" role=\"tooltip\">{}</span></span>",
        html_escape(reason)
    )
}

/// Renders a failure or skip reason, turning runner code frames into the same snippet rows as source code.
fn failure_reason_html(reason: &str, failed: bool) -> String {
    let mut html = String::new();
    let mut labelled = false;
    for part in crate::report::code_frame::split_reason(reason) {
        match part {
            crate::report::code_frame::ReasonPart::Text(text) => {
                html.push_str("<div class=\"test-reason\">");
                if !labelled {
                    html.push_str(&span_accent("reason", Accent::Neutral));
                    html.push_str(": ");
                    labelled = true;
                }
                html.push_str(&html_escape(&text));
                html.push_str("</div>");
            }
            crate::report::code_frame::ReasonPart::Frame { lines, source } => {
                let lang = match source.as_deref() {
                    Some(path) => highlight_lang_for_path(path),
                    None => snippet_language(
                        &lines
                            .iter()
                            .map(|line| line.text.as_str())
                            .collect::<Vec<_>>()
                            .join("\n"),
                    ),
                };
                let fail_class = if failed { " fail" } else { "" };
                html.push_str(&format!("<div class=\"test-snippet diff{fail_class}\">"));
                for line in &lines {
                    let class = match (line.marked, failed) {
                        (true, true) => "ctx highlight fail-line",
                        (true, false) => "ctx highlight",
                        (false, _) => "ctx",
                    };
                    let number = line.number.map(|n| n.to_string()).unwrap_or_default();
                    let code = if line.number.is_some() {
                        highlight_linked(lang, &line.text, |_| None)
                    } else {
                        html_escape(&line.text)
                    };
                    html.push_str(&diff_row_html(class, &number, &number, "", &code));
                }
                html.push_str("</div>");
            }
        }
    }
    html
}

fn render_test_case(
    case: &crate::report::TestCaseView,
    order: usize,
    flat: bool,
    jumps: &JumpIndex,
) -> String {
    let status_span = span_accent(&case.status_label, theme::status_accent(&case.status_label));
    let why = if case.status_label == "FAIL" {
        failure_why_html(case.reason.as_deref().unwrap_or("test failed"))
    } else {
        String::new()
    };
    let stats = if why.is_empty() {
        format!("{} · {}ms", status_span, case.elapsed_ms)
    } else {
        format!("{why} {} · {}ms", status_span, case.elapsed_ms)
    };
    let tags = crate::report::tags_attr(&case.tags);
    let label = format!("{} {} {}", case.test, case.suite, case.project);
    let title = if flat {
        flat_test_title_html(case)
    } else {
        grouped_test_title_html(case)
    };
    let case_class = if flat {
        "test-case test-case-flat"
    } else {
        "test-case"
    };
    let mut html = format!(
        "<details class=\"{case_class}\" data-status=\"{}\" data-ms=\"{}\" data-order=\"{}\" data-tags=\"{}\" data-label=\"{}\">{}",
        html_escape(&case.status_label),
        case.elapsed_ms,
        order,
        html_escape(&tags),
        html_escape(&label),
        fold_summary(&title, &stats),
    );

    let mut detail = String::new();
    if matches!(case.status_label.as_str(), "skipped" | "FAIL") {
        if let Some(reason) = &case.reason {
            detail.push_str(&failure_reason_html(reason, case.status_label == "FAIL"));
        }
    }
    if case.status_label == "FAIL" && (!case.method.is_empty() || !case.url.is_empty()) {
        detail.push_str("<div class=\"test-meta\">");
        detail.push_str(&html_escape(&format!("{} {}", case.method, case.url)));
        detail.push_str(" · HTTP ");
        detail.push_str(&html_escape(&case.http));
        detail.push_str("</div>");
    }
    if !case.snippet.is_empty() {
        let fail_class = if case.status_label == "FAIL" {
            " fail"
        } else {
            ""
        };
        detail.push_str(&format!("<div class=\"test-snippet diff{fail_class}\">"));
        let source = case
            .snippet
            .iter()
            .map(|line| line.text.as_str())
            .collect::<Vec<_>>()
            .join("\n");
        let lang = match &case.snippet_path {
            Some(path) => highlight_lang_for_path(&path.to_string_lossy()),
            None => snippet_language(&source),
        };
        for line in &case.snippet {
            let highlight = if line.highlight && case.status_label == "FAIL" {
                " highlight fail-line"
            } else if line.highlight {
                " highlight"
            } else {
                ""
            };
            let number = line.line_no.to_string();
            let code = highlight_linked(lang, &line.text, |name| {
                jumps.href(None, "", line.line_no, name)
            });
            detail.push_str(&diff_row_html(
                &format!("ctx{highlight}"),
                &number,
                &number,
                "",
                &code,
            ));
        }
        detail.push_str("</div>");
    }
    if !detail.is_empty() {
        html.push_str("<div class=\"test-detail\">");
        html.push_str(&detail);
        html.push_str("</div>");
    }

    html.push_str("</details>");
    html
}

fn render_project_sections(snapshot: &ReportSnapshot, jumps: &JumpIndex) -> String {
    if snapshot.project_diff_sections.is_empty() {
        return String::from(
            "<section><h2>changes</h2><p class=\"muted\">no changed files</p></section>",
        );
    }
    let mut html = String::from("<section id=\"changes\"><h2>changes</h2>");
    for project in &snapshot.project_diff_sections {
        html.push_str(&render_project_diff(snapshot, project, jumps));
    }
    html.push_str("</section>");
    html
}

fn merged_diff_coverage_for_project(
    snapshot: &ReportSnapshot,
    project_id: &str,
) -> Option<CoverageSummary> {
    let mut hit = 0u64;
    let mut found = 0u64;
    let mut fn_hit = 0u64;
    let mut fn_found = 0u64;
    let mut any = false;
    for project in &snapshot.projects {
        if project.project_id != project_id {
            continue;
        }
        let merged = &project.diff_coverage.merged;
        if merged.lines_found == 0 {
            continue;
        }
        any = true;
        hit += merged.lines_hit;
        found += merged.lines_found;
        fn_hit += merged.functions_hit;
        fn_found += merged.functions_found;
    }
    if !any {
        return None;
    }
    Some(CoverageSummary {
        lines_hit: hit,
        lines_found: found,
        functions_hit: fn_hit,
        functions_found: fn_found,
    })
}

fn project_diff_cov_stats_html(snapshot: &ReportSnapshot, project: &ProjectDiffSection) -> String {
    if let Some(summary) = merged_diff_coverage_for_project(snapshot, &project.project_id) {
        lines_cell_html(Some(&summary))
    } else {
        diff_coverage_stats_html(project.coverage_pct, project.uncovered_pct)
    }
}

fn render_project_diff(
    snapshot: &ReportSnapshot,
    project: &ProjectDiffSection,
    jumps: &JumpIndex,
) -> String {
    let status = if project.tests_passed { "ok" } else { "FAIL" };
    let status_span = span_accent(status, theme::status_accent(status));
    let cov_stats = project_diff_cov_stats_html(snapshot, project);
    let stats = format!("{} files · {cov_stats} · {status_span}", project.file_count,);
    let mut html = format!(
        "<details class=\"project\" id=\"project-{}\"{}>{}",
        html_escape(&project.project_id),
        tree_depth_open(1),
        fold_summary(&html_escape(&project.project_id), &stats),
    );
    if project.modules.is_empty() {
        html.push_str("<p class=\"muted\">no changed files</p>");
    } else {
        for module in &project.modules {
            html.push_str(&render_module_diff(module, jumps));
        }
    }
    html.push_str("</details>");
    html
}

fn render_module_diff(module: &ModuleDiffSection, jumps: &JumpIndex) -> String {
    let cov_stats = diff_coverage_stats_html(module.coverage_pct, module.uncovered_pct);
    let stats = format!("{} files · {cov_stats}", module.file_count,);
    let mut html = format!(
        "<details class=\"module\"{}>{}",
        tree_depth_open(2),
        fold_summary(&html_escape(&module.path), &stats),
    );
    for file in &module.files {
        html.push_str(&render_file_diff(file, jumps));
    }
    html.push_str("</details>");
    html
}

fn render_file_diff(file: &FileDiffSection, jumps: &JumpIndex) -> String {
    let cov_stats = diff_coverage_stats_html(file.coverage_pct, file.uncovered_pct);
    let fail_badge = if file.fail_lines > 0 {
        format!(
            " · {}",
            span_accent(&format!("{} fail", file.fail_lines), Accent::Fail,)
        )
    } else {
        String::new()
    };
    let stats = format!("+{} -{} · {cov_stats}{fail_badge}", file.adds, file.dels,);
    let mut html = format!(
        "<details class=\"file\"{}>{}",
        tree_depth_open(3),
        fold_summary(&html_escape(&file.path), &stats),
    );
    if file.hunks.is_empty() {
        if file.unparsed_diff {
            html.push_str("<p class=\"muted\">binary or unparsed diff</p>");
        }
    } else {
        html.push_str("<div class=\"diff\">");
        html.push_str(&diff_row_html("column-labels", "old", "new", "±", "code"));
        for (index, hunk) in file.hunks.iter().enumerate() {
            if index > 0 {
                html.push_str(&render_ellipsis_row());
            }
            let lang = highlight_lang_for_path(&file.path);
            for line in &hunk.lines {
                html.push_str(&render_diff_line(&file.path, lang, line, jumps));
            }
        }
        html.push_str("</div>");
    }
    html.push_str("</details>");
    html
}

fn render_ellipsis_row() -> String {
    diff_row_html("ellipsis", "", "", "", "…")
}

fn render_diff_line(
    path: &str,
    lang: crate::report::highlight::HighlightLang,
    line: &crate::report::diff_view::DiffLine,
    jumps: &JumpIndex,
) -> String {
    let mark = match line.tag {
        DiffTag::Add => "++",
        DiffTag::Del => "--",
        DiffTag::Context => "",
    };
    let tag_class = match line.tag {
        DiffTag::Add => "add",
        DiffTag::Del => "del",
        DiffTag::Context => "ctx",
    };
    let cov_class = match line.coverage {
        LineCov::Covered => " covered",
        LineCov::Uncovered => " uncovered",
        LineCov::Fail => " uncovered fail-line",
        LineCov::Na => "",
    };
    let old = line.old_no.map(|n| n.to_string()).unwrap_or_default();
    let new = line.new_no.map(|n| n.to_string()).unwrap_or_default();
    let line_no = line
        .new_no
        .and_then(|number| usize::try_from(number).ok())
        .unwrap_or(0);
    let code = highlight_linked(lang, &line.text, |name| {
        jumps.href(None, path, line_no, name)
    });
    diff_row_html(&format!("{tag_class}{cov_class}"), &old, &new, mark, &code)
}

fn span_accent(text: &str, accent: Accent) -> String {
    format!(
        "<span class=\"{}\">{}</span>",
        theme::html_class(accent),
        html_escape(text)
    )
}

fn diff_coverage_stats_html(covered_pct: f64, uncovered_pct: f64) -> String {
    let (covered, uncovered) =
        crate::report::diff_view::format_covered_uncovered_pair(covered_pct, uncovered_pct);
    format!(
        "{} covered / {} uncovered",
        span_accent(&covered, Accent::Covered),
        span_accent(&uncovered, Accent::Uncovered),
    )
}

fn fold_title(name: &str, fail: usize, skip: usize) -> String {
    let accent = theme::suite_accent(fail, skip);
    if accent == Accent::Ok {
        html_escape(name)
    } else {
        span_accent(name, accent)
    }
}

fn count_token(count: usize, label: &str, accent: Accent) -> String {
    let text = format!("{count} {label}");
    if count > 0 {
        span_accent(&text, accent)
    } else {
        text
    }
}

fn timing_stats_html(stats: &crate::report::TestTimingStats) -> String {
    let timing = if stats.run > 0 {
        format!(
            " · {}ms total · {}ms avg · {}ms fastest · {}ms slowest",
            stats.total_ms, stats.avg_ms, stats.min_ms, stats.max_ms
        )
    } else {
        String::new()
    };
    let fail = count_token(stats.fail, "fail", Accent::Fail);
    let skip = count_token(stats.skip, "skip", Accent::Skip);
    format!(
        "{} run · {} ok · {fail} · {skip}{timing}",
        stats.run, stats.ok
    )
}

fn fold_summary(title: &str, stats: &str) -> String {
    format!(
        "<summary class=\"fold-summary\"><span class=\"fold-title\">{}</span><span class=\"fold-stats\">{}</span></summary>",
        title, stats
    )
}

fn html_escape(value: &str) -> String {
    value
        .replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::preflight::{DiscoveredTest, PreflightPlan};
    use crate::report::diff_view::{
        DiffHunk, DiffLine, DiffTag, FileDiffSection, LineCov, ModuleDiffSection,
        ProjectDiffSection,
    };
    use crate::report::model::ProjectReport;
    use crate::report::{
        build_tests_report, derive_tags, CoverageTotals, PackageCoverage, TestsReport,
    };
    use std::path::PathBuf;

    mod failure_reason {
        use super::*;

        const PLAYWRIGHT_REASON: &str = "Error: page.goto: Page crashed

   6 |   test(`shows the value`, async ({ page }) => {
>  7 |     await page.goto(url);
     |                ^
   8 |   });
    at /opt/mini/polyglot/browser/functional.spec.ts:7:16";

        fn failed_case(reason: &str) -> crate::report::TestCaseView {
            crate::report::TestCaseView {
                project: "prices".into(),
                suite: "visual".into(),
                test: "vue shows the value".into(),
                full_name: "vue shows the value".into(),
                scope: "none".into(),
                target: "prices.mini-java-spring-mcap".into(),
                elapsed_ms: 30882,
                status_label: "FAIL".into(),
                http: "-".into(),
                reason: Some(reason.into()),
                method: String::new(),
                url: String::new(),
                tags: Vec::new(),
                snippet: Vec::new(),
                snippet_path: None,
            }
        }

        fn rendered(reason: &str) -> String {
            render_test_case(&failed_case(reason), 0, false, &JumpIndex::default())
        }

        #[test]
        fn renders_the_code_frame_as_a_formatted_snippet() {
            assert!(rendered(PLAYWRIGHT_REASON).contains("<div class=\"test-snippet diff fail\">"));
        }

        #[test]
        fn highlights_the_code_in_the_frame() {
            assert!(rendered(PLAYWRIGHT_REASON).contains("tok-kw"));
        }

        #[test]
        fn marks_the_failing_line_of_the_frame() {
            let html = rendered(PLAYWRIGHT_REASON);
            let marked = html.find("fail-line").unwrap();
            assert!(html[marked..].contains("goto"));
        }

        #[test]
        fn keeps_the_error_message_as_reason_text() {
            assert!(rendered(PLAYWRIGHT_REASON).contains(
                "<div class=\"test-reason\"><span class=\"neutral\">reason</span>: Error: page.goto: Page crashed</div>"
            ));
        }

        #[test]
        fn does_not_repeat_the_frame_as_plain_text() {
            assert!(!rendered(PLAYWRIGHT_REASON).contains(">  7 |"));
        }

        #[test]
        fn highlights_the_snippet_in_the_language_of_its_file() {
            let mut case = failed_case("listed price did not match");
            case.snippet = vec![crate::report::test_view::SnippetLine {
                line_no: 217,
                text: "        let mug = products.iter();".into(),
                highlight: true,
            }];
            case.snippet_path = Some("shop-rs-api/src/application/tests.rs".into());
            let html = render_test_case(&case, 0, false, &JumpIndex::default());
            assert!(html.contains("<span class=\"tok-kw\">let</span>"));
        }

        #[test]
        fn keeps_a_reason_without_a_frame_unchanged() {
            assert!(rendered("listed price did not match").contains(
                "<div class=\"test-reason\"><span class=\"neutral\">reason</span>: listed price did not match</div>"
            ));
        }
    }

    fn tests_report_for(rows: &[crate::report::IntegrationRow]) -> TestsReport {
        build_tests_report(rows, &PreflightPlan::default(), std::path::Path::new("."))
    }

    fn module_with_file(module_path: &str, file: FileDiffSection) -> ModuleDiffSection {
        ModuleDiffSection {
            path: module_path.into(),
            file_count: 1,
            coverage_pct: file.coverage_pct,
            uncovered_pct: file.uncovered_pct,
            has_uncovered: file.has_uncovered,
            files: vec![file],
        }
    }

    #[test]
    fn html_all_row_shows_aggregated_counts() {
        let snapshot = ReportSnapshot {
            run_id: "run".into(),
            overall_ok: true,
            packages_passed: 1,
            packages_total: 1,
            packages_failed: 0,
            integration_run: 0,
            integration_skipped: 0,
            integration_failed: 0,
            coverage_enabled: true,
            totals: CoverageTotals::default(),
            diff_coverage: CoverageTotals {
                unit: Some(crate::coverage::CoverageSummary {
                    lines_hit: 15,
                    lines_found: 50,
                    functions_hit: 2,
                    functions_found: 8,
                }),
                integration: None,
                merged: crate::coverage::CoverageSummary {
                    lines_hit: 15,
                    lines_found: 50,
                    functions_hit: 0,
                    functions_found: 0,
                },
            },
            projects: vec![],
            project_diff_sections: vec![],
            integration_rows: vec![],
            tests_report: TestsReport::default(),
            crap: crate::crap::CrapReport::default(),
            report_path: PathBuf::from("index.html"),
        };
        let html = render_projects_table(&snapshot);
        assert!(html.contains("(changed)"));
        assert!(html.contains("15/50"));
        assert!(html.contains("35 unc"));
        assert!(html.contains("30.0%"));
    }

    #[test]
    fn html_status_separates_unit_and_it_spans() {
        let snapshot = ReportSnapshot {
            run_id: "run".into(),
            overall_ok: true,
            packages_passed: 2,
            packages_total: 2,
            packages_failed: 0,
            integration_run: 0,
            integration_skipped: 0,
            integration_failed: 0,
            coverage_enabled: true,
            totals: CoverageTotals {
                unit: Some(crate::coverage::CoverageSummary {
                    lines_hit: 406,
                    lines_found: 1000,
                    functions_hit: 0,
                    functions_found: 0,
                }),
                integration: Some(crate::coverage::CoverageSummary {
                    lines_hit: 0,
                    lines_found: 10,
                    functions_hit: 0,
                    functions_found: 0,
                }),
                merged: crate::coverage::CoverageSummary {
                    lines_hit: 268,
                    lines_found: 1000,
                    functions_hit: 0,
                    functions_found: 0,
                },
            },
            diff_coverage: CoverageTotals {
                merged: crate::coverage::CoverageSummary {
                    lines_hit: 193,
                    lines_found: 1000,
                    functions_hit: 0,
                    functions_found: 0,
                },
                ..Default::default()
            },
            projects: vec![],
            project_diff_sections: vec![],
            integration_rows: vec![],
            tests_report: TestsReport::default(),
            crap: crate::crap::CrapReport::default(),
            report_path: PathBuf::from("index.html"),
        };
        let status = build_status_html(&snapshot);
        assert!(status.contains("unit:<span"));
        assert!(status.contains(" it:<span"));
        assert!(status.contains("</span> it:"));
    }

    #[test]
    fn html_contains_accent_classes() {
        let snapshot = ReportSnapshot {
            run_id: "run".into(),
            overall_ok: false,
            packages_passed: 0,
            packages_total: 1,
            packages_failed: 0,
            integration_run: 0,
            integration_skipped: 0,
            integration_failed: 0,
            coverage_enabled: true,
            totals: CoverageTotals::default(),
            diff_coverage: CoverageTotals {
                merged: crate::coverage::CoverageSummary {
                    lines_hit: 1,
                    lines_found: 4,
                    functions_hit: 0,
                    functions_found: 0,
                },
                ..Default::default()
            },
            projects: vec![ProjectReport {
                project_id: "mini".into(),
                package: "mini-rs-api".into(),
                tests_passed: false,
                coverage: PackageCoverage {
                    unit: Some(crate::coverage::CoverageSummary {
                        lines_hit: 1,
                        lines_found: 4,
                        functions_hit: 0,
                        functions_found: 0,
                    }),
                    ..Default::default()
                },
                diff_coverage: CoverageTotals::default(),
                changed_files: vec![],
            }],
            project_diff_sections: vec![ProjectDiffSection {
                project_id: "mini".into(),
                tests_passed: false,
                file_count: 1,
                coverage_pct: 25.0,
                uncovered_pct: 75.0,
                modules: vec![module_with_file(
                    "mini/mini-rs-api",
                    FileDiffSection {
                        path: "mini/mini-rs-api/src/lib.rs".into(),
                        adds: 3,
                        dels: 1,
                        coverage_pct: 25.0,
                        uncovered_pct: 75.0,
                        has_uncovered: true,
                        fail_lines: 1,
                        unparsed_diff: false,
                        hunks: vec![DiffHunk {
                            header: "@@".into(),
                            lines: vec![
                                DiffLine {
                                    old_no: Some(1),
                                    new_no: None,
                                    tag: DiffTag::Del,
                                    text: "fn legacy() {}".into(),
                                    coverage: LineCov::Na,
                                },
                                DiffLine {
                                    old_no: None,
                                    new_no: Some(1),
                                    tag: DiffTag::Add,
                                    text: "fn main() {}".into(),
                                    coverage: LineCov::Fail,
                                },
                                DiffLine {
                                    old_no: None,
                                    new_no: Some(2),
                                    tag: DiffTag::Add,
                                    text: "fn covered() {}".into(),
                                    coverage: LineCov::Covered,
                                },
                                DiffLine {
                                    old_no: None,
                                    new_no: Some(3),
                                    tag: DiffTag::Add,
                                    text: "fn gap() {}".into(),
                                    coverage: LineCov::Uncovered,
                                },
                                DiffLine {
                                    old_no: Some(4),
                                    new_no: Some(4),
                                    tag: DiffTag::Context,
                                    text: "fn kept() {}".into(),
                                    coverage: LineCov::Covered,
                                },
                            ],
                        }],
                    },
                )],
            }],
            integration_rows: vec![crate::report::IntegrationRow {
                project: "mini".into(),
                suite: "it".into(),
                test: "health".into(),
                full_name: "mini_project_it_suite_health_test".into(),
                scope: "local".into(),
                target: "http".into(),
                elapsed_ms: 10,
                status_label: "skipped".into(),
                http: "-".into(),
                reason: Some("no docker".into()),
                method: String::new(),
                url: String::new(),
                tags: derive_tags("mini", "it", "local", "health"),
                location: None,
            }],
            tests_report: tests_report_for(&[crate::report::IntegrationRow {
                project: "mini".into(),
                suite: "it".into(),
                test: "health".into(),
                full_name: "mini_project_it_suite_health_test".into(),
                scope: "local".into(),
                target: "http".into(),
                elapsed_ms: 10,
                status_label: "skipped".into(),
                http: "-".into(),
                reason: Some("no docker".into()),
                method: String::new(),
                url: String::new(),
                tags: derive_tags("mini", "it", "local", "health"),
                location: None,
            }]),
            crap: crate::crap::CrapReport::default(),
            report_path: PathBuf::from("index.html"),
        };
        let html = render_html(&snapshot);
        assert!(html.contains("class=\"low\""));
        assert!(html.contains("class=\"skip\""));
        assert!(html.contains("row add uncovered fail-line"));
        assert!(html.contains("<span class=\"mark\">++</span>"));
        assert!(html.contains("<span class=\"mark\">--</span>"));
        assert!(html.contains("row add covered"));
        assert!(html.contains("class=\"row add uncovered\""));
        assert!(html.contains("row ctx covered"));
        assert!(html.contains("aria-label=\"failed line\">!</button>"));
        assert!(html.contains("class=\"tok-kw\">fn</span>"));
        assert!(html.contains("class=\"fail\""));
        assert!(html.contains("1 fail"));
        assert!(html.contains("<span class=\"fail\">cherry-test FAIL</span>"));
        assert!(html.contains("<td><span class=\"fail\">FAIL</span></td></tr>"));
        assert!(!html.contains("<td>ALL</td></tr>"));
        assert!(html.contains("class=\"covered\""));
        assert!(html.contains("class=\"uncovered\""));
        assert!(html.contains(" covered / "));
        assert!(html.contains(" uncovered"));
    }

    #[test]
    fn html_diff_summary_shows_covered_uncovered_accents() {
        let snapshot = ReportSnapshot {
            run_id: "run".into(),
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
            project_diff_sections: vec![ProjectDiffSection {
                project_id: "mini".into(),
                tests_passed: true,
                file_count: 1,
                coverage_pct: 39.0,
                uncovered_pct: 61.0,
                modules: vec![module_with_file(
                    "mini/mini-rs-api",
                    FileDiffSection {
                        path: "mini/mini-rs-api/src/lib.rs".into(),
                        adds: 45,
                        dels: 0,
                        coverage_pct: 39.0,
                        uncovered_pct: 61.0,
                        has_uncovered: true,
                        fail_lines: 0,
                        unparsed_diff: false,
                        hunks: vec![],
                    },
                )],
            }],
            integration_rows: vec![],
            tests_report: TestsReport::default(),
            crap: crate::crap::CrapReport::default(),
            report_path: PathBuf::from("index.html"),
        };
        let html = render_html(&snapshot);
        assert!(html.contains("+45 -0"));
        assert!(html.contains("39%"));
        assert!(html.contains("61%"));
        assert!(html.contains(
            "class=\"covered\">39%</span> covered / <span class=\"uncovered\">61%</span> uncovered"
        ));
    }

    #[test]
    fn html_diff_separates_hunks_with_an_ellipsis() {
        let snapshot = ReportSnapshot {
            run_id: "run".into(),
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
            project_diff_sections: vec![ProjectDiffSection {
                project_id: "shop".into(),
                tests_passed: true,
                file_count: 1,
                coverage_pct: 100.0,
                uncovered_pct: 0.0,
                modules: vec![module_with_file(
                    "shop/shop-rs-api",
                    FileDiffSection {
                        path: "shop-rs-api/src/lib.rs".into(),
                        adds: 1,
                        dels: 0,
                        coverage_pct: 100.0,
                        uncovered_pct: 0.0,
                        has_uncovered: false,
                        fail_lines: 0,
                        unparsed_diff: false,
                        hunks: vec![
                            DiffHunk {
                                header: "@@ -1 +1 @@".into(),
                                lines: vec![DiffLine {
                                    old_no: Some(1),
                                    new_no: Some(1),
                                    tag: DiffTag::Context,
                                    text: "above".into(),
                                    coverage: LineCov::Na,
                                }],
                            },
                            DiffHunk {
                                header: "@@ -8 +8 @@".into(),
                                lines: vec![DiffLine {
                                    old_no: None,
                                    new_no: Some(9),
                                    tag: DiffTag::Add,
                                    text: "covered".into(),
                                    coverage: LineCov::Covered,
                                }],
                            },
                        ],
                    },
                )],
            }],
            integration_rows: vec![],
            tests_report: TestsReport::default(),
            crap: crate::crap::CrapReport::default(),
            report_path: PathBuf::from("index.html"),
        };
        let html = render_html(&snapshot);
        let above = html.find("above").expect("context line");
        let ellipsis = html.find("row ellipsis").expect("hunk separator");
        let covered = html.find("row add covered").expect("covered line");
        assert!(above < ellipsis && ellipsis < covered);
        assert!(html.contains("column-labels"));
        assert!(html.contains("<span class=\"no old\">old</span>"));
        assert!(html.contains("<span class=\"no new\">new</span>"));
        assert!(html.contains(">code</span>"));
        assert!(html.contains("<span class=\"rail run\">run</span>"));
        assert!(html.contains("<span class=\"mark\">++</span>"));
        assert!(html.contains("<span class=\"no old\">1</span><span class=\"no new\">1</span>"));
        assert!(html.contains("<span class=\"no old\"></span><span class=\"no new\">9</span>"));
    }

    #[test]
    fn diff_line_links_a_unique_symbol_to_the_phase_page() {
        use crate::report::highlight::{record_rendered_definitions, JumpIndex};
        use std::collections::BTreeSet;
        let mut jumps = JumpIndex::default();
        record_rendered_definitions(
            &mut jumps,
            "integration",
            "shop-rs-api/src/service.rs",
            "\n\n\nstruct CatalogService;\n",
            &BTreeSet::from([4]),
        );
        let line = DiffLine {
            old_no: None,
            new_no: Some(9),
            tag: DiffTag::Add,
            text: "let service = CatalogService::new();".into(),
            coverage: LineCov::Covered,
        };
        let html = render_diff_line(
            "shop-rs-api/src/app.rs",
            crate::report::highlight::HighlightLang::Rust,
            &line,
            &jumps,
        );
        assert!(html.contains("href=\"phases.html#cov-integration-shop-rs-api-src-service-rs-L4\""));
        assert!(html.contains("tok-ty"));
        assert!(!html.contains(" id=\""));
    }

    #[test]
    fn html_contains_foldable_project_and_coverage_classes() {
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
            projects: vec![ProjectReport {
                project_id: "mini".into(),
                package: "mini-rs-api".into(),
                tests_passed: true,
                coverage: PackageCoverage::default(),
                diff_coverage: CoverageTotals::default(),
                changed_files: vec![],
            }],
            project_diff_sections: vec![ProjectDiffSection {
                project_id: "mini".into(),
                tests_passed: true,
                file_count: 1,
                coverage_pct: 50.0,
                uncovered_pct: 50.0,
                modules: vec![module_with_file(
                    "mini/mini-rs-api",
                    FileDiffSection {
                        path: "mini/mini-rs-api/src/lib.rs".into(),
                        adds: 1,
                        dels: 0,
                        coverage_pct: 50.0,
                        uncovered_pct: 50.0,
                        has_uncovered: true,
                        fail_lines: 0,
                        unparsed_diff: false,
                        hunks: vec![DiffHunk {
                            header: "@@".into(),
                            lines: vec![DiffLine {
                                old_no: None,
                                new_no: Some(1),
                                tag: DiffTag::Add,
                                text: "fn main() {}".into(),
                                coverage: LineCov::Uncovered,
                            }],
                        }],
                    },
                )],
            }],
            integration_rows: vec![],
            tests_report: TestsReport::default(),
            crap: crate::crap::CrapReport::default(),
            report_path: PathBuf::from("index.html"),
        };
        let html = render_html(&snapshot);
        assert!(html.contains("details class=\"project\""));
        assert!(html.contains("details class=\"module\""));
        assert!(html.contains("details class=\"file\""));
        assert!(html.contains("row add uncovered"));
        assert!(html.contains("id=\"projects_summary\""));
        let changes_start = html.find("section id=\"changes\"").unwrap();
        let changes_html = &html[changes_start..];
        assert!(changes_html.contains("id=\"project-mini\" open>"));
        assert!(changes_html.contains("<details class=\"module\" open>"));
        assert!(!changes_html.contains("<details class=\"file\" open>"));
    }

    #[test]
    fn html_contains_project_section_ids() {
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
            projects: vec![ProjectReport {
                project_id: "mini".into(),
                package: "mini-rs-api".into(),
                tests_passed: true,
                coverage: PackageCoverage::default(),
                diff_coverage: CoverageTotals::default(),
                changed_files: vec![],
            }],
            project_diff_sections: vec![ProjectDiffSection {
                project_id: "mini".into(),
                tests_passed: true,
                file_count: 0,
                coverage_pct: 0.0,
                uncovered_pct: f64::NAN,
                modules: vec![],
            }],
            integration_rows: vec![],
            tests_report: TestsReport::default(),
            crap: crate::crap::CrapReport::default(),
            report_path: PathBuf::from("index.html"),
        };
        let html = render_html(&snapshot);
        assert!(html.contains("id=\"project-mini\""));
        assert!(html.contains("id=\"projects_summary\""));
    }

    #[test]
    fn html_tests_section_has_toolbar_and_foldable_cases() {
        let dir = std::env::temp_dir().join(format!("cherry-test-html-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let rel = PathBuf::from("mini/mini-rs-api/tests/integration.rs");
        let path = dir.join(&rel);
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        std::fs::write(
            &path,
            "#[cherry_test(scope = \"mini_wallet\")]\nasync fn mini_project_wallet_suite_get_usdt_test() {}\nasync fn mini_project_wallet_suite_ping_test() {}\n",
        )
        .unwrap();

        let preflight = PreflightPlan {
            discovered: vec![
                DiscoveredTest {
                    package: "mini-rs-api".into(),
                    function: "mini_project_wallet_suite_get_usdt_test".into(),
                    attr_scope: Some("mini_wallet".into()),
                    attr_auth: None,
                    effective_scope: "mini_wallet".into(),
                    project: "mini".into(),
                    suite: "wallet".into(),
                    test: "get_usdt".into(),
                    source_path: rel.clone(),
                    fn_line: 2,
                },
                DiscoveredTest {
                    package: "mini-rs-api".into(),
                    function: "mini_project_wallet_suite_ping_test".into(),
                    attr_scope: Some("mini_wallet".into()),
                    attr_auth: None,
                    effective_scope: "mini_wallet".into(),
                    project: "mini".into(),
                    suite: "wallet".into(),
                    test: "ping".into(),
                    source_path: rel,
                    fn_line: 3,
                },
            ],
            ..Default::default()
        };

        let row = crate::report::IntegrationRow {
            project: "mini".into(),
            suite: "wallet".into(),
            test: "get_usdt".into(),
            full_name: "mini_project_wallet_suite_get_usdt_test".into(),
            scope: "mini_wallet".into(),
            target: "mini.api".into(),
            elapsed_ms: 42,
            status_label: "FAIL".into(),
            http: "500".into(),
            reason: Some("HTTP 500: boom".into()),
            method: "GET".into(),
            url: "/health".into(),
            tags: derive_tags("mini", "wallet", "mini_wallet", "get_usdt"),
            location: None,
        };
        let skip = crate::report::IntegrationRow {
            status_label: "skipped".into(),
            reason: Some("no docker".into()),
            elapsed_ms: 0,
            http: "-".into(),
            method: String::new(),
            url: String::new(),
            ..row.clone()
        };
        let ok = crate::report::IntegrationRow {
            status_label: "ok".into(),
            reason: None,
            elapsed_ms: 5,
            http: "200".into(),
            test: "ping".into(),
            full_name: "mini_project_wallet_suite_ping_test".into(),
            tags: derive_tags("mini", "wallet", "mini_wallet", "ping"),
            ..row.clone()
        };
        let rows = vec![row, skip, ok];
        let snapshot = ReportSnapshot {
            run_id: "run".into(),
            overall_ok: false,
            packages_passed: 0,
            packages_total: 1,
            packages_failed: 0,
            integration_run: 2,
            integration_skipped: 1,
            integration_failed: 1,
            coverage_enabled: false,
            totals: CoverageTotals::default(),
            diff_coverage: CoverageTotals::default(),
            projects: vec![],
            project_diff_sections: vec![],
            integration_rows: rows.clone(),
            tests_report: build_tests_report(&rows, &preflight, &dir),
            crap: crate::crap::CrapReport::default(),
            report_path: PathBuf::from("index.html"),
        };
        let html = render_html(&snapshot);
        assert!(html.contains("id=\"tests\""));
        assert!(html.contains("id=\"test-filter\""));
        assert!(html.contains("id=\"test-view-toggle\""));
        assert!(html.contains("id=\"test-list-grouped\""));
        assert!(html.contains("id=\"test-list-flat\""));
        assert!(html.contains("data-view=\"grouped\""));
        assert!(html.contains("id=\"test-tag\""));
        assert!(html.contains("id=\"test-status\""));
        assert!(html.contains("id=\"test-sort\""));
        assert!(html.contains("details class=\"test-project\""));
        assert!(html.contains("details class=\"test-suite\""));
        assert!(html.contains("details class=\"test-case\""));
        assert!(html.contains("class=\"fold-summary\""));
        assert!(html.contains("class=\"fold-stats\""));
        assert!(html.contains(
            "2 run · 1 ok · <span class=\"fail\">1 fail</span> · <span class=\"skip\">1 skip</span>"
        ));
        assert!(html.contains("<span class=\"fail\">fail:1</span>"));
        assert!(html.contains("<span class=\"fold-title\"><span class=\"fail\">mini</span></span>"));
        assert!(
            html.contains("<span class=\"fold-title\"><span class=\"fail\">wallet</span></span>")
        );
        assert!(html.contains("test-snippet"));
        assert!(html.contains("class=\"test-reason\""));
        assert!(html.contains("class=\"why\""));
        assert!(html.contains("class=\"flag ask\""));
        assert!(html.contains("class=\"why-pop\""));
        assert!(html.contains("no docker"));
        assert!(html.contains("HTTP 500: boom"));
        assert!(!html.contains("id=\"integration\""));
        let flat_start = html.find("id=\"test-list-flat\"").unwrap();
        let flat_html = &html[flat_start..];
        assert!(flat_html.contains("class=\"test-path\">mini &gt; wallet &gt;</span> get_usdt"));
        assert!(flat_html.contains("· scope mini_wallet · target mini.api"));
        assert!(flat_html.contains("class=\"test-case test-case-flat\""));
        let grouped_end = html.find("id=\"test-list-flat\"").unwrap();
        let grouped_html = &html[..grouped_end];
        assert!(grouped_html.contains("class=\"fold-title\">get_usdt <span class=\"test-meta\">· scope mini_wallet · target mini.api</span></span>"));
        assert!(!grouped_html.contains("class=\"test-path\">mini &gt; wallet &gt;</span> get_usdt"));
        assert!(grouped_html.contains("details class=\"test-project\" data-project=\"mini\" open>"));
        assert!(grouped_html.contains("details class=\"test-suite\" data-suite=\"wallet\" open>"));
        assert!(!grouped_html.contains("details class=\"test-case\" data-status=\"FAIL\" open>"));
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn html_changes_groups_files_under_module() {
        let snapshot = ReportSnapshot {
            run_id: "run".into(),
            overall_ok: true,
            packages_passed: 2,
            packages_total: 2,
            packages_failed: 0,
            integration_run: 0,
            integration_skipped: 0,
            integration_failed: 0,
            coverage_enabled: false,
            totals: CoverageTotals::default(),
            diff_coverage: CoverageTotals::default(),
            projects: vec![],
            project_diff_sections: vec![ProjectDiffSection {
                project_id: "mini".into(),
                tests_passed: true,
                file_count: 2,
                coverage_pct: 85.0,
                uncovered_pct: 15.0,
                modules: vec![
                    module_with_file(
                        "mini/mini-ts-web-react",
                        FileDiffSection {
                            path: "mini/mini-ts-web-react/src/App.tsx".into(),
                            adds: 10,
                            dels: 0,
                            coverage_pct: 80.0,
                            uncovered_pct: 20.0,
                            has_uncovered: true,
                            fail_lines: 0,
                            unparsed_diff: false,
                            hunks: vec![],
                        },
                    ),
                    module_with_file(
                        "mini/mini-ts-lib-price",
                        FileDiffSection {
                            path: "mini/mini-ts-lib-price/src/displayAddressUsdt.ts".into(),
                            adds: 27,
                            dels: 0,
                            coverage_pct: 90.0,
                            uncovered_pct: 10.0,
                            has_uncovered: true,
                            fail_lines: 0,
                            unparsed_diff: false,
                            hunks: vec![],
                        },
                    ),
                ],
            }],
            integration_rows: vec![],
            tests_report: TestsReport::default(),
            crap: crate::crap::CrapReport::default(),
            report_path: PathBuf::from("index.html"),
        };
        let html = render_html(&snapshot);
        let module_pos = html
            .find("class=\"fold-title\">mini/mini-ts-web-react</span>")
            .unwrap();
        let file_pos = html
            .find("class=\"fold-title\">mini/mini-ts-web-react/src/App.tsx</span>")
            .unwrap();
        assert!(module_pos < file_pos);
        assert!(html.contains("class=\"fold-title\">mini/mini-ts-lib-price</span>"));
        assert!(html.contains(
            "class=\"fold-title\">mini/mini-ts-lib-price/src/displayAddressUsdt.ts</span>"
        ));
    }

    #[test]
    fn html_fold_summary_splits_title_and_stats() {
        let snapshot = ReportSnapshot {
            run_id: "run".into(),
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
            project_diff_sections: vec![ProjectDiffSection {
                project_id: "mini".into(),
                tests_passed: true,
                file_count: 5,
                coverage_pct: 28.0,
                uncovered_pct: 72.0,
                modules: vec![module_with_file(
                    "mini/mini-rs-lib-mcap",
                    FileDiffSection {
                        path: "mini/mini-rs-lib-mcap/Cargo.toml".into(),
                        adds: 18,
                        dels: 0,
                        coverage_pct: 0.0,
                        uncovered_pct: f64::NAN,
                        has_uncovered: true,
                        fail_lines: 0,
                        unparsed_diff: false,
                        hunks: vec![],
                    },
                )],
            }],
            integration_rows: vec![],
            tests_report: TestsReport::default(),
            crap: crate::crap::CrapReport::default(),
            report_path: PathBuf::from("index.html"),
        };
        let html = render_html(&snapshot);
        assert!(html.contains("class=\"fold-summary\""));
        assert!(html.contains("class=\"fold-title\""));
        assert!(html.contains("class=\"fold-stats\""));
        assert!(html.contains(">mini</span>"));
        assert!(html.contains("5 files"));
        assert!(html.contains("mini/mini-rs-lib-mcap/Cargo.toml"));
        assert!(html.contains("+18 -0"));
    }

    #[test]
    fn html_shell_marks_add_del_in_the_gutter() {
        let styles = cherry_test_common::report_shell::REPORT_HTML_STYLES;
        assert!(styles.contains(".diff .row.add .no { background: var(--diff-add-gutter); }"));
        assert!(styles.contains(".diff .row.del .no { background: var(--diff-del-gutter); }"));
        assert!(styles.contains("--covered: #dbeafe;"));
    }

    #[test]
    fn html_header_is_compact_bar() {
        let snapshot = ReportSnapshot {
            run_id: "run".into(),
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
            report_path: PathBuf::from("index.html"),
        };
        let html = render_html(&snapshot);
        assert!(html.contains("header-bar"));
        assert!(html.contains("id=\"theme-toggle\""));
        assert!(!html.contains(">theme<"));
        assert!(!html.contains("href='#projects_summary'"));
        assert!(!html.contains("href='#tests'"));
        assert!(html.contains("white-space: nowrap"));
    }

    #[test]
    fn html_ignored_file_diff_summary_shows_dash() {
        let snapshot = ReportSnapshot {
            run_id: "run".into(),
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
            project_diff_sections: vec![ProjectDiffSection {
                project_id: "mini".into(),
                tests_passed: true,
                file_count: 1,
                coverage_pct: f64::NAN,
                uncovered_pct: f64::NAN,
                modules: vec![module_with_file(
                    "mini/mini-rs-lib-mcap",
                    FileDiffSection {
                        path: "mini/mini-rs-lib-mcap/Cargo.toml".into(),
                        adds: 18,
                        dels: 0,
                        coverage_pct: f64::NAN,
                        uncovered_pct: f64::NAN,
                        has_uncovered: false,
                        fail_lines: 0,
                        unparsed_diff: false,
                        hunks: vec![],
                    },
                )],
            }],
            integration_rows: vec![],
            tests_report: TestsReport::default(),
            crap: crate::crap::CrapReport::default(),
            report_path: PathBuf::from("index.html"),
        };
        let html = render_html(&snapshot);
        assert!(html.contains("mini/mini-rs-lib-mcap/Cargo.toml"));
        assert!(html.contains(" covered / "));
        assert!(html.contains(" uncovered"));
    }

    #[test]
    fn should_open_report_respects_flags() {
        assert!(!should_open_report_with(true, true, false, false));
        assert!(!should_open_report_with(false, false, false, false));
        assert!(!should_open_report_with(false, true, true, false));
        assert!(!should_open_report_with(false, true, false, true));
        assert!(should_open_report_with(false, true, false, false));
    }

    #[test]
    fn html_report_uses_full_width_layout() {
        let snapshot = ReportSnapshot {
            run_id: "run".into(),
            overall_ok: true,
            packages_passed: 0,
            packages_total: 0,
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
            report_path: PathBuf::from("index.html"),
        };
        let html = render_html(&snapshot);
        assert!(html.contains("max-width: none"));
        assert!(html.contains("width: 100%"));
    }

    #[test]
    fn html_tables_are_wrapped_for_horizontal_overflow() {
        let snapshot = ReportSnapshot {
            run_id: "run".into(),
            overall_ok: true,
            packages_passed: 1,
            packages_total: 1,
            packages_failed: 0,
            integration_run: 0,
            integration_skipped: 0,
            integration_failed: 0,
            coverage_enabled: true,
            totals: CoverageTotals::default(),
            diff_coverage: CoverageTotals::default(),
            projects: vec![ProjectReport {
                project_id: "mini".into(),
                package: "mini-rs-api".into(),
                tests_passed: true,
                coverage: PackageCoverage::default(),
                diff_coverage: CoverageTotals::default(),
                changed_files: vec![],
            }],
            project_diff_sections: vec![],
            integration_rows: vec![],
            tests_report: TestsReport::default(),
            crap: crate::crap::CrapReport::default(),
            report_path: PathBuf::from("index.html"),
        };
        let table = render_projects_table(&snapshot);
        assert!(table.contains("table-wrap"));
        assert!(table.contains("table-scroll"));
        let html = render_html(&snapshot);
        assert!(html.contains("class=\"table-wrap table-scroll\""));
    }

    #[test]
    fn html_crap_section_shows_totals_only_when_enabled() {
        let disabled = render_html(&snapshot_with_crap(crate::crap::CrapReport::default()));
        assert!(!disabled.contains("id=\"crap\""));
        let mut functions = vec![crate::crap::CrapFunction {
            path: "a&b.rs".into(),
            name: "<price>".into(),
            line: 4,
            complexity: 10,
            covered_lines: 0,
            measured_lines: 4,
            coverage: 0.0,
            score: 110.0,
            crap_load: crate::crap::crap_load(10, 0.0),
            source: Vec::new(),
        }];
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
        let totals = crate::crap::crap_totals(&functions);
        let html = render_html(&snapshot_with_crap(crate::crap::CrapReport {
            enabled: true,
            projects: vec![crate::crap::CrapProjectTotals {
                project: "shop".into(),
                totals,
            }],
            totals,
            functions,
        }));
        assert!(html.contains("id=\"crap\""));
        assert!(html.contains("line coverage, not basis-path coverage"));
        assert!(html.contains("The aggregate LCOV percentage is not a CRAP score."));
        assert!(html.contains("31 functions"));
        assert!(html.contains("at or above 30"));
        assert!(html.contains("crap load 10.3"));
        assert!(html.contains("complexity 40"));
        assert!(html.contains("total CRAP 140.0"));
        assert!(html.contains("mean 4.5"));
        assert!(html.contains("median 1.0"));
        assert!(html.contains(">Crap load<"));
        assert!(html.contains("&lt;price&gt;"));
        assert!(html.contains("a&amp;b.rs:4"));
        assert!(html.contains(">shop<"));
        assert!(html.contains("1 more functions"));
        assert!(!html.contains("<price>"));
    }

    mod crap_section {
        use super::*;

        fn scored(name: &str, score: f64) -> crate::crap::CrapFunction {
            crate::crap::CrapFunction {
                path: "shop-rs-api/src/domain/model.rs".into(),
                name: name.into(),
                line: 2,
                complexity: 3,
                covered_lines: 1,
                measured_lines: 2,
                coverage: 0.5,
                score,
                crap_load: 0.0,
                source: vec![
                    crate::crap::CrapSourceLine {
                        number: 2,
                        text: "fn price(x: u32) -> u32 {".into(),
                        measure: crate::crap::LineMeasure::Covered,
                    },
                    crate::crap::CrapSourceLine {
                        number: 3,
                        text: "    if x > 1 { 2 } else { 3 }".into(),
                        measure: crate::crap::LineMeasure::Uncovered,
                    },
                ],
            }
        }

        fn rendered(functions: Vec<crate::crap::CrapFunction>) -> String {
            let totals = crate::crap::crap_totals(&functions);
            render_html(&snapshot_with_crap(crate::crap::CrapReport {
                enabled: true,
                projects: Vec::new(),
                totals,
                functions,
            }))
        }

        fn many(count: usize) -> Vec<crate::crap::CrapFunction> {
            (0..count)
                .map(|index| scored(&format!("f{index}"), 1.0))
                .collect()
        }

        #[test]
        fn comes_after_every_other_section() {
            let html = rendered(vec![scored("price", 40.0)]);
            let crap = html.find("<section id=\"crap\"").unwrap();
            assert!(!html[crap..].contains("<section id=\"tests\""));
            assert!(html.find("id=\"projects_summary\"").unwrap() < crap);
        }

        #[test]
        fn expands_each_function_to_its_code() {
            let html = rendered(vec![scored("price", 40.0)]);
            let row = html
                .find("<details class=\"crap-fn crap-critical\"")
                .unwrap();
            assert!(html[row..].contains("class=\"test-snippet diff\""));
            assert!(html[row..].contains("u32"));
        }

        #[test]
        fn marks_covered_and_uncovered_lines_of_the_function() {
            let html = rendered(vec![scored("price", 40.0)]);
            let row = html.find("<details class=\"crap-fn").unwrap();
            assert!(html[row..].contains("ctx covered"));
            assert!(html[row..].contains("ctx uncovered"));
        }

        #[test]
        fn colors_rows_by_severity() {
            let html = rendered(vec![
                scored("a", 40.0),
                scored("b", 20.0),
                scored("c", 6.0),
                scored("d", 1.0),
            ]);
            for class in ["crap-critical", "crap-high", "crap-moderate", "crap-low"] {
                assert!(
                    html.contains(&format!("class=\"crap-fn {class}\"")),
                    "{class}"
                );
            }
        }

        #[test]
        fn lists_the_remaining_functions_in_a_collapsed_section() {
            let html = rendered(many(32));
            let more = html
                .find("<details class=\"crap-more\"><summary>2 more functions</summary>")
                .unwrap();
            assert_eq!(html[more..].matches("<details class=\"crap-fn").count(), 2);
        }

        #[test]
        fn shows_every_function() {
            assert_eq!(
                rendered(many(32))
                    .matches("<details class=\"crap-fn")
                    .count(),
                32
            );
        }
    }

    fn snapshot_with_crap(crap: crate::crap::CrapReport) -> ReportSnapshot {
        ReportSnapshot {
            run_id: "run".into(),
            overall_ok: true,
            packages_passed: 1,
            packages_total: 1,
            packages_failed: 0,
            integration_run: 0,
            integration_skipped: 0,
            integration_failed: 0,
            coverage_enabled: true,
            totals: CoverageTotals::default(),
            diff_coverage: CoverageTotals::default(),
            projects: vec![],
            project_diff_sections: vec![],
            integration_rows: vec![],
            tests_report: TestsReport::default(),
            crap,
            report_path: PathBuf::from("index.html"),
        }
    }
}
