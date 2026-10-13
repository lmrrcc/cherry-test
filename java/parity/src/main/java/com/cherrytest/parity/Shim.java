package com.cherrytest.parity;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

public final class Shim {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final PrintStream ERR = System.err;
    private static final Set<String> SHELL_VARIABLES = Set.of("SHLVL", "_", "OLDPWD");
    private static final List<String> LAUNCHER_PREFIXES =
            List.of("JAVA_MAIN_CLASS_", "JAVA_STARTED_ON_FIRST_THREAD_", "CHERRY_PARITY_");
    private static final List<String> SECRET_MARKERS =
            List.of("AUTH", "TOKEN", "SECRET", "PASSWORD", "KEY", "CREDENTIAL");

    private Shim() {}

    record Invocation(
            String tool,
            List<String> argv,
            String cwd,
            String runId,
            @Nullable Path root,
            @Nullable Path runDir,
            Map<String, String> environment) {
        List<String> normalizedArgv() {
            return argv.stream()
                    .map(argument -> Placeholders.normalize(argument, runId))
                    .toList();
        }

        String normalizedCwd() {
            return Placeholders.normalize(cwd, runId);
        }

        Map<String, String> comparableEnvironment() {
            Map<String, String> comparable = new TreeMap<>();
            environment.forEach((key, value) -> {
                if (SHELL_VARIABLES.contains(key) || LAUNCHER_PREFIXES.stream().anyMatch(key::startsWith)) {
                    return;
                }
                String normalized = Placeholders.normalize(value, runId);
                String shims = environment.get("CHERRY_PARITY_SHIM_DIR");
                if (shims != null && !shims.isEmpty()) {
                    normalized = normalized.replace(shims, Placeholders.SHIM_DIR);
                }
                comparable.put(key, secret(key) ? "sha256:" + Blobs.sha256(utf8(normalized)) : normalized);
            });
            return comparable;
        }
    }

    public static void main(String[] arguments) throws Exception {
        Map<String, String> environment = System.getenv();
        Path shimDir = Path.of(environment.getOrDefault("CHERRY_PARITY_SHIM_DIR", ""));
        String mode = environment.containsKey("CHERRY_PARITY_NESTED")
                ? "passthrough"
                : environment.getOrDefault("CHERRY_PARITY_MODE", "passthrough");
        Invocation invocation = new Invocation(
                arguments[0],
                List.of(arguments).subList(1, arguments.length),
                Path.of("").toAbsolutePath().toString(),
                environment.getOrDefault("CHERRY_TEST_RUN_ID", ""),
                optionalPath(environment.get("CHERRY_PARITY_ROOT")),
                optionalPath(environment.get("CHERRY_TEST_RUN_DIR")),
                environment);
        Cassette cassette = new Cassette(Path.of(environment.getOrDefault("CHERRY_PARITY_CASSETTE", "/nonexistent")));
        int exit = switch (mode) {
            case "record" -> record(invocation, shimDir, cassette);
            case "replay" -> replay(invocation, cassette);
            default -> passthrough(invocation, shimDir);
        };
        System.out.flush();
        System.err.flush();
        System.exit(exit);
    }

    private static @Nullable Path optionalPath(@Nullable String value) {
        return value == null || value.isEmpty() ? null : Path.of(value).toAbsolutePath();
    }

    private static boolean secret(String key) {
        String upper = key.toUpperCase(Locale.ROOT);
        return SECRET_MARKERS.stream().anyMatch(upper::contains);
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String realExecutable(String tool, Path shimDir) {
        String path = System.getenv().getOrDefault("PATH", "");
        for (String entry : path.split(File.pathSeparator, -1)) {
            if (entry.isEmpty()) {
                continue;
            }
            Path directory = Path.of(entry);
            if (sameDirectory(directory, shimDir)) {
                continue;
            }
            Path candidate = directory.resolve(tool);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }
        throw new IllegalStateException("parity shim: real executable not found for " + tool);
    }

    private static boolean sameDirectory(Path left, Path right) {
        try {
            return Files.exists(left) && Files.exists(right) && Files.isSameFile(left, right);
        } catch (IOException exception) {
            return false;
        }
    }

    private static void prepareChild(Map<String, String> child, Path shimDir) {
        String path = System.getenv().getOrDefault("PATH", "");
        List<String> kept = new ArrayList<>();
        for (String entry : path.split(File.pathSeparator, -1)) {
            if (!entry.isEmpty() && sameDirectory(Path.of(entry), shimDir)) {
                continue;
            }
            kept.add(entry);
        }
        child.put("PATH", String.join(File.pathSeparator, kept));
        String nvmBin = System.getenv().getOrDefault("CHERRY_PARITY_NVM_BIN", "");
        if (nvmBin.isEmpty()) {
            child.remove("NVM_BIN");
        } else {
            child.put("NVM_BIN", nvmBin);
        }
        child.put("CHERRY_PARITY_NESTED", "1");
    }

    private static int passthrough(Invocation invocation, Path shimDir) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(realExecutable(invocation.tool(), shimDir));
        command.addAll(invocation.argv());
        ProcessBuilder builder = new ProcessBuilder(command).inheritIO();
        prepareChild(builder.environment(), shimDir);
        return builder.start().waitFor();
    }

