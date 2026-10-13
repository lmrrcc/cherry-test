package com.cherrytest.common.rustcompat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class RustFloatFormatTest {
    private static final JsonNode FLOATS = GoldenVectors.load("floats.json").get("floats");

    @Test
    void display_matches_rust_for_every_golden_vector() {
        List<String> mismatches = new ArrayList<>();
        for (JsonNode vector : FLOATS) {
            String expected = vector.get("display").stringValue();
            String actual = RustFloatFormat.display(GoldenVectors.doubleFromBits(vector));
            if (!expected.equals(actual)) {
                mismatches.add(vector.get("bits").stringValue() + " expected " + expected + " got " + actual);
            }
        }
        assertThat(mismatches).isEmpty();
    }

    @Test
    void fixed_precision_matches_rust_for_every_golden_vector() {
        List<String> mismatches = new ArrayList<>();
        for (JsonNode vector : FLOATS) {
            double value = GoldenVectors.doubleFromBits(vector);
            List<String> expected = GoldenVectors.strings(vector.get("fixed"));
            for (int precision = 0; precision < expected.size(); precision++) {
                String actual = RustFloatFormat.fixed(value, precision);
                if (!expected.get(precision).equals(actual)) {
                    mismatches.add(vector.get("bits").stringValue() + " precision " + precision + " expected "
                            + expected.get(precision) + " got " + actual);
                }
            }
        }
        assertThat(mismatches).isEmpty();
    }

    @Test
    void exact_ties_round_to_even_like_rust() {
        assertThat(RustFloatFormat.fixed(0.125, 2)).isEqualTo("0.12");
        assertThat(RustFloatFormat.fixed(2.5, 0)).isEqualTo("2");
        assertThat(RustFloatFormat.fixed(-0.04, 1)).isEqualTo("-0.0");
    }
}
