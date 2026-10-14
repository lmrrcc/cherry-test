package com.cherrytest.cli.adapters;

import com.cherrytest.cli.NodeRuntime;
import com.cherrytest.cli.coverage.Coverage;
import com.cherrytest.common.adapter.AdapterRequest;
import com.cherrytest.common.adapter.AdapterResponse;
import com.cherrytest.common.adapter.Attachment;
import com.cherrytest.common.adapter.ExecutionMode;
import com.cherrytest.common.adapter.TestResult;
import com.cherrytest.common.adapter.TestStatus;
import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.common.rustcompat.RustIoError;
import com.cherrytest.common.rustcompat.RustOrdering;
import com.cherrytest.common.rustcompat.RustPath;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;

public final class Builtins {
    public static final List<String> NAMES =
            List.of("cargo", "vitest", "maven", "gradle", "cmake", "playwright", "command");

    private Builtins() {}

    public static AdapterResponse run(String name, AdapterRequest input) {
        createDirectories(input.output());
        SortedMap<String, String> env = new TreeMap<>(RustOrdering.STRING_ORDER);
        env.putAll(input.env());
        env.put("CHERRY_TEST_PHASE", input.phase());
        env.put("CHERRY_TEST_ADAPTER_OUTPUT", input.output().toString());
        MutableRequest request = new MutableRequest(input, env);
        return switch (name) {
            case "cargo" -> cargo(request);
            case "vitest" -> node(request, false);
            case "playwright" -> node(request, true);
            case "maven", "gradle" -> jvm(request, name);
            case "cmake" -> nativeBuild(request);
            case "command" -> command(request);
            default -> throw new AnyhowError("unknown bundled adapter " + name);
        };
    }

    private static final class MutableRequest {
        private final AdapterRequest base;
        private final SortedMap<String, String> env;

        private MutableRequest(AdapterRequest base, SortedMap<String, String> env) {
            this.base = base;
            this.env = env;
        }

        private AdapterRequest snapshot() {
            return new AdapterRequest(
                    base.protocol(),
                    base.project(),
                    base.packageName(),
                    base.phase(),
                    base.root(),
                    base.manifest(),
                    base.cwd(),
                    base.output(),
                    base.execution(),
                    base.coverage(),
                    base.strict(),
                    base.args(),
                    env,
                    base.options());
        }
    }

    private static AdapterResponse cargo(MutableRequest mutable) {
        AdapterRequest request = mutable.base;
        List<String> args = new ArrayList<>();
        boolean coverage =
                request.coverage() && (request.execution() == ExecutionMode.CONTAINER || Coverage.llvmCovAvailable());
        if (request.coverage() && !coverage && request.strict()) {
            throw new AnyhowError("cargo-llvm-cov is required for coverage");
        }
        Path lcov = request.output().resolve("coverage.lcov");
        if (coverage) {
            args.add("llvm-cov");
        }
        args.addAll(List.of("test", "--manifest-path"));
        args.add(request.manifest().toString());
        args.addAll(List.of("--package", request.packageName()));
        if (coverage) {
            args.addAll(List.of("--lcov", "--output-path"));
            args.add(lcov.toString());
        }
        switch (request.phase()) {
            case "unit" -> args.add("--lib");
            case "integration" -> args.addAll(List.of("--test", "*"));
            default -> throw new AnyhowError("Cargo adapter supports unit and integration phases");
        }
        args.add("--");
        if (request.phase().equals("integration")) {
            args.addAll(List.of("--ignored", "--test-threads=1"));
        }
        args.addAll(request.args());
        args.addAll(List.of("--format", "pretty", "--color", "never"));
        mutable.env.put("CARGO_TARGET_DIR", cargoTargetDir(request).toString());
        boolean success = ProcessRunner.run(mutable.snapshot(), "cargo", args, "tests");
        String output = read(request.output().resolve("tests.stdout"));
        AdapterResponse result = new AdapterResponse(
                AdapterResponse.PROTOCOL_VERSION, success, Formats.rustTests(output), List.of(), List.of());
        if (Files.isRegularFile(lcov)) {
            result.coverage().add(lcov);
        }
        if (request.coverage() && !coverage) {
            result.diagnostics().add("cargo-llvm-cov unavailable; coverage was not measured");
        }
        return result;
    }

