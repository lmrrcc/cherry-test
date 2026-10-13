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
import com.cherrytest.common.process.EnvFile;
import com.cherrytest.common.process.ProcessEnvironment;
import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.common.rustcompat.RustIoError;
import com.cherrytest.common.rustcompat.RustOrdering;
import com.cherrytest.common.rustcompat.RustPath;
import com.cherrytest.common.rustcompat.RustText;
import com.cherrytest.core.ExternalRunner;
import com.cherrytest.core.PackageConfig;
import com.cherrytest.core.PackageRuntime;
import com.cherrytest.core.Scope;
import com.cherrytest.core.TestItConfig;
import com.cherrytest.core.Workspace;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

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
            Adapters.SelfCommand self) {}

    private record PackageKey(String project, String packageName) implements Comparable<PackageKey> {
        @Override
        public int compareTo(PackageKey other) {
            int project = RustOrdering.compareStrings(this.project, other.project);
            return project != 0 ? project : RustOrdering.compareStrings(packageName, other.packageName);
        }
    }

    public static RunOutcome runProjects(
            List<ProjectGroup> projects, RunOptions options, PreflightPlan preflightInput) {
        PreflightPlan preflight = preflightInput.copy();
        TreeMap<PackageKey, Boolean> statuses = new TreeMap<>();
        TreeMap<PackageKey, List<Path>> coverage = new TreeMap<>();
        List<Path> unit = new ArrayList<>();
        List<Path> integration = new ArrayList<>();
        boolean ranIntegration = false;
        for (String phase : phasesFor(options.kinds())) {
            if (!options.kinds().isEmpty() && !options.kinds().contains(phase)) {
                continue;
            }
            ProcessEnvironment.set("CHERRY_TEST_PHASE", phase);
            TreeSet<String> booted = new TreeSet<>(RustOrdering.STRING_ORDER);
            boolean authReady = false;
            for (ProjectGroup project : projects) {
                EnvFile.apply(options.workspaceRoot(), project.id());
                for (WorkspaceMember member : project.members()) {
                    if (ProcessRunner.cancelled()) {
                        break;
                    }
                    PackageConfig packageConfig = options.config().packageConfig(project.id(), member.name());
                    List<String> configured = options.config().packageKinds(member.name(), project.id());
                    for (String kind : configured) {
                        if (TestKind.parse(kind) == null) {
                            throw new AnyhowError("unknown test kind " + kind);
                        }
                    }
                    if (!configured.contains(phase)) {
                        continue;
                    }
                    ExternalRunner runner = switch (phase) {
                        case "unit" -> packageConfig.unit();
                        case "integration" -> packageConfig.integration();
                        case "functional" -> options.config().functionalRunner(member.name(), project.id());
                        case "ui" -> options.config().uiRunner(member.name(), project.id());
                        case "visual" -> packageConfig.visual();
                        case "mutation" -> packageConfig.mutation();
                        default -> null;
                    };
                    String adapter = phaseAdapter(phase, runner, packageConfig);
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
                            .resolve(project.id())
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
                    List<String> scopes = new ArrayList<>();
                    if (phase.equals("integration") && adapter.equals("cargo")) {
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
                    RuntimeException setupError = null;
                    try {
                        for (String scope : scopes) {
                            ranIntegration = true;
                            if (!booted.contains(scope)) {
                                Workspace.bootScope(scope);
                                booted.add(scope);
                            }
                        }
                        if (phase.equals("integration") && !authReady && !booted.isEmpty()) {
                            AuthPreflight.runAuthPreflight(
                                    options.workspaceRoot(), options.config(), preflight, options.quiet());
                            authReady = true;
                        }
                    } catch (RuntimeException error) {
                        setupError = error;
                    }
                    if (adapter.equals("cargo") && phase.equals("integration")) {
                        for (SkippedTest skip : preflight.skipped()) {
                            if (skip.packageName().equals(member.name())) {
                                args.add("--skip");
                                args.add(skip.function());
                            }
                        }
                    }
                    SortedMap<String, String> env = new TreeMap<>(RustOrdering.STRING_ORDER);
                    for (Map.Entry<String, String> variable :
                            ProcessEnvironment.snapshot().entrySet()) {
                        String key = variable.getKey();
                        if (key.startsWith("CHERRY_TEST_")
                                && !key.contains("AUTH")
                                && !key.contains("TOKEN")
                                && !key.contains("SECRET")) {
                            env.put(key, variable.getValue());
                        }
                    }
                    AdapterRequest request = new AdapterRequest(
                            AdapterResponse.PROTOCOL_VERSION,
                            project.id(),
                            member.name(),
                            phase,
                            options.workspaceRoot(),
                            options.workspaceRoot().resolve(member.manifestPath()),
                            cwd,
                            output,
                            phaseExecution(
                                    phase,
                                    packageConfig.execution(),
                                    options.config().defaults().execution()),
                            phaseCollectsCoverage(phase, options.coverage()),
                            options.config().defaults().strict(),
                            args,
                            env,
                            requestOptions);
                    if (!options.quiet()) {
                        Terminal.println(
                                "==> " + project.id() + "/" + member.name() + "/" + phase + " (" + adapter + ")");
                    }
                    AdapterResponse response;
                    try {
                        if (setupError != null) {
                            throw setupError;
                        }
                        response = Adapters.invoke(
                                adapter, options.config().adapters().get(adapter), request, options.self());
                    } catch (RuntimeException error) {
                        response = AdapterResponse.failure(AnyhowError.alternate(error));
                    }
                    if (adapter.equals("cargo") && phase.equals("integration")) {
                        recordSkips(response, preflight, member.name(), request.strict());
                    }
                    PackageKey key = new PackageKey(project.id(), member.name());
                    CoveragePaths paths = new CoveragePaths(options.runDir(), project.id());
                    paths.ensureDirs();
                    if (!response.coverage().isEmpty()) {
                        Path lcov = paths.lcovDir().resolve(member.name() + "-" + phase + ".lcov");
                        try {
                            normalizeCoverage(request, response.coverage(), lcov);
                            coverage.computeIfAbsent(key, ignored -> new ArrayList<>())
                                    .add(lcov);
                            if (phase.equals("unit")) {
                                unit.add(lcov);
                            } else if (phase.equals("integration")) {
                                integration.add(lcov);
                            }
                        } catch (RuntimeException error) {
                            response.success(false);
                            response.diagnostics().add("coverage import failed: " + AnyhowError.alternate(error));
                        }
                    }
                    AdapterResponse saved =
                            Adapters.save(request, response, options.runDir().reports());
                    statuses.merge(key, saved.success(), (existing, incoming) -> existing && incoming);
                    if (!options.quiet()) {
                        if (options.testListing() == TestListing.AFTER_EACH_PHASE) {
                            ReportOutput.printPhaseResults(saved.tests());
                        }
                        if (!saved.success()) {
                            Terminal.eprintln("  failed: " + failureSummary(saved));
                        }
                    }
                }
            }
            Workspace.teardownScopes();
            if (ProcessRunner.cancelled()) {
                break;
            }
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
        return new RunOutcome(results, lcovFiles, unit, integration, failed, ranIntegration);
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
