use std::collections::BTreeMap;
use std::path::{Component, Path, PathBuf};

use crate::coverage::{merge_line_coverage_maps, FileLineCoverage};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Language {
    Rust,
    C,
    Java,
    Kotlin,
    Script,
}

#[derive(Debug, Clone, PartialEq, Eq)]
enum TokKind {
    Ident,
    Op,
}

#[derive(Debug, Clone)]
struct Tok {
    line: u32,
    kind: TokKind,
    text: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct FunctionSpan {
    name: String,
    start: u32,
    end: u32,
    complexity: u32,
}

pub const CRAP_THRESHOLD: f64 = 30.0;

#[derive(Debug, Clone)]
pub struct CrapFunction {
    pub path: String,
    pub name: String,
    pub line: u32,
    pub complexity: u32,
    pub covered_lines: u32,
    pub measured_lines: u32,
    pub coverage: f64,
    pub score: f64,
    pub crap_load: f64,
    pub source: Vec<CrapSourceLine>,
}

/// Whether coverage measured a source line of a scored function.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LineMeasure {
    Covered,
    Uncovered,
    NotMeasured,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CrapSourceLine {
    pub number: u32,
    pub text: String,
    pub measure: LineMeasure,
}

/// How urgently a function's CRAP score needs attention, from worst to best.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CrapSeverity {
    Critical,
    High,
    Moderate,
    Low,
}

const CRAP_HIGH: f64 = CRAP_THRESHOLD / 2.0;
const CRAP_MODERATE: f64 = 5.0;

/// Purpose: band a CRAP score. Ensures: scores at or above CRAP_THRESHOLD are Critical.
pub fn crap_severity(score: f64) -> CrapSeverity {
    if score >= CRAP_THRESHOLD {
        CrapSeverity::Critical
    } else if score >= CRAP_HIGH {
        CrapSeverity::High
    } else if score >= CRAP_MODERATE {
        CrapSeverity::Moderate
    } else {
        CrapSeverity::Low
    }
}

/// Purpose: the lines `start..=end` of `source` with their coverage.
/// Ensures: one entry per existing line in the range, numbered from 1; lines past the end of `source` are omitted.
pub fn function_source(
    source: &str,
    start: u32,
    end: u32,
    coverage: &BTreeMap<u32, bool>,
) -> Vec<CrapSourceLine> {
    source
        .lines()
        .zip(1u32..)
        .skip_while(|(_, number)| *number < start)
        .take_while(|(_, number)| *number <= end)
        .map(|(text, number)| CrapSourceLine {
            number,
            text: text.to_string(),
            measure: match coverage.get(&number) {
                Some(true) => LineMeasure::Covered,
                Some(false) => LineMeasure::Uncovered,
                None => LineMeasure::NotMeasured,
            },
        })
        .collect()
}

#[derive(Debug, Clone, Copy, PartialEq, Default)]
pub struct CrapTotals {
    pub functions: usize,
    pub complexity: u64,
    pub total_crap: f64,
    pub mean_crap: f64,
    pub median_crap: f64,
    pub worst_crap: f64,
    pub crappy_functions: usize,
    pub crappy_percent: f64,
    pub crap_load: f64,
}

#[derive(Debug, Clone, PartialEq)]
pub struct CrapProjectTotals {
    pub project: String,
    pub totals: CrapTotals,
}

#[derive(Debug, Clone)]
pub struct CrapProjectRoot {
    pub id: String,
    pub path: PathBuf,
}

#[derive(Debug, Clone)]
pub struct CrapChange {
    pub functions: Vec<CrapFunction>,
    pub totals: CrapTotals,
}

#[derive(Debug, Clone, Default)]
pub struct CrapReport {
    pub enabled: bool,
    pub functions: Vec<CrapFunction>,
    pub totals: CrapTotals,
    pub projects: Vec<CrapProjectTotals>,
    pub changed: Option<CrapChange>,
}

pub fn crap_score(complexity: u32, coverage: f64) -> f64 {
    let complexity = f64::from(complexity.max(1));
    let gap = (1.0 - coverage).clamp(0.0, 1.0);
    complexity * complexity * gap * gap * gap + complexity
}

pub fn crap_load(complexity: u32, coverage: f64) -> f64 {
    if crap_score(complexity, coverage) < CRAP_THRESHOLD {
        return 0.0;
    }
    let complexity = f64::from(complexity.max(1));
    let gap = (1.0 - coverage).clamp(0.0, 1.0);
    complexity * gap + complexity / CRAP_THRESHOLD
}

