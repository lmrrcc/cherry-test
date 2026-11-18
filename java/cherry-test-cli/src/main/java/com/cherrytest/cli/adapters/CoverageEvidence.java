package com.cherrytest.cli.adapters;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.jacoco.core.analysis.*;
import org.jacoco.core.tools.ExecFileLoader;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

final class CoverageEvidence {
    private static final long MAX_BYTES = 64L * 1024 * 1024;

    private record Compiled(
            String name, Path path, String hash, Path source, String sourceHash, String generation, String compiler) {}

    private CoverageEvidence() {}

    static void enrich(ObjectNode report, Path captures, Path build, String token, Path root) {
        enrich(report, captures, build, token, List.of(root));
    }

    static void enrich(ObjectNode report, Path captures, Path build, String token, List<Path> roots) {
        if (!Files.isDirectory(captures)) return;
        try {
            List<Path> realRoots = new ArrayList<>();
            for (Path root : roots) realRoots.add(root.toRealPath());
            verify(report, captures, build, token, realRoots);
        } catch (IOException | RuntimeException invalid) {
            for (JsonNode file : report.path("files")) {
                ObjectNode proof = ((ObjectNode) file).putObject("provenance");
                proof.put("schemaVersion", 1);
                proof.put("status", "unknown");
                proof.put("reason", "Provenance could not be validated: " + invalid.getMessage());
            }
        }
    }

