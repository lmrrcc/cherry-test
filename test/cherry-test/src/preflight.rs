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
                if let Some(selection) = java_selection(config, &member.name, &project.id) {
                    discovered.extend(discover_java_package_tests(
                        workspace_root,
                        member,
                        config,
                        &selection,
                    )?);
                }
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
    walk_files(dir, "rs")
}

fn walk_files(dir: &Path, extension: &str) -> Result<Vec<PathBuf>> {
    let mut files = Vec::new();
    if !dir.is_dir() {
        return Ok(files);
    }
    for entry in std::fs::read_dir(dir)? {
        let entry = entry?;
        let path = entry.path();
        if path.is_dir() {
            files.extend(walk_files(&path, extension)?);
        } else if path.extension().and_then(|e| e.to_str()) == Some(extension) {
            files.push(path);
        }
    }
    Ok(files)
}

/// Classes and methods a Maven integration phase runs: `-Dit.test` / `-Dtest` from the package arguments,
/// otherwise the Failsafe default includes.
#[derive(Debug, Clone, PartialEq)]
pub(crate) struct JavaSelection {
    includes: Vec<JavaPattern>,
    excludes: Vec<JavaPattern>,
}

#[derive(Debug, Clone, PartialEq)]
struct JavaPattern {
    class: String,
    methods: Vec<String>,
}

pub(crate) fn java_selection(
    config: &TestItConfig,
    package: &str,
    project_id: &str,
) -> Option<JavaSelection> {
    let pkg = config.projects.get(project_id)?.packages.get(package)?;
    let runner = pkg.integration.as_ref();
    let adapter = runner
        .and_then(|r| r.adapter.as_deref())
        .or(pkg.adapter.as_deref())?;
    if adapter != "maven" {
        return None;
    }
    let args = runner.map(|r| r.args.clone()).unwrap_or_default();
    Some(parse_java_selection(&args))
}

fn parse_java_selection(args: &[String]) -> JavaSelection {
    let value = args.iter().rev().find_map(|arg| {
        arg.strip_prefix("-Dit.test=")
            .or_else(|| arg.strip_prefix("-Dtest="))
            .map(str::to_string)
    });
    let Some(value) = value else {
        return JavaSelection {
            includes: ["IT*", "*IT", "*ITCase"]
                .iter()
                .map(|class| JavaPattern {
                    class: (*class).into(),
                    methods: Vec::new(),
                })
                .collect(),
            excludes: Vec::new(),
        };
    };
    let mut selection = JavaSelection {
        includes: Vec::new(),
        excludes: Vec::new(),
    };
    for token in value.split(',').map(str::trim).filter(|t| !t.is_empty()) {
        let (exclude, token) = match token.strip_prefix('!') {
            Some(rest) => (true, rest),
            None => (false, token),
        };
        let (class, methods) = match token.split_once('#') {
            Some((class, methods)) => (
                class,
                methods
                    .split('+')
                    .filter(|m| !m.is_empty())
                    .map(str::to_string)
                    .collect(),
            ),
            None => (token, Vec::new()),
        };
        let class = class
            .trim_start_matches("**/")
            .trim_end_matches(".java")
            .trim_end_matches(".class")
            .to_string();
        let pattern = JavaPattern { class, methods };
        if exclude {
            selection.excludes.push(pattern);
        } else {
            selection.includes.push(pattern);
        }
    }
    selection
}

impl JavaSelection {
    fn selects(&self, class_path: &str, method: &str) -> bool {
        let matches = |pattern: &JavaPattern| {
            let name = if pattern.class.contains('/') || pattern.class.contains('.') {
                class_path.to_string()
            } else {
                class_path
                    .rsplit('/')
                    .next()
                    .unwrap_or(class_path)
                    .to_string()
            };
            let class = pattern.class.replace('.', "/");
            glob_matches(&class, &name.replace('.', "/"))
                && (pattern.methods.is_empty()
                    || pattern.methods.iter().any(|m| glob_matches(m, method)))
        };
        self.includes.iter().any(matches) && !self.excludes.iter().any(matches)
    }

