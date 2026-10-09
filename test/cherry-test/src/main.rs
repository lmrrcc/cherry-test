use anyhow::Result;
use cherry_test::{run, Cli};
use clap::Parser;

fn main() -> Result<()> {
    if std::env::var_os("RUST_LOG").is_some() {
        tracing_subscriber::fmt()
            .with_env_filter(tracing_subscriber::EnvFilter::from_default_env())
            .init();
    }
    let mut args: Vec<_> = std::env::args_os().collect();
    let plugin_arg = args
        .first()
        .and_then(|arg| std::path::Path::new(arg).file_stem())
        .and_then(|name| {
            if name == "cargo-cherry-test" {
                Some("cherry-test")
            } else {
                None
            }
        });
    if let Some(name) = plugin_arg {
        if args.get(1).is_some_and(|arg| arg == name) {
            args.remove(1);
        }
    }
    let cli = Cli::parse_from(args);
    let code = run(cli)?;
    std::process::exit(code);
}
