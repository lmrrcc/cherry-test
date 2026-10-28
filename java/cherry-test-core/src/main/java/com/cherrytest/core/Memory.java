package com.cherrytest.core;

import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.common.rustcompat.RustText;

/** Memory amounts in bytes, written as bytes or with a binary unit such as {@code 512m} or {@code 8g}. */
public final class Memory {
    private static final double LIMIT = 0x1p63;

    private Memory() {}

    /** Parses {@code 8g}, {@code 1.5G}, {@code 512mi}, {@code 512MiB}, {@code 1024k} or a byte count; units are powers of 1024. */
    public static long parse(String value) {
        String error = "invalid memory `" + value + "`; use bytes or a unit such as 512m or 8g";
        String text = RustText.toAsciiLowercase(RustText.trim(value));
        int digitsEnd = 0;
        while (digitsEnd < text.length() && isNumberCharacter(text.charAt(digitsEnd))) {
            digitsEnd++;
        }
        String number = text.substring(0, digitsEnd);
        String unit = RustText.trim(text.substring(digitsEnd));
        if (unit.endsWith("b")) {
            unit = unit.substring(0, unit.length() - 1);
        }
        if (unit.endsWith("i")) {
            unit = unit.substring(0, unit.length() - 1);
        }
        long multiplier = switch (unit) {
            case "" -> 1L;
            case "k" -> 1L << 10;
            case "m" -> 1L << 20;
            case "g" -> 1L << 30;
            case "t" -> 1L << 40;
            default -> throw new AnyhowError(error);
        };
        if (number.isEmpty() || number.startsWith(".") || number.endsWith(".")) {
            throw new AnyhowError(error);
        }
        double amount;
        try {
            amount = Double.parseDouble(number);
        } catch (NumberFormatException invalid) {
            throw new AnyhowError(error, invalid);
        }
        double bytes = Math.floor(amount * multiplier);
        if (!Double.isFinite(bytes) || bytes < 1.0 || bytes >= LIMIT) {
            throw new AnyhowError(error);
        }
        return (long) bytes;
    }

    private static boolean isNumberCharacter(char character) {
        return (character >= '0' && character <= '9') || character == '.';
    }
}
