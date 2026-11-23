use std::process::Command;

fn unchanged_project(base: &str) -> tempfile::TempDir {
    let root = tempfile::tempdir().unwrap();
    std::fs::write(root.path().join("pom.xml"), "<project><modelVersion>4.0.0</modelVersion><groupId>demo</groupId><artifactId>fixture</artifactId><version>1</version></project>").unwrap();
    std::fs::write(root.path().join("cherry-test.yaml"), format!("defaults:\n  coverage: false\n  strict: true\n  diff_base: {base}\nprojects:\n  demo:\n    path: .\n    packages:\n      fixture:\n        adapter: maven\n        manifest: pom.xml\n        kinds: [unit]\n")).unwrap();
    for args in [
        vec!["init", "-b", "main"],
        vec!["add", "."],
        vec![
            "-c",
            "user.name=Fixture",
            "-c",
            "user.email=fixture@example.invalid",
            "commit",
            "-m",
            "baseline",
        ],
    ] {
        let output = Command::new("git")
            .current_dir(root.path())
            .args(args)
            .output()
            .unwrap();
        assert!(
            output.status.success(),
            "{}",
            String::from_utf8_lossy(&output.stderr)
        );
    }
    root
}

#[test]
fn unchanged_source_reports_not_needed_without_starting_maven() {
    let root = unchanged_project("main");
    let result = Command::new(env!("CARGO_BIN_EXE_cherry-test"))
        .args([
            "--root",
            root.path().to_str().unwrap(),
            "--kinds",
            "mutation",
            "--no-open",
            "--no-crap",
        ])
        .output()
        .unwrap();
    assert!(
        result.status.success(),
        "{}",
        String::from_utf8_lossy(&result.stderr)
    );
    let reports = root.path().join("target/cherry-test/latest/reports");
    let output = reports.join("adapters/demo/fixture/mutation");
    let report: serde_json::Value =
        serde_json::from_slice(&std::fs::read(output.join("mutation.json")).unwrap()).unwrap();
    assert_eq!(report["status"], "no_changes");
    assert!(!output.join("mutation-compile.stdout").exists());
    assert!(std::fs::read_to_string(reports.join("index.html"))
        .unwrap()
        .contains("no changed production sources"));
    let result: serde_json::Value =
        serde_json::from_slice(&std::fs::read(output.join("result.json")).unwrap()).unwrap();
    assert_eq!(result["result"]["tests"], serde_json::json!([]));
}

#[test]
fn invalid_mutation_base_fails_instead_of_claiming_no_changes() {
    let root = unchanged_project("missing-base");
    let result = Command::new(env!("CARGO_BIN_EXE_cherry-test"))
        .args([
            "--root",
            root.path().to_str().unwrap(),
            "--kinds",
            "mutation",
            "--no-open",
            "--no-crap",
        ])
        .output()
        .unwrap();
    assert!(!result.status.success());
    let output = root
        .path()
        .join("target/cherry-test/latest/reports/adapters/demo/fixture/mutation");
    assert!(!output.join("mutation.json").exists());
    assert!(std::fs::read_to_string(output.join("result.json"))
        .unwrap()
        .contains("missing-base"));
}

#[test]
fn automatic_mutation_waits_for_tests_even_without_phase_barriers() {
    let root = tempfile::tempdir().unwrap();
    let source = root.path().join("adapter.rs");
    let binary = root.path().join("adapter");
    let marker = root.path().join("unit-finished");
    std::fs::write(
        &source,
        r#"use std::{env, fs, path::Path, thread, time::Duration};
fn main() {
    let args: Vec<String> = env::args().collect();
    let passed = if args[1] == "unit" {
        thread::sleep(Duration::from_millis(400));
        fs::write(&args[2], "finished").unwrap();
        true
    } else {
        Path::new(&args[2]).is_file()
    };
    let status = if passed { "passed" } else { "failed" };
    println!("{{\"protocol\":1,\"success\":{passed},\"tests\":[{{\"name\":\"{}\",\"status\":\"{status}\"}}]}}", args[1]);
}
"#,
    )
    .unwrap();
    let compiled = Command::new("rustc")
        .arg(&source)
        .arg("-o")
        .arg(&binary)
        .output()
        .unwrap();
    assert!(
        compiled.status.success(),
        "{}",
        String::from_utf8_lossy(&compiled.stderr)
    );
    std::fs::write(
        root.path().join("pom.xml"),
        "<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>fixture</artifactId><version>1</version></project>",
    )
    .unwrap();
    std::fs::write(
        root.path().join("cherry-test.yaml"),
        format!(
            "defaults:\n  coverage: false\n  html_report: false\n  parallel:\n    jobs: 2\n    cpus: 2\n    phase_barrier: false\nadapters:\n  fixture-unit:\n    command: {binary:?}\n    args: [unit, {marker:?}]\n  fixture-mutation:\n    command: {binary:?}\n    args: [mutation, {marker:?}]\nprojects:\n  demo:\n    path: .\n    packages:\n      fixture:\n        manifest: pom.xml\n        adapter: maven\n        kinds: [unit]\n        unit:\n          adapter: fixture-unit\n        mutation:\n          adapter: fixture-mutation\n"
        ),
    )
    .unwrap();
    let result = Command::new(env!("CARGO_BIN_EXE_cherry-test"))
        .args([
            "--root",
            root.path().to_str().unwrap(),
            "--no-open",
            "--no-crap",
            "--quiet",
        ])
        .output()
        .unwrap();
    assert!(
        result.status.success(),
        "stdout: {}\nstderr: {}",
        String::from_utf8_lossy(&result.stdout),
        String::from_utf8_lossy(&result.stderr)
    );
    let reports = root
        .path()
        .join("target/cherry-test/latest/reports/adapters/demo/fixture");
    for phase in ["unit", "mutation"] {
        let document: serde_json::Value = serde_json::from_slice(
            &std::fs::read(reports.join(phase).join("result.json")).unwrap(),
        )
        .unwrap();
        assert_eq!(document["result"]["tests"][0]["name"], phase);
        assert_eq!(document["result"]["success"], true);
    }
}

