package com.cherrytest.cli.adapters;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.jacoco.core.analysis.*;
import org.jacoco.core.tools.ExecFileLoader;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.*;

public final class CoverageUnion {
    private CoverageUnion() {}

    private record Input(JsonNode file, JsonNode executions) {}

    public static ObjectNode merge(Path workspace, List<Path> reports) throws IOException {
        return merge(workspace, reports, List.of(workspace));
    }

    public static ObjectNode merge(Path workspace, List<Path> reports, List<Path> sourceRoots) throws IOException {
        Path root = workspace.toRealPath();
        List<Path> trusted = new ArrayList<>();
        trusted.add(root);
        for (Path sourceRoot : sourceRoots) trusted.add(sourceRoot.toRealPath());
        var result = JsonNodeFactory.instance.objectNode();
        result.put("schemaVersion", 1);
        result.put("format", "jacoco-lines-v1");
        result.put("perTestAttribution", false);
        var files = result.putArray("files");
        Map<String, List<Input>> sources = new TreeMap<>();
        for (Path report : reports) {
            if (!report.toString().endsWith(".native.json")) {
                for (String line :
                        new String(bytes(root, report), java.nio.charset.StandardCharsets.UTF_8).split("\\R", -1)) {
                    if (!line.startsWith("SF:")) continue;
                    var unknown = JsonNodeFactory.instance.objectNode();
                    unknown.put("source", line.substring(3));
                    unknown.putArray("lines");
                    sources.computeIfAbsent(line.substring(3), ignored -> new ArrayList<>())
                            .add(new Input(unknown, JsonNodeFactory.instance.arrayNode()));
                }
                continue;
            }
            JsonNode data = JsonMapper.builder().build().readTree(bytes(root, report));
            if (data.path("schemaVersion").asInt() != 1
                    || !data.path("format").asString().equals("jacoco-lines-v1"))
                throw new IOException("Unsupported coverage union input");
            for (JsonNode file : data.path("files"))
                sources.computeIfAbsent(file.path("source").asString(), ignored -> new ArrayList<>())
                        .add(new Input(file, data.path("executionFiles")));
        }
        for (var entry : sources.entrySet()) {
            if (entry.getValue().size() < 2) continue;
            ObjectNode merged = mergeSource(trusted, entry.getKey(), entry.getValue());
            files.add(merged);
        }
        return result;
    }

