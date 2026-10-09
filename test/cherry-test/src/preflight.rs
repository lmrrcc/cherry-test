use std::path::{Path, PathBuf};

use crate::discover::{ProjectGroup, WorkspaceMember};
use crate::node_discover;
use anyhow::Result;
use cherry_test_core::{
    effective_scope, needs_docker, parse_test_name, PackageRuntime, TestItConfig,
};

#[derive(Debug, Clone)]
pub struct DiscoveredTest {
    pub package: String,
    pub function: String,
    pub attr_scope: Option<String>,
    pub attr_auth: Option<String>,
    pub effective_scope: String,
    pub project: String,
    pub suite: String,
    pub test: String,
    pub source_path: PathBuf,
    pub fn_line: usize,
}

#[derive(Debug, Clone)]
pub struct SkippedTest {
    pub package: String,
    pub function: String,
    pub scope: String,
    pub project: String,
    pub suite: String,
    pub test: String,
    pub reason: String,
}

#[derive(Debug, Clone, Default)]
pub struct PreflightPlan {
    pub docker_available: bool,
    pub skip_tests: Vec<String>,
    pub skipped: Vec<SkippedTest>,
    pub scopes_to_boot: Vec<String>,
    pub discovered: Vec<DiscoveredTest>,
}

pub fn build_preflight_plan(
    workspace_root: &Path,
    projects: &[ProjectGroup],
    config: &TestItConfig,
    quiet: bool,
) -> Result<PreflightPlan> {
    let mut discovered = Vec::new();

    for project in projects {
        for member in &project.members {
            let kinds = config.package_kinds(&member.name, &project.id);
            if kinds.iter().any(|k| k == "integration") {
                discovered.extend(discover_package_tests(workspace_root, &member, config)?);
            }
            if config.package_runtime(&member.name, &project.id) == PackageRuntime::Node
                && kinds.iter().any(|k| k == "unit")
            {
                discovered.extend(node_discover::discover_package_node_tests(
                    workspace_root,
                    member,
                    &project.id,
                )?);
            }
        }
    }

    let docker_required = discovered.iter().any(|t| needs_docker(&t.effective_scope));
    let docker_ok = !docker_required || cherry_test_core::docker_available();

    let mut skip_tests = Vec::new();
    let mut skipped = Vec::new();
    let mut scopes_to_boot = Vec::new();

    if docker_required && docker_ok {
        for test in &discovered {
            if needs_docker(&test.effective_scope)
                && !scopes_to_boot.contains(&test.effective_scope)
            {
                scopes_to_boot.push(test.effective_scope.clone());
            }
        }
    }

    if docker_required && !docker_ok {
        if !quiet {
            eprintln!("warning: Docker not available; skipping scoped integration test(s):");
        }
        for test in &discovered {
            if !needs_docker(&test.effective_scope) {
                continue;
            }
            if !quiet {
                eprintln!("  - {} (scope={})", test.function, test.effective_scope);
            }
            skip_tests.push(test.function.clone());
            skipped.push(SkippedTest {
                package: test.package.clone(),
                function: test.function.clone(),
                scope: test.effective_scope.clone(),
                project: test.project.clone(),
                suite: test.suite.clone(),
                test: test.test.clone(),
                reason: "no docker".into(),
            });
        }
    }

    Ok(PreflightPlan {
        docker_available: docker_ok,
        skip_tests,
        skipped,
        scopes_to_boot,
        discovered,
    })
}

fn discover_package_tests(
    workspace_root: &Path,
    member: &WorkspaceMember,
    config: &TestItConfig,
) -> Result<Vec<DiscoveredTest>> {
    let package_dir = workspace_root.join(
        member
            .manifest_path
            .parent()
            .map(Path::to_path_buf)
            .unwrap_or_else(|| PathBuf::from(".")),
    );
    let tests_dir = package_dir.join("tests");
    if !tests_dir.exists() {
        return Ok(Vec::new());
    }

    let mut out = Vec::new();
    for entry in walk_rs_files(&tests_dir)? {
        let rel = entry
            .strip_prefix(workspace_root)
            .unwrap_or(&entry)
            .to_path_buf();
        let content = std::fs::read_to_string(&entry)?;
        out.extend(parse_test_sources(&member.name, &rel, &content, config)?);
    }
    Ok(out)
}

