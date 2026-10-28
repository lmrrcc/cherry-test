use std::collections::BTreeMap;

use anyhow::{bail, Result};
use cherry_test_common::adapter::{AdapterRequest, PROTOCOL_VERSION};
use cherry_test_common::container::ContainerSpec;
use cherry_test_core::{Memory, ResourcesConfig};

use crate::config::{ExternalRunner, TestKind};
use crate::discover::ProjectGroup;
use crate::preflight::PreflightPlan;
use crate::runner::{discovers_scopes, phase_adapter, phase_collects_coverage, phase_execution};
use crate::runner::{phases_for, RunOptions};

/// One package phase: the unit the scheduler starts, times and reports.
#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct JobKey {
    pub project: String,
    pub package: String,
    pub phase: String,
}

impl JobKey {
    pub fn new(project: &str, package: &str, phase: &str) -> Self {
        Self {
            project: project.into(),
            package: package.into(),
            phase: phase.into(),
        }
    }

    /// `project/package/phase`, the key used in `durations.json`.
    pub fn id(&self) -> String {
        format!("{}/{}/{}", self.project, self.package, self.phase)
    }
}

/// CPU, in thousandths of a CPU, and memory, in bytes, that a job reserves while it runs.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Resources {
    pub millicpus: u64,
    pub memory: u64,
}

impl Resources {
    pub const PACKAGE_DEFAULT: Resources = Resources {
        millicpus: 1000,
        memory: 1 << 30,
    };
}

/// One adapter invocation, fully described before anything runs.
///
/// `request.env` stays empty here: the scheduler fills it from the job's environment when the job starts.
#[derive(Debug, Clone)]
pub struct Job {
    pub key: JobKey,
    pub adapter: String,
    pub request: AdapterRequest,
    pub scopes: Vec<String>,
    pub resources: Resources,
    pub estimate_ms: u64,
    pub exclusive: bool,
    /// Why the job's `container:` settings could not be resolved; the job then fails without starting.
    pub container_error: Option<String>,
}

/// The jobs of one phase, in configuration order.
#[derive(Debug, Clone)]
pub struct PhasePlan {
    pub phase: &'static str,
    pub jobs: Vec<Job>,
}

/// Every phase a run executes, in phase order, each holding its jobs in configuration order.
#[derive(Debug, Clone, Default)]
pub struct Plan {
    pub phases: Vec<PhasePlan>,
}

impl Plan {
    pub fn jobs(&self) -> impl Iterator<Item = &Job> {
        self.phases.iter().flat_map(|phase| phase.jobs.iter())
    }
}

/// Builds the run's jobs without starting a process, booting a scope or touching the environment.
pub fn plan(
    projects: &[ProjectGroup],
    opts: &RunOptions<'_>,
    preflight: &PreflightPlan,
) -> Result<Plan> {
    for project in projects {
        for member in &project.members {
            for kind in opts.config.package_kinds(&member.name, &project.id) {
                if TestKind::parse(&kind).is_none() {
                    bail!("unknown test kind {kind}");
                }
            }
        }
    }
    let mut phases = Vec::new();
    for phase in phases_for(opts.kinds) {
        if !opts.kinds.is_empty() && !opts.kinds.iter().any(|kind| kind == phase) {
            continue;
        }
        let mut jobs = Vec::new();
        for project in projects {
            for member in &project.members {
                let configured = opts.config.package_kinds(&member.name, &project.id);
                if !configured.iter().any(|kind| kind == phase) {
                    continue;
                }
                jobs.push(job(opts, preflight, &project.id, member, phase));
            }
        }
        phases.push(PhasePlan { phase, jobs });
    }
    Ok(Plan { phases })
}

