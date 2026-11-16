package com.cherrytest.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.cherrytest.cli.adapters.Json;
import com.cherrytest.common.adapter.AdapterRequest;
import com.cherrytest.common.adapter.AdapterResponse;
import com.cherrytest.common.adapter.ExecutionMode;
import com.cherrytest.common.adapter.TestResult;
import com.cherrytest.common.adapter.TestStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

class NativeEventsTest {
    @TempDir
    Path temp;

    @Test
    void parallel_jobs_keep_ordered_events_and_distinct_module_identities() throws Exception {
        Path output = temp.resolve("parallel.jsonl");
        try (var events = NativeEvents.openAt(temp, "parallel", output);
                var workers = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var jobs = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int index = 0; index < 64; index++) {
                int module = index;
                jobs.add(workers.submit(() -> {
                    var req = new AdapterRequest(
                            1,
                            "project",
                            "module" + module,
                            "integration",
                            temp,
                            temp.resolve("module" + module).resolve("pom.xml"),
                            temp,
                            temp.resolve("output" + module),
                            ExecutionMode.CONTAINER,
                            false,
                            true,
                            List.of(),
                            new TreeMap<>(),
                            new TreeMap<>());
                    events.phaseStarted(req, "maven", "java");
                    events.phaseCompleted(
                            req, "maven", "java", new AdapterResponse(1, true, List.of(), List.of(), List.of()), true);
                }));
            }
            for (var job : jobs) job.get();
            events.complete(0);
        }
        var rows = read(output);
        assertThat(rows).hasSize(130);
        for (int i = 0; i < rows.size(); i++)
            assertThat(rows.get(i).path("sequence").asLong()).isEqualTo(i);
        assertThat(rows.stream()
                        .filter(r -> r.path("event").asString().equals("phase.completed"))
                        .map(r -> r.path("phaseKey").asString())
                        .distinct()
                        .count())
                .isEqualTo(64);
    }

    private AdapterRequest request() {
        return new AdapterRequest(
                1,
                "shop",
                "prices",
                "unit",
                temp,
                temp.resolve("pom.xml"),
                temp,
                temp.resolve("output"),
                ExecutionMode.LOCAL,
                true,
                true,
                List.of(),
                new TreeMap<>(),
                new TreeMap<>());
    }

    private List<JsonNode> read(Path output) throws IOException {
        return Files.readAllLines(output).stream().map(Json::parse).toList();
    }

    @Test
    void real_results_preserve_locations_failures_and_independent_coverage() throws IOException {
        Path source = temp.resolve("PriceTest.java");
        Files.writeString(source, "class PriceTest {}\n");
        Path lcov = temp.resolve("measured.lcov");
        Files.writeString(lcov, "SF:Price.java\nDA:12,0\nend_of_record\n");
        Files.writeString(temp.resolve("measured.lcov.native.json"), "{}");
        Path eventsPath = temp.resolve("events.jsonl");
        AdapterResponse response = new AdapterResponse(
                1,
                false,
                List.of(new TestResult(
                        "PriceTest::discount",
                        TestStatus.FAILED,
                        3,
                        "expected 90 but was 100\n at PriceTest.discount(PriceTest.java:8)",
                        source,
                        8L,
                        List.of())),
                List.of(lcov),
                List.of());
        try (NativeEvents events = NativeEvents.openAt(temp, "run-1", eventsPath)) {
            events.phaseStarted(request(), "maven", "JAVA");
            events.phaseCompleted(request(), "maven", "JAVA", response, true);
            assertThat(events.complete(1)).isEqualTo(1);
        }
        List<JsonNode> rows = read(eventsPath);
        assertThat(rows.stream().map(row -> row.path("event").asString()))
                .containsExactly("run.started", "phase.started", "phase.completed", "run.completed");
        assertThat(rows.stream().map(row -> row.path("sequence").asLong())).containsExactly(0L, 1L, 2L, 3L);
        assertThat(rows.getFirst().path("sourceBefore").path("PriceTest.java").asString())
                .hasSize(64);
        JsonNode result = rows.get(2);
        assertThat(result.path("failureScope").asString()).isEqualTo("tests");
        assertThat(result.path("perTestAttribution").asBoolean()).isFalse();
        assertThat(result.path("tests").get(0).path("source").asString()).isEqualTo("PriceTest.java");
        assertThat(result.path("tests").get(0).path("line").asLong()).isEqualTo(8);
        assertThat(result.path("tests").get(0).path("message").asString()).contains("\n at PriceTest");
        assertThat(result.path("coverage").get(0).path("sha256").asString()).hasSize(64);
        assertThat(result.path("coveragePrecisionArtifacts")
                        .get(0)
                        .path("format")
                        .asString())
                .isEqualTo("jacoco-lines-v1");
        assertThat(rows.getLast().path("outcome").asString()).isEqualTo("failed");
        assertThat(rows.getLast().path("complete").asBoolean()).isTrue();
        assertThat(rows.getLast().path("sourceStable").asBoolean()).isTrue();
        assertThat(rows.getLast().path("compiledIdentity").asString()).isEqualTo("unknown");
    }

    @Test
    void changed_and_removed_sources_are_stale_and_build_output_is_excluded() throws IOException {
        Path source = temp.resolve("Price.java");
        Files.writeString(source, "class Price {}\n");
        Files.createDirectories(temp.resolve("target"));
        Files.writeString(temp.resolve("target/Generated.java"), "class Generated {}\n");
        Path output = temp.resolve("events.jsonl");
        try (NativeEvents events = NativeEvents.openAt(temp, "changed", output)) {
            Files.delete(source);
            Files.writeString(temp.resolve("New.java"), "class New {}\n");
            events.complete(0);
        }
        List<JsonNode> rows = read(output);
        assertThat(rows.getFirst().path("sourceBefore").size()).isEqualTo(1);
        assertThat(rows.getLast().path("sourceStable").asBoolean()).isFalse();
        assertThat(rows.getLast().path("sourceAfter").has("New.java")).isTrue();
        assertThat(rows.getLast().path("sourceAfter").has("Price.java")).isFalse();
    }

    @Test
    void missing_terminal_result_is_explicitly_incomplete_and_does_not_invent_tests() throws IOException {
        Path output = temp.resolve("events.jsonl");
        try (NativeEvents events = NativeEvents.openAt(temp, "aborted", output)) {
            events.phaseStarted(request(), "maven", "JAVA");
            events.phaseCompleted(request(), "maven", "JAVA", AdapterResponse.failure("compiler failed"), false);
        }
        List<JsonNode> rows = read(output);
        assertThat(rows.get(2).path("tests").isEmpty()).isTrue();
        assertThat(rows.get(2).path("failureScope").asString()).isEqualTo("execution");
        assertThat(rows.get(2).path("completeness").asString()).isEqualTo("incomplete");
        assertThat(rows.getLast().path("outcome").asString()).isEqualTo("error");
        assertThat(rows.getLast().path("complete").asBoolean()).isFalse();
    }

    @Test
    void stable_phase_and_test_ids_survive_distinct_runs_and_terminal_is_emitted_once() throws IOException {
        Path output = temp.resolve("events.jsonl");
        AdapterResponse response = new AdapterResponse(
                1,
                true,
                List.of(new TestResult("PriceTest::normal", TestStatus.PASSED, 1, null, null, null, List.of())),
                List.of(),
                List.of());
        for (String run : List.of("one", "two")) {
            try (NativeEvents events = NativeEvents.openAt(temp, run, output)) {
                events.phaseStarted(request(), "maven", "JAVA");
                events.phaseCompleted(request(), "maven", "JAVA", response, true);
                events.complete(0);
                events.complete(0);
            }
        }
        List<JsonNode> rows = read(output);
        assertThat(rows).hasSize(8);
        assertThat(rows.get(2).path("phaseKey")).isEqualTo(rows.get(6).path("phaseKey"));
        assertThat(rows.get(2).path("tests").get(0).path("id"))
                .isEqualTo(rows.get(6).path("tests").get(0).path("id"));
        assertThat(rows.get(6).path("tests").get(0).path("source").isNull()).isTrue();
    }
}
