package com.cherrytest.cli;

import com.cherrytest.cli.adapters.Adapters;
import com.cherrytest.cli.adapters.Builtins;
import com.cherrytest.cli.adapters.Container;
import com.cherrytest.cli.adapters.Formats;
import com.cherrytest.cli.adapters.ProcessRunner;
import com.cherrytest.cli.coverage.Coverage;
import com.cherrytest.cli.coverage.CoverageIgnore;
import com.cherrytest.cli.coverage.CoveragePaths;
import com.cherrytest.cli.nativeos.Terminal;
import com.cherrytest.cli.report.ProjectRunResult;
import com.cherrytest.cli.report.ReportOutput;
import com.cherrytest.common.adapter.AdapterRequest;
import com.cherrytest.common.adapter.AdapterResponse;
import com.cherrytest.common.adapter.ExecutionMode;
import com.cherrytest.common.adapter.TestResult;
import com.cherrytest.common.adapter.TestStatus;
import com.cherrytest.common.container.ContainerSpec;
import com.cherrytest.common.container.SidecarService;
import com.cherrytest.common.container.SidecarSpec;
import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.common.rustcompat.RustIoError;
import com.cherrytest.common.rustcompat.RustOrdering;
import com.cherrytest.common.rustcompat.RustPath;
import com.cherrytest.common.rustcompat.RustText;
import com.cherrytest.core.ExternalRunner;
import com.cherrytest.core.PackageConfig;
import com.cherrytest.core.PackageRuntime;
import com.cherrytest.core.Sidecars;
import com.cherrytest.core.TestItConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

public final class Runner {
    private Runner() {}

    public enum TestListing {
        AFTER_EACH_PHASE,
        AT_END_OF_RUN
    }

    public record RunOptions(
            Path workspaceRoot,
            RunDir runDir,
            TestItConfig config,
            boolean coverage,
            boolean quiet,
            TestListing testListing,
            List<String> extraTestArgs,
            List<String> kinds,
            Adapters.SelfCommand self,
            Map<String, String> env,
            Scheduler.Parallelism parallelism,
            Isolation.@Nullable Settings isolate,
            @Nullable NativeEvents nativeEvents) {
        public RunOptions(
                Path workspaceRoot,
                RunDir runDir,
                TestItConfig config,
                boolean coverage,
                boolean quiet,
                TestListing testListing,
                List<String> extraTestArgs,
                List<String> kinds,
                Adapters.SelfCommand self,
                Map<String, String> env,
                Scheduler.Parallelism parallelism,
                Isolation.@Nullable Settings isolate) {
            this(
                    workspaceRoot,
                    runDir,
                    config,
                    coverage,
                    quiet,
                    testListing,
                    extraTestArgs,
                    kinds,
                    self,
                    env,
                    parallelism,
                    isolate,
                    null);
        }

        public RunOptions {
            SortedMap<String, String> base = new TreeMap<>(RustOrdering.STRING_ORDER);
            base.putAll(env);
            env = base;
        }

        public RunOptions(
                Path workspaceRoot,
                RunDir runDir,
                TestItConfig config,
                boolean coverage,
                boolean quiet,
                TestListing testListing,
                List<String> extraTestArgs,
                List<String> kinds,
                Adapters.SelfCommand self,
                Map<String, String> env,
                Scheduler.Parallelism parallelism) {
            this(
                    workspaceRoot,
                    runDir,
                    config,
                    coverage,
                    quiet,
                    testListing,
                    extraTestArgs,
                    kinds,
                    self,
                    env,
                    parallelism,
                    null);
        }
    }

    /** Keeps each job's terminal block together when jobs run at once. */
    private static final Object OUTPUT = new Object();

    /** What one finished job contributes to the run's results. */
    public record JobOutcome(
            Plan.JobKey key, boolean success, @Nullable Path lcov, Duration elapsed) {}

    private record PackageKey(String project, String packageName) implements Comparable<PackageKey> {
        @Override
        public int compareTo(PackageKey other) {
            int project = RustOrdering.compareStrings(this.project, other.project);
            return project != 0 ? project : RustOrdering.compareStrings(packageName, other.packageName);
        }
    }

