use std::path::{Path, PathBuf};

use anyhow::{Context, Result};

use crate::http::HttpRecord;
use crate::workspace::workspace_root;

fn resolve_report_dir() -> Result<PathBuf> {
    if let Ok(dir) = std::env::var("CHERRY_TEST_REPORT_DIR") {
        return Ok(PathBuf::from(dir));
    }
    let root = workspace_root()?;
    if let Ok(run_dir) = std::env::var("CHERRY_TEST_RUN_DIR") {
        return Ok(PathBuf::from(run_dir).join("reports"));
    }
    Ok(root.join("target/cherry-test/reports"))
}

pub fn append_records(package: &str, records: &[HttpRecord]) -> Result<()> {
    if records.is_empty() {
        return Ok(());
    }

    let report_dir = resolve_report_dir()?;
    let integration_dir = report_dir.join("integration");
    std::fs::create_dir_all(&integration_dir)?;

    let path = integration_dir.join(format!("{package}.jsonl"));
    let mut file = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(&path)
        .with_context(|| format!("open {}", path.display()))?;

    use std::io::Write;
    for record in records {
        let line = serde_json::json!({
            "project": record.test_id.project,
            "suite": record.test_id.suite,
            "test": record.test_id.test,
            "full_name": record.test_id.full_name,
            "scope": record.scope,
            "target_project": record.target_project,
            "target_service": record.target_service,
            "elapsed_ms": record.elapsed_ms,
            "method": record.method,
            "url": record.url,
            "status": record.status,
            "passed": record.passed,
            "reason": record.failure_reason,
            "request_body": record.request_body,
            "authorization": record.authorization,
            "response_body": record.response_body,
        });
        writeln!(file, "{}", line)?;
    }

    Ok(())
}

pub fn append_skipped_records(package: &str, rows: &[serde_json::Value]) -> Result<()> {
    if rows.is_empty() {
        return Ok(());
    }
    let report_dir = resolve_report_dir()?;
    let integration_dir = report_dir.join("integration");
    std::fs::create_dir_all(&integration_dir)?;
    let path = integration_dir.join(format!("{package}.jsonl"));
    let mut file = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(&path)
        .with_context(|| format!("open {}", path.display()))?;
    use std::io::Write;
    for row in rows {
        writeln!(file, "{}", row)?;
    }
    Ok(())
}

pub fn read_all_records(report_dir: &Path) -> Result<Vec<serde_json::Value>> {
    let integration_dir = report_dir.join("integration");
    if !integration_dir.exists() {
        return Ok(Vec::new());
    }

    let mut rows = Vec::new();
    for entry in std::fs::read_dir(&integration_dir)? {
        let entry = entry?;
        if entry.path().extension().and_then(|e| e.to_str()) != Some("jsonl") {
            continue;
        }
        let content = std::fs::read_to_string(entry.path())?;
        for line in content.lines() {
            if line.trim().is_empty() {
                continue;
            }
            rows.push(serde_json::from_str(line)?);
        }
    }
    Ok(rows)
}
