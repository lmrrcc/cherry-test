package com.cherrytest.cli.adapters;

import com.cherrytest.cli.nativeos.Terminal;
import com.cherrytest.common.adapter.AdapterConfig;
import com.cherrytest.common.adapter.AdapterRequest;
import com.cherrytest.common.adapter.AdapterResponse;
import com.cherrytest.common.adapter.Attachment;
import com.cherrytest.common.adapter.TestResult;
import com.cherrytest.common.adapter.TestStatus;
import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.common.rustcompat.RustIoError;
import com.cherrytest.common.rustcompat.RustParse;
import com.cherrytest.common.rustcompat.RustPath;
import com.cherrytest.common.rustcompat.RustText;
import com.cherrytest.common.rustcompat.SerdeJsonWriter;
import com.cherrytest.core.TestId;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

public final class Adapters {
    private static final long STDIN_LIMIT = 4L * 1024 * 1024;
    private static final long RESPONSE_LIMIT = 32L * 1024 * 1024;
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private Adapters() {}

    public static int cli(String name, String operation) {
        if (!Builtins.NAMES.contains(name)) {
            throw new AnyhowError("unknown adapter " + name);
        }
        ObjectNode response = switch (operation) {
            case "describe" -> {
                ObjectNode node = NODES.objectNode();
                ArrayNode execution = node.putArray("execution");
                execution.add("local");
                execution.add("container");
                node.put("name", name);
                ArrayNode operations = node.putArray("operations");
                operations.add("describe");
                operations.add("discover");
                operations.add("run");
                node.put("protocol", AdapterResponse.PROTOCOL_VERSION);
                yield node;
            }
            case "discover" -> {
                JsonNode request = Json.parseBytes(readStdin());
                JsonNode rootNode = Json.index(request, "root");
                if (!rootNode.isString()) {
                    throw new AnyhowError("discovery root missing");
                }
                String root = rootNode.stringValue();
                List<String> manifests = switch (name) {
                    case "cargo" -> List.of("Cargo.toml");
                    case "maven" -> List.of("pom.xml");
                    case "gradle" -> List.of("build.gradle", "build.gradle.kts");
                    case "cmake" -> List.of("CMakeLists.txt");
                    default -> List.of("package.json");
                };
                List<String> found = new ArrayList<>();
                discover(Path.of(root), Path.of(root), manifests, 0, found);
                ObjectNode node = NODES.objectNode();
                ArrayNode list = node.putArray("manifests");
                found.forEach(list::add);
                node.put("protocol", AdapterResponse.PROTOCOL_VERSION);
                yield node;
            }
            case "run" -> {
                AdapterRequest request = AdapterJson.parseRequest(readStdin());
                if (request.protocol() != AdapterResponse.PROTOCOL_VERSION) {
                    throw new AnyhowError("unsupported adapter protocol");
                }
                AdapterResponse result;
                try {
                    result = Builtins.run(name, request);
                } catch (RuntimeException error) {
                    result = AdapterResponse.failure(AnyhowError.alternate(error));
                }
                yield AdapterJson.responseValue(result);
            }
            default -> throw new AnyhowError("unsupported adapter operation");
        };
        Terminal.OUT.print(SerdeJsonWriter.compact(response));
        Terminal.OUT.flush();
        return 0;
    }

    private static byte[] readStdin() {
        try {
            InputStream input = System.in;
            return input.readNBytes((int) STDIN_LIMIT);
        } catch (IOException exception) {
            throw new AnyhowError(RustIoError.display(exception), null);
        }
    }

    private static void discover(Path root, Path directory, List<String> names, int depth, List<String> found) {
        if (depth > 6) {
            return;
        }
        for (String name : names) {
            if (Files.isRegularFile(directory.resolve(name))) {
                found.add(root.relativize(directory.resolve(name)).toString());
            }
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                Path fileName = entry.getFileName();
                String name = fileName == null ? "" : fileName.toString();
                if (Files.isDirectory(entry, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                        && !name.startsWith(".")
                        && !List.of("target", "build", "node_modules", "dist").contains(name)) {
                    discover(root, entry, names, depth + 1, found);
                }
            }
        } catch (IOException exception) {
            throw new AnyhowError(RustIoError.display(exception), null);
        }
    }

