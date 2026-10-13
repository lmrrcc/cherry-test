package com.cherrytest.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class VitestReportTest {
    @Test
    void parses_vitest_json_assertions() {
        String json = """
                {
                  "testResults": [{
                    "assertionResults": [{
                      "title": "formats USDT with grouping",
                      "fullName": "formatUsdtValue formats USDT with grouping",
                      "status": "passed",
                      "duration": 12.4
                    }]
                  }]
                }""";
        List<JsonNode> rows = VitestReport.parseVitestJson(json, "mini-ts-lib-price");
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().get("status").stringValue()).isEqualTo("ok");
        assertThat(rows.getFirst().get("suite").stringValue()).isEqualTo("unit");
        assertThat(rows.getFirst().get("elapsed_ms").longValue()).isEqualTo(12);
    }
}