    private static ObjectNode mergeSource(List<Path> root, String source, List<Input> inputs) throws IOException {
        var file = JsonNodeFactory.instance.objectNode();
        file.put("source", source);
        var proof = file.putObject("provenance");
        proof.put("schemaVersion", 1);
        proof.put("status", "unknown");
        proof.put("reason", "Not all collections have matching compiler/process evidence");
        var first = inputs.getFirst().file();
        var unknownLines = file.putArray("lines");
        Set<Integer> lineNumbers = new TreeSet<>();
        inputs.forEach(input -> input.file()
                .path("lines")
                .forEach(line -> lineNumbers.add(line.path("line").asInt())));
        for (int nr : lineNumbers) {
            var row = unknownLines.addObject();
            row.put("line", nr);
            row.put("ci", -1);
            row.put("mi", -1);
            row.put("cb", -1);
            row.put("mb", -1);
        }
        JsonNode original = first.path("provenance");
        String sourceHash = original.path("sourceHash").asString();
        String identity = original.path("classIdentity").asString();
        Map<Path, String> classes = new TreeMap<>(), executions = new TreeMap<>();
        Map<String, Set<String>> identities = new TreeMap<>();
        for (String key : List.of(
                "collectionId", "compilationId", "compilerIdentity", "processId", "sessionId", "executionHashes"))
            identities.put(key, new TreeSet<>());
        for (Input input : inputs) {
            JsonNode evidence = input.file().path("provenance");
            boolean inputsVerified = evidence.path("status").asString().equals("unknown")
                    && evidence.path("inputIdentityVerified").asBoolean();
            if (!(evidence.path("status").asString().equals("verified") || inputsVerified)
                    || !evidence.path("sourceHash").asString().equals(sourceHash)
                    || !evidence.path("classIdentity").asString().equals(identity)
                    || input.executions().isEmpty()) return file;
            for (var field : identities.entrySet())
                field.getValue()
                        .addAll(List.of(evidence.path(field.getKey()).asString().split(",", -1)));
            for (JsonNode artifact : evidence.path("classFiles")) add(classes, artifact);
            for (JsonNode artifact : input.executions()) add(executions, artifact);
        }
        if (!hash(bytes(root, Path.of(source))).equals(sourceHash)) return file;
        ExecFileLoader probes = new ExecFileLoader();
        for (var execution : executions.entrySet()) {
            byte[] data = checked(root, execution);
            probes.load(new ByteArrayInputStream(data));
        }
        Set<String> sessions = new TreeSet<>();
        probes.getSessionInfoStore().getInfos().forEach(s -> sessions.add(s.getId()));
        if (!sessions.equals(identities.get("sessionId"))) return file;
        CoverageBuilder measured = new CoverageBuilder();
        Analyzer analyzer = new Analyzer(probes.getExecutionDataStore(), measured);
        Set<String> analyzed = new HashSet<>();
        for (var compiled : classes.entrySet()) {
            byte[] code = checked(root, compiled);
            if (analyzed.add(compiled.getValue()))
                analyzer.analyzeClass(code, compiled.getKey().toString());
        }
        if (measured.getSourceFiles().size() != 1
                || measured.getClasses().stream()
                        .anyMatch(c -> c.isNoMatch()
                                || probes.getExecutionDataStore().get(c.getId()) == null
                                || probes.getExecutionDataStore().getContents().stream()
                                        .anyMatch(data ->
                                                data.getName().equals(c.getName()) && data.getId() != c.getId())))
            return file;
        var counters = measured.getSourceFiles().iterator().next();
        var lines = file.putArray("lines");
        for (int nr = counters.getFirstLine(); nr > 0 && nr <= counters.getLastLine(); nr++) {
            var line = counters.getLine(nr);
            if (line.getInstructionCounter().getTotalCount() == 0) continue;
            var row = lines.addObject();
            row.put("line", nr);
            row.put("ci", line.getInstructionCounter().getCoveredCount());
            row.put("mi", line.getInstructionCounter().getMissedCount());
            row.put("cb", line.getBranchCounter().getCoveredCount());
            row.put("mb", line.getBranchCounter().getMissedCount());
        }
        proof.put("status", "verified");
        proof.put("sourceHash", sourceHash);
        proof.put("classIdentity", identity);
        proof.put(
                "collectionId",
                UUID.nameUUIDFromBytes(String.join(",", identities.get("collectionId"))
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        .toString());
        var collections = proof.putArray("collectionIds");
        Objects.requireNonNull(identities.get("collectionId")).forEach(collections::add);
        for (var field : identities.entrySet())
            if (!field.getKey().equals("collectionId")) proof.put(field.getKey(), String.join(",", field.getValue()));
        var artifacts = proof.putArray("classFiles");
        classes.forEach((path, digest) -> {
            var item = artifacts.addObject();
            item.put("path", path.toString());
            item.put("sha256", digest);
        });
        proof.put(
                "reason",
                "Union of recorded JaCoCo probes for identical source and class generations; not per-test attribution");
        return file;
    }

    private static void add(Map<Path, String> artifacts, JsonNode artifact) throws IOException {
        Path path = Path.of(artifact.path("path").asString()).toRealPath();
        String digest = artifact.path("sha256").asString();
        String previous = artifacts.putIfAbsent(path, digest);
        if (previous != null && !previous.equals(digest))
            throw new IOException("Conflicting coverage artifact identity");
    }

    private static byte[] checked(List<Path> root, Map.Entry<Path, String> artifact) throws IOException {
        byte[] data = bytes(root, artifact.getKey());
        if (!hash(data).equals(artifact.getValue())) throw new IOException("Coverage union artifact changed");
        return data;
    }

    private static byte[] bytes(Path root, Path path) throws IOException {
        return bytes(List.of(root), path);
    }

    private static byte[] bytes(List<Path> roots, Path path) throws IOException {
        Path canonical = path.toRealPath();
        if (roots.stream().noneMatch(canonical::startsWith) || Files.size(path) > 64L * 1024 * 1024)
            throw new IOException("Invalid coverage union path/size");
        return Files.readAllBytes(path);
    }

    private static String hash(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
