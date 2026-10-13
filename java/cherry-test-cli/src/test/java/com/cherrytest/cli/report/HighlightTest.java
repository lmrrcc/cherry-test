package com.cherrytest.cli.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class HighlightTest {
    private static SortedSet<Long> lines(long... numbers) {
        SortedSet<Long> set = new TreeSet<>();
        for (long number : numbers) {
            set.add(number);
        }
        return set;
    }

    @Test
    void same_file_definition_is_preferred_over_another_file() {
        JumpIndex index = JumpIndex.empty();
        Highlight.recordRenderedDefinitions(
                index, "unit", "a.rs", "fn answer() {\n\n\nfn caller() { answer() }\n", lines(1, 4));
        Highlight.recordRenderedDefinitions(index, "unit", "b.rs", "fn answer() {}\n", lines(1));
        assertThat(index.href("unit", "a.rs", 4, "answer")).isEqualTo("#cov-unit-a-rs-L1");
    }

    @Test
    void definition_line_does_not_link_to_the_same_line_in_another_phase() {
        JumpIndex index = JumpIndex.empty();
        String source = "pub struct CatalogService {\n\n\n    CatalogService::new()\n";
        Highlight.recordRenderedDefinitions(index, "integration", "service.rs", source, lines(1, 4));
        Highlight.recordRenderedDefinitions(index, "unit", "service.rs", source, lines(1, 4));
        assertThat(index.href("integration", "service.rs", 1, "CatalogService")).isNull();
        assertThat(index.href("integration", "service.rs", 4, "CatalogService"))
                .isEqualTo("#cov-integration-service-rs-L1");
    }

    @Test
    void ambiguous_name_is_not_linked_across_files() {
        JumpIndex index = JumpIndex.empty();
        Highlight.recordRenderedDefinitions(index, "unit", "a.rs", "fn new() {}\n", lines(1));
        Highlight.recordRenderedDefinitions(index, "unit", "b.rs", "fn new() {}\n", lines(1));
        assertThat(index.href("unit", "c.rs", 3, "new")).isNull();
        assertThat(index.href(null, "c.rs", 3, "new")).isNull();
    }

    @Test
    void equally_distant_definitions_prefer_the_earlier_line() {
        JumpIndex index = JumpIndex.empty();
        Highlight.recordRenderedDefinitions(
                index, "unit", "a.rs", "fn answer() {}\n\nlet value = answer();\n\nfn answer() {}\n", lines(1, 3, 5));
        assertThat(index.href("unit", "a.rs", 3, "answer")).isEqualTo("#cov-unit-a-rs-L1");
    }

    @Test
    void index_links_only_a_globally_unique_name() {
        JumpIndex index = JumpIndex.empty();
        Highlight.recordRenderedDefinitions(index, "integration", "service.rs", "struct CatalogService;\n", lines(1));
        Highlight.recordRenderedDefinitions(index, "unit", "a.rs", "fn new() {}\n", lines(1));
        Highlight.recordRenderedDefinitions(index, "unit", "b.rs", "fn new() {}\n", lines(1));
        assertThat(index.href(null, "other.rs", 10, "CatalogService"))
                .isEqualTo("phases.html#cov-integration-service-rs-L1");
    }

    @Test
    void keyword_is_colored_and_call_links_to_rendered_definition() {
        JumpIndex index = JumpIndex.empty();
        Highlight.recordRenderedDefinitions(
                index, "unit", "lib.rs", "fn answer() {\n    1\n}\nfn caller() {\n    answer()\n}\n", lines(1, 4, 5));
        String call = Highlight.highlightLinked(
                HighlightLang.RUST, "    answer()", name -> index.href("unit", "lib.rs", 5, name));
        assertThat(call).contains("tok-fnc").contains("href=\"#cov-unit-lib-rs-L1\"");
        String definition = Highlight.highlightLinked(
                HighlightLang.RUST, "fn answer() {", name -> index.href("unit", "lib.rs", 1, name));
        assertThat(definition).contains("tok-kw\">fn</span>").doesNotContain("href=");
        assertThat(index.rowAnchor("unit", "lib.rs", 1)).isEqualTo("cov-unit-lib-rs-L1");
    }

    @Test
    void rust_attribute_colors_the_rest_of_the_line() {
        String html = Highlight.highlightLine(HighlightLang.RUST, "#[derive(Debug)] struct Foo;");
        assertThat(html).startsWith("<span class=\"tok-attr\">").doesNotContain("tok-kw");
    }

    @Test
    void strings_comments_and_markup_stay_out_of_definitions() {
        assertThat(Highlight.definedNames(HighlightLang.RUST, "let label = \"fn hidden\"; // fn also"))
                .isEmpty();
        String html = Highlight.highlightLine(HighlightLang.RUST, "let label = \"<script>\"; // fn also");
        assertThat(html)
                .contains("tok-str")
                .contains("tok-cmt")
                .contains("tok-kw\">let</span>")
                .contains("&lt;script&gt;")
                .doesNotContain("<script>");
    }

    @Test
    void snippet_language_keeps_rust_closures_and_plain_let() {
        assertThat(Highlight.snippetLanguage("let value = input;")).isEqualTo(HighlightLang.PLAIN);
        assertThat(Highlight.snippetLanguage("async fn run() { items.map(|item| item) }"))
                .isEqualTo(HighlightLang.RUST);
        assertThat(Highlight.snippetLanguage("export function run() { return 1; }"))
                .isEqualTo(HighlightLang.TYPESCRIPT);
    }

    @Test
    void lifetimes_do_not_swallow_the_rest_of_the_line() {
        String html = Highlight.highlightLine(HighlightLang.RUST, "fn get<'a>(value: &'a str) {}");
        assertThat(html).contains("tok-kw\">fn</span>").contains("get").contains("tok-ty\">str</span>");
        assertThat(Highlight.definedNames(HighlightLang.RUST, "fn get<'a>(value: &'a str)"))
                .isEqualTo(List.of("get"));
    }
}