    public static Path cargoTargetDir(AdapterRequest request) {
        String execution = switch (request.execution()) {
            case LOCAL -> "local";
            case CONTAINER -> "container";
        };
        return request.root()
                .resolve("target")
                .resolve("cherry-test")
                .resolve("cargo")
                .resolve(execution);
    }

    private static AdapterResponse node(MutableRequest mutable, boolean playwright) {
        AdapterRequest request = mutable.base;
        Path report = request.output().resolve("results.json");
        String script;
        List<String> args;
        if (playwright) {
            mutable.env.put("PLAYWRIGHT_JSON_OUTPUT_FILE", report.toString());
            script = "node_modules/@playwright/test/cli.js";
            args = NodeRuntime.playwrightRunArgs(request.args());
        } else {
            mutable.env.put("VITEST_JSON_REPORT", report.toString());
            if (request.coverage()) {
                mutable.env.put(
                        "VITEST_COVERAGE_DIR",
                        request.output().resolve("coverage").toString());
            }
            script = "node_modules/vitest/vitest.mjs";
            args = NodeRuntime.vitestRunArgs(request.args());
        }
        if (args.isEmpty()) {
            args.add(playwright ? "test" : "run");
        }
        if (playwright) {
            args.addAll(List.of("--reporter=json", "--output"));
            args.add(request.output().resolve("attachments").toString());
        }
        if (!playwright && request.coverage() && !args.contains("--coverage")) {
            args.add("--coverage");
        }
        if (!request.coverage()) {
            args.removeIf(argument -> argument.equals("--coverage"));
        }
        args.addFirst(request.cwd().resolve(script).toString());
        String program;
        if (request.execution() == ExecutionMode.LOCAL) {
            mutable.env.put("PATH", NodeRuntime.augmentedPath(request.cwd()));
            Path node = NodeRuntime.resolveNodeBinary();
            if (node == null) {
                throw new AnyhowError("Node.js is required");
            }
            program = node.toString();
        } else {
            program = "node";
        }
        boolean success = ProcessRunner.run(mutable.snapshot(), program, args, "tests");
        String content;
        try {
            content = Files.readString(report, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw AnyhowError.context(
                    "runner did not write its JSON report", new AnyhowError(RustIoError.display(exception)));
        }
        List<TestResult> tests = playwright ? Formats.playwright(content) : Formats.vitest(content);
        if (playwright) {
            tests = importAttachments(mutable.snapshot(), tests);
        }
        List<Path> coverage = Formats.files(request.output().resolve("coverage"), "info");
        return new AdapterResponse(AdapterResponse.PROTOCOL_VERSION, success, tests, coverage, List.of());
    }

    private static List<TestResult> importAttachments(AdapterRequest request, List<TestResult> tests) {
        Path imported = request.output().resolve("imported-attachments");
        List<TestResult> out = new ArrayList<>();
        for (int testIndex = 0; testIndex < tests.size(); testIndex++) {
            TestResult test = tests.get(testIndex);
            List<Attachment> attachments = new ArrayList<>();
            for (int index = 0; index < test.attachments().size(); index++) {
                Attachment attachment = test.attachments().get(index);
                Path path = RustPath.canonicalize(
                        sourcePath(request, attachment.path().toString()));
                if (!path.startsWith(RustPath.canonicalize(request.output()))) {
                    if (!path.startsWith(RustPath.canonicalize(request.root()))) {
                        throw new AnyhowError("Playwright attachment escapes workspace");
                    }
                    createDirectories(imported);
                    Path fileName = path.getFileName();
                    if (fileName == null) {
                        throw new AnyhowError("attachment filename");
                    }
                    Path copy = imported.resolve(testIndex + "-" + index + "-" + fileName);
                    try {
                        Files.copy(path, copy, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException exception) {
                        throw new AnyhowError(RustIoError.display(exception), null);
                    }
                    attachments.add(attachment.withPath(copy));
                } else {
                    attachments.add(attachment);
                }
            }
            out.add(test.withAttachments(attachments));
        }
        return out;
    }

    private static AdapterResponse jvm(MutableRequest mutable, String adapter) {
        AdapterRequest request = mutable.base;
        Path build = request.output().resolve("build");
        String task = switch (request.phase()) {
            case "unit" -> "test";
            case "integration" -> "integrationTest";
            default -> throw new AnyhowError("JVM adapters support unit and integration phases");
        };
        String program;
        List<String> args;
        if (adapter.equals("maven")) {
            program = Files.exists(request.cwd().resolve("mvnw")) ? "./mvnw" : "mvn";
            args = ProcessRunner.strings("-B", "-ntp", task.equals("test") ? "test" : "verify");
            args.add("-Dtestit.buildDirectory=" + build);
            args.add("-Dtestit.phase=" + request.phase());
            if (!task.equals("test")) {
                args.add("-DskipUnitTests=true");
            }
            if (request.coverage()) {
                args.add("jacoco:report");
            }
        } else {
            program = Files.exists(request.cwd().resolve("gradlew")) ? "./gradlew" : "gradle";
            args = ProcessRunner.strings("--no-daemon", "--console=plain", task);
            args.add("-PtestItBuildDir=" + build);
            if (request.coverage()) {
                args.add(task.equals("test") ? "jacocoTestReport" : "jacocoIntegrationTestReport");
            }
        }
        args.addAll(request.args());
        boolean success = ProcessRunner.run(mutable.snapshot(), program, args, "tests");
        Path reportPath = adapter.equals("maven")
                ? build.resolve(task.equals("test") ? "surefire-reports" : "failsafe-reports")
                : build.resolve("test-results").resolve(task);
        List<TestResult> tests = new ArrayList<>();
        for (Path file : Formats.files(reportPath, "xml")) {
            Path fileName = file.getFileName();
            if (fileName != null && fileName.toString().equals("failsafe-summary.xml")) {
                continue;
            }
            tests.addAll(Formats.junit(read(file)));
        }
        AdapterResponse result =
                new AdapterResponse(AdapterResponse.PROTOCOL_VERSION, success, tests, List.of(), List.of());
        if (request.coverage()) {
            List<Path> roots = List.of(
                    request.cwd().resolve("src/main/java"), request.cwd().resolve("src/main/kotlin"));
            for (Path file : Formats.files(build, "xml")) {
                Path fileName = file.getFileName();
                if (fileName == null || !fileName.toString().startsWith("jacoco")) {
                    continue;
                }
                Path lcov = request.output().resolve(result.coverage().size() + ".lcov");
                write(lcov, Formats.jacoco(read(file), roots));
                result.coverage().add(lcov);
            }
        }
        return result;
    }

    private static AdapterResponse nativeBuild(MutableRequest mutable) {
        AdapterRequest request = mutable.base;
        mutable.env.put(
                "LLVM_PROFILE_FILE",
                request.output().resolve("native-%p.profraw").toString());
        Path build = request.output().resolve("build");
        List<String> configure = ProcessRunner.strings(
                "-S",
                request.cwd().toString(),
                "-B",
                build.toString(),
                "-DCMAKE_BUILD_TYPE=Debug",
                "-DCMAKE_C_COMPILER=clang",
                "-DCMAKE_CXX_COMPILER=clang++");
        configure.add("-DCHERRY_TEST_COVERAGE=" + (request.coverage() ? "ON" : "OFF"));
        if (!ProcessRunner.run(mutable.snapshot(), "cmake", configure, "configure")) {
            throw new AnyhowError("CMake configuration failed");
        }
        if (!ProcessRunner.run(
                mutable.snapshot(),
                "cmake",
                ProcessRunner.strings("--build", build.toString(), "--parallel", "2"),
                "build")) {
            throw new AnyhowError("native build failed");
        }
        mutable.env.put(
                "LLVM_PROFILE_FILE",
                request.output().resolve("native-%p.profraw").toString());
        Path junit = request.output().resolve("results.xml");
        List<String> ctest = ProcessRunner.strings(
                "--test-dir",
                build.toString(),
                "--output-on-failure",
                "--no-tests=error",
                "-L",
                "^" + request.phase() + "$",
                "--output-junit",
                junit.toString());
        ctest.addAll(request.args());
        boolean success = ProcessRunner.run(mutable.snapshot(), "ctest", ctest, "tests");
        String junitContent;
        try {
            junitContent = Files.readString(junit, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw AnyhowError.context("CTest results missing", new AnyhowError(RustIoError.display(exception)));
        }
        AdapterResponse result = new AdapterResponse(
                AdapterResponse.PROTOCOL_VERSION, success, Formats.junit(junitContent), List.of(), List.of());
        if (request.coverage()) {
            List<Path> profiles = Formats.files(request.output(), "profraw");
            if (profiles.isEmpty()) {
                throw new AnyhowError("native coverage profiles missing");
            }
            Path profile = request.output().resolve("coverage.profdata");
            List<String> merge = ProcessRunner.strings("merge", "-sparse", "-o", profile.toString());
            profiles.forEach(path -> merge.add(path.toString()));
            if (!ProcessRunner.run(mutable.snapshot(), "llvm-profdata", merge, "profile")) {
                throw new AnyhowError("LLVM profile merge failed");
            }
            JsonNode binaryNode = request.options().get("coverage_binary");
            String binary = binaryNode != null && binaryNode.isString() ? binaryNode.stringValue() : "native-tests";
            List<String> export = ProcessRunner.strings(
                    "export",
                    build.resolve(binary).toString(),
                    "-instr-profile=" + profile,
                    "-format=lcov",
                    "-ignore-filename-regex=(_deps|/usr/|tests/)");
            if (!ProcessRunner.run(mutable.snapshot(), "llvm-cov", export, "coverage")) {
                throw new AnyhowError("LLVM coverage export failed");
            }
            Path lcov = request.output().resolve("coverage.lcov");
            try {
                Files.copy(request.output().resolve("coverage.stdout"), lcov, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException exception) {
                throw new AnyhowError(RustIoError.display(exception), null);
            }
            result.coverage().add(lcov);
        }
        return result;
    }

    private static AdapterResponse command(MutableRequest mutable) {
        AdapterRequest request = mutable.base;
        JsonNode commandNode = request.options().get("command");
        if (commandNode == null || !commandNode.isString()) {
            throw new AnyhowError("command adapter requires options.command");
        }
        String program = commandNode.stringValue();
        boolean success = ProcessRunner.run(mutable.snapshot(), program, request.args(), "tests");
        Path resultPath = request.output().resolve("result.json");
        if (!Files.isRegularFile(resultPath) && !request.strict()) {
            return new AdapterResponse(
                    AdapterResponse.PROTOCOL_VERSION,
                    success,
                    List.of(Formats.testCase(
                            "command: " + program, success ? TestStatus.PASSED : TestStatus.FAILED, null, 0)),
                    List.of(),
                    List.of("No individual test report; result uses the external command exit status"));
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(resultPath);
        } catch (IOException exception) {
            throw AnyhowError.context(
                    "command must write CHERRY_TEST_ADAPTER_OUTPUT/result.json",
                    new AnyhowError(RustIoError.display(exception)));
        }
        AdapterResponse result = AdapterJson.parseResponse(bytes);
        result.success(result.success() && success);
        return result;
    }

    public static Path sourcePath(AdapterRequest request, String path) {
        Path translated = path.startsWith("/workspace/")
                ? ProcessRunner.mountRoot(request).resolve(path.substring("/workspace/".length()))
                : Path.of(path);
        if (translated.isAbsolute()) {
            return translated;
        }
        return request.cwd().resolve(translated);
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new AnyhowError(RustIoError.display(exception), null);
        }
    }

    private static void write(Path path, String content) {
        try {
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new AnyhowError(RustIoError.display(exception), null);
        }
    }

    private static void createDirectories(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException exception) {
            throw new AnyhowError(RustIoError.display(exception), null);
        }
    }
}
