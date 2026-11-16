use std::collections::BTreeMap;
use std::path::Path;

pub use cherry_test_core::Environment;

/// The process environment, captured once before a run; a run never changes the process environment.
pub fn snapshot() -> Environment {
    cherry_test_core::process_environment()
}

/// Variables from `.env.test` at the configuration root, or else from `<project>/.env.test`.
pub fn env_file(workspace_root: &Path, project_id: &str) -> Environment {
    let local = workspace_root.join(".env.test");
    let nested = workspace_root.join(project_id).join(".env.test");
    let env_path = if local.is_file() {
        local
    } else if nested.is_file() {
        nested
    } else {
        return Environment::new();
    };
    let mut vars = Environment::new();
    if let Ok(content) = std::fs::read_to_string(&env_path) {
        for line in content.lines() {
            let line = line.trim();
            if line.is_empty() || line.starts_with('#') {
                continue;
            }
            if let Some((key, value)) = line.split_once('=') {
                vars.insert(key.trim().to_string(), value.trim().to_string());
            }
        }
    }
    vars
}

/// The `CHERRY_TEST_*` variables an adapter request records; credentials stay out of `request.json`.
pub fn request_env(env: &Environment) -> BTreeMap<String, String> {
    env.iter()
        .filter(|(key, _)| {
            key.starts_with("CHERRY_TEST_")
                && !key.contains("AUTH")
                && !key.contains("TOKEN")
                && !key.contains("SECRET")
        })
        .map(|(key, value)| (key.clone(), value.clone()))
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn env_file_prefers_the_root_file_and_skips_comments() {
        let root = tempfile::tempdir().unwrap();
        std::fs::create_dir(root.path().join("mini")).unwrap();
        std::fs::write(
            root.path().join("mini/.env.test"),
            "CHERRY_TEST_MINI_DB_NAME=nested\n",
        )
        .unwrap();
        assert_eq!(
            env_file(root.path(), "mini")["CHERRY_TEST_MINI_DB_NAME"],
            "nested"
        );
        std::fs::write(
            root.path().join(".env.test"),
            "# comment\n\n CHERRY_TEST_MINI_DB_NAME = root \nBROKEN\n",
        )
        .unwrap();
        let vars = env_file(root.path(), "mini");
        assert_eq!(vars.len(), 1);
        assert_eq!(vars["CHERRY_TEST_MINI_DB_NAME"], "root");
        assert!(env_file(root.path(), "other").contains_key("CHERRY_TEST_MINI_DB_NAME"));
    }

    #[test]
    fn request_env_keeps_cherry_test_variables_without_credentials() {
        let env: Environment = [
            ("PATH", "/bin"),
            ("CHERRY_TEST_RUN_ID", "run"),
            ("CHERRY_TEST_AUTH_OKTA_ACCESS_TOKEN", "token"),
            ("CHERRY_TEST_API_TOKEN", "token"),
            ("CHERRY_TEST_CLIENT_SECRET", "secret"),
            ("CHERRY_TEST_MINI_DB_PASSWORD", "password"),
        ]
        .into_iter()
        .map(|(key, value)| (key.to_string(), value.to_string()))
        .collect();
        let view = request_env(&env);
        assert_eq!(
            view.keys().collect::<Vec<_>>(),
            ["CHERRY_TEST_MINI_DB_PASSWORD", "CHERRY_TEST_RUN_ID"]
        );
    }
}
