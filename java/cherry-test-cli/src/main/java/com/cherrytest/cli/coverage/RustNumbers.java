package com.cherrytest.cli.coverage;

import java.math.BigInteger;
import org.jspecify.annotations.Nullable;

public final class RustNumbers {
    private static final BigInteger U64_MAX = new BigInteger("18446744073709551615");

    private RustNumbers() {}

    public static @Nullable Long parseU64(String text) {
        BigInteger value = parseUnsigned(text);
        if (value == null || value.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
            return value == null || value.compareTo(U64_MAX) > 0 ? null : Long.MAX_VALUE;
        }
        return value.longValue();
    }

    public static @Nullable Long parseU32(String text) {
        BigInteger value = parseUnsigned(text);
        if (value == null || value.compareTo(BigInteger.valueOf(0xFFFFFFFFL)) > 0) {
            return null;
        }
        return value.longValue();
    }

    public static @Nullable BigInteger parseUnsigned(String text) {
        String digits = text.startsWith("+") ? text.substring(1) : text;
        if (digits.isEmpty()) {
            return null;
        }
        for (int index = 0; index < digits.length(); index++) {
            char character = digits.charAt(index);
            if (character < '0' || character > '9') {
                return null;
            }
        }
        return new BigInteger(digits);
    }
}
