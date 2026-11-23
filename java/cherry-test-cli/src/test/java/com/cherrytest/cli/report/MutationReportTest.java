package com.cherrytest.cli.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.cherrytest.cli.adapters.Json;
import com.cherrytest.common.adapter.AdapterRequest;
import com.cherrytest.common.adapter.ExecutionMode;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

class MutationReportTest {
    @TempDir
    Path root;

    private JsonNode report() {
        return Json.parse("""
                {"tool":"pitest","status":"complete","sources":["src/main/java/Price.java"],"mutants":[
                {"status":"KILLED","detected":true,"source":"src/main/java/Price.java","line":2,"class":"Price","method":"price","mutator":"returns","description":"<script>alert(1)</script>","killing_test":"PriceTest.price"},
                {"status":"SURVIVED","detected":false,"source":"src/main/java/Price.java","line":3,"class":"Price","method":"price","mutator":"returns","description":"replaced return","killing_test":null},
                {"status":"NO_COVERAGE","detected":false,"source":"src/main/java/Price.java","line":4,"class":"Price","method":"unused","mutator":"returns","description":"replaced return","killing_test":null}]}
                """);
    }

    @Test
    void score_includes_surviving_and_uncovered_mutants() {
        assertThat(MutationReport.summary(report()))
                .contains("33.3% (1/3 detected)", "KILLED: 1, NO_COVERAGE: 1, SURVIVED: 1");
    }

    @Test
    void no_changes_is_not_reported_as_a_perfect_score() {
        assertThat(MutationReport.summary(Json.parse("{\"status\":\"no_changes\"}")))
                .contains("no changed production sources")
                .doesNotContain("%");
    }

    @Test
    void detail_preserves_statuses_and_escapes_mutant_text() {
        var request = new AdapterRequest(
                1,
                "demo",
                "prices",
                "mutation",
                root,
                root.resolve("pom.xml"),
                root,
                root,
                ExecutionMode.LOCAL,
                false,
                true,
                List.of(),
                new TreeMap<>(),
                new TreeMap<>());
        String html = MutationReport.details(request, report(), root);
        assertThat(html)
                .contains(
                        "&lt;script&gt;alert(1)&lt;/script&gt;",
                        "SURVIVED</td>",
                        "NO_COVERAGE</td>",
                        "src/main/java/Price.java:2")
                .doesNotContain("<script>");
    }
}
