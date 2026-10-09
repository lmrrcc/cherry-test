use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::process::Command;

use anyhow::{Context, Result};

use crate::config::TestKind;
use crate::run_dir::RunDir;

pub fn llvm_cov_available() -> bool {
    Command::new("cargo")
        .args(["llvm-cov", "--version"])
        .output()
        .map(|o| o.status.success())
        .unwrap_or(false)
}

pub struct CoveragePaths {
    pub project_dir: PathBuf,
    pub lcov_dir: PathBuf,
}

impl CoveragePaths {
    pub fn new(run_dir: &RunDir, project_id: &str) -> Self {
        Self {
            project_dir: run_dir.targets_dir(project_id),
            lcov_dir: run_dir.coverage_dir(project_id),
        }
    }

    pub fn lcov_file(&self, package_name: &str) -> PathBuf {
        self.lcov_dir.join(format!("{package_name}.lcov"))
    }

    pub fn kind_lcov_file(&self, package_name: &str, kind: TestKind) -> PathBuf {
        self.lcov_dir
            .join(format!("{package_name}-{}.lcov", kind.as_str()))
    }

    pub fn ensure_dirs(&self) -> Result<()> {
        std::fs::create_dir_all(&self.project_dir)
            .with_context(|| format!("create {}", self.project_dir.display()))?;
        std::fs::create_dir_all(&self.lcov_dir)
            .with_context(|| format!("create {}", self.lcov_dir.display()))?;
        Ok(())
    }
}

pub fn run_llvm_cov_test(
    workspace_root: &Path,
    package: &str,
    target_dir: &Path,
    lcov_out: &Path,
    cargo_args: &[&str],
    test_args: &[&str],
    extra_cargo_test_args: &[String],
) -> Result<()> {
    let mut cmd = Command::new("cargo");
    cmd.current_dir(workspace_root);
    cmd.env("CARGO_TARGET_DIR", target_dir);
    cmd.args([
        "llvm-cov",
        "test",
        "--package",
        package,
        "--lcov",
        "--output-path",
    ]);
    cmd.arg(lcov_out);
    cmd.args(cargo_args);
    if !test_args.is_empty() || !extra_cargo_test_args.is_empty() {
        cmd.arg("--");
        cmd.args(test_args);
        for arg in extra_cargo_test_args {
            cmd.arg(arg);
        }
    }

    let status = cmd
        .status()
        .with_context(|| format!("cargo llvm-cov test -p {package}"))?;
    if !status.success() {
        anyhow::bail!("cargo llvm-cov test failed for package {package}");
    }
    Ok(())
}

pub fn run_cargo_test(
    workspace_root: &Path,
    package: &str,
    target_dir: &Path,
    cargo_args: &[&str],
    test_args: &[&str],
    extra_cargo_test_args: &[String],
) -> Result<()> {
    let mut cmd = Command::new("cargo");
    cmd.current_dir(workspace_root);
    cmd.env("CARGO_TARGET_DIR", target_dir);
    cmd.args(["test", "--package", package]);
    cmd.args(cargo_args);
    if !test_args.is_empty() || !extra_cargo_test_args.is_empty() {
        cmd.arg("--");
        cmd.args(test_args);
        for arg in extra_cargo_test_args {
            cmd.arg(arg);
        }
    }

    let status = cmd
        .status()
        .with_context(|| format!("cargo test -p {package}"))?;
    if !status.success() {
        anyhow::bail!("cargo test failed for package {package}");
    }
    Ok(())
}

pub fn kind_cargo_args(kind: TestKind) -> (&'static [&'static str], &'static [&'static str]) {
    match kind {
        TestKind::Unit => (&["--lib"], &[]),
        TestKind::Integration => (&["--test", "*"], &["--ignored", "--test-threads=1"]),
        TestKind::Functional | TestKind::Ui | TestKind::Visual | TestKind::Mutation => (&[], &[]),
    }
}

