package com.cherrytest.common.rustcompat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class SerdeJsonWriterTest {
    private static final JsonNode VALUES = GoldenVectors.load("serde-json.json").get("values");

    @Test
    void compact_output_matches_serde_json_bytes() {
        List<String> mismatches = new ArrayList<>();
        for (JsonNode vector : VALUES) {
            String expected = vector.get("compact").stringValue();
            String actual = SerdeJsonWriter.compact(GoldenVectors.parse(expected));
            if (!expected.equals(actual)) {
                mismatches.add("expected " + expected + " got " + actual);
            }
        }
        assertThat(mismatches).isEmpty();
    }

    @Test
    void pretty_output_matches_serde_json_bytes() {
        List<String> mismatches = new ArrayList<>();
        for (JsonNode vector : VALUES) {
            String expected = vector.get("pretty").stringValue();
            String actual = SerdeJsonWriter.pretty(
                    GoldenVectors.parse(vector.get("compact").stringValue()));
            if (!expected.equals(actual)) {
                mismatches.add("expected " + expected + " got " + actual);
            }
        }
        assertThat(mismatches).isEmpty();
    }

    @Test
    void sorted_keys_match_serde_json_value_maps() {
        List<String> mismatches = new ArrayList<>();
        for (JsonNode vector : VALUES) {
            String expected = vector.get("sorted_compact").stringValue();
            JsonNode declarationOrder =
                    GoldenVectors.parse(vector.get("compact").stringValue());
            String actual = SerdeJsonWriter.compact(SerdeJsonWriter.withSortedKeys(declarationOrder));
            if (!expected.equals(actual)) {
                mismatches.add("expected " + expected + " got " + actual);
            }
        }
        assertThat(mismatches).isEmpty();
    }

    @Test
    void large_exponents_carry_an_explicit_plus_sign_like_zmij() {
        assertThat(SerdeJsonWriter.formatFloat(1e21)).isEqualTo("1e+21");
        assertThat(SerdeJsonWriter.formatFloat(1e-7)).isEqualTo("1e-7");
        assertThat(SerdeJsonWriter.formatFloat(1e15)).isEqualTo("1000000000000000.0");
    }
}
