package com.cherrytest.cli;

import com.cherrytest.cli.adapters.Adapters;
import com.cherrytest.cli.adapters.Builtins;
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
import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.common.rustcompat.RustIoError;
import com.cherrytest.common.rustcompat.RustOrdering;
import com.cherrytest.common.rustcompat.RustPath;
import com.cherrytest.common.rustcompat.RustText;
import com.cherrytest.core.ExternalRunner;
import com.cherrytest.core.PackageConfig;
import com.cherrytest.core.PackageRuntime;
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
            Scheduler.Parallelism parallelism) {
        public RunOptions {
            SortedMap<String, String> base = new TreeMap<>(RustOrdering.STRING_ORDER);
            base.putAll(env);
            env = base;
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
        if (options.parallelism().workers() > 1) {
            plan = Durations.load(Durations.path(
                            options.workspaceRoot(), options.config().defaults().runsBase()))
                    .estimate(plan);
        }
        List<Scheduler.Finished> finished = Scheduler.run(plan, projects, options, preflight);
        return aggregate(options, finished);
    }

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
                RunEnv.requestEnv(environment),
                planned.options());
        if (!options.quiet()) {
            synchronized (OUTPUT) {
                Terminal.println("==> " + job.key().project() + "/" + packageName + "/" + phase + " (" + adapter + ")");
            }
        }
        AdapterResponse response;
        try {
            if (setupError != null) {
                response = AdapterResponse.failure(setupError);
            } else {
                response = Adapters.invoke(
                        adapter, options.config().adapters().get(adapter), request, options.self(), environment);
            }
        } catch (RuntimeException error) {
            response = AdapterResponse.failure(AnyhowError.alternate(error));
        }
        if (discoversScopes(adapter) && phase.equals("integration")) {
            recordSkips(response, preflight, packageName, request.strict());
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

    private static void normalizeCoverage(AdapterRequest request, List<Path> files, Path output) {
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
                    if (!path.startsWith(request.root())) {
                        include = false;
                        continue;
                    }
                    Path canonical;
                    try {
                        canonical = path.toRealPath();
                    } catch (IOException exception) {
                        throw AnyhowError.context(
                                "coverage source " + path, new AnyhowError(RustIoError.display(exception)));
                    }
                    include = canonical.startsWith(request.root());
                    Path relative = RustPath.stripPrefix(canonical, request.root());
                    if (relative != null) {
                        result.append("SF:").append(relative).append('\n');
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
