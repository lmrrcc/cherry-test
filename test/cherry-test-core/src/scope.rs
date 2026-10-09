use anyhow::{bail, Result};

use crate::config::WorkspaceConfig;

pub fn is_none(scope: &str) -> bool {
    scope.eq_ignore_ascii_case("none")
}

pub fn is_global(scope: &str) -> bool {
    matches!(
        scope.to_ascii_lowercase().as_str(),
        "workspace" | "all" | "global"
    )
}

pub fn needs_docker(scope: &str) -> bool {
    !is_none(scope)
}

pub fn effective_scope(config: &WorkspaceConfig, attr_scope: Option<&str>) -> String {
    attr_scope
        .map(str::to_string)
        .unwrap_or_else(|| config.default_scope())
}

pub fn resolve_projects(config: &WorkspaceConfig, scope: &str) -> Result<Vec<String>> {
    if is_none(scope) {
        return Ok(Vec::new());
    }
    if is_global(scope) {
        return Ok(config.projects.keys().cloned().collect());
    }
    if let Some(scope_cfg) = config.scopes.get(scope) {
        return Ok(scope_cfg.projects.clone());
    }
    if config.projects.contains_key(scope) {
        return Ok(vec![scope.to_string()]);
    }
    let mut known: Vec<_> = config.scopes.keys().cloned().collect();
    known.extend(config.projects.keys().cloned());
    known.sort();
    known.dedup();
    bail!(
        "unknown cherry-test scope `{scope}`; known scopes/projects: {}",
        known.join(", ")
    )
}

pub fn service_filter(config: &WorkspaceConfig, scope: &str, project: &str, service: &str) -> bool {
    if is_none(scope) || is_global(scope) || config.projects.contains_key(scope) {
        return true;
    }
    let Some(scope_cfg) = config.scopes.get(scope) else {
        return true;
    };
    let Some(services) = scope_cfg.services.get(project) else {
        return true;
    };
    services.iter().any(|s| s == service)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;
    use std::path::PathBuf;

    fn sample_config() -> WorkspaceConfig {
        let mut cfg = WorkspaceConfig::default();
        cfg.workspace_root = PathBuf::from("/tmp");
        cfg.defaults.scope = "global".into();
        cfg.projects.insert("mini".into(), Default::default());
        cfg.scopes.insert(
            "mini_wallet".into(),
            crate::config::ScopeConfig {
                projects: vec!["mini".into()],
                services: HashMap::new(),
            },
        );
        cfg
    }

    #[test]
    fn global_synonyms_resolve_all_projects() {
        let cfg = sample_config();
        for name in ["workspace", "all", "global"] {
            let projects = resolve_projects(&cfg, name).unwrap();
            assert_eq!(projects.len(), 1);
        }
    }

    #[test]
    fn project_scope_resolves_single_project() {
        let cfg = sample_config();
        let projects = resolve_projects(&cfg, "mini").unwrap();
        assert_eq!(projects, vec!["mini"]);
    }
}