    public static RunOutcome runProjects(List<ProjectGroup> projects, RunOptions options, PreflightPlan preflight) {
        Plan plan = Plan.plan(projects, options, preflight);
        NativeEvents nativeEvents = options.nativeEvents();
        if (nativeEvents != null) plan = nativeEvents.preparePlan(plan);
        if (options.parallelism().workers() > 1) {
            plan = Durations.load(Durations.path(
                            options.workspaceRoot(), options.config().defaults().runsBase()))
                    .estimate(plan);
        }
        List<Scheduler.Finished> finished = Scheduler.run(plan, projects, options, preflight);
        try {
            Isolation.run(plan, projects, options, preflight);
        } catch (RuntimeException error) {
            Terminal.eprintln("warning: failed-test isolation stopped: " + AnyhowError.alternate(error));
        }
        return aggregate(options, finished);
    }

    /** What invoking one job's adapter produced, before coverage and reports. */
    record Invocation(
            AdapterRequest request,
            AdapterResponse response,
            @Nullable String infrastructure) {}

    /**
     * Runs one planned job: completes its request from the job environment, invokes the adapter and saves the
     * result. A non-null {@code setupError} fails the job without starting its adapter.
     */
    static JobOutcome executeJob(
            Plan.Job job,
            Map<String, String> environment,
            PreflightPlan preflight,
            @Nullable String setupError,
            RunOptions options) {
        long started = System.nanoTime();
        String adapter = job.adapter();
        String phase = job.key().phase();
        String packageName = job.key().packageName();
        if (!options.quiet()) {
            synchronized (OUTPUT) {
                Terminal.println("==> " + job.key().project() + "/" + packageName + "/" + phase + " (" + adapter + ")");
            }
        }
        Invocation invocation = invokeJob(job, environment, preflight, setupError, options);
        AdapterRequest request = invocation.request();
        AdapterResponse response = invocation.response();
        if (discoversScopes(adapter) && phase.equals("integration")) {
            recordSkips(response, preflight, packageName, request.strict());
        }
        if (request.coverage()) {
            try {
                com.cherrytest.cli.adapters.ExternalCoverage.export(request, response);
            } catch (RuntimeException failure) {
                response.success(false);
                response.coverage().clear();
                response.diagnostics().add("Server coverage export failed: " + AnyhowError.alternate(failure));
            }
        }
        CoveragePaths paths = new CoveragePaths(options.runDir(), job.key().project());
        paths.ensureDirs();
        Path lcovFile = null;
        if (!response.coverage().isEmpty()) {
            Path lcov = paths.lcovDir().resolve(packageName + "-" + phase + ".lcov");
            try {
                normalizeCoverage(request, response.coverage(), lcov);
                lcovFile = lcov;
            } catch (RuntimeException error) {
                response.success(false);
                response.diagnostics().add("coverage import failed: " + AnyhowError.alternate(error));
            }
        }
        NativeEvents events = options.nativeEvents();
        if (events != null)
            events.phaseCompleted(request, adapter, adapter, response, invocation.infrastructure() == null);
        AdapterResponse saved =
                Adapters.save(request, response, options.runDir().reports());
        if (!options.quiet()) {
            synchronized (OUTPUT) {
                if (options.testListing() == TestListing.AFTER_EACH_PHASE) {
                    ReportOutput.printPhaseResults(saved.tests());
                }
                if (!saved.success()) {
                    Terminal.eprintln("  failed: " + failureSummary(saved));
                }
            }
        }
        return new JobOutcome(job.key(), saved.success(), lcovFile, Duration.ofNanos(System.nanoTime() - started));
    }

