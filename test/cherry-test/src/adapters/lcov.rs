use std::collections::BTreeMap;
use std::path::{Path, PathBuf};

use anyhow::{bail, Context, Result};

#[derive(Default)]
struct Record {
    lines: BTreeMap<u64, u64>,
    definitions: BTreeMap<String, String>,
    functions: BTreeMap<String, u64>,
    branches: BTreeMap<String, Option<u64>>,
}

fn add(total: &mut u64, value: u64) -> Result<()> {
    *total = total
        .checked_add(value)
        .context("coverage counter overflow")?;
    Ok(())
}

pub fn merge(sources: &[PathBuf], destination: &Path) -> Result<()> {
    let mut records = BTreeMap::<String, Record>::new();
    for source in sources {
        let content = std::fs::read_to_string(source)
            .with_context(|| format!("read coverage {}", source.display()))?;
        let mut current = None;
        for line in content.lines() {
            if let Some(file) = line.strip_prefix("SF:") {
                if current.is_some() || file.is_empty() {
                    bail!("invalid LCOV source record");
                }
                current = Some(file.to_string());
                records.entry(file.to_string()).or_default();
            } else if line == "end_of_record" {
                current = None;
            } else if let Some(file) = &current {
                let record = records.get_mut(file).context("LCOV current source")?;
                if let Some(value) = line.strip_prefix("DA:") {
                    let mut values = value.split(',');
                    let number = values.next().context("LCOV line")?.parse::<u64>()?;
                    let count = values.next().context("LCOV count")?.parse::<u64>()?;
                    if number == 0 {
                        bail!("invalid LCOV line zero");
                    }
                    add(record.lines.entry(number).or_default(), count)?;
                } else if let Some(value) = line.strip_prefix("FN:") {
                    let (_, name) = value.split_once(',').context("LCOV function definition")?;
                    record
                        .definitions
                        .insert(name.to_string(), value.to_string());
                } else if let Some(value) = line.strip_prefix("FNDA:") {
                    let (count, name) = value.split_once(',').context("LCOV function count")?;
                    add(
                        record.functions.entry(name.to_string()).or_default(),
                        count.parse()?,
                    )?;
                } else if let Some(value) = line.strip_prefix("BRDA:") {
                    let (key, count) = value.rsplit_once(',').context("LCOV branch count")?;
                    let entry = record.branches.entry(key.to_string()).or_default();
                    if count != "-" {
                        add(entry.get_or_insert(0), count.parse()?)?;
                    }
                }
            }
        }
        if current.is_some() {
            bail!("truncated LCOV record in {}", source.display());
        }
    }
    let mut output = String::new();
    for (file, record) in records {
        output.push_str(&format!("SF:{file}\n"));
        for definition in record.definitions.values() {
            output.push_str(&format!("FN:{definition}\n"));
        }
        for (name, count) in &record.functions {
            output.push_str(&format!("FNDA:{count},{name}\n"));
        }
        output.push_str(&format!(
            "FNF:{}\nFNH:{}\n",
            record.functions.len(),
            record
                .functions
                .values()
                .filter(|count| **count > 0)
                .count()
        ));
        for (line, count) in &record.lines {
            output.push_str(&format!("DA:{line},{count}\n"));
        }
        output.push_str(&format!(
            "LF:{}\nLH:{}\n",
            record.lines.len(),
            record.lines.values().filter(|count| **count > 0).count()
        ));
        for (key, count) in &record.branches {
            output.push_str(&format!(
                "BRDA:{key},{}\n",
                count
                    .map(|count| count.to_string())
                    .unwrap_or_else(|| "-".into())
            ));
        }
        output.push_str(&format!(
            "BRF:{}\nBRH:{}\nend_of_record\n",
            record.branches.len(),
            record
                .branches
                .values()
                .filter(|count| count.is_some_and(|count| count > 0))
                .count()
        ));
    }
    std::fs::write(destination, output)?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn merges_overlapping_phases_without_double_counting_lines() {
        let directory = tempfile::tempdir().unwrap();
        let unit = directory.path().join("unit.lcov");
        let integration = directory.path().join("integration.lcov");
        let merged = directory.path().join("merged.lcov");
        std::fs::write(
            &unit,
            "SF:src/service.java\nDA:4,1\nDA:5,0\nBRDA:4,0,0,-\nend_of_record\n",
        )
        .unwrap();
        std::fs::write(
            &integration,
            "SF:src/service.java\nDA:4,2\nDA:5,1\nDA:6,0\nBRDA:4,0,0,2\nend_of_record\n",
        )
        .unwrap();
        merge(&[unit, integration], &merged).unwrap();
        let content = std::fs::read_to_string(&merged).unwrap();
        assert_eq!(content.matches("SF:").count(), 1);
        assert!(content.contains("DA:4,3\n"));
        assert!(content.contains("LF:3\nLH:2\n"));
        assert!(content.contains("BRDA:4,0,0,2\n"));
        merge(std::slice::from_ref(&merged), &merged).unwrap();
        assert_eq!(std::fs::read_to_string(merged).unwrap(), content);
    }

    #[test]
    fn missing_coverage_is_an_error() {
        let directory = tempfile::tempdir().unwrap();
        assert!(merge(
            &[directory.path().join("missing")],
            &directory.path().join("merged")
        )
        .is_err());
    }
}