#[test]
fn pit_mutation_generated_test_records_remain_isolated_from_ordinary_reports() {
    let root = tempfile::tempdir().unwrap();
    let source = root.path().join("adapter.rs");
    let binary = root.path().join("adapter");
    std::fs::write(&source, r##"use std::{env, fs, path::Path};
fn main() {
    let mutation = env::args().nth(1).unwrap() == "mutation";
    let report = env::var("CHERRY_TEST_REPORT_DIR").unwrap();
    let report = Path::new(&report);
    let integration = report.join("integration");
    fs::create_dir_all(&integration).unwrap();
    let (name, passed, duration) = if mutation { ("mutation_jsonl_failure", false, 999999999) } else { ("ordinary_integration", true, 7) };
    fs::write(integration.join("fixture.jsonl"), format!("{{\"project\":\"demo\",\"suite\":\"api\",\"test\":\"{name}\",\"full_name\":\"demo_project_api_suite_{name}_test\",\"elapsed_ms\":{duration},\"passed\":{passed},\"method\":\"GET\",\"url\":\"http://fixture.invalid/\",\"status\":200}}\n")).unwrap();
    if mutation {
        fs::write(report.parent().unwrap().join("mutation.json"), r#"{"tool":"pitest","status":"complete","sources":["src/main/java/Price.java"],"mutants":[{"status":"KILLED","detected":true,"source":"src/main/java/Price.java","line":2,"class":"Price","method":"price","mutator":"MathMutator","description":"changed addition","killing_test":"PriceTest"}]}"#).unwrap();
        println!("{{\"protocol\":1,\"success\":true,\"tests\":[]}}");
    } else {
        println!("{{\"protocol\":1,\"success\":true,\"tests\":[{{\"name\":\"ordinary_unit\",\"status\":\"passed\"}}]}}");
    }
}
"##).unwrap();
    let compiled = Command::new("rustc")
        .arg(&source)
        .arg("-o")
        .arg(&binary)
        .output()
        .unwrap();
    assert!(
        compiled.status.success(),
        "{}",
        String::from_utf8_lossy(&compiled.stderr)
    );
    std::fs::write(root.path().join("pom.xml"), "<project><modelVersion>4.0.0</modelVersion><groupId>demo</groupId><artifactId>fixture</artifactId><version>1</version></project>").unwrap();
    std::fs::write(root.path().join("cherry-test.yaml"), format!("defaults:\n  coverage: false\n  strict: true\nadapters:\n  fixture-unit:\n    command: {binary:?}\n    args: [unit]\n  fixture-mutation:\n    command: {binary:?}\n    args: [mutation]\nprojects:\n  demo:\n    path: .\n    packages:\n      fixture:\n        adapter: maven\n        manifest: pom.xml\n        kinds: [unit]\n        unit:\n          adapter: fixture-unit\n        mutation:\n          adapter: fixture-mutation\n          options: {{mutation_tool: pitest}}\n")).unwrap();
    for workers in ["1", "2"] {
        let result = Command::new(env!("CARGO_BIN_EXE_cherry-test"))
            .args([
                "--root",
                root.path().to_str().unwrap(),
                "--jobs",
                workers,
                "--no-open",
                "--no-crap",
                "--quiet",
            ])
            .output()
            .unwrap();
        assert!(
            result.status.success(),
            "stdout: {}\nstderr: {}",
            String::from_utf8_lossy(&result.stdout),
            String::from_utf8_lossy(&result.stderr)
        );
        let reports = root.path().join("target/cherry-test/latest/reports");
        let ordinary = std::fs::read_to_string(reports.join("integration/fixture.jsonl")).unwrap();
        assert_eq!(ordinary.lines().count(), 1);
        assert!(ordinary.contains("ordinary_integration"));
        assert!(!ordinary.contains("mutation_jsonl_failure"));
        let mutation = reports.join("adapters/demo/fixture/mutation");
        assert!(
            std::fs::read_to_string(mutation.join("report/integration/fixture.jsonl"))
                .unwrap()
                .contains("mutation_jsonl_failure")
        );
        let sidecar: serde_json::Value =
            serde_json::from_slice(&std::fs::read(mutation.join("mutation.json")).unwrap())
                .unwrap();
        assert_eq!(sidecar["mutants"][0]["status"], "KILLED");
        let html = std::fs::read_to_string(reports.join("index.html")).unwrap();
        assert!(!html.contains("mutation_jsonl_failure"));
        assert!(!html.contains("999999999"));
    }
}
