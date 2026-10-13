package com.cherrytest.common.rustcompat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class RustOrderingTest {
    private static final JsonNode ORDERING = GoldenVectors.load("ordering.json");

    @Test
    void string_comparison_matches_rust_for_every_pair() {
        assertPairwise(ORDERING.get("strings"), RustOrdering.STRING_ORDER);
    }

    @Test
    void path_comparison_matches_rust_for_every_pair() {
        assertPairwise(ORDERING.get("paths"), RustOrdering.PATH_ORDER);
    }

    @Test
    void stable_string_sort_matches_rust() {
        assertSorted(ORDERING.get("strings"), RustOrdering.STRING_ORDER);
    }

    @Test
    void stable_path_sort_matches_rust() {
        assertSorted(ORDERING.get("paths"), RustOrdering.PATH_ORDER);
    }

    @Test
    void paths_compare_by_component_not_by_text() {
        assertThat(RustOrdering.comparePaths("a/b", "a.b")).isNegative();
        assertThat("a/b".compareTo("a.b")).isPositive();
    }

    private static void assertPairwise(JsonNode group, Comparator<String> comparator) {
        List<String> inputs = GoldenVectors.strings(group.get("input"));
        List<String> mismatches = new ArrayList<>();
        for (JsonNode triple : group.get("compare")) {
            String left = inputs.get(triple.get(0).intValue());
            String right = inputs.get(triple.get(1).intValue());
            int expected = triple.get(2).intValue();
            int actual = Integer.signum(comparator.compare(left, right));
            if (expected != actual) {
                mismatches.add("compare(" + left + ", " + right + ") expected " + expected + " got " + actual);
            }
        }
        assertThat(mismatches).isEmpty();
    }

    private static void assertSorted(JsonNode group, Comparator<String> comparator) {
        List<String> sorted = new ArrayList<>(GoldenVectors.strings(group.get("input")));
        sorted.sort(comparator);
        assertThat(sorted).containsExactlyElementsOf(GoldenVectors.strings(group.get("sorted")));
    }
}