pub fn crap_totals(functions: &[CrapFunction]) -> CrapTotals {
    let count = functions.len();
    if count == 0 {
        return CrapTotals::default();
    }
    let mut scores: Vec<f64> = functions.iter().map(|function| function.score).collect();
    scores.sort_by(f64::total_cmp);
    let mid = count / 2;
    let median = if count % 2 == 0 {
        (scores[mid - 1] + scores[mid]) / 2.0
    } else {
        scores[mid]
    };
    let complexity = functions
        .iter()
        .map(|function| u64::from(function.complexity))
        .sum();
    let total_crap = functions.iter().map(|function| function.score).sum();
    let crap_load = functions.iter().map(|function| function.crap_load).sum();
    let crappy = functions
        .iter()
        .filter(|function| function.score >= CRAP_THRESHOLD)
        .count();
    CrapTotals {
        functions: count,
        complexity,
        total_crap,
        mean_crap: total_crap / count as f64,
        median_crap: median,
        worst_crap: scores[count - 1],
        crappy_functions: crappy,
        crappy_percent: crappy as f64 / count as f64 * 100.0,
        crap_load,
    }
}

pub fn change_slice(functions: &[CrapFunction], changed: &[String]) -> Option<CrapChange> {
    if changed.is_empty() {
        return None;
    }
    let matched = functions
        .iter()
        .filter(|function| crate::coverage::path_matches_changed(&function.path, changed))
        .cloned()
        .collect::<Vec<_>>();
    Some(CrapChange {
        totals: crap_totals(&matched),
        functions: matched,
    })
}

pub fn change_on_added_lines(
    functions: &[CrapFunction],
    files: &[(String, std::collections::BTreeSet<u32>)],
) -> Option<CrapChange> {
    if files.is_empty() {
        return None;
    }
    let matched = functions
        .iter()
        .filter(|function| {
            files.iter().any(|(path, lines)| {
                crate::coverage::path_matches_changed(&function.path, std::slice::from_ref(path))
                    && function_touches_lines(function, lines)
            })
        })
        .cloned()
        .collect::<Vec<_>>();
    Some(CrapChange {
        totals: crap_totals(&matched),
        functions: matched,
    })
}

fn function_touches_lines(
    function: &CrapFunction,
    lines: &std::collections::BTreeSet<u32>,
) -> bool {
    if lines.contains(&function.line) {
        return true;
    }
    function
        .source
        .iter()
        .any(|line| lines.contains(&line.number))
}

pub fn project_rollups(
    workspace: &Path,
    functions: &[CrapFunction],
    projects: &[CrapProjectRoot],
) -> Vec<CrapProjectTotals> {
    if projects.is_empty() || functions.is_empty() {
        return Vec::new();
    }
    let mut grouped: BTreeMap<String, Vec<CrapFunction>> = BTreeMap::new();
    let mut outside = Vec::new();
    for function in functions {
        let file = file_path(workspace, &function.path);
        match file_project(&file, projects) {
            FileProject::Ambiguous => return Vec::new(),
            FileProject::Outside => outside.push(function.clone()),
            FileProject::One(id) => grouped.entry(id).or_default().push(function.clone()),
        }
    }
    let mut rollups = Vec::new();
    for (project, group) in grouped {
        rollups.push(CrapProjectTotals {
            project,
            totals: crap_totals(&group),
        });
    }
    if !outside.is_empty() {
        rollups.push(CrapProjectTotals {
            project: "outside projects".to_string(),
            totals: crap_totals(&outside),
        });
    }
    rollups
}

pub fn score_report(
    workspace: &Path,
    files: &[FileLineCoverage],
    projects: &[CrapProjectRoot],
) -> CrapReport {
    let functions = score_files(workspace, files);
    CrapReport {
        enabled: true,
        totals: crap_totals(&functions),
        projects: project_rollups(workspace, &functions, projects),
        functions,
        changed: None,
    }
}

enum FileProject {
    One(String),
    Outside,
    Ambiguous,
}

fn file_project(file: &Path, projects: &[CrapProjectRoot]) -> FileProject {
    let mut best_len = 0usize;
    let mut best: Vec<&str> = Vec::new();
    for project in projects {
        let root = lexical_normalize(&project.path);
        if root.as_os_str().is_empty() {
            continue;
        }
        if file.starts_with(&root) {
            let len = root.components().count();
            if len > best_len {
                best_len = len;
                best.clear();
                best.push(project.id.as_str());
            } else if len == best_len {
                best.push(project.id.as_str());
            }
        }
    }
    match best.as_slice() {
        [] => FileProject::Outside,
        [id] => FileProject::One((*id).to_string()),
        _ => FileProject::Ambiguous,
    }
}

fn file_path(workspace: &Path, path: &str) -> PathBuf {
    let raw = PathBuf::from(path.replace('\\', "/"));
    let joined = if raw.is_absolute() {
        raw
    } else {
        workspace.join(raw)
    };
    lexical_normalize(&joined)
}

fn lexical_normalize(path: &Path) -> PathBuf {
    let mut out = PathBuf::new();
    for component in path.components() {
        match component {
            Component::CurDir => {}
            Component::ParentDir => match out.components().next_back() {
                Some(Component::Normal(_)) => {
                    out.pop();
                }
                Some(Component::ParentDir) | None => out.push(".."),
                _ => {}
            },
            other => out.push(other.as_os_str()),
        }
    }
    out
}