    fn selects_class(&self, class_path: &str) -> bool {
        let simple = class_path.rsplit('/').next().unwrap_or(class_path);
        self.includes.iter().any(|pattern| {
            let target = if pattern.class.contains('/') || pattern.class.contains('.') {
                class_path
            } else {
                simple
            };
            glob_matches(&pattern.class.replace('.', "/"), target)
        })
    }
}

fn glob_matches(pattern: &str, text: &str) -> bool {
    fn matches(pattern: &[char], text: &[char]) -> bool {
        match pattern.first() {
            None => text.is_empty(),
            Some('*') => (0..=text.len()).any(|skip| matches(&pattern[1..], &text[skip..])),
            Some('?') => !text.is_empty() && matches(&pattern[1..], &text[1..]),
            Some(c) => text.first() == Some(c) && matches(&pattern[1..], &text[1..]),
        }
    }
    let pattern: Vec<char> = pattern.chars().collect();
    let text: Vec<char> = text.chars().collect();
    matches(&pattern, &text)
}

fn discover_java_package_tests(
    workspace_root: &Path,
    member: &WorkspaceMember,
    config: &TestItConfig,
    selection: &JavaSelection,
) -> Result<Vec<DiscoveredTest>> {
    let package_dir = workspace_root.join(
        member
            .manifest_path
            .parent()
            .map(Path::to_path_buf)
            .unwrap_or_else(|| PathBuf::from(".")),
    );
    let sources = package_dir.join("src").join("test").join("java");
    let mut out = Vec::new();
    let mut entries = walk_files(&sources, "java")?;
    entries.sort();
    for entry in entries {
        let class_path = entry
            .strip_prefix(&sources)
            .unwrap_or(&entry)
            .with_extension("")
            .to_string_lossy()
            .replace('\\', "/");
        if !selection.selects_class(&class_path) {
            continue;
        }
        let rel = entry
            .strip_prefix(workspace_root)
            .unwrap_or(&entry)
            .to_path_buf();
        let content = std::fs::read_to_string(&entry)?;
        let class = class_path.replace('/', ".");
        out.extend(
            parse_java_test_sources(&member.name, &class, &rel, &content, config)?
                .into_iter()
                .filter(|test| {
                    let method = test.function.rsplit("::").next().unwrap_or(&test.function);
                    selection.selects(&class_path, method)
                }),
        );
    }
    Ok(out)
}

