use std::collections::BTreeSet;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::process::Command;
use std::sync::mpsc;

use anyhow::Result;
use cherry_test_common::adapter::ExecutionMode;
use cherry_test_core::{
    boot_compose_in, boot_scope_in, teardown_scopes_in, Environment, Jobs, ParallelConfig,
    ScopeState,
};

use crate::adapters::process::cancelled;
use crate::discover::ProjectGroup;
use crate::plan::{Job, Plan, Resources};
use crate::preflight::PreflightPlan;
use crate::runner::{execute_job, job_report_dir, JobOutcome, RunOptions};

/// Times another job may start ahead of the queue's head before the head reserves the next free capacity.
pub const HEAD_SKIP_LIMIT: u32 = 3;

/// How many jobs run at once, the CPU and memory they share, and whether phases wait for each other.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Parallelism {
    pub workers: usize,
    pub budget: Resources,
    pub phase_barrier: bool,
}

impl Default for Parallelism {
    fn default() -> Self {
        Self {
            workers: 1,
            budget: Resources {
                millicpus: u64::MAX,
                memory: u64::MAX,
            },
            phase_barrier: true,
        }
    }
}

impl Parallelism {
    /// Resolves `--jobs` over `defaults.parallel.jobs` (default 1).
    ///
    /// One job keeps today's sequential run and asks nothing of the host. Otherwise the budget comes from
    /// `defaults.parallel`, then from `docker info` when execution is container, then from the host's CPUs with
    /// no memory limit; `auto` starts one job per budgeted CPU.
    pub fn resolve(
        cli: Option<Jobs>,
        config: Option<&ParallelConfig>,
        execution: ExecutionMode,
        env: &Environment,
    ) -> Self {
        let jobs = cli
            .or(config.and_then(|config| config.jobs))
            .unwrap_or(Jobs::Count(1));
        if jobs == Jobs::Count(1) {
            return Self::default();
        }
        let cpus = config.and_then(|config| config.cpus).map(|cpus| cpus.0);
        let memory = config
            .and_then(|config| config.memory)
            .map(|memory| memory.0);
        let docker =
            if execution == ExecutionMode::Container && (cpus.is_none() || memory.is_none()) {
                docker_resources(env)
            } else {
                None
            };
        let host_cpus = std::thread::available_parallelism()
            .map(|count| count.get() as u64)
            .unwrap_or(1);
        let budget = Resources {
            millicpus: cpus
                .or(docker.map(|docker| docker.millicpus))
                .unwrap_or(host_cpus * 1000),
            memory: memory
                .or(docker.map(|docker| docker.memory))
                .unwrap_or(u64::MAX),
        };
        let workers = match jobs {
            Jobs::Auto => (budget.millicpus / 1000).max(1) as usize,
            Jobs::Count(count) => count as usize,
        };
        Self {
            workers,
            budget,
            phase_barrier: config.is_none_or(|config| config.phase_barrier),
        }
    }
}

/// CPUs and memory of the Docker engine, from `docker info`.
fn docker_resources(env: &Environment) -> Option<Resources> {
    let output = Command::new("docker")
        .env_clear()
        .envs(env)
        .args(["info", "--format", "{{.NCPU}} {{.MemTotal}}"])
        .output()
        .ok()?;
    if !output.status.success() {
        return None;
    }
    parse_docker_resources(&String::from_utf8_lossy(&output.stdout))
}

fn parse_docker_resources(text: &str) -> Option<Resources> {
    let mut fields = text.split_whitespace();
    let cpus: u64 = fields.next()?.parse().ok()?;
    let memory: u64 = fields.next()?.parse().ok()?;
    (cpus > 0 && memory > 0).then_some(Resources {
        millicpus: cpus * 1000,
        memory,
    })
}

/// A job waiting for, or holding, a share of the budget.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Candidate {
    pub slot: usize,
    pub resources: Resources,
    pub exclusive: bool,
}