    public static AdapterResponse invoke(
            String name, @Nullable AdapterConfig config, AdapterRequest request, SelfCommand self) {
        createDirectories(request.output());
        ProcessRunner.Spec spec;
        Path requestFile = request.output().resolve("request.json");
        if (config != null) {
            List<String> arguments = new ArrayList<>(config.args());
            arguments.add("run");
            spec = new ProcessRunner.Spec(config.command(), arguments, Map.of(), request.root(), requestFile);
        } else {
            if (!Builtins.NAMES.contains(name)) {
                throw new AnyhowError("adapter `" + name + "` is not registered");
            }
            spec = self.adapterSpec(name, request.root(), requestFile);
        }
        try {
            Files.writeString(requestFile, AdapterJson.requestCompact(request), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new AnyhowError(RustIoError.display(exception), null);
        }
        Path prefix = request.output().resolve("adapter");
        boolean success = ProcessRunner.capture(
                spec, prefix, config == null ? AdapterConfig.DEFAULT_TIMEOUT_SECS : config.timeoutSecs(), true);
        Path stdout = RustPath.withExtension(prefix, "stdout");
        byte[] data;
        try {
            if (Files.size(stdout) > RESPONSE_LIMIT) {
                throw new AnyhowError("adapter response exceeds 32 MiB");
            }
            data = Files.readAllBytes(stdout);
        } catch (IOException exception) {
            throw new AnyhowError(RustIoError.display(exception), null);
        }
        AdapterResponse response;
        try {
            response = AdapterJson.parseResponse(data);
        } catch (AnyhowError error) {
            throw AnyhowError.context("invalid adapter JSON response", error);
        }
        response.success(response.success() && success);
        validate(request, response);
        return response;
    }

    public interface SelfCommand {
        ProcessRunner.Spec adapterSpec(String name, Path workingDirectory, Path stdin);
    }

    public static void validate(AdapterRequest request, AdapterResponse response) {
        if (response.protocol() != AdapterResponse.PROTOCOL_VERSION) {
            throw new AnyhowError("adapter protocol version mismatch");
        }
        Set<String> names = new HashSet<>();
        List<TestResult> tests = response.tests();
        for (int index = 0; index < tests.size(); index++) {
            TestResult test = tests.get(index);
            if (RustText.trim(test.name()).isEmpty() || !names.add(test.name())) {
                throw new AnyhowError("empty or duplicate adapter test name");
            }
            if (test.status() == TestStatus.FAILED || (request.strict() && test.status() == TestStatus.SKIPPED)) {
                response.success(false);
            }
            if (test.source() != null) {
                Path resolved = resolveTestSource(request, test.source());
                Path source = resolved != null
                        ? resolved
                        : Builtins.sourcePath(request, test.source().toString());
                if (Files.exists(source)
                        && !RustPath.canonicalize(source).startsWith(RustPath.canonicalize(request.root()))) {
                    throw new AnyhowError("test source escapes workspace");
                }
                test = test.withSource(source);
            }
            List<Attachment> attachments = new ArrayList<>();
            for (Attachment attachment : test.attachments()) {
                Path path = Builtins.sourcePath(request, attachment.path().toString());
                confined(request.output(), path);
                attachments.add(attachment.withPath(path));
            }
            tests.set(index, test.withAttachments(attachments));
        }
        List<Path> coverage = response.coverage();
        for (int index = 0; index < coverage.size(); index++) {
            Path path = Builtins.sourcePath(request, coverage.get(index).toString());
            coverage.set(index, path);
            confined(request.output(), path);
            String content;
            try {
                content = Files.readString(path, StandardCharsets.UTF_8);
            } catch (IOException exception) {
                throw new AnyhowError(RustIoError.display(exception), null);
            }
            validateLcov(content);
        }
        if (request.strict() && response.tests().isEmpty()) {
            response.success(false);
            response.diagnostics().add("required phase produced zero tests");
        }
        if (request.coverage() && request.strict() && response.coverage().isEmpty()) {
            response.success(false);
            response.diagnostics().add("required coverage artifact missing");
        }
    }

    static void validateLcov(String content) {
        boolean active = false;
        long sources = 0;
        long lines = 0;
        for (String line : RustText.lines(content)) {
            if (line.startsWith("SF:")) {
                String source = line.substring(3);
                if (active || RustText.trim(source).isEmpty()) {
                    throw new AnyhowError("invalid LCOV source record");
                }
                active = true;
                sources++;
            } else if (line.startsWith("DA:")) {
                if (!active) {
                    throw new AnyhowError("LCOV line outside source record");
                }
                String[] values = line.substring(3).split(",", -1);
                long number = RustParse.u64(values[0]);
                if (values.length < 2) {
                    throw new AnyhowError("LCOV hit count missing");
                }
                RustParse.u64(values[1]);
                if (number == 0) {
                    throw new AnyhowError("LCOV line numbers start at one");
                }
                lines++;
            } else if (line.equals("end_of_record")) {
                if (!active) {
                    throw new AnyhowError("LCOV record has no source");
                }
                active = false;
            }
        }
        if (active || sources == 0 || lines == 0) {
            throw new AnyhowError("coverage contains no complete measured source records");
        }
    }

    private static void confined(Path root, Path path) {
        if (!RustPath.canonicalize(path).startsWith(RustPath.canonicalize(root))) {
            throw new AnyhowError("artifact escapes adapter output directory");
        }
        if (!Files.isRegularFile(path)) {
            throw new AnyhowError("artifact is not a file");
        }
    }

    static @Nullable Path resolveTestSource(AdapterRequest request, Path source) {
        Path direct = Builtins.sourcePath(request, source.toString());
        if (Files.isRegularFile(direct)) {
            return direct;
        }
        Path directory = request.cwd().getParent();
        while (directory != null && directory.startsWith(request.root())) {
            Path candidate = directory.resolve(source);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            directory = directory.getParent();
        }
        return null;
    }

    public static AdapterResponse save(AdapterRequest request, AdapterResponse input, Path reports) {
        AdapterResponse response = input.copy();
        if (!response.success() && response.tests().stream().noneMatch(test -> test.status() == TestStatus.FAILED)) {
            response.tests()
                    .add(Formats.testCase("runner", TestStatus.FAILED, String.join("\n", response.diagnostics()), 0));
        }
        Path directory = reports.resolve("adapters")
                .resolve(request.project())
                .resolve(request.packageName())
                .resolve(request.phase());
        createDirectories(directory);
        ObjectNode document = NODES.objectNode();
        document.put("package", request.packageName());
        document.put("phase", request.phase());
        document.put("project", request.project());
        document.set("result", AdapterJson.responseValue(response));
        try {
            Files.writeString(
                    directory.resolve("result.json"), SerdeJsonWriter.pretty(document), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new AnyhowError(RustIoError.display(exception), null);
        }
        return response;
    }

    public static List<ObjectNode> readRows(Path reports) {
        List<ObjectNode> rows = new ArrayList<>();
        for (Path path : Formats.files(reports.resolve("adapters"), "json")) {
            Path fileName = path.getFileName();
            if (fileName == null || !fileName.toString().equals("result.json")) {
                continue;
            }
            JsonNode document;
            try {
                document = Json.parseBytes(Files.readAllBytes(path));
            } catch (IOException exception) {
                throw new AnyhowError(RustIoError.display(exception), null);
            }
            String project = requireString(document, "project", "result project");
            String packageName = requireString(document, "package", "result package");
            String phase = requireString(document, "phase", "result phase");
            AdapterResponse result = AdapterJson.responseFrom(Json.index(document, "result"));
            for (TestResult test : result.tests()) {
                ObjectNode row = NODES.objectNode();
                row.set(
                        "elapsed_ms",
                        NODES.numberNode(new java.math.BigInteger(Long.toUnsignedString(test.durationMs()))));
                row.put("full_name", project + "/" + packageName + "/" + phase + "/" + test.name());
                if (test.line() == null) {
                    row.putNull("line");
                } else {
                    row.put("line", test.line());
                }
                row.put("project", project);
                if (test.message() == null) {
                    row.putNull("reason");
                } else {
                    row.put("reason", test.message());
                }
                row.put("scope", "none");
                if (test.source() == null) {
                    row.putNull("source");
                } else {
                    row.put("source", test.source().toString());
                }
                row.put(
                        "status",
                        switch (test.status()) {
                            case PASSED -> "ok";
                            case FAILED -> "FAIL";
                            case SKIPPED -> "skipped";
                        });
                row.put("suite", phase);
                row.put("target_project", project);
                row.put("target_service", packageName);
                row.put("test", packageName + ": " + test.name());
                if (phase.equals("integration")) {
                    String tail = test.name();
                    int separator = tail.lastIndexOf("::");
                    if (separator >= 0) {
                        tail = tail.substring(separator + 2);
                    }
                    if (TestId.isValid(tail)) {
                        TestId id = TestId.parse(tail);
                        row.put("project", id.project());
                        row.put("suite", id.suite());
                        row.put("test", id.test());
                        row.put("full_name", id.fullName());
                    }
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private static String requireString(JsonNode document, String field, String context) {
        JsonNode value = Json.index(document, field);
        if (!value.isString()) {
            throw new AnyhowError(context);
        }
        return value.stringValue();
    }

    private static void createDirectories(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException exception) {
            throw new AnyhowError(RustIoError.display(exception), null);
        }
    }
}
