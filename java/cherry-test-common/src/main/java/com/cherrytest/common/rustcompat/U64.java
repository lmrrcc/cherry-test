package com.cherrytest.common.rustcompat;

public final class U64 {
    public static final long MAX = -1L;
    private static final double TWO_POW_64 = 0x1p64;
    private static final double TWO_POW_63 = 0x1p63;

    private U64() {}

    public static long fromF64(double value) {
        if (Double.isNaN(value) || value <= 0.0) {
            return 0L;
        }
        if (value >= TWO_POW_64) {
            return MAX;
        }
        if (value >= TWO_POW_63) {
            return ((long) (value - TWO_POW_63)) ^ Long.MIN_VALUE;
        }
        return (long) value;
    }

    public static double toF64(long value) {
        if (value >= 0) {
            return (double) value;
        }
        return ((double) (value >>> 1 | (value & 1L))) * 2.0;
    }

    public static long saturatingAdd(long left, long right) {
        long sum = left + right;
        return Long.compareUnsigned(sum, left) < 0 ? MAX : sum;
    }

    public static long saturatingSub(long left, long right) {
        return Long.compareUnsigned(left, right) < 0 ? 0L : left - right;
    }

    public static long checkedAdd(long left, long right, String context) {
        long sum = left + right;
        if (Long.compareUnsigned(sum, left) < 0) {
            throw new AnyhowError(context);
        }
        return sum;
    }

    public static long div(long left, long right) {
        return Long.divideUnsigned(left, right);
    }

    public static int compare(long left, long right) {
        return Long.compareUnsigned(left, right);
    }

    public static String toString(long value) {
        return Long.toUnsignedString(value);
    }
}
