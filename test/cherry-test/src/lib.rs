pub mod adapters;
pub mod auth_preflight;
pub mod config;
pub mod coverage;
pub mod coverage_ignore;
pub mod crap;
pub mod discover;
pub mod filter;
pub mod git_diff;
pub mod init;
pub mod node_discover;
pub mod node_runtime;
pub mod preflight;
pub mod report;
pub mod run_dir;
pub mod runner;
pub mod scaffold;
pub mod update;
pub mod vitest_report;

use anyhow::Result;
use cherry_test_core::TestItConfig;
use clap::{Parser, Subcommand};

pub const COMMAND_NAME: &str = "cherry-test";

pub use config::{Config, TestKind};
pub use discover::{config_roots_from, workspace_root, workspace_root_from};
pub use init::TestPlan;
pub use run_dir::RunDir;
pub use update::UpdateCli;

#[derive(Debug, Parser)]
#[command(
    name = "cherry-test",
    version,
    about = "cherry-test: workspace tests, coverage and browser reports",
    args_conflicts_with_subcommands = true
)]
pub struct Cli {
    #[command(subcommand)]
    pub command: Option<Command>,

    #[command(flatten)]
    pub run: RunCli,
}

#[derive(Debug, Subcommand)]
pub enum Command {
    Update(UpdateCli),
    Adapter(adapters::AdapterCli),
}

#[derive(Debug, Parser)]
pub struct RunCli {
    #[arg(help = "Project group id (e.g. mini) or package name (e.g. mini-rs-api)")]
    pub filter: Option<String>,

    #[arg(long, help = "Repo root directory (default: auto-detect from cwd)")]
    pub root: Option<std::path::PathBuf>,

    #[arg(
        long,
        help = "Create cherry-test.yaml from discovered packages and exit"
    )]
    pub init_only: bool,

    #[arg(long, help = "Regenerate cherry-test.yaml even when it already exists")]
    pub force_init: bool,

    #[arg(long)]
    pub no_coverage: bool,

    #[arg(long, help = "Do not calculate cyclomatic complexity or report CRAP")]
    pub no_crap: bool,

    #[arg(long, value_parser = ["local", "container"])]
    pub execution: Option<String>,

    #[arg(long)]
    pub strict: bool,

    #[arg(
        long,
        value_delimiter = ',',
        value_parser = ["unit", "integration", "functional", "ui", "visual", "mutation"],
        help = "Phases to run. mutation runs only when it is named here"
    )]
    pub kinds: Vec<String>,

    #[arg(long)]
    pub no_html: bool,

    #[arg(long, help = "Do not open the HTML report in a browser")]
    pub no_open: bool,

    #[arg(long, help = "Do not print the startup banner")]
    pub no_banner: bool,

    #[arg(long, help = "Continue after a project failure")]
    pub keep_going: bool,

    #[arg(short, long)]
    pub quiet: bool,

    #[arg(
        short = 'v',
        long,
        help = "Print each suite and test result in the terminal"
    )]
    pub verbose: bool,

    #[arg(last = true, help = "Extra args forwarded to cargo test")]
    pub cargo_test_args: Vec<String>,
}

pub fn run(cli: Cli) -> Result<i32> {
    match cli.command {
        Some(Command::Update(update)) => return update::run_update(update),
        Some(Command::Adapter(adapter)) => return adapters::cli(adapter),
        None => {}
    }
    adapters::process::install_cancellation()?;
    run_tests(cli.run)
}

fn run_tests(cli: RunCli) -> Result<i32> {
    if show_banner(&cli) {
        print_banner();
    }
    let config_roots = discover::config_roots_from(cli.root.as_deref(), cli.filter.as_deref())?;
    let mut exit_code = 0;
    let multi = config_roots.len() > 1;
    for config_root in &config_roots {
        if multi && !cli.quiet {
            println!("{COMMAND_NAME}: config root {}", config_root.display());
        }
        let code = run_tests_at_root(&cli, config_root)?;
        if code != 0 {
            exit_code = code;
            if !cli.keep_going {
                return Ok(exit_code);
            }
        }
    }
    Ok(exit_code)
}

