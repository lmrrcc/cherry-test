use std::collections::{BTreeMap, BTreeSet};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum HighlightLang {
    Rust,
    TypeScript,
    Plain,
    Config,
}

#[derive(Debug, Default)]
pub(crate) struct JumpIndex {
    defs: Vec<Def>,
    by_phase_file: BTreeMap<String, BTreeMap<String, Vec<usize>>>,
    by_name: BTreeMap<String, Vec<usize>>,
}

#[derive(Debug)]
struct Def {
    name: String,
    phase: String,
    file: String,
    line: usize,
    anchor: String,
}

enum Token<'a> {
    Plain(&'a str),
    Comment(&'a str),
    String(&'a str),
    Number(&'a str),
    Attribute(&'a str),
    Keyword(&'a str),
    Macro(&'a str),
    Call(&'a str),
    Type(&'a str),
    Ident(&'a str),
}

struct Taken<'a> {
    token: Token<'a>,
    next: usize,
}

pub(crate) fn highlight_lang_for_path(path: &str) -> HighlightLang {
    let lower = path.to_ascii_lowercase();
    if lower.ends_with(".rs") {
        HighlightLang::Rust
    } else if lower.ends_with(".ts")
        || lower.ends_with(".tsx")
        || lower.ends_with(".js")
        || lower.ends_with(".jsx")
        || lower.ends_with(".mjs")
        || lower.ends_with(".cjs")
    {
        HighlightLang::TypeScript
    } else if lower.ends_with(".json")
        || lower.ends_with(".yaml")
        || lower.ends_with(".yml")
        || lower.ends_with(".toml")
        || lower.ends_with(".lock")
    {
        HighlightLang::Config
    } else {
        HighlightLang::Plain
    }
}

pub(crate) fn snippet_language(source: &str) -> HighlightLang {
    let rust_score = signal_count(source, &["use ", "pub ", "impl ", "fn ", " fn ", "mod "]);
    let script_score = signal_count(
        source,
        &[
            "import ",
            "export ",
            "function ",
            "interface ",
            "const ",
            "=>",
        ],
    );
    if rust_score > 0 && rust_score >= script_score {
        HighlightLang::Rust
    } else if script_score > 0 {
        HighlightLang::TypeScript
    } else {
        HighlightLang::Plain
    }
}

pub(crate) fn highlight_line(lang: HighlightLang, text: &str) -> String {
    highlight_linked(lang, text, |_| None)
}

pub(crate) fn highlight_linked(
    lang: HighlightLang,
    text: &str,
    mut href_for: impl FnMut(&str) -> Option<String>,
) -> String {
    let mut html = String::new();
    for token in scan(lang, text) {
        html.push_str(&render_token(lang, token, &mut href_for));
    }
    html
}

pub(crate) fn defined_names(lang: HighlightLang, line: &str) -> Vec<String> {
    if !matches!(lang, HighlightLang::Rust | HighlightLang::TypeScript) {
        return Vec::new();
    }
    let tokens = scan(lang, line);
    let mut names = Vec::new();
    let mut index = 0;
    while index < tokens.len() {
        if let Some(Token::Keyword(word)) = tokens.get(index) {
            if is_def_keyword(lang, word) {
                let mut next = index + 1;
                while let Some(token) = tokens.get(next) {
                    match token {
                        Token::Keyword(_) => next += 1,
                        Token::Plain(text) if text.trim().is_empty() => next += 1,
                        _ => break,
                    }
                }
                if let Some(name) = tokens.get(next).and_then(token_name) {
                    names.push(name.to_string());
                }
            }
        }
        index += 1;
    }
    names
}

pub(crate) fn record_rendered_definitions(
    index: &mut JumpIndex,
    phase: &str,
    file: &str,
    source: &str,
    rendered_lines: &BTreeSet<usize>,
) {
    let lang = highlight_lang_for_path(file);
    let lines: Vec<&str> = source.lines().collect();
    for line_no in rendered_lines {
        let Some(text) = line_no
            .checked_sub(1)
            .and_then(|offset| lines.get(offset).copied())
        else {
            continue;
        };
        for name in defined_names(lang, text) {
            index.insert(phase, file, *line_no, &name);
        }
    }
}

impl JumpIndex {
    pub(crate) fn href(
        &self,
        phase: Option<&str>,
        file: &str,
        line: usize,
        name: &str,
    ) -> Option<String> {
        if self.line_defines(file, line, name) {
            return None;
        }
        let def = self.resolve(phase, file, line, name)?;
        if phase.is_some() {
            Some(format!("#{}", def.anchor))
        } else {
            Some(format!("phases.html#{}", def.anchor))
        }
    }

    pub(crate) fn row_anchor(&self, phase: &str, file: &str, line: usize) -> Option<&str> {
        let indices = self.by_phase_file.get(phase)?.get(file)?;
        indices.iter().find_map(|idx| {
            let def = self.defs.get(*idx)?;
            (def.line == line).then_some(def.anchor.as_str())
        })
    }

    fn line_defines(&self, file: &str, line: usize, name: &str) -> bool {
        self.by_name.get(name).is_some_and(|indices| {
            indices.iter().any(|idx| {
                self.defs
                    .get(*idx)
                    .is_some_and(|def| def.file == file && def.line == line)
            })
        })
    }

    fn resolve<'a>(
        &'a self,
        phase: Option<&str>,
        file: &str,
        line: usize,
        name: &str,
    ) -> Option<&'a Def> {
        if let Some(phase) = phase {
            if let Some(def) = self.nearest_same_file(phase, file, line, name) {
                return Some(def);
            }
            if let Some(def) = self.unique_in_phase(phase, file, line, name) {
                return Some(def);
            }
        }
        self.unique_global(phase, file, line, name)
    }

    fn nearest_same_file<'a>(
        &'a self,
        phase: &str,
        file: &str,
        line: usize,
        name: &str,
    ) -> Option<&'a Def> {
        let indices = self.by_phase_file.get(phase)?.get(file)?.clone();
        let mut best: Option<usize> = None;
        for idx in indices {
            let candidate_line = {
                let Some(def) = self.defs.get(idx) else {
                    continue;
                };
                if def.name != name || def.line == line {
                    continue;
                }
                def.line
            };
            let current_line = best.and_then(|current| self.defs.get(current).map(|def| def.line));
            let replace = match current_line {
                None => true,
                Some(current_line) => {
                    let candidate_distance = candidate_line.abs_diff(line);
                    let current_distance = current_line.abs_diff(line);
                    candidate_distance < current_distance
                        || (candidate_distance == current_distance
                            && candidate_line <= line
                            && current_line > line)
                }
            };
            if replace {
                best = Some(idx);
            }
        }
        best.and_then(|idx| self.defs.get(idx))
    }

    fn unique_in_phase<'a>(
        &'a self,
        phase: &str,
        file: &str,
        line: usize,
        name: &str,
    ) -> Option<&'a Def> {
        self.unique_named(name, |def| {
            def.phase != phase || is_self(def, phase, file, line)
        })
    }

    fn unique_global<'a>(
        &'a self,
        phase: Option<&str>,
        file: &str,
        line: usize,
        name: &str,
    ) -> Option<&'a Def> {
        self.unique_named(name, |def| match phase {
            Some(phase) => is_self(def, phase, file, line),
            None => def.file == file && def.line == line,
        })
    }

    fn unique_named<'a>(&'a self, name: &str, exclude: impl Fn(&Def) -> bool) -> Option<&'a Def> {
        let indices = self.by_name.get(name)?.clone();
        let mut found = None;
        for idx in indices {
            let Some(def) = self.defs.get(idx) else {
                continue;
            };
            if exclude(def) {
                continue;
            }
            if found.is_some() {
                return None;
            }
            found = Some(idx);
        }
        found.and_then(|idx| self.defs.get(idx))
    }

    fn insert(&mut self, phase: &str, file: &str, line: usize, name: &str) {
        if self
            .by_phase_file
            .get(phase)
            .and_then(|files| files.get(file))
            .is_some_and(|indices| {
                indices.iter().any(|idx| {
                    self.defs
                        .get(*idx)
                        .is_some_and(|def| def.line == line && def.name == name)
                })
            })
        {
            return;
        }
        let anchor = anchor_id(phase, file, line);
        let idx = self.defs.len();
        self.by_phase_file
            .entry(phase.to_string())
            .or_default()
            .entry(file.to_string())
            .or_default()
            .push(idx);
        self.by_name.entry(name.to_string()).or_default().push(idx);
        self.defs.push(Def {
            name: name.to_string(),
            phase: phase.to_string(),
            file: file.to_string(),
            line,
            anchor,
        });
    }
}

