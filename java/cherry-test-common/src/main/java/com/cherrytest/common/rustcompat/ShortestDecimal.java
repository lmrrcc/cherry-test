package com.cherrytest.common.rustcompat;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;

record ShortestDecimal(String digits, int exponent) {
    private static final int MAXIMUM_SIGNIFICANT_DIGITS = 17;

    ShortestDecimal {
        if (digits.isEmpty() || digits.charAt(0) == '0') {
            throw new IllegalArgumentException("digits must be a non-empty decimal without leading zero: " + digits);
        }
    }

    static ShortestDecimal of(double value) {
        if (!(value > 0.0) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("value must be positive and finite: " + value);
        }
        BigDecimal exact = new BigDecimal(value);
        for (int precision = 1; precision <= MAXIMUM_SIGNIFICANT_DIGITS; precision++) {
            BigDecimal below = exact.round(new MathContext(precision, RoundingMode.FLOOR));
            BigDecimal above = exact.round(new MathContext(precision, RoundingMode.CEILING));
            boolean belowRoundTrips = below.doubleValue() == value;
            boolean aboveRoundTrips = above.doubleValue() == value;
            if (belowRoundTrips && aboveRoundTrips) {
                return fromDecimal(closer(exact, below, above));
            }
            if (belowRoundTrips) {
                return fromDecimal(below);
            }
            if (aboveRoundTrips) {
                return fromDecimal(above);
            }
        }
        throw new IllegalStateException("no round-tripping decimal within 17 significant digits for " + value);
    }

    BigDecimal toBigDecimal() {
        return new BigDecimal(new BigInteger(digits), -exponent);
    }

    private static BigDecimal closer(BigDecimal exact, BigDecimal below, BigDecimal above) {
        int comparison = exact.subtract(below).compareTo(above.subtract(exact));
        if (comparison != 0) {
            return comparison < 0 ? below : above;
        }
        return below.unscaledValue().testBit(0) ? above : below;
    }

    private static ShortestDecimal fromDecimal(BigDecimal decimal) {
        BigDecimal normalized = decimal.stripTrailingZeros();
        return new ShortestDecimal(normalized.unscaledValue().toString(), -normalized.scale());
    }
}
