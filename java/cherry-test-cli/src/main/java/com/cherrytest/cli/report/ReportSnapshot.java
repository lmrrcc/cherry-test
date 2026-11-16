package com.cherrytest.cli.report;

import com.cherrytest.cli.crap.CrapReport;
import com.cherrytest.common.adapter.IsolationClass;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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
        List<Map.Entry<IsolationClass, Long>> isolation,
        Path reportPath) {
    public ReportSnapshot {
        projects = List.copyOf(projects);
        projectDiffSections = List.copyOf(projectDiffSections);
        integrationRows = List.copyOf(integrationRows);
        isolation = List.copyOf(isolation);
    }

    /** A snapshot of a run without isolation. */
    public ReportSnapshot(
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
        this(
                runId,
                overallOk,
                packagesPassed,
                packagesTotal,
                packagesFailed,
                integrationRun,
                integrationSkipped,
                integrationFailed,
                coverageEnabled,
                totals,
                diffCoverage,
                projects,
                projectDiffSections,
                integrationRows,
                testsReport,
                crap,
                List.of(),
                reportPath);
    }
}
