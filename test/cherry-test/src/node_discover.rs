use std::path::{Path, PathBuf};

use anyhow::Result;
use regex::Regex;

use crate::discover::WorkspaceMember;
use crate::preflight::DiscoveredTest;

pub fn discover_package_node_tests(
    workspace_root: &Path,
    member: &WorkspaceMember,
    project_id: &str,
) -> Result<Vec<DiscoveredTest>> {
    let package_dir = workspace_root.join(
        member
            .manifest_path
            .parent()
            .map(Path::to_path_buf)
            .unwrap_or_else(|| PathBuf::from(".")),
    );
    if !package_dir.is_dir() {
        return Ok(Vec::new());
    }

    let test_name = Regex::new(r#"(?m)(?:\bit|\btest)\(\s*['"]([^'"]+)['"]"#)?;
    let mut out = Vec::new();

    for file in walk_test_files(&package_dir)? {
        let rel = file
            .strip_prefix(workspace_root)
            .unwrap_or(&file)
            .to_path_buf();
        let content = std::fs::read_to_string(&file)?;
        for cap in test_name.captures_iter(&content) {
            let title = cap.get(1).map(|m| m.as_str()).unwrap_or("unnamed");
            let test_slug = slugify(title);
            let function = format!("{project_id}_project_unit_suite_{test_slug}_test");
            out.push(DiscoveredTest {
                package: member.name.clone(),
                function: function.clone(),
                attr_scope: None,
                attr_auth: None,
                effective_scope: "none".into(),
                project: project_id.to_string(),
                suite: "unit".into(),
                test: test_slug,
                source_path: rel.clone(),
                fn_line: line_number_for_match(
                    &content,
                    cap.get(0).map(|m| m.start()).unwrap_or(0),
                ),
            });
        }
    }

    Ok(out)
}

fn walk_test_files(dir: &Path) -> Result<Vec<PathBuf>> {
    let mut files = Vec::new();
    if !dir.is_dir() {
        return Ok(files);
    }
    for entry in std::fs::read_dir(dir)? {
        let entry = entry?;
        let path = entry.path();
        if path.is_dir() {
            if path.file_name().and_then(|n| n.to_str()) == Some("node_modules") {
                continue;
            }
            files.extend(walk_test_files(&path)?);
            continue;
        }
        if is_test_file(&path) {
            files.push(path);
        }
    }
    Ok(files)
}

fn is_test_file(path: &Path) -> bool {
    let name = path.file_name().and_then(|n| n.to_str()).unwrap_or("");
    (name.ends_with(".test.ts")
        || name.ends_with(".test.tsx")
        || name.ends_with(".spec.ts")
        || name.ends_with(".spec.tsx")
        || name.ends_with(".test.vue")
        || name.ends_with(".spec.vue"))
        && path.is_file()
}

fn slugify(input: &str) -> String {
    let mut out = String::new();
    let mut prev_underscore = false;
    for ch in input.chars() {
        if ch.is_ascii_alphanumeric() {
            out.push(ch.to_ascii_lowercase());
            prev_underscore = false;
        } else if !prev_underscore {
            out.push('_');
            prev_underscore = true;
        }
    }
    let trimmed = out.trim_matches('_');
    if trimmed.is_empty() {
        "unnamed".to_string()
    } else {
        trimmed.to_string()
    }
}

fn line_number_for_match(content: &str, byte_offset: usize) -> usize {
    content[..byte_offset.min(content.len())]
        .chars()
        .filter(|c| *c == '\n')
        .count()
        + 1
}

#[cfg(test)]
mod tests {
    use super::*;
    use cherry_test_core::PackageRuntime;

    #[test]
    fn discovers_vitest_tests_from_source() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path();
        let pkg = root.join("mini/mini-ts-web-react");
        std::fs::create_dir_all(&pkg.join("src")).unwrap();
        std::fs::write(
            pkg.join("src/display.test.tsx"),
            r#"
describe("App", () => {
  it("renders formatted USDT from API response", async () => {});
});
"#,
        )
        .unwrap();

        let member = WorkspaceMember {
            name: "mini-ts-web-react".into(),
            manifest_path: PathBuf::from("mini/mini-ts-web-react/package.json"),
            project_id: "mini".into(),
            runtime: PackageRuntime::Node,
        };
        let tests = discover_package_node_tests(root, &member, "mini").unwrap();
        assert_eq!(tests.len(), 1);
        assert_eq!(tests[0].suite, "unit");
        assert!(tests[0].function.contains("renders_formatted_usdt"));
    }

    #[test]
    fn slugify_normalizes_titles() {
        assert_eq!(slugify("formats USDT value"), "formats_usdt_value");
    }
}