fn run_tests_at_root(cli: &RunCli, workspace: &std::path::Path) -> Result<i32> {
    std::env::set_var(
        "CHERRY_TEST_WORKSPACE_ROOT",
        workspace.to_string_lossy().as_ref(),
    );

    let mut config = TestItConfig::load(workspace)?;
    let needs_scaffold = scaffold::config_needs_scaffold(&config, workspace);
    if cli.force_init || needs_scaffold {
        if needs_scaffold && !config.projects.is_empty() && !cli.quiet {
            eprintln!(
                "{COMMAND_NAME}: regenerated cherry-test.yaml (invalid or missing project layout)"
            );
        }
        config = scaffold::scaffold_and_write(workspace, cli.quiet)?;
    }

    if let Some(execution) = &cli.execution {
        config = config.with_execution(if execution == "container" {
            cherry_test_common::adapter::ExecutionMode::Container
        } else {
            cherry_test_common::adapter::ExecutionMode::Local
        });
    }
    config.defaults.strict |= cli.strict;

    if cli.init_only {
        return Ok(0);
    }

    let plan =
        match init::initialize_with_config(workspace, config, cli.filter.as_deref(), cli.quiet) {
            Ok(plan) => plan,
            Err(e) => {
                if e.to_string()
                    .contains("no testable workspace members found")
                {
                    eprintln!("no testable workspace members found");
                    return Ok(if cli.strict { 1 } else { 0 });
                }
                return Err(e);
            }
        };

    let coverage_requested =
        plan.config.defaults.coverage && !cli.no_coverage && !skip_coverage_env();
    let has_rust_packages = plan.projects.iter().any(|project| {
        project.members.iter().any(|member| {
            member.runtime == cherry_test_core::PackageRuntime::Rust
                && plan.config.projects[&project.id].packages[&member.name]
                    .adapter
                    .as_deref()
                    .unwrap_or("cargo")
                    == "cargo"
        })
    });

    if coverage_requested && has_rust_packages && !coverage::llvm_cov_available() {
        eprintln!(
            "warning: cargo-llvm-cov not found; running tests without coverage. \
             Install: cargo install cargo-llvm-cov && rustup component add llvm-tools-preview"
        );
    }

    let run_dir = run_dir::RunDir::create(workspace, &plan.config.defaults.runs_base)?;
    if !cli.quiet {
        println!(
            "{COMMAND_NAME} run: {} ({})",
            run_dir.id,
            run_dir.root.display()
        );
        println!();
    }
    run_dir.export_env();
    std::env::set_var("CHERRY_TEST_RUN_ID", &run_dir.id);
    std::env::set_var(
        "CHERRY_TEST_COMPOSE_PROJECT",
        format!("cherry-test-{}", run_dir.id),
    );

    let opts = runner::RunOptions {
        workspace_root: workspace,
        run_dir: &run_dir,
        config: &plan.config,
        coverage: coverage_requested,
        quiet: cli.quiet,
        test_listing: if cli.verbose {
            runner::TestListing::AtEndOfRun
        } else {
            runner::TestListing::AfterEachPhase
        },
        extra_test_args: &cli.cargo_test_args,
        kinds: &cli.kinds,
    };

    let preflight = plan.preflight.clone();
    let outcome = runner::run_projects(&plan.projects, &opts, &preflight)?;

    let merged_lcov = run_dir.merged_lcov();
    let mut coverage_totals = report::CoverageTotals::default();
    let html_enabled = plan.config.defaults.html_report && !cli.no_html;

    let coverage_enabled = coverage_requested;
    let coverage_ignore = coverage_ignore::CoverageIgnore::load(workspace, &plan.config);

    if coverage_requested {
        coverage::merge_lcov_files(&outcome.lcov_files, &merged_lcov)?;
        let phase_summary = |files: &[std::path::PathBuf],
                             name: &str|
         -> Result<Option<coverage::CoverageSummary>> {
            if files.is_empty() {
                return Ok(None);
            }
            let merged = run_dir.root.join(format!("{name}.lcov"));
            coverage::merge_lcov_files(files, &merged)?;
            Ok(Some(coverage::summary_for_lcov(&merged, &coverage_ignore)?))
        };
        coverage_totals = report::CoverageTotals {
            unit: phase_summary(&outcome.unit_lcov_files, "unit")?,
            integration: phase_summary(&outcome.integration_lcov_files, "integration")?,
            merged: coverage::summary_for_lcov(&merged_lcov, &coverage_ignore)?,
        };
    }

    if !cli.quiet {
        println!("==> report snapshot");
    }
    let snapshot = report::build_report_snapshot(
        workspace,
        &run_dir,
        &plan.projects,
        &outcome,
        &plan.preflight,
        &coverage_totals,
        &plan.config,
        coverage_enabled,
        !cli.no_crap,
    )?;

    let mut report_failed = false;
    if html_enabled {
        if !cli.quiet {
            println!("==> html report");
        }
        match report::write_html_report(&snapshot) {
            Ok(()) => {
                if report::should_open_report(cli.no_open, &plan.config.defaults) {
                    if let Err(e) = report::open_html_report(&snapshot.report_path) {
                        if !cli.quiet {
                            eprintln!("warning: could not open html report: {e:#}");
                        }
                    }
                }
            }
            Err(e) => {
                report_failed = true;
                if !cli.quiet {
                    eprintln!("warning: html report failed: {e:#}");
                }
            }
        }
    }

    if let Err(e) = run_dir.update_latest_symlink(workspace, &plan.config.defaults.runs_base) {
        if !cli.quiet {
            eprintln!("warning: latest symlink failed: {e:#}");
        }
    }

    report::print_aggregate_report(&snapshot, !cli.quiet, cli.verbose && !cli.quiet);

    if outcome.ran_integration {
        let _ = cherry_test_core::teardown_scopes();
    }

    if outcome.failed || report_failed {
        return Ok(1);
    }
    Ok(0)
}

