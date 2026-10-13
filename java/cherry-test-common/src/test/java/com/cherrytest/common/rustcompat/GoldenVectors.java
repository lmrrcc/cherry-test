package com.cherrytest.common.rustcompat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class GoldenVectors {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private GoldenVectors() {}

    static JsonNode load(String name) {
        try (InputStream stream = GoldenVectors.class.getResourceAsStream("/vectors/" + name)) {
            if (stream == null) {
                throw new IllegalStateException("missing golden vector file " + name);
            }
            return MAPPER.readTree(stream);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static JsonNode parse(String json) {
        return MAPPER.readTree(json);
    }

    static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(element -> values.add(element.stringValue()));
        return values;
    }

    static double doubleFromBits(JsonNode vector) {
        return Double.longBitsToDouble(Long.parseUnsignedLong(vector.get("bits").stringValue(), 16));
    }
}
