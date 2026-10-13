package com.cherrytest.cli.crap;

public record CrapTotals(
        long functions,
        long complexity,
        double totalCrap,
        double meanCrap,
        double medianCrap,
        double worstCrap,
        long crappyFunctions,
        double crappyPercent,
        double crapLoad) {
    public static final CrapTotals ZERO = new CrapTotals(0, 0, 0.0, 0.0, 0.0, 0.0, 0, 0.0, 0.0);
}
