use std::collections::{BTreeMap, BTreeSet, HashMap};
use std::path::{Path, PathBuf};

use anyhow::{bail, Context, Result};
use cherry_test_core::{
    ExternalRunner, PackageConfig, PackageRuntime, ProjectConfig, ScopeConfig, ServiceConfig,
    TestItConfig,
};
use regex::Regex;
use serde_yaml::Value;

const SKIP_CRATE_NAMES: &[&str] = &["cherry-test", "cherry-test-core", "cherry-test-macros"];
const SKIP_DIR_NAMES: &[&str] = &[
    "target",
    "node_modules",
    ".git",
    ".idea",
    ".cursor",
    "dist",
    "build",
];

#[derive(Debug, Clone)]
struct DiscoveredPackage {
    name: String,
    project_id: String,
    manifest_rel: PathBuf,
    package_dir_rel: PathBuf,
    runtime: PackageRuntime,
    kinds: Vec<String>,
}

pub fn scaffold_and_write(root: &Path, quiet: bool) -> Result<TestItConfig> {
    let config = scaffold_config(root)?;
    config.write()?;
    if !quiet {
        let package_count: usize = config.projects.values().map(|p| p.packages.len()).sum();
        println!(
            "{}: created {} ({} projects, {} packages)",
            crate::COMMAND_NAME,
            config.config_path.display(),
            config.projects.len(),
            package_count
        );
    }
    Ok(config)
}

pub fn config_needs_scaffold(config: &TestItConfig, workspace: &Path) -> bool {
    if config.projects.is_empty() {
        return true;
    }
    for (project_id, project) in &config.projects {
        let base = project.path.as_deref().unwrap_or(project_id.as_str());
        let project_dir = workspace.join(base);
        if !project_dir.is_dir() {
            return true;
        }
        for (_, package_cfg) in &project.packages {
            if let Some(manifest) = &package_cfg.manifest {
                if !workspace.join(manifest).is_file() {
                    return true;
                }
            }
        }
    }
    false
}

pub fn scaffold_config(root: &Path) -> Result<TestItConfig> {
    let packages = discover_packages(root)?;
    if packages.is_empty() {
        bail!("no supported test packages found under {}", root.display());
    }
    let mut by_project: BTreeMap<String, Vec<&DiscoveredPackage>> = BTreeMap::new();
    for pkg in &packages {
        by_project
            .entry(pkg.project_id.clone())
            .or_default()
            .push(pkg);
    }
    let mut projects: BTreeMap<String, ProjectConfig> = BTreeMap::new();
    for (project_id, pkgs) in &by_project {
        let mut project = ProjectConfig::default();
        project.path = Some(project_path_for_packages(pkgs));
        project.diff_base = Some("main".into());
        for pkg in pkgs {
            project
                .packages
                .insert(pkg.name.clone(), package_config(root, pkg)?);
        }
        projects.insert(project_id.clone(), project);
    }
    for project in projects.values_mut() {
        let project_path = project.path.as_deref().unwrap_or(".");
        if let Some(compose_rel) = find_compose_file(root, project_path) {
            project.compose = Some(compose_rel.clone());
            project.services = infer_services(root, &compose_rel)?;
        }
    }

    let scopes = infer_scopes(root, &packages, &projects)?;

    Ok(TestItConfig {
        adapters: BTreeMap::new(),
        workspace_root: root.to_path_buf(),
        config_path: root.join("cherry-test.yaml"),
        deprecated_config: false,
        defaults: cherry_test_core::Defaults::default(),
        auth: std::collections::BTreeMap::new(),
        projects,
        scopes,
    })
}

fn discover_packages(root: &Path) -> Result<Vec<DiscoveredPackage>> {
    let mut out = Vec::new();
    walk_packages(root, root, 0, &mut out)?;
    out.sort_by(|a, b| a.name.cmp(&b.name));
    Ok(out)
}