fn is_self(def: &Def, phase: &str, file: &str, line: usize) -> bool {
    def.phase == phase && def.file == file && def.line == line
}

fn anchor_id(phase: &str, file: &str, line: usize) -> String {
    let mut id = String::from("cov-");
    push_slug(&mut id, phase);
    id.push('-');
    push_slug(&mut id, file);
    id.push_str("-L");
    id.push_str(&line.to_string());
    id
}

fn push_slug(out: &mut String, value: &str) {
    for ch in value.chars() {
        if ch.is_ascii_alphanumeric() {
            out.push(ch);
        } else {
            out.push('-');
        }
    }
}

fn signal_count(source: &str, signals: &[&str]) -> usize {
    signals
        .iter()
        .map(|signal| source.matches(signal).count())
        .sum()
}

fn token_name<'a>(token: &Token<'a>) -> Option<&'a str> {
    match token {
        Token::Call(name) | Token::Type(name) | Token::Ident(name) => Some(*name),
        _ => None,
    }
}

fn render_token(
    lang: HighlightLang,
    token: Token<'_>,
    href_for: &mut impl FnMut(&str) -> Option<String>,
) -> String {
    match token {
        Token::Plain(text) => escape(text),
        Token::Comment(text) => colored("tok-cmt", text),
        Token::String(text) => colored("tok-str", text),
        Token::Number(text) => colored("tok-num", text),
        Token::Attribute(text) => colored("tok-attr", text),
        Token::Keyword(text) => colored("tok-kw", text),
        Token::Macro(text) => colored("tok-mac", text),
        Token::Call(text) => linked(lang, "tok-fnc", text, href_for),
        Token::Type(text) => linked(lang, "tok-ty", text, href_for),
        Token::Ident(text) => linked(lang, "", text, href_for),
    }
}

