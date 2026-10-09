pub fn normalize_path(path: &str) -> String {
    path.replace('\\', "/")
}

pub fn is_path_ignored(path: &str, patterns: &[String]) -> bool {
    let normalized = normalize_path(path);
    patterns
        .iter()
        .any(|pattern| matches_pattern(&normalized, pattern))
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
        assert!(is_path_ignored(
            "mini/mini-rs-lib-mcap/Cargo.toml",
            &["**/Cargo.toml".into()]
        ));
        assert!(!is_path_ignored(
            "mini/mini-rs-lib-mcap/src/lib.rs",
            &["**/Cargo.toml".into()]
        ));
    }
}