pub fn report_project_coverage(
    workspace_root: &Path,
    target_dir: &Path,
    merged_lcov: &Path,
    html_dir: &Path,
    html: bool,
) -> Result<()> {
    if let Some(parent) = merged_lcov.parent() {
        std::fs::create_dir_all(parent).context("create merged lcov parent")?;
    }
    if html {
        std::fs::create_dir_all(html_dir).context("create html report dir")?;
    }

    let mut lcov_cmd = Command::new("cargo");
    lcov_cmd.current_dir(workspace_root);
    lcov_cmd.env("CARGO_TARGET_DIR", target_dir);
    lcov_cmd.args(["llvm-cov", "report", "--lcov", "--output-path"]);
    lcov_cmd.arg(merged_lcov);

    let lcov_status = lcov_cmd.status().context("cargo llvm-cov report --lcov")?;
    if !lcov_status.success() {
        anyhow::bail!("cargo llvm-cov lcov report failed");
    }

    if html {
        let mut html_cmd = Command::new("cargo");
        html_cmd.current_dir(workspace_root);
        html_cmd.env("CARGO_TARGET_DIR", target_dir);
        html_cmd.args(["llvm-cov", "report", "--html", "--output-dir"]);
        html_cmd.arg(html_dir);

        let html_status = html_cmd.status().context("cargo llvm-cov report --html")?;
        if !html_status.success() {
            anyhow::bail!("cargo llvm-cov html report failed");
        }
    }

    Ok(())
}

pub fn merge_lcov_files(sources: &[PathBuf], dest: &Path) -> Result<()> {
    if sources.is_empty() {
        return Ok(());
    }
    crate::adapters::lcov::merge(sources, dest)
}

pub fn summary_for_lcov(
    lcov_path: &Path,
    ignore: &crate::coverage_ignore::CoverageIgnore,
) -> Result<CoverageSummary> {
    if !lcov_path.exists() {
        return Ok(CoverageSummary::default());
    }

    let files = parse_lcov_files(lcov_path)?;
    Ok(files
        .iter()
        .filter(|file| !ignore.is_ignored(&file.path))
        .map(|file| file.summary())
        .fold(CoverageSummary::default(), |mut acc, summary| {
            acc.lines_hit += summary.lines_hit;
            acc.lines_found += summary.lines_found;
            acc.functions_hit += summary.functions_hit;
            acc.functions_found += summary.functions_found;
            acc
        }))
}

pub fn parse_lcov_files(lcov_path: &Path) -> Result<Vec<FileCoverage>> {
    if !lcov_path.exists() {
        return Ok(Vec::new());
    }
    let content = std::fs::read_to_string(lcov_path)
        .with_context(|| format!("read {}", lcov_path.display()))?;
    Ok(parse_lcov_content(&content))
}

pub fn parse_lcov_content(content: &str) -> Vec<FileCoverage> {
    let mut files = Vec::new();
    let mut current: Option<FileCoverage> = None;

    for line in content.lines() {
        if let Some(rest) = line.strip_prefix("SF:") {
            if let Some(file) = current.take() {
                files.push(file);
            }
            current = Some(FileCoverage {
                path: rest.trim().to_string(),
                ..Default::default()
            });
        } else if let Some(file) = current.as_mut() {
            if let Some(rest) = line.strip_prefix("LH:") {
                file.lines_hit = rest.trim().parse().unwrap_or(0);
            } else if let Some(rest) = line.strip_prefix("LF:") {
                file.lines_found = rest.trim().parse().unwrap_or(0);
            } else if let Some(rest) = line.strip_prefix("FNH:") {
                file.functions_hit = rest.trim().parse().unwrap_or(0);
            } else if let Some(rest) = line.strip_prefix("FNF:") {
                file.functions_found = rest.trim().parse().unwrap_or(0);
            } else if line == "end_of_record" {
                if let Some(file) = current.take() {
                    files.push(file);
                }
            }
        }
    }

    if let Some(file) = current {
        files.push(file);
    }

    files
}

