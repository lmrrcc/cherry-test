package com.cherrytest.common.container;

import com.cherrytest.common.rustcompat.AnyhowError;
import java.math.BigInteger;

/** Timeouts written as a number of seconds or a duration such as {@code 1800s}, {@code 30m} or {@code 2h}. */
public final class Durations {
    /** Seconds a container tool invocation may run when {@code timeout} is not configured. */
    public static final long DEFAULT_TIMEOUT_SECS = 1800;

    private Durations() {}

    /** Parses {@code 90}, {@code 90s}, {@code 30m} or {@code 2h} into seconds; zero and overflow are rejected. */
    public static long parse(String text) {
        String digits = text;
        long factor = 1;
        if (!text.isEmpty()) {
            switch (text.charAt(text.length() - 1)) {
                case 's' -> digits = text.substring(0, text.length() - 1);
                case 'm' -> {
                    digits = text.substring(0, text.length() - 1);
                    factor = 60;
                }
                case 'h' -> {
                    digits = text.substring(0, text.length() - 1);
                    factor = 3600;
                }
                default -> {}
            }
        }
        if (!digits.isEmpty() && digits.chars().allMatch(c -> c >= '0' && c <= '9')) {
            BigInteger seconds = new BigInteger(digits).multiply(BigInteger.valueOf(factor));
            if (seconds.signum() > 0 && seconds.bitLength() < 64) {
                return seconds.longValueExact();
            }
        }
        throw new AnyhowError("invalid duration `" + text
                + "`; use a positive number of seconds or a value such as 1800s, 30m or 2h");
    }
}
