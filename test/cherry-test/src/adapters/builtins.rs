use std::path::PathBuf;

use anyhow::{bail, Context, Result};
use cherry_test_common::adapter::{AdapterRequest, AdapterResponse, ExecutionMode};

use super::{formats, process};

pub const NAMES: &[&str] = &[
    "cargo",
    "vitest",
    "maven",
    "gradle",
    "cmake",
    "playwright",
    "command",
];

pub fn run(name: &str, input: &AdapterRequest) -> Result<AdapterResponse> {
    let mut request = input.clone();
    std::fs::create_dir_all(&request.output)?;
    request
        .env
        .insert("CHERRY_TEST_PHASE".into(), request.phase.clone());
    request.env.insert(
        "CHERRY_TEST_ADAPTER_OUTPUT".into(),
        request.output.to_string_lossy().into(),
    );
    match name {
        "cargo" => cargo(&mut request),
        "vitest" => node(&mut request, false),
        "playwright" => node(&mut request, true),
        "maven" | "gradle" => jvm(&mut request, name),
        "cmake" => native(&mut request),
        "command" => command(&mut request),
        _ => bail!("unknown bundled adapter {name}"),
    }
}

fn cargo(request: &mut AdapterRequest) -> Result<AdapterResponse> {
    let mut args = Vec::new();
    let coverage = request.coverage
        && (request.execution == cherry_test_common::adapter::ExecutionMode::Container
            || crate::coverage::llvm_cov_available());
    if request.coverage && !coverage && request.strict {
        bail!("cargo-llvm-cov is required for coverage");
    }
    let lcov = request.output.join("coverage.lcov");
    if coverage {
        args.push("llvm-cov".into());
    }
    args.extend(process::strings(&["test", "--manifest-path"]));
    args.push(request.manifest.to_string_lossy().into());
    args.extend(process::strings(&["--package", &request.package]));
    if coverage {
        args.extend(process::strings(&["--lcov", "--output-path"]));
        args.push(lcov.to_string_lossy().into());
    }
    match request.phase.as_str() {
        "unit" => args.push("--lib".into()),
        "integration" => args.extend(process::strings(&["--test", "*"])),
        _ => bail!("Cargo adapter supports unit and integration phases"),
    }
    args.push("--".into());
    if request.phase == "integration" {
        args.extend(process::strings(&["--ignored", "--test-threads=1"]));
    }
    args.extend(request.args.clone());
    args.extend(process::strings(&[
        "--format", "pretty", "--color", "never",
    ]));
    request.env.insert(
        "CARGO_TARGET_DIR".into(),
        cargo_target_dir(request).to_string_lossy().into(),
    );
    let success = process::run(request, "cargo", &args, "tests")?;
    let output = std::fs::read_to_string(request.output.join("tests.stdout"))?;
    let mut result = AdapterResponse {
        success,
        tests: formats::rust_tests(&output),
        ..Default::default()
    };
    if lcov.is_file() {
        result.coverage.push(lcov);
    }
    if request.coverage && !coverage {
        result
            .diagnostics
            .push("cargo-llvm-cov unavailable; coverage was not measured".into());
    }
    Ok(result)
}

pub fn cargo_target_dir(request: &AdapterRequest) -> PathBuf {
    let execution = match request.execution {
        ExecutionMode::Local => "local",
        ExecutionMode::Container => "container",
    };
    request
        .root
        .join("target")
        .join("cherry-test")
        .join("cargo")
        .join(execution)
}

fn node(request: &mut AdapterRequest, playwright: bool) -> Result<AdapterResponse> {
    let report = request.output.join("results.json");
    let (script, mut args) = if playwright {
        request.env.insert(
            "PLAYWRIGHT_JSON_OUTPUT_FILE".into(),
            report.to_string_lossy().into(),
        );
        (
            "node_modules/@playwright/test/cli.js",
            crate::node_runtime::playwright_run_args(&request.args),
        )
    } else {
        request
            .env
            .insert("VITEST_JSON_REPORT".into(), report.to_string_lossy().into());
        if request.coverage {
            request.env.insert(
                "VITEST_COVERAGE_DIR".into(),
                request.output.join("coverage").to_string_lossy().into(),
            );
        }
        (
            "node_modules/vitest/vitest.mjs",
            crate::node_runtime::vitest_run_args(&request.args),
        )
    };
    if args.is_empty() {
        args.push(if playwright { "test" } else { "run" }.into());
    }
    if playwright {
        args.extend(process::strings(&["--reporter=json", "--output"]));
        args.push(request.output.join("attachments").to_string_lossy().into());
    }
    if !playwright && request.coverage && !args.iter().any(|a| a == "--coverage") {
        args.push("--coverage".into());
    }
    if !request.coverage {
        args.retain(|arg| arg != "--coverage");
    }
    args.insert(0, request.cwd.join(script).to_string_lossy().into());
    let program = if request.execution == cherry_test_common::adapter::ExecutionMode::Local {
        request.env.insert(
            "PATH".into(),
            crate::node_runtime::augmented_path(&request.cwd),
        );
        crate::node_runtime::resolve_node_binary(&request.cwd).context("Node.js is required")?
    } else {
        PathBuf::from("node")
    };
    let success = process::run(request, &program.to_string_lossy(), &args, "tests")?;
    let content =
        std::fs::read_to_string(&report).context("runner did not write its JSON report")?;
    let mut tests = if playwright {
        formats::playwright(&content)?
    } else {
        formats::vitest(&content)?
    };
    if playwright {
        let imported = request.output.join("imported-attachments");
        for (test_index, test) in tests.iter_mut().enumerate() {
            for (index, attachment) in test.attachments.iter_mut().enumerate() {
                let path =
                    source_path(request, &attachment.path.to_string_lossy()).canonicalize()?;
                if !path.starts_with(request.output.canonicalize()?) {
                    if !path.starts_with(request.root.canonicalize()?) {
                        bail!("Playwright attachment escapes workspace");
                    }
                    std::fs::create_dir_all(&imported)?;
                    let name = path
                        .file_name()
                        .context("attachment filename")?
                        .to_string_lossy();
                    let copy = imported.join(format!("{test_index}-{index}-{name}"));
                    std::fs::copy(path, &copy)?;
                    attachment.path = copy;
                }
            }
        }
    }
    let coverage = formats::files(&request.output.join("coverage"), "info")?;
    Ok(AdapterResponse {
        success,
        tests,
        coverage,
        ..Default::default()
    })
}

