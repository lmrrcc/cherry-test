package com.cherrytest.cli;

import com.cherrytest.common.rustcompat.RustText;
import java.util.List;

final class Banner {
    private static final int MARK_COLUMNS = 17;
    private static final String FRUIT = "\u001b[38;2;226;59;50m";
    private static final String STEM = "\u001b[38;2;217;180;143m";
    private static final String PAY = "\u001b[38;2;194;181;168m";
    private static final String ACCENT = "\u001b[38;2;255;155;146m";
    private static final String RESET = "\u001b[0m";
    private static final String PHRASE = "Multiple mono-repositories. Single test workflow.";

    private Banner() {}

    static String text() {
        try (java.io.InputStream stream = Banner.class.getResourceAsStream("banner.txt")) {
            if (stream == null) {
                throw new IllegalStateException("missing banner resource");
            }
            return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    static String render(boolean color) {
        StringBuilder out = new StringBuilder();
        List<String> lines = RustText.lines(text());
        for (int index = 0; index < lines.size(); index++) {
            if (index > 0) {
                out.append('\n');
            }
            out.append(color ? colorLine(lines.get(index)) : lines.get(index));
        }
        return out.toString();
    }

    private static String colorLine(String line) {
        int at = line.indexOf(PHRASE);
        if (at >= 0) {
            return line.substring(0, at) + PAY + "Multiple mono-repositories. " + RESET + ACCENT
                    + "Single test workflow." + RESET;
        }
        StringBuilder mark = new StringBuilder();
        StringBuilder word = new StringBuilder();
        int index = 0;
        int column = 0;
        while (index < line.length()) {
            int character = line.codePointAt(index);
            index += Character.charCount(character);
            if (column < MARK_COLUMNS) {
                mark.appendCodePoint(character);
            } else {
                word.appendCodePoint(character);
            }
            column++;
        }
        StringBuilder out = new StringBuilder(colorMark(mark.toString()));
        if (!word.isEmpty()) {
            out.append("\u001b[1m").append(word).append(RESET);
        }
        return out.toString();
    }

    private static String colorMark(String mark) {
        StringBuilder out = new StringBuilder();
        char kind = ' ';
        StringBuilder buffer = new StringBuilder();
        int index = 0;
        while (index < mark.length()) {
            int character = mark.codePointAt(index);
            index += Character.charCount(character);
            char next = switch (character) {
                case '▀', '▄', '▌', '▐', '▒', '░' -> 'f';
                case '▓', '╱', '╲' -> 's';
                default -> 'g';
            };
            if (next != kind) {
                pushMarkRun(out, kind, buffer.toString());
                buffer.setLength(0);
                kind = next;
            }
            buffer.appendCodePoint(character);
        }
        pushMarkRun(out, kind, buffer.toString());
        return out.toString();
    }

    private static void pushMarkRun(StringBuilder out, char kind, String buffer) {
        if (buffer.isEmpty()) {
            return;
        }
        String paint = switch (kind) {
            case 'f' -> FRUIT;
            case 's' -> STEM;
            default -> "";
        };
        out.append(paint).append(buffer);
        if (!paint.isEmpty()) {
            out.append(RESET);
        }
    }
}
