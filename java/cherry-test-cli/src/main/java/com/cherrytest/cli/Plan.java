package com.cherrytest.cli;

import com.cherrytest.cli.adapters.Container;
import com.cherrytest.common.adapter.AdapterRequest;
import com.cherrytest.common.adapter.AdapterResponse;
import com.cherrytest.common.container.ContainerLabels;
import com.cherrytest.common.container.ContainerSpec;
import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.common.rustcompat.RustOrdering;
import com.cherrytest.core.ExternalRunner;
import com.cherrytest.core.Memory;
import com.cherrytest.core.PackageConfig;
import com.cherrytest.core.ResourcesConfig;
import com.cherrytest.core.Scope;
import com.cherrytest.core.Sidecars;
import com.cherrytest.core.TestItConfig;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/** Every phase a run executes, in phase order, each holding its jobs in configuration order. */
public record Plan(List<PhasePlan> phases) {
    public Plan {
        phases = List.copyOf(phases);
    }

    /** One package phase: the unit the scheduler starts, times and reports. */
    public record JobKey(String project, String packageName, String phase) implements Comparable<JobKey> {
        /** {@code project/package/phase}, the key used in {@code durations.json}. */
        public String id() {
            return project + "/" + packageName + "/" + phase;
        }

        @Override
        public int compareTo(JobKey other) {
            int byProject = RustOrdering.compareStrings(project, other.project);
            if (byProject != 0) {
                return byProject;
            }
            int byPackage = RustOrdering.compareStrings(packageName, other.packageName);
            return byPackage != 0 ? byPackage : RustOrdering.compareStrings(phase, other.phase);
        }
    }

    /** CPU, in thousandths of a CPU, and memory, in bytes, that a job reserves while it runs. */
    public record Resources(long millicpus, long memory) {
        public static final Resources PACKAGE_DEFAULT = new Resources(1000, 1L << 30);
    }

    /**
     * One adapter invocation, fully described before anything runs. {@code request.env} stays empty: the scheduler
     * fills it from the job's environment when the job starts.
     */
    public record Job(
            JobKey key,
            String adapter,
            AdapterRequest request,
            List<String> scopes,
            Resources resources,
            long estimateMs,
            boolean exclusive,
            @Nullable String containerError,
            List<String> provisioned) {
        /**
         * {@code provisioned}: the job's scopes that declare image services. The runner provides them, so the test
         * process reads their endpoints from {@code CHERRY_TEST_SCOPE_PROVISIONED} and its environment.
         */
        public Job {
            scopes = List.copyOf(scopes);
            provisioned = List.copyOf(provisioned);
        }

        /** The same job with another estimate. */
        public Job withEstimate(long replacement) {
            return new Job(
                    key, adapter, request, scopes, resources, replacement, exclusive, containerError, provisioned);
        }
    }

    /** The jobs of one phase, in configuration order. */
    public record PhasePlan(String phase, List<Job> jobs) {
        public PhasePlan {
            jobs = List.copyOf(jobs);
        }
    }

    public Stream<Job> jobs() {
        return phases.stream().flatMap(phase -> phase.jobs().stream());
    }

    /** Builds the run's jobs without starting a process, booting a scope or touching the environment. */
    public static Plan plan(List<ProjectGroup> projects, Runner.RunOptions options, PreflightPlan preflight) {
        for (ProjectGroup project : projects) {
            for (WorkspaceMember member : project.members()) {
                for (String kind : options.config().packageKinds(member.name(), project.id())) {
                    if (TestKind.parse(kind) == null) {
                        throw new AnyhowError("unknown test kind " + kind);
                    }
                }
            }
        }
        List<PhasePlan> phases = new ArrayList<>();
        for (String phase : Runner.phasesFor(options.kinds())) {
            if (!options.kinds().isEmpty() && !options.kinds().contains(phase)) {
                continue;
            }
            List<Job> jobs = new ArrayList<>();
            for (ProjectGroup project : projects) {
                for (WorkspaceMember member : project.members()) {
                    PackageConfig configured = options.config().packageConfig(project.id(), member.name());
                    boolean automatic = phase.equals("mutation") && Runner.automaticMutation(configured);
                    boolean selected = options.config()
                            .packageKinds(member.name(), project.id())
                            .contains(phase);
                    var mutation = configured.options().get("mutation");
                    if (mutation != null && !mutation.isBoolean())
                        throw new AnyhowError("options.mutation must be a boolean");
                    if ((phase.equals("mutation") && mutation != null && !mutation.booleanValue())
                            || (!selected && !automatic)
                            || (phase.equals("mutation") && options.kinds().isEmpty() && !automatic)) {
                        continue;
                    }
                    jobs.add(job(options, preflight, project.id(), member, phase));
                }
            }
            if (!phase.equals("mutation") || !jobs.isEmpty()) phases.add(new PhasePlan(phase, jobs));
        }
        return new Plan(phases);
    }