pub fn score_files(workspace: &Path, files: &[FileLineCoverage]) -> Vec<CrapFunction> {
    let mut merged: BTreeMap<String, BTreeMap<u32, bool>> = BTreeMap::new();
    let mut display: BTreeMap<String, String> = BTreeMap::new();
    for file in files {
        if skipped_path(&file.path) {
            continue;
        }
        let key = file.path.replace('\\', "/");
        display
            .entry(key.clone())
            .or_insert_with(|| file.path.clone());
        let entry = merged.entry(key).or_default();
        *entry = merge_line_coverage_maps(entry, &file.lines);
    }
    let mut scored = Vec::new();
    for (key, lines) in &merged {
        let Some(language) = language_for(key) else {
            continue;
        };
        let Some(path) = source_file(workspace, display.get(key).unwrap_or(&key)) else {
            continue;
        };
        let Ok(source) = std::fs::read_to_string(&path) else {
            continue;
        };
        if source.len() > 1_000_000 {
            continue;
        }
        let prepared = match language {
            Language::Script if key.ends_with(".vue") => vue_script(&source),
            _ => source.clone(),
        };
        let parsed = match language {
            Language::Script if key.ends_with(".vue") => Language::Script,
            other => other,
        };
        for function in functions_in(&prepared, parsed) {
            let mut measured = 0u32;
            let mut covered = 0u32;
            for (&line, &hit) in lines {
                if line >= function.start && line <= function.end {
                    measured += 1;
                    if hit {
                        covered += 1;
                    }
                }
            }
            let coverage = if measured == 0 {
                0.0
            } else {
                (f64::from(covered) / f64::from(measured)).clamp(0.0, 1.0)
            };
            scored.push(CrapFunction {
                path: display.get(key).cloned().unwrap_or_else(|| key.clone()),
                name: function.name,
                line: function.start,
                complexity: function.complexity,
                covered_lines: covered,
                measured_lines: measured,
                coverage,
                score: crap_score(function.complexity, coverage),
                crap_load: crap_load(function.complexity, coverage),
                source: function_source(&source, function.start, function.end, lines),
            });
        }
    }
    scored.sort_by(|left, right| {
        right
            .score
            .total_cmp(&left.score)
            .then_with(|| left.path.cmp(&right.path))
            .then_with(|| left.line.cmp(&right.line))
            .then_with(|| left.name.cmp(&right.name))
    });
    scored
}

fn source_file(workspace: &Path, lcov_path: &str) -> Option<PathBuf> {
    let raw = PathBuf::from(lcov_path);
    let candidate = if raw.is_absolute() {
        raw
    } else {
        workspace.join(raw)
    };
    candidate.is_file().then_some(candidate)
}

fn skipped_path(path: &str) -> bool {
    let normalized = path.replace('\\', "/");
    let name = normalized.rsplit('/').next().unwrap_or(&normalized);
    if name.ends_with("_test.rs")
        || name.ends_with("_tests.rs")
        || name.contains(".test.")
        || name.contains(".spec.")
        || name.ends_with("Test.java")
        || name.ends_with("Tests.java")
        || name.ends_with("Test.kt")
        || name.ends_with("Tests.kt")
    {
        return true;
    }
    normalized.split('/').any(|part| {
        matches!(
            part,
            "test" | "tests" | "__tests__" | "node_modules" | "target" | "build"
        )
    })
}

fn language_for(path: &str) -> Option<Language> {
    let name = path.rsplit(['/', '\\']).next().unwrap_or(path);
    let ext = name.rsplit_once('.').map(|(_, ext)| ext)?;
    Some(match ext {
        "rs" => Language::Rust,
        "c" | "h" | "cpp" | "cc" | "cxx" | "hpp" | "hh" | "hxx" => Language::C,
        "java" => Language::Java,
        "kt" | "kts" => Language::Kotlin,
        "js" | "jsx" | "mjs" | "cjs" | "ts" | "tsx" | "mts" | "cts" | "vue" => Language::Script,
        _ => return None,
    })
}

fn vue_script(source: &str) -> String {
    let mut out = String::new();
    let mut inside = false;
    for line in source.lines() {
        let trimmed = line.trim_start();
        if !inside && trimmed.starts_with("<script") {
            inside = true;
            out.push('\n');
            continue;
        }
        if inside && trimmed.starts_with("</script") {
            inside = false;
            out.push('\n');
            continue;
        }
        if inside {
            out.push_str(line);
        }
        out.push('\n');
    }
    out
}

