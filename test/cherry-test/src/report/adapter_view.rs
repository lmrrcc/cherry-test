use std::collections::{BTreeMap, BTreeSet};
use std::path::Path;

use anyhow::{Context, Result};
use cherry_test_common::adapter::{AdapterRequest, AdapterResponse};
use cherry_test_common::report_shell::{diff_row_html, diff_row_with_id};

use super::highlight::{
    highlight_lang_for_path, highlight_linked, record_rendered_definitions, JumpIndex,
};

const SNIPPET_CONTEXT: usize = 3;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum LineMark {
    Context,
    Covered,
    Uncovered,
    Gap,
}

struct MarkedLine {
    number: usize,
    text: String,
    mark: LineMark,
}

fn snippet_lines(source: &str, hits: &BTreeMap<usize, u64>) -> Vec<MarkedLine> {
    let lines: Vec<&str> = source.lines().collect();
    let line_count = lines.len();
    if hits.is_empty() || line_count == 0 {
        return Vec::new();
    }
    let mut measured: Vec<usize> = hits
        .keys()
        .copied()
        .filter(|number| *number >= 1 && *number <= line_count)
        .collect();
    measured.sort_unstable();
    measured.dedup();
    if measured.is_empty() {
        return Vec::new();
    }
    let mut run_start = measured[0];
    let mut run_end = measured[0];
    let mut windows: Vec<(usize, usize)> = Vec::new();
    let push_run = |start: usize, end: usize, windows: &mut Vec<(usize, usize)>| {
        let from = start.saturating_sub(SNIPPET_CONTEXT).max(1);
        let to = end.saturating_add(SNIPPET_CONTEXT).min(line_count);
        if let Some(last) = windows.last_mut() {
            if from <= last.1.saturating_add(1) {
                last.1 = last.1.max(to);
                return;
            }
        }
        windows.push((from, to));
    };
    for number in measured.into_iter().skip(1) {
        if number == run_end.saturating_add(1) {
            run_end = number;
        } else {
            push_run(run_start, run_end, &mut windows);
            run_start = number;
            run_end = number;
        }
    }
    push_run(run_start, run_end, &mut windows);
    let mut marked = Vec::new();
    for (index, (from, to)) in windows.iter().copied().enumerate() {
        if index > 0 {
            marked.push(MarkedLine {
                number: 0,
                text: "…".to_string(),
                mark: LineMark::Gap,
            });
        }
        for number in from..=to {
            let mark = match hits.get(&number) {
                Some(0) => LineMark::Uncovered,
                Some(_) => LineMark::Covered,
                None => LineMark::Context,
            };
            marked.push(MarkedLine {
                number,
                text: lines[number - 1].to_string(),
                mark,
            });
        }
    }
    marked
}

struct CoveredFile {
    relative: String,
    source: String,
    hits: BTreeMap<usize, u64>,
    failed: bool,
}

struct PhaseSection {
    phase: String,
    head: String,
    files: Vec<CoveredFile>,
}

fn rendered_line_numbers(source: &str, hits: &BTreeMap<usize, u64>) -> BTreeSet<usize> {
    snippet_lines(source, hits)
        .into_iter()
        .filter(|line| line.mark != LineMark::Gap)
        .map(|line| line.number)
        .collect()
}

fn render_coverage_snippets(file: &CoveredFile, phase: &str, index: &JumpIndex) -> String {
    let marked = snippet_lines(&file.source, &file.hits);
    if marked.is_empty() {
        return String::new();
    }
    let lang = highlight_lang_for_path(&file.relative);
    let mut html = String::from("<div class=\"diff\">");
    html.push_str(&diff_row_html("column-labels", "old", "new", "±", "code"));
    for line in marked {
        if line.mark == LineMark::Gap {
            html.push_str(&diff_row_html("ellipsis", "", "", "", "…"));
            continue;
        }
        let class = match line.mark {
            LineMark::Covered => "ctx covered",
            LineMark::Uncovered if file.failed => "ctx uncovered fail-line",
            LineMark::Uncovered => "ctx uncovered",
            LineMark::Context | LineMark::Gap => "ctx",
        };
        let number = line.number.to_string();
        let anchor = index
            .row_anchor(phase, &file.relative, line.number)
            .unwrap_or("")
            .to_string();
        let code = highlight_linked(lang, &line.text, |name| {
            index.href(Some(phase), &file.relative, line.number, name)
        });
        html.push_str(&diff_row_with_id(
            &anchor, class, &number, &number, "", &code,
        ));
    }
    html.push_str("</div>");
    html
}

