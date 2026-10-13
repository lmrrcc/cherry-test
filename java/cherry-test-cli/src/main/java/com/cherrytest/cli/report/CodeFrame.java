package com.cherrytest.cli.report;

import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.common.rustcompat.RustParse;
import com.cherrytest.common.rustcompat.RustText;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

final class CodeFrame {
    private CodeFrame() {}

    record FrameLine(@Nullable Long number, String text, boolean marked) {}

    sealed interface ReasonPart permits TextPart, FramePart {}

    record TextPart(String text) implements ReasonPart {}

    record FramePart(List<FrameLine> lines, @Nullable String source) implements ReasonPart {
        FramePart {
            lines = List.copyOf(lines);
        }
    }

    static List<ReasonPart> splitReason(String reason) {
        List<String> lines = RustText.lines(reason);
        List<ReasonPart> parts = new ArrayList<>();
        List<String> text = new ArrayList<>();
        List<FrameLine> frame = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            FrameLine frameLine = parseFrameLine(line);
            if (frameLine != null) {
                if (frame.isEmpty()) {
                    pushText(parts, text);
                }
                frame.add(frameLine);
                continue;
            }
            if (!frame.isEmpty()) {
                String source = null;
                for (int next = index; next < lines.size(); next++) {
                    source = frameSource(lines.get(next));
                    if (source != null) {
                        break;
                    }
                }
                parts.add(new FramePart(frame, source));
                frame = new ArrayList<>();
            }
            text.add(line);
        }
        if (!frame.isEmpty()) {
            parts.add(new FramePart(frame, null));
        }
        pushText(parts, text);
        return parts;
    }

    private static void pushText(List<ReasonPart> parts, List<String> text) {
        String joined = String.join("\n", text);
        text.clear();
        int start = 0;
        int end = joined.length();
        while (start < end && joined.charAt(start) == '\n') {
            start++;
        }
        while (end > start && joined.charAt(end - 1) == '\n') {
            end--;
        }
        String trimmed = joined.substring(start, end);
        if (!RustText.trim(trimmed).isEmpty()) {
            parts.add(new TextPart(trimmed));
        }
    }

    private static @Nullable FrameLine parseFrameLine(String line) {
        String trimmed = RustText.trimStart(line);
        boolean marked = trimmed.startsWith(">");
        String rest = marked ? RustText.trimStart(trimmed.substring(1)) : trimmed;
        int bar = rest.indexOf('|');
        if (bar < 0) {
            return null;
        }
        String gutter = RustText.trim(rest.substring(0, bar));
        String code = rest.substring(bar + 1);
        if (code.startsWith(" ")) {
            code = code.substring(1);
        }
        if (gutter.isEmpty()) {
            String pointer = code;
            boolean isPointer = !marked
                    && pointer.contains("^")
                    && pointer.codePoints().allMatch(character -> character == ' ' || character == '^');
            return isPointer ? new FrameLine(null, pointer, false) : null;
        }
        long number;
        try {
            number = RustParse.u32(gutter);
        } catch (AnyhowError error) {
            return null;
        }
        return new FrameLine(number, code, marked);
    }

    private static @Nullable String frameSource(String line) {
        String trimmed = RustText.trimStart(line);
        if (!trimmed.startsWith("at ")) {
            return null;
        }
        String location = trimmed.substring(3);
        int paren = location.lastIndexOf('(');
        if (paren >= 0) {
            String inner = location.substring(paren + 1);
            int end = inner.length();
            while (end > 0 && inner.charAt(end - 1) == ')') {
                end--;
            }
            location = inner.substring(0, end);
        }
        int last = location.lastIndexOf(':');
        if (last < 0) {
            return null;
        }
        String column = location.substring(last + 1);
        String beforeColumn = location.substring(0, last);
        int middle = beforeColumn.lastIndexOf(':');
        if (middle < 0) {
            return null;
        }
        String row = beforeColumn.substring(middle + 1);
        String path = beforeColumn.substring(0, middle);
        try {
            RustParse.u32(column);
            RustParse.u32(row);
        } catch (AnyhowError error) {
            return null;
        }
        return path;
    }
}