/// Picks the pending job to start next, or `None` when nothing may start now.
///
/// The head of `pending` starts when it fits. Otherwise the first smaller job that fits starts in its place,
/// until the head has been passed over [`HEAD_SKIP_LIMIT`] times; from then on the head reserves the capacity
/// and nothing else starts until it does. An exclusive job starts only on an idle scheduler and runs alone; a
/// job larger than the whole budget also starts only when nothing else runs.
pub fn next_start(
    pending: &[Candidate],
    running: &[Candidate],
    budget: Resources,
    workers: usize,
    head_skips: &mut u32,
) -> Option<usize> {
    if pending.is_empty() || running.len() >= workers.max(1) {
        return None;
    }
    if running.iter().any(|job| job.exclusive) {
        return None;
    }
    let used_cpus: u64 = running.iter().map(|job| job.resources.millicpus).sum();
    let used_memory: u64 = running.iter().map(|job| job.resources.memory).sum();
    let fits = |job: &Candidate| {
        running.is_empty()
            || !job.exclusive
                && used_cpus.saturating_add(job.resources.millicpus) <= budget.millicpus
                && used_memory.saturating_add(job.resources.memory) <= budget.memory
    };
    if fits(&pending[0]) {
        *head_skips = 0;
        return Some(0);
    }
    if *head_skips >= HEAD_SKIP_LIMIT {
        return None;
    }
    let next = pending.iter().skip(1).position(fits)? + 1;
    *head_skips += 1;
    Some(next)
}

/// Queue order for parallel execution: longest estimate first, configuration order on ties.
pub fn longest_first(jobs: &[(usize, &Job)]) -> Vec<usize> {
    let mut order: Vec<usize> = (0..jobs.len()).collect();
    order.sort_by(|a, b| {
        jobs[*b]
            .1
            .estimate_ms
            .cmp(&jobs[*a].1.estimate_ms)
            .then(jobs[*a].0.cmp(&jobs[*b].0))
    });
    order
}

/// What a run accumulates while it executes. The run owns it; nothing here is process-global.
pub struct RunState {
    pub scopes: ScopeState,
    pub preflight: PreflightPlan,
    service_env: Environment,
    auth_env: Environment,
    phase_booted: BTreeSet<String>,
    /// Scopes whose Compose services this phase started for jobs that run their image services themselves.
    phase_compose: BTreeSet<String>,
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
            phase_compose: BTreeSet::new(),
            auth_ready: false,
        }
    }

    fn start_phase(&mut self) {
        self.phase_booted.clear();
        self.phase_compose.clear();
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

/// Jobs that share one boot and teardown of scopes: a phase, or the whole run without a phase barrier.
struct Stage<'p> {
    last_phase: &'static str,
    jobs: Vec<(usize, &'p Job)>,
}

fn stages<'p>(plan: &'p Plan, parallelism: &Parallelism) -> Vec<Stage<'p>> {
    let mut stages: Vec<Stage<'p>> = Vec::new();
    let mut position = 0;
    let merge = parallelism.workers > 1 && !parallelism.phase_barrier;
    for phase in &plan.phases {
        let jobs: Vec<(usize, &Job)> = phase
            .jobs
            .iter()
            .map(|job| {
                position += 1;
                (position - 1, job)
            })
            .collect();
        match stages.last_mut() {
            Some(stage) if merge => {
                stage.last_phase = phase.phase;
                stage.jobs.extend(jobs);
            }
            _ => stages.push(Stage {
                last_phase: phase.phase,
                jobs,
            }),
        }
    }
    stages
}