fn colored(class: &str, text: &str) -> String {
    format!("<span class=\"{class}\">{}</span>", escape(text))
}

fn linked(
    lang: HighlightLang,
    class: &str,
    text: &str,
    href_for: &mut impl FnMut(&str) -> Option<String>,
) -> String {
    let body = if class.is_empty() {
        escape(text)
    } else {
        colored(class, text)
    };
    if !matches!(lang, HighlightLang::Rust | HighlightLang::TypeScript) {
        return body;
    }
    match href_for(text) {
        Some(href) if safe_href(&href) => format!(
            "<a class=\"sym\" href=\"{href}\" title=\"Command-click to jump to the covered definition\">{body}</a>"
        ),
        _ => body,
    }
}

fn safe_href(href: &str) -> bool {
    let id = href
        .strip_prefix("phases.html#")
        .or_else(|| href.strip_prefix('#'));
    id.is_some_and(|id| {
        !id.is_empty() && id.chars().all(|ch| ch.is_ascii_alphanumeric() || ch == '-')
    })
}

fn escape(value: &str) -> String {
    value
        .replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
}

fn scan<'a>(lang: HighlightLang, line: &'a str) -> Vec<Token<'a>> {
    let mut tokens = Vec::new();
    let mut index = 0;
    let mut plain_start = 0;
    while index < line.len() {
        if let Some(taken) = take_special(lang, line, index) {
            if plain_start < index {
                tokens.push(Token::Plain(&line[plain_start..index]));
            }
            tokens.push(taken.token);
            index = taken.next;
            plain_start = index;
            continue;
        }
        index += char_len(line, index);
    }
    if plain_start < line.len() {
        tokens.push(Token::Plain(&line[plain_start..]));
    }
    tokens
}