fn show_banner(cli: &RunCli) -> bool {
    !cli.no_banner && !cli.quiet
}

fn print_banner() {
    println!();
    println!("{}", render_banner(report::colors_enabled()));
    println!();
}

const BANNER_MARK_COLUMNS: usize = 17;
const BANNER_FRUIT: &str = "\u{1b}[38;2;226;59;50m";
const BANNER_STEM: &str = "\u{1b}[38;2;217;180;143m";
const BANNER_PAY: &str = "\u{1b}[38;2;194;181;168m";
const BANNER_ACCENT: &str = "\u{1b}[38;2;255;155;146m";
const BANNER_RESET: &str = "\u{1b}[0m";

fn banner_text() -> &'static str {
    r#"     ╱▓▓▓▓▓       ██████╗  ██╗  ██╗  ███████╗  ██████╗   ██████╗   ██╗   ██╗    ████████╗  ███████╗  ███████╗  ████████╗
    ╱   ╲        ██╔════╝▒ ██║▒ ██║▒ ██╔════╝▒ ██╔══██╗  ██╔══██╗  ╚██╗ ██╔╝▒   ╚══██╔══╝▒ ██╔════╝▒ ██╔════╝▒ ╚══██╔══╝▒
   ╱    ▄▀▀▀▄    ██║▒▒▒▒▒▒ ███████║▒ █████╗▒▒▒ ██████╔╝▒ ██████╔╝▒  ╚████╔╝▒▒    ▒▒██║▒▒▒▒ █████╗▒▒▒ ███████╗▒  ▒▒██║▒▒▒▒
▄▀▀▀▄   ▌   ▐▒   ██║▒      ██╔══██║▒ ██╔══╝▒   ██╔══██╗▒ ██╔══██╗▒   ╚██╔╝▒▒       ██║▒    ██╔══╝▒   ╚════██║▒    ██║▒
▌   ▐▒  ▀▄▄▄▀▒   ╚██████╗  ██║▒▒██║▒ ███████╗  ██║▒▒██║▒ ██║▒▒██║▒    ██║▒▒        ██║▒    ███████╗  ███████║▒    ██║▒
▀▄▄▄▀▒   ▒▒▒▒▒    ╚═════╝▒ ╚═╝▒ ╚═╝▒ ╚══════╝▒ ╚═╝▒ ╚═╝▒ ╚═╝▒ ╚═╝▒    ╚═╝▒         ╚═╝▒    ╚══════╝▒ ╚══════╝▒    ╚═╝▒
 ▒▒▒▒▒             ▒▒▒▒▒▒▒  ▒▒▒  ▒▒▒  ▒▒▒▒▒▒▒▒  ▒▒▒  ▒▒▒  ▒▒▒  ▒▒▒     ▒▒▒          ▒▒▒     ▒▒▒▒▒▒▒▒  ▒▒▒▒▒▒▒▒     ▒▒▒

                 Multiple mono-repositories. Single test workflow."#
}

fn render_banner(color: bool) -> String {
    let mut out = String::new();
    for (index, line) in banner_text().lines().enumerate() {
        if index > 0 {
            out.push('\n');
        }
        if color {
            out.push_str(&color_banner_line(line));
        } else {
            out.push_str(line);
        }
    }
    out
}

fn color_banner_line(line: &str) -> String {
    const PHRASE: &str = "Multiple mono-repositories. Single test workflow.";
    if let Some(at) = line.find(PHRASE) {
        let head = &line[..at];
        let lead = "Multiple mono-repositories. ";
        let accent = "Single test workflow.";
        return format!(
            "{head}{BANNER_PAY}{lead}{BANNER_RESET}{BANNER_ACCENT}{accent}{BANNER_RESET}"
        );
    }
    let mut mark = String::new();
    let mut word = String::new();
    for (index, ch) in line.chars().enumerate() {
        if index < BANNER_MARK_COLUMNS {
            mark.push(ch);
        } else {
            word.push(ch);
        }
    }
    let mut out = color_mark(&mark);
    if !word.is_empty() {
        out.push_str("\u{1b}[1m");
        out.push_str(&word);
        out.push_str(BANNER_RESET);
    }
    out
}