/// Executes the plan and returns each finished job with its position in the plan.
///
/// With one worker, jobs run one at a time in plan order, and scopes boot right before the first job that needs
/// them. With more workers, every scope of a stage boots first, sequentially and in plan order, then jobs start
/// longest first within the budget. Scopes stop at the end of each stage; a stage is one phase unless the phase
/// barrier is off.
pub fn run(
    plan: &Plan,
    projects: &[ProjectGroup],
    opts: &RunOptions<'_>,
    preflight: &PreflightPlan,
) -> Result<Vec<(usize, JobOutcome)>> {
    let layers = EnvLayers::load(opts, projects);
    let mut state = RunState::new(preflight.clone());
    let mut finished = Vec::new();
    for stage in stages(plan, &opts.parallelism) {
        state.start_phase();
        if opts.parallelism.workers <= 1 {
            for (position, job) in &stage.jobs {
                if cancelled() {
                    continue;
                }
                let setup = setup_job(job, &mut state, &layers, opts);
                let env = layers.job_env(&state, &job.key.phase, &job.key.project);
                let outcome = execute_job(job, &env, &state.preflight, setup, opts)?;
                opts.run_dir
                    .merge_job_report(&job_report_dir(&job.request))?;
                finished.push((*position, outcome));
            }
        } else {
            let mut prepared = Vec::new();
            for (position, job) in &stage.jobs {
                if cancelled() {
                    break;
                }
                let setup = setup_job(job, &mut state, &layers, opts);
                prepared.push(Prepared {
                    position: *position,
                    job,
                    setup,
                });
            }
            let mut stage_finished = run_parallel(&prepared, &state, &layers, opts)?;
            stage_finished.sort_by_key(|(position, _)| *position);
            for (position, _) in &stage_finished {
                if let Some(item) = prepared.iter().find(|item| item.position == *position) {
                    opts.run_dir
                        .merge_job_report(&job_report_dir(&item.job.request))?;
                }
            }
            finished.extend(stage_finished);
        }
        let env = layers.phase_env(&state, stage.last_phase);
        teardown_scopes_in(&mut state.scopes, &env)?;
        if cancelled() {
            break;
        }
    }
    Ok(finished)
}

/// A job whose scopes were booted before the stage started, with the outcome of that setup.
struct Prepared<'p> {
    position: usize,
    job: &'p Job,
    setup: std::result::Result<(), String>,
}

