use std::collections::BTreeSet;

use anyhow::Result;
use cherry_test_core::{boot_scope_in, teardown_scopes_in, Environment, ScopeState};

use crate::adapters::process::cancelled;
use crate::discover::ProjectGroup;
use crate::plan::{Job, Plan};
use crate::preflight::PreflightPlan;
use crate::runner::{execute_job, JobOutcome, RunOptions};

/// What a run accumulates while it executes. The run owns it; nothing here is process-global.
pub struct RunState {
    pub scopes: ScopeState,
    pub preflight: PreflightPlan,
    service_env: Environment,
    auth_env: Environment,
    phase_booted: BTreeSet<String>,
    auth_ready: bool,
}

impl RunState {
    pub fn new(preflight: PreflightPlan) -> Self {
        Self {
            scopes: ScopeState::default(),
            preflight,
            service_env: Environment::new(),
            auth_env: Environment::new(),
            phase_booted: BTreeSet::new(),
            auth_ready: false,
        }
    }

    fn start_phase(&mut self) {
        self.phase_booted.clear();
        self.auth_ready = false;
    }
}

/// The base environment and every selected project's `.env.test` variables, in configuration order.
pub struct EnvLayers<'a> {
    pub base: &'a Environment,
    pub env_files: Vec<(String, Environment)>,
}

impl<'a> EnvLayers<'a> {
    pub fn load(opts: &'a RunOptions<'_>, projects: &[ProjectGroup]) -> Self {
        Self {
            base: &opts.env,
            env_files: projects
                .iter()
                .map(|project| {
                    (
                        project.id.clone(),
                        crate::run_env::env_file(opts.workspace_root, &project.id),
                    )
                })
                .collect(),
        }
    }

    /// What a job's adapter receives: the base, its phase, its project's `.env.test`, then service and auth variables.
    pub fn job_env(&self, state: &RunState, phase: &str, project: &str) -> Environment {
        let mut env = self.base.clone();
        env.insert("CHERRY_TEST_PHASE".into(), phase.into());
        if let Some((_, vars)) = self.env_files.iter().find(|(id, _)| id == project) {
            env.extend(vars.clone());
        }
        env.extend(state.service_env.clone());
        env.extend(state.auth_env.clone());
        env
    }

    /// What Docker Compose and the auth preflight receive during a phase: every project's `.env.test` applies.
    pub fn phase_env(&self, state: &RunState, phase: &str) -> Environment {
        let mut env = self.base.clone();
        env.insert("CHERRY_TEST_PHASE".into(), phase.into());
        for (_, vars) in &self.env_files {
            env.extend(vars.clone());
        }
        env.extend(state.service_env.clone());
        env.extend(state.auth_env.clone());
        env
    }
}

/// Executes the plan phase by phase and returns each finished job with its position in the plan.
///
/// Jobs run one at a time in plan order. Scopes boot right before the first job that needs them and stop
/// at the end of the phase.
pub fn run(
    plan: &Plan,
    projects: &[ProjectGroup],
    opts: &RunOptions<'_>,
    preflight: &PreflightPlan,
) -> Result<Vec<(usize, JobOutcome)>> {
    let layers = EnvLayers::load(opts, projects);
    let mut state = RunState::new(preflight.clone());
    let mut finished = Vec::new();
    let mut order = 0;
    for phase in &plan.phases {
        state.start_phase();
        for job in &phase.jobs {
            let position = order;
            order += 1;
            if cancelled() {
                continue;
            }
            let setup = setup_job(job, &mut state, &layers, opts);
            let env = layers.job_env(&state, phase.phase, &job.key.project);
            let outcome = execute_job(job, &env, &state.preflight, setup, opts)?;
            finished.push((position, outcome));
        }
        let env = layers.phase_env(&state, phase.phase);
        teardown_scopes_in(&mut state.scopes, &env)?;
        if cancelled() {
            break;
        }
    }
    Ok(finished)
}

/// Boots the job's scopes not yet started in this phase, then runs the auth preflight once per integration phase.
fn setup_job(
    job: &Job,
    state: &mut RunState,
    layers: &EnvLayers<'_>,
    opts: &RunOptions<'_>,
) -> std::result::Result<(), String> {
    let mut setup = || -> Result<()> {
        for scope in &job.scopes {
            if !state.phase_booted.contains(scope) {
                let env = layers.phase_env(state, &job.key.phase);
                let boot = boot_scope_in(scope, &mut state.scopes, &env)?;
                state.service_env.extend(boot.exports);
                state.phase_booted.insert(scope.clone());
            }
        }
        if job.key.phase == "integration" && !state.auth_ready && !state.phase_booted.is_empty() {
            let env = layers.phase_env(state, &job.key.phase);
            let exports = crate::auth_preflight::run_auth_preflight(
                opts.workspace_root,
                opts.config,
                &mut state.preflight,
                opts.quiet,
                &env,
            )?;
            state.auth_env.extend(exports);
            state.auth_ready = true;
        }
        Ok(())
    };
    setup().map_err(|error| format!("{error:#}"))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn vars(pairs: &[(&str, &str)]) -> Environment {
        pairs
            .iter()
            .map(|(key, value)| (key.to_string(), value.to_string()))
            .collect()
    }

    #[test]
    fn job_env_layers_phase_project_file_services_and_auth() {
        let base = vars(&[("PATH", "/bin"), ("CHERRY_TEST_RUN_ID", "run")]);
        let layers = EnvLayers {
            base: &base,
            env_files: vec![
                ("mini".into(), vars(&[("CHERRY_TEST_MINI_DB_NAME", "mini")])),
                ("shop".into(), vars(&[("CHERRY_TEST_SHOP_DB_NAME", "shop")])),
            ],
        };
        let mut state = RunState::new(PreflightPlan::default());
        state.service_env = vars(&[("CHERRY_TEST_MINI_API_URL", "http://127.0.0.1:4100")]);
        state.auth_env = vars(&[("CHERRY_TEST_AUTH_OKTA_ACCESS_TOKEN", "token")]);
        let env = layers.job_env(&state, "integration", "mini");
        assert_eq!(env["CHERRY_TEST_PHASE"], "integration");
        assert_eq!(env["CHERRY_TEST_MINI_DB_NAME"], "mini");
        assert!(!env.contains_key("CHERRY_TEST_SHOP_DB_NAME"));
        assert_eq!(env["CHERRY_TEST_MINI_API_URL"], "http://127.0.0.1:4100");
        assert_eq!(env["CHERRY_TEST_AUTH_OKTA_ACCESS_TOKEN"], "token");
        assert_eq!(env["PATH"], "/bin");
        let phase = layers.phase_env(&state, "integration");
        assert_eq!(phase["CHERRY_TEST_SHOP_DB_NAME"], "shop");
        assert_eq!(phase["CHERRY_TEST_MINI_DB_NAME"], "mini");
        assert_eq!(base.len(), 2);
    }
}