fn job(
    opts: &RunOptions<'_>,
    preflight: &PreflightPlan,
    project_id: &str,
    member: &crate::discover::WorkspaceMember,
    phase: &'static str,
) -> Job {
    let package = &opts.config.projects[project_id].packages[&member.name];
    let runner = phase_runner(opts, project_id, &member.name, phase);
    let adapter = phase_adapter(phase, runner, package).to_string();
    let cwd = runner
        .and_then(|r| r.cwd.as_ref())
        .map(|p| opts.workspace_root.join(p))
        .unwrap_or_else(|| {
            opts.workspace_root
                .join(&member.manifest_path)
                .parent()
                .unwrap_or(opts.workspace_root)
                .to_path_buf()
        });
    let output = opts
        .run_dir
        .reports
        .join("adapters")
        .join(project_id)
        .join(&member.name)
        .join(phase);
    let mut options = package.options.clone();
    if let Some(runner) = runner {
        options.extend(runner.options.clone());
        if !runner.command.is_empty() {
            options.insert("command".into(), runner.command.clone().into());
        }
    }
    let mut args = runner.map(|r| r.args.clone()).unwrap_or_default();
    if adapter == "cargo" {
        args.extend_from_slice(opts.extra_test_args);
    }
    let mut request = AdapterRequest {
        protocol: PROTOCOL_VERSION,
        project: project_id.to_string(),
        package: member.name.clone(),
        phase: phase.into(),
        root: opts.workspace_root.to_path_buf(),
        manifest: opts.workspace_root.join(&member.manifest_path),
        cwd,
        output,
        execution: phase_execution(phase, package.execution, opts.config.defaults.execution),
        coverage: phase_collects_coverage(phase, opts.coverage),
        strict: opts.config.defaults.strict,
        args,
        env: BTreeMap::new(),
        options,
        container: None,
    };
    let container_error = match crate::adapters::container::resolve(
        opts.config.defaults.container.as_ref(),
        package.container.as_ref(),
        &request,
        &opts.run_dir.id,
        &opts.run_dir.root,
    ) {
        Ok(spec) => {
            request.container = spec;
            None
        }
        Err(error) => Some(format!("{error:#}")),
    };
    let mut scopes = Vec::new();
    if phase == "integration" && discovers_scopes(&adapter) {
        scopes.extend(
            preflight
                .discovered
                .iter()
                .filter(|t| {
                    t.package == member.name
                        && preflight.scopes_to_boot.contains(&t.effective_scope)
                })
                .map(|t| t.effective_scope.clone()),
        );
    }
    if let Some(scope) = runner.and_then(|r| r.scope.as_ref()) {
        if cherry_test_core::needs_docker(scope) {
            scopes.push(scope.clone());
        }
    }
    let resources = job_resources(package.resources.as_ref(), request.container.as_ref());
    Job {
        key: JobKey::new(project_id, &member.name, phase),
        adapter,
        request,
        scopes,
        resources,
        estimate_ms: 0,
        exclusive: package.exclusive,
        container_error,
    }
}

/// What a job reserves: the package's `resources`, else the `cpus`/`memory` limits of its container, else 1 CPU and 1g.
pub fn job_resources(
    configured: Option<&ResourcesConfig>,
    container: Option<&ContainerSpec>,
) -> Resources {
    let container_cpus = container
        .and_then(|spec| spec.cpus.as_deref())
        .and_then(|text| text.parse::<f64>().ok())
        .map(|cpus| (cpus * 1000.0).round())
        .filter(|millicpus| millicpus.is_finite() && *millicpus >= 1.0)
        .map(|millicpus| millicpus as u64);
    let container_memory = container
        .and_then(|spec| spec.memory.as_deref())
        .and_then(|text| Memory::parse(text).ok())
        .map(|memory| memory.0);
    Resources {
        millicpus: configured
            .and_then(|resources| resources.cpus)
            .map(|cpus| cpus.0)
            .or(container_cpus)
            .unwrap_or(Resources::PACKAGE_DEFAULT.millicpus),
        memory: configured
            .and_then(|resources| resources.memory)
            .map(|memory| memory.0)
            .or(container_memory)
            .unwrap_or(Resources::PACKAGE_DEFAULT.memory),
    }
}

