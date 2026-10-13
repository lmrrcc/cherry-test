package com.cherrytest.common.rustcompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class RustText {
    private RustText() {}

    public static boolean isWhitespace(int codePoint) {
        return (codePoint >= 0x09 && codePoint <= 0x0D)
                || codePoint == 0x20
                || codePoint == 0x85
                || codePoint == 0xA0
                || codePoint == 0x1680
                || (codePoint >= 0x2000 && codePoint <= 0x200A)
                || codePoint == 0x2028
                || codePoint == 0x2029
                || codePoint == 0x202F
                || codePoint == 0x205F
                || codePoint == 0x3000;
    }

    public static String trim(String text) {
        int start = firstNonWhitespace(text);
        return text.substring(start, endOfLastNonWhitespace(text, start));
    }

    public static String trimStart(String text) {
        return text.substring(firstNonWhitespace(text));
    }

    public static List<String> lines(String text) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int newline = text.indexOf('\n', start);
            int lineEnd = newline < 0 ? text.length() : newline;
            int contentEnd = newline > start && text.charAt(newline - 1) == '\r' ? newline - 1 : lineEnd;
            lines.add(text.substring(start, contentEnd));
            start = lineEnd + 1;
        }
        return List.copyOf(lines);
    }

    public static List<String> splitWhitespace(String text) {
        List<String> words = new ArrayList<>();
        int index = 0;
        while (index < text.length()) {
            int start = index;
            while (start < text.length() && isWhitespace(text.charAt(start))) {
                start++;
            }
            int end = start;
            while (end < text.length() && !isWhitespace(text.charAt(end))) {
                end++;
            }
            if (end > start) {
                words.add(text.substring(start, end));
            }
            index = end;
        }
        return List.copyOf(words);
    }

    public static int charCount(String text) {
        return text.codePointCount(0, text.length());
    }

    public static int utf8Length(String text) {
        return text.codePoints().map(RustText::utf8Width).sum();
    }

    public static String toUppercase(String text) {
        return text.toUpperCase(Locale.ROOT);
    }

    public static String toLowercase(String text) {
        return text.toLowerCase(Locale.ROOT);
    }

    public static String toAsciiLowercase(String text) {
        StringBuilder lowered = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            lowered.append(character >= 'A' && character <= 'Z' ? (char) (character + ('a' - 'A')) : character);
        }
        return lowered.toString();
    }

    private static int firstNonWhitespace(String text) {
        int index = 0;
        while (index < text.length() && isWhitespace(text.charAt(index))) {
            index++;
        }
        return index;
    }

    private static int endOfLastNonWhitespace(String text, int floor) {
        int end = text.length();
        while (end > floor && isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        return end;
    }

    private static int utf8Width(int codePoint) {
        if (codePoint < 0x80) {
            return 1;
        }
        if (codePoint < 0x800) {
            return 2;
        }
        return codePoint < 0x10000 ? 3 : 4;
    }
}
