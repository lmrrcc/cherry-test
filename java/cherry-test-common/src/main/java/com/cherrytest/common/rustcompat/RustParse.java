package com.cherrytest.common.rustcompat;

import java.math.BigDecimal;
import java.math.BigInteger;

public final class RustParse {
    private static final BigInteger U64_MAX = new BigInteger("18446744073709551615");
    private static final BigInteger U32_MAX = BigInteger.valueOf(0xFFFFFFFFL);
    private static final BigInteger U16_MAX = BigInteger.valueOf(0xFFFFL);
    private static final BigInteger U8_MAX = BigInteger.valueOf(0xFFL);

    private RustParse() {}

    public static long u64(String text) {
        return unsigned(text, U64_MAX).longValue();
    }

    public static long u32(String text) {
        return unsigned(text, U32_MAX).longValue();
    }

    public static int u16(String text) {
        return unsigned(text, U16_MAX).intValue();
    }

    public static int u8(String text) {
        return unsigned(text, U8_MAX).intValue();
    }

    public static long usize(String text) {
        return u64(text);
    }

    private static BigInteger unsigned(String text, BigInteger maximum) {
        if (text.isEmpty()) {
            throw new AnyhowError("cannot parse integer from empty string");
        }
        String digits = text.startsWith("+") ? text.substring(1) : text;
        if (digits.isEmpty()) {
            throw new AnyhowError("invalid digit found in string");
        }
        for (int index = 0; index < digits.length(); index++) {
            char character = digits.charAt(index);
            if (character < '0' || character > '9') {
                throw new AnyhowError("invalid digit found in string");
            }
        }
        BigInteger value = new BigInteger(digits);
        if (value.compareTo(maximum) > 0) {
            throw new AnyhowError("number too large to fit in target type");
        }
        return value;
    }

    public static double f64(String text) {
        String body = text;
        boolean negative = false;
        if (body.startsWith("+") || body.startsWith("-")) {
            negative = body.charAt(0) == '-';
            body = body.substring(1);
        }
        String lowered = RustText.toAsciiLowercase(body);
        if (lowered.equals("inf") || lowered.equals("infinity")) {
            return negative ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        }
        if (lowered.equals("nan")) {
            return Double.NaN;
        }
        if (!isDecimalLiteral(body)) {
            throw new AnyhowError("invalid float literal");
        }
        double magnitude = new BigDecimal(
                        body.startsWith(".") ? "0" + body : body.endsWith(".") ? body + "0" : fixExponent(body))
                .doubleValue();
        return negative ? -magnitude : magnitude;
    }

    private static String fixExponent(String body) {
        int exponent = Math.max(body.indexOf('e'), body.indexOf('E'));
        if (exponent > 0 && body.charAt(exponent - 1) == '.') {
            return body.substring(0, exponent - 1) + body.substring(exponent);
        }
        return body;
    }

    private static boolean isDecimalLiteral(String body) {
        int index = 0;
        int integerDigits = 0;
        while (index < body.length() && Character.isDigit(body.charAt(index)) && body.charAt(index) < 0x80) {
            index++;
            integerDigits++;
        }
        int fractionDigits = 0;
        if (index < body.length() && body.charAt(index) == '.') {
            index++;
            while (index < body.length() && body.charAt(index) >= '0' && body.charAt(index) <= '9') {
                index++;
                fractionDigits++;
            }
        }
        if (integerDigits + fractionDigits == 0) {
            return false;
        }
        if (index < body.length() && (body.charAt(index) == 'e' || body.charAt(index) == 'E')) {
            index++;
            if (index < body.length() && (body.charAt(index) == '+' || body.charAt(index) == '-')) {
                index++;
            }
            int exponentDigits = 0;
            while (index < body.length() && body.charAt(index) >= '0' && body.charAt(index) <= '9') {
                index++;
                exponentDigits++;
            }
            if (exponentDigits == 0) {
                return false;
            }
        }
        return index == body.length();
    }
}
