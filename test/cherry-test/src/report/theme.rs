use std::io::{self, IsTerminal};

use crate::coverage::CoverageSummary;
use crate::report::model::ReportSnapshot;

pub const LOW_COVERAGE_THRESHOLD: f64 = 50.0;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Accent {
    Ok,
    Skip,
    Fail,
    Covered,
    Uncovered,
    Low,
    Neutral,
}

pub fn colors_enabled() -> bool {
    if std::env::var_os("NO_COLOR").is_some() {
        return false;
    }
    io::stdout().is_terminal()
}

pub fn coverage_accent(pct: f64) -> Accent {
    if pct.is_nan() {
        Accent::Neutral
    } else if pct < LOW_COVERAGE_THRESHOLD {
        Accent::Low
    } else {
        Accent::Ok
    }
}

pub fn coverage_accent_opt(summary: Option<&CoverageSummary>) -> Accent {
    match summary {
        Some(s) if s.lines_found > 0 => coverage_accent(s.lines_percent()),
        _ => Accent::Neutral,
    }
}

pub fn status_accent(label: &str) -> Accent {
    match label {
        "ok" | "OK" => Accent::Ok,
        "skipped" => Accent::Skip,
        "FAIL" | "fail" => Accent::Fail,
        _ => Accent::Neutral,
    }
}

pub fn suite_accent(fail: usize, skip: usize) -> Accent {
    if fail > 0 {
        Accent::Fail
    } else if skip > 0 {
        Accent::Skip
    } else {
        Accent::Ok
    }
}

pub fn html_class(accent: Accent) -> &'static str {
    match accent {
        Accent::Ok => "ok",
        Accent::Skip => "skip",
        Accent::Fail => "fail",
        Accent::Covered => "covered",
        Accent::Uncovered => "uncovered",
        Accent::Low => "low",
        Accent::Neutral => "neutral",
    }
}

pub fn paint(text: &str, accent: Accent) -> String {
    paint_when(colors_enabled(), text, accent)
}

pub fn paint_when(enabled: bool, text: &str, accent: Accent) -> String {
    if !enabled {
        return text.to_string();
    }
    format!("{}{text}{}", ansi_code(accent), ANSI_RESET)
}

fn ansi_code(accent: Accent) -> &'static str {
    match accent {
        Accent::Ok => ANSI_GREEN,
        Accent::Skip | Accent::Uncovered | Accent::Low => ANSI_YELLOW,
        Accent::Fail => ANSI_RED,
        Accent::Covered => ANSI_BLUE,
        Accent::Neutral => "",
    }
}

const ANSI_GREEN: &str = "\x1b[32m";
const ANSI_YELLOW: &str = "\x1b[33m";
const ANSI_BLUE: &str = "\x1b[94m";
const ANSI_RED: &str = "\x1b[31m";
const ANSI_RESET: &str = "\x1b[0m";

pub fn colorize_status_line(plain: &str, snapshot: &ReportSnapshot) -> String {
    if !colors_enabled() {
        return plain.to_string();
    }
    let mut out = plain.to_string();
    let overall = format!(
        "{} {}",
        crate::COMMAND_NAME,
        if snapshot.overall_ok { "OK" } else { "FAIL" }
    );
    let accent = if snapshot.overall_ok {
        Accent::Ok
    } else {
        Accent::Fail
    };
    out = out.replacen(&overall, &paint(&overall, accent), 1);
    if snapshot.integration_skipped > 0 {
        let token = format!("skip:{}", snapshot.integration_skipped);
        out = out.replace(&token, &paint(&token, Accent::Skip));
    }
    if snapshot.packages_failed > 0 {
        let token = format!("fail:{}", snapshot.packages_failed);
        if out.contains(&format!(
            "pkg {}/{} fail:",
            snapshot.packages_passed, snapshot.packages_total
        )) {
            let pkg_fail = format!(
                "pkg {}/{} fail:{}",
                snapshot.packages_passed, snapshot.packages_total, snapshot.packages_failed
            );
            out = out.replace(
                &pkg_fail,
                &format!(
                    "pkg {}/{} {}",
                    snapshot.packages_passed,
                    snapshot.packages_total,
                    paint(&format!("fail:{}", snapshot.packages_failed), Accent::Fail)
                ),
            );
        } else {
            out = out.replace(&token, &paint(&token, Accent::Fail));
        }
    }
    if snapshot.integration_failed > 0 {
        let token = format!("fail:{}", snapshot.integration_failed);
        out = out.replace(&token, &paint(&token, Accent::Fail));
    }
    if snapshot.coverage_enabled {
        out = colorize_cov_segment(&out, "all:", snapshot.totals.merged.lines_percent());
        if let Some(unit) = snapshot.totals.unit.as_ref() {
            out = colorize_cov_segment(&out, " unit:", unit.lines_percent());
        }
        if let Some(it) = snapshot.totals.integration.as_ref() {
            out = colorize_cov_segment(&out, " it:", it.lines_percent());
        }
        out = colorize_cov_segment(
            &out,
            " diff:",
            snapshot.diff_coverage.merged.lines_percent(),
        );
    }
    out
}