pub fn parse_lcov_line_coverage(lcov_path: &Path) -> Result<Vec<FileLineCoverage>> {
    if !lcov_path.exists() {
        return Ok(Vec::new());
    }
    let content = std::fs::read_to_string(lcov_path)
        .with_context(|| format!("read {}", lcov_path.display()))?;
    Ok(parse_lcov_line_content(&content))
}

pub fn parse_lcov_line_content(content: &str) -> Vec<FileLineCoverage> {
    let mut files = Vec::new();
    let mut current: Option<FileLineCoverage> = None;
    for line in content.lines() {
        if let Some(rest) = line.strip_prefix("SF:") {
            if let Some(file) = current.take() {
                files.push(file);
            }
            current = Some(FileLineCoverage {
                path: rest.trim().to_string(),
                lines: BTreeMap::new(),
            });
        } else if let Some(file) = current.as_mut() {
            if let Some(rest) = line.strip_prefix("DA:") {
                if let Some((line_no, hits)) = rest.split_once(',') {
                    if let Ok(no) = line_no.trim().parse::<u32>() {
                        let hit = hits.trim().parse::<u64>().unwrap_or(0) > 0;
                        file.lines.insert(no, hit);
                    }
                }
            } else if line == "end_of_record" {
                if let Some(file) = current.take() {
                    files.push(file);
                }
            }
        }
    }
    if let Some(file) = current {
        files.push(file);
    }
    files
}

pub fn merge_line_coverage_maps(
    left: &BTreeMap<u32, bool>,
    right: &BTreeMap<u32, bool>,
) -> BTreeMap<u32, bool> {
    let mut out = left.clone();
    for (&line, &hit) in right {
        out.entry(line)
            .and_modify(|v| *v = *v || hit)
            .or_insert(hit);
    }
    out
}

pub fn merge_line_coverage_files(files: &[FileLineCoverage]) -> BTreeMap<u32, bool> {
    let mut out = BTreeMap::new();
    for file in files {
        out = merge_line_coverage_maps(&out, &file.lines);
    }
    out
}

pub fn line_coverage_for_path(
    files: &[FileLineCoverage],
    changed_path: &str,
) -> BTreeMap<u32, bool> {
    files
        .iter()
        .filter(|f| path_matches_changed(&f.path, &[changed_path.to_string()]))
        .map(|f| f.lines.clone())
        .reduce(|a, b| merge_line_coverage_maps(&a, &b))
        .unwrap_or_default()
}

pub fn merge_line_coverage_for_project_file(
    run_dir: &RunDir,
    project_id: &str,
    packages: &[String],
    changed_path: &str,
    ignore: &crate::coverage_ignore::CoverageIgnore,
) -> Result<BTreeMap<u32, bool>> {
    let cov_paths = CoveragePaths::new(run_dir, project_id);
    let mut merged = BTreeMap::new();
    for package in packages {
        let unit_lcov = cov_paths.kind_lcov_file(package, TestKind::Unit);
        let package_lcov = cov_paths.lcov_file(package);
        let map = merge_line_coverage_from_lcovs(
            unit_lcov.exists().then_some(unit_lcov.as_path()),
            None,
            package_lcov.exists().then_some(package_lcov.as_path()),
            changed_path,
            ignore,
        )?;
        merged = merge_line_coverage_maps(&merged, &map);
    }
    Ok(merged)
}

pub fn merge_line_coverage_from_lcovs(
    unit_lcov: Option<&Path>,
    integration_lcov: Option<&Path>,
    merged_lcov: Option<&Path>,
    changed_path: &str,
    ignore: &crate::coverage_ignore::CoverageIgnore,
) -> Result<BTreeMap<u32, bool>> {
    let mut maps = Vec::new();
    for path in [unit_lcov, integration_lcov, merged_lcov]
        .into_iter()
        .flatten()
    {
        let files = ignore.filter_line_coverage(parse_lcov_line_coverage(path)?);
        maps.push(line_coverage_for_path(&files, changed_path));
    }
    Ok(maps
        .into_iter()
        .reduce(|a, b| merge_line_coverage_maps(&a, &b))
        .unwrap_or_default())
}

