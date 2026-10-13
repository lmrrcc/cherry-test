package com.cherrytest.core;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public final class SerdeYamlWriter {
    private SerdeYamlWriter() {}

    public sealed interface Node permits Scalar, Sequence, Mapping {}

    public record Scalar(@Nullable Object value) implements Node {}

    public record Sequence(List<Node> items) implements Node {
        public Sequence {
            items = List.copyOf(items);
        }
    }

    public record Mapping(List<Map.Entry<String, Node>> entries) implements Node {
        public Mapping {
            entries = List.copyOf(entries);
        }
    }

    public static String write(Mapping document) {
        StringBuilder out = new StringBuilder();
        if (document.entries().isEmpty()) {
            return "{}\n";
        }
        writeMapping(document, out, 0);
        return out.toString();
    }

    private static void writeMapping(Mapping mapping, StringBuilder out, int indent) {
        for (Map.Entry<String, Node> entry : mapping.entries()) {
            out.append(" ".repeat(indent)).append(scalarText(entry.getKey())).append(':');
            writeValue(entry.getValue(), out, indent, true);
        }
    }

    private static void writeValue(Node value, StringBuilder out, int indent, boolean inMapping) {
        switch (value) {
            case Scalar scalar -> out.append(' ').append(render(scalar.value())).append('\n');
            case Sequence sequence -> {
                if (sequence.items().isEmpty()) {
                    out.append(" []\n");
                    return;
                }
                out.append('\n');
                int itemIndent = inMapping ? indent : indent + 2;
                for (Node item : sequence.items()) {
                    out.append(" ".repeat(itemIndent)).append('-');
                    writeSequenceItem(item, out, itemIndent);
                }
            }
            case Mapping mapping -> {
                if (mapping.entries().isEmpty()) {
                    out.append(" {}\n");
                    return;
                }
                out.append('\n');
                writeMapping(mapping, out, indent + 2);
            }
        }
    }

    private static void writeSequenceItem(Node item, StringBuilder out, int indent) {
        switch (item) {
            case Scalar scalar -> out.append(' ').append(render(scalar.value())).append('\n');
            case Sequence sequence -> {
                if (sequence.items().isEmpty()) {
                    out.append(" []\n");
                    return;
                }
                out.append('\n');
                for (Node child : sequence.items()) {
                    out.append(" ".repeat(indent + 2)).append('-');
                    writeSequenceItem(child, out, indent + 2);
                }
            }
            case Mapping mapping -> {
                if (mapping.entries().isEmpty()) {
                    out.append(" {}\n");
                    return;
                }
                boolean first = true;
                for (Map.Entry<String, Node> entry : mapping.entries()) {
                    if (first) {
                        out.append(' ');
                        first = false;
                    } else {
                        out.append(" ".repeat(indent + 2));
                    }
                    out.append(scalarText(entry.getKey())).append(':');
                    writeValue(entry.getValue(), out, indent + 2, true);
                }
            }
        }
    }

    private static String render(@Nullable Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Boolean bool) {
            return bool ? "true" : "false";
        }
        if (value instanceof Number number) {
            return number.toString();
        }
        return scalarText(value.toString());
    }

    static String scalarText(String value) {
        if (value.isEmpty()) {
            return "''";
        }
        if (resolvesToNonString(value) || !plainAllowed(value)) {
            if (singleQuotedAllowed(value)) {
                return "'" + value.replace("'", "''") + "'";
            }
            return doubleQuoted(value);
        }
        return value;
    }

    private static boolean resolvesToNonString(String value) {
        return switch (value) {
            case "~",
                    "null",
                    "Null",
                    "NULL",
                    "true",
                    "True",
                    "TRUE",
                    "false",
                    "False",
                    "FALSE",
                    "y",
                    "Y",
                    "yes",
                    "Yes",
                    "YES",
                    "n",
                    "N",
                    "no",
                    "No",
                    "NO",
                    "on",
                    "On",
                    "ON",
                    "off",
                    "Off",
                    "OFF",
                    ".inf",
                    ".Inf",
                    ".INF",
                    "+.inf",
                    "+.Inf",
                    "+.INF",
                    "-.inf",
                    "-.Inf",
                    "-.INF",
                    ".nan",
                    ".NaN",
                    ".NAN" -> true;
            default -> looksNumeric(value);
        };
    }

    private static boolean looksNumeric(String value) {
        String body = value.startsWith("+") || value.startsWith("-") ? value.substring(1) : value;
        if (body.isEmpty()) {
            return false;
        }
        if (body.startsWith("0x") || body.startsWith("0o") || body.startsWith("0b")) {
            return body.length() > 2;
        }
        boolean digits = false;
        boolean dot = false;
        boolean exponent = false;
        for (int index = 0; index < body.length(); index++) {
            char character = body.charAt(index);
            if (character >= '0' && character <= '9') {
                digits = true;
            } else if (character == '.' && !dot && !exponent) {
                dot = true;
            } else if ((character == 'e' || character == 'E') && digits && !exponent) {
                exponent = true;
                if (index + 1 < body.length() && (body.charAt(index + 1) == '+' || body.charAt(index + 1) == '-')) {
                    index++;
                }
            } else if (character != '_') {
                return false;
            }
        }
        return digits;
    }

    private static boolean plainAllowed(String value) {
        char first = value.charAt(0);
        if ("-?:,[]{}#&*!|>'\"%@`".indexOf(first) >= 0) {
            if ((first == '-' || first == '?' || first == ':') && value.length() > 1 && !isBlank(value.charAt(1))) {
                return checkInner(value);
            }
            return false;
        }
        if (isBlank(first) || isBlank(value.charAt(value.length() - 1))) {
            return false;
        }
        return checkInner(value);
    }

    private static boolean checkInner(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\n' || character == '\r' || character < 0x20 || character == 0x7f) {
                return false;
            }
            if (character == ':' && (index + 1 == value.length() || isBlank(value.charAt(index + 1)))) {
                return false;
            }
            if (character == '#' && index > 0 && isBlank(value.charAt(index - 1))) {
                return false;
            }
        }
        return true;
    }

    private static boolean singleQuotedAllowed(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x20 || character == 0x7f) {
                return false;
            }
        }
        return true;
    }

    private static boolean isBlank(char character) {
        return character == ' ' || character == '\t';
    }

    private static String doubleQuoted(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\t' -> out.append("\\t");
                case '\r' -> out.append("\\r");
                default -> {
                    if (character < 0x20 || character == 0x7f) {
                        out.append(String.format(java.util.Locale.ROOT, "\\x%02X", (int) character));
                    } else {
                        out.append(character);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
