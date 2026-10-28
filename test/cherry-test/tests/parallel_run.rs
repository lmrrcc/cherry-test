use std::path::{Path, PathBuf};
use std::process::Command;

const ADAPTER: &str = r##"
use std::io::Read;

fn field(request: &str, name: &str) -> String {
    let marker = format!("\"{name}\":\"");
    match request.find(&marker) {
        Some(at) => request[at + marker.len()..].split('"').next().unwrap_or("").to_string(),
        None => String::new(),
    }
}

fn main() {
    let mut request = String::new();
    std::io::stdin().read_to_string(&mut request).unwrap();
    let package = field(&request, "package");
    let phase = field(&request, "phase");
    let weight: u64 = package.rsplit('-').next().and_then(|n| n.parse().ok()).unwrap_or(1);
    std::thread::sleep(std::time::Duration::from_millis(weight * 40));
    let evidence = std::path::Path::new(&std::env::var("CHERRY_TEST_REPORT_DIR").unwrap()).join("evidence");
    std::fs::create_dir_all(&evidence).unwrap();
    std::fs::write(evidence.join(format!("{package}-{phase}.txt")), &package).unwrap();
    use std::io::Write;
    let mut shared = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(evidence.join("shared.jsonl"))
        .unwrap();
    writeln!(shared, "{{\"job\":\"{package}/{phase}\"}}").unwrap();
    let failed = package == "b-fail-2" && phase == "unit";
    let status = if failed { "failed" } else { "passed" };
    println!(
        "{{\"protocol\":1,\"success\":{},\"tests\":[{{\"name\":\"{package}_{phase}\",\"status\":\"{status}\",\"duration_ms\":1}}]}}",
        !failed
    );
}
"##;

const PACKAGES: &[(&str, &str, &str, &str)] = &[
    ("a", "a-one-3", "[unit, integration]", ""),
    ("a", "a-two-1", "[unit]", ""),
    ("b", "b-fail-2", "[unit, functional]", ""),
    (
        "b",
        "b-four-4",
        "[unit, integration]",
        "        exclusive: true\n        resources: {cpus: 2, memory: 2g}\n",
    ),
    ("b", "b-five-1", "[unit, functional]", ""),
];

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
        "defaults:\n  coverage: false\n  open_report: false\nadapters:\n  fixture:\n    command: \"{}\"\n    args: []\n    timeout_secs: 60\nprojects:\n",
        binary.display()
    );
    for project in ["a", "b"] {
        yaml.push_str(&format!(
            "  {project}:\n    path: {project}\n    packages:\n"
        ));
        for (owner, name, kinds, extra) in PACKAGES {
            if *owner != project {
                continue;
            }
            std::fs::create_dir_all(root.join(project).join(name)).unwrap();
            std::fs::write(root.join(project).join(name).join("package.json"), "{}").unwrap();
            yaml.push_str(&format!(
                "      {name}:\n        adapter: fixture\n        manifest: {project}/{name}/package.json\n        kinds: {kinds}\n        functional: {{adapter: fixture}}\n{extra}"
            ));
        }
    }
    std::fs::write(root.join("cherry-test.yaml"), yaml).unwrap();
}

fn run(root: &Path, jobs: &str) -> (i32, PathBuf, String) {
    let output = Command::new(env!("CARGO_BIN_EXE_cherry-test"))
        .args([
            "--root",
            root.to_str().unwrap(),
            "--no-open",
            "--no-banner",
            "--jobs",
            jobs,
        ])
        .output()
        .unwrap();
    let stdout = String::from_utf8_lossy(&output.stdout).into_owned();
    let run_id = stdout
        .lines()
        .find_map(|line| line.strip_prefix("cherry-test run: "))
        .and_then(|rest| rest.split_whitespace().next())
        .unwrap()
        .to_string();
    let runs = root.join("target/cherry-test/runs");
    (output.status.code().unwrap(), runs.join(&run_id), run_id)
}

fn files(dir: &Path, base: &Path, run_id: &str, out: &mut Vec<(String, String)>) {
    let mut entries: Vec<_> = std::fs::read_dir(dir)
        .unwrap()
        .map(|entry| entry.unwrap().path())
        .collect();
    entries.sort();
    for path in entries {
        if path.is_dir() {
            files(&path, base, run_id, out);
        } else {
            let relative = path.strip_prefix(base).unwrap().display().to_string();
            let text = std::fs::read_to_string(&path).unwrap_or_default();
            out.push((relative, text.replace(run_id, "RUN")));
        }
    }
}

#[test]
fn jobs_one_and_four_produce_the_same_results() {
    let dir = tempfile::tempdir().unwrap();
    let root = dir.path().canonicalize().unwrap();
    workspace(&root);
    let (sequential_exit, sequential_dir, sequential_id) = run(&root, "1");
    let (parallel_exit, parallel_dir, parallel_id) = run(&root, "4");
    assert_eq!(sequential_exit, 1);
    assert_eq!(parallel_exit, sequential_exit);
    let mut sequential = Vec::new();
    files(
        &sequential_dir,
        &sequential_dir,
        &sequential_id,
        &mut sequential,
    );
    let mut parallel = Vec::new();
    files(&parallel_dir, &parallel_dir, &parallel_id, &mut parallel);
    assert!(sequential
        .iter()
        .any(|(path, _)| path == "reports/index.html"));
    assert_eq!(
        sequential
            .iter()
            .filter(|(path, _)| path.ends_with("result.json"))
            .count(),
        9
    );
    assert_eq!(sequential, parallel);
    let shared = sequential
        .iter()
        .find(|(path, _)| path == "reports/evidence/shared.jsonl")
        .map(|(_, text)| text.clone())
        .unwrap();
    let jobs: Vec<&str> = shared
        .lines()
        .map(|line| {
            line.trim_start_matches("{\"job\":\"")
                .trim_end_matches("\"}")
        })
        .collect();
    assert_eq!(
        jobs,
        [
            "a-one-3/unit",
            "a-two-1/unit",
            "b-fail-2/unit",
            "b-five-1/unit",
            "b-four-4/unit",
            "a-one-3/integration",
            "b-four-4/integration",
            "b-fail-2/functional",
            "b-five-1/functional"
        ]
    );
    assert!(sequential
        .iter()
        .any(|(path, _)| path == "reports/evidence/b-four-4-integration.txt"));
    assert!(sequential
        .iter()
        .all(|(path, _)| !path.contains("/report/")));
    let request = sequential
        .iter()
        .find(|(path, _)| path == "reports/adapters/a/a-one-3/unit/request.json")
        .map(|(_, text)| text.clone())
        .unwrap();
    assert!(
        request.contains("reports/adapters/a/a-one-3/unit/report\""),
        "{request}"
    );
    let durations =
        std::fs::read_to_string(root.join("target/cherry-test/durations.json")).unwrap();
    for key in [
        "a/a-one-3/integration",
        "b/b-four-4/unit",
        "b/b-fail-2/functional",
    ] {
        assert!(durations.contains(&format!("\"{key}\"")), "{durations}");
    }
    assert!(durations.contains("\"runs\": 2"), "{durations}");
}