fn escape(value: &str) -> String {
    value
        .replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
        .replace('\'', "&#39;")
}

fn relative_link(reports: &Path, path: &Path) -> Result<String> {
    let path = path.canonicalize()?;
    let root = reports.canonicalize()?;
    let relative = path
        .strip_prefix(root)
        .context("report artifact outside reports")?;
    Ok(relative
        .to_string_lossy()
        .bytes()
        .map(|byte| {
            if byte.is_ascii_alphanumeric() || b"/-_.".contains(&byte) {
                (byte as char).to_string()
            } else {
                format!("%{byte:02X}")
            }
        })
        .collect())
}

pub fn write(reports: &Path) -> Result<JumpIndex> {
    let sections = load_phase_sections(reports)?;
    let index = index_rendered_definitions(&sections);
    let mut body = String::from("<p><a href=\"index.html\">Back to report</a></p><h1>Tests and coverage by phase</h1><p>Markers distinguish covered lines, uncovered lines, and lines with no coverage measurement. Each measured run is shown with the lines above and below it.</p>");
    for section in &sections {
        body.push_str(&section.head);
        for file in &section.files {
            let hit = file.hits.values().filter(|count| **count > 0).count();
            body.push_str(&format!(
                "<details><summary>{}: {hit}/{} covered lines</summary>{}</details>",
                escape(&file.relative),
                file.hits.len(),
                render_coverage_snippets(file, &section.phase, &index)
            ));
        }
        body.push_str("</section>");
    }
    let document = cherry_test_common::report_shell::render_combined_report_document(
        &format!("{} test phases", crate::COMMAND_NAME),
        "",
        &body,
    );
    std::fs::write(reports.join("phases.html"), document)?;
    Ok(index)
}

fn index_rendered_definitions(sections: &[PhaseSection]) -> JumpIndex {
    let mut index = JumpIndex::default();
    for section in sections {
        for file in &section.files {
            let rendered = rendered_line_numbers(&file.source, &file.hits);
            record_rendered_definitions(
                &mut index,
                &section.phase,
                &file.relative,
                &file.source,
                &rendered,
            );
        }
    }
    index
}

