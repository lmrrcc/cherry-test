package com.cherrytest.core;

/** How many jobs run at once: a fixed number, or {@code auto} to follow the CPU budget. */
public record Jobs(boolean auto, long count) {
    public static final Jobs AUTO = new Jobs(true, 0);
    public static final String EXPECTED = "expected a positive number or `auto`";

    public static Jobs count(long count) {
        return new Jobs(false, count);
    }

    /** Parses {@code auto} or a positive 32-bit count, like Rust's {@code Jobs::from_str}. */
    public static Jobs parse(String value) {
        if (value.equals("auto")) {
            return AUTO;
        }
        if (!value.isEmpty() && value.chars().allMatch(character -> character >= '0' && character <= '9')) {
            try {
                long count = Long.parseLong(value);
                if (count > 0 && count <= 0xFFFFFFFFL) {
                    return count(count);
                }
            } catch (NumberFormatException tooLarge) {
                throw new IllegalArgumentException(EXPECTED, tooLarge);
            }
        }
        throw new IllegalArgumentException(EXPECTED);
    }

    @Override
    public String toString() {
        return auto ? "auto" : Long.toString(count);
    }
}
