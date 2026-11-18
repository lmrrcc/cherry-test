package com.cherrytest.cli;

import com.cherrytest.cli.adapters.AdapterJson;
import com.cherrytest.cli.adapters.ProcessRunner;
import com.cherrytest.common.adapter.AdapterRequest;
import com.cherrytest.common.adapter.AdapterResponse;
import com.cherrytest.common.process.ProcessEnvironment;
import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.common.rustcompat.SerdeJsonWriter;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

public final class NativeEvents implements AutoCloseable {
    private static final Set<String> EXCLUDED = Set.of(
            ".git",
            ".cherry-test",
            ".testit",
            "target",
            "build",
            "node_modules",
            "dist",
            ".gradle",
            ".idea",
            ".vscode",
            "coverage",
            ".next",
            ".venv",
            "vendor");
    private static final Set<String> EXTENSIONS = Set.of(
            "java", "kt", "kts", "scala", "rs", "ts", "tsx", "js", "jsx", "mjs", "cjs", "go", "c", "h", "cpp", "hpp",
            "cc", "cs", "py", "swift");
    private static final long MAX_SOURCE_BYTES = 64L * 1024 * 1024;
    private final @Nullable BufferedWriter writer;
    private final Path workspace;
    private final String runId;
    private final long started = System.nanoTime();
    private final Snapshot before;
    private final List<Path> sourceRoots;
    private final Set<String> capabilities;
    private final @Nullable NativeSelection selection;
    private @Nullable ObjectNode starting;
    private final @Nullable Path reportDirectory;
    private long sequence;
    private boolean finished;
    private final List<Path> coverageInputs = new java.util.ArrayList<>();
    private boolean collectionsComplete = true;
    private final Map<String, String> progressIds = new java.util.HashMap<>();
    private final Map<String, String> declaredNames = new java.util.HashMap<>();
    private final Map<String, String> reportedNames = new java.util.HashMap<>();
    private final Set<String> ambiguousProgressNames = new java.util.HashSet<>();

    private record Snapshot(SortedMap<String, String> hashes, List<String> errors) {}

    private NativeEvents(
            @Nullable BufferedWriter writer,
            Path workspace,
            String runId,
            @Nullable Boolean coverageEnabled,
            List<String> scope,
            @Nullable Path reportDirectory,
            List<Path> modules) {
        this.writer = writer;
        this.workspace = workspace.toAbsolutePath().normalize();
        this.runId = runId;
        this.reportDirectory = reportDirectory;
        sourceRoots = java.util.stream.Stream.concat(java.util.stream.Stream.of(this.workspace), modules.stream())
                .map(path -> path.toAbsolutePath().normalize())
                .distinct()
                .toList();
        String requested = ProcessEnvironment.get("CHERRY_TEST_IDE_CAPABILITIES");
        capabilities = requested == null ? Set.of() : new java.util.HashSet<>(List.of(requested.split(",")));
        selection = NativeSelection.load();
        before = writer == null ? new Snapshot(new TreeMap<>(), List.of()) : snapshot();
        if (writer != null) {
            ObjectNode node = event("run.started");
            node.put("workspaceRoot", this.workspace.toString());
            node.put("implementation", "cherry-test-java");
            node.put("sourceRoot", this.workspace.toString());
            if (coverageEnabled == null) {
                node.putNull("coverageEnabled");
            } else {
                node.put("coverageEnabled", coverageEnabled.booleanValue());
            }
            ArrayNode scopeNode = node.putArray("scope");
            scope.forEach(scopeNode::add);
            for (String candidate : List.of("cherry-test.yaml", "cherry-test.yml", "cherry-test.toml")) {
                if (Files.isRegularFile(this.workspace.resolve(candidate))) {
                    node.put("configPath", candidate);
                    break;
                }
            }
            ObjectNode supported = node.putObject("capabilities");
            supported.put("testProgress", capabilities.contains("test-events-v1"));
            supported.put(
                    "testProgressScope", "Cherry JUnit extension lifecycle; other adapters report phase progress only");
            supported.put("exactSelectors", true);
            if (capabilities.contains("repositories-v1")) {
                ArrayNode repositories = node.putArray("repositories");
                sourceRoots.stream()
                        .map(NativeEvents::repositoryRoot)
                        .distinct()
                        .forEach(root -> {
                            ObjectNode repository = repositories.addObject();
                            repository.put("id", hashText(root.toString()));
                            repository.put("root", root.toString());
                            repository.put("worktree", root.toString());
                        });
            }
            node.put("compiledIdentity", "unknown");
            node.put("perTestAttribution", false);
            putSnapshot(node, "sourceBefore", before);
            starting = node;
        }
    }

