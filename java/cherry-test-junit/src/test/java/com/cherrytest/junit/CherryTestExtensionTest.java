package com.cherrytest.junit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import com.cherrytest.common.process.ProcessEnvironment;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Events;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class CherryTestExtensionTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private HttpServer server;

    @TempDir
    Path workspace;

    @BeforeEach
    void start_a_service_and_a_workspace() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/health", exchange -> {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/missing", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        Files.writeString(
                workspace.resolve("cherry-test.yaml"),
                "projects:\n  demo:\n    path: .\n    services:\n      api:\n        service: demo-api\n        port: 8080\n",
                StandardCharsets.UTF_8);
        ProcessEnvironment.set("CHERRY_TEST_WORKSPACE_ROOT", workspace.toString());
        ProcessEnvironment.set(
                "CHERRY_TEST_REPORT_DIR", workspace.resolve("reports").toString());
        ProcessEnvironment.set(
                "CHERRY_TEST_DEMO_API_URL",
                "http://127.0.0.1:" + server.getAddress().getPort());
        System.setProperty(CherryTestExtension.PACKAGE_PROPERTY, "demo-api");
    }

    @AfterEach
    void stop_the_service() {
        server.stop(0);
        ProcessEnvironment.remove("CHERRY_TEST_WORKSPACE_ROOT");
        ProcessEnvironment.remove("CHERRY_TEST_REPORT_DIR");
        ProcessEnvironment.remove("CHERRY_TEST_DEMO_API_URL");
        System.clearProperty(CherryTestExtension.PACKAGE_PROPERTY);
    }

    private Events run() {
        return EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(HealthFixture.class))
                .execute()
                .testEvents();
    }

    private List<JsonNode> records() throws IOException {
        return Files.readAllLines(workspace.resolve("reports/integration/demo-api.jsonl"), StandardCharsets.UTF_8)
                .stream()
                .map(JSON::readTree)
                .toList();
    }

    @Test
    void native_progress_uses_actual_junit_lifecycle_and_failure_outcomes() throws Exception {
        Path directory = workspace.resolve("native-events");
        ProcessEnvironment.set("CHERRY_TEST_IDE_TEST_EVENTS", directory.toString());
        try {
            Events result = run();
            assertThat(result.succeeded().count()).isEqualTo(1);
            assertThat(result.failed().count()).isEqualTo(1);
            List<JsonNode> events;
            try (var paths = Files.list(directory)) {
                events = paths.map(path -> {
                            try {
                                return JSON.readTree(Files.readString(path));
                            } catch (IOException error) {
                                throw new java.io.UncheckedIOException(error);
                            }
                        })
                        .toList();
            }
            assertThat(events).hasSize(4);
            assertThat(events.stream()
                            .filter(event -> event.path("event").asString().equals("test.started")))
                    .allMatch(event -> event.path("status").asString().equals("pending"));
            var finished = events.stream()
                    .filter(event -> event.path("event").asString().equals("test.completed"))
                    .toList();
            assertThat(finished)
                    .extracting(event -> event.path("status").asString())
                    .containsExactlyInAnyOrder("passed", "failed");
            for (JsonNode end : finished) {
                var start = events.stream()
                        .filter(event -> event.path("event").asString().equals("test.started")
                                && event.path("invocationId").equals(end.path("invocationId")))
                        .findFirst()
                        .orElseThrow();
                assertThat(end.path("name")).isEqualTo(start.path("name"));
                assertThat(end.path("processId").asLong())
                        .isEqualTo(ProcessHandle.current().pid());
                assertThat(end.path("durationMs").asLong()).isGreaterThanOrEqualTo(0);
                assertThat(java.time.Instant.parse(end.path("timestamp").asString()))
                        .isAfterOrEqualTo(
                                java.time.Instant.parse(start.path("timestamp").asString()));
            }
        } finally {
            ProcessEnvironment.remove("CHERRY_TEST_IDE_TEST_EVENTS");
        }
    }

    @Test
    void runs_scoped_tests_with_an_injected_test_env() {
        Events events = run();
        assertThat(events.succeeded().count()).isEqualTo(1);
    }

    @Test
    void fails_the_test_whose_assertion_fails() {
        Events events = run();
        assertThat(events.failed().count()).isEqualTo(1);
    }

    @Test
    void records_every_request_with_its_test_outcome() throws IOException {
        run();
        assertThat(records())
                .extracting(record -> record.get("test").stringValue() + "="
                        + record.get("passed").booleanValue())
                .containsExactlyInAnyOrder("responds_ok=true", "reports_a_missing_page=false");
    }

    @Test
    void records_the_failure_reason_of_a_failed_test() throws IOException {
        run();
        JsonNode failed = records().stream()
                .filter(record -> !record.get("passed").booleanValue())
                .findFirst()
                .orElseThrow();
        assertThat(failed.get("reason").stringValue())
                .startsWith("assertion `left == right` failed: expected HTTP 200, got 404");
    }
}
