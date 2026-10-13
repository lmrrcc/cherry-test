package com.cherrytest.cli.report;

import com.cherrytest.cli.coverage.CoverageSummary;
import com.cherrytest.cli.nativeos.Terminal;
import com.cherrytest.common.process.ProcessEnvironment;
import org.jspecify.annotations.Nullable;

public final class Theme {
    public static final double LOW_COVERAGE_THRESHOLD = 50.0;
    private static final String ANSI_GREEN = "\u001b[32m";
    private static final String ANSI_YELLOW = "\u001b[33m";
    private static final String ANSI_BLUE = "\u001b[94m";
    private static final String ANSI_RED = "\u001b[31m";
    private static final String ANSI_RESET = "\u001b[0m";

    private Theme() {}

    public static boolean colorsEnabled() {
        if (ProcessEnvironment.isPresent("NO_COLOR")) {
            return false;
        }
        return Terminal.stdoutIsTerminal();
    }

    public static Accent coverageAccent(double percent) {
        if (Double.isNaN(percent)) {
            return Accent.NEUTRAL;
        }
        if (percent < LOW_COVERAGE_THRESHOLD) {
            return Accent.LOW;
        }
        return Accent.OK;
    }

    public static Accent coverageAccentOpt(@Nullable CoverageSummary summary) {
        if (summary != null && summary.linesFound() > 0) {
            return coverageAccent(summary.linesPercent());
        }
        return Accent.NEUTRAL;
    }

    public static Accent statusAccent(String label) {
        return switch (label) {
            case "ok", "OK" -> Accent.OK;
            case "skipped" -> Accent.SKIP;
            case "FAIL", "fail" -> Accent.FAIL;
            default -> Accent.NEUTRAL;
        };
    }

    public static Accent suiteAccent(long fail, long skip) {
        if (fail > 0) {
            return Accent.FAIL;
        }
        if (skip > 0) {
            return Accent.SKIP;
        }
        return Accent.OK;
    }

    public static String htmlClass(Accent accent) {
        return switch (accent) {
            case OK -> "ok";
            case SKIP -> "skip";
            case FAIL -> "fail";
            case COVERED -> "covered";
            case UNCOVERED -> "uncovered";
            case LOW -> "low";
            case NEUTRAL -> "neutral";
        };
    }

    public static String paint(String text, Accent accent) {
        return paintWhen(colorsEnabled(), text, accent);
    }

    public static String paintWhen(boolean enabled, String text, Accent accent) {
        if (!enabled) {
            return text;
        }
        return ansiCode(accent) + text + ANSI_RESET;
    }

    private static String ansiCode(Accent accent) {
        return switch (accent) {
            case OK -> ANSI_GREEN;
            case SKIP, UNCOVERED, LOW -> ANSI_YELLOW;
            case FAIL -> ANSI_RED;
            case COVERED -> ANSI_BLUE;
            case NEUTRAL -> "";
        };
    }

    public static String colorizeStatusLine(String plain, ReportSnapshot snapshot) {
        if (!colorsEnabled()) {
            return plain;
        }
        String out = plain;
        String overall = "cherry-test " + (snapshot.overallOk() ? "OK" : "FAIL");
        Accent accent = snapshot.overallOk() ? Accent.OK : Accent.FAIL;
        out = replaceFirst(out, overall, paint(overall, accent));
        if (snapshot.integrationSkipped() > 0) {
            String token = "skip:" + snapshot.integrationSkipped();
            out = out.replace(token, paint(token, Accent.SKIP));
        }
        if (snapshot.packagesFailed() > 0) {
            String token = "fail:" + snapshot.packagesFailed();
            String prefix = "pkg " + snapshot.packagesPassed() + "/" + snapshot.packagesTotal() + " fail:";
            if (out.contains(prefix)) {
                String packageFail = prefix + snapshot.packagesFailed();
                out = out.replace(
                        packageFail,
                        "pkg " + snapshot.packagesPassed() + "/" + snapshot.packagesTotal() + " "
                                + paint("fail:" + snapshot.packagesFailed(), Accent.FAIL));
            } else {
                out = out.replace(token, paint(token, Accent.FAIL));
            }
        }
        if (snapshot.integrationFailed() > 0) {
            String token = "fail:" + snapshot.integrationFailed();
            out = out.replace(token, paint(token, Accent.FAIL));
        }
        if (snapshot.coverageEnabled()) {
            out = colorizeCovSegment(out, "all:", snapshot.totals().merged().linesPercent());
            if (snapshot.totals().unit() != null) {
                out = colorizeCovSegment(out, " unit:", snapshot.totals().unit().linesPercent());
            }
            if (snapshot.totals().integration() != null) {
                out = colorizeCovSegment(
                        out, " it:", snapshot.totals().integration().linesPercent());
            }
            out = colorizeCovSegment(
                    out, " diff:", snapshot.diffCoverage().merged().linesPercent());
        }
        return out;
    }

    private static String replaceFirst(String text, String target, String replacement) {
        int index = text.indexOf(target);
        if (index < 0) {
            return text;
        }
        return text.substring(0, index) + replacement + text.substring(index + target.length());
    }

    private static String colorizeCovSegment(String line, String prefix, double percent) {
        int start = line.indexOf(prefix);
        if (start < 0) {
            return line;
        }
        int valueStart = start + prefix.length();
        String rest = line.substring(valueStart);
        int space = rest.indexOf(' ');
        int end = space < 0 ? rest.length() : space;
        String value = line.substring(valueStart, valueStart + end);
        if (value.equals("-")) {
            return line;
        }
        return line.substring(0, valueStart) + paint(value, coverageAccent(percent)) + line.substring(valueStart + end);
    }
}