    private static Job job(
            Runner.RunOptions options,
            PreflightPlan preflight,
            String projectId,
            WorkspaceMember member,
            String phase) {
        PackageConfig packageConfig = options.config().packageConfig(projectId, member.name());
        ExternalRunner runner = phaseRunner(options, packageConfig, projectId, member.name(), phase);
        String adapter = Runner.phaseAdapter(phase, runner, packageConfig);
        Path cwd;
        if (runner != null && runner.cwd() != null) {
            cwd = options.workspaceRoot().resolve(runner.cwd());
        } else {
            Path manifest = options.workspaceRoot().resolve(member.manifestPath());
            cwd = manifest.getParent() == null ? options.workspaceRoot() : manifest.getParent();
        }
        Path output = options.runDir()
                .reports()
                .resolve("adapters")
                .resolve(projectId)
                .resolve(member.name())
                .resolve(phase);
        SortedMap<String, JsonNode> requestOptions = new TreeMap<>(RustOrdering.STRING_ORDER);
        requestOptions.putAll(packageConfig.options());
        if (runner != null) {
            requestOptions.putAll(runner.options());
            if (!runner.command().isEmpty()) {
                requestOptions.put("command", JsonNodeFactory.instance.stringNode(runner.command()));
            }
        }
        if (adapter.equals("pitest")) {
            String buildTool = packageConfig.adapter();
            if (!"maven".equals(buildTool) && !"gradle".equals(buildTool)) {
                throw new AnyhowError("PIT requires a Maven or Gradle package adapter");
            }
            String diffBase = options.config().diffBaseForProject(projectId);
            requestOptions.put("mutation_tool", JsonNodeFactory.instance.stringNode("pitest"));
            requestOptions.put("mutation_build_tool", JsonNodeFactory.instance.stringNode(buildTool));
            requestOptions.put("mutation_diff_base", JsonNodeFactory.instance.stringNode(diffBase));
        }
        List<String> args = new ArrayList<>(runner == null ? List.of() : runner.args());
        if (adapter.equals("cargo")) {
            args.addAll(options.extraTestArgs());
        }
        AdapterRequest request = new AdapterRequest(
                AdapterResponse.PROTOCOL_VERSION,
                projectId,
                member.name(),
                phase,
                options.workspaceRoot(),
                options.workspaceRoot().resolve(member.manifestPath()),
                cwd,
                output,
                Runner.phaseExecution(
                        phase,
                        packageConfig.execution(),
                        options.config().defaults().execution()),
                Runner.phaseCollectsCoverage(phase, options.coverage()),
                options.config().defaults().strict(),
                args,
                new TreeMap<>(RustOrdering.STRING_ORDER),
                requestOptions);
        ContainerSpec containerSpec = null;
        String containerError = null;
        try {
            containerSpec = Container.resolve(
                    options.config().defaults().container(),
                    packageConfig.container(),
                    request,
                    options.runDir().id(),
                    options.runDir().root());
        } catch (RuntimeException error) {
            containerError = AnyhowError.alternate(error);
        }
        List<String> scopes = new ArrayList<>();
        if (phase.equals("integration") && Runner.discoversScopes(adapter)) {
            for (DiscoveredTest test : preflight.discovered()) {
                if (test.packageName().equals(member.name())
                        && preflight.scopesToBoot().contains(test.effectiveScope())) {
                    scopes.add(test.effectiveScope());
                }
            }
        }
        if (runner != null && runner.scope() != null && Scope.needsDocker(runner.scope())) {
            scopes.add(runner.scope());
        }
        List<String> provisioned = provisionedScopes(options.config(), scopes);
        if (containerSpec != null) {
            try {
                containerSpec = attachSidecars(options, containerSpec, scopes);
            } catch (RuntimeException error) {
                if (containerError == null) {
                    containerError = AnyhowError.alternate(error);
                }
            }
        }
        request = request.withContainer(containerSpec);
        return new Job(
                new JobKey(projectId, member.name(), phase),
                adapter,
                request,
                scopes,
                jobResources(packageConfig.resources(), containerSpec),
                0,
                packageConfig.exclusive(),
                containerError,
                provisioned);
    }

