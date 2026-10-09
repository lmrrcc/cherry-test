use std::future::Future;

use anyhow::Result;
use futures_util::FutureExt;

use crate::env::TestEnv;
use crate::naming::{parse_test_name, TestId};
use crate::report::append_records;
use crate::scope::{effective_scope, is_none};
use crate::workspace::boot_scope;

pub async fn run<F, Fut>(
    full_name: &'static str,
    package: &'static str,
    attr_scope: Option<&'static str>,
    attr_auth: Option<&'static str>,
    f: F,
) -> Result<()>
where
    F: FnOnce(TestEnv) -> Fut,
    Fut: Future<Output = Result<()>>,
{
    let test_id = parse_test_name(full_name)?;
    validate_project(&test_id)?;

    let root = crate::workspace::workspace_root()?;
    let config = crate::config::WorkspaceConfig::load(&root)?;
    let scope = effective_scope(&config, attr_scope);

    let registry = if is_none(&scope) {
        crate::workspace::boot_scope("none")?
    } else {
        boot_scope(&scope)?
    };

    let auth_profile = attr_auth.map(str::to_string);
    if let Some(ref profile_name) = auth_profile {
        let profile = config.auth_profile(profile_name)?;
        crate::auth::ensure_profile_token(&root, profile_name, profile).await?;
    }
    let env = TestEnv::new(test_id, scope, auth_profile, registry);
    let outcome: Result<()> = match std::panic::AssertUnwindSafe(f(env.clone()))
        .catch_unwind()
        .await
    {
        Ok(inner) => inner,
        Err(panic) => Err(anyhow::anyhow!(panic_message(panic))),
    };
    let passed = outcome.is_ok();
    let failure_reason = outcome.as_ref().err().map(|err| format!("{err:#}"));
    let mut records = env.drain_records();
    for record in &mut records {
        record.passed = passed;
        record.failure_reason = failure_reason.clone();
    }
    append_records(package, &records)?;
    outcome
}

fn panic_message(panic: Box<dyn std::any::Any + Send>) -> String {
    if let Some(message) = panic.downcast_ref::<&str>() {
        (*message).to_string()
    } else if let Some(message) = panic.downcast_ref::<String>() {
        message.clone()
    } else {
        "test panicked".to_string()
    }
}

fn validate_project(test_id: &TestId) -> Result<()> {
    let root = crate::workspace::workspace_root()?;
    let config = crate::config::WorkspaceConfig::load(&root)?;
    config.project(&test_id.project)?;
    Ok(())
}
