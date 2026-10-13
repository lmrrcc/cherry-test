package com.cherrytest.common.rustcompat;

public final class RustDebug {
    private RustDebug() {}

    public static String string(String value) {
        StringBuilder rendered = new StringBuilder("\"");
        int index = 0;
        while (index < value.length()) {
            int codePoint = value.codePointAt(index);
            index += Character.charCount(codePoint);
            switch (codePoint) {
                case '"' -> rendered.append("\\\"");
                case '\\' -> rendered.append("\\\\");
                case '\n' -> rendered.append("\\n");
                case '\r' -> rendered.append("\\r");
                case '\t' -> rendered.append("\\t");
                case 0 -> rendered.append("\\0");
                default -> appendCodePoint(rendered, codePoint);
            }
        }
        return rendered.append('"').toString();
    }

    private static void appendCodePoint(StringBuilder rendered, int codePoint) {
        if (isPrintable(codePoint)) {
            rendered.appendCodePoint(codePoint);
            return;
        }
        rendered.append("\\u{");
        rendered.append(Integer.toHexString(codePoint)).append('}');
    }

    private static boolean isPrintable(int codePoint) {
        if (codePoint < 0x20 || codePoint == 0x7F) {
            return false;
        }
        int type = Character.getType(codePoint);
        return type != Character.CONTROL
                && type != Character.FORMAT
                && type != Character.UNASSIGNED
                && type != Character.LINE_SEPARATOR
                && type != Character.PARAGRAPH_SEPARATOR
                && type != Character.PRIVATE_USE
                && type != Character.SURROGATE
                && !(type == Character.SPACE_SEPARATOR && codePoint != ' ');
    }
}
