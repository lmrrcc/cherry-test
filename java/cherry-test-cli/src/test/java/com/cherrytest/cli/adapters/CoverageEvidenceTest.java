package com.cherrytest.cli.adapters;

import static org.junit.jupiter.api.Assertions.*;

import com.cherrytest.provenance.CompilerCapture;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.jar.*;
import org.jacoco.core.analysis.*;
import org.jacoco.core.tools.ExecFileLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.*;

class CoverageEvidenceTest {
    @TempDir
    Path root;

    String token = UUID.randomUUID().toString();
    Path captures, classes, source;
    ObjectNode report;

    @org.junit.jupiter.api.BeforeEach
    void capture() throws Exception {
        captures = Files.createDirectories(root.resolve("capture"));
        classes = Files.createDirectories(root.resolve("classes"));
        source = root.resolve("Price.java");
        Files.writeString(source, """
                public class Price {
                  static int price(boolean discount) { return discount ? 80 : 100; }
                  static int untouched() { return 99; }
                  static class Nested { static int value() { return 1; } }
                  public static void main(String[] args) { System.out.println(price(args.length == 0) + Nested.value()); }
                }
                """);
        Path module = Path.of(CompilerCapture.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
        Path agent = root.resolve("capture.jar");
        if (Files.isDirectory(module)) {
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
            manifest.getMainAttributes().putValue("Premain-Class", "com.cherrytest.provenance.ProcessCapture");
            try (var out = new JarOutputStream(Files.newOutputStream(agent), manifest);
                    var paths = Files.walk(module)) {
                for (Path file : paths.filter(Files::isRegularFile).toList()) {
                    String name = module.relativize(file).toString().replace('\\', '/');
                    if (name.equals("META-INF/MANIFEST.MF")) continue;
                    out.putNextEntry(new JarEntry(name));
                    Files.copy(file, out);
                    out.closeEntry();
                }
            }
        } else Files.copy(module, agent);
        launch(
                "javac",
                "-g",
                "-processorpath",
                agent.toString(),
                "-Xplugin:CherryProvenance " + classes,
                "-d",
                classes.toString(),
                source.toString());
        launch(
                "java",
                "-javaagent:" + System.getProperty("cherrytest.jacocoAgent") + "=destfile="
                        + root.resolve("jacoco.exec"),
                "-javaagent:" + agent,
                "-cp",
                classes.toString(),
                "Price");
        ExecFileLoader data = new ExecFileLoader();
        data.load(root.resolve("jacoco.exec").toFile());
        CoverageBuilder coverage = new CoverageBuilder();
        new Analyzer(data.getExecutionDataStore(), coverage).analyzeAll(classes.toFile());
        report = JsonNodeFactory.instance.objectNode();
        var file = report.putArray("files").addObject();
        file.put("source", source.toString());
        var lines = file.putArray("lines");
        var measured = coverage.getSourceFiles().iterator().next();
        for (int nr = measured.getFirstLine(); nr <= measured.getLastLine(); nr++) {
            var line = measured.getLine(nr);
            if (line.getInstructionCounter().getTotalCount() == 0) continue;
            var row = lines.addObject();
            row.put("line", nr);
            row.put("ci", line.getInstructionCounter().getCoveredCount());
            row.put("mi", line.getInstructionCounter().getMissedCount());
            row.put("cb", line.getBranchCounter().getCoveredCount());
            row.put("mb", line.getBranchCounter().getMissedCount());
        }
    }

    void launch(String tool, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", tool).toString());
        command.addAll(List.of(args));
        Path log = root.resolve(tool + ".log");
        ProcessBuilder builder =
                new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("CHERRY_TEST_PROVENANCE_TOKEN", token);
        builder.environment().put("CHERRY_TEST_PROVENANCE_DIR", captures.toString());
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue(), Files.readString(log));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    @Test
    void external_server_export_preserves_partial_counters_and_three_processes() throws Exception {
        for (int i = 0; i < 2; i++)
            launch(
                    "java",
                    "-javaagent:" + System.getProperty("cherrytest.jacocoAgent") + "=destfile="
                            + root.resolve("server-" + i + ".exec"),
                    "-javaagent:" + root.resolve("capture.jar"),
                    "-cp",
                    classes.toString(),
                    "Price");
        Files.move(captures, root.resolve("provenance"));
        var spec = JsonNodeFactory.instance.objectNode();
        spec.put("schemaVersion", 1);
        spec.put("provider", "jacoco");
        Path lcov = root.resolve("server.lcov");
        Files.writeString(lcov, "");
        spec.put("lcov", lcov.toString());
        spec.put("executionDirectory", root.toString());
        spec.putArray("sourceRoots").add(root.toString());
        spec.putArray("classDirectories").add(classes.toString());
        Files.writeString(root.resolve("coverage-evidence.json"), spec.toString());
        var env = new TreeMap<String, String>();
        env.put("CHERRY_TEST_COVERAGE_COLLECTION", token);
        var request = new com.cherrytest.common.adapter.AdapterRequest(
                1,
                "sample",
                "server",
                "integration",
                root,
                root.resolve("pom.xml"),
                root,
                root,
                com.cherrytest.common.adapter.ExecutionMode.LOCAL,
                true,
                true,
                List.of(),
                env,
                new TreeMap<>());
        var response = new com.cherrytest.common.adapter.AdapterResponse(1, true, List.of(), List.of(lcov), List.of());
        ExternalCoverage.export(request, response);
        var exported = Json.parse(Files.readString(root.resolve("server.lcov.native.json")));
        var source = exported.path("files").get(0);
        assertEquals("verified", source.path("provenance").path("status").asString());
        assertEquals(3, source.path("provenance").path("processId").asString().split(",").length);
        assertEquals(3, source.path("provenance").path("sessionId").asString().split(",").length);
        assertTrue(source.path("lines")
                .valueStream()
                .anyMatch(line -> line.path("cb").asInt() > 0 && line.path("mb").asInt() > 0));
        Files.delete(root.resolve("jacoco.exec"));
        ExternalCoverage.export(request, response);
        var incomplete = Json.parse(Files.readString(root.resolve("server.lcov.native.json")));
        assertEquals(
                "unknown",
                incomplete
                        .path("files")
                        .get(0)
                        .path("provenance")
                        .path("status")
                        .asString());
        assertTrue(incomplete.toString().contains("did not supply execution data"));
        spec.put("executionDirectory", Objects.requireNonNull(root.getParent()).toString());
        Files.writeString(root.resolve("coverage-evidence.json"), spec.toString());
        assertThrows(RuntimeException.class, () -> ExternalCoverage.export(request, response));
    }

    @Test
    void unionUsesActualProbesAcrossTwoVerifiedGenerationsWithoutAddingCounters() throws Exception {
        report.put("schemaVersion", 1);
        report.put("format", "jacoco-lines-v1");
        assertEquals("verified", verify());
        Path one = root.resolve("one.native.json");
        Files.writeString(one, report.toString());
        token = UUID.randomUUID().toString();
        captures = Files.createDirectories(root.resolve("capture-two"));
        classes = Files.createDirectories(root.resolve("classes-two"));
        Path executions = Files.createDirectories(root.resolve("exec-two"));
        launch(
                "javac",
                "-g",
                "-processorpath",
                root.resolve("capture.jar").toString(),
                "-Xplugin:CherryProvenance " + classes,
                "-d",
                classes.toString(),
                source.toString());
        launch(
                "java",
                "-javaagent:" + System.getProperty("cherrytest.jacocoAgent") + "=destfile="
                        + executions.resolve("second.exec"),
                "-javaagent:" + root.resolve("capture.jar"),
                "-cp",
                classes.toString(),
                "Price",
                "other-branch");
        ExecFileLoader probes = new ExecFileLoader();
        probes.load(executions.resolve("second.exec").toFile());
        CoverageBuilder measured = new CoverageBuilder();
        new Analyzer(probes.getExecutionDataStore(), measured).analyzeAll(classes.toFile());
        ObjectNode second = report.deepCopy();
        var counters = measured.getSourceFiles().iterator().next();
        for (JsonNode row : second.path("files").get(0).path("lines")) {
            var actual = counters.getLine(row.path("line").asInt());
            ((ObjectNode) row).put("ci", actual.getInstructionCounter().getCoveredCount());
            ((ObjectNode) row).put("mi", actual.getInstructionCounter().getMissedCount());
            ((ObjectNode) row).put("cb", actual.getBranchCounter().getCoveredCount());
            ((ObjectNode) row).put("mb", actual.getBranchCounter().getMissedCount());
        }
        CoverageEvidence.enrich(second, captures, executions, token, root);
        assertEquals(
                "verified",
                second.path("files").get(0).path("provenance").path("status").asString());
        Path two = root.resolve("two.native.json");
        Files.writeString(two, second.toString());
        var merged = CoverageUnion.merge(root, List.of(one, two));
        var file = merged.path("files").get(0);
        assertEquals("verified", file.path("provenance").path("status").asString());
        assertEquals(2, file.path("provenance").path("collectionIds").size());
        assertTrue(file.path("lines")
                .valueStream()
                .anyMatch(l -> l.path("line").asInt() == 2
                        && l.path("cb").asInt() == 2
                        && l.path("mb").asInt() == 0));
        Path configuration = Files.createDirectories(root.resolve("configuration"));
        Path externalOne = Files.copy(one, configuration.resolve("one.native.json"));
        Path externalTwo = Files.copy(two, configuration.resolve("two.native.json"));
        assertThrows(IOException.class, () -> CoverageUnion.merge(configuration, List.of(externalOne, externalTwo)));
        var externalUnion = CoverageUnion.merge(configuration, List.of(externalOne, externalTwo), List.of(root));
        assertEquals(
                "verified",
                externalUnion
                        .path("files")
                        .get(0)
                        .path("provenance")
                        .path("status")
                        .asString());
        assertEquals(file.path("lines"), externalUnion.path("files").get(0).path("lines"));
        ((ObjectNode) second.path("files").get(0).path("provenance")).put("status", "unknown");
        ((ObjectNode) second.path("files").get(0).path("provenance")).put("inputIdentityVerified", false);
        Files.writeString(two, second.toString());
        assertEquals(
                "unknown",
                CoverageUnion.merge(root, List.of(one, two))
                        .path("files")
                        .get(0)
                        .path("provenance")
                        .path("status")
                        .asString());
        Files.writeString(two, report.toString());
        Files.write(root.resolve("jacoco.exec"), new byte[] {0});
        assertThrows(IOException.class, () -> CoverageUnion.merge(root, List.of(one, two)));
    }

    String verify() {
        CoverageEvidence.enrich(report, captures, root, token, root);
        return report.path("files").get(0).path("provenance").path("status").asString();
    }

    @Test
    void realCompilerAndInstrumentedJvmProvePartialAndUncoveredCounters() throws Exception {
        assertEquals("verified", verify(), report.toString());
        var proof = report.path("files").get(0).path("provenance");
        assertFalse(proof.path("processId").asString().isBlank());
        assertEquals(2, proof.path("classFiles").size());
        assertTrue(report.path("files")
                .get(0)
                .path("lines")
                .valueStream()
                .anyMatch(l -> l.path("cb").asInt() > 0 && l.path("mb").asInt() > 0));
        assertTrue(report.path("files")
                .get(0)
                .path("lines")
                .valueStream()
                .anyMatch(l -> l.path("ci").asInt() == 0 && l.path("mi").asInt() > 0));
    }

    @Test
    void sourceOrBytecodeChangesRejectEvidenceEvenWithUnchangedLineNumbers() throws Exception {
        byte[] original = Files.readAllBytes(source);
        Files.writeString(source, Files.readString(source).replace("80", "81"));
        assertEquals("mismatch", verify());
        Files.write(source, original);
        assertEquals("verified", verify());
        Files.write(classes.resolve("Price.class"), new byte[] {0});
        assertEquals("mismatch", verify());
    }

    @Test
    void foreignSessionIncompleteProcessAndWrongInvocationCannotBecomeCurrent() throws Exception {
        assertEquals("verified", verify());
        String originalToken = token;
        token = UUID.randomUUID().toString();
        assertEquals("unknown", verify());
        token = originalToken;
        Path receipt;
        try (var paths = Files.list(captures)) {
            receipt = paths.filter(p -> p.toString().endsWith(".process"))
                    .findFirst()
                    .orElseThrow();
        }
        byte[] original = Files.readAllBytes(receipt);
        Properties process = new Properties();
        process.load(new ByteArrayInputStream(original));
        process.setProperty("session", "cherry:" + token + ":foreign");
        try (var output = Files.newOutputStream(receipt)) {
            process.store(output, null);
        }
        assertEquals("unknown", verify(), "A session from another instrumented process is not ours");
        Files.write(receipt, original);
        assertEquals("verified", verify());
        try (var output = Files.newOutputStream(captures.resolve("missing.process"))) {
            process.store(output, null);
        }
        assertEquals("unknown", verify(), "All captured instrumented processes must supply exec data");
    }

    @Test
    void missingExecutionAndTamperedCountersNeverCertifyAnXmlReport() throws Exception {
        ObjectNode line = (ObjectNode) report.path("files").get(0).path("lines").get(0);
        line.put("ci", 987);
        assertEquals("mismatch", verify());
        Files.delete(root.resolve("jacoco.exec"));
        assertEquals("unknown", verify());
    }

    @Test
    void oldOrMixedExecutedClassGenerationsCannotMatchANewCompilation() throws Exception {
        Files.writeString(source, Files.readString(source).replace("80", "81"));
        try (var paths = Files.list(captures)) {
            for (Path path :
                    paths.filter(p -> p.toString().endsWith(".compile")).toList()) Files.delete(path);
        }
        try (var paths = Files.walk(classes)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) Files.delete(path);
        }
        launch(
                "javac",
                "-g",
                "-processorpath",
                root.resolve("capture.jar").toString(),
                "-Xplugin:CherryProvenance " + classes,
                "-d",
                classes.toString(),
                source.toString());
        assertEquals("mismatch", verify(), "Old execution must not match a freshly compiled source");
        launch(
                "java",
                "-javaagent:" + System.getProperty("cherrytest.jacocoAgent") + "=destfile="
                        + root.resolve("jacoco.exec"),
                "-javaagent:" + root.resolve("capture.jar"),
                "-cp",
                classes.toString(),
                "Price");
        assertEquals("mismatch", verify(), "Merged sessions containing two bytecode generations must not be certified");
    }