    private static void verify(ObjectNode report, Path captures, Path build, String token, List<Path> root)
            throws IOException {
        Map<String, Compiled> compiled = new TreeMap<>();
        Map<String, String> processes = new TreeMap<>();
        try (var paths = Files.list(captures)) {
            List<Path> manifests = paths.limit(10001).toList();
            if (manifests.size() > 10000) throw new IOException("Too many capture manifests");
            for (Path path : manifests) {
                if (!path.toString().endsWith(".compile") && !path.toString().endsWith(".process")) continue;
                Properties values = new Properties();
                values.load(new ByteArrayInputStream(bytes(path, root)));
                if (!"1".equals(values.getProperty("schemaVersion")) || !token.equals(values.getProperty("token")))
                    continue;
                if (path.toString().endsWith(".process")) {
                    String session = required(values, "session");
                    if (!session.startsWith("cherry:" + token + ":")) throw new IOException("Wrong process session");
                    long pid = Long.parseLong(required(values, "pid"));
                    if (pid <= 0) throw new IOException("Invalid instrumented process");
                    java.time.Instant started = java.time.Instant.parse(required(values, "processStart"));
                    String identity = pid + "@" + started;
                    String name = path.getFileName().toString();
                    Properties finished = new Properties();
                    finished.load(new ByteArrayInputStream(bytes(
                            path.resolveSibling(name.substring(0, name.length() - 8) + ".finished"), root)));
                    for (String key : List.of("schemaVersion", "token", "session", "pid", "processStart")) {
                        if (!required(values, key).equals(required(finished, key)))
                            throw new IOException("Process completion identity mismatch");
                    }
                    java.time.Instant.parse(required(finished, "completedAt"));
                    var handle = ProcessHandle.of(pid);
                    if (handle.isPresent()
                            && handle.get().isAlive()
                            && handle.get()
                                    .info()
                                    .startInstant()
                                    .map(started::equals)
                                    .orElse(true)) throw new IOException("Instrumented process has not exited");
                    if (processes.putIfAbsent(session, identity) != null)
                        throw new IOException("Duplicate process session");
                } else {
                    int count = Integer.parseInt(required(values, "count"));
                    if (count < 0 || count > 100000) throw new IOException("Invalid class inventory size");
                    for (int index = 0; index < count; index++) {
                        String prefix = "class." + index + ".";
                        Compiled item = new Compiled(
                                required(values, prefix + "name"),
                                Path.of(required(values, prefix + "path")),
                                required(values, prefix + "sha256"),
                                Path.of(required(values, prefix + "source")).toRealPath(),
                                required(values, prefix + "sourceHash"),
                                required(values, "generation"),
                                required(values, "compiler"));
                        if (compiled.putIfAbsent(item.name(), item) != null)
                            throw new IOException("Ambiguous compiled class " + item.name());
                    }
                }
            }
        }
        if (compiled.isEmpty() || processes.isEmpty())
            throw new IOException("Compiler or instrumented process capture is absent");
        ExecFileLoader execution = new ExecFileLoader();
        List<String> executionHashes = new ArrayList<>();
        var executionFiles = report.putArray("executionFiles");
        for (Path file : Formats.files(build, "exec")) {
            byte[] data = bytes(file, root);
            execution.load(new ByteArrayInputStream(data));
            executionHashes.add(hash(data));
            var artifact = executionFiles.addObject();
            artifact.put("path", file.toRealPath().toString());
            artifact.put("sha256", hash(data));
        }
        var sessions = execution.getSessionInfoStore().getInfos();
        if (sessions.isEmpty()) throw new IOException("No JaCoCo execution session");
        Set<String> observed = new TreeSet<>();
        for (var session : sessions) {
            if (!processes.containsKey(session.getId()))
                throw new IOException("JaCoCo session has no owned process capture");
            observed.add(session.getId());
        }
        if (!observed.equals(processes.keySet()))
            throw new IOException("One or more instrumented processes did not supply execution data");
        for (JsonNode node : report.path("files")) {
            ObjectNode file = (ObjectNode) node;
            Path source = Path.of(file.path("source").asString()).toRealPath();
            ObjectNode proof = file.putObject("provenance");
            proof.put("schemaVersion", 1);
            proof.put("status", "unknown");
            List<Compiled> matches = compiled.values().stream()
                    .filter(c -> c.source().equals(source))
                    .toList();
            if (matches.isEmpty()) {
                proof.put("reason", "Source was not captured by javac in this invocation");
                continue;
            }
            String sourceHash = hash(bytes(source, root));
            CoverageBuilder counters = new CoverageBuilder();
            Analyzer analyzer = new Analyzer(execution.getExecutionDataStore(), counters);
            List<String> classes = new ArrayList<>();
            Set<String> generations = new TreeSet<>(), compilers = new TreeSet<>();
            String reason = "";
            boolean missingExecution = false;
            for (Compiled item : matches) {
                byte[] code = bytes(item.path(), root);
                if (!item.sourceHash().equals(sourceHash)) {
                    reason = "Source differs from compiler input";
                    break;
                }
                if (!hash(code).equals(item.hash())) {
                    reason = "Class bytes differ from compiler output";
                    break;
                }
                CoverageBuilder single = new CoverageBuilder();
                new Analyzer(execution.getExecutionDataStore(), single).analyzeClass(code, item.name());
                if (single.getClasses().size() != 1) {
                    reason = "Missing class coverage";
                    break;
                }
                var identity = single.getClasses().iterator().next();
                if (!identity.getName().equals(item.name()) || identity.isNoMatch()) {
                    reason = "Executed class identity differs from compiled class";
                    break;
                }
                if (execution.getExecutionDataStore().get(identity.getId()) == null) {
                    missingExecution = true;
                }
                if (execution.getExecutionDataStore().getContents().stream()
                        .anyMatch(data -> data.getName().equals(item.name()) && data.getId() != identity.getId())) {
                    reason = "Executed generations differ for the same class";
                    break;
                }
                classes.add(item.name() + ":" + Long.toUnsignedString(identity.getId(), 16) + ":" + item.hash());
                generations.add(item.generation());
                compilers.add(item.compiler());
                analyzer.analyzeClass(code, item.name());
            }
            if (!reason.isEmpty()) {
                proof.put("status", reason.contains("differ") ? "mismatch" : "unknown");
                proof.put("reason", reason);
                continue;
            }
            if (counters.getSourceFiles().size() != 1) {
                proof.put("reason", "Ambiguous source coverage");
                continue;
            }
            var measured = counters.getSourceFiles().iterator().next();
            Set<Integer> reportedLines = new HashSet<>();
            boolean equal = true;
            for (JsonNode line : file.path("lines")) {
                int nr = line.path("line").asInt();
                if (nr < 1 || !reportedLines.add(nr)) {
                    equal = false;
                    break;
                }
                var actual = measured.getLine(nr);
                if (actual.getInstructionCounter().getCoveredCount()
                                != line.path("ci").asInt(-1)
                        || actual.getInstructionCounter().getMissedCount()
                                != line.path("mi").asInt(-1)
                        || actual.getBranchCounter().getCoveredCount()
                                != line.path("cb").asInt(-1)
                        || actual.getBranchCounter().getMissedCount()
                                != line.path("mb").asInt(-1)) {
                    equal = false;
                    break;
                }
            }
            for (int nr = measured.getFirstLine(); nr <= measured.getLastLine(); nr++) {
                if (measured.getLine(nr).getInstructionCounter().getTotalCount() > 0 && !reportedLines.contains(nr))
                    equal = false;
            }
            if (!equal) {
                proof.put("status", "mismatch");
                proof.put("reason", "XML counters differ from captured class/probe analysis");
                continue;
            }
            var artifacts = proof.putArray("classFiles");
            for (Compiled item : matches) {
                var artifact = artifacts.addObject();
                artifact.put("path", item.path().toString());
                artifact.put("sha256", item.hash());
            }
            proof.put("status", missingExecution ? "unknown" : "verified");
            proof.put("inputIdentityVerified", true);
            proof.put("sourceHash", sourceHash);
            proof.put("collectionId", token);
            proof.put(
                    "classIdentity",
                    hash(String.join("\n", classes).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            proof.put("compilationId", String.join(",", generations));
            proof.put("compilerIdentity", String.join(",", compilers));
            proof.put("processId", String.join(",", processes.values()));
            proof.put("sessionId", String.join(",", observed));
            proof.put("executionHashes", String.join(",", executionHashes));
            proof.put(
                    "reason",
                    missingExecution
                            ? "Class has no execution record; instrumentation scope cannot be proven"
                            : "Compiler input/output and JaCoCo class IDs, counters and process sessions match");
        }
    }

    private static String required(Properties values, String name) throws IOException {
        String result = values.getProperty(name);
        if (result == null || result.isBlank()) throw new IOException("Missing capture field: " + name);
        return result;
    }

    private static byte[] bytes(Path path, List<Path> roots) throws IOException {
        Path real = path.toRealPath();
        if (roots.stream().noneMatch(real::startsWith) || Files.size(path) > MAX_BYTES)
            throw new IOException("Invalid capture artifact path or size");
        try (InputStream stream = Files.newInputStream(path)) {
            byte[] bytes = stream.readNBytes((int) MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("Capture artifact exceeds size limit");
            return bytes;
        }
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
