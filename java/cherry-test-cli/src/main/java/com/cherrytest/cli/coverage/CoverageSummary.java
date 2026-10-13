package com.cherrytest.cli.coverage;

public record CoverageSummary(long linesHit, long linesFound, long functionsHit, long functionsFound) {
    public static final CoverageSummary ZERO = new CoverageSummary(0, 0, 0, 0);

    public CoverageSummary plus(CoverageSummary other) {
        return new CoverageSummary(
                linesHit + other.linesHit,
                linesFound + other.linesFound,
                functionsHit + other.functionsHit,
                functionsFound + other.functionsFound);
    }

    public double linesPercent() {
        return percent(linesHit, linesFound);
    }

    public double functionsPercent() {
        return percent(functionsHit, functionsFound);
    }

    public long linesUncovered() {
        return linesFound < linesHit ? 0 : linesFound - linesHit;
    }

    static double percent(long hit, long found) {
        if (found == 0) {
            return 0.0;
        }
        return ((double) hit / (double) found) * 100.0;
    }
}
