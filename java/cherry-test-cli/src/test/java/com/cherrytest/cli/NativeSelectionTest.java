package com.cherrytest.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cherrytest.cli.adapters.Json;
import com.cherrytest.common.adapter.AdapterRequest;
import com.cherrytest.common.adapter.AdapterResponse;
import com.cherrytest.common.adapter.ExecutionMode;
import com.cherrytest.common.adapter.TestResult;
import com.cherrytest.common.adapter.TestStatus;
import com.cherrytest.common.process.ProcessEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

class NativeSelectionTest {
    @TempDir
    Path temp;

    private AdapterRequest request(String module) {
        Path root = temp.resolve(module);
        return new AdapterRequest(
                1,
                "project",
                module,
                "unit",
                temp,
                root.resolve("pom.xml"),
                root,
                temp.resolve("reports").resolve(module),
                ExecutionMode.LOCAL,
                true,
                true,
                List.of("-Dtest=Old"),
                new TreeMap<>(),
                new TreeMap<>());
    }

    private TestResult test(String name) {
        return new TestResult(name, TestStatus.PASSED, 1, null, null, null, List.of());
    }

    private Plan plan(AdapterRequest... requests) {
        return new Plan(List.of(new Plan.PhasePlan(
                "unit",
                java.util.Arrays.stream(requests)
                        .map(req -> new Plan.Job(
                                new Plan.JobKey(req.project(), req.packageName(), req.phase()),
                                "maven",
                                req,
                                List.of(),
                                Plan.Resources.PACKAGE_DEFAULT,
                                0,
                                false,
                                null,
                                List.of()))
                        .toList())));
    }

    private Path selection(ObjectNode... values) throws Exception {
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("schemaVersion", 1);
        var selected = body.putArray("selectors");
        for (var value : values) selected.add(value);
        Path path = temp.resolve("selection.json");
        Files.writeString(path, body.toString());
        return path;
    }

    @Test
    void exact_selection_filters_jobs_and_checks_reported_inventory() throws Exception {
        var one = request("one");
        var two = request("two");
        var first = NativeSelection.selector(one, "maven", test("demo.PriceTest::discount"), 1);
        var second = NativeSelection.selector(one, "maven", test("demo.PriceTest::full"), 1);
        NativeSelection selected = NativeSelection.read(selection(first, second));
        Plan result = selected.apply(plan(one, two));
        var jobs = result.jobs().toList();
        assertThat(jobs).hasSize(1);
        assertThat(jobs.getFirst().request().args())
                .contains("-Dtest=demo.PriceTest#discount,demo.PriceTest#full")
                .doesNotContain("-Dtest=Old");
        assertThat(jobs.getFirst().request().coverage()).isTrue();
        selected.validateResponse(
                one,
                new AdapterResponse(
                        1,
                        true,
                        List.of(test("demo.PriceTest::discount"), test("demo.PriceTest::full")),
                        List.of(),
                        List.of()));
        assertThatThrownBy(() ->
                        selected.validateResponse(one, new AdapterResponse(1, true, List.of(), List.of(), List.of())))
                .hasMessageContaining("not honored");
        assertThatThrownBy(() -> selected.validateResponse(
                        one,
                        new AdapterResponse(1, true, List.of(test("demo.PriceTest::other")), List.of(), List.of())))
                .hasMessageContaining("not honored");
    }

    @Test
    void selector_cannot_change_runtime_manifest_or_scope() throws Exception {
        var req = request("one");
        for (String field : List.of("manifest", "runtimeId", "adapterId", "phaseKey", "kind")) {
            var value = NativeSelection.selector(req, "maven", test("demo.PriceTest::discount"), 1);
            value.put(field, "different");
            var selected = NativeSelection.read(selection(value));
            assertThatThrownBy(() -> selected.apply(plan(req))).hasMessageContaining("native selector");
        }
    }

