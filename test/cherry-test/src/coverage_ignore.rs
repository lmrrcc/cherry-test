use std::path::Path;

use cherry_test_core::TestItConfig;

use crate::coverage::normalize_path;

#[derive(Debug, Clone, Default)]
pub struct CoverageIgnore {
    patterns: Vec<String>,
}

impl CoverageIgnore {
    pub fn load(workspace_root: &Path, config: &TestItConfig) -> Self {
        let path = workspace_root.join(&config.defaults.coverage_ignore);
        let patterns = if path.exists() {
            load_patterns(&path)
        } else {
            Vec::new()
        };
        Self { patterns }
    }

    pub fn is_ignored(&self, path: &str) -> bool {
        let normalized = normalize_path(path);
        self.patterns
            .iter()
            .any(|pattern| matches_pattern(&normalized, pattern))
    }

    pub fn filter_paths<'a, I>(&self, paths: I) -> Vec<String>
    where
        I: IntoIterator<Item = &'a String>,
    {
        paths
            .into_iter()
            .filter(|path| !self.is_ignored(path))
            .cloned()
            .collect()
    }

    pub fn filter_file_coverage(
        &self,
        files: Vec<crate::coverage::FileCoverage>,
    ) -> Vec<crate::coverage::FileCoverage> {
        files
            .into_iter()
            .filter(|file| !self.is_ignored(&file.path))
            .collect()
    }

    pub fn filter_line_coverage(
        &self,
        files: Vec<crate::coverage::FileLineCoverage>,
    ) -> Vec<crate::coverage::FileLineCoverage> {
        files
            .into_iter()
            .filter(|file| !self.is_ignored(&file.path))
            .collect()
    }

    pub fn from_patterns(patterns: Vec<String>) -> Self {
        Self { patterns }
    }
}

fn load_patterns(path: &Path) -> Vec<String> {
    std::fs::read_to_string(path)
        .unwrap_or_default()
        .lines()
        .map(str::trim)
        .filter(|line| !line.is_empty() && !line.starts_with('#'))
        .map(str::to_string)
        .collect()
}

fn matches_pattern(path: &str, pattern: &str) -> bool {
    let pattern = normalize_path(pattern);
    if let Some(dir) = directory_glob(&pattern) {
        return path_contains_dir(path, dir);
    }
    if pattern.contains('*') {
        return glob_match(path, &pattern);
    }
    path == pattern || path.ends_with(&format!("/{pattern}"))
}

fn directory_glob(pattern: &str) -> Option<&str> {
    pattern
        .strip_prefix("**/")
        .and_then(|rest| rest.strip_suffix("/**"))
        .filter(|dir| !dir.contains('*'))
}

fn path_contains_dir(path: &str, dir: &str) -> bool {
    path.contains(&format!("/{dir}/")) || path.starts_with(&format!("{dir}/"))
}

fn glob_match(path: &str, pattern: &str) -> bool {
    if let Some(suffix) = pattern.strip_prefix("**/") {
        if suffix.contains('*') {
            let parts: Vec<&str> = suffix.split('*').collect();
            if parts.len() == 2 && parts[0].is_empty() && parts[1].is_empty() {
                return true;
            }
            if parts.len() == 2 && parts[0].is_empty() {
                return path.ends_with(parts[1]);
            }
            return path.ends_with(suffix.trim_start_matches('*'))
                || path.split('/').any(|seg| segment_match(seg, suffix));
        }
        return path.ends_with(&format!("/{suffix}")) || path == suffix || path.ends_with(suffix);
    }
    if pattern.contains('*') {
        let parts: Vec<&str> = pattern.split('*').collect();
        if parts.len() == 2 {
            return path.starts_with(parts[0]) && path.ends_with(parts[1]);
        }
    }
    path == pattern
}

fn segment_match(segment: &str, pattern: &str) -> bool {
    if !pattern.contains('*') {
        return segment == pattern;
    }
    let parts: Vec<&str> = pattern.split('*').collect();
    if parts.len() == 2 && parts[0].is_empty() && parts[1].is_empty() {
        return true;
    }
    if parts.len() == 2 && parts[0].is_empty() {
        return segment.ends_with(parts[1]);
    }
    if parts.len() == 2 && parts[1].is_empty() {
        return segment.starts_with(parts[0]);
    }
    segment == pattern
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cargo_toml_pattern_matches_nested_path() {
        let ignore = CoverageIgnore::from_patterns(vec!["**/Cargo.toml".into()]);
        assert!(ignore.is_ignored("mini/mini-rs-lib-mcap/Cargo.toml"));
        assert!(!ignore.is_ignored("mini/mini-rs-lib-mcap/src/lib.rs"));
    }

    #[test]
    fn ignores_comments_and_blanks() {
        let dir =
            std::env::temp_dir().join(format!("cherry-test-cov-ignore-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join(".coverageignore"), "# comment\n\n**/Cargo.toml\n").unwrap();
        let patterns = load_patterns(&dir.join(".coverageignore"));
        assert_eq!(patterns, vec!["**/Cargo.toml".to_string()]);
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn node_modules_pattern_matches_nested_dependency_paths() {
        let ignore = CoverageIgnore::from_patterns(vec!["**/node_modules/**".into()]);
        assert!(ignore.is_ignored("mini/mini-ts-lib-price/node_modules/foo/index.js"));
        assert!(ignore
            .is_ignored("/Users/dev/cherry-test/mini/mini-ts-lib-price/node_modules/foo/index.js"));
        assert!(!ignore.is_ignored("mini/mini-ts-lib-price/src/displayAddressUsdt.ts"));
    }

    #[test]
    fn frontend_config_patterns_match_nested_paths() {
        let ignore = CoverageIgnore::from_patterns(vec![
            "**/package.json".into(),
            "**/tsconfig.json".into(),
            "**/vite.config.ts".into(),
        ]);
        assert!(ignore.is_ignored("mini/mini-ts-web-react/package.json"));
        assert!(ignore.is_ignored("mini/mini-ts-web-react/tsconfig.json"));
        assert!(ignore.is_ignored("mini/mini-ts-web-vue/vite.config.ts"));
        assert!(!ignore.is_ignored("mini/mini-ts-web-react/src/App.tsx"));
    }

    #[test]
    fn filter_paths_removes_ignored() {
        let ignore = CoverageIgnore::from_patterns(vec!["**/Cargo.toml".into()]);
        let paths = vec![
            "mini/mini-rs-lib-mcap/Cargo.toml".into(),
            "mini/mini-rs-lib-mcap/src/lib.rs".into(),
        ];
        let filtered = ignore.filter_paths(paths.iter());
        assert_eq!(
            filtered,
            vec!["mini/mini-rs-lib-mcap/src/lib.rs".to_string()]
        );
    }
}