    @Test
    void skippedCompilationAndAbsentProcessCaptureRemainUnknown() throws Exception {
        Path compilation;
        try (var paths = Files.list(captures)) {
            compilation = paths.filter(p -> p.toString().endsWith(".compile"))
                    .findFirst()
                    .orElseThrow();
        }
        Path saved = compilation.resolveSibling("saved.manifest");
        Files.move(compilation, saved);
        assertEquals("unknown", verify());
        Files.move(saved, compilation);
        assertEquals("verified", verify());
        try (var paths = Files.list(captures)) {
            for (Path path :
                    paths.filter(p -> p.toString().endsWith(".process")).toList()) Files.delete(path);
        }
        assertEquals("unknown", verify());
    }

    @Test
    void wrongOrReusedOutputDirectoryCannotBindNewSourceToOldClassBytes() throws Exception {
        try (var paths = Files.list(captures)) {
            for (Path path :
                    paths.filter(p -> p.toString().endsWith(".compile")).toList()) Files.delete(path);
        }
        Files.writeString(source, Files.readString(source).replace("80", "81"));
        Path actualOutput = Files.createDirectories(root.resolve("different-output"));
        launch(
                "javac",
                "-g",
                "-processorpath",
                root.resolve("capture.jar").toString(),
                "-Xplugin:CherryProvenance " + classes,
                "-d",
                actualOutput.toString(),
                source.toString());
        assertTrue(Files.exists(actualOutput.resolve("Price.class")));
        assertEquals("unknown", verify());
    }

    @Test
    void absentShutdownReceiptOrStillRunningInstrumentedProcessCannotBeComplete() throws Exception {
        Path process, finished;
        try (var paths = Files.list(captures)) {
            process = paths.filter(p -> p.toString().endsWith(".process"))
                    .findFirst()
                    .orElseThrow();
        }
        try (var paths = Files.list(captures)) {
            finished = paths.filter(p -> p.toString().endsWith(".finished"))
                    .findFirst()
                    .orElseThrow();
        }
        byte[] original = Files.readAllBytes(finished);
        Files.delete(finished);
        assertEquals("unknown", verify());
        Files.write(finished, original);
        assertEquals("verified", verify());
        for (Path path : List.of(process, finished)) {
            Properties values = new Properties();
            try (var input = Files.newInputStream(path)) {
                values.load(input);
            }
            values.setProperty("pid", Long.toString(ProcessHandle.current().pid()));
            values.setProperty(
                    "processStart",
                    ProcessHandle.current().info().startInstant().orElseThrow().toString());
            try (var output = Files.newOutputStream(path)) {
                values.store(output, null);
            }
        }
        assertEquals("unknown", verify());
    }
}