    @Test
    void broader_java_invocations_are_not_advertised_as_exact() throws Exception {
        var req = request("one");
        for (String name : List.of("demo.PriceTest::discount[1]", "demo.PriceTest::discount(int)", "Display name"))
            assertThat(NativeSelection.selector(req, "maven", test(name), 1)
                            .path("exact")
                            .asBoolean())
                    .isFalse();
        assertThat(NativeSelection.selector(req, "maven", test("demo.PriceTest::discount"), 2)
                        .path("exact")
                        .asBoolean())
                .isFalse();
        Path source = req.cwd().resolve("src/test/java/demo/PriceTest.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "@TestInstance(PER_CLASS) class PriceTest {}");
        assertThat(NativeSelection.selector(req, "maven", test("demo.PriceTest::discount"), 1)
                        .path("reason")
                        .asString())
                .contains("broader class");
        assertThat(NativeSelection.selector(req, "gradle", test("demo.PriceTest::discount"), 1)
                        .path("exact")
                        .asBoolean())
                .isFalse();
    }

    @Test
    void acknowledgement_is_bound_to_validated_selection_bytes() throws Exception {
        var req = request("one");
        Path path = selection(NativeSelection.selector(req, "maven", test("demo.PriceTest::discount"), 1));
        String previous = ProcessEnvironment.get("CHERRY_TEST_IDE_SELECTION");
        ProcessEnvironment.set("CHERRY_TEST_IDE_SELECTION", path.toString());
        Path output = temp.resolve("events.jsonl");
        try (var events = NativeEvents.openAt(temp, "selected", output)) {
            events.preparePlan(plan(req));
            events.phaseStarted(req, "maven", "java");
            events.complete(0);
        } finally {
            if (previous == null) ProcessEnvironment.remove("CHERRY_TEST_IDE_SELECTION");
            else ProcessEnvironment.set("CHERRY_TEST_IDE_SELECTION", previous);
        }
        var started = Json.parse(Files.readAllLines(output).getFirst());
        assertThat(started.path("selectionSHA256").asString())
                .isEqualTo(NativeEvents.hashBytes(Files.readAllBytes(path)));
        assertThat(started.path("capabilities").path("testProgress").asBoolean())
                .isFalse();
    }

    @Test
    void configured_external_sources_are_captured_and_repositories_stay_distinct() throws Exception {
        Path workspace = temp.resolve("workspace");
        Files.createDirectories(workspace);
        Path one = temp.resolve("repo-one");
        Path two = temp.resolve("repo-two");
        for (Path repo : List.of(one, two)) {
            Files.createDirectories(repo.resolve(".git"));
            Files.writeString(repo.resolve("Price.java"), "class Price {}\n");
        }
        String previous = ProcessEnvironment.get("CHERRY_TEST_IDE_CAPABILITIES");
        ProcessEnvironment.set("CHERRY_TEST_IDE_CAPABILITIES", "repositories-v1,selectors-v1");
        Path output = workspace.resolve("events.jsonl");
        try (var events =
                NativeEvents.openAt(workspace, "repos", output, true, List.of("unit"), null, List.of(one, two))) {
            events.complete(0);
        } finally {
            if (previous == null) ProcessEnvironment.remove("CHERRY_TEST_IDE_CAPABILITIES");
            else ProcessEnvironment.set("CHERRY_TEST_IDE_CAPABILITIES", previous);
        }
        var rows = Files.readAllLines(output).stream().map(Json::parse).toList();
        var started = rows.getFirst();
        assertThat(started.path("sourceBefore")
                        .path(one.resolve("Price.java").toString())
                        .asString())
                .hasSize(64);
        assertThat(started.path("sourceBefore")
                        .path(two.resolve("Price.java").toString())
                        .asString())
                .hasSize(64);
        assertThat(started.path("repositories").size()).isEqualTo(3);
        assertThat(started.path("repositories").get(1).path("id"))
                .isNotEqualTo(started.path("repositories").get(2).path("id"));
        assertThat(rows.getLast().path("sourceStable").asBoolean()).isTrue();
    }

    @Test
    void negotiated_progress_preserves_final_identity_and_old_clients_receive_no_events() throws Exception {
        for (boolean negotiate : List.of(false, true)) {
            String previous = ProcessEnvironment.get("CHERRY_TEST_IDE_CAPABILITIES");
            ProcessEnvironment.set("CHERRY_TEST_IDE_CAPABILITIES", negotiate ? "test-events-v1" : "");
            Path output = temp.resolve("progress-" + negotiate + ".jsonl");
            var request = request("one");
            var observed = JsonNodeFactory.instance.objectNode();
            observed.put("schemaVersion", 1);
            observed.put("event", "test.started");
            observed.put("name", "demo.PriceTest::discount");
            observed.put("status", "pending");
            observed.put("processId", ProcessHandle.current().pid());
            observed.put("invocationId", "invocation-one");
            observed.put("timestamp", java.time.Instant.now().toString());
            try (var events = NativeEvents.openAt(temp, "progress", output)) {
                events.phaseStarted(request, "maven", "java");
                events.testProgress(request, "maven", observed);
                observed.put("event", "test.completed");
                observed.put("status", "passed");
                events.testProgress(request, "maven", observed);
                events.phaseCompleted(
                        request,
                        "maven",
                        "java",
                        new AdapterResponse(1, true, List.of(test("demo.PriceTest::discount")), List.of(), List.of()),
                        true);
                events.complete(0);
            } finally {
                if (previous == null) ProcessEnvironment.remove("CHERRY_TEST_IDE_CAPABILITIES");
                else ProcessEnvironment.set("CHERRY_TEST_IDE_CAPABILITIES", previous);
            }
            var rows = Files.readAllLines(output).stream().map(Json::parse).toList();
            assertThat(rows).hasSize(negotiate ? 6 : 4);
            if (negotiate) {
                var id = rows.get(2).path("test").path("id");
                assertThat(rows.get(3).path("test").path("id")).isEqualTo(id);
                assertThat(rows.get(4).path("tests").get(0).path("id")).isEqualTo(id);
            }
        }
    }

    @Test
    void normalization_accepts_only_declared_external_module_and_rejects_symlink_escape() throws Exception {
        Path workspace = Files.createDirectories(temp.resolve("workspace"));
        Path module = Files.createDirectories(temp.resolve("external"));
        Path source = module.resolve("Price.java");
        Files.writeString(source, "class Price {}\n");
        Path outside = Files.createDirectories(temp.resolve("unrelated")).resolve("Secret.java");
        Files.writeString(outside, "class Secret {}\n");
        Path link = module.resolve("linked.java");
        Files.createSymbolicLink(link, outside);
        var req = new AdapterRequest(
                1,
                "project",
                "module",
                "unit",
                workspace,
                module.resolve("pom.xml"),
                module,
                workspace.resolve("reports"),
                ExecutionMode.LOCAL,
                true,
                true,
                List.of(),
                new TreeMap<>(),
                new TreeMap<>());
        Path report = workspace.resolve("measured.lcov");
        Files.writeString(
                report,
                "SF:" + source + "\nDA:1,1\nend_of_record\nSF:" + outside + "\nDA:1,0\nend_of_record\nSF:" + link
                        + "\nDA:1,0\nend_of_record\n");
        Path normalized = workspace.resolve("normalized.lcov");
        Runner.normalizeCoverage(req, List.of(report), normalized);
        assertThat(Files.readString(normalized))
                .contains(source.toString())
                .doesNotContain(outside.toString(), link.toString());
    }

    @Test
    void junit_signature_and_declared_method_alias_share_one_verified_final_identity() throws Exception {
        String previous = ProcessEnvironment.get("CHERRY_TEST_IDE_CAPABILITIES");
        ProcessEnvironment.set("CHERRY_TEST_IDE_CAPABILITIES", "test-events-v1");
        Path output = temp.resolve("aliases.jsonl");
        var req = request("one");
        var observation = JsonNodeFactory.instance.objectNode();
        observation.put("schemaVersion", 1);
        observation.put("event", "test.started");
        observation.put("name", "demo.PriceTest::discount(TestEnv)");
        observation.put("declaredName", "demo.PriceTest::discount");
        observation.put("status", "pending");
        observation.put("processId", 123);
        observation.put("invocationId", "real-identity");
        observation
                .putArray("reportedNameAliases")
                .add("demo.PriceTest::discount")
                .add("demo.PriceTest::discount(TestEnv)");
        try (var events = NativeEvents.openAt(temp, "aliases", output)) {
            events.testProgress(req, "maven", observation);
            events.phaseCompleted(
                    req,
                    "maven",
                    "java",
                    new AdapterResponse(
                            1, true, List.of(test("demo.PriceTest::discount(TestEnv)")), List.of(), List.of()),
                    true);
            events.complete(0);
        } finally {
            if (previous == null) ProcessEnvironment.remove("CHERRY_TEST_IDE_CAPABILITIES");
            else ProcessEnvironment.set("CHERRY_TEST_IDE_CAPABILITIES", previous);
        }
        var rows = Files.readAllLines(output).stream().map(Json::parse).toList();
        assertThat(rows.get(1).path("test").path("name").asString()).isEqualTo("demo.PriceTest::discount");
        assertThat(rows.get(2).path("tests").get(0).path("name").asString()).isEqualTo("demo.PriceTest::discount");
        assertThat(rows.get(2).path("tests").get(0).path("displayName").asString())
                .isEqualTo("demo.PriceTest::discount(TestEnv)");
        assertThat(rows.get(1).path("test").path("id"))
                .isEqualTo(rows.get(2).path("tests").get(0).path("id"));
    }
}
