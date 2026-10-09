use std::path::{Path, PathBuf};

use anyhow::{bail, Context, Result};
use cherry_test_common::adapter::{Attachment, TestResult, TestStatus};

pub fn case(
    name: String,
    status: TestStatus,
    message: Option<String>,
    duration_ms: u64,
) -> TestResult {
    TestResult {
        name,
        status,
        duration_ms,
        message,
        source: None,
        line: None,
        attachments: Vec::new(),
    }
}

pub fn junit(content: &str) -> Result<Vec<TestResult>> {
    let doc = roxmltree::Document::parse(content).context("parse JUnit XML")?;
    if !matches!(
        doc.root_element().tag_name().name(),
        "testsuite" | "testsuites"
    ) {
        bail!("expected JUnit testsuite or testsuites");
    }
    let mut cases = Vec::new();
    for node in doc.descendants().filter(|n| n.has_tag_name("testcase")) {
        let name = node
            .attribute("name")
            .context("JUnit testcase missing name")?;
        let failure = node
            .children()
            .find(|n| n.has_tag_name("failure") || n.has_tag_name("error"));
        let skip = node.children().find(|n| n.has_tag_name("skipped"));
        let status = if failure.is_some() {
            TestStatus::Failed
        } else if skip.is_some() {
            TestStatus::Skipped
        } else {
            TestStatus::Passed
        };
        let detail = failure.or(skip).map(|n| {
            format!(
                "{}\n{}",
                n.attribute("message").unwrap_or(""),
                n.text().unwrap_or("")
            )
        });
        let seconds = node
            .attribute("time")
            .unwrap_or("0")
            .parse::<f64>()
            .context("JUnit duration")?;
        if !seconds.is_finite() || seconds < 0.0 {
            bail!("invalid JUnit duration");
        }
        let mut result = case(
            format!("{}::{name}", node.attribute("classname").unwrap_or("tests")),
            status,
            detail,
            (seconds * 1000.0) as u64,
        );
        result.source = node.attribute("file").map(PathBuf::from);
        result.line = node.attribute("line").map(str::parse).transpose()?;
        cases.push(result);
    }
    for suite in doc.descendants().filter(|n| n.has_tag_name("testsuite")) {
        for field in ["failures", "errors"] {
            let declared: usize = suite
                .attribute(field)
                .unwrap_or("0")
                .parse()
                .context("JUnit suite count")?;
            let tag = if field == "failures" {
                "failure"
            } else {
                "error"
            };
            let recorded = suite
                .descendants()
                .filter(|node| node.has_tag_name(tag))
                .count();
            if declared > recorded {
                bail!("JUnit declares {declared} {field} but reports only {recorded}");
            }
        }
        for failure in suite
            .children()
            .filter(|n| n.has_tag_name("failure") || n.has_tag_name("error"))
        {
            cases.push(case(
                suite.attribute("name").unwrap_or("suite setup").into(),
                TestStatus::Failed,
                Some(failure.text().unwrap_or("suite failed").into()),
                0,
            ));
        }
    }
    Ok(cases)
}

pub fn jacoco(content: &str, source_roots: &[PathBuf]) -> Result<String> {
    let content = if let Some(start) = content.find("<!DOCTYPE") {
        let end = content[start..]
            .find('>')
            .context("invalid JaCoCo doctype")?
            + start;
        if content[start..end].contains('[') {
            bail!("JaCoCo internal DTD is not supported");
        }
        format!("{}{}", &content[..start], &content[end + 1..])
    } else {
        content.to_string()
    };
    let doc = roxmltree::Document::parse(&content).context("parse JaCoCo XML")?;
    let mut out = String::new();
    for package in doc.descendants().filter(|n| n.has_tag_name("package")) {
        let package_name = package.attribute("name").context("JaCoCo package name")?;
        for source in package.children().filter(|n| n.has_tag_name("sourcefile")) {
            let name = source.attribute("name").context("JaCoCo source name")?;
            let rel = Path::new(package_name).join(name);
            if rel.is_absolute()
                || rel
                    .components()
                    .any(|c| matches!(c, std::path::Component::ParentDir))
            {
                bail!("invalid JaCoCo source path");
            }
            let matches = source_roots
                .iter()
                .map(|r| r.join(&rel))
                .filter(|p| p.is_file())
                .collect::<Vec<_>>();
            if matches.len() != 1 {
                bail!("JaCoCo source {} must resolve uniquely", rel.display());
            }
            out.push_str(&format!("SF:{}\n", matches[0].display()));
            for line in source.children().filter(|n| n.has_tag_name("line")) {
                let number: u32 = line
                    .attribute("nr")
                    .context("JaCoCo line number")?
                    .parse()?;
                let covered: u64 = line
                    .attribute("ci")
                    .context("JaCoCo covered instructions")?
                    .parse()?;
                if number == 0 {
                    bail!("invalid JaCoCo line zero");
                }
                out.push_str(&format!("DA:{number},{}\n", u8::from(covered > 0)));
            }
            out.push_str("end_of_record\n");
        }
    }
    Ok(out)
}

