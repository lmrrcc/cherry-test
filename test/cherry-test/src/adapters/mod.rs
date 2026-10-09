pub mod builtins;
pub mod formats;
pub mod lcov;
pub mod process;

use std::io::Read;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};

use anyhow::{bail, Context, Result};
use cherry_test_common::adapter::{
    AdapterConfig, AdapterRequest, AdapterResponse, TestStatus, PROTOCOL_VERSION,
};
use clap::Args;

#[derive(Debug, Args)]
pub struct AdapterCli {
    pub name: String,
    #[arg(default_value = "run")]
    pub operation: String,
}

pub fn cli(args: AdapterCli) -> Result<i32> {
    if !builtins::NAMES.contains(&args.name.as_str()) {
        bail!("unknown adapter {}", args.name);
    }
    let response = match args.operation.as_str() {
        "describe" => {
            serde_json::json!({"protocol": PROTOCOL_VERSION, "name": args.name, "operations": ["describe", "discover", "run"], "execution": ["local", "container"]})
        }
        "discover" => {
            let mut input = String::new();
            std::io::stdin()
                .take(4 * 1024 * 1024)
                .read_to_string(&mut input)?;
            let request: serde_json::Value = serde_json::from_str(&input)?;
            let root = request["root"].as_str().context("discovery root missing")?;
            let manifests = match args.name.as_str() {
                "cargo" => vec!["Cargo.toml"],
                "maven" => vec!["pom.xml"],
                "gradle" => vec!["build.gradle", "build.gradle.kts"],
                "cmake" => vec!["CMakeLists.txt"],
                _ => vec!["package.json"],
            };
            let mut found = Vec::new();
            discover(Path::new(root), Path::new(root), &manifests, 0, &mut found)?;
            serde_json::json!({"protocol": PROTOCOL_VERSION, "manifests": found})
        }
        "run" => {
            let mut input = String::new();
            std::io::stdin()
                .take(4 * 1024 * 1024)
                .read_to_string(&mut input)?;
            let request: AdapterRequest = serde_json::from_str(&input)?;
            if request.protocol != PROTOCOL_VERSION {
                bail!("unsupported adapter protocol");
            }
            let response = match builtins::run(&args.name, &request) {
                Ok(response) => response,
                Err(error) => AdapterResponse {
                    success: false,
                    diagnostics: vec![format!("{error:#}")],
                    ..Default::default()
                },
            };
            serde_json::to_value(response)?
        }
        _ => bail!("unsupported adapter operation"),
    };
    serde_json::to_writer(std::io::stdout().lock(), &response)?;
    Ok(0)
}

fn discover(
    root: &Path,
    dir: &Path,
    names: &[&str],
    depth: u8,
    found: &mut Vec<String>,
) -> Result<()> {
    if depth > 6 {
        return Ok(());
    }
    for name in names {
        if dir.join(name).is_file() {
            found.push(dir.join(name).strip_prefix(root)?.to_string_lossy().into());
        }
    }
    for entry in std::fs::read_dir(dir)? {
        let entry = entry?;
        let name = entry.file_name().to_string_lossy().into_owned();
        if entry.file_type()?.is_dir()
            && !name.starts_with('.')
            && !["target", "build", "node_modules", "dist"].contains(&name.as_str())
        {
            discover(root, &entry.path(), names, depth + 1, found)?;
        }
    }
    Ok(())
}

pub fn invoke(
    name: &str,
    config: Option<&AdapterConfig>,
    request: &AdapterRequest,
) -> Result<AdapterResponse> {
    std::fs::create_dir_all(&request.output)?;
    let mut command = if let Some(config) = config {
        let mut command = Command::new(&config.command);
        command.args(&config.args).arg("run");
        command
    } else {
        if !builtins::NAMES.contains(&name) {
            bail!("adapter `{name}` is not registered");
        }
        let mut command = Command::new(std::env::current_exe()?);
        command.args(["adapter", name, "run"]);
        command
    };
    let request_file = request.output.join("request.json");
    std::fs::write(&request_file, serde_json::to_vec(request)?)?;
    command
        .stdin(Stdio::from(std::fs::File::open(request_file)?))
        .current_dir(&request.root);
    let prefix = request.output.join("adapter");
    let success = process::capture(
        command,
        &prefix,
        config.map(|c| c.timeout_secs).unwrap_or(1800),
        true,
    )?;
    let stdout = prefix.with_extension("stdout");
    if std::fs::metadata(&stdout)?.len() > 32 * 1024 * 1024 {
        bail!("adapter response exceeds 32 MiB");
    }
    let data = std::fs::read(stdout)?;
    let mut response: AdapterResponse =
        serde_json::from_slice(&data).context("invalid adapter JSON response")?;
    response.success &= success;
    validate(request, &mut response)?;
    Ok(response)
}

