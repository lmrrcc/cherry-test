use std::path::{Path, PathBuf};
use std::process::Command;

fn compile_fixture(root: &Path, source: &str) -> PathBuf {
    let source_path = root.join("suite.rs");
    std::fs::write(&source_path, source).unwrap();
    let binary = root.join("suite-bin");
    let compiled = Command::new("rustc")
        .arg(&source_path)
        .arg("-o")
        .arg(&binary)
        .output()
        .unwrap();
    assert!(
        compiled.status.success(),
        "{}",
        String::from_utf8_lossy(&compiled.stderr)
    );
    binary
}

fn execute(root: &Path, source: &str, coverage: bool) -> std::process::Output {
    let binary = compile_fixture(root, source);
    let command = binary.display().to_string();
    std::fs::write(
        root.join("cherry-test.yaml"),
        format!("defaults:\n  strict: true\n  coverage: true\nadapters:\n  fixture:\n    command: \"{command}\"\n    args: []\n    timeout_secs: 1\nprojects:\n  demo:\n    path: .\n    packages:\n      fixture:\n        adapter: fixture\n        manifest: suite.rs\n        kinds: [unit]\n"),
    )
    .unwrap();
    let mut command = Command::new(env!("CARGO_BIN_EXE_cherry-test"));
    command.args(["--root", root.to_str().unwrap(), "--no-open", "--quiet"]);
    if !coverage {
        command.arg("--no-coverage");
    }
    command.output().unwrap()
}

fn json_program(response: &str, exit_code: Option<i32>) -> String {
    let mut source =
        format!("fn main() {{\n    let body = r#\"{response}\"#;\n    println!(\"{{body}}\");\n");
    if let Some(code) = exit_code {
        source.push_str(&format!("    std::process::exit({code});\n"));
    }
    source.push_str("}\n");
    source
}

#[test]
fn success_failure_and_malformed_plugin_results_reach_cli_exit_status() {
    for (response, expected) in [
        (
            r#"{"protocol":1,"success":true,"tests":[{"name":"case","status":"passed","duration_ms":0}]}"#,
            true,
        ),
        (
            r#"{"protocol":1,"success":true,"tests":[{"name":"case","status":"failed","duration_ms":0}]}"#,
            false,
        ),
        (
            r#"{"protocol":1,"success":true,"tests":[{"name":"case","status":"skipped","duration_ms":0}]}"#,
            false,
        ),
        (r#"{"protocol":1,"success":true,"tests":[]}"#, false),
        ("invalid JSON", false),
    ] {
        let directory = tempfile::tempdir().unwrap();
        let result = execute(directory.path(), &json_program(response, None), false);
        assert_eq!(
            result.status.success(),
            expected,
            "{}",
            String::from_utf8_lossy(&result.stderr)
        );
        assert!(directory
            .path()
            .join("target/cherry-test/latest/reports/index.html")
            .is_file());
    }
}

#[test]
fn missing_coverage_and_nonzero_adapter_exit_cannot_pass() {
    let response = r#"{"protocol":1,"success":true,"tests":[{"name":"case","status":"passed","duration_ms":0}]}"#;
    for (source, coverage) in [
        (json_program(response, None), true),
        (json_program(response, Some(3)), false),
    ] {
        let directory = tempfile::tempdir().unwrap();
        assert!(!execute(directory.path(), &source, coverage)
            .status
            .success());
    }
}

#[test]
fn timeout_is_recorded_as_a_failed_phase() {
    let directory = tempfile::tempdir().unwrap();
    let result = execute(
        directory.path(),
        "fn main() {\n    std::thread::sleep(std::time::Duration::from_secs(10));\n}\n",
        false,
    );
    assert!(!result.status.success());
    let path = directory
        .path()
        .join("target/cherry-test/latest/reports/adapters/demo/fixture/unit/result.json");
    let report = std::fs::read_to_string(path).unwrap();
    assert!(report.contains("exceeded 1s"));
}
