package com.cherrytest.cli;

import com.cherrytest.cli.adapters.Container;
import com.cherrytest.common.adapter.AdapterRequest;
import com.cherrytest.common.adapter.AdapterResponse;
import com.cherrytest.common.container.ContainerSpec;
import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.common.rustcompat.RustOrdering;
import com.cherrytest.core.ExternalRunner;
import com.cherrytest.core.Memory;
import com.cherrytest.core.PackageConfig;
import com.cherrytest.core.ResourcesConfig;
import com.cherrytest.core.Scope;
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
            @Nullable String containerError) {
        public Job {
            scopes = List.copyOf(scopes);
        }

        /** The same job with another estimate. */
        public Job withEstimate(long replacement) {
            return new Job(key, adapter, request, scopes, resources, replacement, exclusive, containerError);
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
                    if (!options.config()
                            .packageKinds(member.name(), project.id())
                            .contains(phase)) {
                        continue;
                    }
                    jobs.add(job(options, preflight, project.id(), member, phase));
                }
            }
            phases.add(new PhasePlan(phase, jobs));
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
        request = request.withContainer(containerSpec);
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
        return new Job(
                new JobKey(projectId, member.name(), phase),
                adapter,
                request,
                scopes,
                jobResources(packageConfig.resources(), containerSpec),
                0,
                packageConfig.exclusive(),
                containerError);
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
