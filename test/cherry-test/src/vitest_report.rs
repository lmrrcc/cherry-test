use std::path::Path;

use anyhow::{Context, Result};
use serde_json::Value;

pub fn read_unit_rows(report_dir: &Path) -> Result<Vec<Value>> {
    let unit_dir = report_dir.join("unit");
    if !unit_dir.exists() {
        return Ok(Vec::new());
    }

    let mut rows = Vec::new();
    for entry in std::fs::read_dir(&unit_dir)? {
        let entry = entry?;
        let path = entry.path();
        if path.extension().and_then(|e| e.to_str()) != Some("json") {
            continue;
        }
        let content =
            std::fs::read_to_string(&path).with_context(|| format!("read {}", path.display()))?;
        let package = path.file_stem().and_then(|s| s.to_str()).unwrap_or("");
        rows.extend(parse_vitest_json(&content, package)?);
    }
    Ok(rows)
}

pub fn parse_vitest_json(content: &str, package: &str) -> Result<Vec<Value>> {
    let root: Value = serde_json::from_str(content)?;
    let mut rows = Vec::new();
    let project = infer_project_id(package);

    let suites = root["testResults"].as_array();
    let Some(suites) = suites else {
        return Ok(rows);
    };

    for suite in suites {
        let assertions = suite["assertionResults"].as_array();
        let Some(assertions) = assertions else {
            continue;
        };
        for assertion in assertions {
            let title = assertion["title"].as_str().unwrap_or("unnamed");
            let test_slug = slugify(title);
            let status = match assertion["status"].as_str().unwrap_or("failed") {
                "passed" => "ok",
                "skipped" | "pending" | "todo" => "skipped",
                _ => "FAIL",
            };
            let elapsed_ms = assertion["duration"].as_f64().unwrap_or(0.0).round() as u64;
            let reason = if status == "FAIL" {
                assertion["failureMessages"]
                    .as_array()
                    .and_then(|msgs| msgs.first())
                    .and_then(|v| v.as_str())
                    .map(str::to_string)
            } else if status == "skipped" {
                Some("skipped".into())
            } else {
                None
            };

            rows.push(serde_json::json!({
                "project": project,
                "suite": "unit",
                "test": test_slug,
                "full_name": format!("{project}_project_unit_suite_{test_slug}_test"),
                "scope": "none",
                "target_project": "",
                "target_service": "",
                "elapsed_ms": elapsed_ms,
                "method": "",
                "url": "",
                "status": status,
                "reason": reason,
            }));
        }
    }

    Ok(rows)
}

fn infer_project_id(package: &str) -> String {
    package
        .split_once('-')
        .map(|(project, _)| project.to_string())
        .unwrap_or_else(|| package.to_string())
}

fn slugify(input: &str) -> String {
    let mut out = String::new();
    let mut prev_underscore = false;
    for ch in input.chars() {
        if ch.is_ascii_alphanumeric() {
            out.push(ch.to_ascii_lowercase());
            prev_underscore = false;
        } else if !prev_underscore {
            out.push('_');
            prev_underscore = true;
        }
    }
    let trimmed = out.trim_matches('_');
    if trimmed.is_empty() {
        "unnamed".to_string()
    } else {
        trimmed.to_string()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_vitest_json_assertions() {
        let json = r#"{
          "testResults": [{
            "assertionResults": [{
              "title": "formats USDT with grouping",
              "fullName": "formatUsdtValue formats USDT with grouping",
              "status": "passed",
              "duration": 12.4
            }]
          }]
        }"#;
        let rows = parse_vitest_json(json, "mini-ts-lib-price").unwrap();
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0]["status"], "ok");
        assert_eq!(rows[0]["suite"], "unit");
        assert_eq!(rows[0]["elapsed_ms"], 12);
    }
}
