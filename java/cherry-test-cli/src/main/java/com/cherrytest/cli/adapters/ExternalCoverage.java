package com.cherrytest.cli.adapters;

import com.cherrytest.common.adapter.AdapterRequest;
import com.cherrytest.common.adapter.AdapterResponse;
import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.common.rustcompat.SerdeJsonWriter;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.jacoco.core.analysis.*;
import org.jacoco.core.tools.ExecFileLoader;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

public final class ExternalCoverage {
    private ExternalCoverage() {}

    public static void export(AdapterRequest request, AdapterResponse response) {
        Path manifest = request.output().resolve("coverage-evidence.json");
        if (!Files.isRegularFile(manifest)) return;
        try {
            Path root = request.root().toRealPath(), output = request.output().toRealPath();
            if (!manifest.toRealPath().startsWith(output) || Files.size(manifest) > 1048576)
                throw new IOException("Invalid coverage evidence manifest");
            JsonNode spec = JsonMapper.builder().build().readTree(Files.readString(manifest));
            if (spec.path("schemaVersion").asInt() != 1
                    || !spec.path("provider").asString().equals("jacoco"))
                throw new IOException("Unsupported server evidence schema/provider");
            Path lcov = existing(root, spec.path("lcov").asString());
            boolean reported = false;
            for (Path artifact : response.coverage())
                if (existing(root, artifact.toString()).equals(lcov)) reported = true;
            if (!lcov.startsWith(output) || !reported)
                throw new IOException("Coverage evidence does not identify a reported artifact");
            Path execution = existing(root, spec.path("executionDirectory").asString());
            if (!execution.startsWith(output)) throw new IOException("Execution data escapes this invocation");
            List<Path> sources = new ArrayList<>(), classes = new ArrayList<>();
            for (JsonNode path : spec.path("sourceRoots")) sources.add(existing(root, path.asString()));
            for (JsonNode path : spec.path("classDirectories")) classes.add(existing(root, path.asString()));
            if (sources.isEmpty() || classes.isEmpty()) throw new IOException("Missing source/class inventory");
            ExecFileLoader probes = new ExecFileLoader();
            List<Path> executions = Formats.files(execution, "exec");
            if (executions.isEmpty()) throw new IOException("No server execution data");
            for (Path file : executions)
                probes.load(existing(root, file.toString()).toFile());
            CoverageBuilder measured = new CoverageBuilder();
            Analyzer analyzer = new Analyzer(probes.getExecutionDataStore(), measured);
            for (Path directory : classes)
                for (Path candidate : Formats.files(directory, "class")) {
                    Path file = existing(root, candidate.toString());
                    if (!file.startsWith(directory) || Files.size(file) > 16777216)
                        throw new IOException("Invalid class evidence");
                    analyzer.analyzeAll(file.toFile());
                }
            var report = JsonNodeFactory.instance.objectNode();
            report.put("schemaVersion", 1);
            report.put("format", "jacoco-lines-v1");
            report.put("perTestAttribution", false);
            report.put("compiledIdentity", "unknown");
            var files = report.putArray("files");
            StringBuilder legacy = new StringBuilder();
            for (var source : measured.getSourceFiles().stream()
                    .sorted(Comparator.comparing(s -> s.getPackageName() + "/" + s.getName()))
                    .toList()) {
                Path relative = Path.of(source.getPackageName()).resolve(source.getName());
                List<Path> matches = sources.stream()
                        .map(p -> p.resolve(relative))
                        .filter(Files::isRegularFile)
                        .toList();
                if (matches.size() != 1) throw new IOException("Ambiguous or missing source: " + relative);
                Path file = existing(root, matches.getFirst().toString());
                var item = files.addObject();
                item.put("source", file.toString());
                var lines = item.putArray("lines");
                legacy.append("SF:").append(file).append('\n');
                for (int nr = source.getFirstLine(); nr > 0 && nr <= source.getLastLine(); nr++) {
                    var line = source.getLine(nr);
                    if (line.getInstructionCounter().getTotalCount() == 0) continue;
                    var counters = lines.addObject();
                    counters.put("line", nr);
                    counters.put("ci", line.getInstructionCounter().getCoveredCount());
                    counters.put("mi", line.getInstructionCounter().getMissedCount());
                    counters.put("cb", line.getBranchCounter().getCoveredCount());
                    counters.put("mb", line.getBranchCounter().getMissedCount());
                    legacy.append("DA:")
                            .append(nr)
                            .append(',')
                            .append(line.getInstructionCounter().getCoveredCount() > 0 ? 1 : 0)
                            .append('\n');
                }
                legacy.append("end_of_record\n");
            }
            String token = request.env().getOrDefault("CHERRY_TEST_COVERAGE_COLLECTION", "");
            if (!token.isEmpty()) CoverageEvidence.enrich(report, output.resolve("provenance"), execution, token, root);
            Files.writeString(lcov, legacy);
            Files.writeString(lcov.resolveSibling(lcov.getFileName() + ".native.json"), SerdeJsonWriter.pretty(report));
        } catch (IOException failure) {
            throw new AnyhowError("server coverage evidence: " + failure.getMessage());
        }
    }

    private static Path existing(Path root, String value) throws IOException {
        if (value.isBlank()) throw new IOException("Missing evidence path");
        Path path = root.resolve(value).toRealPath();
        if (!path.startsWith(root)) throw new IOException("Evidence path escapes workspace");
        return path;
    }
}