    public static NativeEvents open(
            Path workspace, String runId, boolean coverageEnabled, List<String> scope, Path reportDirectory) {
        return open(workspace, runId, coverageEnabled, scope, reportDirectory, List.of());
    }

    public static NativeEvents open(
            Path workspace,
            String runId,
            boolean coverageEnabled,
            List<String> scope,
            Path reportDirectory,
            List<Path> modules) {
        String configured = ProcessEnvironment.get("CHERRY_TEST_IDE_EVENTS");
        if (configured == null || configured.isBlank()) {
            return new NativeEvents(null, workspace, runId, coverageEnabled, scope, reportDirectory, modules);
        }
        Path output = Path.of(configured);
        if (!output.isAbsolute()) {
            output = workspace.resolve(output);
        }
        return openAt(workspace, runId, output, coverageEnabled, scope, reportDirectory, modules);
    }

    static NativeEvents openAt(Path workspace, String runId, Path output) {
        return openAt(workspace, runId, output, null, List.of(), null, List.of());
    }

    static NativeEvents openAt(
            Path workspace,
            String runId,
            Path output,
            @Nullable Boolean coverageEnabled,
            List<String> scope,
            @Nullable Path reportDirectory,
            List<Path> modules) {
        try {
            Path parent = output.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            return new NativeEvents(
                    Files.newBufferedWriter(
                            output, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND),
                    workspace,
                    runId,
                    coverageEnabled,
                    scope,
                    reportDirectory,
                    modules);
        } catch (IOException error) {
            throw new AnyhowError("open native events: " + error.getMessage());
        }
    }

    public synchronized Plan preparePlan(Plan plan) {
        Plan selected = selection == null ? plan : selection.apply(plan);
        ObjectNode node = starting;
        if (node != null && selection != null) node.put("selectionSHA256", selection.sha256());
        start();
        return selected;
    }

    public void validateSelection(AdapterRequest request, AdapterResponse response) {
        if (selection != null) selection.validateResponse(request, normalizedResponse(request, response));
    }

    private AdapterResponse normalizedResponse(AdapterRequest request, AdapterResponse response) {
        AdapterResponse normalized = response.copy();
        for (int index = 0; index < normalized.tests().size(); index++) {
            var test = normalized.tests().get(index);
            String key = identity(request) + "\n" + test.name();
            String declaration = ambiguousProgressNames.contains(key) ? null : declaredNames.get(key);
            if (declaration != null && !declaration.equals(test.name())) {
                reportedNames.put(identity(request) + "\n" + declaration, test.name());
                normalized
                        .tests()
                        .set(
                                index,
                                new com.cherrytest.common.adapter.TestResult(
                                        declaration,
                                        test.status(),
                                        test.durationMs(),
                                        test.message(),
                                        test.source(),
                                        test.line(),
                                        test.attachments(),
                                        test.isolation()));
            }
        }
        return normalized;
    }

    private void start() {
        ObjectNode node = starting;
        if (node != null) {
            starting = null;
            emit(node);
        }
    }

    boolean testProgressEnabled() {
        return writer != null && capabilities.contains("test-events-v1");
    }

