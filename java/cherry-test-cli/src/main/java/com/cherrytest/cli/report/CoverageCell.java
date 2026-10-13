package com.cherrytest.cli.report;

import com.cherrytest.cli.coverage.CoverageSummary;
import com.cherrytest.common.rustcompat.RustFloatFormat;
import org.jspecify.annotations.Nullable;

public final class CoverageCell {
    private CoverageCell() {}

    public static String formatLinesCell(@Nullable CoverageSummary summary, boolean compact) {
        if (summary != null && summary.linesFound() > 0) {
            return formatLinesSummary(summary, compact);
        }
        return "-";
    }

    public static String formatFnCell(@Nullable CoverageSummary summary, boolean compact) {
        if (summary != null && summary.functionsFound() > 0) {
            return formatFnSummary(summary, compact);
        }
        return "-";
    }

    public static String formatLinesCellColored(@Nullable CoverageSummary summary, boolean compact) {
        String plain = formatLinesCell(summary, compact);
        if (plain.equals("-") || summary == null) {
            return plain;
        }
        return colorizeLinesCell(plain, summary, compact);
    }

    public static String formatFnCellColored(@Nullable CoverageSummary summary, boolean compact) {
        String plain = formatFnCell(summary, compact);
        if (plain.equals("-") || summary == null) {
            return plain;
        }
        String percent = RustFloatFormat.fixed(summary.functionsPercent(), 1) + "%";
        Accent accent = Theme.coverageAccent(summary.functionsPercent());
        return plain.replace(percent, Theme.paint(percent, accent));
    }

    public static String linesCellHtml(@Nullable CoverageSummary summary) {
        if (summary != null && summary.linesFound() > 0) {
            return linesHtml(summary);
        }
        return htmlEscape("-");
    }

    public static String fnCellHtml(@Nullable CoverageSummary summary) {
        if (summary != null && summary.functionsFound() > 0) {
            return fnHtml(summary);
        }
        return htmlEscape("-");
    }

    private static String formatLinesSummary(CoverageSummary summary, boolean compact) {
        long uncovered = summary.linesUncovered();
        String percent = RustFloatFormat.fixed(summary.linesPercent(), 1) + "%";
        if (compact) {
            return summary.linesHit() + "/" + summary.linesFound() + "·" + summary.linesHit() + "c·" + uncovered + "u·"
                    + percent;
        }
        return summary.linesHit() + "/" + summary.linesFound() + " · " + summary.linesHit() + " cov · " + uncovered
                + " unc · " + percent;
    }

    private static String formatFnSummary(CoverageSummary summary, boolean compact) {
        String percent = RustFloatFormat.fixed(summary.functionsPercent(), 1) + "%";
        if (compact) {
            return summary.functionsHit() + "/" + summary.functionsFound() + "·" + percent;
        }
        return summary.functionsHit() + "/" + summary.functionsFound() + " · " + percent;
    }

    private static String colorizeLinesCell(String plain, CoverageSummary summary, boolean compact) {
        String percent = RustFloatFormat.fixed(summary.linesPercent(), 1) + "%";
        String colored = Theme.paint(percent, Theme.coverageAccent(summary.linesPercent()));
        if (plain.endsWith(percent)) {
            return plain.substring(0, plain.length() - percent.length()) + colored;
        }
        return formatLinesSummary(summary, compact).replace(percent, colored);
    }

    private static String linesHtml(CoverageSummary summary) {
        long uncovered = summary.linesUncovered();
        String ratio = summary.linesHit() + "/" + summary.linesFound();
        String percent = RustFloatFormat.fixed(summary.linesPercent(), 1) + "%";
        String percentSpan = spanAccent(percent, Theme.coverageAccent(summary.linesPercent()));
        return "<span class=\"sub\">" + htmlEscape(ratio) + "</span> · " + summary.linesHit()
                + " cov · <span class=\"low\">" + uncovered + " unc</span> · " + percentSpan;
    }

    private static String fnHtml(CoverageSummary summary) {
        String ratio = summary.functionsHit() + "/" + summary.functionsFound();
        String percent = RustFloatFormat.fixed(summary.functionsPercent(), 1) + "%";
        String percentSpan = spanAccent(percent, Theme.coverageAccent(summary.functionsPercent()));
        return "<span class=\"sub\">" + htmlEscape(ratio) + "</span> · " + percentSpan;
    }

    private static String spanAccent(String text, Accent accent) {
        return "<span class=\"" + Theme.htmlClass(accent) + "\">" + htmlEscape(text) + "</span>";
    }

    private static String htmlEscape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
