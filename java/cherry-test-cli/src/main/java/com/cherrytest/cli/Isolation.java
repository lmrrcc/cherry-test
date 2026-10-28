package com.cherrytest.cli;

import com.cherrytest.cli.adapters.AdapterJson;
import com.cherrytest.cli.adapters.Adapters;
import com.cherrytest.cli.adapters.Builtins;
import com.cherrytest.cli.adapters.Json;
import com.cherrytest.cli.adapters.ProcessRunner;
import com.cherrytest.cli.nativeos.Terminal;
import com.cherrytest.common.adapter.AdapterConfig;
import com.cherrytest.common.adapter.AdapterRequest;
import com.cherrytest.common.adapter.AdapterResponse;
import com.cherrytest.common.adapter.IsolationClass;
import com.cherrytest.common.adapter.TestResult;
import com.cherrytest.common.adapter.TestStatus;
import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.common.rustcompat.RustIoError;
import com.cherrytest.common.rustcompat.SerdeJsonWriter;
import com.cherrytest.core.IsolateConfig;
import com.cherrytest.core.Workspace;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Isolation on failure: after the main schedule each failed test runs alone, several times, each time as a fresh
 * job, and is classified {@code REAL}, {@code INTERFERENCE}, {@code FLAKY} or {@code INCONCLUSIVE}. Reruns never
 * change a test's status or the run's exit code; they only explain the failure.
 */
public final class Isolation {
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private Isolation() {}

    /** How many times each failed test reruns and how many failed tests are isolated. */
    public record Settings(long runs, long maxTests) {
        /** {@code --isolate} or {@code --no-isolate} over {@code defaults.isolate.on_failure}; null when off. */
        public static @Nullable Settings resolve(boolean isolate, boolean noIsolate, @Nullable IsolateConfig config) {
            IsolateConfig effective = config == null ? IsolateConfig.standard() : config;
            boolean enabled = isolate || (!noIsolate && effective.onFailure());
            return enabled ? new Settings(effective.runs(), effective.maxTests()) : null;
        }
    }

    /** How an adapter reruns one test. */
    public sealed interface Narrowing {
        /** A built-in adapter: the phase arguments that select the test. */
        record Args(List<String> args) implements Narrowing {
            public Args {
                args = List.copyOf(args);
            }
        }

        /** An adapter registered with {@code isolate: true}: the request's {@code select}. */
        record Select(List<String> names) implements Narrowing {
            public Select {
                names = List.copyOf(names);
            }
        }

        /** The adapter cannot run the test alone, and why. */
        record Unsupported(String reason) implements Narrowing {}
    }

    /** What one rerun of a test showed. */
    public sealed interface Rerun {
        /** The test passed alone. */
        record Passed() implements Rerun {}

        /** The test failed alone. */
        record Failed() implements Rerun {}

        /** The rerun did not reach a verdict, and why. */
        record Infrastructure(String message) implements Rerun {}
    }

    /** The class of a test's reruns, how many passed, and the first infrastructure error. */
    public record Classification(
            IsolationClass isolationClass,
            long passed,
            @Nullable String reason) {}

    /** Classifies the reruns of one test. */
    public static Classification classify(List<Rerun> reruns) {
        long passed = reruns.stream().filter(Rerun.Passed.class::isInstance).count();
        String infrastructure = null;
        for (Rerun rerun : reruns) {
            if (rerun instanceof Rerun.Infrastructure(String message)) {
                infrastructure = message;
                break;
            }
        }
        IsolationClass result;
        if (infrastructure != null || reruns.isEmpty()) {
            result = IsolationClass.INCONCLUSIVE;
        } else if (passed == 0) {
            result = IsolationClass.REAL;
        } else if (passed == reruns.size()) {
            result = IsolationClass.INTERFERENCE;
        } else {
            result = IsolationClass.FLAKY;
        }
        return new Classification(result, passed, infrastructure);
    }

