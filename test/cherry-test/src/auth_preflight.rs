use std::collections::BTreeSet;
use std::path::Path;

use anyhow::Result;
use cherry_test_core::{auth, Environment, TestItConfig};

use crate::preflight::{PreflightPlan, SkippedTest};

/// Fetches a token for every auth profile the discovered tests use and returns the variables to export.
///
/// Profiles that fail mark their tests skipped; the Okta mock URL is read from `env`.
pub fn run_auth_preflight(
    workspace_root: &Path,
    config: &TestItConfig,
    plan: &mut PreflightPlan,
    quiet: bool,
    env: &Environment,
) -> Result<Vec<(String, String)>> {
    let profiles = auth_profiles_needed(plan);
    let mut exports = Vec::new();
    if profiles.is_empty() {
        return Ok(exports);
    }
    for profile_name in profiles {
        let profile = match config.auth_profile(&profile_name) {
            Ok(p) => p,
            Err(e) => {
                mark_auth_skip(plan, &profile_name, &e.to_string(), quiet);
                continue;
            }
        };
        match auth::profile_token_env_blocking(workspace_root, &profile_name, profile, env) {
            Ok(vars) => exports.extend(vars),
            Err(e) => {
                mark_auth_skip(plan, &profile_name, &format!("{e:#}"), quiet);
                continue;
            }
        }
        if !quiet {
            println!("auth preflight: profile `{profile_name}` ready");
        }
    }
    Ok(exports)
}

fn auth_profiles_needed(plan: &PreflightPlan) -> BTreeSet<String> {
    let mut out = BTreeSet::new();
    for test in &plan.discovered {
        if let Some(auth) = &test.attr_auth {
            out.insert(auth.clone());
        }
    }
    out
}

fn mark_auth_skip(plan: &mut PreflightPlan, profile: &str, reason: &str, quiet: bool) {
    if !quiet {
        eprintln!("warning: auth profile `{profile}` unavailable: {reason}");
    }
    for test in &plan.discovered {
        if test.attr_auth.as_deref() != Some(profile) {
            continue;
        }
        if plan.skip_tests.contains(&test.function) {
            continue;
        }
        if !quiet {
            eprintln!("  - {} (auth={profile})", test.function);
        }
        plan.skip_tests.push(test.function.clone());
        plan.skipped.push(SkippedTest {
            package: test.package.clone(),
            function: test.function.clone(),
            scope: test.effective_scope.clone(),
            project: test.project.clone(),
            suite: test.suite.clone(),
            test: test.test.clone(),
            reason: "auth unavailable".into(),
        });
    }
}
