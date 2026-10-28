use std::path::{Path, PathBuf};
use std::process::Command;

const ADAPTER: &str = r##"
use std::io::Read;
use std::path::Path;

fn field(request: &str, name: &str) -> String {
    let marker = format!("\"{name}\":\"");
    match request.find(&marker) {
        Some(at) => request[at + marker.len()..].split('"').next().unwrap_or("").to_string(),
        None => String::new(),
    }
}

fn selected(request: &str) -> Option<Vec<String>> {
    let at = request.find("\"select\":[")? + "\"select\":[".len();
    let list = request[at..].split(']').next().unwrap_or("");
    Some(list.split(',').map(|name| name.trim().trim_matches('"').to_string()).collect())
}

fn main() {
    let mut request = String::new();
    std::io::stdin().read_to_string(&mut request).unwrap();
    let package = field(&request, "package");
    let output = field(&request, "output");
    let selected = selected(&request);
    let wants = |name: &str| selected.as_ref().map_or(true, |names| names.iter().any(|n| n == name));
    std::fs::create_dir_all(&output).unwrap();
    let marker = Path::new(&output).join("shared-marker");
    let _ = std::fs::remove_file(&marker);
    let mut tests: Vec<(&str, bool)> = Vec::new();
    if package == "order" {
        if wants("a_writes_marker") {
            std::fs::write(&marker, "a ran first").unwrap();
            tests.push(("a_writes_marker", true));
        }
        if wants("b_needs_a_clean_slate") {
            tests.push(("b_needs_a_clean_slate", !marker.exists()));
        }
        if wants("c_always_fails") {
            tests.push(("c_always_fails", false));
        }
        if wants("d_alternates") {
            let counter = Path::new(&std::env::var("CHERRY_TEST_RUN_DIR").unwrap()).join("d-counter");
            let count: u32 = std::fs::read_to_string(&counter).ok().and_then(|text| text.trim().parse().ok()).unwrap_or(0) + 1;
            std::fs::write(&counter, count.to_string()).unwrap();
            tests.push(("d_alternates", count % 2 == 0));
        }
    } else {
        tests.push(("e_fails_without_isolate", false));
    }
    let results: Vec<String> = tests
        .iter()
        .map(|(name, passed)| format!("{{\"name\":\"{name}\",\"status\":\"{}\",\"duration_ms\":1}}", if *passed { "passed" } else { "failed" }))
        .collect();
    println!(
        "{{\"protocol\":1,\"success\":{},\"tests\":[{}]}}",
        tests.iter().all(|(_, passed)| *passed),
        results.join(",")
    );
}
"##;

fn workspace(root: &Path) {
    let source = root.join("adapter.rs");
    std::fs::write(&source, ADAPTER).unwrap();
    let binary = root.join("adapter-bin");
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
    let mut yaml = format!(
        "defaults:\n  coverage: false\n  open_report: false\nadapters:\n  fixture:\n    command: \"{0}\"\n    args: []\n    timeout_secs: 60\n    isolate: true\n  plain:\n    command: \"{0}\"\n    args: []\n    timeout_secs: 60\nprojects:\n  a:\n    path: a\n    packages:\n",
        binary.display()
    );
    for (name, adapter) in [("order", "fixture"), ("plain", "plain")] {
        std::fs::create_dir_all(root.join("a").join(name)).unwrap();
        std::fs::write(root.join("a").join(name).join("package.json"), "{}").unwrap();
        yaml.push_str(&format!(
            "      {name}:\n        adapter: {adapter}\n        manifest: a/{name}/package.json\n        kinds: [unit]\n"
        ));
    }
    std::fs::write(root.join("cherry-test.yaml"), yaml).unwrap();
}

fn run(root: &Path, flag: Option<&str>) -> (i32, String, PathBuf) {
    let mut command = Command::new(env!("CARGO_BIN_EXE_cherry-test"));
    command.args(["--root", root.to_str().unwrap(), "--no-open", "--no-banner"]);
    command.args(flag);
    let output = command.output().unwrap();
    let stdout = String::from_utf8_lossy(&output.stdout).into_owned();
    let run_id = stdout
        .lines()
        .find_map(|line| line.strip_prefix("cherry-test run: "))
        .and_then(|rest| rest.split_whitespace().next())
        .unwrap()
        .to_string();
    (
        output.status.code().unwrap(),
        stdout,
        root.join("target/cherry-test/runs").join(run_id),
    )
}

