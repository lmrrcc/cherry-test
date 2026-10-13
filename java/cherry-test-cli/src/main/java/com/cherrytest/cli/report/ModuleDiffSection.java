package com.cherrytest.cli.report;

import java.util.List;

public record ModuleDiffSection(
        String path,
        long fileCount,
        double coveragePct,
        double uncoveredPct,
        boolean hasUncovered,
        List<FileDiffSection> files) {
    public ModuleDiffSection {
        files = List.copyOf(files);
    }
}