/// Runs prepared jobs on worker threads within the budget and returns them as they finish.
///
/// After the first job error no new job starts; running jobs finish and the error is returned.
fn run_parallel(
    prepared: &[Prepared<'_>],
    state: &RunState,
    layers: &EnvLayers<'_>,
    opts: &RunOptions<'_>,
) -> Result<Vec<(usize, JobOutcome)>> {
    let ordered: Vec<(usize, &Job)> = prepared
        .iter()
        .map(|item| (item.position, item.job))
        .collect();
    let mut pending: Vec<Candidate> = longest_first(&ordered)
        .into_iter()
        .map(|slot| Candidate {
            slot,
            resources: prepared[slot].job.resources,
            exclusive: prepared[slot].job.exclusive,
        })
        .collect();
    let parallelism = opts.parallelism;
    std::thread::scope(|scope| {
        let (sender, receiver) = mpsc::channel::<(usize, Result<JobOutcome>)>();
        let mut running: Vec<Candidate> = Vec::new();
        let mut head_skips = 0;
        let mut finished = Vec::new();
        let mut failure: Option<anyhow::Error> = None;
        loop {
            while failure.is_none() && !cancelled() {
                let Some(next) = next_start(
                    &pending,
                    &running,
                    parallelism.budget,
                    parallelism.workers,
                    &mut head_skips,
                ) else {
                    break;
                };
                let candidate = pending.remove(next);
                running.push(candidate);
                let item = &prepared[candidate.slot];
                let env = layers.job_env(state, &item.job.key.phase, &item.job.key.project);
                let sender = sender.clone();
                scope.spawn(move || {
                    let result = catch_unwind(AssertUnwindSafe(|| {
                        execute_job(item.job, &env, &state.preflight, item.setup.clone(), opts)
                    }))
                    .unwrap_or_else(|_| Err(anyhow::anyhow!("job {} panicked", item.job.key.id())));
                    let _ = sender.send((candidate.slot, result));
                });
            }
            if running.is_empty() {
                break;
            }
            let Ok((slot, result)) = receiver.recv() else {
                break;
            };
            running.retain(|job| job.slot != slot);
            match result {
                Ok(outcome) => finished.push((prepared[slot].position, outcome)),
                Err(error) => {
                    failure.get_or_insert(error);
                }
            }
        }
        match failure {
            Some(error) => Err(error),
            None => Ok(finished),
        }
    })
}

/// Boots the job's scopes not yet started in this phase, then runs the auth preflight once per integration phase.
fn setup_job(
    job: &Job,
    state: &mut RunState,
    layers: &EnvLayers<'_>,
    opts: &RunOptions<'_>,
) -> std::result::Result<(), String> {
    let own_network = job
        .request
        .container
        .as_ref()
        .is_some_and(|spec| spec.sidecars.is_some());
    let mut setup = || -> Result<()> {
        for scope in &job.scopes {
            if state.phase_booted.contains(scope) {
                continue;
            }
            if own_network {
                if !state.phase_compose.contains(scope) {
                    let env = layers.phase_env(state, &job.key.phase);
                    let boot = boot_compose_in(scope, &mut state.scopes, &env)?;
                    state.service_env.extend(boot.exports);
                    state.phase_compose.insert(scope.clone());
                }
            } else {
                let env = layers.phase_env(state, &job.key.phase);
                let boot = boot_scope_in(scope, &mut state.scopes, &env)?;
                state.service_env.extend(boot.exports);
                state.phase_booted.insert(scope.clone());
            }
        }
        let booted = !state.phase_booted.is_empty() || !state.phase_compose.is_empty();
        if job.key.phase == "integration" && !state.auth_ready && booted {
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

    fn job(slot: usize, cpus: u64, exclusive: bool) -> Candidate {
        Candidate {
            slot,
            resources: Resources {
                millicpus: cpus * 1000,
                memory: 1 << 30,
            },
            exclusive,
        }
    }

    fn budget(cpus: u64) -> Resources {
        Resources {
            millicpus: cpus * 1000,
            memory: 64 << 30,
        }
    }

    /// Runs `jobs` (cpus, exclusive, duration) through [`next_start`] in simulated time; returns (slot, start, end).
    fn simulate(jobs: &[(u64, bool, u64)], cpus: u64, workers: usize) -> Vec<(usize, u64, u64)> {
        let mut pending: Vec<Candidate> = jobs
            .iter()
            .enumerate()
            .map(|(slot, (cpus, exclusive, _))| job(slot, *cpus, *exclusive))
            .collect();
        let mut running: Vec<(Candidate, u64)> = Vec::new();
        let mut timeline = Vec::new();
        let mut now = 0;
        let mut skips = 0;
        while !pending.is_empty() || !running.is_empty() {
            loop {
                let busy: Vec<Candidate> = running.iter().map(|(job, _)| *job).collect();
                let Some(next) = next_start(&pending, &busy, budget(cpus), workers, &mut skips)
                else {
                    break;
                };
                let started = pending.remove(next);
                let end = now + jobs[started.slot].2;
                timeline.push((started.slot, now, end));
                running.push((started, end));
                let used: u64 = running.iter().map(|(job, _)| job.resources.millicpus).sum();
                assert!(running.len() == 1 || used <= cpus * 1000, "budget exceeded");
                assert!(running.len() <= workers);
            }
            let Some(first_end) = running.iter().map(|(_, end)| *end).min() else {
                break;
            };
            now = first_end;
            running.retain(|(_, end)| *end > now);
        }
        timeline.sort();
        timeline
    }

    #[test]
    fn next_start_takes_the_head_when_it_fits() {
        let pending = [job(0, 2, false), job(1, 1, false)];
        let mut skips = 2;
        assert_eq!(next_start(&pending, &[], budget(4), 4, &mut skips), Some(0));
        assert_eq!(skips, 0);
        assert_eq!(
            next_start(&pending, &[job(9, 1, false)], budget(4), 1, &mut skips),
            None
        );
        assert_eq!(next_start(&[], &[], budget(4), 4, &mut skips), None);
    }

    #[test]
    fn a_smaller_job_starts_ahead_until_the_head_reserves() {
        let head = job(0, 2, false);
        let smalls: Vec<Candidate> = (1..=5).map(|slot| job(slot, 1, false)).collect();
        let big = job(9, 3, false);
        let mut pending = vec![head];
        pending.extend(smalls.iter().copied());
        let mut skips = 0;
        assert_eq!(
            next_start(&pending, &[big], budget(4), 4, &mut skips),
            Some(1)
        );
        assert_eq!(skips, 1);
        pending.remove(1);
        assert_eq!(
            next_start(&pending, &[big, smalls[0]], budget(4), 4, &mut skips),
            None
        );
        assert_eq!(skips, 1);
        assert_eq!(
            next_start(&pending, &[big], budget(4), 4, &mut skips),
            Some(1)
        );
        pending.remove(1);
        assert_eq!(
            next_start(&pending, &[big], budget(4), 4, &mut skips),
            Some(1)
        );
        pending.remove(1);
        assert_eq!(skips, HEAD_SKIP_LIMIT);
        assert_eq!(next_start(&pending, &[big], budget(4), 4, &mut skips), None);
        assert_eq!(next_start(&pending, &[], budget(4), 4, &mut skips), Some(0));
        assert_eq!(skips, 0);
    }

    #[test]
    fn exclusive_and_oversized_jobs_run_alone() {
        let mut skips = 0;
        let exclusive = job(0, 1, true);
        assert_eq!(
            next_start(&[exclusive], &[job(5, 1, false)], budget(8), 8, &mut skips),
            None
        );
        assert_eq!(
            next_start(&[exclusive], &[], budget(8), 8, &mut skips),
            Some(0)
        );
        assert_eq!(
            next_start(&[job(1, 1, false)], &[exclusive], budget(8), 8, &mut skips),
            None
        );
        let oversized = job(2, 16, false);
        assert_eq!(
            next_start(&[oversized], &[], budget(8), 8, &mut skips),
            Some(0)
        );
        assert_eq!(
            next_start(&[job(3, 1, false)], &[oversized], budget(8), 8, &mut skips),
            None
        );
        let timeline = simulate(&[(1, false, 10), (1, true, 10), (1, false, 10)], 8, 8);
        assert_eq!(timeline, [(0, 0, 10), (1, 10, 20), (2, 0, 10)]);
    }

    #[test]
    fn simulated_runs_stay_within_the_budget_and_never_starve_the_head() {
        let jobs = [
            (3, false, 50),
            (4, false, 40),
            (1, false, 10),
            (1, false, 10),
            (1, false, 10),
            (1, false, 10),
            (1, false, 10),
            (1, false, 10),
        ];
        let timeline = simulate(&jobs, 4, 4);
        assert_eq!(timeline.len(), jobs.len());
        let head = timeline.iter().find(|(slot, _, _)| *slot == 1).unwrap();
        assert_eq!(head.1, 50);
        let before_head = timeline
            .iter()
            .filter(|(slot, start, _)| *slot >= 2 && *start < 50)
            .count();
        assert_eq!(before_head, HEAD_SKIP_LIMIT as usize);
        let sequential = simulate(&jobs, 4, 1);
        let mut clock = 0;
        for (slot, start, end) in sequential {
            assert_eq!(start, clock, "job {slot}");
            clock = end;
        }
    }

    #[test]
    fn orders_jobs_longest_first_with_configuration_order_on_ties() {
        let (root, config, projects) = crate::plan::tests::fixture();
        let run_dir =
            crate::run_dir::RunDir::create(root.path(), std::path::Path::new("runs")).unwrap();
        let opts = crate::plan::tests::options(root.path(), &run_dir, &config, &[]);
        let mut plan = crate::plan::plan(&projects, &opts, &PreflightPlan::default()).unwrap();
        let estimates = [100, 300, 300];
        for (job, estimate) in plan
            .phases
            .iter_mut()
            .flat_map(|phase| phase.jobs.iter_mut())
            .zip(estimates)
        {
            job.estimate_ms = estimate;
        }
        let jobs: Vec<(usize, &Job)> = plan.jobs().enumerate().collect();
        assert_eq!(longest_first(&jobs), [1, 2, 0]);
    }

    #[test]
    fn one_stage_per_phase_unless_the_barrier_is_off() {
        let (root, config, projects) = crate::plan::tests::fixture();
        let run_dir =
            crate::run_dir::RunDir::create(root.path(), std::path::Path::new("runs")).unwrap();
        let opts = crate::plan::tests::options(root.path(), &run_dir, &config, &[]);
        let plan = crate::plan::plan(&projects, &opts, &PreflightPlan::default()).unwrap();
        let barrier = stages(
            &plan,
            &Parallelism {
                workers: 4,
                ..Default::default()
            },
        );
        assert_eq!(barrier.len(), plan.phases.len());
        assert_eq!(barrier[1].jobs[0].0, 2);
        let merged = stages(
            &plan,
            &Parallelism {
                workers: 4,
                phase_barrier: false,
                ..Default::default()
            },
        );
        assert_eq!(merged.len(), 1);
        assert_eq!(merged[0].last_phase, "visual");
        let positions: Vec<usize> = merged[0]
            .jobs
            .iter()
            .map(|(position, _)| *position)
            .collect();
        assert_eq!(positions, [0, 1, 2]);
        let sequential = stages(
            &plan,
            &Parallelism {
                phase_barrier: false,
                ..Default::default()
            },
        );
        assert_eq!(sequential.len(), plan.phases.len());
    }

    #[test]
    fn resolve_keeps_one_job_unless_asked_and_honours_the_configured_budget() {
        let env = Environment::new();
        assert_eq!(
            Parallelism::resolve(None, None, ExecutionMode::Container, &env),
            Parallelism::default()
        );
        let config = ParallelConfig {
            jobs: Some(Jobs::Auto),
            cpus: Some(cherry_test_core::Cpus(6500)),
            memory: Some(cherry_test_core::Memory(8 << 30)),
            phase_barrier: false,
        };
        let auto = Parallelism::resolve(None, Some(&config), ExecutionMode::Container, &env);
        assert_eq!(auto.workers, 6);
        assert_eq!(
            auto.budget,
            Resources {
                millicpus: 6500,
                memory: 8 << 30
            }
        );
        assert!(!auto.phase_barrier);
        let counted = Parallelism::resolve(
            Some(Jobs::Count(3)),
            Some(&config),
            ExecutionMode::Local,
            &env,
        );
        assert_eq!(counted.workers, 3);
        assert_eq!(
            Parallelism::resolve(
                Some(Jobs::Count(1)),
                Some(&config),
                ExecutionMode::Local,
                &env
            ),
            Parallelism::default()
        );
        let host = Parallelism::resolve(Some(Jobs::Count(2)), None, ExecutionMode::Local, &env);
        assert_eq!(host.budget.memory, u64::MAX);
        assert!(host.budget.millicpus >= 1000);
        assert!(host.phase_barrier);
    }

    #[test]
    fn parses_docker_info_resources() {
        assert_eq!(
            parse_docker_resources("10 8321515520\n"),
            Some(Resources {
                millicpus: 10_000,
                memory: 8_321_515_520
            })
        );
        assert_eq!(parse_docker_resources(""), None);
        assert_eq!(parse_docker_resources("0 10"), None);
        assert_eq!(parse_docker_resources("four 10"), None);
    }
}