    /**
     * Completes the job's request from {@code environment} and invokes its adapter, with its image services when it
     * has any. Nothing is printed, no coverage is read and no report is written.
     */
    static Invocation invokeJob(
            Plan.Job job,
            Map<String, String> environment,
            PreflightPlan preflight,
            @Nullable String setupError,
            RunOptions options) {
        String adapter = job.adapter();
        String phase = job.key().phase();
        String packageName = job.key().packageName();
        AdapterRequest planned = job.request();
        List<String> args = new ArrayList<>(planned.args());
        if (adapter.equals("cargo") && phase.equals("integration")) {
            for (SkippedTest skip : preflight.skipped()) {
                if (skip.packageName().equals(packageName)) {
                    args.add("--skip");
                    args.add(skip.function());
                }
            }
        }
        if (adapter.equals("maven") && phase.equals("integration")) {
            String excludes = mavenSkipExcludes(preflight, packageName);
            if (excludes != null) {
                Path file = planned.output().resolve("cherry-test-skips.txt");
                try {
                    Files.createDirectories(planned.output());
                    Files.writeString(file, excludes, StandardCharsets.UTF_8);
                } catch (IOException exception) {
                    throw new AnyhowError(RustIoError.display(exception), null);
                }
                args.add("-Dfailsafe.excludesFile=" + file);
            }
        }
        Path reportDir = jobReportDir(planned);
        try {
            Files.createDirectories(reportDir);
        } catch (IOException exception) {
            throw AnyhowError.context("create " + reportDir, new AnyhowError(RustIoError.display(exception)));
        }
        SortedMap<String, String> jobEnvironment = new TreeMap<>(RustOrdering.STRING_ORDER);
        jobEnvironment.putAll(environment);
        jobEnvironment.put("CHERRY_TEST_REPORT_DIR", reportDir.toString());
        jobEnvironment.put("CHERRY_TEST_PACKAGE", planned.packageName());
        extendWithProvisioned(jobEnvironment, job);
        if (planned.coverage() && !adapter.equals("maven") && !adapter.equals("gradle")) {
            String collection = java.util.UUID.randomUUID().toString();
            jobEnvironment.put("CHERRY_TEST_COVERAGE_COLLECTION", collection);
            jobEnvironment.put("CHERRY_TEST_PROVENANCE_TOKEN", collection);
            jobEnvironment.put(
                    "CHERRY_TEST_PROVENANCE_DIR",
                    planned.output().resolve("provenance").toString());
        }

        NativeEvents events = options.nativeEvents();
        Path progress = planned.output().resolve("native-test-events");
        if (events != null && events.testProgressEnabled())
            jobEnvironment.put("CHERRY_TEST_IDE_TEST_EVENTS", progress.toString());

        AdapterRequest request = new AdapterRequest(
                planned.protocol(),
                planned.project(),
                planned.packageName(),
                planned.phase(),
                planned.root(),
                planned.manifest(),
                planned.cwd(),
                planned.output(),
                planned.execution(),
                planned.coverage(),
                planned.strict(),
                args,
                RunEnv.requestEnv(jobEnvironment),
                planned.options(),
                planned.container(),
                planned.select());
        if (events != null) events.phaseStarted(request, adapter, adapter);
        String failure = setupError != null ? setupError : job.containerError();
        if (failure != null) {
            return new Invocation(request, AdapterResponse.failure(failure), failure);
        }
        try {
            AdapterResponse response;
            if (events != null && events.testProgressEnabled()) {
                try (NativeProgress monitor = new NativeProgress(events, request, adapter, progress)) {
                    monitor.start();
                    response = invokeWithSidecars(adapter, request, jobEnvironment, options);
                }
            } else {
                response = invokeWithSidecars(adapter, request, jobEnvironment, options);
            }
            if (events != null) events.validateSelection(request, response);
            return new Invocation(request, response, null);
        } catch (RuntimeException error) {
            String message = AnyhowError.alternate(error);
            return new Invocation(request, AdapterResponse.failure(message), message);
        }
    }

    /**
     * Adds what the runner provides for the job's scopes: {@code CHERRY_TEST_SCOPE_PROVISIONED}, and the
     * {@code 127.0.0.1} endpoints of the image services in the job's own network.
     */
    static void extendWithProvisioned(Map<String, String> environment, Plan.Job job) {
        if (!job.provisioned().isEmpty()) {
            environment.put(Sidecars.SCOPE_PROVISIONED_ENV, String.join(",", job.provisioned()));
        }
        ContainerSpec spec = job.request().container();
        SidecarSpec sidecars = spec == null ? null : spec.sidecars();
        if (sidecars != null) {
            for (SidecarService service : sidecars.services()) {
                Sidecars.sidecarEnv(service)
                        .forEach(variable -> environment.put(variable.getKey(), variable.getValue()));
            }
        }
    }

