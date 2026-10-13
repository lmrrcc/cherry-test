package com.cherrytest.cli.crap;

import java.util.List;

public record CrapFunction(
        String path,
        String name,
        long line,
        long complexity,
        long coveredLines,
        long measuredLines,
        double coverage,
        double score,
        double crapLoad,
        List<CrapSourceLine> source) {
    public CrapFunction {
        source = List.copyOf(source);
    }
}
