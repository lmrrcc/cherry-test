package com.cherrytest.cli.report;

import com.cherrytest.cli.crap.CrapReport;
import java.nio.file.Path;
import java.util.List;

public record ReportSnapshot(
        String runId,
        boolean overallOk,
        long packagesPassed,
        long packagesTotal,
        long packagesFailed,
        long integrationRun,
        long integrationSkipped,
        long integrationFailed,
        boolean coverageEnabled,
        CoverageTotals totals,
        CoverageTotals diffCoverage,
        List<ProjectReport> projects,
        List<ProjectDiffSection> projectDiffSections,
        List<IntegrationRow> integrationRows,
        TestsReport testsReport,
        CrapReport crap,
        Path reportPath) {
    public ReportSnapshot {
        projects = List.copyOf(projects);
        projectDiffSections = List.copyOf(projectDiffSections);
        integrationRows = List.copyOf(integrationRows);
    }
}