    /**
     * Invokes the adapter; a job with image services gets its private network first and loses it afterwards, also
     * when the adapter fails, times out or the run is cancelled. A service that does not start or become healthy
     * fails the job before the adapter starts.
     */
    private static AdapterResponse invokeWithSidecars(
            String adapter, AdapterRequest request, Map<String, String> environment, RunOptions options) {
        ContainerSpec spec = request.container();
        SidecarSpec sidecars = spec == null ? null : spec.sidecars();
        if (spec == null || sidecars == null) {
            return Adapters.invoke(
                    adapter, options.config().adapters().get(adapter), request, options.self(), environment);
        }
        Sidecars.Labels labels = new Sidecars.Labels(spec.run(), spec.job(), Container.ownerPid());
        try {
            Files.createDirectories(request.output());
        } catch (IOException exception) {
            throw AnyhowError.context("create " + request.output(), new AnyhowError(RustIoError.display(exception)));
        }
        try {
            Sidecars.startJobNetwork(
                    environment,
                    sidecars,
                    spec.image(),
                    spec.pull(),
                    spec.network(),
                    labels,
                    request.output(),
                    ProcessRunner::cancelled);
        } catch (RuntimeException error) {
            throw AnyhowError.context("image services of " + spec.job() + " did not start", error);
        }
        AdapterResponse response = null;
        RuntimeException failure = null;
        try {
            response = Adapters.invoke(
                    adapter, options.config().adapters().get(adapter), request, options.self(), environment);
        } catch (RuntimeException error) {
            failure = error;
        }
        try {
            Sidecars.stopJobNetwork(environment, sidecars, request.output());
        } catch (RuntimeException error) {
            String message =
                    "could not remove the image services of " + spec.job() + ": " + AnyhowError.alternate(error);
            if (failure != null) {
                throw new AnyhowError(AnyhowError.alternate(failure) + "; " + message, null);
            }
            if (response != null) {
                response.success(false);
                response.diagnostics().add(message);
            }
        }
        if (failure != null) {
            throw failure;
        }
        if (response == null) {
            throw new AnyhowError("adapter `" + adapter + "` returned no response");
        }
        return response;
    }

    /**
     * The job's own {@code CHERRY_TEST_REPORT_DIR}: {@code report/} in its adapter output, writable in hardened
     * containers. The scheduler merges it into the run's report directory in plan order once the job has finished.
     */
    static Path jobReportDir(AdapterRequest request) {
        return request.output().resolve("report");
    }

    /** Folds finished jobs, taken in plan order, into per-package results and coverage lists. */
    static RunOutcome aggregate(RunOptions options, List<Scheduler.Finished> input) {
        List<Scheduler.Finished> finished = new ArrayList<>(input);
        finished.sort(Comparator.comparingInt(Scheduler.Finished::order));
        TreeMap<PackageKey, Boolean> statuses = new TreeMap<>();
        TreeMap<PackageKey, List<Path>> coverage = new TreeMap<>();
        List<Path> unit = new ArrayList<>();
        List<Path> integration = new ArrayList<>();
        List<Map.Entry<Plan.JobKey, Duration>> timings = new ArrayList<>();
        for (Scheduler.Finished entry : finished) {
            JobOutcome outcome = entry.outcome();
            PackageKey key =
                    new PackageKey(outcome.key().project(), outcome.key().packageName());
            Path lcov = outcome.lcov();
            if (lcov != null) {
                coverage.computeIfAbsent(key, ignored -> new ArrayList<>()).add(lcov);
                if (outcome.key().phase().equals("unit")) {
                    unit.add(lcov);
                } else if (outcome.key().phase().equals("integration")) {
                    integration.add(lcov);
                }
            }
            statuses.merge(key, outcome.success(), (existing, incoming) -> existing && incoming);
            timings.add(Map.entry(outcome.key(), outcome.elapsed()));
        }
        CoverageIgnore ignore = CoverageIgnore.load(options.workspaceRoot(), options.config());
        List<Path> lcovFiles = new ArrayList<>();
        List<ProjectRunResult> results = new ArrayList<>();
        for (Map.Entry<PackageKey, Boolean> status : statuses.entrySet()) {
            PackageKey key = status.getKey();
            CoveragePaths paths = new CoveragePaths(options.runDir(), key.project());
            List<Path> files = coverage.get(key);
            if (files != null) {
                Path merged = paths.lcovFile(key.packageName());
                Coverage.mergeLcovFiles(files, merged);
                lcovFiles.add(merged);
            }
            results.add(new ProjectRunResult(
                    key.project(),
                    key.packageName(),
                    status.getValue(),
                    ReportOutput.packageCoverageFromPaths(paths, key.packageName(), ignore)));
        }
        boolean failed = ProcessRunner.cancelled()
                || results.stream().anyMatch(result -> !result.testsPassed())
                || (options.config().defaults().strict() && results.isEmpty());
        return new RunOutcome(results, lcovFiles, unit, integration, failed, timings);
    }

