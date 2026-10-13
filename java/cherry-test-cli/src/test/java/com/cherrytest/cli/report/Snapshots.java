package com.cherrytest.cli.report;

import com.cherrytest.cli.coverage.CoverageSummary;
import com.cherrytest.cli.crap.CrapReport;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class Snapshots {
    String runId = "run";
    boolean overallOk = true;
    long packagesPassed;
    long packagesTotal;
    long packagesFailed;
    long integrationRun;
    long integrationSkipped;
    long integrationFailed;
    boolean coverageEnabled = true;
    CoverageTotals totals = CoverageTotals.EMPTY;
    CoverageTotals diffCoverage = CoverageTotals.EMPTY;
    List<ProjectReport> projects = new ArrayList<>();
    List<ProjectDiffSection> projectDiffSections = new ArrayList<>();
    List<IntegrationRow> integrationRows = new ArrayList<>();
    TestsReport testsReport = TestsReport.EMPTY;
    CrapReport crap = CrapReport.DISABLED;
    Path reportPath = Path.of("index.html");

    static CoverageSummary summary(long linesHit, long linesFound, long functionsHit, long functionsFound) {
        return new CoverageSummary(linesHit, linesFound, functionsHit, functionsFound);
    }

    static CoverageTotals merged(CoverageSummary merged) {
        return new CoverageTotals(null, null, merged);
    }

    ReportSnapshot build() {
        return new ReportSnapshot(
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
                reportPath);
    }
}
