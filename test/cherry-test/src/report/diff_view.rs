use std::collections::{BTreeMap, BTreeSet};
use std::path::Path;

use anyhow::Result;
use cherry_test_core::TestItConfig;

use crate::coverage::merge_line_coverage_for_project_file;
use crate::coverage_ignore::CoverageIgnore;
use crate::discover::ProjectGroup;
use crate::git_diff;
use crate::report::model::ProjectReport;
use crate::run_dir::RunDir;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DiffTag {
    Context,
    Add,
    Del,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LineCov {
    Covered,
    Uncovered,
    Fail,
    Na,
}

#[derive(Debug, Clone)]
pub struct DiffLine {
    pub old_no: Option<u32>,
    pub new_no: Option<u32>,
    pub tag: DiffTag,
    pub text: String,
    pub coverage: LineCov,
}

#[derive(Debug, Clone)]
pub struct DiffHunk {
    pub header: String,
    pub lines: Vec<DiffLine>,
}

#[derive(Debug, Clone)]
pub struct FileDiffSection {
    pub path: String,
    pub adds: u32,
    pub dels: u32,
    pub coverage_pct: f64,
    pub uncovered_pct: f64,
    pub has_uncovered: bool,
    pub fail_lines: u32,
    pub unparsed_diff: bool,
    pub hunks: Vec<DiffHunk>,
}

#[derive(Debug, Clone)]
pub struct ModuleDiffSection {
    pub path: String,
    pub file_count: usize,
    pub coverage_pct: f64,
    pub uncovered_pct: f64,
    pub has_uncovered: bool,
    pub files: Vec<FileDiffSection>,
}

#[derive(Debug, Clone)]
pub struct ProjectDiffSection {
    pub project_id: String,
    pub tests_passed: bool,
    pub file_count: usize,
    pub coverage_pct: f64,
    pub uncovered_pct: f64,
    pub modules: Vec<ModuleDiffSection>,
}

pub fn added_lines_by_file(sections: &[ProjectDiffSection]) -> Vec<(String, BTreeSet<u32>)> {
    let mut files = Vec::new();
    for project in sections {
        for module in &project.modules {
            for file in &module.files {
                let mut lines = BTreeSet::new();
                for hunk in &file.hunks {
                    for line in &hunk.lines {
                        if line.tag == DiffTag::Add {
                            if let Some(number) = line.new_no {
                                lines.insert(number);
                            }
                        }
                    }
                }
                if !lines.is_empty() {
                    files.push((file.path.clone(), lines));
                }
            }
        }
    }
    files
}

pub fn build_project_diff_sections(
    workspace_root: &Path,
    run_dir: &RunDir,
    config: &TestItConfig,
    projects: &[ProjectGroup],
    project_reports: &[ProjectReport],
    coverage_ignore: &CoverageIgnore,
) -> Result<Vec<ProjectDiffSection>> {
    let mut project_ids = BTreeSet::new();
    for report in project_reports {
        project_ids.insert(report.project_id.clone());
    }
    let mut out = Vec::new();
    for project_id in project_ids {
        let reports: Vec<_> = project_reports
            .iter()
            .filter(|r| r.project_id == project_id)
            .collect();
        let tests_passed = reports.iter().all(|r| r.tests_passed);
        let mut paths = BTreeSet::new();
        for report in &reports {
            for file in &report.changed_files {
                paths.insert(file.path.clone());
            }
        }
        let diff_base = config.diff_base_for_project(&project_id);
        let project = projects
            .iter()
            .find(|p| p.id == project_id)
            .ok_or_else(|| anyhow::anyhow!("unknown project {project_id}"))?;
        let module_prefixes = git_diff::package_prefixes(workspace_root, config, project);
        let mut files = Vec::new();
        for path in paths {
            let packages: Vec<String> = reports.iter().map(|r| r.package.clone()).collect();
            let coverage_ignored = coverage_ignore.is_ignored(&path);
            let line_cov = if coverage_ignored {
                BTreeMap::new()
            } else {
                merge_line_coverage_for_project_file(
                    run_dir,
                    &project_id,
                    &packages,
                    &path,
                    coverage_ignore,
                )?
            };
            let config_base = config.config_base();
            let project_root = config
                .project_path(&project_id)
                .unwrap_or_else(|_| workspace_root.join(&project_id));
            let (git_root, path_prefix) = cherry_test_common::git_diff::resolve_project_git_scope(
                &config_base,
                &project_root,
                None,
            );
            let diff_text = cherry_test_common::git_diff::git_unified_diff_scoped(
                &config_base,
                &git_root,
                &path_prefix,
                &path,
                &diff_base,
                5,
            )?;
            let package_failed =
                package_failed_for_path(&path, &project_id, &reports, &module_prefixes);
            let mut section = build_file_diff_section(&path, &diff_text, &line_cov, package_failed);
            if coverage_ignored {
                section = strip_coverage(section);
            }
            if section.hunks.is_empty()
                && section.adds == 0
                && section.dels == 0
                && !section.unparsed_diff
                && line_cov.is_empty()
            {
                continue;
            }
            files.push(section);
        }
        if files.is_empty() {
            continue;
        }
        files.sort_by(|a, b| a.path.cmp(&b.path));
        let modules = group_files_into_modules(files, &module_prefixes);
        let coverage_pct = aggregate_module_coverage(&modules);
        let uncovered_pct = aggregate_module_uncovered(&modules);
        out.push(ProjectDiffSection {
            project_id: project_id.clone(),
            tests_passed,
            file_count: modules.iter().map(|m| m.file_count).sum(),
            coverage_pct,
            uncovered_pct,
            modules,
        });
    }
    Ok(out)
}

fn package_failed_for_path(
    path: &str,
    project_id: &str,
    reports: &[&ProjectReport],
    module_prefixes: &[String],
) -> bool {
    let module = git_diff::module_path_for_file(path, module_prefixes);
    reports
        .iter()
        .find(|report| module == format!("{}/{}", project_id, report.package))
        .map(|report| !report.tests_passed)
        .unwrap_or(false)
}

pub fn group_files_into_modules(
    files: Vec<FileDiffSection>,
    module_prefixes: &[String],
) -> Vec<ModuleDiffSection> {
    let mut grouped: BTreeMap<String, Vec<FileDiffSection>> = BTreeMap::new();
    for file in files {
        let module_path = git_diff::module_path_for_file(&file.path, module_prefixes);
        grouped.entry(module_path).or_default().push(file);
    }
    grouped
        .into_iter()
        .map(|(path, mut files)| {
            files.sort_by(|a, b| a.path.cmp(&b.path));
            let has_uncovered = files.iter().any(|file| file.has_uncovered);
            ModuleDiffSection {
                file_count: files.len(),
                coverage_pct: aggregate_file_coverage(&files),
                uncovered_pct: aggregate_file_uncovered(&files),
                has_uncovered,
                path,
                files,
            }
        })
        .collect()
}

fn build_file_diff_section(
    path: &str,
    diff_text: &str,
    line_cov: &BTreeMap<u32, bool>,
    project_failed: bool,
) -> FileDiffSection {
    let hunks = parse_unified_diff(diff_text, line_cov, project_failed);
    let unparsed_diff = !diff_text.trim().is_empty() && hunks.is_empty();
    let mut adds = 0u32;
    let mut dels = 0u32;
    let mut has_uncovered = false;
    let mut fail_lines = 0u32;
    for hunk in &hunks {
        for line in &hunk.lines {
            match line.tag {
                DiffTag::Add => adds += 1,
                DiffTag::Del => dels += 1,
                DiffTag::Context => {}
            }
            match line.coverage {
                LineCov::Uncovered => has_uncovered = true,
                LineCov::Fail => {
                    has_uncovered = true;
                    fail_lines += 1;
                }
                _ => {}
            }
        }
    }
    if hunks.is_empty() && !line_cov.is_empty() {
        for covered in line_cov.values() {
            if !covered {
                has_uncovered = true;
                break;
            }
        }
    }
    let (coverage_pct, uncovered_pct) = if hunks.is_empty() && !line_cov.is_empty() {
        line_map_coverage_breakdown(line_cov)
    } else {
        diff_coverage_breakdown(&hunks)
    };
    FileDiffSection {
        path: path.to_string(),
        adds,
        dels,
        coverage_pct,
        uncovered_pct,
        has_uncovered,
        fail_lines,
        unparsed_diff,
        hunks,
    }
}

fn strip_coverage(mut section: FileDiffSection) -> FileDiffSection {
    for hunk in &mut section.hunks {
        for line in &mut hunk.lines {
            line.coverage = LineCov::Na;
        }
    }
    section.has_uncovered = false;
    section.fail_lines = 0;
    let (coverage_pct, uncovered_pct) = diff_coverage_breakdown(&section.hunks);
    section.coverage_pct = coverage_pct;
    section.uncovered_pct = uncovered_pct;
    section
}

pub fn diff_coverage_breakdown(hunks: &[DiffHunk]) -> (f64, f64) {
    let mut hit = 0u64;
    let mut found = 0u64;
    for hunk in hunks {
        for line in &hunk.lines {
            match line.coverage {
                LineCov::Covered => {
                    found += 1;
                    hit += 1;
                }
                LineCov::Uncovered | LineCov::Fail => {
                    found += 1;
                }
                LineCov::Na => {}
            }
        }
    }
    if found == 0 {
        (f64::NAN, f64::NAN)
    } else {
        let covered = (hit as f64 / found as f64) * 100.0;
        (covered, 100.0 - covered)
    }
}

fn line_map_coverage_breakdown(line_cov: &BTreeMap<u32, bool>) -> (f64, f64) {
    if line_cov.is_empty() {
        return (f64::NAN, f64::NAN);
    }
    let found = line_cov.len() as u64;
    let hit = line_cov.values().filter(|covered| **covered).count() as u64;
    if found == 0 {
        (f64::NAN, f64::NAN)
    } else {
        let covered = (hit as f64 / found as f64) * 100.0;
        (covered, 100.0 - covered)
    }
}

pub fn format_covered_uncovered_pair(covered: f64, uncovered: f64) -> (String, String) {
    (format_pct(covered), format_pct(uncovered))
}

fn aggregate_file_coverage(files: &[FileDiffSection]) -> f64 {
    aggregate_pct_values(files.iter().map(|f| f.coverage_pct))
}

fn aggregate_file_uncovered(files: &[FileDiffSection]) -> f64 {
    aggregate_pct_values(files.iter().map(|f| f.uncovered_pct))
}

fn aggregate_module_coverage(modules: &[ModuleDiffSection]) -> f64 {
    aggregate_pct_values(modules.iter().map(|m| m.coverage_pct))
}

fn aggregate_module_uncovered(modules: &[ModuleDiffSection]) -> f64 {
    aggregate_pct_values(modules.iter().map(|m| m.uncovered_pct))
}

fn aggregate_pct_values(values: impl Iterator<Item = f64>) -> f64 {
    let values: Vec<f64> = values.filter(|v| !v.is_nan()).collect();
    if values.is_empty() {
        f64::NAN
    } else {
        values.iter().sum::<f64>() / values.len() as f64
    }
}

pub fn parse_unified_diff(
    diff_text: &str,
    line_cov: &BTreeMap<u32, bool>,
    project_failed: bool,
) -> Vec<DiffHunk> {
    let mut hunks = Vec::new();
    let mut current: Option<DiffHunk> = None;
    let mut old_line = 0u32;
    let mut new_line = 0u32;
    for line in diff_text.lines() {
        if line.starts_with("@@ ") {
            if let Some(hunk) = current.take() {
                if !hunk.lines.is_empty() {
                    hunks.push(hunk);
                }
            }
            let (old, new) = parse_hunk_header(line);
            old_line = old;
            new_line = new;
            current = Some(DiffHunk {
                header: line.to_string(),
                lines: Vec::new(),
            });
            continue;
        }
        let Some(hunk) = current.as_mut() else {
            continue;
        };
        if line.starts_with('\\') {
            continue;
        }
        if line.is_empty() {
            continue;
        }
        let tag_byte = line.as_bytes().first().copied().unwrap_or(b' ');
        let text = line.get(1..).unwrap_or("").to_string();
        let (tag, old_no, new_no) = match tag_byte {
            b'+' => {
                let no = Some(new_line);
                new_line += 1;
                (DiffTag::Add, None, no)
            }
            b'-' => {
                let no = Some(old_line);
                old_line += 1;
                (DiffTag::Del, no, None)
            }
            _ => {
                let old_no = Some(old_line);
                let new_no = Some(new_line);
                old_line += 1;
                new_line += 1;
                (DiffTag::Context, old_no, new_no)
            }
        };
        let coverage = coverage_for_line(new_no, tag, line_cov, project_failed);
        hunk.lines.push(DiffLine {
            old_no,
            new_no,
            tag,
            text,
            coverage,
        });
    }
    if let Some(hunk) = current {
        if !hunk.lines.is_empty() {
            hunks.push(hunk);
        }
    }
    hunks
}

fn coverage_for_line(
    new_no: Option<u32>,
    tag: DiffTag,
    line_cov: &BTreeMap<u32, bool>,
    project_failed: bool,
) -> LineCov {
    if tag == DiffTag::Del {
        return LineCov::Na;
    }
    let Some(no) = new_no else {
        return LineCov::Na;
    };
    match line_cov.get(&no) {
        Some(true) => LineCov::Covered,
        Some(false) if project_failed => LineCov::Fail,
        Some(false) => LineCov::Uncovered,
        None => LineCov::Na,
    }
}

fn parse_hunk_header(line: &str) -> (u32, u32) {
    let mut old = 1u32;
    let mut new = 1u32;
    let parts: Vec<_> = line.split_whitespace().collect();
    if parts.len() >= 3 {
        if let Some(old_part) = parts[1].strip_prefix('-') {
            old = old_part
                .split(',')
                .next()
                .unwrap_or("1")
                .parse()
                .unwrap_or(1);
        }
        if let Some(new_part) = parts[2].strip_prefix('+') {
            new = new_part
                .split(',')
                .next()
                .unwrap_or("1")
                .parse()
                .unwrap_or(1);
        }
    }
    (old, new)
}

pub fn format_pct(value: f64) -> String {
    if value.is_nan() {
        "-".into()
    } else {
        format!("{value:.0}%")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_unified_diff_builds_hunk_lines() {
        let diff = "\
diff --git a/src/lib.rs b/src/lib.rs
@@ -1,3 +1,4 @@
 fn main() {
+    let x = 1;
 }
";
        let mut cov = BTreeMap::new();
        cov.insert(2, true);
        cov.insert(3, false);
        let hunks = parse_unified_diff(diff, &cov, false);
        assert_eq!(hunks.len(), 1);
        assert_eq!(hunks[0].lines.len(), 3);
        assert_eq!(hunks[0].lines[1].tag, DiffTag::Add);
        assert_eq!(hunks[0].lines[1].coverage, LineCov::Covered);
    }

    #[test]
    fn file_coverage_pct_counts_executable_lines() {
        let hunks = vec![DiffHunk {
            header: "@@".into(),
            lines: vec![
                DiffLine {
                    old_no: None,
                    new_no: Some(1),
                    tag: DiffTag::Add,
                    text: "a".into(),
                    coverage: LineCov::Covered,
                },
                DiffLine {
                    old_no: None,
                    new_no: Some(2),
                    tag: DiffTag::Add,
                    text: "b".into(),
                    coverage: LineCov::Uncovered,
                },
            ],
        }];
        let (covered, uncovered) = diff_coverage_breakdown(&hunks);
        assert_eq!(covered, 50.0);
        assert_eq!(uncovered, 50.0);
    }

    #[test]
    fn diff_coverage_breakdown_is_nan_without_executable_lines() {
        let hunks = vec![DiffHunk {
            header: "@@".into(),
            lines: vec![DiffLine {
                old_no: Some(1),
                new_no: Some(1),
                tag: DiffTag::Context,
                text: "ctx".into(),
                coverage: LineCov::Na,
            }],
        }];
        let (covered, uncovered) = diff_coverage_breakdown(&hunks);
        assert!(covered.is_nan());
        assert!(uncovered.is_nan());
    }

    #[test]
    fn format_covered_uncovered_pair_shows_dash_when_missing() {
        assert_eq!(
            format_covered_uncovered_pair(f64::NAN, f64::NAN),
            ("-".into(), "-".into())
        );
        assert_eq!(
            format_covered_uncovered_pair(39.0, 61.0),
            ("39%".into(), "61%".into())
        );
    }

    #[test]
    fn build_file_diff_section_counts_fail_lines() {
        let diff = "\
@@ -1,1 +1,3 @@
 fn main() {
+    let x = 1;
+    let y = 2;
 }
";
        let mut cov = BTreeMap::new();
        cov.insert(2, false);
        cov.insert(3, false);
        let section = build_file_diff_section("src/lib.rs", diff, &cov, true);
        assert_eq!(section.fail_lines, 2);
        assert!(section.has_uncovered);
    }

    #[test]
    fn strip_coverage_clears_stats() {
        let hunks = vec![DiffHunk {
            header: "@@".into(),
            lines: vec![DiffLine {
                old_no: None,
                new_no: Some(1),
                tag: DiffTag::Add,
                text: "x".into(),
                coverage: LineCov::Uncovered,
            }],
        }];
        let section = FileDiffSection {
            path: "mini/mini-rs-lib-mcap/Cargo.toml".into(),
            adds: 1,
            dels: 0,
            coverage_pct: 0.0,
            uncovered_pct: 100.0,
            has_uncovered: true,
            fail_lines: 0,
            unparsed_diff: false,
            hunks,
        };
        let stripped = strip_coverage(section);
        assert!(stripped.coverage_pct.is_nan());
        assert!(stripped.uncovered_pct.is_nan());
        assert!(!stripped.has_uncovered);
        assert!(stripped
            .hunks
            .iter()
            .flat_map(|h| &h.lines)
            .all(|l| l.coverage == LineCov::Na));
    }

    #[test]
    fn group_files_into_modules_splits_by_package() {
        let files = vec![
            FileDiffSection {
                path: "mini/mini-ts-web-react/src/App.tsx".into(),
                adds: 1,
                dels: 0,
                coverage_pct: 80.0,
                uncovered_pct: 20.0,
                has_uncovered: true,
                fail_lines: 0,
                unparsed_diff: false,
                hunks: vec![],
            },
            FileDiffSection {
                path: "mini/mini-ts-lib-price/src/displayAddressUsdt.ts".into(),
                adds: 2,
                dels: 0,
                coverage_pct: 90.0,
                uncovered_pct: 10.0,
                has_uncovered: true,
                fail_lines: 0,
                unparsed_diff: false,
                hunks: vec![],
            },
        ];
        let prefixes = vec![
            "mini/mini-ts-web-react/".into(),
            "mini/mini-ts-lib-price/".into(),
        ];
        let modules = group_files_into_modules(files, &prefixes);
        assert_eq!(modules.len(), 2);
        assert_eq!(modules[0].path, "mini/mini-ts-lib-price");
        assert_eq!(modules[0].file_count, 1);
        assert_eq!(modules[1].path, "mini/mini-ts-web-react");
        assert_eq!(modules[1].file_count, 1);
    }

    #[test]
    fn package_failed_for_path_uses_owning_package() {
        use super::package_failed_for_path;
        use crate::report::model::ProjectReport;

        let mini_rust = ProjectReport {
            project_id: "mini".into(),
            package: "mini-rs-api".into(),
            tests_passed: true,
            coverage: Default::default(),
            diff_coverage: Default::default(),
            changed_files: vec![],
        };
        let mini_ts = ProjectReport {
            project_id: "mini".into(),
            package: "mini-ts-lib-price".into(),
            tests_passed: false,
            coverage: Default::default(),
            diff_coverage: Default::default(),
            changed_files: vec![],
        };
        let reports = vec![&mini_rust, &mini_ts];
        let prefixes = vec!["mini/mini-rs-api/".into(), "mini/mini-ts-lib-price/".into()];
        assert!(!package_failed_for_path(
            "mini/mini-rs-api/src/lib.rs",
            "mini",
            &reports,
            &prefixes,
        ));
        assert!(package_failed_for_path(
            "mini/mini-ts-lib-price/src/displayAddressUsdt.ts",
            "mini",
            &reports,
            &prefixes,
        ));
    }

    #[test]
    fn uncovered_becomes_fail_when_project_failed() {
        let mut cov = BTreeMap::new();
        cov.insert(2, false);
        let line = coverage_for_line(Some(2), DiffTag::Add, &cov, true);
        assert_eq!(line, LineCov::Fail);
        let line = coverage_for_line(Some(2), DiffTag::Add, &cov, false);
        assert_eq!(line, LineCov::Uncovered);
    }
}