pub fn validate(request: &AdapterRequest, response: &mut AdapterResponse) -> Result<()> {
    if response.protocol != PROTOCOL_VERSION {
        bail!("adapter protocol version mismatch");
    }
    let mut names = std::collections::BTreeSet::new();
    for test in &mut response.tests {
        if test.name.trim().is_empty() || !names.insert(test.name.clone()) {
            bail!("empty or duplicate adapter test name");
        }
        if test.status == TestStatus::Failed || request.strict && test.status == TestStatus::Skipped
        {
            response.success = false;
        }
        if let Some(source) = &test.source {
            let source = resolve_test_source(request, source)
                .unwrap_or_else(|| builtins::source_path(request, &source.to_string_lossy()));
            if source.exists()
                && !source
                    .canonicalize()?
                    .starts_with(request.root.canonicalize()?)
            {
                bail!("test source escapes workspace");
            }
            test.source = Some(source);
        }
        for attachment in &mut test.attachments {
            attachment.path = builtins::source_path(request, &attachment.path.to_string_lossy());
            confined(&request.output, &attachment.path)?;
        }
    }
    for path in &mut response.coverage {
        *path = builtins::source_path(request, &path.to_string_lossy());
        confined(&request.output, path)?;
        validate_lcov(&std::fs::read_to_string(&path)?)?;
    }
    if request.strict && response.tests.is_empty() {
        response.success = false;
        response
            .diagnostics
            .push("required phase produced zero tests".into());
    }
    if request.coverage && request.strict && response.coverage.is_empty() {
        response.success = false;
        response
            .diagnostics
            .push("required coverage artifact missing".into());
    }
    Ok(())
}

fn validate_lcov(content: &str) -> Result<()> {
    let mut active = false;
    let mut sources = 0;
    let mut lines = 0;
    for line in content.lines() {
        if let Some(source) = line.strip_prefix("SF:") {
            if active || source.trim().is_empty() {
                bail!("invalid LCOV source record");
            }
            active = true;
            sources += 1;
        } else if let Some(value) = line.strip_prefix("DA:") {
            if !active {
                bail!("LCOV line outside source record");
            }
            let mut values = value.split(',');
            let number: u64 = values.next().context("LCOV line missing")?.parse()?;
            let _: u64 = values.next().context("LCOV hit count missing")?.parse()?;
            if number == 0 {
                bail!("LCOV line numbers start at one");
            }
            lines += 1;
        } else if line == "end_of_record" {
            if !active {
                bail!("LCOV record has no source");
            }
            active = false;
        }
    }
    if active || sources == 0 || lines == 0 {
        bail!("coverage contains no complete measured source records");
    }
    Ok(())
}

fn confined(root: &Path, path: &Path) -> Result<()> {
    if !path.canonicalize()?.starts_with(root.canonicalize()?) {
        bail!("artifact escapes adapter output directory");
    }
    if !path.is_file() {
        bail!("artifact is not a file");
    }
    Ok(())
}

/// Finds the file a runner named for a test: relative to the package directory as before,
/// then relative to each directory above it up to the run root (Rust panics print workspace paths).
fn resolve_test_source(request: &AdapterRequest, source: &Path) -> Option<PathBuf> {
    let above_package = request
        .cwd
        .ancestors()
        .skip(1)
        .take_while(|dir| dir.starts_with(&request.root))
        .map(|dir| dir.join(source));
    std::iter::once(builtins::source_path(request, &source.to_string_lossy()))
        .chain(above_package)
        .find(|path| path.is_file())
}

pub fn save(
    request: &AdapterRequest,
    mut response: AdapterResponse,
    reports: &Path,
) -> Result<AdapterResponse> {
    if !response.success
        && !response
            .tests
            .iter()
            .any(|t| t.status == TestStatus::Failed)
    {
        response.tests.push(formats::case(
            "runner".into(),
            TestStatus::Failed,
            Some(response.diagnostics.join("\n")),
            0,
        ));
    }
    let dir = reports
        .join("adapters")
        .join(&request.project)
        .join(&request.package)
        .join(&request.phase);
    std::fs::create_dir_all(&dir)?;
    std::fs::write(
        dir.join("result.json"),
        serde_json::to_vec_pretty(
            &serde_json::json!({"project":request.project,"package":request.package,"phase":request.phase,"result":response}),
        )?,
    )?;
    Ok(response)
}