fn functions_in(source: &str, language: Language) -> Vec<FunctionSpan> {
    let tokens = tokenize(source);
    let mut functions = Vec::new();
    let mut stack: Vec<OpenFunction> = Vec::new();
    let mut arms: Vec<i32> = Vec::new();
    let mut brace = 0i32;
    let mut paren = 0i32;
    let mut pending: Option<(String, u32)> = None;
    let mut arm_pending = false;
    for (index, token) in tokens.iter().enumerate() {
        match token.kind {
            TokKind::Ident => {
                if is_decision(&token.text) {
                    if let Some(function) = stack.last_mut() {
                        function.complexity += 1;
                    }
                }
                if matches!(token.text.as_str(), "fn" | "fun" | "function") {
                    pending = Some(next_name(&tokens, index, &token.text));
                }
                if matches!(token.text.as_str(), "match" | "when" | "switch") {
                    arm_pending = true;
                }
            }
            TokKind::Op => {
                if token.text == "{" {
                    brace += 1;
                    if arm_pending {
                        arms.push(brace);
                        arm_pending = false;
                    }
                    if pending.is_none()
                        && matches!(language, Language::C | Language::Java | Language::Script)
                    {
                        pending = signature_name(&tokens, index);
                    }
                    if let Some((name, line)) = pending.take() {
                        stack.push(OpenFunction {
                            name,
                            line,
                            complexity: 1,
                            depth: brace,
                        });
                    }
                } else if token.text == "}" {
                    brace -= 1;
                    while arms.last().is_some_and(|depth| *depth > brace) {
                        arms.pop();
                    }
                    while stack.last().is_some_and(|function| function.depth > brace) {
                        if let Some(function) = stack.pop() {
                            functions.push(FunctionSpan {
                                name: function.name,
                                start: function.line,
                                end: token.line,
                                complexity: function.complexity,
                            });
                        }
                    }
                } else if token.text == "(" {
                    paren += 1;
                } else if token.text == ")" {
                    paren -= 1;
                } else if token.text == ";" && paren == 0 {
                    pending = None;
                    arm_pending = false;
                } else if token.text == "=>" {
                    if arms.last().is_some_and(|depth| *depth == brace) {
                        if let Some(function) = stack.last_mut() {
                            function.complexity += 1;
                        }
                    }
                    if language == Language::Script {
                        pending = Some(arrow_name(&tokens, index));
                    }
                } else if token.text == "->" && arms.last().is_some_and(|depth| *depth == brace) {
                    if let Some(function) = stack.last_mut() {
                        function.complexity += 1;
                    }
                } else if matches!(token.text.as_str(), "&&" | "||") {
                    if let Some(function) = stack.last_mut() {
                        function.complexity += 1;
                    }
                } else if token.text == "?" && language != Language::Rust {
                    if let Some(function) = stack.last_mut() {
                        function.complexity += 1;
                    }
                }
            }
        }
    }
    functions
}

struct OpenFunction {
    name: String,
    line: u32,
    complexity: u32,
    depth: i32,
}

fn is_decision(text: &str) -> bool {
    matches!(
        text,
        "if" | "while" | "for" | "loop" | "catch" | "case" | "elif" | "elsif"
    )
}

fn next_name(tokens: &[Tok], index: usize, keyword: &str) -> (String, u32) {
    let fallback = if keyword == "function" {
        "<function>".to_string()
    } else {
        "<fn>".to_string()
    };
    match tokens.get(index + 1) {
        Some(token) if token.kind == TokKind::Ident && !is_reserved(&token.text) => {
            (token.text.clone(), token.line)
        }
        Some(token) => (fallback, token.line),
        None => (fallback, tokens[index].line),
    }
}

fn arrow_name(tokens: &[Tok], index: usize) -> (String, u32) {
    let mut cursor = index;
    if index > 0 && tokens[index - 1].text == ")" {
        let mut depth = 0i32;
        while cursor > 0 {
            cursor -= 1;
            if tokens[cursor].text == ")" {
                depth += 1;
            } else if tokens[cursor].text == "(" {
                depth -= 1;
                if depth == 0 {
                    break;
                }
            }
        }
    }
    while cursor > 0 {
        cursor -= 1;
        let token = &tokens[cursor];
        if token.kind == TokKind::Ident && !is_reserved(&token.text) {
            return (token.text.clone(), token.line);
        }
        if token.text == ";" || token.text == "{" || token.text == "}" {
            break;
        }
    }
    ("<arrow>".into(), tokens[index].line)
}

fn signature_name(tokens: &[Tok], brace: usize) -> Option<(String, u32)> {
    let mut cursor = brace;
    let mut saw_paren = false;
    while cursor > 0 {
        cursor -= 1;
        let token = &tokens[cursor];
        if token.text == ")" {
            saw_paren = true;
            break;
        }
        if token.text == ";" || token.text == "{" || token.text == "}" || token.text == "=>" {
            return None;
        }
        if token.kind == TokKind::Ident || token.text == "." || token.text == "," {
            continue;
        }
        return None;
    }
    if !saw_paren {
        return None;
    }
    let mut depth = 0i32;
    loop {
        if tokens[cursor].text == ")" {
            depth += 1;
        } else if tokens[cursor].text == "(" {
            depth -= 1;
            if depth == 0 {
                break;
            }
        }
        if cursor == 0 {
            return None;
        }
        cursor -= 1;
    }
    while cursor > 0 {
        cursor -= 1;
        let token = &tokens[cursor];
        if matches!(token.text.as_str(), "*" | "&" | ".") {
            continue;
        }
        if token.kind == TokKind::Ident && !is_reserved(&token.text) {
            return Some((token.text.clone(), token.line));
        }
        return None;
    }
    None
}

