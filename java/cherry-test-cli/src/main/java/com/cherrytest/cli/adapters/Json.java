package com.cherrytest.cli.adapters;

import com.cherrytest.common.rustcompat.AnyhowError;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

public final class Json {
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private Json() {}

    public static JsonNode parse(String content) {
        return parseBytes(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public static JsonNode parseBytes(byte[] content) {
        String syntaxError = SerdeJsonSyntax.firstError(content);
        if (syntaxError != null) {
            throw new AnyhowError(syntaxError);
        }
        try {
            return MAPPER.readTree(content);
        } catch (tools.jackson.core.JacksonException exception) {
            String message = exception.getOriginalMessage();
            throw new AnyhowError(message == null ? "invalid JSON" : message);
        }
    }

    public static JsonNode index(JsonNode node, String key) {
        if (node.isObject()) {
            JsonNode value = node.get(key);
            return value == null ? JsonNodeFactory.instance.nullNode() : value;
        }
        return JsonNodeFactory.instance.nullNode();
    }

    public static @Nullable String string(JsonNode node) {
        return node.isString() ? node.stringValue() : null;
    }

    public static @Nullable Double f64(JsonNode node) {
        return node.isNumber() ? node.doubleValue() : null;
    }

    public static @Nullable Long u64(JsonNode node) {
        if (!node.isIntegralNumber()) {
            return null;
        }
        java.math.BigInteger value = node.bigIntegerValue();
        if (value.signum() < 0 || value.bitLength() > 64) {
            return null;
        }
        return value.longValue();
    }

    public static @Nullable Long i64OrU64(JsonNode node) {
        return u64(node);
    }
}