    public synchronized void testProgress(AdapterRequest request, String adapter, JsonNode observation) {
        if (!testProgressEnabled() || observation.path("schemaVersion").asInt() != 1) return;
        String type = observation.path("event").asString();
        if (!type.equals("test.started") && !type.equals("test.completed")) return;
        String name = observation.path("name").asString();
        if (name.isBlank() || observation.path("processId").asLong() <= 0) return;
        String status = observation.path("status").asString();
        if (!Set.of("pending", "passed", "failed", "skipped", "aborted").contains(status)) return;
        start();
        ObjectNode node = phaseEvent(type, request, adapter, adapter);
        ObjectNode test = node.putObject("test");
        String id = hashText(identity(request) + "\n" + name + "\n1");
        test.put("id", id);
        java.util.ArrayList<String> aliases = new java.util.ArrayList<>();
        aliases.add(name);
        for (JsonNode alias : observation.path("reportedNameAliases"))
            if (alias.isString()) aliases.add(alias.asString());
        for (String alias : aliases) {
            String key = identity(request) + "\n" + alias;
            String declared = observation.path("declaredName").asString(name);
            if (declared.matches(
                    "[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}.$]*::[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*"))
                declaredNames.putIfAbsent(key, declared);
            String previous = progressIds.putIfAbsent(key, id);
            if (previous != null && !previous.equals(id)) ambiguousProgressNames.add(key);
        }
        String declaration = observation.path("declaredName").asString(name);
        if (!declaration.matches(
                "[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}.$]*::[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*"))
            declaration = name;
        test.put("name", declaration);
        test.put("displayName", name);
        test.put("status", status);
        test.put("durationMs", observation.path("durationMs").asLong());
        test.put("message", observation.path("message").asString(""));
        test.put("locationRole", "test-source-or-reported-failure");
        test.put("processId", observation.path("processId").asLong());
        test.put("invocationId", observation.path("invocationId").asString());
        node.put("observedTimestamp", observation.path("timestamp").asString());
        emit(node);
    }

    public synchronized void progressUnavailable(AdapterRequest request, String adapter) {
        collectionsComplete = false;
    }

    public synchronized void phaseStarted(AdapterRequest request, String adapter, String runtime) {
        if (writer == null) {
            return;
        }
        start();
        ObjectNode node = phaseEvent("phase.started", request, adapter, runtime);
        node.put("coverageRequested", request.coverage());
        node.put("execution", request.execution().wireName());
        emit(node);
    }

    public synchronized void phaseCompleted(
            AdapterRequest request,
            String adapter,
            String runtime,
            AdapterResponse response,
            boolean adapterCompleted) {
        if (writer == null) {
            return;
        }
        start();
        ObjectNode node = phaseEvent("phase.completed", request, adapter, runtime);
        collectionsComplete &= adapterCompleted && !ProcessRunner.cancelled();
        node.put("success", response.success());
        node.put(
                "failureScope",
                response.success()
                        ? "none"
                        : response.tests().stream()
                                        .anyMatch(test ->
                                                test.status() == com.cherrytest.common.adapter.TestStatus.FAILED)
                                ? "tests"
                                : "execution");
        node.put("coverageRequested", request.coverage());
        node.put(
                "coverageAvailability",
                !request.coverage() ? "disabled" : response.coverage().isEmpty() ? "unavailable" : "present");
        node.put(
                "completeness", ProcessRunner.cancelled() ? "cancelled" : adapterCompleted ? "complete" : "incomplete");
        node.put("testInventoryComplete", "unknown");
        node.put("coverageComplete", "unknown");
        node.put("compiledIdentity", "unknown");
        node.put("perTestAttribution", false);
        AdapterResponse nativeResponse = normalizedResponse(request, response);
        ObjectNode responseValue = AdapterJson.responseValue(nativeResponse);
        node.set("diagnostics", responseValue.get("diagnostics"));
        ArrayNode tests = node.putArray("tests");
        Map<String, Integer> totalNames = new TreeMap<>();
        nativeResponse.tests().forEach(test -> totalNames.merge(test.name(), 1, Integer::sum));
        Map<String, Integer> occurrences = new TreeMap<>();
        for (JsonNode test : responseValue.path("tests")) {
            ObjectNode value = (ObjectNode) test.deepCopy();
            String name = test.path("name").asString();
            int occurrence = occurrences.merge(name, 1, Integer::sum);
            String key = identity(request) + "\n" + name;
            String observedId = totalNames.getOrDefault(name, 0) == 1 && !ambiguousProgressNames.contains(key)
                    ? progressIds.get(key)
                    : null;
            value.put(
                    "id",
                    observedId == null ? hashText(identity(request) + "\n" + name + "\n" + occurrence) : observedId);
            value.set("durationMs", value.remove("duration_ms"));
            String source = test.path("source").isString() ? test.path("source").asString() : null;
            if (source != null) {
                value.put("source", sourcePath(Path.of(source)));
            }
            String reported = reportedNames.get(identity(request) + "\n" + name);
            if (reported != null) value.put("displayName", reported);
            value.put("locationRole", "test-source-or-reported-failure");
            if (capabilities.contains("selectors-v1")) {
                var actual = nativeResponse.tests().stream()
                        .filter(result -> result.name().equals(name))
                        .findFirst()
                        .orElseThrow();
                value.set(
                        "selector",
                        NativeSelection.selector(request, adapter, actual, totalNames.getOrDefault(name, 0)));
            }
            tests.add(value);
        }
        ArrayNode coverage = node.putArray("coverage");
        ArrayNode precision = node.putArray("coveragePrecisionArtifacts");
        for (Path path : response.coverage()) {
            addArtifact(coverage, path, "lcov");
            Path sidecar = path.resolveSibling(path.getFileName() + ".native.json");
            if (Files.isRegularFile(sidecar)) {
                addArtifact(precision, sidecar, "jacoco-lines-v1");
                coverageInputs.add(sidecar);
            } else {
                coverageInputs.add(path);
            }
        }
        ArrayNode logs = node.putArray("logs");
        for (String name : List.of("tests.stdout", "tests.stderr", "adapter.stdout", "adapter.stderr")) {
            Path log = request.output().resolve(name);
            if (Files.isRegularFile(log)) {
                addArtifact(logs, log, "text");
            }
        }
        node.put("resultPath", request.output().resolve("result.json").toString());
        emit(node);
    }