/// `@CherryTest(scope = "...", auth = "...")` methods of a JUnit source, the Java form of `#[cherry_test]`. A test is
/// named `<class>::<method>`, as the Maven and Gradle adapters report it.
fn parse_java_test_sources(
    package: &str,
    class: &str,
    source_path: &Path,
    content: &str,
    config: &TestItConfig,
) -> Result<Vec<DiscoveredTest>> {
    let mut out = Vec::new();
    let lines: Vec<&str> = content.lines().collect();
    let mut i = 0;
    while i < lines.len() {
        let line = lines[i].trim();
        let annotation = line
            .strip_prefix("@CherryTest")
            .or_else(|| line.strip_prefix("@com.cherrytest.junit.CherryTest"));
        let is_annotation = annotation
            .map(|rest| rest.is_empty() || rest.starts_with('(') || rest.starts_with(' '))
            .unwrap_or(false);
        if is_annotation {
            let mut attr = line.to_string();
            while attr.contains('(') && !attr.contains(')') && i + 1 < lines.len() {
                i += 1;
                attr.push(' ');
                attr.push_str(lines[i].trim());
            }
            let attr = normalize_java_attr(&attr);
            let attr_scope = parse_scope_attr(&attr);
            let attr_auth = parse_auth_attr(&attr);
            i += 1;
            while i < lines.len() {
                let next = lines[i].trim();
                if next.starts_with("@CherryTest")
                    || next.contains(" class ")
                    || next.starts_with("class ")
                {
                    break;
                }
                if !next.starts_with('@') && !next.starts_with("//") && next.contains('(') {
                    if let Some(name) = parse_java_method_name(next) {
                        if let Ok(test_id) = parse_test_name(&name) {
                            let effective = effective_scope(config, attr_scope.as_deref());
                            out.push(DiscoveredTest {
                                package: package.to_string(),
                                function: format!("{class}::{name}"),
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
                i += 1;
            }
            continue;
        }
        i += 1;
    }
    Ok(out)
}

/// Writes `key="v"`, `key ="v"` and `key= "v"` as `key = "v"`, the form the shared attribute parser reads.
fn normalize_java_attr(attr: &str) -> String {
    let mut compact = attr.to_string();
    loop {
        let next = compact.replace(" =", "=").replace("= ", "=");
        if next == compact {
            break;
        }
        compact = next;
    }
    compact.replace("=\"", " = \"")
}

fn parse_java_method_name(line: &str) -> Option<String> {
    let before = line.split('(').next()?.trim();
    let name = before.rsplit(char::is_whitespace).next()?.trim();
    if name.is_empty() || !name.chars().all(|c| c.is_alphanumeric() || c == '_') {
        return None;
    }
    Some(name.to_string())
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

    #[test]
    fn discovers_java_cherry_test_methods_with_scope_and_auth() {
        let src = r#"
package com.example.wallet;

class WalletIT {
    @CherryTest(scope = "mini_wallet")
    void mini_project_wallet_suite_balance_test(TestEnv env) {}

    @CherryTest(
            scope="mini_auth",
            auth = "okta")
    @DisplayName("token")
    public void mini_project_auth_suite_token_test(TestEnv env) {}

    @Test
    void plainUnitTest() {}

    @CherryTest
    void notFollowingTheNamingContract(TestEnv env) {}
}
"#;
        let cfg = TestItConfig::default();
        let tests = parse_java_test_sources(
            "mini-java-api",
            "com.example.wallet.WalletIT",
            Path::new("mini-java-api/src/test/java/com/example/wallet/WalletIT.java"),
            src,
            &cfg,
        )
        .unwrap();
        assert_eq!(tests.len(), 2);
        assert_eq!(
            tests[0].function,
            "com.example.wallet.WalletIT::mini_project_wallet_suite_balance_test"
        );
        assert_eq!(tests[0].effective_scope, "mini_wallet");
        assert_eq!(tests[0].fn_line, 6);
        assert_eq!(tests[0].suite, "wallet");
        assert_eq!(tests[1].attr_scope.as_deref(), Some("mini_auth"));
        assert_eq!(tests[1].attr_auth.as_deref(), Some("okta"));
        assert_eq!(tests[1].fn_line, 12);
    }

    #[test]
    fn java_selection_follows_failsafe_filters() {
        let args = |value: &str| vec!["-o".to_string(), value.to_string()];
        let filtered =
            parse_java_selection(&args("-Dit.test=WalletIT,Stack*IT#jug_*,!StackLegacyIT"));
        assert!(filtered.selects("com/example/WalletIT", "anything"));
        assert!(filtered.selects("com/example/StackReferenceIT", "jug_project_a_suite_b_test"));
        assert!(!filtered.selects("com/example/StackReferenceIT", "other"));
        assert!(!filtered.selects("com/example/StackLegacyIT", "jug_project_a_suite_b_test"));
        assert!(!filtered.selects("com/example/ShopIT", "anything"));
        assert!(filtered.selects_class("com/example/StackReferenceIT"));
        assert!(!filtered.selects_class("com/example/ShopIT"));
        let defaults = parse_java_selection(&["-o".to_string()]);
        assert!(defaults.selects("com/example/WalletIT", "m"));
        assert!(defaults.selects("com/example/ITWallet", "m"));
        assert!(!defaults.selects("com/example/WalletTest", "m"));
        let qualified = parse_java_selection(&args("-Dit.test=com.example.WalletIT"));
        assert!(qualified.selects("com/example/WalletIT", "m"));
        assert!(!qualified.selects("org/other/WalletIT", "m"));
    }
}