fn walk_rs_files(dir: &Path) -> Result<Vec<PathBuf>> {
    let mut files = Vec::new();
    if !dir.is_dir() {
        return Ok(files);
    }
    for entry in std::fs::read_dir(dir)? {
        let entry = entry?;
        let path = entry.path();
        if path.is_dir() {
            files.extend(walk_rs_files(&path)?);
        } else if path.extension().and_then(|e| e.to_str()) == Some("rs") {
            files.push(path);
        }
    }
    Ok(files)
}

fn parse_test_sources(
    package: &str,
    source_path: &Path,
    content: &str,
    config: &TestItConfig,
) -> Result<Vec<DiscoveredTest>> {
    let mut out = Vec::new();
    let lines: Vec<&str> = content.lines().collect();
    let mut i = 0;
    while i < lines.len() {
        let line = lines[i].trim();
        if line.starts_with("#[cherry_test") {
            let mut attr = line.to_string();
            while !attr.contains(']') && i + 1 < lines.len() {
                i += 1;
                attr.push(' ');
                attr.push_str(lines[i].trim());
            }
            let attr_scope = parse_scope_attr(&attr);
            let attr_auth = parse_auth_attr(&attr);
            i += 1;
            while i < lines.len() {
                let next = lines[i].trim();
                if next.starts_with("async fn ") {
                    if let Some(name) = parse_fn_name(next) {
                        if let Ok(test_id) = parse_test_name(&name) {
                            let effective = effective_scope(config, attr_scope.as_deref());
                            out.push(DiscoveredTest {
                                package: package.to_string(),
                                function: name,
                                attr_scope,
                                attr_auth: attr_auth.clone(),
                                effective_scope: effective,
                                project: test_id.project,
                                suite: test_id.suite,
                                test: test_id.test,
                                source_path: source_path.to_path_buf(),
                                fn_line: i + 1,
                            });
                        }
                    }
                    break;
                }
                if next.starts_with("#[") {
                    break;
                }
                i += 1;
            }
        }
        i += 1;
    }
    Ok(out)
}

fn parse_scope_attr(attr: &str) -> Option<String> {
    parse_string_attr(attr, "scope")
}

fn parse_auth_attr(attr: &str) -> Option<String> {
    parse_string_attr(attr, "auth")
}

fn parse_string_attr(attr: &str, key: &str) -> Option<String> {
    let needle = format!("{key} = \"");
    let idx = attr.rfind(&needle)?;
    let rest = &attr[idx + needle.len()..];
    let end = rest.find('"')?;
    Some(rest[..end].to_string())
}

fn parse_fn_name(line: &str) -> Option<String> {
    let rest = line.strip_prefix("async fn ")?;
    let name = rest.split('(').next()?.trim();
    Some(name.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_scope_from_attribute() {
        let attr = r#"#[cherry_test(scope = "mini_wallet")]"#;
        assert_eq!(parse_scope_attr(attr).as_deref(), Some("mini_wallet"));
    }

    #[test]
    fn parses_auth_from_attribute() {
        let attr = r#"#[cherry_test(scope = "mini_auth", auth = "okta")]"#;
        assert_eq!(parse_auth_attr(attr).as_deref(), Some("okta"));
    }

    #[test]
    fn discovers_test_from_source() {
        let src = r#"
#[cherry_test(scope = "none")]
async fn mini_project_wallet_suite_offline_test(env: TestEnv) {}
"#;
        let cfg = TestItConfig::default();
        let tests = parse_test_sources(
            "mini-rs-api",
            Path::new("mini/mini-rs-api/tests/integration.rs"),
            src,
            &cfg,
        )
        .unwrap();
        assert_eq!(tests.len(), 1);
        assert_eq!(tests[0].effective_scope, "none");
        assert_eq!(tests[0].fn_line, 3);
    }
}
