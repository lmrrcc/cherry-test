package com.cherrytest.common.rustcompat;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class RustFloatFormat {
    private RustFloatFormat() {}

    public static String display(double value) {
        if (Double.isNaN(value)) {
            return "NaN";
        }
        String sign = signOf(value);
        if (Double.isInfinite(value)) {
            return sign + "inf";
        }
        if (value == 0.0) {
            return sign + "0";
        }
        return sign + ShortestDecimal.of(Math.abs(value)).toBigDecimal().toPlainString();
    }

    public static String fixed(double value, int precision) {
        if (precision < 0) {
            throw new IllegalArgumentException("precision must not be negative: " + precision);
        }
        if (Double.isNaN(value)) {
            return "NaN";
        }
        String sign = signOf(value);
        if (Double.isInfinite(value)) {
            return sign + "inf";
        }
        return sign
                + new BigDecimal(Math.abs(value))
                        .setScale(precision, RoundingMode.HALF_EVEN)
                        .toPlainString();
    }

    static String signOf(double value) {
        return Double.doubleToRawLongBits(value) < 0 ? "-" : "";
    }
}
