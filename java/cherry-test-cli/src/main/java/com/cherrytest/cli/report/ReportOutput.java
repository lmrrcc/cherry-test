package com.cherrytest.cli.report;

import com.cherrytest.cli.TestKind;
import com.cherrytest.cli.coverage.Coverage;
import com.cherrytest.cli.coverage.CoverageIgnore;
import com.cherrytest.cli.coverage.CoveragePaths;
import com.cherrytest.cli.coverage.CoverageSummary;
import com.cherrytest.cli.nativeos.Terminal;
import com.cherrytest.common.adapter.TestResult;
import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.common.rustcompat.RustFloatFormat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

public final class ReportOutput {
    private ReportOutput() {}

    static String treeDepthOpen(int depth) {
        return depth <= 2 ? " open" : "";
    }

    public static PackageCoverage packageCoverageFromPaths(
            CoveragePaths paths, String packageName, CoverageIgnore ignore) {
        Path unit = paths.kindLcovFile(packageName, TestKind.UNIT);
        Path integration = paths.kindLcovFile(packageName, TestKind.INTEGRATION);
        Path merged = paths.lcovFile(packageName);
        return new PackageCoverage(
                optionalSummary(unit, ignore),
                optionalSummary(integration, ignore),
                Files.exists(merged) ? summaryOrDefault(merged, ignore) : CoverageSummary.ZERO);
    }

    private static @Nullable CoverageSummary optionalSummary(Path path, CoverageIgnore ignore) {
        if (!Files.exists(path)) {
            return null;
        }
        return summaryOrDefault(path, ignore);
    }

    private static CoverageSummary summaryOrDefault(Path path, CoverageIgnore ignore) {
        try {
            return Coverage.summaryForLcov(path, ignore);
        } catch (AnyhowError error) {
            return CoverageSummary.ZERO;
        }
    }

    public static String formatPct(@Nullable CoverageSummary summary) {
        return summary == null ? "-" : RustFloatFormat.fixed(summary.linesPercent(), 1) + "%";
    }

    public static String formatFnPct(@Nullable CoverageSummary summary) {
        return summary == null ? "-" : RustFloatFormat.fixed(summary.functionsPercent(), 1) + "%";
    }

    public static void printPhaseResults(List<TestResult> tests) {
        for (String line : TerminalReport.formatPhaseResults(tests, Theme.colorsEnabled())) {
            Terminal.println(line);
        }
    }

    public static void printAggregateReport(ReportSnapshot snapshot, boolean useColor, boolean verbose) {
        boolean color = useColor && Theme.colorsEnabled();
        if (verbose && !snapshot.testsReport().projects().isEmpty()) {
            Terminal.println();
            for (String line : TerminalReport.formatVerboseLines(snapshot.testsReport(), color)) {
                Terminal.println(line);
            }
            for (String line : TerminalReport.formatVerboseClosing(
                    snapshot.testsReport(), snapshot.crap(), snapshot.reportPath(), color)) {
                Terminal.println(line);
            }
            Terminal.println();
            return;
        }
        for (String line : TerminalReport.formatCrapLines(snapshot.crap(), color)) {
            Terminal.println(line);
        }
        Terminal.println();
        String plain = ReportModel.formatStatusLine(snapshot, TerminalReport.TERMINAL_WIDTH);
        Terminal.println(color ? Theme.colorizeStatusLine(plain, snapshot) : plain);
        Terminal.println();
        Terminal.println("projects");
        Terminal.println(TerminalReport.ruleLine());
        Terminal.println(TerminalReport.formatProjectsHeader());
        Terminal.println(TerminalReport.ruleLine());
        for (ProjectReport project : snapshot.projects()) {
            Terminal.println(TerminalReport.formatProjectRow(project, color));
        }
        if (snapshot.coverageEnabled()) {
            Terminal.println(TerminalReport.ruleLine());
            Terminal.println(TerminalReport.formatProjectsFooter(snapshot, color));
        }
        if (!snapshot.testsReport().projects().isEmpty()) {
            Terminal.println();
            Terminal.println("tests");
            for (String line : TerminalReport.formatGroupedTestLines(snapshot.testsReport(), color)) {
                Terminal.println(line);
            }
            Terminal.println();
            Terminal.println("mini");
            for (String line : TerminalReport.formatMiniReportLines(snapshot.testsReport(), color)) {
                Terminal.println(line);
            }
        } else if (!snapshot.integrationRows().isEmpty()) {
            Terminal.println();
            Terminal.println("tests");
            Terminal.println(TerminalReport.ruleLine());
            Terminal.println(TerminalReport.formatIntegrationHeader());
            Terminal.println(TerminalReport.ruleLine());
            for (IntegrationRow row : snapshot.integrationRows()) {
                Terminal.println(TerminalReport.formatIntegrationRow(row, color));
            }
        }
        Terminal.println();
    }
}