fn take_special<'a>(lang: HighlightLang, line: &'a str, index: usize) -> Option<Taken<'a>> {
    if lang == HighlightLang::Plain {
        return None;
    }
    let rest = &line[index..];
    if lang == HighlightLang::Rust && (rest.starts_with("#[") || rest.starts_with("#![")) {
        return Some(Taken {
            token: Token::Attribute(rest),
            next: line.len(),
        });
    }
    if matches!(lang, HighlightLang::Rust | HighlightLang::TypeScript) && rest.starts_with("//") {
        return Some(Taken {
            token: Token::Comment(rest),
            next: line.len(),
        });
    }
    if matches!(lang, HighlightLang::Rust | HighlightLang::TypeScript) && rest.starts_with("/*") {
        let end = rest
            .find("*/")
            .map(|at| index + at + 2)
            .unwrap_or(line.len());
        return Some(Taken {
            token: Token::Comment(&line[index..end]),
            next: end,
        });
    }
    if let Some(end) = take_string(lang, line, index) {
        return Some(Taken {
            token: Token::String(&line[index..end]),
            next: end,
        });
    }
    if lang == HighlightLang::Rust {
        if let Some(end) = take_lifetime(line, index) {
            return Some(Taken {
                token: Token::Plain(&line[index..end]),
                next: end,
            });
        }
    }
    if lang == HighlightLang::Config && rest.starts_with('#') {
        return Some(Taken {
            token: Token::Comment(rest),
            next: line.len(),
        });
    }
    if let Some(end) = take_number(line, index) {
        return Some(Taken {
            token: Token::Number(&line[index..end]),
            next: end,
        });
    }
    take_ident(lang, line, index)
}

fn take_string(lang: HighlightLang, line: &str, index: usize) -> Option<usize> {
    match lang {
        HighlightLang::Plain => None,
        HighlightLang::Rust => take_rust_string(line, index),
        HighlightLang::TypeScript => take_quoted(line, index, &['"', '\'', '`']),
        HighlightLang::Config => take_quoted(line, index, &['"', '\'']),
    }
}

fn take_rust_string(line: &str, index: usize) -> Option<usize> {
    let bytes = line.as_bytes();
    let mut cursor = index;
    if matches!(bytes.get(cursor), Some(b'b' | b'c'))
        && matches!(bytes.get(cursor + 1), Some(b'r' | b'"' | b'\''))
    {
        cursor += 1;
    }
    match bytes.get(cursor) {
        Some(b'r') => take_raw_string(line, cursor),
        Some(b'"') => take_escaped_quote(line, cursor, '"'),
        Some(b'\'') => take_char(line, cursor),
        _ => None,
    }
}

fn take_raw_string(line: &str, r_index: usize) -> Option<usize> {
    let bytes = line.as_bytes();
    if bytes.get(r_index) != Some(&b'r') {
        return None;
    }
    let mut cursor = r_index + 1;
    let mut hashes = 0usize;
    while bytes.get(cursor) == Some(&b'#') {
        hashes += 1;
        cursor += 1;
    }
    if bytes.get(cursor) != Some(&b'"') {
        return None;
    }
    cursor += 1;
    let closer = format!("\"{}", "#".repeat(hashes));
    Some(
        line[cursor..]
            .find(&closer)
            .map(|at| cursor + at + closer.len())
            .unwrap_or(line.len()),
    )
}

fn take_quoted(line: &str, index: usize, quotes: &[char]) -> Option<usize> {
    let first = line[index..].chars().next()?;
    if quotes.contains(&first) {
        take_escaped_quote(line, index, first)
    } else {
        None
    }
}

fn take_escaped_quote(line: &str, quote_index: usize, quote: char) -> Option<usize> {
    let mut chars = line[quote_index..].chars();
    if chars.next() != Some(quote) {
        return None;
    }
    let mut end = quote_index + quote.len_utf8();
    let mut escaped = false;
    for ch in line[end..].chars() {
        end += ch.len_utf8();
        if escaped {
            escaped = false;
            continue;
        }
        if ch == '\\' {
            escaped = true;
            continue;
        }
        if ch == quote {
            return Some(end);
        }
    }
    Some(line.len())
}

fn take_char(line: &str, index: usize) -> Option<usize> {
    if !line[index..].starts_with('\'') {
        return None;
    }
    let after_quote = index + 1;
    match line[after_quote..].chars().next() {
        Some('\\') => take_escaped_quote(line, index, '\''),
        Some(ch) => {
            let close_at = after_quote + ch.len_utf8();
            if line[close_at..].starts_with('\'') {
                Some(close_at + 1)
            } else {
                None
            }
        }
        None => None,
    }
}