fn walk_packages(
    root: &Path,
    dir: &Path,
    depth: usize,
    out: &mut Vec<DiscoveredPackage>,
) -> Result<()> {
    if depth > 6 {
        return Ok(());
    }
    let name = dir.file_name().and_then(|n| n.to_str()).unwrap_or("");
    if depth > 0 && should_skip_dir(name, dir, root) {
        return Ok(());
    }

    let cargo = dir.join("Cargo.toml");
    if cargo.is_file() {
        if let Some(pkg) = parse_rust_package(root, dir, &cargo)? {
            out.push(pkg);
            return Ok(());
        }
    }

    let package_json = dir.join("package.json");
    if package_json.is_file() {
        if let Some(pkg) = parse_node_package(root, dir, &package_json)? {
            out.push(pkg);
            return Ok(());
        }
    }

    for manifest_name in [
        "pom.xml",
        "build.gradle.kts",
        "build.gradle",
        "CMakeLists.txt",
    ] {
        let manifest = dir.join(manifest_name);
        if !manifest.is_file() {
            continue;
        }
        let rel = dir.strip_prefix(root)?.to_path_buf();
        let content = std::fs::read_to_string(&manifest)?;
        let name = if manifest_name == "pom.xml" {
            let doc = roxmltree::Document::parse(&content).context("parse Maven manifest")?;
            doc.root_element()
                .children()
                .find(|node| node.has_tag_name("artifactId"))
                .and_then(|node| node.text())
                .context("Maven artifactId missing")?
                .to_string()
        } else if manifest_name == "CMakeLists.txt" {
            Regex::new(r#"(?i)project\s*\(\s*["]?([A-Za-z0-9_.-]+)"#)?
                .captures(&content)
                .and_then(|capture| capture.get(1))
                .context("CMake project name missing")?
                .as_str()
                .to_string()
        } else {
            let settings = ["settings.gradle.kts", "settings.gradle"]
                .into_iter()
                .map(|name| dir.join(name))
                .find(|path| path.is_file());
            let settings = settings
                .map(std::fs::read_to_string)
                .transpose()?
                .unwrap_or_default();
            Regex::new(r#"rootProject\.name\s*=\s*['"]([^'"]+)['"]"#)?
                .captures(&settings)
                .and_then(|capture| capture.get(1))
                .map(|value| value.as_str().to_string())
                .unwrap_or_else(|| name.to_string())
        };
        out.push(DiscoveredPackage {
            project_id: infer_project_id(root, &rel, &name),
            name,
            manifest_rel: rel.join(manifest_name),
            package_dir_rel: rel,
            runtime: PackageRuntime::Rust,
            kinds: vec!["unit".into()],
        });
        return Ok(());
    }

    if !dir.is_dir() {
        return Ok(());
    }

    for entry in std::fs::read_dir(dir)? {
        let entry = entry?;
        let path = entry.path();
        if path.is_dir() {
            walk_packages(root, &path, depth + 1, out)?;
        }
    }
    Ok(())
}

fn should_skip_dir(name: &str, dir: &Path, root: &Path) -> bool {
    if SKIP_DIR_NAMES.contains(&name) {
        return true;
    }
    if name.starts_with('.') {
        return true;
    }
    if let Ok(rel) = dir.strip_prefix(root) {
        let rel = rel.to_string_lossy();
        if rel.starts_with("test/cherry-test") {
            return true;
        }
    }
    false
}

fn parse_rust_package(root: &Path, dir: &Path, cargo: &Path) -> Result<Option<DiscoveredPackage>> {
    let contents = std::fs::read_to_string(cargo)?;
    let Some(name) = parse_toml_package_name(&contents) else {
        return Ok(None);
    };
    if SKIP_CRATE_NAMES.contains(&name.as_str()) {
        return Ok(None);
    }

    let rel = dir.strip_prefix(root).unwrap_or(dir).to_path_buf();
    let project_id = infer_project_id(root, &rel, &name);
    let has_cherry_test = has_cherry_test_integration(dir);
    let kinds = if has_cherry_test {
        vec!["unit".into(), "integration".into()]
    } else {
        vec!["unit".into()]
    };

    Ok(Some(DiscoveredPackage {
        name,
        project_id,
        manifest_rel: rel.join("Cargo.toml"),
        package_dir_rel: rel,
        runtime: PackageRuntime::Rust,
        kinds,
    }))
}

fn parse_node_package(
    root: &Path,
    dir: &Path,
    package_json: &Path,
) -> Result<Option<DiscoveredPackage>> {
    let contents = std::fs::read_to_string(package_json)?;
    let value: serde_json::Value = serde_json::from_str(&contents)?;
    let name = value["name"]
        .as_str()
        .context("package.json missing name")?
        .to_string();

    if !has_vitest(&value) {
        return Ok(None);
    }

    let rel = dir.strip_prefix(root).unwrap_or(dir).to_path_buf();
    let project_id = infer_project_id(root, &rel, &name);

    Ok(Some(DiscoveredPackage {
        name,
        project_id,
        manifest_rel: rel.join("package.json"),
        package_dir_rel: rel,
        runtime: PackageRuntime::Node,
        kinds: vec!["unit".into()],
    }))
}

fn parse_toml_package_name(contents: &str) -> Option<String> {
    for line in contents.lines() {
        let line = line.trim();
        if line.starts_with('[') {
            continue;
        }
        if let Some(rest) = line.strip_prefix("name") {
            if let Some(value) = rest.trim().strip_prefix('=') {
                let value = value.trim().trim_matches('"');
                if !value.is_empty() {
                    return Some(value.to_string());
                }
            }
        }
    }
    None
}

fn has_vitest(value: &serde_json::Value) -> bool {
    if value["devDependencies"]["vitest"].is_string() {
        return true;
    }
    if value["dependencies"]["vitest"].is_string() {
        return true;
    }
    value["scripts"]
        .as_object()
        .map(|scripts| {
            scripts
                .values()
                .any(|v| v.as_str().map(|s| s.contains("vitest")).unwrap_or(false))
        })
        .unwrap_or(false)
}

fn has_cherry_test_integration(dir: &Path) -> bool {
    let tests_dir = dir.join("tests");
    if !tests_dir.is_dir() {
        return false;
    }
    walk_rs_for_marker(&tests_dir, "#[cherry_test")
}

fn walk_rs_for_marker(dir: &Path, marker: &str) -> bool {
    let Ok(entries) = std::fs::read_dir(dir) else {
        return false;
    };
    for entry in entries.flatten() {
        let path = entry.path();
        if path.is_dir() {
            if walk_rs_for_marker(&path, marker) {
                return true;
            }
        } else if path.extension().and_then(|e| e.to_str()) == Some("rs") {
            if let Ok(content) = std::fs::read_to_string(&path) {
                if content.contains(marker) {
                    return true;
                }
            }
        }
    }
    false
}

fn infer_project_id(_root: &Path, rel: &Path, package_name: &str) -> String {
    if rel.components().count() >= 2 {
        return rel
            .components()
            .next()
            .and_then(|c| c.as_os_str().to_str())
            .unwrap_or(package_name)
            .to_string();
    }
    package_name
        .split_once('-')
        .map(|(project, _)| project.to_string())
        .unwrap_or_else(|| package_name.to_string())
}

fn package_config(root: &Path, pkg: &DiscoveredPackage) -> Result<PackageConfig> {
    let mut cfg = PackageConfig {
        runtime: pkg.runtime,
        kinds: pkg.kinds.clone(),
        manifest: Some(pkg.manifest_rel.clone()),
        ..Default::default()
    };

    cfg.adapter = match pkg.manifest_rel.file_name().and_then(|name| name.to_str()) {
        Some("pom.xml") => Some("maven".into()),
        Some("build.gradle" | "build.gradle.kts") => Some("gradle".into()),
        Some("CMakeLists.txt") => Some("cmake".into()),
        _ => None,
    };
    if pkg.runtime == PackageRuntime::Node {
        cfg.unit = Some(node_unit_runner(root, &pkg.package_dir_rel)?);
    }

    Ok(cfg)
}

fn node_unit_runner(root: &Path, package_dir_rel: &Path) -> Result<ExternalRunner> {
    let use_pnpm = prefers_pnpm(root, package_dir_rel);
    if use_pnpm {
        Ok(ExternalRunner {
            command: "pnpm".into(),
            args: vec![
                "exec".into(),
                "vitest".into(),
                "run".into(),
                "--coverage".into(),
            ],
            cwd: Some(package_dir_rel.to_path_buf()),
            adapter: None,
            scope: None,
            options: Default::default(),
        })
    } else {
        Ok(ExternalRunner {
            command: "npx".into(),
            args: vec!["vitest".into(), "run".into(), "--coverage".into()],
            cwd: Some(package_dir_rel.to_path_buf()),
            adapter: None,
            scope: None,
            options: Default::default(),
        })
    }
}

fn prefers_pnpm(root: &Path, package_dir_rel: &Path) -> bool {
    if root.join("pnpm-lock.yaml").exists() || root.join("pnpm-workspace.yaml").exists() {
        return true;
    }
    if package_dir_rel.components().count() >= 1 {
        let project_root = root.join(
            package_dir_rel
                .components()
                .next()
                .map(|c| c.as_os_str())
                .unwrap_or_default(),
        );
        if project_root.join("pnpm-lock.yaml").exists()
            || project_root.join("pnpm-workspace.yaml").exists()
        {
            return true;
        }
    }
    false
}

fn project_path_for_packages(packages: &[&DiscoveredPackage]) -> String {
    let mut prefix: Option<PathBuf> = None;
    for pkg in packages {
        prefix = Some(match prefix {
            None => pkg.package_dir_rel.clone(),
            Some(existing) => common_path_prefix(&existing, &pkg.package_dir_rel),
        });
    }
    let prefix = prefix.unwrap_or_default();
    if prefix.as_os_str().is_empty() {
        ".".to_string()
    } else {
        prefix.to_string_lossy().into_owned()
    }
}

fn common_path_prefix(left: &Path, right: &Path) -> PathBuf {
    let left_parts: Vec<_> = left.components().collect();
    let right_parts: Vec<_> = right.components().collect();
    let mut shared = PathBuf::new();
    for (left_part, right_part) in left_parts.iter().zip(right_parts.iter()) {
        if left_part == right_part {
            shared.push(left_part.as_os_str());
        } else {
            break;
        }
    }
    shared
}

fn find_compose_file(root: &Path, project_path: &str) -> Option<String> {
    let project_dir = root.join(project_path);
    for name in ["docker-compose.test.yml", "docker-compose.yml"] {
        let path = project_dir.join(name);
        if path.is_file() {
            if project_path == "." {
                return Some(name.to_string());
            }
            return Some(format!("{project_path}/{name}"));
        }
    }
    None
}

fn infer_services(root: &Path, compose_rel: &str) -> Result<HashMap<String, ServiceConfig>> {
    let path = root.join(compose_rel);
    let contents =
        std::fs::read_to_string(&path).with_context(|| format!("read {}", path.display()))?;
    let value: Value = serde_yaml::from_str(&contents)?;
    let services = value
        .get("services")
        .and_then(|s| s.as_mapping())
        .cloned()
        .unwrap_or_default();

    let mut out = HashMap::new();
    for (service_name, service) in services {
        let Some(service_name) = service_name.as_str() else {
            continue;
        };
        let Some(service) = service.as_mapping() else {
            continue;
        };

        let image = mapping_str(service, "image").unwrap_or_default();
        if image.to_ascii_lowercase().contains("postgres") {
            out.insert(
                "db".into(),
                ServiceConfig {
                    service: service_name.to_string(),
                    port: 5432,
                    health: None,
                    kind: Some("postgres".into()),
                },
            );
            continue;
        }

        if service.contains_key("build") {
            if let Some((port, health)) = infer_http_service(service) {
                out.insert(
                    "api".into(),
                    ServiceConfig {
                        service: service_name.to_string(),
                        port,
                        health: Some(health),
                        kind: None,
                    },
                );
            }
        }
    }

    Ok(out)
}

fn infer_http_service(service: &serde_yaml::Mapping) -> Option<(u16, String)> {
    if let Some(health) = extract_health_path(service) {
        let port = extract_compose_port(service)?;
        return Some((port, health));
    }
    let port = extract_compose_port(service)?;
    Some((port, "/health".into()))
}

fn mapping_str(map: &serde_yaml::Mapping, key: &str) -> Option<String> {
    map.get(&Value::from(key))
        .and_then(|v| v.as_str())
        .map(str::to_string)
}

fn extract_health_path(service: &serde_yaml::Mapping) -> Option<String> {
    let health = service.get(&Value::from("healthcheck"))?;
    let test = health.get("test")?;
    let seq = test.as_sequence()?;
    for item in seq {
        if let Some(s) = item.as_str() {
            if let Some(path) = extract_url_path(s) {
                return Some(path);
            }
        }
    }
    None
}

fn extract_url_path(s: &str) -> Option<String> {
    let idx = s.find("http://")?;
    let rest = &s[idx + 7..];
    let path_start = rest.find('/')?;
    Some(rest[path_start..].to_string())
}

fn extract_compose_port(service: &serde_yaml::Mapping) -> Option<u16> {
    let ports = service.get(&Value::from("ports"))?.as_sequence()?;
    for entry in ports {
        if let Some(s) = entry.as_str() {
            if let Some(port) = parse_port_spec(s) {
                return Some(port);
            }
        } else if let Some(n) = entry.as_i64() {
            return Some(n as u16);
        }
    }
    None
}

fn parse_port_spec(s: &str) -> Option<u16> {
    let part = s.split(':').next()?.trim();
    part.parse().ok()
}

fn infer_scopes(
    root: &Path,
    packages: &[DiscoveredPackage],
    projects: &BTreeMap<String, ProjectConfig>,
) -> Result<BTreeMap<String, ScopeConfig>> {
    let project_refs = collect_scope_projects(root, packages)?;
    let compose_projects: Vec<String> = projects
        .iter()
        .filter(|(_, p)| p.compose.is_some())
        .map(|(id, _)| id.clone())
        .collect();

    let mut scopes = BTreeMap::new();
    for (scope_name, project_ids) in project_refs {
        let mut projects_list: Vec<String> = if project_ids.is_empty() {
            compose_projects.clone()
        } else {
            project_ids.into_iter().collect()
        };
        projects_list.sort();
        projects_list.dedup();
        scopes.insert(
            scope_name,
            ScopeConfig {
                projects: projects_list,
                services: HashMap::new(),
            },
        );
    }
    Ok(scopes)
}

fn collect_scope_projects(
    root: &Path,
    packages: &[DiscoveredPackage],
) -> Result<BTreeMap<String, BTreeSet<String>>> {
    let scope_re = Regex::new(r#"#\[cherry_test\s*\(\s*scope\s*=\s*"([^"]+)"\s*\)\]"#)?;
    let project_re = Regex::new(r#"\.project\s*\(\s*"([^"]+)"\s*\)"#)?;
    let mut scopes: BTreeMap<String, BTreeSet<String>> = BTreeMap::new();

    for pkg in packages {
        if pkg.runtime != PackageRuntime::Rust {
            continue;
        }
        let tests_dir = root.join(&pkg.package_dir_rel).join("tests");
        if !tests_dir.is_dir() {
            continue;
        }
        for file in walk_rs_files(&tests_dir)? {
            let content = std::fs::read_to_string(&file)?;
            for cap in scope_re.captures_iter(&content) {
                let scope_name = cap.get(1).map(|m| m.as_str()).unwrap_or("");
                if scope_name.eq_ignore_ascii_case("none") {
                    continue;
                }
                let fn_body = function_body_after_attr(&content, cap.get(0).unwrap().start());
                let entry = scopes.entry(scope_name.to_string()).or_default();
                for project_cap in project_re.captures_iter(fn_body) {
                    if let Some(project) = project_cap.get(1) {
                        entry.insert(project.as_str().to_string());
                    }
                }
            }
        }
    }

    Ok(scopes)
}

fn function_body_after_attr(content: &str, attr_start: usize) -> &str {
    let rest = &content[attr_start..];
    if let Some(idx) = rest.find("async fn") {
        let after_fn = &rest[idx..];
        if let Some(brace) = after_fn.find('{') {
            return &after_fn[brace..];
        }
        return after_fn;
    }
    rest
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

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    #[test]
    fn scaffolds_mini_like_layout() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path();
        fs::create_dir_all(root.join("mini/mini-rs-api/tests")).unwrap();
        fs::create_dir_all(root.join("mini/mini-ts-lib-price")).unwrap();
        fs::create_dir_all(root.join("mini/mini-rs-lib-mcap")).unwrap();
        fs::write(
            root.join("Cargo.toml"),
            "[workspace]\nmembers = [\"mini/mini-rs-api\", \"mini/mini-rs-lib-mcap\"]\n",
        )
        .unwrap();
        fs::write(
            root.join("mini/mini-rs-api/Cargo.toml"),
            "[package]\nname = \"mini-rs-api\"\n",
        )
        .unwrap();
        fs::write(
            root.join("mini/mini-rs-api/tests/integration.rs"),
            r#"#[cherry_test(scope = "mini_wallet")]
async fn mini_project_wallet_suite_ping_test(env: TestEnv) {
    env.project("mini").service("api")?;
    env.project("mini").service("mcap")?;
}
"#,
        )
        .unwrap();
        fs::write(
            root.join("mini/mini-ts-lib-price/package.json"),
            r#"{"name":"mini-ts-lib-price","devDependencies":{"vitest":"2.1.9"}}"#,
        )
        .unwrap();
        fs::write(
            root.join("mini/mini-rs-lib-mcap/Cargo.toml"),
            "[package]\nname = \"mini-rs-lib-mcap\"\n",
        )
        .unwrap();
        fs::write(
            root.join("mini/docker-compose.test.yml"),
            r#"services:
  mini-db:
    image: postgres:16-alpine
  mini-rs-lib-mcap:
    build:
      context: ..
    ports:
      - "3001"
    healthcheck:
      test: ["CMD", "wget", "-q", "-O", "-", "http://127.0.0.1:3001/health"]
  mini-rs-api:
    build:
      context: ..
    ports:
      - "3000"
    healthcheck:
      test: ["CMD", "wget", "-q", "-O", "-", "http://127.0.0.1:3000/health"]
"#,
        )
        .unwrap();
        fs::write(root.join("pnpm-workspace.yaml"), "packages:\n  - mini/*\n").unwrap();

        let config = scaffold_config(root).unwrap();
        assert!(config.projects.contains_key("mini"));
        assert!(!config.projects.contains_key("mcap"));
        assert!(config.projects["mini"].packages.contains_key("mini-rs-api"));
        assert!(config.projects["mini"]
            .packages
            .contains_key("mini-rs-lib-mcap"));
        assert!(config.projects["mini"]
            .packages
            .contains_key("mini-ts-lib-price"));
        assert_eq!(config.projects["mini"].path.as_deref(), Some("mini"));
        assert_eq!(
            config.projects["mini"].compose.as_deref(),
            Some("mini/docker-compose.test.yml")
        );
        assert!(config.projects["mini"].services.contains_key("api"));
        assert!(config.projects["mini"].services.contains_key("db"));
        assert!(config.scopes.contains_key("mini_wallet"));
        assert_eq!(
            config.scopes["mini_wallet"].projects,
            vec!["mini".to_string()]
        );
    }

    #[test]
    fn scaffolds_jvm_and_native_packages_without_a_cargo_workspace() {
        let dir = tempfile::tempdir().unwrap();
        for (directory, manifest, content) in [
            ("java", "pom.xml", "<project><parent><artifactId>parent</artifactId></parent><artifactId>sample-java</artifactId></project>"),
            ("kotlin", "build.gradle.kts", "plugins { kotlin(\"jvm\") }"),
            ("native", "CMakeLists.txt", "project(sample-native LANGUAGES C CXX)"),
        ] {
            fs::create_dir_all(dir.path().join(directory)).unwrap();
            fs::write(dir.path().join(directory).join(manifest), content).unwrap();
        }
        fs::write(
            dir.path().join("kotlin/settings.gradle.kts"),
            "rootProject.name = \"sample-kotlin\"",
        )
        .unwrap();
        let config = scaffold_config(dir.path()).unwrap();
        for (name, adapter) in [
            ("sample-java", "maven"),
            ("sample-kotlin", "gradle"),
            ("sample-native", "cmake"),
        ] {
            assert_eq!(
                config.projects["sample"].packages[name].adapter.as_deref(),
                Some(adapter)
            );
            assert_eq!(config.projects["sample"].packages[name].kinds, ["unit"]);
        }
    }

    #[test]
    fn scaffolds_node_only_repo() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path();
        fs::create_dir_all(root.join("app/my-lib")).unwrap();
        fs::write(
            root.join("app/my-lib/package.json"),
            r#"{"name":"app-ts-lib-core","scripts":{"test":"vitest run"},"devDependencies":{"vitest":"2.1.9"}}"#,
        )
        .unwrap();

        let config = scaffold_config(root).unwrap();
        assert_eq!(config.projects.len(), 1);
        assert!(config.projects.contains_key("app"));
    }

    #[test]
    fn scaffolds_flat_mini_at_root() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path();
        fs::create_dir_all(root.join("mini-rs-api/tests")).unwrap();
        fs::create_dir_all(root.join("mini-ts-lib-price")).unwrap();
        fs::create_dir_all(root.join("mini-rs-lib-mcap")).unwrap();
        fs::write(
            root.join("mini-rs-api/Cargo.toml"),
            "[package]\nname = \"mini-rs-api\"\n",
        )
        .unwrap();
        fs::write(
            root.join("mini-rs-lib-mcap/Cargo.toml"),
            "[package]\nname = \"mini-rs-lib-mcap\"\n",
        )
        .unwrap();
        fs::write(
            root.join("mini-ts-lib-price/package.json"),
            r#"{"name":"mini-ts-lib-price","devDependencies":{"vitest":"2.1.9"}}"#,
        )
        .unwrap();
        fs::write(
            root.join("docker-compose.test.yml"),
            r#"services:
  mini-rs-api:
    build:
      context: .
    ports:
      - "3000"
    healthcheck:
      test: ["CMD", "wget", "-q", "-O", "-", "http://127.0.0.1:3000/health"]
"#,
        )
        .unwrap();
        fs::write(root.join("pnpm-workspace.yaml"), "packages:\n  - '*'\n").unwrap();
        let config = scaffold_config(root).unwrap();
        assert!(config.projects.contains_key("mini"));
        assert_eq!(config.projects["mini"].path.as_deref(), Some("."));
        assert_eq!(
            config.projects["mini"].compose.as_deref(),
            Some("docker-compose.test.yml")
        );
        assert!(config.projects["mini"].packages.contains_key("mini-rs-api"));
    }

    #[test]
    fn config_needs_scaffold_when_project_path_missing() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path();
        fs::write(root.join("cherry-test.yaml"), "projects: {}\n").unwrap();
        let config = TestItConfig::load(root).unwrap();
        assert!(config_needs_scaffold(&config, root));
        let mut config = TestItConfig {
            adapters: BTreeMap::new(),
            workspace_root: root.to_path_buf(),
            config_path: root.join("cherry-test.yaml"),
            deprecated_config: false,
            defaults: cherry_test_core::Defaults::default(),
            auth: BTreeMap::new(),
            projects: BTreeMap::from([(
                "mini".to_string(),
                ProjectConfig {
                    path: Some("mini".to_string()),
                    packages: BTreeMap::from([(
                        "mini-rs-api".to_string(),
                        PackageConfig {
                            manifest: Some(PathBuf::from("mini-rs-api/Cargo.toml")),
                            ..Default::default()
                        },
                    )]),
                    ..Default::default()
                },
            )]),
            scopes: BTreeMap::new(),
        };
        assert!(config_needs_scaffold(&config, root));
        fs::create_dir_all(root.join("mini-rs-api")).unwrap();
        fs::write(
            root.join("mini-rs-api/Cargo.toml"),
            "[package]\nname = \"mini-rs-api\"\n",
        )
        .unwrap();
        config.projects.get_mut("mini").unwrap().path = Some(".".to_string());
        assert!(!config_needs_scaffold(&config, root));
    }
}