    /**
     * The arguments or selection that run {@code test} alone with {@code adapter}. Cargo adds {@code <name> --exact}.
     * Maven replaces {@code -Dtest} (unit) or {@code -Dit.test} (integration) with {@code Class#method}, or with the
     * whole class when it orders its methods or shares one instance. Vitest adds the test file and an anchored
     * {@code -t} pattern. An adapter registered in {@code adapters:} must declare {@code isolate: true} to receive
     * {@code select}; every other adapter cannot narrow.
     */
    public static Narrowing narrow(
            String adapter, @Nullable AdapterConfig registered, AdapterRequest request, TestResult test) {
        if (registered != null) {
            return registered.isolate()
                    ? new Narrowing.Select(List.of(test.name()))
                    : new Narrowing.Unsupported("adapter `" + adapter + "` does not declare `isolate: true`");
        }
        return switch (adapter) {
            case "cargo" -> {
                List<String> args = new ArrayList<>(request.args());
                args.add(test.name());
                args.add("--exact");
                yield new Narrowing.Args(args);
            }
            case "maven" -> maven(request, test.name());
            case "vitest" -> vitest(request, test);
            default -> new Narrowing.Unsupported("adapter `" + adapter + "` cannot run a single test");
        };
    }

    private static Narrowing maven(AdapterRequest request, String name) {
        int separator = name.indexOf("::");
        if (separator < 0) {
            return new Narrowing.Unsupported("`" + name + "` does not name a test class and method");
        }
        String testClass = name.substring(0, separator);
        String method = name.substring(separator + 2);
        int end = method.length();
        for (char stop : new char[] {'(', '['}) {
            int at = method.indexOf(stop);
            if (at >= 0 && at < end) {
                end = at;
            }
        }
        method = method.substring(0, end);
        String selector = runsAsAClass(request.cwd(), testClass) ? testClass : testClass + "#" + method;
        String property = request.phase().equals("integration") ? "-Dit.test=" : "-Dtest=";
        List<String> args = new ArrayList<>();
        for (String argument : request.args()) {
            if (!argument.startsWith(property)) {
                args.add(argument);
            }
        }
        args.add(property + selector);
        args.add("-Dsurefire.failIfNoSpecifiedTests=false");
        if (request.phase().equals("integration")) {
            args.add("-Dit.failIfNoSpecifiedTests=false");
        }
        return new Narrowing.Args(args);
    }

    /**
     * Whether a JUnit class orders its methods ({@code @TestMethodOrder}) or shares one instance
     * ({@code @TestInstance(PER_CLASS)}): its methods may depend on each other, so the whole class reruns.
     */
    public static boolean runsAsAClass(Path module, String testClass) {
        int nested = testClass.indexOf('$');
        String outer = (nested < 0 ? testClass : testClass.substring(0, nested)).replace('.', '/');
        for (String[] root : new String[][] {{"src/test/java", "java"}, {"src/test/kotlin", "kt"}}) {
            Path file = module.resolve(root[0]).resolve(outer + "." + root[1]);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            String source;
            try {
                source = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException unreadable) {
                continue;
            }
            return source.contains("@TestMethodOrder")
                    || (source.contains("@TestInstance") && source.contains("PER_CLASS"));
        }
        return false;
    }

    private static Narrowing vitest(AdapterRequest request, TestResult test) {
        Path source = test.source();
        if (source == null) {
            return new Narrowing.Unsupported("Vitest did not report the file of `" + test.name() + "`");
        }
        Path file = Builtins.sourcePath(request, source.toString());
        String relative =
                file.startsWith(request.cwd()) ? request.cwd().relativize(file).toString() : file.toString();
        List<String> args = new ArrayList<>(request.args());
        if (NodeRuntime.vitestRunArgs(args).isEmpty()) {
            args.add("run");
        }
        args.add(relative);
        args.add("-t");
        args.add("^" + regexEscape(test.name()) + "$");
        return new Narrowing.Args(args);
    }

    /** Escapes the characters a JavaScript regular expression treats specially. */
    public static String regexEscape(String text) {
        StringBuilder escaped = new StringBuilder(text.length());
        text.codePoints().forEach(ch -> {
            if ("\\^$.|?*+()[]{}/".indexOf(ch) >= 0) {
                escaped.append('\\');
            }
            escaped.appendCodePoint(ch);
        });
        return escaped.toString();
    }

    /** One classified test in {@code reports/isolation.json}. */
    public record IsolatedTest(
            String project,
            String packageName,
            String phase,
            String test,
            IsolationClass isolationClass,
            long runs,
            long passed,
            List<String> logs,
            @Nullable String reason) {
        public IsolatedTest {
            logs = List.copyOf(logs);
        }

        com.cherrytest.common.adapter.Isolation isolation() {
            return new com.cherrytest.common.adapter.Isolation(isolationClass, runs, passed, logs, reason);
        }
    }

