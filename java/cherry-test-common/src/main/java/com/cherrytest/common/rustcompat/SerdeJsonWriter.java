package com.cherrytest.common.rustcompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

public final class SerdeJsonWriter {
    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();
    private static final int PLAIN_NOTATION_MAXIMUM_POINT_POSITION = 16;
    private static final int PLAIN_NOTATION_MINIMUM_POINT_POSITION = -5;
    private static final String INDENT = "  ";

    private SerdeJsonWriter() {}

    public static String compact(JsonNode value) {
        StringBuilder output = new StringBuilder();
        writeCompact(value, output);
        return output.toString();
    }

    public static String pretty(JsonNode value) {
        StringBuilder output = new StringBuilder();
        writePretty(value, output, 0);
        return output.toString();
    }

    public static JsonNode withSortedKeys(JsonNode value) {
        if (value.isObject()) {
            List<Map.Entry<String, JsonNode>> entries = new ArrayList<>(value.properties());
            entries.sort(Map.Entry.comparingByKey(RustOrdering.STRING_ORDER));
            ObjectNode sorted = JsonNodeFactory.instance.objectNode();
            entries.forEach(entry -> sorted.set(entry.getKey(), withSortedKeys(entry.getValue())));
            return sorted;
        }
        if (value.isArray()) {
            ArrayNode sorted = JsonNodeFactory.instance.arrayNode();
            value.forEach(element -> sorted.add(withSortedKeys(element)));
            return sorted;
        }
        return value;
    }

    static String formatFloat(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "null";
        }
        String sign = RustFloatFormat.signOf(value);
        if (value == 0.0) {
            return sign + "0.0";
        }
        ShortestDecimal decimal = ShortestDecimal.of(Math.abs(value));
        String digits = decimal.digits();
        int exponent = decimal.exponent();
        int pointPosition = digits.length() + exponent;
        if (exponent >= 0 && pointPosition <= PLAIN_NOTATION_MAXIMUM_POINT_POSITION) {
            return sign + digits + "0".repeat(exponent) + ".0";
        }
        if (pointPosition > 0 && pointPosition <= PLAIN_NOTATION_MAXIMUM_POINT_POSITION) {
            return sign + digits.substring(0, pointPosition) + "." + digits.substring(pointPosition);
        }
        if (pointPosition > PLAIN_NOTATION_MINIMUM_POINT_POSITION && pointPosition <= 0) {
            return sign + "0." + "0".repeat(-pointPosition) + digits;
        }
        int scientificExponent = pointPosition - 1;
        String exponentText = (scientificExponent >= 0 ? "+" : "") + scientificExponent;
        if (digits.length() == 1) {
            return sign + digits + "e" + exponentText;
        }
        return sign + digits.charAt(0) + "." + digits.substring(1) + "e" + exponentText;
    }

    private static void writeCompact(JsonNode value, StringBuilder output) {
        switch (value.getNodeType()) {
            case OBJECT -> {
                output.append('{');
                boolean first = true;
                for (Map.Entry<String, JsonNode> entry : value.properties()) {
                    if (!first) {
                        output.append(',');
                    }
                    writeString(entry.getKey(), output);
                    output.append(':');
                    writeCompact(entry.getValue(), output);
                    first = false;
                }
                output.append('}');
            }
            case ARRAY -> {
                output.append('[');
                boolean first = true;
                for (JsonNode element : value) {
                    if (!first) {
                        output.append(',');
                    }
                    writeCompact(element, output);
                    first = false;
                }
                output.append(']');
            }
            case STRING -> writeString(value.stringValue(), output);
            case NUMBER -> writeNumber(value, output);
            case BOOLEAN -> output.append(value.booleanValue());
            case NULL -> output.append("null");
            case BINARY, MISSING, POJO ->
                throw new IllegalArgumentException("unsupported JSON node type " + value.getNodeType());
        }
    }

    private static void writePretty(JsonNode value, StringBuilder output, int depth) {
        if (value.isObject() && !value.isEmpty()) {
            output.append("{\n");
            int remaining = value.size();
            for (Map.Entry<String, JsonNode> entry : value.properties()) {
                output.append(INDENT.repeat(depth + 1));
                writeString(entry.getKey(), output);
                output.append(": ");
                writePretty(entry.getValue(), output, depth + 1);
                remaining--;
                output.append(remaining > 0 ? ",\n" : "\n");
            }
            output.append(INDENT.repeat(depth)).append('}');
            return;
        }
        if (value.isArray() && !value.isEmpty()) {
            output.append("[\n");
            int remaining = value.size();
            for (JsonNode element : value) {
                output.append(INDENT.repeat(depth + 1));
                writePretty(element, output, depth + 1);
                remaining--;
                output.append(remaining > 0 ? ",\n" : "\n");
            }
            output.append(INDENT.repeat(depth)).append(']');
            return;
        }
        writeCompact(value, output);
    }

    private static void writeNumber(JsonNode value, StringBuilder output) {
        if (value.isIntegralNumber()) {
            output.append(value.bigIntegerValue());
            return;
        }
        if (value.isDouble() || value.isFloat()) {
            output.append(formatFloat(value.doubleValue()));
            return;
        }
        throw new IllegalArgumentException("unsupported JSON number representation " + value.numberType());
    }

    private static void writeString(String value, StringBuilder output) {
        output.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\f' -> output.append("\\f");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> appendCharacter(character, output);
            }
        }
        output.append('"');
    }

    private static void appendCharacter(char character, StringBuilder output) {
        if (character < 0x20) {
            output.append("\\u00").append(HEX_DIGITS[character >> 4]).append(HEX_DIGITS[character & 0xF]);
            return;
        }
        output.append(character);
    }
}