fn is_reserved(text: &str) -> bool {
    matches!(
        text,
        "if" | "while"
            | "for"
            | "switch"
            | "catch"
            | "else"
            | "do"
            | "try"
            | "foreach"
            | "synchronized"
            | "function"
            | "fn"
            | "fun"
            | "match"
            | "when"
            | "loop"
            | "return"
            | "case"
            | "default"
            | "new"
            | "throws"
    )
}

fn tokenize(source: &str) -> Vec<Tok> {
    let chars: Vec<char> = source.chars().collect();
    let mut tokens = Vec::new();
    let mut index = 0usize;
    let mut line = 1u32;
    while index < chars.len() {
        let ch = chars[index];
        if ch == '\n' {
            line += 1;
            index += 1;
            continue;
        }
        if ch.is_whitespace() {
            index += 1;
            continue;
        }
        if ch == '/' && chars.get(index + 1) == Some(&'/') {
            index += 2;
            while index < chars.len() && chars[index] != '\n' {
                index += 1;
            }
            continue;
        }
        if ch == '/' && chars.get(index + 1) == Some(&'*') {
            index += 2;
            while index + 1 < chars.len() && !(chars[index] == '*' && chars[index + 1] == '/') {
                if chars[index] == '\n' {
                    line += 1;
                }
                index += 1;
            }
            index = (index + 2).min(chars.len());
            continue;
        }
        if ch == '`' {
            index += 1;
            while index < chars.len() && chars[index] != '`' {
                if chars[index] == '\n' {
                    line += 1;
                }
                if chars[index] == '\\' {
                    index += 1;
                }
                index += 1;
            }
            index = (index + 1).min(chars.len());
            continue;
        }
        if ch == '"' || (ch == 'r' && raw_string_at(&chars, index).is_some()) {
            if let Some(end) = raw_string_at(&chars, index) {
                for cursor in index..end {
                    if chars[cursor] == '\n' {
                        line += 1;
                    }
                }
                index = end;
                continue;
            }
            index += 1;
            while index < chars.len() && chars[index] != '"' {
                if chars[index] == '\n' {
                    line += 1;
                }
                if chars[index] == '\\' {
                    index += 1;
                }
                index += 1;
            }
            index = (index + 1).min(chars.len());
            continue;
        }
        if ch == '\'' {
            if let Some(end) = char_literal_end(&chars, index) {
                index = end;
                continue;
            }
            index += 1;
            continue;
        }
        if ch == '_' || ch.is_ascii_alphabetic() {
            let start = index;
            index += 1;
            while index < chars.len()
                && (chars[index] == '_' || chars[index].is_ascii_alphanumeric())
            {
                index += 1;
            }
            tokens.push(Tok {
                line,
                kind: TokKind::Ident,
                text: chars[start..index].iter().collect(),
            });
            continue;
        }
        let operators = [
            "&&=", "||=", "&&", "||", "=>", "->", "?.", "??", "==", "!=", "<=", ">=", "::",
        ];
        if let Some(operator) = operators
            .iter()
            .find(|operator| starts_with(&chars, index, operator))
        {
            tokens.push(Tok {
                line,
                kind: TokKind::Op,
                text: (*operator).to_string(),
            });
            index += operator.chars().count();
            continue;
        }
        tokens.push(Tok {
            line,
            kind: TokKind::Op,
            text: ch.to_string(),
        });
        index += 1;
    }
    tokens
}

fn starts_with(chars: &[char], index: usize, text: &str) -> bool {
    chars[index..]
        .iter()
        .copied()
        .zip(text.chars())
        .all(|(left, right)| left == right)
        && chars[index..].len() >= text.chars().count()
}

fn raw_string_at(chars: &[char], index: usize) -> Option<usize> {
    if chars.get(index) != Some(&'r') {
        return None;
    }
    let mut cursor = index + 1;
    let mut hashes = 0usize;
    while chars.get(cursor) == Some(&'#') {
        hashes += 1;
        cursor += 1;
    }
    if chars.get(cursor) != Some(&'"') {
        return None;
    }
    cursor += 1;
    while cursor < chars.len() {
        if chars[cursor] == '"' {
            let mut matched = 0usize;
            while matched < hashes && chars.get(cursor + 1 + matched) == Some(&'#') {
                matched += 1;
            }
            if matched == hashes {
                return Some(cursor + 1 + hashes);
            }
        }
        cursor += 1;
    }
    Some(chars.len())
}