pub fn normalize_lcov_sf_paths(
    content: &str,
    workspace_root: &Path,
    package_prefix: &str,
) -> String {
    let workspace = normalize_path(&workspace_root.to_string_lossy().to_string());
    let prefix = normalize_path(package_prefix);
    let mut out = String::new();
    for line in content.lines() {
        if let Some(rest) = line.strip_prefix("SF:") {
            let sf = normalize_sf_path(rest.trim(), &workspace, &prefix);
            out.push_str("SF:");
            out.push_str(&sf);
            out.push('\n');
        } else {
            out.push_str(line);
            out.push('\n');
        }
    }
    out
}

pub fn write_normalized_lcov(
    source: &Path,
    dest: &Path,
    workspace_root: &Path,
    package_prefix: &str,
) -> Result<()> {
    let content =
        std::fs::read_to_string(source).with_context(|| format!("read {}", source.display()))?;
    let normalized = normalize_lcov_sf_paths(&content, workspace_root, package_prefix);
    std::fs::write(dest, normalized).with_context(|| format!("write {}", dest.display()))?;
    Ok(())
}

fn normalize_sf_path(path: &str, workspace: &str, package_prefix: &str) -> String {
    let path = normalize_path(path);
    if path.starts_with(&format!("{workspace}/")) {
        return path;
    }
    if path.starts_with(&format!("{package_prefix}/")) {
        return path;
    }
    if path.starts_with('/') || path.contains(":/") {
        if let Some(relative) = path.strip_prefix(&format!("{workspace}/")) {
            return relative.to_string();
        }
        return path;
    }
    format!("{package_prefix}/{path}")
}

pub fn normalize_path(path: &str) -> String {
    path.replace('\\', "/")
}

pub fn path_matches_changed(file_path: &str, changed_paths: &[String]) -> bool {
    let normalized = normalize_path(file_path);
    changed_paths
        .iter()
        .any(|changed| paths_refer_to_same_file(&normalized, &normalize_path(changed)))
}

fn paths_refer_to_same_file(left: &str, right: &str) -> bool {
    if left == right {
        return true;
    }
    if left.ends_with(&format!("/{right}")) || right.ends_with(&format!("/{left}")) {
        return true;
    }
    let left_name = left.rsplit('/').next().unwrap_or(left);
    let right_name = right.rsplit('/').next().unwrap_or(right);
    if left_name != right_name {
        return false;
    }
    right.ends_with(&format!("/{left_name}")) || left.ends_with(&format!("/{right_name}"))
}

pub fn summary_for_files(
    files: &[FileCoverage],
    changed_paths: &[String],
    ignore: &crate::coverage_ignore::CoverageIgnore,
) -> CoverageSummary {
    let changed_paths = ignore.filter_paths(changed_paths.iter());
    files
        .iter()
        .filter(|f| path_matches_changed(&f.path, &changed_paths))
        .map(|f| f.summary())
        .fold(CoverageSummary::default(), |mut acc, s| {
            acc.lines_hit += s.lines_hit;
            acc.lines_found += s.lines_found;
            acc.functions_hit += s.functions_hit;
            acc.functions_found += s.functions_found;
            acc
        })
}

