package com.cherrytest.cli.report;

final class RustChar {
    private RustChar() {}

    static boolean isAlphabetic(int codePoint) {
        return Character.isAlphabetic(codePoint);
    }

    static boolean isNumeric(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.DECIMAL_DIGIT_NUMBER
                || type == Character.LETTER_NUMBER
                || type == Character.OTHER_NUMBER;
    }

    static boolean isAlphanumeric(int codePoint) {
        return isAlphabetic(codePoint) || isNumeric(codePoint);
    }

    static boolean isUppercase(int codePoint) {
        return Character.isUpperCase(codePoint);
    }

    static boolean isAsciiAlphanumeric(int codePoint) {
        return (codePoint >= 'a' && codePoint <= 'z')
                || (codePoint >= 'A' && codePoint <= 'Z')
                || (codePoint >= '0' && codePoint <= '9');
    }

    static boolean isAsciiDigit(int codePoint) {
        return codePoint >= '0' && codePoint <= '9';
    }

    static boolean isAsciiHexdigit(int codePoint) {
        return isAsciiDigit(codePoint)
                || (codePoint >= 'a' && codePoint <= 'f')
                || (codePoint >= 'A' && codePoint <= 'F');
    }
}