pub fn rust_tests(output: &str) -> Vec<TestResult> {
    output
        .lines()
        .filter_map(|line| {
            let value = line.strip_prefix("test ")?;
            let (name, status) = value.rsplit_once(" ... ")?;
            let state = match status.trim() {
                "ok" => TestStatus::Passed,
                "FAILED" => TestStatus::Failed,
                value if value.starts_with("ignored") => TestStatus::Skipped,
                _ => return None,
            };
            let panic = (state == TestStatus::Failed)
                .then(|| rust_panic(output, name))
                .flatten();
            let mut result = case(
                name.into(),
                state,
                panic.as_ref().and_then(|panic| panic.message.clone()),
                0,
            );
            if let Some(location) = panic.and_then(|panic| panic.location) {
                result.source = Some(location.0);
                result.line = Some(location.1);
            }
            Some(result)
        })
        .collect()
}

struct RustPanic {
    message: Option<String>,
    location: Option<(PathBuf, u32)>,
}

/// Reads the panic libtest prints in the `---- <name> stdout ----` block of a failed test:
/// the `file:line:column` after `panicked at` (same line or the next), then the message lines.
fn rust_panic(output: &str, name: &str) -> Option<RustPanic> {
    let header = format!("---- {name} stdout ----");
    let block = &output[output.find(&header)? + header.len()..];
    let after_marker = &block[block.find("panicked at")? + "panicked at".len()..];
    let mut lines = after_marker.lines();
    let first = lines.next().unwrap_or_default().trim();
    let location_text = if first.is_empty() {
        lines.next().unwrap_or_default().trim()
    } else {
        first
    };
    let message = lines
        .take_while(|line| {
            !line.is_empty() && !line.starts_with("note: ") && !line.starts_with("---- ")
        })
        .collect::<Vec<_>>()
        .join("\n");
    Some(RustPanic {
        message: (!message.is_empty()).then_some(message),
        location: rust_location(location_text),
    })
}

/// Parses `path:line:column:` as printed by a Rust panic.
fn rust_location(text: &str) -> Option<(PathBuf, u32)> {
    let mut pieces = text.trim_end_matches(':').rsplitn(3, ':');
    let column = pieces.next()?;
    let line = pieces.next()?.parse::<u32>().ok()?;
    let path = pieces.next()?;
    (column.parse::<u32>().is_ok() && !path.is_empty()).then(|| (PathBuf::from(path), line))
}

pub fn vitest(content: &str) -> Result<Vec<TestResult>> {
    let data: serde_json::Value = serde_json::from_str(content)?;
    let suites = data["testResults"]
        .as_array()
        .context("Vitest testResults missing")?;
    let mut out = Vec::new();
    for suite in suites {
        for test in suite["assertionResults"]
            .as_array()
            .context("Vitest assertionResults missing")?
        {
            let status = match test["status"].as_str() {
                Some("passed") => TestStatus::Passed,
                Some("skipped" | "pending" | "todo") => TestStatus::Skipped,
                _ => TestStatus::Failed,
            };
            let mut result = case(
                test["fullName"]
                    .as_str()
                    .or(test["title"].as_str())
                    .context("Vitest test name missing")?
                    .into(),
                status,
                test["failureMessages"].as_array().map(|m| {
                    m.iter()
                        .filter_map(|v| v.as_str())
                        .collect::<Vec<_>>()
                        .join("\n")
                }),
                test["duration"].as_f64().unwrap_or(0.0).max(0.0) as u64,
            );
            result.source = suite["name"].as_str().map(PathBuf::from);
            out.push(result);
        }
    }
    Ok(out)
}

