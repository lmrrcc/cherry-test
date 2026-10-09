use std::collections::BTreeSet;
use std::path::Path;

use anyhow::Result;
use cherry_test_core::{auth, TestItConfig};

use crate::preflight::{PreflightPlan, SkippedTest};

pub fn run_auth_preflight(
    workspace_root: &Path,
    config: &TestItConfig,
    plan: &mut PreflightPlan,
    quiet: bool,
) -> Result<()> {
    let profiles = auth_profiles_needed(plan);
    if profiles.is_empty() {
        return Ok(());
    }
    for profile_name in profiles {
        let profile = match config.auth_profile(&profile_name) {
            Ok(p) => p,
            Err(e) => {
                mark_auth_skip(plan, &profile_name, &e.to_string(), quiet);
                continue;
            }
        };
        if let Err(e) = auth::ensure_profile_token_blocking(workspace_root, &profile_name, profile)
        {
            mark_auth_skip(plan, &profile_name, &format!("{e:#}"), quiet);
            continue;
        }
        if !quiet {
            println!("auth preflight: profile `{profile_name}` ready");
        }
    }
    Ok(())
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