fn take_lifetime(line: &str, index: usize) -> Option<usize> {
    if !line[index..].starts_with('\'') {
        return None;
    }
    let start = index + 1;
    let first = line[start..].chars().next()?;
    if !is_ident_start(first) {
        return None;
    }
    let mut end = start + first.len_utf8();
    while let Some(ch) = line[end..].chars().next() {
        if is_ident_continue(ch) {
            end += ch.len_utf8();
        } else {
            break;
        }
    }
    Some(end)
}

fn take_number(line: &str, index: usize) -> Option<usize> {
    let first = line[index..].chars().next()?;
    if !first.is_ascii_digit() {
        return None;
    }
    let mut end = index + first.len_utf8();
    if first == '0' {
        if let Some(marker) = line[end..].chars().next() {
            if matches!(marker, 'x' | 'X' | 'b' | 'o') {
                end += marker.len_utf8();
                while let Some(ch) = line[end..].chars().next() {
                    if ch.is_ascii_hexdigit() || ch == '_' {
                        end += ch.len_utf8();
                    } else {
                        break;
                    }
                }
                return Some(end);
            }
        }
    }
    while let Some(ch) = line[end..].chars().next() {
        if ch.is_ascii_digit() || ch == '_' {
            end += ch.len_utf8();
            continue;
        }
        if ch == '.' {
            let after = line[end + ch.len_utf8()..].chars().next();
            if matches!(after, Some(next) if next.is_ascii_digit()) {
                end += ch.len_utf8();
                continue;
            }
        }
        break;
    }
    Some(end)
}

fn take_ident<'a>(lang: HighlightLang, line: &'a str, index: usize) -> Option<Taken<'a>> {
    let first = line[index..].chars().next()?;
    if !is_ident_start(first) {
        return None;
    }
    let mut end = index + first.len_utf8();
    while let Some(ch) = line[end..].chars().next() {
        if is_ident_continue(ch) {
            end += ch.len_utf8();
        } else {
            break;
        }
    }
    let ident = &line[index..end];
    if is_keyword(lang, ident) {
        return Some(Taken {
            token: Token::Keyword(ident),
            next: end,
        });
    }
    if is_primitive_type(lang, ident) {
        return Some(Taken {
            token: Token::Type(ident),
            next: end,
        });
    }
    let after = skip_ws(line, end);
    let mut following = line[after..].chars();
    let next = following.next();
    let after_next = following.next();
    if next == Some('!') && after_next != Some('=') && lang == HighlightLang::Rust {
        let bang_end = after + '!'.len_utf8();
        return Some(Taken {
            token: Token::Macro(&line[index..bang_end]),
            next: bang_end,
        });
    }
    if next == Some('(') && matches!(lang, HighlightLang::Rust | HighlightLang::TypeScript) {
        return Some(Taken {
            token: Token::Call(ident),
            next: end,
        });
    }
    if first.is_uppercase() && matches!(lang, HighlightLang::Rust | HighlightLang::TypeScript) {
        return Some(Taken {
            token: Token::Type(ident),
            next: end,
        });
    }
    Some(Taken {
        token: Token::Ident(ident),
        next: end,
    })
}

fn skip_ws(line: &str, index: usize) -> usize {
    let mut end = index;
    while let Some(ch) = line[end..].chars().next() {
        if ch == ' ' || ch == '\t' {
            end += ch.len_utf8();
        } else {
            break;
        }
    }
    end
}

fn char_len(line: &str, index: usize) -> usize {
    line[index..]
        .chars()
        .next()
        .map(|ch| ch.len_utf8())
        .unwrap_or(1)
}

fn is_ident_start(ch: char) -> bool {
    ch == '_' || ch.is_alphabetic()
}

fn is_ident_continue(ch: char) -> bool {
    ch == '_' || ch.is_alphanumeric()
}

fn is_def_keyword(lang: HighlightLang, word: &str) -> bool {
    match lang {
        HighlightLang::Rust => matches!(
            word,
            "fn" | "struct" | "enum" | "trait" | "type" | "const" | "static" | "mod"
        ),
        HighlightLang::TypeScript => {
            matches!(word, "function" | "class" | "interface" | "type" | "enum")
        }
        HighlightLang::Plain | HighlightLang::Config => false,
    }
}

