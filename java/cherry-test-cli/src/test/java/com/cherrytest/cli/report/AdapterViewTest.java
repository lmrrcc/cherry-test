package com.cherrytest.cli.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AdapterViewTest {
    private static SortedMap<Long, Long> hits(Map<Long, Long> values) {
        return new TreeMap<>(values);
    }

    private static AdapterView.CoveredFile covered_file(String relative, String source, boolean failed) {
        return new AdapterView.CoveredFile(relative, source, hits(Map.of(2L, 4L, 3L, 0L)), failed);
    }

    @Test
    void escapes_untrusted_test_output_and_rejects_external_artifacts(@TempDir Path temp) throws IOException {
        assertThat(AdapterView.escape("<script>\"&")).isEqualTo("&lt;script&gt;&quot;&amp;");
        Path reports = Files.createDirectory(temp.resolve("reports"));
        Path outside = temp.resolve("private.png");
        Files.writeString(outside, "private", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> AdapterView.relativeLink(reports, outside)).isInstanceOf(RuntimeException.class);
        Path inside = reports.resolve("actual image.png");
        Files.writeString(inside, "image", StandardCharsets.UTF_8);
        assertThat(AdapterView.relativeLink(reports, inside)).isEqualTo("actual%20image.png");
    }

    @Test
    void coverage_window_includes_context_above_and_below_and_stops_at_the_file_edge() {
        List<AdapterView.MarkedLine> marked = AdapterView.snippetLines("a\nb\nc\nd\ne\nf\ng\n", hits(Map.of(2L, 1L)));
        assertThat(marked).extracting(AdapterView.MarkedLine::number).containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(marked.get(0).mark()).isEqualTo(AdapterView.LineMark.CONTEXT);
        assertThat(marked.get(1).mark()).isEqualTo(AdapterView.LineMark.COVERED);
        assertThat(marked.get(1).text()).isEqualTo("b");
        assertThat(marked).noneMatch(line -> line.mark() == AdapterView.LineMark.GAP);
    }

    @Test
    void adjacent_measured_lines_share_one_snippet() {
        List<AdapterView.MarkedLine> marked =
                AdapterView.snippetLines("1\n2\n3\n4\n5\n6\n7\n8\n9\n10\n", hits(Map.of(4L, 3L, 5L, 0L)));
        assertThat(marked).noneMatch(line -> line.mark() == AdapterView.LineMark.GAP);
        assertThat(marked.getFirst().number()).isEqualTo(1);
        assertThat(marked.getLast().number()).isEqualTo(8);
        assertThat(marked.get(3).mark()).isEqualTo(AdapterView.LineMark.COVERED);
        assertThat(marked.get(4).mark()).isEqualTo(AdapterView.LineMark.UNCOVERED);
    }

    @Test
    void a_gap_between_runs_inserts_a_separator() {
        String source = IntStream.rangeClosed(1, 20).mapToObj(Integer::toString).collect(Collectors.joining("\n"));
        List<AdapterView.MarkedLine> marked = AdapterView.snippetLines(source, hits(Map.of(1L, 1L, 20L, 0L)));
        assertThat(marked).anyMatch(line -> line.mark() == AdapterView.LineMark.GAP);
        assertThat(marked.getFirst().number()).isEqualTo(1);
        assertThat(marked.getLast().number()).isEqualTo(20);
        assertThat(marked.getLast().mark()).isEqualTo(AdapterView.LineMark.UNCOVERED);
    }

    @Test
    void rendered_covered_line_uses_the_bluish_report_class() {
        AdapterView.CoveredFile file = covered_file("sample.rs", "safe\nlet value = input;\ntrailer\n", false);
        String html = AdapterView.renderCoverageSnippets(file, "unit", JumpIndex.empty());
        assertThat(html)
                .contains("class=\"row ctx covered\"")
                .contains("class=\"row ctx uncovered\"")
                .contains("<span class=\"no old\">old</span>")
                .contains("<span class=\"no new\">new</span>")
                .contains(">code</span>")
                .contains("<span class=\"rail run\">run</span>")
                .contains("<span class=\"rail cov\">cov</span>")
                .contains("<span class=\"no old\">2</span><span class=\"no new\">2</span>")
                .contains("tok-kw\">let</span>")
                .contains("value = input;")
                .doesNotContain("let value = input;")
                .doesNotContain("background:")
                .doesNotContain("#194f39");
        AdapterView.CoveredFile failed =
                new AdapterView.CoveredFile(file.relative(), file.source(), hits(Map.of(2L, 4L, 3L, 0L)), true);
        String failedHtml = AdapterView.renderCoverageSnippets(failed, "unit", JumpIndex.empty());
        assertThat(failedHtml)
                .contains("class=\"row ctx uncovered fail-line\"")
                .contains("aria-label=\"failed line\">!</button>");
    }

    @Test
    void command_click_target_is_the_rendered_definition_row() {
        String source = "fn answer() {\n    1\n}\nfn caller() {\n    answer()\n}\n";
        SortedMap<Long, Long> lineHits = hits(Map.of(1L, 1L, 5L, 1L));
        AdapterView.CoveredFile file = new AdapterView.CoveredFile("lib.rs", source, lineHits, false);
        JumpIndex index = JumpIndex.empty();
        Highlight.recordRenderedDefinitions(
                index, "unit", "lib.rs", source, AdapterView.renderedLineNumbers(source, lineHits));
        String html = AdapterView.renderCoverageSnippets(file, "unit", index);
        assertThat(html)
                .contains("id=\"cov-unit-lib-rs-L1\"")
                .contains("href=\"#cov-unit-lib-rs-L1\"")
                .contains("tok-kw\">fn</span>");
    }
}