fn color_mark(mark: &str) -> String {
    let mut out = String::new();
    let mut kind = ' ';
    let mut buf = String::new();
    for ch in mark.chars() {
        let next = match ch {
            '▀' | '▄' | '▌' | '▐' | '▒' | '░' => 'f',
            '▓' | '╱' | '╲' => 's',
            _ => 'g',
        };
        if next != kind {
            push_mark_run(&mut out, kind, &buf);
            buf.clear();
            kind = next;
        }
        buf.push(ch);
    }
    push_mark_run(&mut out, kind, &buf);
    out
}

fn push_mark_run(out: &mut String, kind: char, buf: &str) {
    if buf.is_empty() {
        return;
    }
    let paint = match kind {
        'f' => BANNER_FRUIT,
        's' => BANNER_STEM,
        _ => "",
    };
    out.push_str(paint);
    out.push_str(buf);
    if !paint.is_empty() {
        out.push_str(BANNER_RESET);
    }
}

fn skip_coverage_env() -> bool {
    std::env::var("CHERRY_TEST_SKIP_COVERAGE")
        .map(|v| v == "1" || v.eq_ignore_ascii_case("true"))
        .unwrap_or(false)
}

#[cfg(test)]
mod integration {
    use super::*;

    #[test]
    fn cli_parses_filter() {
        let cli = Cli::try_parse_from(["cherry-test", "mini"]).unwrap();
        assert_eq!(cli.run.filter.as_deref(), Some("mini"));
        let compat = Cli::try_parse_from(["cherry-test", "mini"]).unwrap();
        assert_eq!(compat.run.filter.as_deref(), Some("mini"));
    }

    #[test]
    fn cli_name_is_cherry_test() {
        use clap::CommandFactory;
        assert_eq!(Cli::command().get_name(), COMMAND_NAME);
    }

    #[test]
    fn cli_parses_root_and_init_only() {
        let cli =
            Cli::try_parse_from(["cherry-test", "--root", "/tmp/repo", "--init-only"]).unwrap();
        assert_eq!(
            cli.run.root.as_deref().map(|p| p.to_str().unwrap()),
            Some("/tmp/repo")
        );
        assert!(cli.run.init_only);
    }

    #[test]
    fn cli_hides_banner_when_asked() {
        let shown = Cli::try_parse_from(["cherry-test", "mini"]).unwrap();
        assert!(show_banner(&shown.run));
        let hidden = Cli::try_parse_from(["cherry-test", "--no-banner"]).unwrap();
        assert!(hidden.run.no_banner);
        assert!(!show_banner(&hidden.run));
        let quiet = Cli::try_parse_from(["cherry-test", "--quiet"]).unwrap();
        assert!(!show_banner(&quiet.run));
    }

    #[test]
    fn banner_fits_a_terminal_line() {
        let lines: Vec<_> = banner_text().lines().collect();
        assert_eq!(lines.len(), 9);
        assert!(lines.iter().all(|line| line.chars().count() <= 124));
        assert!(banner_text().contains("Multiple mono-repositories. Single test workflow."));
        assert!(!render_banner(false).contains('\u{1b}'));
        let colored = render_banner(true);
        assert!(colored.contains("\u{1b}[38;2;226;59;50m"));
        assert!(colored.contains("\u{1b}[38;2;217;180;143m"));
        assert!(colored.contains("Single test workflow."));
    }

    #[test]
    fn cli_accepts_mutation_only_when_named() {
        let cli = Cli::try_parse_from(["cherry-test", "--kinds", "unit,mutation"]).unwrap();
        assert_eq!(cli.run.kinds, ["unit", "mutation"]);
        assert!(Cli::try_parse_from(["cherry-test", "--kinds", "mutant"]).is_err());
        assert!(Cli::try_parse_from(["cherry-test", "--kinds", "crap"]).is_err());
        assert!(!Cli::try_parse_from(["cherry-test"]).unwrap().run.no_crap);
        assert!(
            Cli::try_parse_from(["cherry-test", "--no-crap"])
                .unwrap()
                .run
                .no_crap
        );
    }

    #[test]
    fn cli_parses_update_subcommand() {
        let cli = Cli::try_parse_from(["cherry-test", "update", "--quiet"]).unwrap();
        match cli.command {
            Some(Command::Update(update)) => assert!(update.quiet),
            _ => panic!("expected update subcommand"),
        }
    }
}
