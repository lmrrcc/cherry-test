package com.cherrytest.cli.report;

import java.util.List;

public record ProjectDiffSection(
        String projectId,
        boolean testsPassed,
        long fileCount,
        double coveragePct,
        double uncoveredPct,
        List<ModuleDiffSection> modules) {
    public ProjectDiffSection {
        modules = List.copyOf(modules);
    }
}
