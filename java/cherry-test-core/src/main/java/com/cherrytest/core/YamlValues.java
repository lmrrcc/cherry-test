package com.cherrytest.core;

import com.cherrytest.common.rustcompat.AnyhowError;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.schema.CoreSchema;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

final class YamlValues {
    private YamlValues() {}

    static Map<String, Object> parseDocument(String contents) {
        LoadSettings settings = LoadSettings.builder()
                .setSchema(new CoreSchema())
                .setAllowDuplicateKeys(false)
                .build();
        Object document = new Load(settings).loadFromString(contents);
        if (document == null) {
            return Map.of();
        }
        return mapping(document, "");
    }

    static Map<String, Object> mapping(Object value, String path) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new AnyhowError(prefix(path) + "invalid type: expected a map");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            Object key = entry.getKey();
            Object entryValue = entry.getValue();
            result.put(
                    key == null ? "null" : String.valueOf(key), entryValue == null ? NullValue.INSTANCE : entryValue);
        }
        return result;
    }

    static @Nullable Object field(Map<String, Object> map, String name) {
        Object value = map.get(name);
        if (value == null || value == NullValue.INSTANCE) {
            return null;
        }
        return value;
    }

    static boolean present(Map<String, Object> map, String name) {
        return map.containsKey(name);
    }

    static String string(Object value, String path) {
        if (value instanceof String text) {
            return text;
        }
        throw new AnyhowError(prefix(path) + "invalid type: " + describe(value) + ", expected a string");
    }

    static @Nullable String optionalString(Map<String, Object> map, String name, String path) {
        Object value = field(map, name);
        return value == null ? null : string(value, path + name);
    }

    static String requiredString(Map<String, Object> map, String name, String path) {
        Object value = field(map, name);
        if (value == null) {
            throw new AnyhowError(prefix(path) + "missing field `" + name + "`");
        }
        return string(value, path + name);
    }

    static String stringOr(Map<String, Object> map, String name, String path, String fallback) {
        String value = optionalString(map, name, path);
        return value == null ? fallback : value;
    }

    static boolean boolOr(Map<String, Object> map, String name, String path, boolean fallback) {
        Object value = field(map, name);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        throw new AnyhowError(prefix(path + name) + "invalid type: " + describe(value) + ", expected a boolean");
    }

    static long unsigned(Object value, String path, long maximum) {
        if (value instanceof Number number && !(value instanceof Double) && !(value instanceof Float)) {
            BigInteger integer = new BigInteger(number.toString());
            if (integer.signum() < 0 || integer.compareTo(BigInteger.valueOf(maximum)) > 0) {
                throw new AnyhowError(
                        prefix(path) + "invalid value: integer `" + integer + "`, expected u" + bits(maximum));
            }
            return integer.longValueExact();
        }
        throw new AnyhowError(prefix(path) + "invalid type: " + describe(value) + ", expected u" + bits(maximum));
    }

    static long unsignedOr(Map<String, Object> map, String name, String path, long maximum, long fallback) {
        Object value = field(map, name);
        return value == null ? fallback : unsigned(value, path + name, maximum);
    }

    static @Nullable Path optionalPath(Map<String, Object> map, String name, String path) {
        String value = optionalString(map, name, path);
        return value == null ? null : Path.of(value);
    }

    static List<String> stringList(Map<String, Object> map, String name, String path) {
        Object value = field(map, name);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new AnyhowError(prefix(path + name) + "invalid type: " + describe(value) + ", expected a sequence");
        }
        List<String> result = new ArrayList<>();
        int index = 0;
        for (Object element : list) {
            result.add(string(element == null ? NullValue.INSTANCE : element, path + name + "[" + index + "]"));
            index++;
        }
        return result;
    }

    static JsonNode json(@Nullable Object value) {
        JsonNodeFactory factory = JsonNodeFactory.instance;
        if (value == null || value == NullValue.INSTANCE) {
            return factory.nullNode();
        }
        return switch (value) {
            case String text -> factory.stringNode(text);
            case Boolean bool -> factory.booleanNode(bool);
            case Integer number -> factory.numberNode(number.longValue());
            case Long number -> factory.numberNode(number);
            case BigInteger number -> factory.numberNode(number);
            case Double number -> factory.numberNode(number);
            case Float number -> factory.numberNode(number.doubleValue());
            case List<?> list -> {
                ArrayNode array = factory.arrayNode();
                list.forEach(element -> array.add(json(element)));
                yield array;
            }
            case Map<?, ?> map -> {
                ObjectNode object = factory.objectNode();
                map.forEach((key, element) -> object.set(String.valueOf(key), json(element)));
                yield object;
            }
            default -> factory.stringNode(String.valueOf(value));
        };
    }

    static String describe(Object value) {
        return switch (value) {
            case NullValue ignored -> "unit value";
            case String text -> "string \"" + text + "\"";
            case Boolean bool -> "boolean `" + bool + "`";
            case Double number -> "floating point `" + number + "`";
            case Number number -> "integer `" + number + "`";
            case List<?> ignored -> "sequence";
            case Map<?, ?> ignored -> "map";
            default -> "value";
        };
    }

    private static String bits(long maximum) {
        return maximum == 0xFFFFL ? "16" : maximum == 0xFFFFFFFFL ? "32" : "64";
    }

    private static String prefix(String path) {
        return path.isEmpty() ? "" : path + ": ";
    }

    enum NullValue {
        INSTANCE
    }
}