    /** {@code reports/isolation.json}: the settings, how many tests failed, and the classified ones in plan order. */
    public record Report(long runs, long maxTests, long failed, List<IsolatedTest> tests) {
        public Report {
            tests = List.copyOf(tests);
        }

        /** The terminal lines: a header, one line per classified test, and how many were left out. */
        public List<String> lines() {
            List<String> lines = new ArrayList<>();
            lines.add(header(failed, tests.size(), runs));
            tests.forEach(test -> lines.add(line(test)));
            String leftover = leftover();
            if (leftover != null) {
                lines.add(leftover);
            }
            return lines;
        }

        @Nullable
        String leftover() {
            long left = Math.max(0, failed - tests.size());
            if (left == 0) {
                return null;
            }
            return "  " + left + " more failed " + plural(left, "test", "tests") + " not isolated (max_tests "
                    + maxTests + ")";
        }

        /** How many tests each class holds, in class order, leaving out empty classes. */
        public List<Map.Entry<IsolationClass, Long>> counts() {
            List<Map.Entry<IsolationClass, Long>> counts = new ArrayList<>();
            for (IsolationClass value : IsolationClass.values()) {
                long count = tests.stream()
                        .filter(test -> test.isolationClass() == value)
                        .count();
                if (count > 0) {
                    counts.add(Map.entry(value, count));
                }
            }
            return counts;
        }

        /** The pretty JSON of {@code reports/isolation.json}, fields in declaration order. */
        public String json() {
            ObjectNode node = NODES.objectNode();
            node.put("runs", runs);
            node.put("max_tests", maxTests);
            node.put("failed", failed);
            ArrayNode entries = node.putArray("tests");
            for (IsolatedTest test : tests) {
                ObjectNode entry = entries.addObject();
                entry.put("project", test.project());
                entry.put("package", test.packageName());
                entry.put("phase", test.phase());
                entry.put("test", test.test());
                entry.put("class", test.isolationClass().wireName());
                entry.put("runs", test.runs());
                entry.put("passed", test.passed());
                ArrayNode logs = entry.putArray("logs");
                test.logs().forEach(logs::add);
                if (test.reason() != null) {
                    entry.put("reason", test.reason());
                }
            }
            return SerdeJsonWriter.pretty(node);
        }
    }

    /** {@code ==> isolate N failed tests: each alone, R runs}, or the line for a run without failures. */
    static String header(long failed, long selected, long runs) {
        if (failed == 0) {
            return "==> isolate: no failed tests";
        }
        return "==> isolate " + selected + " failed " + plural(selected, "test", "tests") + ": each alone, " + runs
                + " " + plural(runs, "run", "runs");
    }

    /** One classified test: class, job, test name, and how many reruns passed or why none could run. */
    static String line(IsolatedTest test) {
        String detail;
        if (test.reason() != null && test.runs() == 0) {
            detail = test.reason();
        } else if (test.reason() != null) {
            detail = test.passed() + "/" + test.runs() + " passed alone; " + test.reason();
        } else {
            detail = test.passed() + "/" + test.runs() + " passed alone";
        }
        String name = test.isolationClass().wireName();
        return "  " + name + " ".repeat(Math.max(0, 12 - name.length())) + "  " + test.project() + "/"
                + test.packageName() + "/" + test.phase() + "  " + test.test() + "  " + detail;
    }

    private static String plural(long count, String one, String many) {
        return count == 1 ? one : many;
    }

    /** Reads {@code reports/isolation.json}, or null when the run did not isolate failures. */
    public static @Nullable Report readReport(Path reports) {
        Path path = reports.resolve("isolation.json");
        if (!Files.isRegularFile(path)) {
            return null;
        }
        JsonNode node;
        try {
            node = Json.parseBytes(Files.readAllBytes(path));
        } catch (IOException exception) {
            throw AnyhowError.context("read " + path, new AnyhowError(RustIoError.display(exception)));
        } catch (RuntimeException exception) {
            throw AnyhowError.context("parse " + path, exception);
        }
        List<IsolatedTest> tests = new ArrayList<>();
        for (JsonNode entry : node.get("tests")) {
            List<String> logs = new ArrayList<>();
            entry.get("logs").forEach(log -> logs.add(log.stringValue()));
            JsonNode reason = entry.get("reason");
            tests.add(new IsolatedTest(
                    entry.get("project").stringValue(),
                    entry.get("package").stringValue(),
                    entry.get("phase").stringValue(),
                    entry.get("test").stringValue(),
                    IsolationClass.fromWire(entry.get("class").stringValue()),
                    entry.get("runs").longValue(),
                    entry.get("passed").longValue(),
                    logs,
                    reason == null || reason.isNull() ? null : reason.stringValue()));
        }
        return new Report(
                node.get("runs").longValue(),
                node.get("max_tests").longValue(),
                node.get("failed").longValue(),
                tests);
    }

