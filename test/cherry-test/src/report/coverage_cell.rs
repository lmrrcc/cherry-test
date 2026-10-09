use crate::coverage::CoverageSummary;

use super::theme::{self, Accent};

pub fn format_lines_cell(summary: Option<&CoverageSummary>, compact: bool) -> String {
    match summary {
        Some(s) if s.lines_found > 0 => format_lines_summary(s, compact),
        _ => "-".into(),
    }
}

pub fn format_fn_cell(summary: Option<&CoverageSummary>, compact: bool) -> String {
    match summary {
        Some(s) if s.functions_found > 0 => format_fn_summary(s, compact),
        _ => "-".into(),
    }
}

pub fn format_lines_cell_colored(summary: Option<&CoverageSummary>, compact: bool) -> String {
    let plain = format_lines_cell(summary, compact);
    if plain == "-" {
        return plain;
    }
    let Some(s) = summary else {
        return plain;
    };
    colorize_lines_cell(&plain, s, compact)
}

pub fn format_fn_cell_colored(summary: Option<&CoverageSummary>, compact: bool) -> String {
    let plain = format_fn_cell(summary, compact);
    if plain == "-" {
        return plain;
    }
    let Some(s) = summary else {
        return plain;
    };
    let pct = format!("{:.1}%", s.functions_percent());
    let accent = theme::coverage_accent(s.functions_percent());
    plain.replace(&pct, &theme::paint(&pct, accent))
}

pub fn lines_cell_html(summary: Option<&CoverageSummary>) -> String {
    match summary {
        Some(s) if s.lines_found > 0 => lines_html(s),
        _ => html_escape("-"),
    }
}

pub fn fn_cell_html(summary: Option<&CoverageSummary>) -> String {
    match summary {
        Some(s) if s.functions_found > 0 => fn_html(s),
        _ => html_escape("-"),
    }
}

fn format_lines_summary(s: &CoverageSummary, compact: bool) -> String {
    let unc = s.lines_uncovered();
    let pct = format!("{:.1}%", s.lines_percent());
    if compact {
        format!(
            "{}/{}·{}c·{}u·{pct}",
            s.lines_hit, s.lines_found, s.lines_hit, unc
        )
    } else {
        format!(
            "{}/{} · {} cov · {} unc · {pct}",
            s.lines_hit, s.lines_found, s.lines_hit, unc
        )
    }
}

fn format_fn_summary(s: &CoverageSummary, compact: bool) -> String {
    let pct = format!("{:.1}%", s.functions_percent());
    if compact {
        format!("{}/{}·{pct}", s.functions_hit, s.functions_found)
    } else {
        format!("{}/{} · {pct}", s.functions_hit, s.functions_found)
    }
}

fn colorize_lines_cell(plain: &str, s: &CoverageSummary, compact: bool) -> String {
    let pct = format!("{:.1}%", s.lines_percent());
    let colored_pct = theme::paint(&pct, theme::coverage_accent(s.lines_percent()));
    if plain.ends_with(&pct) {
        format!("{}{}", &plain[..plain.len() - pct.len()], colored_pct)
    } else {
        format_lines_summary(s, compact).replace(&pct, &colored_pct)
    }
}

fn lines_html(s: &CoverageSummary) -> String {
    let unc = s.lines_uncovered();
    let ratio = format!("{}/{}", s.lines_hit, s.lines_found);
    let pct = format!("{:.1}%", s.lines_percent());
    let pct_span = span_accent(&pct, theme::coverage_accent(s.lines_percent()));
    format!(
        "<span class=\"sub\">{}</span> · {} cov · <span class=\"low\">{} unc</span> · {pct_span}",
        html_escape(&ratio),
        s.lines_hit,
        unc
    )
}

fn fn_html(s: &CoverageSummary) -> String {
    let ratio = format!("{}/{}", s.functions_hit, s.functions_found);
    let pct = format!("{:.1}%", s.functions_percent());
    let pct_span = span_accent(&pct, theme::coverage_accent(s.functions_percent()));
    format!(
        "<span class=\"sub\">{}</span> · {pct_span}",
        html_escape(&ratio)
    )
}

fn span_accent(text: &str, accent: Accent) -> String {
    format!(
        "<span class=\"{}\">{}</span>",
        theme::html_class(accent),
        html_escape(text)
    )
}

fn html_escape(value: &str) -> String {
    value
        .replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample(lines_hit: u64, lines_found: u64, fn_hit: u64, fn_found: u64) -> CoverageSummary {
        CoverageSummary {
            lines_hit,
            lines_found,
            functions_hit: fn_hit,
            functions_found: fn_found,
        }
    }

    #[test]
    fn format_lines_cell_shows_counts_and_pct() {
        let s = sample(12, 45, 0, 0);
        assert_eq!(
            format_lines_cell(Some(&s), false),
            "12/45 · 12 cov · 33 unc · 26.7%"
        );
        assert_eq!(format_lines_cell(Some(&s), true), "12/45·12c·33u·26.7%");
    }

    #[test]
    fn format_lines_cell_dash_when_empty() {
        assert_eq!(format_lines_cell(None, false), "-");
        assert_eq!(format_lines_cell(Some(&sample(0, 0, 0, 0)), false), "-");
    }

    #[test]
    fn format_fn_cell_shows_hit_found_and_pct() {
        let s = sample(0, 0, 2, 8);
        assert_eq!(format_fn_cell(Some(&s), false), "2/8 · 25.0%");
    }

    #[test]
    fn lines_cell_html_contains_ratio_and_pct() {
        let s = sample(12, 45, 0, 0);
        let html = lines_cell_html(Some(&s));
        assert!(html.contains("12/45"));
        assert!(html.contains("33 unc"));
        assert!(html.contains("26.7%"));
    }
}
