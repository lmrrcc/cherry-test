package com.cherrytest.cli.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.cherrytest.cli.coverage.Coverage;
import com.cherrytest.cli.coverage.CoverageSummary;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReportOutputTest {
    private static CoverageSummary sample_summary(
            long linesHit, long linesFound, long functionsHit, long functionsFound) {
        return new CoverageSummary(linesHit, linesFound, functionsHit, functionsFound);
    }

    @Test
    void format_lines_cell_uses_diff_coverage_semantics() {
        assertThat(CoverageCell.formatLinesCell(sample_summary(12, 45, 2, 8), true))
                .isEqualTo("12/45·12c·33u·26.7%");
    }

    @Test
    void aggregate_diff_pct_from_summed_hits() {
        CoverageSummary merged =
                Coverage.mergeSummaries(List.of(sample_summary(10, 20, 0, 0), sample_summary(5, 30, 0, 0)));
        assertThat(merged.linesHit()).isEqualTo(15);
        assertThat(merged.linesFound()).isEqualTo(50);
        assertThat(Math.abs(merged.linesPercent() - 30.0)).isLessThan(0.1);
    }

    @Test
    void format_pct_shows_dash_when_missing() {
        assertThat(ReportOutput.formatPct(null)).isEqualTo("-");
        assertThat(ReportOutput.formatFnPct(null)).isEqualTo("-");
    }

    @Test
    void format_pct_shows_percentage_when_present() {
        CoverageSummary summary = sample_summary(65, 100, 5, 8);
        assertThat(ReportOutput.formatPct(summary)).isEqualTo("65.0%");
        assertThat(ReportOutput.formatFnPct(summary)).isEqualTo("62.5%");
    }

    @Test
    void status_line_truncates_at_limit() {
        Snapshots snapshot = new Snapshots();
        snapshot.runId = "20260523-143022-a1b2c3";
        snapshot.packagesPassed = 2;
        snapshot.packagesTotal = 2;
        snapshot.integrationSkipped = 1;
        snapshot.totals = new CoverageTotals(
                sample_summary(40, 100, 4, 10), sample_summary(0, 10, 0, 5), sample_summary(30, 100, 3, 10));
        snapshot.diffCoverage = Snapshots.merged(sample_summary(5, 10, 1, 2));
        snapshot.projects.add(new ProjectReport(
                "mini",
                "mini-rs-api",
                true,
                new PackageCoverage(
                        sample_summary(65, 100, 5, 8), sample_summary(0, 12, 0, 8), sample_summary(30, 100, 3, 10)),
                CoverageTotals.EMPTY,
                List.of()));
        snapshot.reportPath = Path.of("target/cherry-test/runs/20260523-143022-a1b2c3/reports/index.html");
        String line = ReportModel.formatStatusLine(snapshot.build(), TerminalReport.TERMINAL_WIDTH);
        assertThat(TerminalReport.visibleWidth(line)).isEqualTo(TerminalReport.TERMINAL_WIDTH);
        assertThat(line).contains("cherry-test OK");
    }
}
