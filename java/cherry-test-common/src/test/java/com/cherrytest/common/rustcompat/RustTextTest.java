package com.cherrytest.common.rustcompat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class RustTextTest {
    private static final JsonNode STRINGS = GoldenVectors.load("strings.json").get("strings");

    @Test
    void whitespace_predicate_matches_rust_for_every_code_point() {
        List<int[]> ranges = new ArrayList<>();
        GoldenVectors.load("whitespace.json")
                .get("whitespace_ranges")
                .forEach(range -> ranges.add(
                        new int[] {range.get(0).intValue(), range.get(1).intValue()}));
        List<Integer> mismatches = new ArrayList<>();
        for (int codePoint = 0; codePoint <= Character.MAX_CODE_POINT; codePoint++) {
            int candidate = codePoint;
            boolean expected = ranges.stream().anyMatch(range -> candidate >= range[0] && candidate <= range[1]);
            if (RustText.isWhitespace(codePoint) != expected) {
                mismatches.add(codePoint);
            }
        }
        assertThat(mismatches).isEmpty();
    }

    @Test
    void trim_matches_rust() {
        assertTextFunction("trim", RustText::trim);
    }

    @Test
    void trim_start_matches_rust() {
        assertTextFunction("trim_start", RustText::trimStart);
    }

    @Test
    void lines_match_rust() {
        assertListFunction("lines", RustText::lines);
    }

    @Test
    void split_whitespace_matches_rust() {
        assertListFunction("split_whitespace", RustText::splitWhitespace);
    }

    @Test
    void uppercase_matches_rust() {
        assertTextFunction("to_uppercase", RustText::toUppercase);
    }

    @Test
    void lowercase_matches_rust() {
        assertTextFunction("to_lowercase", RustText::toLowercase);
    }

    @Test
    void ascii_lowercase_matches_rust() {
        assertTextFunction("to_ascii_lowercase", RustText::toAsciiLowercase);
    }

    @Test
    void char_count_matches_rust() {
        assertTextFunction("chars_count", input -> Integer.toString(RustText.charCount(input)));
    }

    @Test
    void utf8_length_matches_rust() {
        assertTextFunction("utf8_len", input -> Integer.toString(RustText.utf8Length(input)));
    }

    @Test
    void lone_carriage_return_at_end_is_kept_like_rust() {
        assertThat(RustText.lines("a\r")).containsExactly("a\r");
        assertThat(RustText.lines("a\r\nb\n")).containsExactly("a", "b");
    }

    private static void assertTextFunction(String field, Function<String, String> function) {
        List<String> mismatches = new ArrayList<>();
        for (JsonNode vector : STRINGS) {
            String input = vector.get("input").stringValue();
            String expected = vector.get(field).asString();
            String actual = function.apply(input);
            if (!expected.equals(actual)) {
                mismatches.add(escape(input) + " expected " + escape(expected) + " got " + escape(actual));
            }
        }
        assertThat(mismatches).isEmpty();
    }

    private static void assertListFunction(String field, Function<String, List<String>> function) {
        List<String> mismatches = new ArrayList<>();
        for (JsonNode vector : STRINGS) {
            String input = vector.get("input").stringValue();
            List<String> expected = GoldenVectors.strings(vector.get(field));
            List<String> actual = function.apply(input);
            if (!expected.equals(actual)) {
                mismatches.add(escape(input) + " expected " + expected + " got " + actual);
            }
        }
        assertThat(mismatches).isEmpty();
    }

    private static String escape(String text) {
        StringBuilder escaped = new StringBuilder();
        text.codePoints()
                .forEach(codePoint -> escaped.append(
                        codePoint < 0x20 || codePoint > 0x7E
                                ? "\\u{" + Integer.toHexString(codePoint) + "}"
                                : Character.toString(codePoint)));
        return escaped.toString();
    }
}