fn statuses(run: &Path, package: &str) -> Vec<(String, String)> {
    let path = run.join(format!("reports/adapters/a/{package}/unit/result.json"));
    let document: serde_json::Value =
        serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap();
    document["result"]["tests"]
        .as_array()
        .unwrap()
        .iter()
        .map(|test| {
            (
                test["name"].as_str().unwrap().to_string(),
                test["status"].as_str().unwrap().to_string(),
            )
        })
        .collect()
}

#[test]
fn isolation_classifies_interference_real_and_flaky_failures() {
    let dir = tempfile::tempdir().unwrap();
    let root = dir.path().canonicalize().unwrap();
    workspace(&root);

    let (plain_exit, plain_stdout, plain_run) = run(&root, None);
    assert_eq!(plain_exit, 1);
    assert!(!plain_stdout.contains("==> isolate"));
    assert!(!plain_run.join("reports/isolation.json").exists());
    assert!(!plain_run.join("reports/isolation").exists());

    let (exit, stdout, isolated_run) = run(&root, Some("--isolate"));
    assert_eq!(exit, plain_exit, "{stdout}");
    for package in ["order", "plain"] {
        assert_eq!(
            statuses(&isolated_run, package),
            statuses(&plain_run, package)
        );
    }
    assert_eq!(
        statuses(&isolated_run, "order"),
        [
            ("a_writes_marker".to_string(), "passed".to_string()),
            ("b_needs_a_clean_slate".to_string(), "failed".to_string()),
            ("c_always_fails".to_string(), "failed".to_string()),
            ("d_alternates".to_string(), "failed".to_string()),
        ]
    );
    for line in [
        "==> isolate 4 failed tests: each alone, 3 runs",
        "  INTERFERENCE  a/order/unit  b_needs_a_clean_slate  3/3 passed alone",
        "  REAL          a/order/unit  c_always_fails  0/3 passed alone",
        "  FLAKY         a/order/unit  d_alternates  2/3 passed alone",
        "  INCONCLUSIVE  a/plain/unit  e_fails_without_isolate  adapter `plain` does not declare `isolate: true`",
    ] {
        assert!(stdout.lines().any(|out| out == line), "{line}\n{stdout}");
    }

    let report: serde_json::Value = serde_json::from_str(
        &std::fs::read_to_string(isolated_run.join("reports/isolation.json")).unwrap(),
    )
    .unwrap();
    assert_eq!(report["failed"], 4);
    let classes: Vec<(&str, &str, u64)> = report["tests"]
        .as_array()
        .unwrap()
        .iter()
        .map(|test| {
            (
                test["test"].as_str().unwrap(),
                test["class"].as_str().unwrap(),
                test["passed"].as_u64().unwrap(),
            )
        })
        .collect();
    assert_eq!(
        classes,
        [
            ("b_needs_a_clean_slate", "INTERFERENCE", 3),
            ("c_always_fails", "REAL", 0),
            ("d_alternates", "FLAKY", 2),
            ("e_fails_without_isolate", "INCONCLUSIVE", 0),
        ]
    );
    let first_rerun = isolated_run.join("reports/isolation/a/order/unit/1/1");
    let request = std::fs::read_to_string(first_rerun.join("request.json")).unwrap();
    assert!(
        request.contains("\"select\":[\"b_needs_a_clean_slate\"]"),
        "{request}"
    );
    assert!(request.contains("\"coverage\":false"));
    assert!(first_rerun.join("result.json").is_file());

    let result =
        std::fs::read_to_string(isolated_run.join("reports/adapters/a/order/unit/result.json"))
            .unwrap();
    assert!(result.contains("\"class\": \"INTERFERENCE\""), "{result}");
    assert_eq!(result.matches("\"isolation\"").count(), 3);

    let index = std::fs::read_to_string(isolated_run.join("reports/index.html")).unwrap();
    assert!(index.contains(
        "<a href=\"phases.html#isolation\">Failed tests rerun alone: 1 REAL, 1 INTERFERENCE, 1 FLAKY, 1 INCONCLUSIVE</a>"
    ));
    let phases = std::fs::read_to_string(isolated_run.join("reports/phases.html")).unwrap();
    assert!(phases.contains("<section id=\"isolation\">"));
    assert!(phases.contains("<strong class=\"isolation neutral\">INTERFERENCE</strong>"));
    let rows = walk(&isolated_run.join("reports/adapters"))
        .into_iter()
        .filter(|path| path.ends_with("result.json"))
        .count();
    assert_eq!(rows, 2);
}

fn walk(dir: &Path) -> Vec<PathBuf> {
    let mut found = Vec::new();
    for entry in std::fs::read_dir(dir).unwrap() {
        let path = entry.unwrap().path();
        if path.is_dir() {
            found.extend(walk(&path));
        } else {
            found.push(path);
        }
    }
    found
}