    /** The report class of an isolation badge: the colour of a failure, a skip or plain text. */
    public static String badgeClass(IsolationClass value) {
        return switch (value) {
            case REAL -> "isolation fail";
            case FLAKY -> "isolation skip";
            case INTERFERENCE -> "isolation neutral";
            case INCONCLUSIVE -> "isolation muted";
        };
    }

    private static Path resultPath(Runner.RunOptions options, Plan.Job job) {
        return options.runDir()
                .reports()
                .resolve("adapters")
                .resolve(job.key().project())
                .resolve(job.key().packageName())
                .resolve(job.key().phase())
                .resolve("result.json");
    }

    private static AdapterResponse readResult(Path path) {
        try {
            return AdapterJson.responseFrom(
                    Json.parseBytes(Files.readAllBytes(path)).get("result"));
        } catch (IOException exception) {
            throw AnyhowError.context("read " + path, new AnyhowError(RustIoError.display(exception)));
        }
    }

    /** The entry {@code save} adds when an adapter failed without naming a failed test; it is not a test. */
    private static boolean isRunnerEntry(TestResult test, AdapterResponse response) {
        return test.name().equals("runner")
                && !response.diagnostics().isEmpty()
                && String.join("\n", response.diagnostics()).equals(test.message());
    }

    private record Failure(Plan.Job job, TestResult test) {}

    /**
     * Reruns the failed tests of a finished schedule and records their classes; does nothing when isolation is off
     * or the run was cancelled.
     */
    static @Nullable Report run(
            Plan plan, List<ProjectGroup> projects, Runner.RunOptions options, PreflightPlan preflight) {
        Settings settings = options.isolate();
        if (settings == null || ProcessRunner.cancelled()) {
            return null;
        }
        List<Failure> failures = new ArrayList<>();
        for (Plan.Job job : plan.jobs().toList()) {
            Path path = resultPath(options, job);
            if (!Files.isRegularFile(path)) {
                continue;
            }
            AdapterResponse response = readResult(path);
            for (TestResult test : response.tests()) {
                if (test.status() == TestStatus.FAILED && !isRunnerEntry(test, response)) {
                    failures.add(new Failure(job, test));
                }
            }
        }
        Scheduler.EnvLayers layers = Scheduler.EnvLayers.load(options, projects);
        long selected = Math.min(failures.size(), settings.maxTests());
        if (!options.quiet()) {
            Terminal.println(header(failures.size(), selected, settings.runs()));
        }
        List<IsolatedTest> tests = new ArrayList<>();
        for (int index = 0; index < selected; index++) {
            if (ProcessRunner.cancelled()) {
                break;
            }
            Plan.Job job = failures.get(index).job();
            TestResult test = failures.get(index).test();
            Narrowing narrowing =
                    narrow(job.adapter(), options.config().adapters().get(job.adapter()), job.request(), test);
            IsolatedTest entry;
            if (narrowing instanceof Narrowing.Unsupported(String reason)) {
                entry = new IsolatedTest(
                        job.key().project(),
                        job.key().packageName(),
                        job.key().phase(),
                        test.name(),
                        IsolationClass.INCONCLUSIVE,
                        0,
                        0,
                        List.of(),
                        reason);
            } else {
                List<Rerun> reruns = new ArrayList<>();
                List<String> logs = new ArrayList<>();
                for (long attempt = 1; attempt <= settings.runs(); attempt++) {
                    String relative = "isolation/" + job.key().project() + "/"
                            + job.key().packageName() + "/" + job.key().phase() + "/" + (index + 1) + "/" + attempt;
                    Plan.Job rerun = Plan.rerunJob(
                            job,
                            narrowing,
                            options.runDir().reports().resolve(relative),
                            "isolate-" + (index + 1) + "-" + attempt,
                            options);
                    reruns.add(rerunOnce(rerun, test.name(), layers, options, preflight));
                    logs.add(relative);
                }
                Classification classification = classify(reruns);
                entry = new IsolatedTest(
                        job.key().project(),
                        job.key().packageName(),
                        job.key().phase(),
                        test.name(),
                        classification.isolationClass(),
                        settings.runs(),
                        classification.passed(),
                        logs,
                        classification.reason());
            }
            if (!options.quiet()) {
                Terminal.println(line(entry));
            }
            tests.add(entry);
        }
        Report report = new Report(settings.runs(), settings.maxTests(), failures.size(), tests);
        if (!options.quiet()) {
            String leftover = report.leftover();
            if (leftover != null) {
                Terminal.println(leftover);
            }
        }
        record(plan, options, report);
        return report;
    }