pub fn playwright(content: &str) -> Result<Vec<TestResult>> {
    let root: serde_json::Value = serde_json::from_str(content)?;
    let mut out = Vec::new();
    fn visit(suite: &serde_json::Value, out: &mut Vec<TestResult>) -> Result<()> {
        if let Some(specs) = suite["specs"].as_array() {
            for spec in specs {
                for test in spec["tests"]
                    .as_array()
                    .context("Playwright tests missing")?
                {
                    let status = match test["status"].as_str() {
                        Some("expected") => TestStatus::Passed,
                        Some("skipped") => TestStatus::Skipped,
                        _ => TestStatus::Failed,
                    };
                    let runs = test["results"]
                        .as_array()
                        .context("Playwright results missing")?;
                    let name = format!(
                        "{}:{}:{} [{}]",
                        spec["file"].as_str().unwrap_or(""),
                        spec["line"],
                        spec["title"].as_str().unwrap_or(""),
                        test["projectName"].as_str().unwrap_or("")
                    );
                    let mut result = case(
                        name,
                        status,
                        runs.last()
                            .and_then(|r| r["error"]["message"].as_str())
                            .map(str::to_owned),
                        runs.iter().filter_map(|r| r["duration"].as_u64()).sum(),
                    );
                    result.source = spec["file"].as_str().map(PathBuf::from);
                    result.line = spec["line"].as_u64().map(|v| v as u32);
                    for run in runs {
                        if let Some(attachments) = run["attachments"].as_array() {
                            for attachment in attachments {
                                if let Some(path) = attachment["path"].as_str() {
                                    result.attachments.push(Attachment {
                                        name: attachment["name"]
                                            .as_str()
                                            .unwrap_or("attachment")
                                            .into(),
                                        path: path.into(),
                                        media_type: attachment["contentType"]
                                            .as_str()
                                            .unwrap_or("application/octet-stream")
                                            .into(),
                                    });
                                }
                            }
                        }
                    }
                    out.push(result);
                }
            }
        }
        if let Some(children) = suite["suites"].as_array() {
            for child in children {
                visit(child, out)?;
            }
        }
        Ok(())
    }
    visit(&root, &mut out)?;
    if let Some(errors) = root["errors"].as_array() {
        for error in errors {
            out.push(case(
                "Playwright setup".into(),
                TestStatus::Failed,
                error["message"].as_str().map(str::to_owned),
                0,
            ));
        }
    }
    Ok(out)
}