pub fn file_coverage_for_project(
    unit_lcov: Option<&Path>,
    integration_lcov: Option<&Path>,
    merged_lcov: Option<&Path>,
    changed_paths: &[String],
    ignore: &crate::coverage_ignore::CoverageIgnore,
) -> Result<Vec<ChangedFileCoverage>> {
    let changed_paths = ignore.filter_paths(changed_paths.iter());
    let unit_files = unit_lcov
        .map(parse_lcov_files)
        .transpose()?
        .map(|files| ignore.filter_file_coverage(files))
        .unwrap_or_default();
    let integration_files = integration_lcov
        .map(parse_lcov_files)
        .transpose()?
        .map(|files| ignore.filter_file_coverage(files))
        .unwrap_or_default();
    let merged_files = merged_lcov
        .map(parse_lcov_files)
        .transpose()?
        .map(|files| ignore.filter_file_coverage(files))
        .unwrap_or_default();

    let mut out = Vec::new();
    for path in &changed_paths {
        let unit = find_file_summary(&unit_files, path.as_str());
        let integration = find_file_summary(&integration_files, path.as_str());
        let merged = find_file_summary(&merged_files, path.as_str()).unwrap_or_default();
        if unit.is_none() && integration.is_none() && merged.lines_found == 0 {
            out.push(ChangedFileCoverage {
                path: path.clone(),
                unit: None,
                integration: None,
                merged: CoverageSummary::default(),
            });
            continue;
        }
        out.push(ChangedFileCoverage {
            path: path.clone(),
            unit,
            integration,
            merged,
        });
    }
    out.sort_by(|a, b| a.path.cmp(&b.path));
    Ok(out)
}

fn find_file_summary(files: &[FileCoverage], path: &str) -> Option<CoverageSummary> {
    files
        .iter()
        .find(|f| path_matches_changed(&f.path, &[path.to_string()]))
        .map(|f| f.summary())
}

pub fn merge_summaries(summaries: &[CoverageSummary]) -> CoverageSummary {
    summaries
        .iter()
        .cloned()
        .fold(CoverageSummary::default(), |mut acc, s| {
            acc.lines_hit += s.lines_hit;
            acc.lines_found += s.lines_found;
            acc.functions_hit += s.functions_hit;
            acc.functions_found += s.functions_found;
            acc
        })
}

#[derive(Debug, Clone, Default)]
pub struct CoverageSummary {
    pub lines_hit: u64,
    pub lines_found: u64,
    pub functions_hit: u64,
    pub functions_found: u64,
}

#[derive(Debug, Clone, Default)]
pub struct FileCoverage {
    pub path: String,
    pub lines_hit: u64,
    pub lines_found: u64,
    pub functions_hit: u64,
    pub functions_found: u64,
}

#[derive(Debug, Clone, Default)]
pub struct FileLineCoverage {
    pub path: String,
    pub lines: BTreeMap<u32, bool>,
}

#[derive(Debug, Clone, Default)]
pub struct ChangedFileCoverage {
    pub path: String,
    pub unit: Option<CoverageSummary>,
    pub integration: Option<CoverageSummary>,
    pub merged: CoverageSummary,
}

impl FileCoverage {
    pub fn summary(&self) -> CoverageSummary {
        CoverageSummary {
            lines_hit: self.lines_hit,
            lines_found: self.lines_found,
            functions_hit: self.functions_hit,
            functions_found: self.functions_found,
        }
    }
}

impl CoverageSummary {
    pub fn lines_percent(&self) -> f64 {
        percent(self.lines_hit, self.lines_found)
    }

    pub fn functions_percent(&self) -> f64 {
        percent(self.functions_hit, self.functions_found)
    }

    pub fn lines_uncovered(&self) -> u64 {
        self.lines_found.saturating_sub(self.lines_hit)
    }
}