    /**
     * Runs one rerun job like a stage of its own: boots its scopes, invokes it, tears them down, and reads the test's
     * status from its response.
     */
    private static Rerun rerunOnce(
            Plan.Job job, String test, Scheduler.EnvLayers layers, Runner.RunOptions options, PreflightPlan preflight) {
        Scheduler.RunState state = new Scheduler.RunState(preflight.copy());
        state.startPhase();
        String setup = Scheduler.setupJob(job, state, layers, options);
        SortedMap<String, String> environment =
                layers.jobEnv(state, job.key().phase(), job.key().project());
        Runner.Invocation invocation = null;
        String invokeError = null;
        try {
            invocation = Runner.invokeJob(job, environment, state.preflight, setup, options);
        } catch (RuntimeException error) {
            invokeError = AnyhowError.alternate(error);
        }
        String teardownError = null;
        try {
            Workspace.teardownScopes(
                    state.scopes, layers.phaseEnv(state, job.key().phase()));
        } catch (RuntimeException error) {
            teardownError = AnyhowError.alternate(error);
        }
        if (invocation == null) {
            return new Rerun.Infrastructure(invokeError == null ? "rerun did not start" : invokeError);
        }
        String writeError = null;
        try {
            Adapters.writeResult(
                    invocation.request().output().resolve("result.json"), invocation.request(), invocation.response());
        } catch (RuntimeException error) {
            writeError = AnyhowError.alternate(error);
        }
        if (invocation.infrastructure() != null) {
            return new Rerun.Infrastructure(invocation.infrastructure());
        }
        if (teardownError != null) {
            return new Rerun.Infrastructure("scope teardown failed: " + teardownError);
        }
        if (writeError != null) {
            return new Rerun.Infrastructure(writeError);
        }
        for (TestResult result : invocation.response().tests()) {
            if (result.name().equals(test)) {
                return switch (result.status()) {
                    case PASSED -> new Rerun.Passed();
                    case FAILED -> new Rerun.Failed();
                    case SKIPPED -> new Rerun.Infrastructure("`" + test + "` was skipped when run alone");
                };
            }
        }
        return new Rerun.Infrastructure("`" + test + "` did not run alone");
    }

    /** Adds each classification to its test in the job's {@code result.json} and writes {@code reports/isolation.json}. */
    private static void record(Plan plan, Runner.RunOptions options, Report report) {
        for (Plan.Job job : plan.jobs().toList()) {
            List<IsolatedTest> entries = report.tests().stream()
                    .filter(entry -> entry.project().equals(job.key().project())
                            && entry.packageName().equals(job.key().packageName())
                            && entry.phase().equals(job.key().phase()))
                    .toList();
            if (entries.isEmpty()) {
                continue;
            }
            Path path = resultPath(options, job);
            AdapterResponse response = readResult(path);
            List<TestResult> tests = new ArrayList<>();
            for (TestResult test : response.tests()) {
                IsolatedTest match = entries.stream()
                        .filter(entry -> entry.test().equals(test.name()))
                        .findFirst()
                        .orElse(null);
                tests.add(match == null ? test : test.withIsolation(match.isolation()));
            }
            response.tests().clear();
            response.tests().addAll(tests);
            Adapters.writeResult(path, job.request(), response);
        }
        Path reports = options.runDir().reports();
        try {
            Files.createDirectories(reports);
            Files.writeString(reports.resolve("isolation.json"), report.json(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new AnyhowError(RustIoError.display(exception), null);
        }
    }
}