fn jvm(request: &mut AdapterRequest, adapter: &str) -> Result<AdapterResponse> {
    let build = request.output.join("build");
    let task = match request.phase.as_str() {
        "unit" => "test",
        "integration" => "integrationTest",
        _ => bail!("JVM adapters support unit and integration phases"),
    };
    let (program, mut args) = if adapter == "maven" {
        let program = if request.cwd.join("mvnw").exists() {
            "./mvnw"
        } else {
            "mvn"
        };
        let phase = if task == "test" { "test" } else { "verify" };
        let mut args = process::strings(&["-B", "-ntp", phase]);
        args.push(format!("-Dtestit.buildDirectory={}", build.display()));
        args.push(format!("-Dtestit.phase={}", request.phase));
        if task != "test" {
            args.push("-DskipUnitTests=true".into());
        }
        if request.coverage {
            args.push("jacoco:report".into());
        }
        (program, args)
    } else {
        let program = if request.cwd.join("gradlew").exists() {
            "./gradlew"
        } else {
            "gradle"
        };
        let mut args = process::strings(&["--no-daemon", "--console=plain", task]);
        args.push(format!("-PtestItBuildDir={}", build.display()));
        if request.coverage {
            args.push(
                if task == "test" {
                    "jacocoTestReport"
                } else {
                    "jacocoIntegrationTestReport"
                }
                .into(),
            );
        }
        (program, args)
    };
    args.extend(request.args.clone());
    let success = process::run(request, program, &args, "tests")?;
    let report_path = if adapter == "maven" {
        build.join(if task == "test" {
            "surefire-reports"
        } else {
            "failsafe-reports"
        })
    } else {
        build.join("test-results").join(task)
    };
    let mut tests = Vec::new();
    for file in formats::files(&report_path, "xml")? {
        if file.file_name().and_then(|v| v.to_str()) == Some("failsafe-summary.xml") {
            continue;
        }
        tests.extend(formats::junit(&std::fs::read_to_string(file)?)?);
    }
    let mut result = AdapterResponse {
        success,
        tests,
        ..Default::default()
    };
    if request.coverage {
        let roots = [
            request.cwd.join("src/main/java"),
            request.cwd.join("src/main/kotlin"),
        ];
        for file in formats::files(&build, "xml")?.into_iter().filter(|p| {
            p.file_name()
                .and_then(|n| n.to_str())
                .is_some_and(|n| n.starts_with("jacoco"))
        }) {
            let lcov = request
                .output
                .join(format!("{}.lcov", result.coverage.len()));
            std::fs::write(
                &lcov,
                formats::jacoco(&std::fs::read_to_string(file)?, &roots)?,
            )?;
            result.coverage.push(lcov);
        }
    }
    Ok(result)
}