fn percent(hit: u64, found: u64) -> f64 {
    if found == 0 {
        0.0
    } else {
        (hit as f64 / found as f64) * 100.0
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::Path;

    #[test]
    fn kind_lcov_file_uses_kind_suffix() {
        let dir = tempfile::tempdir().unwrap();
        let run = crate::run_dir::RunDir::create(dir.path(), Path::new("runs")).unwrap();
        let paths = CoveragePaths::new(&run, "mini");
        let path = paths.kind_lcov_file("mini-rs-api", TestKind::Integration);
        assert_eq!(
            path,
            run.coverage_dir("mini")
                .join("mini-rs-api-integration.lcov")
        );
    }

    #[test]
    fn parse_lcov_content_reads_file_records() {
        let content = "\
SF:/ws/mini/mini-rs-api/src/lib.rs
LF:10
LH:7
FNF:2
FNH:1
end_of_record
";
        let files = parse_lcov_content(content);
        assert_eq!(files.len(), 1);
        assert_eq!(files[0].lines_found, 10);
        assert_eq!(files[0].lines_hit, 7);
    }

    #[test]
    fn parse_lcov_line_content_reads_da_records() {
        let content = "\
SF:/ws/mini/mini-rs-api/src/lib.rs
DA:12,1
DA:13,0
end_of_record
";
        let files = parse_lcov_line_content(content);
        assert_eq!(files.len(), 1);
        assert_eq!(files[0].lines.get(&12), Some(&true));
        assert_eq!(files[0].lines.get(&13), Some(&false));
    }

    #[test]
    fn merge_line_coverage_or_hits() {
        let mut a = BTreeMap::new();
        a.insert(1, false);
        a.insert(2, true);
        let mut b = BTreeMap::new();
        b.insert(1, true);
        b.insert(3, false);
        let merged = merge_line_coverage_maps(&a, &b);
        assert_eq!(merged.get(&1), Some(&true));
        assert_eq!(merged.get(&2), Some(&true));
        assert_eq!(merged.get(&3), Some(&false));
    }

    #[test]
    fn summary_for_files_filters_changed_paths() {
        let files = vec![FileCoverage {
            path: "/ws/mini/mini-rs-api/src/api.rs".into(),
            lines_hit: 5,
            lines_found: 10,
            functions_hit: 1,
            functions_found: 2,
        }];
        let summary = summary_for_files(
            &files,
            &["mini/mini-rs-api/src/api.rs".into()],
            &crate::coverage_ignore::CoverageIgnore::default(),
        );
        assert_eq!(summary.lines_hit, 5);
        assert_eq!(summary.lines_found, 10);
    }

    #[test]
    fn summary_for_files_skips_ignored_changed_path() {
        let files = vec![FileCoverage {
            path: "mini/mini-rs-lib-mcap/Cargo.toml".into(),
            lines_hit: 0,
            lines_found: 20,
            functions_hit: 0,
            functions_found: 0,
        }];
        let ignore =
            crate::coverage_ignore::CoverageIgnore::from_patterns(vec!["**/Cargo.toml".into()]);
        let summary = summary_for_files(
            &files,
            &["mini/mini-rs-lib-mcap/Cargo.toml".into()],
            &ignore,
        );
        assert_eq!(summary.lines_found, 0);
    }

    #[test]
    fn path_matches_vitest_relative_lcov_path() {
        let changed = vec!["mini/mini-ts-lib-price/src/displayAddressUsdt.ts".into()];
        assert!(path_matches_changed("src/displayAddressUsdt.ts", &changed));
        assert!(path_matches_changed(
            "mini/mini-ts-lib-price/src/displayAddressUsdt.ts",
            &changed
        ));
    }

    #[test]
    fn normalize_lcov_sf_paths_rewrites_relative_sf() {
        let content = "SF:src/displayAddressUsdt.ts\nDA:1,1\nend_of_record\n";
        let normalized =
            normalize_lcov_sf_paths(content, Path::new("/ws"), "mini/mini-ts-lib-price");
        assert!(normalized.contains("SF:mini/mini-ts-lib-price/src/displayAddressUsdt.ts"));
    }

    #[test]
    fn summary_for_lcov_excludes_ignored_node_modules() {
        let dir = tempfile::tempdir().unwrap();
        let lcov = dir.path().join("coverage.lcov");
        std::fs::write(
            &lcov,
            "SF:mini/mini-ts-lib-price/src/displayAddressUsdt.ts\nLF:10\nLH:9\nend_of_record\nSF:mini/mini-ts-lib-price/node_modules/foo/index.js\nLF:100\nLH:50\nend_of_record\n",
        )
        .unwrap();
        let ignore =
            crate::coverage_ignore::CoverageIgnore::from_patterns(
                vec!["**/node_modules/**".into()],
            );
        let summary = summary_for_lcov(&lcov, &ignore).unwrap();
        assert_eq!(summary.lines_found, 10);
        assert_eq!(summary.lines_hit, 9);
    }
}