    private static boolean tracksFiles(Invocation invocation) {
        return !invocation.tool().equals("git");
    }

    private static Snapshot snapshot(Invocation invocation) throws IOException {
        return tracksFiles(invocation) ? Snapshot.of(invocation.root(), invocation.runDir()) : Snapshot.of(null, null);
    }

    private static Set<Long> standardStreamInodes() throws IOException {
        Set<Long> inodes = new HashSet<>();
        for (String descriptor : List.of("/dev/fd/1", "/dev/fd/2")) {
            Path stream = Path.of(descriptor);
            if (Files.isRegularFile(stream)) {
                inodes.add(Snapshot.inode(stream));
            }
        }
        return inodes;
    }

    private static int record(Invocation invocation, Path shimDir, Cassette cassette) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(realExecutable(invocation.tool(), shimDir));
        command.addAll(invocation.argv());
        Snapshot before = snapshot(invocation);
        ProcessBuilder builder = new ProcessBuilder(command);
        prepareChild(builder.environment(), shimDir);
        builder.redirectInput(ProcessBuilder.Redirect.INHERIT);
        Process process = builder.start();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit;
        try (ExecutorService pumps = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> out = pumps.submit(() -> tee(process.getInputStream(), System.out, stdout));
            Future<?> err = pumps.submit(() -> tee(process.getErrorStream(), System.err, stderr));
            exit = process.waitFor();
            out.get();
            err.get();
        }
        Snapshot after = snapshot(invocation);
        Set<Long> streams = standardStreamInodes();
        String runId = invocation.runId();
        ObjectNode entry = JsonNodeFactory.instance.objectNode();
        entry.put("tool", invocation.tool());
        ArrayNode args = entry.putArray("argv");
        invocation.normalizedArgv().forEach(args::add);
        entry.put("cwd", invocation.normalizedCwd());
        ObjectNode environment = entry.putObject("env");
        invocation.comparableEnvironment().forEach(environment::put);
        entry.put("exit", exit);
        entry.put("stdout", cassette.storeText(stdout.toByteArray(), runId));
        entry.put("stderr", cassette.storeText(stderr.toByteArray(), runId));
        ArrayNode files = entry.putArray("files");
        for (Map.Entry<String, Snapshot.Entry> changed : after.changedSince(before, streams)) {
            ObjectNode item = files.addObject();
            item.put("path", Placeholders.normalize(changed.getKey(), runId));
            Path file = after.root().resolve(changed.getKey());
            switch (changed.getValue()) {
                case Snapshot.Directory _ -> item.put("kind", "dir");
                case Snapshot.Link link -> {
                    item.put("kind", "link");
                    item.put("target", Placeholders.normalize(link.target(), runId));
                }
                case Snapshot.RegularFile _ -> {
                    item.put("kind", "file");
                    item.put("blob", cassette.storeFile(file, runId));
                    item.put("executable", Files.isExecutable(file));
                }
            }
        }
        ArrayNode deleted = entry.putArray("deleted");
        before.deletedIn(after).forEach(path -> deleted.add(Placeholders.normalize(path, runId)));
        cassette.locked(() -> {
            cassette.append(entry);
            cassette.rememberAuthTokens(invocation.environment());
            return entry;
        });
        return exit;
    }

    private static int replay(Invocation invocation, Cassette cassette) throws Exception {
        List<String> argv = invocation.normalizedArgv();
        String cwd = invocation.normalizedCwd();
        @Nullable
        JsonNode entry = cassette.<@Nullable JsonNode>locked(() -> cassette.next(invocation.tool(), argv, cwd));
        if (entry == null) {
            String message =
                    "parity replay miss: " + invocation.tool() + " " + String.join(" ", argv) + " (cwd " + cwd + ")";
            cassette.appendLog("replay-misses.log", message);
            ERR.println(message);
            return 127;
        }
        compareEnvironment(invocation, entry, cassette);
        String runId = invocation.runId();
        Path root = invocation.root();
        if (root == null) {
            throw new IllegalStateException("parity shim: CHERRY_PARITY_ROOT is required for replay");
        }
        for (JsonNode removed : entry.get("deleted")) {
            Files.deleteIfExists(root.resolve(Placeholders.expand(removed.stringValue(), runId)));
        }
        for (JsonNode file : entry.get("files")) {
            Path target = root.resolve(Placeholders.expand(file.get("path").stringValue(), runId));
            switch (file.get("kind").stringValue()) {
                case "dir" -> Files.createDirectories(target);
                case "link" -> {
                    Files.deleteIfExists(target);
                    Files.createSymbolicLink(
                            target,
                            Path.of(Placeholders.expand(file.get("target").stringValue(), runId)));
                }
                default -> {
                    Path parent = target.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    cassette.restore(file.get("blob").stringValue(), target, runId);
                    if (!target.toFile().setExecutable(file.get("executable").booleanValue(), false)) {
                        throw new IOException("parity shim: cannot set the executable bit on " + target);
                    }
                }
            }
        }
        System.out.write(cassette.loadText(entry.get("stdout").stringValue(), runId));
        System.err.write(cassette.loadText(entry.get("stderr").stringValue(), runId));
        return entry.get("exit").intValue();
    }

    private static void compareEnvironment(Invocation invocation, JsonNode entry, Cassette cassette)
            throws IOException {
        Map<String, String> recorded = new TreeMap<>();
        entry.get("env")
                .properties()
                .forEach(field -> recorded.put(field.getKey(), field.getValue().stringValue()));
        Map<String, String> actual = invocation.comparableEnvironment();
        Set<String> keys = new TreeSet<>(recorded.keySet());
        keys.addAll(actual.keySet());
        if (Placeholders.isComposePort(invocation.tool(), invocation.argv())) {
            keys.removeIf(key -> Placeholders.ENDPOINT_VARIABLE.matcher(key).matches());
        }
        List<String> differences = new ArrayList<>();
        for (String key : keys) {
            String expected = recorded.get(key);
            String found = actual.get(key);
            if (expected == null || !expected.equals(found)) {
                differences.add("  " + key + ": rust=" + expected + " java=" + found);
            }
        }
        if (!differences.isEmpty()) {
            cassette.appendLog(
                    "replay-environment.log",
                    invocation.tool() + " " + String.join(" ", invocation.normalizedArgv()) + "\n"
                            + String.join("\n", differences));
        }
    }

    private static @Nullable Void tee(InputStream input, OutputStream live, ByteArrayOutputStream copy)
            throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            live.write(buffer, 0, read);
            live.flush();
            copy.write(buffer, 0, read);
        }
        return null;
    }

    @FunctionalInterface
    interface LockedAction<T extends @Nullable Object> {
        T run() throws IOException;
    }

    static final class Cassette {
        private final Path directory;

        Cassette(Path directory) {
            this.directory = directory;
        }

        <T extends @Nullable Object> T locked(LockedAction<T> action) throws IOException {
            Files.createDirectories(directory);
            try (FileChannel channel =
                    FileChannel.open(directory.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                if (!channel.lock().isValid()) {
                    throw new IOException("parity shim: cassette lock is not valid");
                }
                return action.run();
            }
        }

        void append(ObjectNode entry) throws IOException {
            Files.writeString(
                    directory.resolve("index.jsonl"),
                    JSON.writeValueAsString(entry) + "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        }

        @Nullable
        JsonNode next(String tool, List<String> argv, String cwd) throws IOException {
            Path index = directory.resolve("index.jsonl");
            Path state = directory.resolve("replay-state.txt");
            List<String> consumed = Files.exists(state) ? Files.readAllLines(state, StandardCharsets.UTF_8) : List.of();
            List<String> lines = Files.readAllLines(index, StandardCharsets.UTF_8);
            for (int number = 0; number < lines.size(); number++) {
                if (consumed.contains(Integer.toString(number))) {
                    continue;
                }
                JsonNode entry = JSON.readTree(lines.get(number));
                if (!entry.get("tool").stringValue().equals(tool)
                        || !entry.get("cwd").stringValue().equals(cwd)) {
                    continue;
                }
                List<String> recorded = new ArrayList<>();
                entry.get("argv").forEach(argument -> recorded.add(argument.stringValue()));
                if (!Placeholders.argvMatches(tool, recorded, argv)) {
                    continue;
                }
                Files.writeString(
                        state,
                        number + "\n",
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
                return entry;
            }
            return null;
        }

        void rememberAuthTokens(Map<String, String> environment) throws IOException {
            Path secrets = directory.resolve("auth.json");
            if (Files.exists(secrets)) {
                return;
            }
            ObjectNode tokens = JsonNodeFactory.instance.objectNode();
            environment.forEach((key, value) -> {
                if (key.startsWith("CHERRY_TEST_AUTH_") && key.endsWith("_ACCESS_TOKEN")) {
                    tokens.put(key, value);
                }
            });
            if (tokens.isEmpty()) {
                return;
            }
            Files.writeString(
                    secrets,
                    JSON.writeValueAsString(tokens),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
            Files.setPosixFilePermissions(secrets, PosixFilePermissions.fromString("rw-------"));
        }

        void appendLog(String name, String message) throws IOException {
            Files.writeString(
                    directory.resolve(name),
                    message + "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        }

        String storeText(byte[] content, String runId) throws IOException {
            return Blobs.store(directory.resolve("blobs"), Placeholders.normalizeBytes(content, runId));
        }

        byte[] loadText(String blob, String runId) throws IOException {
            return Placeholders.expandBytes(
                    Files.readAllBytes(directory.resolve("blobs").resolve(blob)), runId);
        }

        String storeFile(Path file, String runId) throws IOException {
            return Blobs.store(
                    directory.resolve("blobs"), Placeholders.normalizeBytes(Files.readAllBytes(file), runId));
        }

        void restore(String blob, Path target, String runId) throws IOException {
            Files.write(target, loadText(blob, runId));
        }
    }
}
