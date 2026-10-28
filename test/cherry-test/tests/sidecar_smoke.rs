use std::path::Path;
use std::process::Command;

const CLIENT: &str = "pg_isready -h \"$CHERRY_TEST_SMOKE_DB_HOST\" -p \"$CHERRY_TEST_SMOKE_DB_PORT\" && PGPASSWORD=smoke psql -h 127.0.0.1 -U postgres -v ON_ERROR_STOP=1 -qc 'create table only_mine (id int)' && test \"$(PGPASSWORD=smoke psql -h 127.0.0.1 -U postgres -tAc 'select count(*) from pg_tables where tablename = $$only_mine$$')\" = 1";

fn workspace(root: &Path) {
    std::fs::create_dir_all(root.join(".git")).unwrap();
    let mut yaml = String::from(
        "defaults:\n  coverage: false\n  open_report: false\n  execution: container\n  container:\n    image: postgres:16-alpine\n    timeout: 180s\nprojects:\n  smoke:\n    path: .\n    services:\n      db:\n        image: postgres:16-alpine\n        port: 5432\n        kind: postgres\n        env: {POSTGRES_PASSWORD: smoke}\n        tmpfs: [/var/lib/postgresql/data]\n        health:\n          exec: [pg_isready, -h, 127.0.0.1, -p, \"5432\"]\n          timeout: 120s\n    packages:\n",
    );
    for name in ["one", "two"] {
        std::fs::create_dir_all(root.join(name)).unwrap();
        std::fs::write(root.join(name).join("package.json"), "{}").unwrap();
        yaml.push_str(&format!(
            "      {name}:\n        manifest: {name}/package.json\n        kinds: [integration]\n        integration:\n          adapter: command\n          command: sh\n          scope: smoke\n          args: [-c, {}]\n",
            serde_json::to_string(CLIENT).unwrap()
        ));
    }
    std::fs::write(root.join("cherry-test.yaml"), yaml).unwrap();
}

fn labelled(run_id: &str) -> String {
    let output = Command::new("docker")
        .args([
            "ps",
            "-aq",
            "--filter",
            &format!("label=cherry-test.run={run_id}"),
        ])
        .output()
        .unwrap();
    assert!(output.status.success());
    String::from_utf8_lossy(&output.stdout).trim().to_string()
}

#[test]
#[ignore = "needs Docker and the local postgres:16-alpine image"]
fn smoke_parallel_jobs_get_their_own_postgres_on_the_same_port() {
    let dir = tempfile::tempdir().unwrap();
    let root = dir.path().canonicalize().unwrap();
    workspace(&root);
    let output = Command::new(env!("CARGO_BIN_EXE_cherry-test"))
        .args([
            "--root",
            root.to_str().unwrap(),
            "--no-open",
            "--no-banner",
            "--jobs",
            "2",
        ])
        .output()
        .unwrap();
    let stdout = String::from_utf8_lossy(&output.stdout).into_owned();
    let stderr = String::from_utf8_lossy(&output.stderr).into_owned();
    let run_id = stdout
        .lines()
        .find_map(|line| line.strip_prefix("cherry-test run: "))
        .and_then(|rest| rest.split_whitespace().next())
        .unwrap()
        .to_string();
    let adapters = root
        .join("target/cherry-test/runs")
        .join(&run_id)
        .join("reports/adapters/smoke");
    for name in ["one", "two"] {
        let job = adapters.join(name).join("integration");
        let result = std::fs::read_to_string(job.join("result.json")).unwrap();
        assert!(
            result.contains("\"status\": \"passed\""),
            "{name}: {result}\n{stdout}\n{stderr}\n{}",
            std::fs::read_to_string(job.join("tests.stderr")).unwrap_or_default()
        );
        let request = std::fs::read_to_string(job.join("request.json")).unwrap();
        assert!(request.contains("\"CHERRY_TEST_SCOPE_PROVISIONED\":\"smoke\""));
        assert!(request.contains("\"CHERRY_TEST_SMOKE_DB_PORT\":\"5432\""));
        assert!(request.contains(&format!(
            "\"holder\":\"ct-{run_id}-smoke-{name}-integration-net\""
        )));
        assert!(job.join("sidecar-smoke-db.log").is_file());
    }
    assert_eq!(output.status.code(), Some(0), "{stdout}\n{stderr}");
    assert_eq!(labelled(&run_id), "");
}
