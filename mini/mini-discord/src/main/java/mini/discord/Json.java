package mini.discord;

import java.math.BigInteger;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

final class Json {
    static final JsonMapper MAPPER = JsonMapper.builder().build();

    private Json() {}

    static ObjectNode obj(Object... pairs) {
        ObjectNode result = MAPPER.createObjectNode();
        for (int i = 0; i < pairs.length; i += 2) {
            Object value = pairs[i + 1];
            result.set((String) pairs[i], value instanceof JsonNode node ? node.deepCopy() : MAPPER.valueToTree(value));
        }
        return result;
    }

    static ArrayNode arr(Iterable<?> values) {
        ArrayNode result = MAPPER.createArrayNode();
        values.forEach(
                value -> result.add(value instanceof JsonNode node ? node.deepCopy() : MAPPER.valueToTree(value)));
        return result;
    }

    static String str(JsonNode node, String field, String fallback) {
        return node.path(field).asString(fallback);
    }

    static String required(JsonNode node, String field) {
        if (!node.path(field).isString()) throw ApiError.invalid(field);
        String value = node.path(field).asString();
        if (value.isBlank() || value.length() > 4096) throw ApiError.invalid(field);
        return value;
    }

    static String name(JsonNode node, String field, int max) {
        String value = required(node, field);
        if (value.length() > max) throw ApiError.invalid(field);
        return value;
    }

    static int integer(JsonNode node, String field, int fallback, int min, int max) {
        if (!node.has(field)) return fallback;
        try {
            int value = Integer.parseInt(node.path(field).asString());
            if (value < min || value > max) throw ApiError.invalid(field);
            return value;
        } catch (NumberFormatException e) {
            throw ApiError.invalid(field);
        }
    }

    static boolean bool(JsonNode node, String field, boolean fallback) {
        if (!node.has(field)) return fallback;
        if (!node.path(field).isBoolean()) throw ApiError.invalid(field);
        return node.path(field).asBoolean();
    }

    static BigInteger bits(JsonNode node, String field) {
        String value = str(node, field, "0");
        if (!value.matches("[0-9]{1,20}")) throw ApiError.invalid(field);
        BigInteger result = new BigInteger(value);
        if (result.bitLength() > 64) throw ApiError.invalid(field);
        return result;
    }

    static String snowflake(String value) {
        if (!value.matches("[0-9]{1,20}") || new BigInteger(value).bitLength() > 64) throw ApiError.invalid("id");
        return value;
    }

    static List<String> strings(JsonNode node) {
        if (!node.isArray()) throw ApiError.invalid("array");
        List<String> result = new ArrayList<>();
        node.forEach(item -> {
            if (!item.isString()) throw ApiError.invalid("array");
            result.add(item.asString());
        });
        return result;
    }

    static void fields(JsonNode node, String... allowed) {
        if (!node.isObject()) throw ApiError.invalid("body");
        Set<String> names = Set.of(allowed);
        node.properties().forEach(entry -> {
            if (!names.contains(entry.getKey())) throw ApiError.invalid(entry.getKey());
        });
    }

    static ObjectNode form(String raw) {
        ObjectNode out = obj();
        if (raw == null || raw.isEmpty()) return out;
        for (String pair : raw.split("&", -1)) {
            String[] parts = pair.split("=", 2);
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            if (out.has(key)) throw ApiError.invalid(key);
            out.put(key, URLDecoder.decode(parts.length == 2 ? parts[1] : "", StandardCharsets.UTF_8));
        }
        return out;
    }
}