fn char_literal_end(chars: &[char], index: usize) -> Option<usize> {
    let next = *chars.get(index + 1)?;
    if next == '\\' {
        let close = *chars.get(index + 3)?;
        return (close == '\'').then_some(index + 4);
    }
    if next != '\'' && chars.get(index + 2) == Some(&'\'') {
        return Some(index + 3);
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    mod severity {
        use super::*;

        #[test]
        fn treats_the_threshold_as_critical() {
            assert_eq!(crap_severity(CRAP_THRESHOLD), CrapSeverity::Critical);
        }

        #[test]
        fn bands_scores_from_high_to_low() {
            assert_eq!(crap_severity(15.0), CrapSeverity::High);
            assert_eq!(crap_severity(5.0), CrapSeverity::Moderate);
            assert_eq!(crap_severity(4.9), CrapSeverity::Low);
        }
    }

    mod source_lines {
        use super::*;

        const SOURCE: &str =
            "fn a() {}\nfn price(x: u32) -> u32 {\n    if x > 1 { 2 } else { 3 }\n}\nfn b() {}";

        fn coverage() -> BTreeMap<u32, bool> {
            BTreeMap::from([(2, true), (3, false)])
        }

        #[test]
        fn keeps_only_the_function_lines() {
            let lines = function_source(SOURCE, 2, 4, &coverage());
            let numbers: Vec<u32> = lines.iter().map(|line| line.number).collect();
            assert_eq!(numbers, vec![2, 3, 4]);
        }

        #[test]
        fn marks_each_line_with_its_coverage() {
            let measures: Vec<LineMeasure> = function_source(SOURCE, 2, 4, &coverage())
                .iter()
                .map(|line| line.measure)
                .collect();
            assert_eq!(
                measures,
                vec![
                    LineMeasure::Covered,
                    LineMeasure::Uncovered,
                    LineMeasure::NotMeasured
                ]
            );
        }

        #[test]
        fn stops_at_the_end_of_the_file() {
            assert_eq!(function_source(SOURCE, 5, 9, &coverage()).len(), 1);
        }
    }

    #[test]
    fn formula_matches_the_published_examples() {
        assert_eq!(crap_score(10, 0.0), 110.0);
        assert_eq!(crap_score(10, 0.5), 22.5);
        assert_eq!(crap_score(10, 1.0), 10.0);
    }

    #[test]
    fn rust_counts_decisions_and_ignores_strings() {
        let source = r#"
fn price(x: u64) -> u64 {
    if x > 10 && x < 20 {
        1
    } else if x == 0 {
        2
    } else {
        3
    }
}
fn label() -> &'static str {
    "if && for"
}
fn choose(kind: u8) -> u8 {
    match kind {
        1 => 1,
        2 => 2,
        _ => 0,
    }
}
"#;
        let functions = functions_in(source, Language::Rust);
        let price = functions
            .iter()
            .find(|function| function.name == "price")
            .unwrap();
        assert_eq!(price.complexity, 4);
        let label = functions
            .iter()
            .find(|function| function.name == "label")
            .unwrap();
        assert_eq!(label.complexity, 1);
        let choose = functions
            .iter()
            .find(|function| function.name == "choose")
            .unwrap();
        assert_eq!(choose.complexity, 4);
    }

    #[test]
    fn nested_rust_function_keeps_its_own_decisions() {
        let source = r#"
fn outer() {
    if ready() {
        fn inner() {
            if deep() { 1 } else { 2 }
        }
    }
}
"#;
        let functions = functions_in(source, Language::Rust);
        assert_eq!(
            functions
                .iter()
                .find(|function| function.name == "outer")
                .unwrap()
                .complexity,
            2
        );
        assert_eq!(
            functions
                .iter()
                .find(|function| function.name == "inner")
                .unwrap()
                .complexity,
            2
        );
    }

    #[test]
    fn c_java_script_and_kotlin_count_their_branches() {
        let c = functions_in(
            "int price(int x) { if (x > 1 && x < 3) return 1; return 0; }\n",
            Language::C,
        );
        assert_eq!(c[0].name, "price");
        assert_eq!(c[0].complexity, 3);
        let java = functions_in(
            "class Prices { long convert(long x) { if (x > 0) return x; return 0; } }\n",
            Language::Java,
        );
        assert_eq!(
            java.iter()
                .find(|function| function.name == "convert")
                .unwrap()
                .complexity,
            2
        );
        let script = functions_in(
            "function price(x) { return x > 1 && x < 3 ? 1 : 0; }\nconst arrow = (x) => { if (x) return 1; return 0; };\n",
            Language::Script,
        );
        assert_eq!(
            script
                .iter()
                .find(|function| function.name == "price")
                .unwrap()
                .complexity,
            3
        );
        assert_eq!(
            script
                .iter()
                .find(|function| function.name == "arrow")
                .unwrap()
                .complexity,
            2
        );
        let kotlin = functions_in(
            "fun price(x: Int): Int { if (x > 0 && x < 3) return 1; return 0 }\n",
            Language::Kotlin,
        );
        assert_eq!(kotlin[0].complexity, 3);
    }

    #[test]
    fn vue_script_uses_the_source_line() {
        let source = "<template><p>if</p></template>\n<script setup lang=\"ts\">\nfunction price(x: number) { if (x > 0) return 1; return 0; }\n</script>\n";
        let functions = functions_in(&vue_script(source), Language::Script);
        assert_eq!(functions[0].name, "price");
        assert_eq!(functions[0].start, 3);
        assert_eq!(functions[0].complexity, 2);
    }

    #[test]
    fn score_uses_measured_lines_inside_the_function() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::write(
            dir.path().join("price.rs"),
            "fn price(x: u64) -> u64 {\n    if x > 0 { 1 } else { 0 }\n}\n",
        )
        .unwrap();
        let scored = score_files(
            dir.path(),
            &[FileLineCoverage {
                path: "price.rs".into(),
                lines: BTreeMap::from([(2, true), (2, true)]),
            }],
        );
        assert_eq!(scored.len(), 1);
        assert_eq!(scored[0].complexity, 2);
        assert_eq!(scored[0].measured_lines, 1);
        assert_eq!(scored[0].covered_lines, 1);
        assert_eq!(scored[0].coverage, 1.0);
        assert_eq!(scored[0].score, 2.0);
        assert_eq!(scored[0].crap_load, 0.0);
    }

    #[test]
    fn crap_load_is_zero_under_thirty_and_set_at_the_threshold() {
        assert_eq!(crap_load(10, 1.0), 0.0);
        assert_eq!(crap_load(10, 0.5), 0.0);
        assert_eq!(crap_score(4, 0.0), 20.0);
        assert_eq!(crap_load(4, 0.0), 0.0);
        assert_eq!(crap_score(5, 0.0), 30.0);
        assert_eq!(crap_load(5, 0.0), 5.0 + 5.0 / 30.0);
        assert_eq!(crap_score(10, 0.0), 110.0);
        assert_eq!(crap_load(10, 0.0), 10.0 + 10.0 / 30.0);
        assert_eq!(format!("{:.1}", crap_load(10, 0.0)), "10.3");
    }

    #[test]
    fn median_averages_the_middle_pair_and_counts_thirty_as_crappy() {
        let odd = crap_totals(&[
            scored("c", 110.0, 10, crap_load(10, 0.0)),
            scored("a", 5.0, 1, 0.0),
            scored("b", 30.0, 5, crap_load(5, 0.0)),
        ]);
        assert_eq!(odd.median_crap, 30.0);
        assert_eq!(odd.worst_crap, 110.0);
        assert_eq!(odd.crappy_functions, 2);
        let even = crap_totals(&[
            scored("d", 110.0, 10, 1.0),
            scored("a", 1.0, 1, 0.0),
            scored("c", 20.0, 4, 0.0),
            scored("b", 10.0, 2, 0.0),
        ]);
        assert_eq!(even.median_crap, 15.0);
        assert_eq!(even.mean_crap, 35.25);
        assert_eq!(even.crappy_functions, 1);
        assert_eq!(even.crappy_percent, 25.0);
        assert_eq!(even.complexity, 17);
        assert_eq!(crap_totals(&[]), CrapTotals::default());
    }

    #[test]
    fn totals_include_functions_past_the_displayed_thirty() {
        let mut functions = Vec::new();
        for index in 0..30 {
            functions.push(scored(&format!("low{index}"), 1.0, 1, 0.0));
        }
        functions.push(scored("high", 110.0, 10, crap_load(10, 0.0)));
        let totals = crap_totals(&functions);
        let mut ranked = functions.clone();
        ranked.sort_by(|left, right| right.score.total_cmp(&left.score));
        let shown_score: f64 = ranked.iter().take(30).map(|function| function.score).sum();
        let shown_complexity: u64 = ranked
            .iter()
            .take(30)
            .map(|function| u64::from(function.complexity))
            .sum();
        assert_eq!(totals.functions, 31);
        assert_eq!(totals.total_crap, 140.0);
        assert_eq!(shown_score, 139.0);
        assert_eq!(totals.complexity, 40);
        assert_eq!(shown_complexity, 39);
        assert_eq!(totals.worst_crap, 110.0);
        assert_eq!(totals.median_crap, 1.0);
        assert_eq!(totals.crappy_functions, 1);
        assert_eq!(totals.crappy_percent, 100.0 / 31.0);
        assert_eq!(totals.crap_load, 10.0 + 10.0 / 30.0);
    }

    #[test]
    fn project_rollups_use_the_longest_path_and_skip_ties() {
        let workspace = Path::new("/ws");
        let shop = CrapProjectRoot {
            id: "shop".into(),
            path: PathBuf::from("/ws/app/shop"),
        };
        let mini = CrapProjectRoot {
            id: "mini".into(),
            path: PathBuf::from("/ws/app/mini"),
        };
        let functions = vec![
            scored("app/shop/src/a.rs", 110.0, 10, crap_load(10, 0.0)),
            scored("app/shop/src/b.rs", 10.0, 2, 0.0),
            scored("app/mini/src/c.rs", 30.0, 5, crap_load(5, 0.0)),
            scored("notes.rs", 4.0, 1, 0.0),
            scored("app/shop-extra/src/d.rs", 8.0, 1, 0.0),
        ];
        let rollups = project_rollups(workspace, &functions, &[shop, mini]);
        assert_eq!(rollups.len(), 3);
        assert_eq!(rollups[0].project, "mini");
        assert_eq!(rollups[0].totals.functions, 1);
        assert_eq!(rollups[0].totals.crappy_functions, 1);
        assert_eq!(rollups[1].project, "shop");
        assert_eq!(rollups[1].totals.functions, 2);
        assert_eq!(rollups[1].totals.complexity, 12);
        assert_eq!(rollups[1].totals.total_crap, 120.0);
        assert_eq!(rollups[1].totals.crap_load, 10.0 + 10.0 / 30.0);
        assert_eq!(rollups[2].project, "outside projects");
        assert_eq!(rollups[2].totals.functions, 2);
        assert_eq!(rollups[2].totals.total_crap, 12.0);
        let nested = project_rollups(
            workspace,
            &functions,
            &[
                CrapProjectRoot {
                    id: "root".into(),
                    path: workspace.join("."),
                },
                CrapProjectRoot {
                    id: "shop".into(),
                    path: PathBuf::from("/ws/app/shop"),
                },
            ],
        );
        assert_eq!(nested.len(), 2);
        assert_eq!(nested[0].project, "root");
        assert_eq!(nested[0].totals.functions, 3);
        assert_eq!(nested[1].project, "shop");
        assert_eq!(nested[1].totals.functions, 2);
        let tied = project_rollups(
            workspace,
            &functions,
            &[
                CrapProjectRoot {
                    id: "mini".into(),
                    path: PathBuf::from("/ws"),
                },
                CrapProjectRoot {
                    id: "prices".into(),
                    path: PathBuf::from("/ws"),
                },
            ],
        );
        assert!(tied.is_empty());
        assert!(project_rollups(workspace, &functions, &[]).is_empty());
    }

    #[test]
    fn change_slice_keeps_functions_whose_file_is_in_the_diff() {
        let functions = vec![
            scored("shop-rs-api/src/domain/model.rs", 4.0, 2, 0.0),
            scored("shop-ts-lib-cart/src/cart.ts", 2.0, 1, 0.0),
        ];
        assert!(change_slice(&functions, &[]).is_none());
        let changed =
            change_slice(&functions, &["shop-rs-api/src/domain/model.rs".to_string()]).unwrap();
        assert_eq!(changed.functions.len(), 1);
        assert_eq!(changed.functions[0].path, "shop-rs-api/src/domain/model.rs");
        assert_eq!(changed.totals.functions, 1);
        assert_eq!(changed.totals.worst_crap, 4.0);
    }

    #[test]
    fn change_on_added_lines_keeps_functions_that_overlap_the_diff() {
        let mut parse = scored("shop-rs-api/src/domain/model.rs", 4.0, 3, 0.0);
        parse.name = "parse".into();
        parse.line = 110;
        parse.source = vec![CrapSourceLine {
            number: 112,
            text: "_ => Err(DomainError::InvalidDiscount),".into(),
            measure: LineMeasure::Uncovered,
        }];
        let mut checkout = scored("shop-rs-api/src/domain/model.rs", 2.0, 2, 0.0);
        checkout.name = "checkout".into();
        checkout.line = 220;
        checkout.source = vec![CrapSourceLine {
            number: 221,
            text: "self.ensure_open()?;".into(),
            measure: LineMeasure::Covered,
        }];
        let mut lines = std::collections::BTreeSet::new();
        lines.insert(112);
        let changed = change_on_added_lines(
            &[parse, checkout],
            &[("shop-rs-api/src/domain/model.rs".into(), lines)],
        )
        .unwrap();
        assert_eq!(changed.functions.len(), 1);
        assert_eq!(changed.functions[0].name, "parse");
        assert!(change_on_added_lines(&[], &[]).is_none());
    }

    fn scored(path: &str, score: f64, complexity: u32, load: f64) -> CrapFunction {
        CrapFunction {
            path: path.into(),
            name: path.into(),
            line: 1,
            complexity,
            covered_lines: 0,
            measured_lines: 1,
            coverage: 0.0,
            score,
            crap_load: load,
            source: Vec::new(),
        }
    }
}