    /** Gives a container job its private network when its scopes select image services. */
    private static ContainerSpec attachSidecars(Runner.RunOptions options, ContainerSpec spec, List<String> scopes) {
        List<Sidecars.ImageService> services = Sidecars.imageServices(options.config(), scopes);
        return spec.withSidecars(Sidecars.jobSidecars(options.workspaceRoot(), spec.name(), services, spec.pull()));
    }

    /**
     * A fresh job that reruns one test of {@code job} alone: its own output, container name, writable directories and
     * image services, no coverage, not strict, and the narrowed selection.
     */
    static Job rerunJob(Job job, Isolation.Narrowing narrowing, Path output, String suffix, Runner.RunOptions options) {
        AdapterRequest planned = job.request();
        List<String> args = planned.args();
        List<String> select = planned.select();
        switch (narrowing) {
            case Isolation.Narrowing.Args(List<String> replacement) -> args = replacement;
            case Isolation.Narrowing.Select(List<String> names) -> select = names;
            case Isolation.Narrowing.Unsupported ignored -> {}
        }
        AdapterRequest request = planned.forRerun(output, args, select);
        String containerError = job.containerError();
        if (planned.container() != null) {
            PackageConfig packageConfig = options.config()
                    .packageConfig(job.key().project(), job.key().packageName());
            ContainerSpec spec = null;
            try {
                spec = Container.resolve(
                        options.config().defaults().container(),
                        packageConfig.container(),
                        request.withContainer(null),
                        options.runDir().id(),
                        options.runDir().root().resolve("isolation").resolve(suffix));
                if (spec != null) {
                    spec = spec.withName(ContainerLabels.sanitizeName(spec.name() + "-" + suffix));
                    try {
                        spec = attachSidecars(options, spec, job.scopes());
                    } catch (RuntimeException error) {
                        if (containerError == null) {
                            containerError = AnyhowError.alternate(error);
                        }
                    }
                }
            } catch (RuntimeException error) {
                spec = null;
                if (containerError == null) {
                    containerError = AnyhowError.alternate(error);
                }
            }
            request = request.withContainer(spec);
        }
        return new Job(
                job.key(),
                job.adapter(),
                request,
                job.scopes(),
                job.resources(),
                job.estimateMs(),
                job.exclusive(),
                containerError,
                job.provisioned());
    }

    /** The scopes, each once and in order, that select at least one image service. */
    public static List<String> provisionedScopes(TestItConfig config, List<String> scopes) {
        List<String> provisioned = new ArrayList<>();
        for (String scope : scopes) {
            boolean hasImages;
            try {
                hasImages = !Sidecars.imageServices(config, List.of(scope)).isEmpty();
            } catch (RuntimeException unknownScope) {
                hasImages = false;
            }
            if (hasImages && !provisioned.contains(scope)) {
                provisioned.add(scope);
            }
        }
        return provisioned;
    }

    /** What a job reserves: the package's {@code resources}, else its container's limits, else 1 CPU and 1g. */
    public static Resources jobResources(@Nullable ResourcesConfig configured, @Nullable ContainerSpec container) {
        Long containerCpus = null;
        Long containerMemory = null;
        if (container != null && container.cpus() != null) {
            try {
                double cpus = Double.parseDouble(container.cpus());
                long millicpus = Math.round(cpus * 1000.0);
                if (Double.isFinite(cpus) && millicpus >= 1) {
                    containerCpus = millicpus;
                }
            } catch (NumberFormatException notANumber) {
                containerCpus = null;
            }
        }
        if (container != null && container.memory() != null) {
            try {
                containerMemory = Memory.parse(container.memory());
            } catch (AnyhowError notASize) {
                containerMemory = null;
            }
        }
        Long millicpus = configured == null ? null : configured.millicpus();
        Long memory = configured == null ? null : configured.memory();
        return new Resources(
                millicpus != null
                        ? millicpus
                        : containerCpus != null ? containerCpus : Resources.PACKAGE_DEFAULT.millicpus(),
                memory != null
                        ? memory
                        : containerMemory != null ? containerMemory : Resources.PACKAGE_DEFAULT.memory());
    }

    private static @Nullable ExternalRunner phaseRunner(
            Runner.RunOptions options,
            PackageConfig packageConfig,
            String projectId,
            String packageName,
            String phase) {
        return switch (phase) {
            case "unit" -> packageConfig.unit();
            case "integration" -> packageConfig.integration();
            case "functional" -> options.config().functionalRunner(packageName, projectId);
            case "ui" -> options.config().uiRunner(packageName, projectId);
            case "visual" -> packageConfig.visual();
            case "mutation" -> packageConfig.mutation();
            default -> null;
        };
    }
}