fn native(request: &mut AdapterRequest) -> Result<AdapterResponse> {
    request.env.insert(
        "LLVM_PROFILE_FILE".into(),
        request
            .output
            .join("native-%p.profraw")
            .to_string_lossy()
            .into(),
    );
    let build = request.output.join("build");
    let mut args = process::strings(&[
        "-S",
        &request.cwd.to_string_lossy(),
        "-B",
        &build.to_string_lossy(),
        "-DCMAKE_BUILD_TYPE=Debug",
        "-DCMAKE_C_COMPILER=clang",
        "-DCMAKE_CXX_COMPILER=clang++",
    ]);
    args.push(format!(
        "-DCHERRY_TEST_COVERAGE={}",
        if request.coverage { "ON" } else { "OFF" }
    ));
    if !process::run(request, "cmake", &args, "configure")? {
        bail!("CMake configuration failed");
    }
    if !process::run(
        request,
        "cmake",
        &process::strings(&["--build", &build.to_string_lossy(), "--parallel", "2"]),
        "build",
    )? {
        bail!("native build failed");
    }
    request.env.insert(
        "LLVM_PROFILE_FILE".into(),
        request
            .output
            .join("native-%p.profraw")
            .to_string_lossy()
            .into(),
    );
    let junit = request.output.join("results.xml");
    let mut args = process::strings(&[
        "--test-dir",
        &build.to_string_lossy(),
        "--output-on-failure",
        "--no-tests=error",
        "-L",
        &format!("^{}$", request.phase),
        "--output-junit",
        &junit.to_string_lossy(),
    ]);
    args.extend(request.args.clone());
    let success = process::run(request, "ctest", &args, "tests")?;
    let tests = formats::junit(&std::fs::read_to_string(junit).context("CTest results missing")?)?;
    let mut result = AdapterResponse {
        success,
        tests,
        ..Default::default()
    };
    if request.coverage {
        let profiles = formats::files(&request.output, "profraw")?;
        if profiles.is_empty() {
            bail!("native coverage profiles missing");
        }
        let profile = request.output.join("coverage.profdata");
        let mut args = process::strings(&["merge", "-sparse", "-o", &profile.to_string_lossy()]);
        args.extend(profiles.iter().map(|p| p.to_string_lossy().into_owned()));
        if !process::run(request, "llvm-profdata", &args, "profile")? {
            bail!("LLVM profile merge failed");
        }
        let binary = request
            .options
            .get("coverage_binary")
            .and_then(|v| v.as_str())
            .unwrap_or("native-tests");
        let args = process::strings(&[
            "export",
            &build.join(binary).to_string_lossy(),
            &format!("-instr-profile={}", profile.display()),
            "-format=lcov",
            "-ignore-filename-regex=(_deps|/usr/|tests/)",
        ]);
        if !process::run(request, "llvm-cov", &args, "coverage")? {
            bail!("LLVM coverage export failed");
        }
        let lcov = request.output.join("coverage.lcov");
        std::fs::copy(request.output.join("coverage.stdout"), &lcov)?;
        result.coverage.push(lcov);
    }
    Ok(result)
}

fn command(request: &mut AdapterRequest) -> Result<AdapterResponse> {
    let program = request
        .options
        .get("command")
        .and_then(|v| v.as_str())
        .context("command adapter requires options.command")?
        .to_string();
    let success = process::run(request, &program, &request.args, "tests")?;
    let result_path = request.output.join("result.json");
    if !result_path.is_file() && !request.strict {
        return Ok(AdapterResponse {
            success,
            tests: vec![formats::case(
                format!("command: {program}"),
                if success {
                    cherry_test_common::adapter::TestStatus::Passed
                } else {
                    cherry_test_common::adapter::TestStatus::Failed
                },
                None,
                0,
            )],
            diagnostics: vec![
                "No individual test report; result uses the external command exit status".into(),
            ],
            ..Default::default()
        });
    }
    let mut result: AdapterResponse = serde_json::from_slice(
        &std::fs::read(result_path)
            .context("command must write CHERRY_TEST_ADAPTER_OUTPUT/result.json")?,
    )?;
    result.success &= success;
    Ok(result)
}

pub fn source_path(request: &AdapterRequest, path: &str) -> PathBuf {
    let translated = path
        .strip_prefix("/workspace/")
        .map(|p| process::mount_root(request).join(p))
        .unwrap_or_else(|| PathBuf::from(path));
    if translated.is_absolute() {
        translated
    } else {
        request.cwd.join(translated)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::Path;

    fn request(root: &Path, execution: ExecutionMode) -> AdapterRequest {
        AdapterRequest {
            protocol: 1,
            project: "mini".into(),
            package: "mini-rs-api".into(),
            phase: "unit".into(),
            root: root.into(),
            manifest: root.join("mini-rs-api/Cargo.toml"),
            cwd: root.into(),
            output: root.join("target/cherry-test/runs/20261014-000000-abcdef/reports/adapters/mini/mini-rs-api/unit"),
            execution,
            coverage: true,
            strict: false,
            args: Vec::new(),
            env: Default::default(),
            options: Default::default(),
        }
    }

    mod cargo_target_dir {
        use super::*;

        #[test]
        fn is_shared_by_runs_packages_and_phases() {
            let mut integration = request(Path::new("/work/mini"), ExecutionMode::Local);
            integration.package = "mini-rs-api-okta".into();
            integration.phase = "integration".into();
            assert_eq!(
                cargo_target_dir(&request(Path::new("/work/mini"), ExecutionMode::Local)),
                cargo_target_dir(&integration)
            );
        }

        #[test]
        fn keeps_host_and_container_builds_apart() {
            assert_eq!(
                cargo_target_dir(&request(Path::new("/work/mini"), ExecutionMode::Local)),
                Path::new("/work/mini/target/cherry-test/cargo/local")
            );
            assert_eq!(
                cargo_target_dir(&request(Path::new("/work/mini"), ExecutionMode::Container)),
                Path::new("/work/mini/target/cherry-test/cargo/container")
            );
        }
    }
}