    public synchronized int complete(int exitCode) {
        terminal(
                ProcessRunner.cancelled() ? "cancelled" : exitCode == 0 ? "passed" : "failed",
                !ProcessRunner.cancelled(),
                null);
        return exitCode;
    }

    public synchronized void failed(String rawFailure) {
        terminal(ProcessRunner.cancelled() ? "cancelled" : "error", false, rawFailure);
    }

    private void terminal(String outcome, boolean complete, @Nullable String rawFailure) {
        if (writer == null || finished) {
            return;
        }
        start();
        Snapshot after = snapshot();
        ObjectNode node = event("run.completed");
        node.put("outcome", outcome);
        Path reports = reportDirectory;
        if (reports != null) {
            if (complete && collectionsComplete && coverageInputs.size() > 1) {
                try {
                    var merged =
                            com.cherrytest.cli.adapters.CoverageUnion.merge(workspace, coverageInputs, sourceRoots);
                    if (!merged.path("files").isEmpty()) {
                        Path artifact = reports.resolve("native-coverage-union.json");
                        Files.writeString(artifact, SerdeJsonWriter.pretty(merged));
                        addArtifact(node.putArray("coverageUnion"), artifact, "jacoco-lines-v1");
                    }
                } catch (IOException | RuntimeException invalid) {
                    node.put("coverageUnionError", "Coverage union withheld: " + invalid.getMessage());
                }
            }
            node.put("resultDirectory", reports.toString());
            Path html = reports.resolve("index.html");
            if (Files.isRegularFile(html)) {
                node.put("htmlReport", html.toString());
            } else {
                node.putNull("htmlReport");
            }
        }
        node.put("complete", complete);
        node.put("compiledIdentity", "unknown");
        node.put("perTestAttribution", false);
        putSnapshot(node, "sourceAfter", after);
        node.put(
                "sourceStable",
                before.errors().isEmpty()
                        && after.errors().isEmpty()
                        && before.hashes().equals(after.hashes()));
        node.put("rawFailure", rawFailure);
        emit(node);
        finished = true;
    }

    private ObjectNode phaseEvent(String type, AdapterRequest request, String adapter, String runtime) {
        ObjectNode node = event(type);
        node.put("projectId", request.project());
        Path parent = request.manifest().getParent();
        node.put("moduleId", parent == null ? "." : sourcePath(parent));
        node.put("packageId", request.packageName());
        node.put("phaseId", request.phase());
        node.put("runtimeId", adapter);
        node.put("configuredPackageRuntime", runtime);
        node.put("phaseKey", phaseKey(request));
        if (capabilities.contains("repositories-v1")) {
            Path module = request.manifest().toAbsolutePath().normalize().getParent();
            if (module == null) module = workspace;
            node.put("moduleRoot", module.toString());
            node.put("repositoryId", hashText(repositoryRoot(module).toString()));
        }
        return node;
    }

    static String phaseKey(AdapterRequest request) {
        return hashText(identity(request));
    }

