package com.cherrytest.cli.report;

import java.util.List;

public record FileDiffSection(
        String path,
        long adds,
        long dels,
        double coveragePct,
        double uncoveredPct,
        boolean hasUncovered,
        long failLines,
        boolean unparsedDiff,
        List<DiffHunk> hunks) {
    public FileDiffSection {
        hunks = List.copyOf(hunks);
    }
}