pub fn files(root: &Path, extension: &str) -> Result<Vec<PathBuf>> {
    let mut out = Vec::new();
    if !root.exists() {
        return Ok(out);
    }
    if root.is_file() {
        if root.extension().and_then(|v| v.to_str()) == Some(extension) {
            out.push(root.to_path_buf());
        }
        return Ok(out);
    }
    for item in std::fs::read_dir(root)? {
        let item = item?;
        if item.file_type()?.is_symlink() {
            continue;
        }
        out.extend(files(&item.path(), extension)?);
    }
    out.sort();
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    mod rust_tests {
        use super::*;

        const FAILED_RUN: &str = "running 2 tests
test catalog::lists ... ok
test catalog::reports_a_price_mismatch ... FAILED

failures:

---- catalog::reports_a_price_mismatch stdout ----

thread 'catalog::reports_a_price_mismatch' (4523666) panicked at src/tests.rs:217:9:
assertion `left == right` failed: listed price did not match the expected 1500
  left: 1800
 right: 1500
note: run with `RUST_BACKTRACE=1` environment variable to display a backtrace


failures:
    catalog::reports_a_price_mismatch

test result: FAILED. 1 passed; 1 failed; 0 ignored; 0 measured; 0 filtered out
";

        #[test]
        fn keeps_the_panic_message_of_a_failed_test() {
            let rows = rust_tests(FAILED_RUN);
            assert_eq!(
                rows[1].message.as_deref(),
                Some("assertion `left == right` failed: listed price did not match the expected 1500\n  left: 1800\n right: 1500")
            );
        }

        #[test]
        fn leaves_passed_tests_without_a_message() {
            let rows = rust_tests(FAILED_RUN);
            assert_eq!(rows[0].message, None);
        }

        #[test]
        fn keeps_a_failure_without_captured_output_unexplained() {
            let rows = rust_tests("test catalog::times_out ... FAILED\n");
            assert_eq!(rows[0].status, TestStatus::Failed);
            assert_eq!(rows[0].message, None);
        }

        #[test]
        fn keeps_the_file_of_the_failed_assertion() {
            let rows = rust_tests(FAILED_RUN);
            assert_eq!(rows[1].source, Some(PathBuf::from("src/tests.rs")));
        }

        #[test]
        fn keeps_the_line_of_the_failed_assertion() {
            let rows = rust_tests(FAILED_RUN);
            assert_eq!(rows[1].line, Some(217));
        }

        #[test]
        fn reads_a_location_on_the_line_after_the_thread_name() {
            let output = "test a::b ... FAILED\n---- a::b stdout ----\nthread 'a::b' panicked at\nsrc/lib.rs:9:5:\nboom\n";
            let rows = rust_tests(output);
            assert_eq!(
                (rows[0].source.clone(), rows[0].line),
                (Some(PathBuf::from("src/lib.rs")), Some(9))
            );
        }
    }

    #[test]
    fn junit_preserves_failures_skips_and_parameterized_names() {
        let xml = r#"<testsuite><testcase classname="Prices" name="round[1]" time="0.125"/><testcase name="upstream"><failure message="expected 502">received 200</failure></testcase><testcase name="optional"><skipped/></testcase></testsuite>"#;
        let rows = junit(xml).unwrap();
        assert_eq!(rows.len(), 3);
        assert_eq!(rows[0].duration_ms, 125);
        assert_eq!(rows[1].status, TestStatus::Failed);
        assert!(rows[1].message.as_deref().unwrap().contains("received 200"));
        assert_eq!(rows[2].status, TestStatus::Skipped);
    }

    #[test]
    fn junit_rejects_external_entities_and_wrong_documents() {
        assert!(junit(r#"<!DOCTYPE a [<!ENTITY secret SYSTEM "file:///etc/passwd">]><testsuite><testcase name="x">&secret;</testcase></testsuite>"#).is_err());
        assert!(junit("<project/>").is_err());
        assert!(junit("<testsuite><testcase name='x' time='NaN'/></testsuite>").is_err());
    }

    #[test]
    fn jacoco_resolves_source_and_preserves_uncovered_lines() {
        let temp = tempfile::tempdir().unwrap();
        std::fs::create_dir(temp.path().join("prices")).unwrap();
        std::fs::write(temp.path().join("prices/Rate.java"), "class Rate {}\n").unwrap();
        let xml = r#"<?xml version="1.0"?><!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd"><report><package name="prices"><sourcefile name="Rate.java"><line nr="1" ci="3" mi="0"/><line nr="2" ci="0" mi="4"/></sourcefile></package></report>"#;
        let text = jacoco(xml, &[temp.path().to_path_buf()]).unwrap();
        assert!(text.contains("DA:1,1\nDA:2,0"));
        assert!(jacoco(xml, &[]).is_err());
    }

    #[test]
    fn playwright_keeps_failed_screenshot_attachments() {
        let data = r#"{"suites":[{"specs":[{"title":"price","file":"price.spec.ts","line":4,"tests":[{"projectName":"java","status":"unexpected","results":[{"duration":12,"error":{"message":"pixels differ"},"attachments":[{"name":"diff","path":"/tmp/diff.png","contentType":"image/png"}]}]}]}]}]}"#;
        let rows = playwright(data).unwrap();
        assert_eq!(rows[0].status, TestStatus::Failed);
        assert_eq!(rows[0].attachments[0].name, "diff");
    }
}
