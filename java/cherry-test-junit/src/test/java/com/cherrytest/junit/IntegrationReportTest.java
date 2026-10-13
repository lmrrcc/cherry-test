package com.cherrytest.junit;

import static org.assertj.core.api.Assertions.assertThat;

import com.cherrytest.common.process.ProcessEnvironment;
import com.cherrytest.common.rustcompat.SerdeJsonWriter;
import com.cherrytest.core.TestId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IntegrationReportTest {
    private static final HttpRecord RECORD = new HttpRecord(
            TestId.parse("demo_project_health_suite_responds_test"),
            "none",
            "demo",
            "api",
            7,
            "GET",
            "http://127.0.0.1:1/health",
            200,
            null,
            "Bearer [redacted]",
            "ok",
            false,
            "boom");

    @AfterEach
    void forget_the_report_dir() {
        ProcessEnvironment.remove("CHERRY_TEST_REPORT_DIR");
    }

    @Test
    void writes_a_line_with_sorted_keys_like_serde_json() {
        String line = SerdeJsonWriter.compact(SerdeJsonWriter.withSortedKeys(IntegrationReport.line(RECORD)));
        assertThat(line)
                .isEqualTo("{\"authorization\":\"Bearer [redacted]\",\"elapsed_ms\":7,\"full_name\":"
                        + "\"demo_project_health_suite_responds_test\",\"method\":\"GET\",\"passed\":false,"
                        + "\"project\":\"demo\",\"reason\":\"boom\",\"request_body\":null,\"response_body\":\"ok\","
                        + "\"scope\":\"none\",\"status\":200,\"suite\":\"health\",\"target_project\":\"demo\","
                        + "\"target_service\":\"api\",\"test\":\"responds\",\"url\":\"http://127.0.0.1:1/health\"}");
    }

    @Test
    void appends_records_to_the_package_file_under_the_report_dir(@TempDir Path reports) throws IOException {
        ProcessEnvironment.set("CHERRY_TEST_REPORT_DIR", reports.toString());
        IntegrationReport.appendRecords("demo-api", List.of(RECORD));
        IntegrationReport.appendRecords("demo-api", List.of(RECORD));
        List<String> lines = Files.readAllLines(reports.resolve("integration/demo-api.jsonl"), StandardCharsets.UTF_8);
        assertThat(lines).hasSize(2);
    }

    @Test
    void writes_nothing_when_a_test_made_no_requests(@TempDir Path reports) {
        ProcessEnvironment.set("CHERRY_TEST_REPORT_DIR", reports.toString());
        IntegrationReport.appendRecords("demo-api", List.of());
        assertThat(reports.resolve("integration")).doesNotExist();
    }
}