fn colorize_cov_segment(line: &str, prefix: &str, pct: f64) -> String {
    let Some(start) = line.find(prefix) else {
        return line.to_string();
    };
    let value_start = start + prefix.len();
    let rest = &line[value_start..];
    let end = rest.find(' ').unwrap_or(rest.len());
    let value = &line[value_start..value_start + end];
    if value == "-" {
        return line.to_string();
    }
    let colored = paint(value, coverage_accent(pct));
    format!(
        "{}{}{}",
        &line[..value_start],
        colored,
        &line[value_start + end..]
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;

    #[test]
    fn coverage_accent_uses_threshold() {
        assert_eq!(coverage_accent(49.9), Accent::Low);
        assert_eq!(coverage_accent(50.0), Accent::Ok);
        assert_eq!(coverage_accent(f64::NAN), Accent::Neutral);
    }

    #[test]
    fn status_accent_maps_labels() {
        assert_eq!(status_accent("ok"), Accent::Ok);
        assert_eq!(status_accent("skipped"), Accent::Skip);
        assert_eq!(status_accent("FAIL"), Accent::Fail);
    }

    #[test]
    fn paint_respects_no_color_env() {
        std::env::set_var("NO_COLOR", "1");
        let painted = paint("ok", Accent::Ok);
        std::env::remove_var("NO_COLOR");
        assert_eq!(painted, "ok");
    }

    #[test]
    fn colorize_status_line_does_not_match_it_inside_unit() {
        std::env::set_var("NO_COLOR", "1");
        let snapshot = ReportSnapshot {
            run_id: "run".into(),
            overall_ok: true,
            packages_passed: 2,
            packages_total: 2,
            packages_failed: 0,
            integration_run: 0,
            integration_skipped: 0,
            integration_failed: 0,
            coverage_enabled: true,
            totals: crate::report::CoverageTotals {
                unit: Some(crate::coverage::CoverageSummary {
                    lines_hit: 406,
                    lines_found: 1000,
                    functions_hit: 0,
                    functions_found: 0,
                }),
                integration: Some(crate::coverage::CoverageSummary {
                    lines_hit: 0,
                    lines_found: 10,
                    functions_hit: 0,
                    functions_found: 0,
                }),
                merged: crate::coverage::CoverageSummary {
                    lines_hit: 268,
                    lines_found: 1000,
                    functions_hit: 0,
                    functions_found: 0,
                },
            },
            diff_coverage: crate::report::CoverageTotals {
                merged: crate::coverage::CoverageSummary {
                    lines_hit: 193,
                    lines_found: 1000,
                    functions_hit: 0,
                    functions_found: 0,
                },
                ..Default::default()
            },
            projects: vec![],
            project_diff_sections: vec![],
            integration_rows: vec![],
            tests_report: crate::report::TestsReport::default(),
            crap: crate::crap::CrapReport::default(),
            report_path: PathBuf::from("index.html"),
        };
        let plain = crate::report::format_status_line(&snapshot, 200);
        assert!(plain.contains("unit:40.6%"));
        assert!(plain.contains(" it:0.0%"));
        std::env::remove_var("NO_COLOR");
    }

    #[test]
    fn colorize_status_line_keeps_plain_length_when_no_color() {
        std::env::set_var("NO_COLOR", "1");
        let snapshot = ReportSnapshot {
            run_id: "run".into(),
            overall_ok: false,
            packages_passed: 0,
            packages_total: 1,
            packages_failed: 1,
            integration_run: 0,
            integration_skipped: 1,
            integration_failed: 1,
            coverage_enabled: true,
            totals: crate::report::CoverageTotals::default(),
            diff_coverage: crate::report::CoverageTotals::default(),
            projects: vec![],
            project_diff_sections: vec![],
            integration_rows: vec![],
            tests_report: crate::report::TestsReport::default(),
            crap: crate::crap::CrapReport::default(),
            report_path: PathBuf::from("index.html"),
        };
        let plain = crate::report::format_status_line(&snapshot, 260);
        let colored = colorize_status_line(&plain, &snapshot);
        std::env::remove_var("NO_COLOR");
        assert_eq!(plain, colored);
        assert!(plain.len() <= 260);
    }
}