    static void normalizeCoverage(AdapterRequest request, List<Path> files, Path output) {
        StringBuilder result = new StringBuilder();
        for (Path file : files) {
            String text;
            try {
                text = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException exception) {
                throw new AnyhowError(RustIoError.display(exception), null);
            }
            boolean include = true;
            for (String line : RustText.lines(text)) {
                if (line.startsWith("SF:")) {
                    Path path = Builtins.sourcePath(request, line.substring(3));
                    Path canonical;
                    try {
                        canonical = path.toRealPath();
                    } catch (IOException exception) {
                        throw AnyhowError.context(
                                "coverage source " + path, new AnyhowError(RustIoError.display(exception)));
                    }
                    include = Adapters.sourceAllowed(request, canonical);
                    if (include) {
                        Path relative = RustPath.stripPrefix(
                                canonical, request.root().toAbsolutePath().normalize());
                        result.append("SF:")
                                .append(relative == null ? canonical : relative)
                                .append('\n');
                    }
                } else if (include) {
                    result.append(line).append('\n');
                }
            }
        }
        if (RustText.lines(result.toString()).stream().noneMatch(line -> line.startsWith("DA:"))) {
            throw new AnyhowError("no measured source lines inside selected workspace");
        }
        try {
            Files.writeString(output, result.toString(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new AnyhowError(RustIoError.display(exception), null);
        }
        Coverage.mergeLcovFiles(List.of(output), output);
    }

    /** Adapters whose integration tests declare their scope in the source: {@code #[cherry_test]} (Cargo), {@code @CherryTest} (Maven). */
    static boolean discoversScopes(String adapter) {
        return adapter.equals("cargo") || adapter.equals("maven");
    }

    /** Failsafe exclusions for the scoped tests preflight skipped: one {@code Class#method} per line. */
    static @Nullable String mavenSkipExcludes(PreflightPlan preflight, String packageName) {
        StringBuilder lines = new StringBuilder();
        for (SkippedTest skip : preflight.skipped()) {
            int separator = skip.function().indexOf("::");
            if (!skip.packageName().equals(packageName) || separator < 0) {
                continue;
            }
            lines.append(skip.function().substring(0, separator).replace('.', '/'))
                    .append('#')
                    .append(skip.function().substring(separator + 2))
                    .append('\n');
        }
        return lines.isEmpty() ? null : lines.toString();
    }

    static void recordSkips(AdapterResponse response, PreflightPlan preflight, String packageName, boolean strict) {
        for (SkippedTest skip : preflight.skipped()) {
            if (!skip.packageName().equals(packageName)) {
                continue;
            }
            response.tests().add(Formats.testCase(skip.function(), TestStatus.SKIPPED, skip.reason(), 0));
            if (strict) {
                response.success(false);
                response.diagnostics().add("required test " + skip.function() + " skipped: " + skip.reason());
            }
        }
    }

    static String failureSummary(AdapterResponse response) {
        if (!response.diagnostics().isEmpty()) {
            return String.join("; ", response.diagnostics());
        }
        List<String> failed = response.tests().stream()
                .filter(test -> test.status() == TestStatus.FAILED)
                .map(TestResult::name)
                .toList();
        if (failed.isEmpty()) {
            return "test runner exited with an error; see its logs in the run directory";
        }
        if (failed.size() == 1) {
            return "1 test failed: " + failed.getFirst();
        }
        return failed.size() + " tests failed: " + String.join(", ", failed);
    }

    static List<String> phasesFor(List<String> kinds) {
        List<String> phases = new ArrayList<>(List.of("unit", "integration", "functional", "ui", "visual"));
        if (kinds.contains("mutation")) {
            phases.add("mutation");
        }
        return phases;
    }

    private static boolean hostCommandPhase(String phase) {
        return phase.equals("mutation");
    }

    private static String defaultAdapter(String phase, PackageRuntime runtime) {
        return switch (phase) {
            case "ui", "visual" -> "playwright";
            case "functional", "mutation" -> "command";
            default -> runtime == PackageRuntime.NODE ? "vitest" : "cargo";
        };
    }

    static String phaseAdapter(String phase, @Nullable ExternalRunner runner, PackageConfig packageConfig) {
        if (hostCommandPhase(phase)) {
            return runner != null && runner.adapter() != null ? runner.adapter() : "command";
        }
        if (runner != null && runner.adapter() != null) {
            return runner.adapter();
        }
        if (packageConfig.adapter() != null) {
            return packageConfig.adapter();
        }
        return defaultAdapter(phase, packageConfig.runtime());
    }

    static ExecutionMode phaseExecution(String phase, @Nullable ExecutionMode configured, ExecutionMode defaultMode) {
        if (hostCommandPhase(phase)) {
            return ExecutionMode.LOCAL;
        }
        return configured != null ? configured : defaultMode;
    }

    static boolean phaseCollectsCoverage(String phase, boolean requested) {
        return requested && !hostCommandPhase(phase);
    }
}