    private static String identity(AdapterRequest request) {
        Path root = request.root().toAbsolutePath().normalize();
        Path manifest = request.manifest().toAbsolutePath().normalize();
        String path = manifest.startsWith(root)
                ? root.relativize(manifest).toString().replace('\\', '/')
                : manifest.toString();
        return request.project() + "\n" + path + "\n" + request.packageName() + "\n" + request.phase();
    }

    private static Path repositoryRoot(Path module) {
        Path normalized = module.toAbsolutePath().normalize();
        for (Path candidate = normalized; candidate != null; candidate = candidate.getParent()) {
            if (Files.exists(candidate.resolve(".git"))) return candidate;
        }
        return normalized;
    }

    private ObjectNode event(String type) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("schemaVersion", 1);
        node.put("event", type);
        node.put("runId", runId);
        node.put("sequence", sequence++);
        node.put("timestamp", Instant.now().toString());
        node.put("elapsedNanos", System.nanoTime() - started);
        return node;
    }

    private void addArtifact(ArrayNode array, Path path, String format) {
        ObjectNode artifact = array.addObject();
        artifact.put("path", path.toAbsolutePath().normalize().toString());
        artifact.put("format", format);
        try {
            artifact.put("sha256", hashFile(path));
        } catch (IOException error) {
            artifact.putNull("sha256");
            artifact.put("error", error.getMessage());
        }
    }

    private String sourcePath(Path path) {
        Path absolute =
                path.isAbsolute() ? path.normalize() : workspace.resolve(path).normalize();
        if (!absolute.startsWith(workspace)) {
            return absolute.toString();
        }
        String relative = workspace.relativize(absolute).toString().replace('\\', '/');
        return relative.isEmpty() ? "." : relative;
    }

    private static void putSnapshot(ObjectNode node, String key, Snapshot snapshot) {
        ObjectNode hashes = node.putObject(key);
        snapshot.hashes().forEach(hashes::put);
        node.put(key + "Complete", snapshot.errors().isEmpty());
        ArrayNode errors = node.putArray(key + "Errors");
        snapshot.errors().forEach(errors::add);
    }

    private Snapshot snapshot() {
        TreeMap<String, String> hashes = new TreeMap<>();
        java.util.ArrayList<String> errors = new java.util.ArrayList<>();
        for (Path sourceRoot : sourceRoots) {
            try {
                Files.walkFileTree(sourceRoot, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                        Path name = directory.getFileName();
                        return !directory.equals(sourceRoot) && name != null && EXCLUDED.contains(name.toString())
                                ? FileVisitResult.SKIP_SUBTREE
                                : FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                        Path name = file.getFileName();
                        String filename = name == null ? "" : name.toString();
                        int dot = filename.lastIndexOf('.');
                        if (attributes.isRegularFile()
                                && dot >= 0
                                && EXTENSIONS.contains(filename.substring(dot + 1))) {
                            try {
                                if (attributes.size() > MAX_SOURCE_BYTES || hashes.size() >= 200_000) {
                                    errors.add("source capture limit: " + sourcePath(file));
                                } else {
                                    hashes.put(sourcePath(file), hashFile(file));
                                }
                            } catch (IOException error) {
                                errors.add(sourcePath(file) + ": " + error.getMessage());
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException error) {
                        errors.add(sourcePath(file) + ": " + error.getMessage());
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException error) {
                errors.add(error.getMessage() == null ? error.toString() : error.getMessage());
            }
        }
        return new Snapshot(hashes, List.copyOf(errors));
    }

    private static String hashFile(Path path) throws IOException {
        MessageDigest digest = digest();
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String hashText(String text) {
        return hashBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    static String hashBytes(byte[] bytes) {
        return HexFormat.of().formatHex(digest().digest(bytes));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private void emit(ObjectNode node) {
        if (writer == null) {
            return;
        }
        try {
            writer.write(SerdeJsonWriter.compact(node));
            writer.newLine();
            writer.flush();
        } catch (IOException error) {
            throw new AnyhowError("write native events: " + error.getMessage());
        }
    }

    @Override
    public synchronized void close() {
        if (writer == null) {
            return;
        }
        try (writer) {
            terminal("error", false, "orchestration ended without a terminal result");
        } catch (IOException error) {
            throw new AnyhowError("close native events: " + error.getMessage());
        }
    }
}
