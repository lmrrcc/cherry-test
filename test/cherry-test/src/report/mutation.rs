use std::collections::BTreeMap;
use std::path::Path;

use anyhow::{Context, Result};
use cherry_test_common::adapter::AdapterRequest;

use crate::adapters::pitest::{self, MutationReport};

pub fn summary(report: &MutationReport) -> String {
    if report.status == "no_changes" {
        return "PIT: no changed production sources; mutation testing not needed".into();
    }
    let total = report.mutants.len();
    let detected = report
        .mutants
        .iter()
        .filter(|mutant| mutant.detected)
        .count();
    let mut counts = BTreeMap::<&str, usize>::new();
    for mutant in &report.mutants {
        *counts.entry(&mutant.status).or_default() += 1;
    }
    let counts = counts
        .into_iter()
        .map(|(status, count)| format!("{status}: {count}"))
        .collect::<Vec<_>>()
        .join(", ");
    let score = if total == 0 {
        0.0
    } else {
        detected as f64 * 100.0 / total as f64
    };
    format!("PIT: {total} mutants in {} changed source files; mutation score {score:.1}% ({detected}/{total} detected); {counts}", report.sources.len())
}

fn escape(value: &str) -> String {
    value
        .replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
        .replace('\'', "&#39;")
}

fn anchor(project: &str, package: &str) -> String {
    let id = format!("{project}/{package}");
    format!(
        "mutation-{}",
        id.bytes()
            .map(|byte| format!("{byte:02x}"))
            .collect::<String>()
    )
}

pub fn index(reports: &Path) -> Result<String> {
    let mut rows = String::new();
    for path in crate::adapters::formats::files(&reports.join("adapters"), "json")? {
        if path.file_name().and_then(|name| name.to_str()) != Some("result.json") {
            continue;
        }
        let output = path.parent().context("mutation report directory")?;
        if !output.join("request.json").is_file() {
            continue;
        }
        let request: AdapterRequest =
            serde_json::from_slice(&std::fs::read(output.join("request.json"))?)?;
        if !is_pitest(&request) {
            continue;
        }
        let Some(report) = pitest::read_report(output)? else {
            continue;
        };
        rows.push_str(&format!(
            "<li><a href=\"phases.html#{}\">{} / {}</a>: {}</li>",
            anchor(&request.project, &request.package),
            escape(&request.project),
            escape(&request.package),
            escape(&summary(&report))
        ));
    }
    if rows.is_empty() {
        return Ok(String::new());
    }
    Ok(format!("<section id=\"mutation\"><h2>Changed-source mutation testing</h2><p>Mutants are reported separately from test totals. The score is PIT's detected mutants divided by all reported mutants, including uncovered mutants.</p><ul>{rows}</ul></section>"))
}

pub fn is_pitest(request: &AdapterRequest) -> bool {
    request.phase == "mutation"
        && request
            .options
            .get("mutation_tool")
            .and_then(|value| value.as_str())
            == Some("pitest")
}

pub fn details(
    request: &AdapterRequest,
    report: &MutationReport,
    reports: &Path,
) -> Result<String> {
    let mut html = format!(
        "<div id=\"{}\"><h3>PIT changed-source mutation testing</h3><p>{}</p>",
        anchor(&request.project, &request.package),
        escape(&summary(report))
    );
    if !report.sources.is_empty() {
        html.push_str("<details><summary>Changed production sources</summary><ul>");
        for source in &report.sources {
            html.push_str(&format!("<li>{}</li>", escape(source)));
        }
        html.push_str("</ul></details>");
    }
    for (path, label) in [
        ("mutation.json", "Mutation data"),
        ("pit-reports/mutations.xml", "PIT XML"),
        ("pit-reports/index.html", "PIT HTML"),
    ] {
        let file = request.output.join(path);
        if file.is_file() {
            let link = super::adapter_view::relative_link(reports, &file)?;
            html.push_str(&format!("<a href=\"{link}\">{label}</a> "));
        }
    }
    if !report.mutants.is_empty() {
        html.push_str("<table><thead><tr><th>Status</th><th>Source</th><th>Method</th><th>Mutation</th><th>Killing test</th></tr></thead><tbody>");
        for mutant in &report.mutants {
            html.push_str(&format!("<tr><td>{}</td><td>{}:{}</td><td>{}::{}</td><td>{}<br><small>{}</small></td><td>{}</td></tr>", escape(&mutant.status), escape(&mutant.source), mutant.line, escape(&mutant.class_name), escape(&mutant.method), escape(&mutant.description), escape(&mutant.mutator), escape(mutant.killing_test.as_deref().unwrap_or("—"))));
        }
        html.push_str("</tbody></table>");
    }
    html.push_str("</div>");
    Ok(html)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn report() -> MutationReport {
        serde_json::from_value(serde_json::json!({
            "tool": "pitest", "status": "complete", "sources": ["src/main/java/Price.java"],
            "mutants": [
                {"status":"KILLED", "detected":true, "source":"src/main/java/Price.java", "line":2, "class":"Price", "method":"price", "mutator":"returns", "description":"<script>alert(1)</script>", "killing_test":"PriceTest.price"},
                {"status":"SURVIVED", "detected":false, "source":"src/main/java/Price.java", "line":3, "class":"Price", "method":"price", "mutator":"returns", "description":"replaced return", "killing_test":null},
                {"status":"NO_COVERAGE", "detected":false, "source":"src/main/java/Price.java", "line":4, "class":"Price", "method":"unused", "mutator":"returns", "description":"replaced return", "killing_test":null}
            ]
        })).unwrap()
    }

    #[test]
    fn score_includes_surviving_and_uncovered_mutants() {
        let text = summary(&report());
        assert!(text.contains("33.3% (1/3 detected)"));
        assert!(text.contains("KILLED: 1, NO_COVERAGE: 1, SURVIVED: 1"));
    }

    #[test]
    fn no_changes_is_not_reported_as_a_perfect_score() {
        let report = MutationReport {
            tool: "pitest".into(),
            status: "no_changes".into(),
            sources: Vec::new(),
            mutants: Vec::new(),
        };
        assert!(summary(&report).contains("no changed production sources"));
        assert!(!summary(&report).contains('%'));
    }

    #[test]
    fn detail_preserves_statuses_and_escapes_mutant_text() {
        let temp = tempfile::tempdir().unwrap();
        let request: AdapterRequest = serde_json::from_value(serde_json::json!({"protocol":1,"project":"demo","package":"prices","phase":"mutation","root":temp.path(),"manifest":temp.path().join("pom.xml"),"cwd":temp.path(),"output":temp.path(),"execution":"local","coverage":false,"strict":true})).unwrap();
        let html = details(&request, &report(), temp.path()).unwrap();
        assert!(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"));
        assert!(!html.contains("<script>"));
        assert!(html.contains("SURVIVED</td>"));
        assert!(html.contains("NO_COVERAGE</td>"));
        assert!(html.contains("src/main/java/Price.java:2"));
    }
}