fn phase_runner<'a>(
    opts: &'a RunOptions<'_>,
    project_id: &str,
    package_name: &str,
    phase: &str,
) -> Option<&'a ExternalRunner> {
    let package = &opts.config.projects[project_id].packages[package_name];
    match phase {
        "unit" => package.unit.as_ref(),
        "integration" => package.integration.as_ref(),
        "functional" => opts.config.functional_runner(package_name, project_id),
        "ui" => opts.config.ui_runner(package_name, project_id),
        "visual" => package.visual.as_ref(),
        "mutation" => package.mutation.as_ref(),
        _ => None,
    }
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    use crate::config::{Config, PackageConfig, ProjectConfig};
    use crate::discover::WorkspaceMember;
    use crate::run_dir::RunDir;
    use crate::runner::TestListing;
    use cherry_test_core::PackageRuntime;
    use std::path::PathBuf;

    pub(crate) fn fixture() -> (tempfile::TempDir, Config, Vec<ProjectGroup>) {
        let root = tempfile::tempdir().unwrap();
        let mut config = Config::default();
        let mut shop = ProjectConfig::default();
        shop.packages.insert(
            "api".into(),
            PackageConfig {
                kinds: vec!["unit".into(), "integration".into()],
                integration: Some(ExternalRunner {
                    scope: Some("checkout".into()),
                    ..Default::default()
                }),
                ..Default::default()
            },
        );
        shop.packages.insert(
            "web".into(),
            PackageConfig {
                runtime: PackageRuntime::Node,
                kinds: vec!["unit".into()],
                ..Default::default()
            },
        );
        config.projects.insert("shop".into(), shop);
        let projects = vec![ProjectGroup {
            id: "shop".into(),
            members: ["api", "web"]
                .into_iter()
                .map(|name| WorkspaceMember {
                    name: name.into(),
                    manifest_path: PathBuf::from(format!("shop/{name}/Cargo.toml")),
                    project_id: "shop".into(),
                    runtime: PackageRuntime::Rust,
                })
                .collect(),
        }];
        (root, config, projects)
    }

    pub(crate) fn options<'a>(
        root: &'a std::path::Path,
        run_dir: &'a RunDir,
        config: &'a Config,
        kinds: &'a [String],
    ) -> RunOptions<'a> {
        RunOptions {
            workspace_root: root,
            run_dir,
            config,
            coverage: true,
            quiet: true,
            test_listing: TestListing::AfterEachPhase,
            extra_test_args: &[],
            kinds,
            env: Default::default(),
            parallelism: Default::default(),
        }
    }

    #[test]
    fn plans_jobs_phase_by_phase_in_configuration_order() {
        let (root, config, projects) = fixture();
        let run_dir = RunDir::create(root.path(), std::path::Path::new("runs")).unwrap();
        let opts = options(root.path(), &run_dir, &config, &[]);
        let plan = plan(&projects, &opts, &PreflightPlan::default()).unwrap();
        let phases: Vec<_> = plan.phases.iter().map(|phase| phase.phase).collect();
        assert_eq!(
            phases,
            ["unit", "integration", "functional", "ui", "visual"]
        );
        let ids: Vec<_> = plan.jobs().map(|job| job.key.id()).collect();
        assert_eq!(
            ids,
            ["shop/api/unit", "shop/web/unit", "shop/api/integration"]
        );
        let integration = &plan.phases[1].jobs[0];
        assert_eq!(integration.adapter, "cargo");
        assert_eq!(integration.scopes, ["checkout"]);
        assert!(integration.request.env.is_empty());
        assert!(integration
            .request
            .output
            .ends_with("reports/adapters/shop/api/integration"));
        assert_eq!(plan.phases[0].jobs[1].adapter, "vitest");
        assert_eq!(plan.phases[0].jobs[1].resources, Resources::PACKAGE_DEFAULT);
    }

    #[test]
    fn plans_only_the_selected_kinds() {
        let (root, config, projects) = fixture();
        let run_dir = RunDir::create(root.path(), std::path::Path::new("runs")).unwrap();
        let kinds = vec!["integration".to_string()];
        let opts = options(root.path(), &run_dir, &config, &kinds);
        let plan = plan(&projects, &opts, &PreflightPlan::default()).unwrap();
        assert_eq!(plan.phases.len(), 1);
        assert_eq!(plan.phases[0].jobs.len(), 1);
    }

    #[test]
    fn rejects_an_unknown_kind_before_anything_runs() {
        let (root, mut config, projects) = fixture();
        config
            .projects
            .get_mut("shop")
            .unwrap()
            .packages
            .get_mut("web")
            .unwrap()
            .kinds
            .push("smoke".into());
        let run_dir = RunDir::create(root.path(), std::path::Path::new("runs")).unwrap();
        let opts = options(root.path(), &run_dir, &config, &[]);
        let error = plan(&projects, &opts, &PreflightPlan::default()).unwrap_err();
        assert_eq!(error.to_string(), "unknown test kind smoke");
    }

    #[test]
    fn container_limits_set_job_resources_and_resolve_errors_fail_setup() {
        let (root, mut config, projects) = fixture();
        config.defaults.execution = cherry_test_common::adapter::ExecutionMode::Container;
        config.defaults.container =
            Some(serde_yaml::from_str("image: rust:1\ncpus: \"2.5\"\nmemory: 512m\n").unwrap());
        let shop = config.projects.get_mut("shop").unwrap();
        shop.packages.get_mut("web").unwrap().resources = Some(ResourcesConfig {
            cpus: Some(cherry_test_core::Cpus(1500)),
            memory: None,
        });
        shop.packages.get_mut("api").unwrap().container =
            Some(serde_yaml::from_str("user: \"bad user\"\n").unwrap());
        let run_dir = RunDir::create(root.path(), std::path::Path::new("runs")).unwrap();
        let opts = options(root.path(), &run_dir, &config, &[]);
        let plan = plan(&projects, &opts, &PreflightPlan::default()).unwrap();
        let api = &plan.phases[0].jobs[0];
        assert!(api.request.container.is_none());
        assert!(api
            .container_error
            .as_deref()
            .is_some_and(|error| error.contains("container.user")));
        let web = &plan.phases[0].jobs[1];
        assert!(web.container_error.is_none());
        assert!(web.request.container.is_some());
        assert_eq!(
            web.resources,
            Resources {
                millicpus: 1500,
                memory: 512 << 20
            }
        );
        assert_eq!(
            job_resources(None, web.request.container.as_ref()),
            Resources {
                millicpus: 2500,
                memory: 512 << 20
            }
        );
        assert_eq!(job_resources(None, None), Resources::PACKAGE_DEFAULT);
    }
}
