package com.cherrytest.cli.coverage;

public record FileCoverage(String path, long linesHit, long linesFound, long functionsHit, long functionsFound) {
    public CoverageSummary summary() {
        return new CoverageSummary(linesHit, linesFound, functionsHit, functionsFound);
    }
}