fn is_primitive_type(lang: HighlightLang, word: &str) -> bool {
    lang == HighlightLang::Rust
        && matches!(
            word,
            "bool"
                | "char"
                | "str"
                | "u8"
                | "u16"
                | "u32"
                | "u64"
                | "u128"
                | "usize"
                | "i8"
                | "i16"
                | "i32"
                | "i64"
                | "i128"
                | "isize"
                | "f32"
                | "f64"
        )
}

fn is_keyword(lang: HighlightLang, word: &str) -> bool {
    match lang {
        HighlightLang::Rust => matches!(
            word,
            "as" | "async"
                | "await"
                | "break"
                | "const"
                | "continue"
                | "crate"
                | "dyn"
                | "else"
                | "enum"
                | "extern"
                | "false"
                | "fn"
                | "for"
                | "if"
                | "impl"
                | "in"
                | "let"
                | "loop"
                | "match"
                | "mod"
                | "move"
                | "mut"
                | "pub"
                | "ref"
                | "return"
                | "self"
                | "Self"
                | "static"
                | "struct"
                | "super"
                | "trait"
                | "true"
                | "type"
                | "unsafe"
                | "use"
                | "where"
                | "while"
        ),
        HighlightLang::TypeScript => matches!(
            word,
            "as" | "async"
                | "await"
                | "break"
                | "case"
                | "catch"
                | "class"
                | "const"
                | "continue"
                | "debugger"
                | "default"
                | "delete"
                | "do"
                | "else"
                | "enum"
                | "export"
                | "extends"
                | "false"
                | "finally"
                | "for"
                | "from"
                | "function"
                | "if"
                | "implements"
                | "import"
                | "in"
                | "instanceof"
                | "interface"
                | "let"
                | "new"
                | "null"
                | "of"
                | "private"
                | "protected"
                | "public"
                | "readonly"
                | "return"
                | "satisfies"
                | "static"
                | "super"
                | "switch"
                | "this"
                | "throw"
                | "true"
                | "try"
                | "type"
                | "typeof"
                | "undefined"
                | "var"
                | "void"
                | "while"
                | "yield"
        ),
        HighlightLang::Plain | HighlightLang::Config => false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn same_file_definition_is_preferred_over_another_file() {
        let mut index = JumpIndex::default();
        let source = "fn answer() {\n\n\nfn caller() { answer() }\n";
        record_rendered_definitions(&mut index, "unit", "a.rs", source, &BTreeSet::from([1, 4]));
        record_rendered_definitions(
            &mut index,
            "unit",
            "b.rs",
            "fn answer() {}\n",
            &BTreeSet::from([1]),
        );
        assert_eq!(
            index.href(Some("unit"), "a.rs", 4, "answer").as_deref(),
            Some("#cov-unit-a-rs-L1")
        );
    }

    #[test]
    fn definition_line_does_not_link_to_the_same_line_in_another_phase() {
        let mut index = JumpIndex::default();
        let source = "pub struct CatalogService {\n\n\n    CatalogService::new()\n";
        let rendered = BTreeSet::from([1, 4]);
        record_rendered_definitions(&mut index, "integration", "service.rs", source, &rendered);
        record_rendered_definitions(&mut index, "unit", "service.rs", source, &rendered);
        assert!(index
            .href(Some("integration"), "service.rs", 1, "CatalogService")
            .is_none());
        assert_eq!(
            index
                .href(Some("integration"), "service.rs", 4, "CatalogService")
                .as_deref(),
            Some("#cov-integration-service-rs-L1")
        );
    }

    #[test]
    fn ambiguous_name_is_not_linked_across_files() {
        let mut index = JumpIndex::default();
        record_rendered_definitions(
            &mut index,
            "unit",
            "a.rs",
            "fn new() {}\n",
            &BTreeSet::from([1]),
        );
        record_rendered_definitions(
            &mut index,
            "unit",
            "b.rs",
            "fn new() {}\n",
            &BTreeSet::from([1]),
        );
        assert!(index.href(Some("unit"), "c.rs", 3, "new").is_none());
        assert!(index.href(None, "c.rs", 3, "new").is_none());
    }

    #[test]
    fn equally_distant_definitions_prefer_the_earlier_line() {
        let mut index = JumpIndex::default();
        let source = "fn answer() {}\n\nlet value = answer();\n\nfn answer() {}\n";
        record_rendered_definitions(
            &mut index,
            "unit",
            "a.rs",
            source,
            &BTreeSet::from([1, 3, 5]),
        );
        assert_eq!(
            index.href(Some("unit"), "a.rs", 3, "answer").as_deref(),
            Some("#cov-unit-a-rs-L1")
        );
    }

    #[test]
    fn index_links_only_a_globally_unique_name() {
        let mut index = JumpIndex::default();
        record_rendered_definitions(
            &mut index,
            "integration",
            "service.rs",
            "struct CatalogService;\n",
            &BTreeSet::from([1]),
        );
        record_rendered_definitions(
            &mut index,
            "unit",
            "a.rs",
            "fn new() {}\n",
            &BTreeSet::from([1]),
        );
        record_rendered_definitions(
            &mut index,
            "unit",
            "b.rs",
            "fn new() {}\n",
            &BTreeSet::from([1]),
        );
        assert_eq!(
            index
                .href(None, "other.rs", 10, "CatalogService")
                .as_deref(),
            Some("phases.html#cov-integration-service-rs-L1")
        );
    }

    #[test]
    fn keyword_is_colored_and_call_links_to_rendered_definition() {
        let mut index = JumpIndex::default();
        let source = "fn answer() {\n    1\n}\nfn caller() {\n    answer()\n}\n";
        record_rendered_definitions(
            &mut index,
            "unit",
            "lib.rs",
            source,
            &BTreeSet::from([1, 4, 5]),
        );
        let call = highlight_linked(HighlightLang::Rust, "    answer()", |name| {
            index.href(Some("unit"), "lib.rs", 5, name)
        });
        assert!(call.contains("tok-fnc"));
        assert!(call.contains("href=\"#cov-unit-lib-rs-L1\""));
        let definition = highlight_linked(HighlightLang::Rust, "fn answer() {", |name| {
            index.href(Some("unit"), "lib.rs", 1, name)
        });
        assert!(definition.contains("tok-kw\">fn</span>"));
        assert!(!definition.contains("href="));
        assert_eq!(
            index.row_anchor("unit", "lib.rs", 1),
            Some("cov-unit-lib-rs-L1")
        );
    }

    #[test]
    fn rust_attribute_colors_the_rest_of_the_line() {
        let html = highlight_line(HighlightLang::Rust, "#[derive(Debug)] struct Foo;");
        assert!(html.starts_with("<span class=\"tok-attr\">"));
        assert!(!html.contains("tok-kw"));
    }

    #[test]
    fn strings_comments_and_markup_stay_out_of_definitions() {
        let names = defined_names(HighlightLang::Rust, "let label = \"fn hidden\"; // fn also");
        assert!(names.is_empty());
        let html = highlight_line(HighlightLang::Rust, "let label = \"<script>\"; // fn also");
        assert!(html.contains("tok-str"));
        assert!(html.contains("tok-cmt"));
        assert!(html.contains("tok-kw\">let</span>"));
        assert!(html.contains("&lt;script&gt;"));
        assert!(!html.contains("<script>"));
    }

    #[test]
    fn snippet_language_keeps_rust_closures_and_plain_let() {
        assert_eq!(snippet_language("let value = input;"), HighlightLang::Plain);
        assert_eq!(
            snippet_language("async fn run() { items.map(|item| item) }"),
            HighlightLang::Rust
        );
        assert_eq!(
            snippet_language("export function run() { return 1; }"),
            HighlightLang::TypeScript
        );
    }

    #[test]
    fn lifetimes_do_not_swallow_the_rest_of_the_line() {
        let html = highlight_line(HighlightLang::Rust, "fn get<'a>(value: &'a str) {}");
        assert!(html.contains("tok-kw\">fn</span>"));
        assert!(html.contains("get"));
        assert!(html.contains("tok-ty\">str</span>"));
        let names = defined_names(HighlightLang::Rust, "fn get<'a>(value: &'a str)");
        assert_eq!(names, vec!["get".to_string()]);
    }
}
