use std::fs::File;
use std::path::Path;
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};

static CANCELLED: AtomicBool = AtomicBool::new(false);

pub fn cancelled() -> bool {
    CANCELLED.load(Ordering::SeqCst)
}

pub fn install_cancellation() -> Result<()> {
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()?;
    #[cfg(unix)]
    {
        let guard = runtime.enter();
        let mut interrupt =
            tokio::signal::unix::signal(tokio::signal::unix::SignalKind::interrupt())?;
        let mut terminate =
            tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())?;
        drop(guard);
        std::thread::spawn(move || {
            runtime.block_on(async {
                tokio::select! { _ = interrupt.recv() => {}, _ = terminate.recv() => {} }
            });
            CANCELLED.store(true, Ordering::SeqCst);
        });
    }
    #[cfg(not(unix))]
    std::thread::spawn(move || {
        if runtime.block_on(tokio::signal::ctrl_c()).is_ok() {
            CANCELLED.store(true, Ordering::SeqCst);
        }
    });
    Ok(())
}

use anyhow::{bail, Context, Result};
use cherry_test_common::adapter::{AdapterRequest, ExecutionMode};

pub fn capture(mut command: Command, prefix: &Path, timeout: u64, group: bool) -> Result<bool> {
    std::fs::create_dir_all(prefix.parent().context("log directory")?)?;
    command.stdout(Stdio::from(File::create(prefix.with_extension("stdout"))?));
    command.stderr(Stdio::from(File::create(prefix.with_extension("stderr"))?));
    #[cfg(unix)]
    if group {
        use std::os::unix::process::CommandExt;
        command.process_group(0);
    }
    let program = command.get_program().to_string_lossy().into_owned();
    let mut child = command
        .spawn()
        .with_context(|| format!("start test process `{program}`"))?;
    let start = Instant::now();
    loop {
        if let Some(status) = child.try_wait()? {
            return Ok(status.success());
        }
        if cancelled() || start.elapsed() >= Duration::from_secs(timeout) {
            #[cfg(unix)]
            if group {
                let status = Command::new("kill")
                    .args(["-TERM", "--", &format!("-{}", child.id())])
                    .status()?;
                if !status.success() {
                    tracing::warn!(pid = child.id(), "could not terminate process group");
                }
            }
            let grace = Instant::now();
            while child.try_wait()?.is_none() && grace.elapsed() < Duration::from_secs(30) {
                std::thread::sleep(Duration::from_millis(50));
            }
            if child.try_wait()?.is_none() {
                #[cfg(unix)]
                if group {
                    let status = Command::new("kill")
                        .args(["-KILL", "--", &format!("-{}", child.id())])
                        .status()?;
                    if !status.success() {
                        tracing::warn!(pid = child.id(), "process group force stop failed");
                    }
                }
                child.kill()?;
            }
            child.wait()?;
            bail!(
                "test process cancelled or exceeded {timeout}s; see {}",
                prefix.display()
            );
        }
        std::thread::sleep(Duration::from_millis(25));
    }
}

pub fn mount_root(request: &AdapterRequest) -> &Path {
    request
        .root
        .ancestors()
        .find(|path| path.join(".git").is_dir())
        .unwrap_or(&request.root)
}

pub fn mapped(request: &AdapterRequest, value: &str) -> String {
    if request.execution == ExecutionMode::Container {
        value.replace(mount_root(request).to_string_lossy().as_ref(), "/workspace")
    } else {
        value.to_string()
    }
}

pub fn run(request: &AdapterRequest, program: &str, args: &[String], label: &str) -> Result<bool> {
    let mut command = if request.execution == ExecutionMode::Container {
        let image = request
            .options
            .get("image")
            .and_then(|v| v.as_str())
            .context("container execution requires options.image")?;
        let mut cmd = Command::new("docker");
        cmd.args([
            "run",
            "--rm",
            "--init",
            "--add-host",
            "host.docker.internal:host-gateway",
        ]);
        cmd.args([
            "--label",
            &format!(
                "cherry-test.run={}",
                std::env::var("CHERRY_TEST_RUN_ID").unwrap_or_default()
            ),
        ]);
        cmd.args([
            "-v",
            &format!("{}:/workspace", mount_root(request).display()),
        ]);
        cmd.args(["-w", &mapped(request, &request.cwd.to_string_lossy())]);
        for (key, _) in std::env::vars().filter(|(key, _)| key.starts_with("CHERRY_TEST_AUTH_")) {
            cmd.args(["-e", &key]);
        }
        for (key, value) in &request.env {
            let value = mapped(request, value)
                .replace("127.0.0.1:", "host.docker.internal:")
                .replace("localhost:", "host.docker.internal:");
            cmd.args(["-e", &format!("{key}={value}")]);
        }
        for (prefix, volume) in [
            ("maven:", "cherry-test-polyglot-maven:/root/.m2"),
            (
                "gradle:",
                "cherry-test-polyglot-gradle:/home/gradle/.gradle",
            ),
            (
                "cherry-test-rust:",
                "cherry-test-polyglot-cargo:/usr/local/cargo/registry",
            ),
        ] {
            if image.starts_with(prefix) {
                cmd.args(["-v", volume]);
            }
        }
        cmd.arg(image).arg(mapped(request, program));
        cmd.args(args.iter().map(|arg| mapped(request, arg)));
        cmd
    } else {
        let mut cmd = Command::new(program);
        cmd.args(args).current_dir(&request.cwd).envs(&request.env);
        cmd
    };
    command.stdin(Stdio::null());
    capture(command, &request.output.join(label), 1800, false)
}

pub fn strings(values: &[&str]) -> Vec<String> {
    values.iter().map(|s| s.to_string()).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    mod capture {
        use super::*;

        #[test]
        fn names_the_program_that_could_not_start() {
            let logs = tempfile::tempdir().unwrap();
            let command = Command::new("cherry-test-missing-runner");
            let error = capture(command, &logs.path().join("tests"), 5, false).unwrap_err();
            assert!(format!("{error:#}").contains("`cherry-test-missing-runner`"));
        }
    }
}