fn load_phase_sections(reports: &Path) -> Result<Vec<PhaseSection>> {
    let mut sections = Vec::new();
    for result in crate::adapters::formats::files(&reports.join("adapters"), "json")? {
        if result.file_name().and_then(|name| name.to_str()) != Some("result.json") {
            continue;
        }
        let directory = result.parent().context("adapter report directory")?;
        let request_path = directory.join("request.json");
        if !request_path.is_file() {
            continue;
        }
        let request: AdapterRequest = serde_json::from_slice(&std::fs::read(request_path)?)?;
        let document: serde_json::Value = serde_json::from_slice(&std::fs::read(&result)?)?;
        let response: AdapterResponse = serde_json::from_value(document["result"].clone())?;
        let mut head = format!(
            "<section><h2>{} / {} / {}</h2>",
            escape(&request.project),
            escape(&request.package),
            escape(&request.phase)
        );
        for entry in std::fs::read_dir(directory)? {
            let path = entry?.path();
            if path.is_file()
                && matches!(
                    path.extension().and_then(|value| value.to_str()),
                    Some("stdout" | "stderr" | "log")
                )
            {
                let link = relative_link(reports, &path)?;
                let name = path.file_name().context("log filename")?.to_string_lossy();
                head.push_str(&format!("<a href=\"{link}\">{}</a> ", escape(&name)));
            }
        }
        for diagnostic in response.diagnostics {
            head.push_str(&format!("<pre>{}</pre>", escape(&diagnostic)));
        }
        head.push_str("<ul>");
        for test in response.tests {
            head.push_str(&format!(
                "<li><strong>{:?}</strong> {} ({} ms)",
                test.status,
                escape(&test.name),
                test.duration_ms
            ));
            if let Some(message) = test.message {
                head.push_str(&format!("<pre>{}</pre>", escape(&message)));
            }
            for attachment in test.attachments {
                let url = relative_link(reports, &attachment.path)?;
                head.push_str(&format!(
                    "<p><a href=\"{url}\">{}</a></p>",
                    escape(&attachment.name)
                ));
                if attachment.media_type == "image/png" {
                    head.push_str(&format!(
                        "<img style=\"max-width:100%\" src=\"{url}\" alt=\"{}\">",
                        escape(&attachment.name)
                    ));
                }
            }
            head.push_str("</li>");
        }
        head.push_str("</ul>");
        let mut sources = BTreeMap::<String, BTreeMap<usize, u64>>::new();
        for lcov in response.coverage {
            let mut file = None;
            for line in std::fs::read_to_string(&lcov)?.lines() {
                if let Some(path) = line.strip_prefix("SF:") {
                    file = Some(path.to_string());
                }
                if let (Some(file), Some(value)) = (&file, line.strip_prefix("DA:")) {
                    let mut values = value.split(',');
                    let line: usize = values.next().context("LCOV line")?.parse()?;
                    let count: u64 = values.next().context("LCOV hits")?.parse()?;
                    let hits = sources
                        .entry(file.clone())
                        .or_default()
                        .entry(line)
                        .or_default();
                    *hits = hits
                        .checked_add(count)
                        .context("coverage counter overflow")?;
                }
            }
        }
        let mut files = Vec::new();
        for (source, lines) in sources {
            let path = crate::adapters::builtins::source_path(&request, &source);
            if !path.starts_with(&request.root) {
                continue;
            }
            let path = path.canonicalize()?;
            let root = request.root.canonicalize()?;
            let Ok(relative) = path.strip_prefix(root) else {
                continue;
            };
            let source = std::fs::read_to_string(&path)?;
            files.push(CoveredFile {
                relative: relative.to_string_lossy().to_string(),
                source,
                hits: lines,
                failed: !response.success,
            });
        }
        sections.push(PhaseSection {
            phase: request.phase,
            head,
            files,
        });
    }
    Ok(sections)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn escapes_untrusted_test_output_and_rejects_external_artifacts() {
        assert_eq!(escape("<script>\"&"), "&lt;script&gt;&quot;&amp;");
        let temp = tempfile::tempdir().unwrap();
        let reports = temp.path().join("reports");
        std::fs::create_dir(&reports).unwrap();
        let outside = temp.path().join("private.png");
        std::fs::write(&outside, "private").unwrap();
        assert!(relative_link(&reports, &outside).is_err());
        let inside = reports.join("actual image.png");
        std::fs::write(&inside, "image").unwrap();
        assert_eq!(
            relative_link(&reports, &inside).unwrap(),
            "actual%20image.png"
        );
    }

    #[test]
    fn coverage_window_includes_context_above_and_below_and_stops_at_the_file_edge() {
        let source = "a\nb\nc\nd\ne\nf\ng\n";
        let hits = BTreeMap::from([(2, 1)]);
        let marked = snippet_lines(source, &hits);
        assert_eq!(
            marked.iter().map(|line| line.number).collect::<Vec<_>>(),
            vec![1, 2, 3, 4, 5]
        );
        assert_eq!(marked[0].mark, LineMark::Context);
        assert_eq!(marked[1].mark, LineMark::Covered);
        assert_eq!(marked[1].text, "b");
        assert!(marked.iter().all(|line| line.mark != LineMark::Gap));
    }

    #[test]
    fn adjacent_measured_lines_share_one_snippet() {
        let source = "1\n2\n3\n4\n5\n6\n7\n8\n9\n10\n";
        let hits = BTreeMap::from([(4, 3), (5, 0)]);
        let marked = snippet_lines(source, &hits);
        assert!(marked.iter().all(|line| line.mark != LineMark::Gap));
        assert_eq!(marked.first().map(|line| line.number), Some(1));
        assert_eq!(marked.last().map(|line| line.number), Some(8));
        assert_eq!(marked[3].mark, LineMark::Covered);
        assert_eq!(marked[4].mark, LineMark::Uncovered);
    }

    #[test]
    fn a_gap_between_runs_inserts_a_separator() {
        let source = (1..=20)
            .map(|n| n.to_string())
            .collect::<Vec<_>>()
            .join("\n");
        let hits = BTreeMap::from([(1, 1), (20, 0)]);
        let marked = snippet_lines(&source, &hits);
        let gap = marked.iter().position(|line| line.mark == LineMark::Gap);
        assert!(gap.is_some());
        assert_eq!(marked.first().map(|line| line.number), Some(1));
        assert_eq!(
            marked.last().map(|line| (line.number, line.mark)),
            Some((20, LineMark::Uncovered))
        );
    }

    #[test]
    fn rendered_covered_line_uses_the_bluish_report_class() {
        let file = covered_file("sample.rs", "safe\nlet value = input;\ntrailer\n", false);
        let html = render_coverage_snippets(&file, "unit", &JumpIndex::default());
        assert!(html.contains("class=\"row ctx covered\""));
        assert!(html.contains("class=\"row ctx uncovered\""));
        assert!(html.contains("<span class=\"no old\">old</span>"));
        assert!(html.contains("<span class=\"no new\">new</span>"));
        assert!(html.contains(">code</span>"));
        assert!(html.contains("<span class=\"rail run\">run</span>"));
        assert!(html.contains("<span class=\"rail cov\">cov</span>"));
        assert!(html.contains("<span class=\"no old\">2</span><span class=\"no new\">2</span>"));
        assert!(html.contains("tok-kw\">let</span>"));
        assert!(html.contains("value = input;"));
        assert!(!html.contains("let value = input;"));
        assert!(!html.contains("background:"));
        assert!(!html.contains("#194f39"));
        let mut failed = file;
        failed.hits.insert(3, 0);
        failed.failed = true;
        let failed = render_coverage_snippets(&failed, "unit", &JumpIndex::default());
        assert!(failed.contains("class=\"row ctx uncovered fail-line\""));
        assert!(failed.contains("aria-label=\"failed line\">!</button>"));
    }

    fn covered_file(relative: &str, source: &str, failed: bool) -> CoveredFile {
        CoveredFile {
            relative: relative.to_string(),
            source: source.to_string(),
            hits: BTreeMap::from([(2, 4), (3, 0)]),
            failed,
        }
    }

    #[test]
    fn command_click_target_is_the_rendered_definition_row() {
        let source = "fn answer() {\n    1\n}\nfn caller() {\n    answer()\n}\n";
        let hits = BTreeMap::from([(1, 1), (5, 1)]);
        let file = CoveredFile {
            relative: "lib.rs".to_string(),
            source: source.to_string(),
            hits: hits.clone(),
            failed: false,
        };
        let mut index = JumpIndex::default();
        record_rendered_definitions(
            &mut index,
            "unit",
            "lib.rs",
            source,
            &rendered_line_numbers(source, &hits),
        );
        let html = render_coverage_snippets(&file, "unit", &index);
        assert!(html.contains("id=\"cov-unit-lib-rs-L1\""));
        assert!(html.contains("href=\"#cov-unit-lib-rs-L1\""));
        assert!(html.contains("tok-kw\">fn</span>"));
    }
}