pub fn read_rows(reports: &Path) -> Result<Vec<serde_json::Value>> {
    let mut rows = Vec::new();
    for path in formats::files(&reports.join("adapters"), "json")? {
        if path.file_name().and_then(|name| name.to_str()) != Some("result.json") {
            continue;
        }
        let doc: serde_json::Value = serde_json::from_slice(&std::fs::read(path)?)?;
        let project = doc["project"].as_str().context("result project")?;
        let package = doc["package"].as_str().context("result package")?;
        let phase = doc["phase"].as_str().context("result phase")?;
        let result: AdapterResponse = serde_json::from_value(doc["result"].clone())?;
        for test in result.tests {
            let mut row = serde_json::json!({"project":project,"suite":phase,"test":format!("{package}: {}",test.name),"full_name":format!("{project}/{package}/{phase}/{}",test.name),"scope":"none","target_project":project,"target_service":package,"elapsed_ms":test.duration_ms,"status": match test.status { TestStatus::Passed=>"ok", TestStatus::Failed=>"FAIL", TestStatus::Skipped=>"skipped" },"reason":test.message,"source":test.source,"line":test.line});
            if phase == "integration" {
                if let Ok(id) = cherry_test_core::parse_test_name(
                    test.name.rsplit("::").next().unwrap_or(&test.name),
                ) {
                    row["project"] = id.project.into();
                    row["suite"] = id.suite.into();
                    row["test"] = id.test.into();
                    row["full_name"] = id.full_name.into();
                }
            }
            rows.push(row);
        }
    }
    Ok(rows)
}

#[cfg(test)]
mod tests {
    use super::*;
    use cherry_test_common::adapter::{Attachment, ExecutionMode};

    fn request(root: &Path) -> AdapterRequest {
        AdapterRequest {
            protocol: 1,
            project: "demo".into(),
            package: "prices".into(),
            phase: "unit".into(),
            root: root.into(),
            manifest: root.join("pom.xml"),
            cwd: root.into(),
            output: root.join("output"),
            execution: ExecutionMode::Local,
            coverage: true,
            strict: true,
            args: Vec::new(),
            env: Default::default(),
            options: Default::default(),
        }
    }

    mod test_source {
        use super::*;

        fn layout() -> (tempfile::TempDir, AdapterRequest) {
            let temp = tempfile::tempdir().unwrap();
            let file = temp.path().join("shop-rs-api/src/tests.rs");
            std::fs::create_dir_all(file.parent().unwrap()).unwrap();
            std::fs::write(&file, "fn t() {}\n").unwrap();
            let mut request = request(temp.path());
            request.cwd = temp.path().join("shop-rs-api");
            (temp, request)
        }

        #[test]
        fn resolves_a_path_relative_to_the_workspace() {
            let (temp, request) = layout();
            let resolved = resolve_test_source(&request, Path::new("shop-rs-api/src/tests.rs"));
            assert_eq!(resolved, Some(temp.path().join("shop-rs-api/src/tests.rs")));
        }

        #[test]
        fn resolves_a_path_relative_to_the_package() {
            let (temp, request) = layout();
            let resolved = resolve_test_source(&request, Path::new("src/tests.rs"));
            assert_eq!(resolved, Some(temp.path().join("shop-rs-api/src/tests.rs")));
        }

        #[test]
        fn drops_a_path_that_does_not_exist() {
            let (_temp, request) = layout();
            assert_eq!(
                resolve_test_source(&request, Path::new("src/missing.rs")),
                None
            );
        }
    }

    #[test]
    fn rejects_non_measurements_and_truncated_coverage() {
        assert!(validate_lcov("not a report").is_err());
        assert!(validate_lcov("SF:file.c\nDA:1,0\n").is_err());
        assert!(validate_lcov("SF:file.c\nDA:1,invalid\nend_of_record\n").is_err());
        assert!(validate_lcov("SF:file.c\nDA:1,0\nend_of_record\n").is_ok());
    }

    #[test]
    fn strict_mode_rejects_empty_tests_and_missing_coverage() {
        let temp = tempfile::tempdir().unwrap();
        let request = request(temp.path());
        let mut response = AdapterResponse::default();
        validate(&request, &mut response).unwrap();
        assert!(!response.success);
        assert_eq!(response.diagnostics.len(), 2);
    }

    #[test]
    fn failure_wins_over_claimed_success_and_duplicate_names_rejected() {
        let temp = tempfile::tempdir().unwrap();
        let request = request(temp.path());
        let failed = formats::case("broken".into(), TestStatus::Failed, None, 0);
        let mut response = AdapterResponse {
            tests: vec![failed.clone()],
            ..Default::default()
        };
        validate(&request, &mut response).unwrap();
        assert!(!response.success);
        response.tests.push(failed);
        assert!(validate(&request, &mut response).is_err());
    }

    #[test]
    fn attachment_cannot_escape_output() {
        let temp = tempfile::tempdir().unwrap();
        let request = request(temp.path());
        std::fs::create_dir(&request.output).unwrap();
        let outside = temp.path().join("secret.txt");
        std::fs::write(&outside, "private").unwrap();
        let mut test = formats::case("screen".into(), TestStatus::Passed, None, 0);
        test.attachments.push(Attachment {
            name: "image".into(),
            path: outside,
            media_type: "image/png".into(),
        });
        let mut response = AdapterResponse {
            tests: vec![test],
            ..Default::default()
        };
        assert!(validate(&request, &mut response).is_err());
    }
}
