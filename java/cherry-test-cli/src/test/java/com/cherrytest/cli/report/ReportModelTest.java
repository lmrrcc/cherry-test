package com.cherrytest.cli.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class ReportModelTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static IntegrationRow integration_row(Map<String, Object> extra) {
        ObjectNode row = JSON.createObjectNode();
        row.put("project", "mini");
        row.put("suite", "wallet");
        row.put("test", "bad_address_returns_400");
        row.put("full_name", "mini_project_wallet_suite_bad_address_returns_400_test");
        row.put("target_project", "mini");
        row.put("target_service", "api");
        row.put("method", "GET");
        row.put("url", "http://127.0.0.1/x");
        extra.forEach((key, value) -> row.set(key, JSON.valueToTree(value)));
        return Objects.requireNonNull(ReportModel.parseIntegrationRow(row));
    }

    @Test
    void status_line_respects_max_length() {
        Snapshots snapshot = new Snapshots();
        snapshot.runId = "20260523-143022-a1b2c3";
        snapshot.packagesPassed = 1;
        snapshot.packagesTotal = 1;
        snapshot.coverageEnabled = false;
        snapshot.reportPath = Path.of("target/cherry-test/runs/20260523-143022-a1b2c3/reports/index.html");
        String line = ReportModel.formatStatusLine(snapshot.build(), 80);
        assertThat(line.codePointCount(0, line.length())).isLessThanOrEqualTo(80);
    }

    @Test
    void keeps_the_failure_location_of_a_row() {
        IntegrationRow row = integration_row(Map.of("status", "FAIL", "source", "/w/src/tests.rs", "line", 217));
        assertThat(row.location).isEqualTo(new IntegrationRow.Location(Path.of("/w/src/tests.rs"), 217));
    }

    @Test
    void leaves_a_row_without_a_line_unlocated() {
        IntegrationRow row = integration_row(Map.of("status", "FAIL", "source", "/w/src/tests.rs"));
        assertThat(row.location).isNull();
    }

    @Test
    void negative_test_with_4xx_response_is_ok_when_passed() {
        IntegrationRow row = integration_row(Map.of("status", 400, "passed", true));
        assertThat(row.statusLabel()).isEqualTo("ok");
        assertThat(row.http()).isEqualTo("400");
        assertThat(row.reason()).isNull();
    }

    @Test
    void counts_one_test_for_multiple_http_records_and_its_adapter_result(@TempDir Path root) throws IOException {
        Path integration = Files.createDirectory(root.resolve("integration"));
        String name = "demo_project_http_suite_health_test";
        Files.writeString(
                integration.resolve("api.jsonl"),
                "{\"project\":\"demo\",\"suite\":\"http\",\"test\":\"health\",\"full_name\":\"" + name
                        + "\",\"status\":200,\"passed\":true,\"elapsed_ms\":3}\n"
                        + "{\"project\":\"demo\",\"suite\":\"http\",\"test\":\"health\",\"full_name\":\"" + name
                        + "\",\"status\":500,\"passed\":false,\"elapsed_ms\":7,\"reason\":\"unavailable\"}\n",
                StandardCharsets.UTF_8);
        Path adapter = Files.createDirectories(root.resolve("adapters/demo/api/integration"));
        Files.writeString(
                adapter.resolve("result.json"),
                "{\"package\":\"api\",\"phase\":\"integration\",\"project\":\"demo\",\"result\":{\"coverage\":[],"
                        + "\"diagnostics\":[],\"protocol\":1,\"success\":false,\"tests\":[{\"attachments\":[],"
                        + "\"duration_ms\":0,\"message\":\"failed assertion\",\"name\":\"" + name
                        + "\",\"status\":\"failed\"}]}}",
                StandardCharsets.UTF_8);
        List<IntegrationRow> rows = ReportModel.loadIntegrationRows(root);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().statusLabel()).isEqualTo("FAIL");
        assertThat(rows.getFirst().elapsedMs()).isEqualTo(10);
        assertThat(ReportModel.integrationStats(rows, TestViewTest.noPreflight()))
                .containsExactly(1, 0, 1);
    }

    @Test
    void failed_test_is_fail_with_reason_even_on_2xx() {
        IntegrationRow row =
                integration_row(Map.of("status", 200, "passed", false, "reason", "expected HTTP 201, got 200"));
        assertThat(row.statusLabel()).isEqualTo("FAIL");
        assertThat(row.reason()).isEqualTo("expected HTTP 201, got 200");
    }

    @Test
    void missing_passed_falls_back_to_status_heuristic() {
        assertThat(integration_row(Map.of("status", 200)).statusLabel()).isEqualTo("ok");
        assertThat(integration_row(Map.of("status", 500)).statusLabel()).isEqualTo("FAIL");
    }
}
