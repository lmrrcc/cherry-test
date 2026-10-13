package com.cherrytest.cli.report;

import com.cherrytest.cli.coverage.ChangedFileCoverage;
import java.util.List;

public record ProjectReport(
        String projectId,
        String packageName,
        boolean testsPassed,
        PackageCoverage coverage,
        CoverageTotals diffCoverage,
        List<ChangedFileCoverage> changedFiles) {
    public ProjectReport {
        changedFiles = List.copyOf(changedFiles);
    }
}
