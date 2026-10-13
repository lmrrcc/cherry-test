package com.cherrytest.common.reportshell;

import static com.cherrytest.common.reportshell.ReportShell.REPORT_HTML_JUMP_SCRIPT;
import static com.cherrytest.common.reportshell.ReportShell.REPORT_HTML_STYLES;
import static com.cherrytest.common.reportshell.ReportShell.REPORT_HTML_TEST_FILTER_SCRIPT;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ReportShellTest {
    private static final List<String> PRIMITIVE_SCALES =
            List.of("--gray-", "--accent-", "--pass-", "--fail-", "--warn-", "--radius-");

    @Test
    void styles_use_github_pull_request_gutters_and_a_blue_covered_line() {
        assertThat(REPORT_HTML_STYLES)
                .contains("--covered: #dbeafe;")
                .contains("--diff-add-bg: #e6ffec;")
                .contains("--diff-add-gutter: #ccffd8;")
                .contains("--diff-del-bg: #ffebe9;")
                .contains(".diff .row.add .no { background: var(--diff-add-gutter); }")
                .contains(".diff .row.del .no { background: var(--diff-del-gutter); }")
                .contains(".diff .row.covered .code { background: var(--covered); }")
                .contains("--rail-pass: #1a7f37;")
                .contains(".diff .row.fail-line .rail.run")
                .contains(".diff .row.uncovered .rail.cov")
                .contains("++ added · -- removed")
                .contains(".test-toolbar select")
                .contains("min-width: 0;")
                .doesNotContain(".diff .row.add .code { background")
                .doesNotContain(".diff .row.del .code { background");
        assertThat(REPORT_HTML_TEST_FILTER_SCRIPT).contains("function placeWhy").contains("closest('.why .flag')");
    }

    @Test
    void diff_row_has_old_and_new_columns() {
        String row = ReportShell.diffRowHtml("add covered", "", "12", "++", "let value = 1;");
        assertThat(row)
                .contains("<span class=\"rail run\"></span>")
                .contains("<span class=\"rail cov\"></span>")
                .contains("<span class=\"no old\"></span>")
                .contains("<span class=\"no new\">12</span>")
                .contains("<span class=\"mark\">++</span>")
                .contains("class=\"row add covered\"");
        assertThat(ReportShell.diffRowHtml("del", "3", "", "--", "old")).contains("<span class=\"mark\">--</span>");
        assertThat(ReportShell.diffRowHtml("add uncovered fail-line", "", "4", "++", "gap"))
                .contains("aria-label=\"failed line\">!</button>");
        assertThat(ReportShell.diffRowHtml("column-labels", "old", "new", "±", "code"))
                .contains("<span class=\"rail run\">run</span>")
                .contains("<span class=\"rail cov\">cov</span>");
    }

    @Test
    void combined_document_includes_theme_toggle() {
        String document = ReportShell.renderCombinedReportDocument("t", "ok", "<section></section>");
        assertThat(document).contains("theme-toggle").contains("cherry-test-theme");
    }

    @Test
    void jump_script_requires_command_or_control_and_opens_details() {
        assertThat(REPORT_HTML_STYLES)
                .contains("--tok-kw: #cf222e;")
                .contains(".diff .tok-kw { color: var(--tok-kw); }");
        assertThat(REPORT_HTML_JUMP_SCRIPT)
                .contains("event.metaKey")
                .contains("event.ctrlKey")
                .contains("DETAILS");
        assertThat(ReportShell.renderCombinedReportDocument("t", "", "")).contains("event.metaKey");
        assertThat(ReportShell.diffRowWithId("cov-unit-a-rs-L1", "ctx covered", "1", "1", "", "fn"))
                .contains(" id=\"cov-unit-a-rs-L1\"");
        assertThat(ReportShell.diffRowHtml("ctx", "1", "1", "", "fn")).doesNotContain(" id=");
    }

    private static String designSystemTokens() throws IOException {
        return Files.readString(Path.of("../../site/design-system/tokens.css"), StandardCharsets.UTF_8);
    }

    private static boolean primitive(String name) {
        for (String scale : PRIMITIVE_SCALES) {
            if (name.startsWith(scale)) {
                String step = name.substring(scale.length());
                return !step.isEmpty() && step.chars().allMatch(character -> character >= '0' && character <= '9');
            }
        }
        return false;
    }

    private static List<Map.Entry<String, String>> declarations(String stylesheet, String selector) {
        int found = stylesheet.indexOf(selector);
        if (found < 0) {
            throw new AssertionError("selector " + selector + " missing");
        }
        int blockStart = found + selector.length();
        int closing = stylesheet.indexOf('}', blockStart);
        int blockEnd = closing < 0 ? blockStart : closing;
        List<Map.Entry<String, String>> declarations = new ArrayList<>();
        for (String declaration : stylesheet.substring(blockStart, blockEnd).split(";", -1)) {
            int colon = declaration.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String name = declaration.substring(0, colon).strip();
            if (primitive(name)) {
                declarations.add(
                        Map.entry(name, declaration.substring(colon + 1).strip()));
            }
        }
        return declarations;
    }

    private static void assert_primitives_match(String reportSelector, String tokensSelector) throws IOException {
        List<Map.Entry<String, String>> report = declarations(REPORT_HTML_STYLES, reportSelector);
        List<Map.Entry<String, String>> tokens = declarations(designSystemTokens(), tokensSelector);
        assertThat(report).isNotEmpty();
        for (Map.Entry<String, String> declaration : report) {
            Optional<String> token = tokens.stream()
                    .filter(candidate -> candidate.getKey().equals(declaration.getKey()))
                    .map(Map.Entry::getValue)
                    .findFirst();
            assertThat(token).as(declaration.getKey()).contains(declaration.getValue());
        }
        for (Map.Entry<String, String> token : tokens) {
            if (token.getKey().startsWith("--radius-")) {
                continue;
            }
            assertThat(report)
                    .as(token.getKey() + " missing")
                    .anyMatch(declaration -> declaration.getKey().equals(token.getKey()));
        }
    }

    @Test
    void light_primitives_match_design_system_tokens() throws IOException {
        assert_primitives_match(":root {", ":root {");
    }

    @Test
    void dark_primitives_match_design_system_tokens() throws IOException {
        assert_primitives_match("html[data-theme=\"dark\"] {", ":root[data-theme=\"dark\"] {");
    }
}
