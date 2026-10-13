package com.cherrytest.cli.report;

import com.cherrytest.cli.coverage.CoverageSummary;
import com.cherrytest.cli.crap.Crap;
import com.cherrytest.cli.crap.CrapFunction;
import com.cherrytest.cli.crap.CrapReport;
import com.cherrytest.cli.crap.CrapSeverity;
import com.cherrytest.cli.crap.CrapSourceLine;
import com.cherrytest.cli.crap.CrapTotals;
import com.cherrytest.cli.crap.LineMeasure;
import com.cherrytest.common.adapter.TestResult;
import com.cherrytest.common.adapter.TestStatus;
import com.cherrytest.common.rustcompat.RustFloatFormat;
import com.cherrytest.common.rustcompat.RustOrdering;
import com.cherrytest.common.rustcompat.RustText;
import com.cherrytest.common.rustcompat.U64;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.Nullable;

public final class TerminalReport {
    public static final int TERMINAL_WIDTH = 200;
    public static final int VERBOSE_WIDTH = 100;
    private static final int CRAP_TERMINAL_FUNCTIONS = 4;
    private static final int CRAP_NAME_WIDTH = 16;
    private static final int PHASE_NAME_WIDTH = 78;

    private TerminalReport() {}

    enum Align {
        LEFT,
        RIGHT
    }

    record Col(int width, Align align) {}

    private record Cell(String value, Col column) {}

    private static final Col STATUS_OVERALL = new Col(15, Align.LEFT);
    private static final Col STATUS_RUN = new Col(28, Align.LEFT);
    private static final Col STATUS_PKG = new Col(10, Align.LEFT);
    private static final Col STATUS_IT = new Col(34, Align.LEFT);
    private static final Col STATUS_COV = new Col(58, Align.LEFT);
    private static final Col STATUS_REPORT = new Col(50, Align.LEFT);
    private static final Col PRJ_PROJECT = new Col(10, Align.LEFT);
    private static final Col PRJ_PACKAGE = new Col(14, Align.LEFT);
    private static final Col PRJ_UNIT = new Col(38, Align.RIGHT);
    private static final Col PRJ_UNIT_FN = new Col(22, Align.RIGHT);
    private static final Col PRJ_IT = new Col(38, Align.RIGHT);
    private static final Col PRJ_IT_FN = new Col(22, Align.RIGHT);
    private static final Col PRJ_MERGED = new Col(38, Align.RIGHT);
    static final Col PRJ_STATUS = new Col(11, Align.LEFT);
    private static final Col IT_PROJECT = new Col(10, Align.LEFT);
    private static final Col IT_SUITE = new Col(12, Align.LEFT);
    private static final Col IT_TEST = new Col(84, Align.LEFT);
    private static final Col IT_SCOPE = new Col(29, Align.LEFT);
    private static final Col IT_TARGET = new Col(34, Align.LEFT);
    private static final Col IT_MS = new Col(8, Align.RIGHT);
    private static final Col IT_STATUS = new Col(8, Align.RIGHT);
    private static final Col IT_HTTP = new Col(8, Align.RIGHT);

    public static int visibleWidth(String value) {
        int width = 0;
        int index = 0;
        while (index < value.length()) {
            int character = value.codePointAt(index);
            index += Character.charCount(character);
            if (character == 0x1b) {
                while (index < value.length()) {
                    int next = value.codePointAt(index);
                    index += Character.charCount(next);
                    if (next == 'm') {
                        break;
                    }
                }
                continue;
            }
            width++;
        }
        return width;
    }

    public static String truncateVisible(String value, int maximum) {
        if (maximum == 0) {
            return "";
        }
        if (visibleWidth(value) <= maximum) {
            return value;
        }
        if (maximum == 1) {
            return "…";
        }
        StringBuilder out = new StringBuilder();
        int width = 0;
        int index = 0;
        while (index < value.length()) {
            if (width + 1 >= maximum) {
                break;
            }
            int character = value.codePointAt(index);
            out.appendCodePoint(character);
            index += Character.charCount(character);
            width++;
        }
        out.append('…');
        return out.toString();
    }

    static String padCell(String value, int width, Align align) {
        int visible = visibleWidth(value);
        if (visible >= width) {
            return truncateVisible(value, width);
        }
        String padding = " ".repeat(width - visible);
        return align == Align.LEFT ? value + padding : padding + value;
    }

    private static String formatRow(List<Cell> cells) {
        List<String> parts = new ArrayList<>();
        for (Cell cell : cells) {
            parts.add(padCell(cell.value(), cell.column().width(), cell.column().align()));
        }
        return String.join(" ", parts);
    }

    public static String ruleLine() {
        return "-".repeat(TERMINAL_WIDTH);
    }

    public static List<String> formatCrapLines(CrapReport report, boolean color) {
        if (!report.enabled()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        if (report.changed() != null) {
            lines.add(crapTotalsLine("change: ", report.changed().totals()));
        }
        lines.add(crapTotalsLine("crap: ", report.totals()));
        if (report.functions().isEmpty()) {
            lines.add(crapComplexityLine("", report.totals()));
            return lines;
        }
        List<CrapFunction> ranked = new ArrayList<>(report.functions());
        ranked.sort(Comparator.<CrapFunction>comparingDouble(CrapFunction::score)
                .reversed()
                .thenComparing(CrapFunction::path, RustOrdering.STRING_ORDER)
                .thenComparingLong(CrapFunction::line)
                .thenComparing(CrapFunction::name, RustOrdering.STRING_ORDER));
        List<CrapFunction> shown = ranked.subList(0, Math.min(CRAP_TERMINAL_FUNCTIONS, ranked.size()));
        int nameWidth = crapNameWidth(shown);
        for (CrapFunction function : shown) {
            lines.add(formatCrapFocusLine(function, nameWidth, color));
        }
        return lines;
    }

    private static String crapTotalsLine(String prefix, CrapTotals totals) {
        return prefix + totals.functions() + " functions · worst " + RustFloatFormat.fixed(totals.worstCrap(), 1)
                + " · "
                + totals.crappyFunctions() + " at or above " + RustFloatFormat.fixed(Crap.CRAP_THRESHOLD, 0) + " ("
                + RustFloatFormat.fixed(totals.crappyPercent(), 1) + "%) · crap load "
                + RustFloatFormat.fixed(totals.crapLoad(), 1);
    }

    private static String crapComplexityLine(String prefix, CrapTotals totals) {
        return prefix + "complexity " + totals.complexity() + " · total CRAP "
                + RustFloatFormat.fixed(totals.totalCrap(), 1)
                + " · mean " + RustFloatFormat.fixed(totals.meanCrap(), 1) + " · median "
                + RustFloatFormat.fixed(totals.medianCrap(), 1);
    }

    private static int crapNameWidth(List<CrapFunction> functions) {
        int width = CRAP_NAME_WIDTH;
        while (width > 0) {
            int current = width;
            if (functions.stream().noneMatch(function -> crapFocusWidth(function, current) > VERBOSE_WIDTH)) {
                break;
            }
            width--;
        }
        return width;
    }

    private static int crapFocusWidth(CrapFunction function, int nameWidth) {
        return 2 + 5 + 2 + nameWidth + 2 + RustText.charCount(crapFocusTail(function));
    }

    private static String formatCrapFocusLine(CrapFunction function, int nameWidth, boolean color) {
        String score = leftPad(RustFloatFormat.fixed(function.score(), 1), 5);
        String painted = paintCrapScore(score, Crap.crapSeverity(function.score()), color);
        String name = padCell(trimVisible(function.name(), nameWidth), nameWidth, Align.LEFT);
        String head = "  " + painted + "  " + name + "  ";
        int room = Math.max(0, VERBOSE_WIDTH - visibleWidth(head));
        return head + trimVisible(crapFocusTail(function), room);
    }

    private static String leftPad(String value, int width) {
        int count = RustText.charCount(value);
        return count >= width ? value : " ".repeat(width - count) + value;
    }

    private static String crapFocusTail(CrapFunction function) {
        String location = crapFocusFile(function.path()) + ":" + function.line();
        String snippet = crapFocusText(function);
        return snippet.isEmpty() ? location : location + "  " + snippet;
    }

    private static String crapFocusFile(String path) {
        String[] parts = path.split("[/\\\\]", -1);
        for (int index = parts.length - 1; index >= 0; index--) {
            if (!parts[index].isEmpty()) {
                return parts[index];
            }
        }
        return path;
    }

    private static String paintCrapScore(String score, CrapSeverity severity, boolean color) {
        Accent accent = switch (severity) {
            case CRITICAL -> Accent.FAIL;
            case HIGH, MODERATE -> Accent.LOW;
            case LOW -> null;
        };
        if (accent == null) {
            return score;
        }
        return Theme.paintWhen(color, score, accent);
    }

    private static String crapFocusText(CrapFunction function) {
        CrapSourceLine chosen = null;
        for (CrapSourceLine line : function.source()) {
            if (line.measure() == LineMeasure.UNCOVERED) {
                chosen = line;
                break;
            }
        }
        if (chosen == null && !function.source().isEmpty()) {
            chosen = function.source().getFirst();
        }
        return chosen == null ? "" : String.join(" ", RustText.splitWhitespace(chosen.text()));
    }

    private static String trimVisible(String text, int maximum) {
        if (RustText.charCount(text) <= maximum) {
            return text;
        }
        if (maximum <= 3) {
            return ReportModel.prefixCodePoints(text, maximum);
        }
        return ReportModel.prefixCodePoints(text, maximum - 3) + "...";
    }

    public static List<String> formatVerboseLines(TestsReport report, boolean color) {
        List<String> lines = new ArrayList<>();
        String currentPhase = "";
        for (VerboseGroup group : verboseGroups(report)) {
            String phase = group.project + "/" + group.packageName + "/" + group.phase;
            if (!phase.equals(currentPhase)) {
                if (!currentPhase.isEmpty()) {
                    lines.add("");
                }
                lines.add(verbosePhaseLine(phase, color));
                currentPhase = phase;
            }
            lines.add(verboseSuiteLine(group.suite, suiteAccentOf(group.stats), color));
            for (TestCaseView testCase : group.cases) {
                lines.add(verboseCaseLine(testCase, color));
                if (testCase.statusLabel().equals("FAIL")
                        && testCase.reason() != null
                        && !testCase.reason().isEmpty()) {
                    lines.add(verboseReasonLine(testCase.reason(), color));
                }
            }
        }
        if (!lines.isEmpty()) {
            lines.add("");
        }
        return lines;
    }

    public static List<String> formatVerboseClosing(
            TestsReport report, CrapReport crap, Path reportPath, boolean color) {
        List<String> lines = new ArrayList<>(formatCrapLines(crap, color));
        if (!lines.isEmpty()) {
            lines.add("");
        }
        lines.add(formatVerboseReportPath(reportPath));
        lines.add(verboseSummaryLine(report, color));
        return lines;
    }

    public static String formatVerboseReportPath(Path path) {
        String text = path.toString();
        int index = text.lastIndexOf("target/cherry-test");
        String shown = index >= 0 ? "report  " + text.substring(index) : "report  index.html";
        return truncateVisible(shown, VERBOSE_WIDTH);
    }

    private static final class VerboseGroup {
        private final String project;
        private final String packageName;
        private final String phase;
        private final String suite;
        private TestTimingStats stats = new TestTimingStats(0, 0, 0, 0, 0, 0, 0, 0);
        private final List<TestCaseView> cases = new ArrayList<>();

        private VerboseGroup(String project, String packageName, String phase, String suite) {
            this.project = project;
            this.packageName = packageName;
            this.phase = phase;
            this.suite = suite;
        }
    }

    private static List<VerboseGroup> verboseGroups(TestsReport report) {
        List<VerboseGroup> groups = new ArrayList<>();
        for (ProjectTestView project : report.projects()) {
            for (SuiteTestView suite : project.suites()) {
                for (TestCaseView testCase : suite.cases()) {
                    String packageName = verbosePackage(testCase);
                    String phase = verbosePhase(suite.suite(), testCase);
                    String suiteName = verboseSuite(suite.suite(), phase, testCase);
                    VerboseGroup last = groups.isEmpty() ? null : groups.getLast();
                    if (last != null
                            && last.project.equals(testCase.project())
                            && last.packageName.equals(packageName)
                            && last.phase.equals(phase)
                            && last.suite.equals(suiteName)) {
                        last.cases.add(testCase);
                    } else {
                        VerboseGroup group = new VerboseGroup(testCase.project(), packageName, phase, suiteName);
                        group.cases.add(testCase);
                        groups.add(group);
                    }
                }
            }
        }
        for (VerboseGroup group : groups) {
            List<TestView.StatusTiming> items = new ArrayList<>();
            group.cases.forEach(
                    testCase -> items.add(new TestView.StatusTiming(testCase.statusLabel(), testCase.elapsedMs())));
            group.stats = TestView.timingStats(items);
        }
        return groups;
    }

    private static String verbosePackage(TestCaseView testCase) {
        int dot = testCase.target().indexOf('.');
        if (dot < 0) {
            return testCase.project();
        }
        String service = testCase.target().substring(dot + 1);
        if (service.isEmpty()) {
            return testCase.project();
        }
        if (service.equals("api")) {
            return testCase.project() + "-rs-api";
        }
        return service;
    }

    private static String verbosePhase(String recordedSuite, TestCaseView testCase) {
        if (isPhaseName(recordedSuite)) {
            return recordedSuite;
        }
        if (!testCase.scope().isEmpty() && !testCase.scope().equals("none")) {
            return "integration";
        }
        return "unit";
    }

    private static String verboseSuite(String recordedSuite, String phase, TestCaseView testCase) {
        if (phase.equals("visual")) {
            return "screen";
        }
        if (phase.equals("functional")) {
            return "storefront";
        }
        if (!isPhaseName(recordedSuite)) {
            return recordedSuite;
        }
        String test = stripPackagePrefix(testCase.test());
        String rust = rustSuite(test);
        return rust != null ? rust : jsSuite(test);
    }

    private static String verboseTestName(TestCaseView testCase) {
        String test = stripPackagePrefix(testCase.test());
        int separator = test.lastIndexOf("::");
        return separator < 0 ? test : test.substring(separator + 2);
    }

    private static boolean isPhaseName(String name) {
        return switch (name) {
            case "unit", "integration", "functional", "visual", "ui", "mutation" -> true;
            default -> false;
        };
    }

    private static String stripPackagePrefix(String test) {
        int separator = test.indexOf(": ");
        return separator < 0 ? test : test.substring(separator + 2);
    }

    private static @Nullable String rustSuite(String test) {
        int separator = test.lastIndexOf("::");
        if (separator < 0) {
            return null;
        }
        String head = test.substring(0, separator);
        int parentSeparator = head.lastIndexOf("::");
        if (parentSeparator < 0) {
            return head;
        }
        String parent = head.substring(0, parentSeparator);
        String last = head.substring(parentSeparator + 2);
        if (last.equals("tests")) {
            int grand = parent.lastIndexOf("::");
            return grand < 0 ? parent : parent.substring(grand + 2);
        }
        return last;
    }

    private static String jsSuite(String test) {
        if (test.startsWith("Shop ") || test.equals("Shop")) {
            return "Shop";
        }
        if (test.startsWith("mount")) {
            return "mount";
        }
        if (test.startsWith("shop API") || test.startsWith("shop api")) {
            return "api";
        }
        return "cart";
    }

    private static String verbosePhaseLine(String phase, boolean color) {
        String text = truncateVisible("==> " + phase, VERBOSE_WIDTH);
        return Theme.paintWhen(color, text, Accent.COVERED);
    }

    private static String verboseSuiteLine(String name, Accent accent, boolean color) {
        String shown = truncateVisible(name, VERBOSE_WIDTH - 2);
        return "  " + Theme.paintWhen(color, shown, accent);
    }

    static String verboseCaseLine(TestCaseView testCase, boolean color) {
        String mark = switch (testCase.statusLabel()) {
            case "FAIL", "fail" -> "✗";
            case "skipped" -> "-";
            default -> "✓";
        };
        String painted = Theme.paintWhen(color, mark, Theme.statusAccent(testCase.statusLabel()));
        String name = padCell(truncateVisible(verboseTestName(testCase), 86), 86, Align.LEFT);
        String timing = padCell(U64.toString(testCase.elapsedMs()) + "ms", 7, Align.RIGHT);
        return "    " + painted + " " + name + " " + timing;
    }

    private static String verboseReasonLine(String reason, boolean color) {
        String text = truncateVisible(reason, 92);
        return "      " + Theme.paintWhen(color, "! " + text, Accent.FAIL);
    }

    static String verboseSummaryLine(TestsReport report, boolean color) {
        long run = 0;
        long ok = 0;
        long fail = 0;
        long skip = 0;
        for (ProjectTestView project : report.projects()) {
            run += project.stats().run();
            ok += project.stats().ok();
            fail += project.stats().fail();
            skip += project.stats().skip();
        }
        String head = "cherry-test " + (fail > 0 ? "FAIL" : "OK");
        String painted = Theme.paintWhen(color, head, fail > 0 ? Accent.FAIL : Accent.OK);
        String line = painted + "  " + run + " run · " + ok + " ok · " + fail + " fail · " + skip + " skip";
        return truncateVisible(line, VERBOSE_WIDTH);
    }

    public static List<String> formatPhaseResults(List<TestResult> tests, boolean color) {
        if (tests.isEmpty()) {
            return List.of("  no tests reported");
        }
        List<String> suiteNames = new ArrayList<>();
        List<List<TestResult>> members = new ArrayList<>();
        for (TestResult test : tests) {
            String suite = phaseSuite(test);
            int index = suiteNames.indexOf(suite);
            if (index < 0) {
                suiteNames.add(suite);
                List<TestResult> group = new ArrayList<>();
                group.add(test);
                members.add(group);
            } else {
                members.get(index).add(test);
            }
        }
        List<String> lines = new ArrayList<>();
        for (int index = 0; index < suiteNames.size(); index++) {
            lines.add("  " + truncateVisible(suiteNames.get(index), VERBOSE_WIDTH - 2));
            for (TestResult test : members.get(index)) {
                lines.add(phaseCaseLine(test, color));
                String reason = phaseReasonLine(test, color);
                if (reason != null) {
                    lines.add(reason);
                }
            }
        }
        lines.add(phaseTotalsLine(tests));
        return lines;
    }

    private static String phaseSuite(TestResult test) {
        String[] segments = test.name().split("::", -1);
        if (segments.length >= 2) {
            for (int index = segments.length - 2; index >= 0; index--) {
                if (!segments[index].equals("tests")) {
                    return segments[index];
                }
            }
            return segments[segments.length - 2];
        }
        if (test.source() != null && test.source().getFileName() != null) {
            return test.source().getFileName().toString();
        }
        return "tests";
    }

    private static String phaseTestName(TestResult test) {
        int separator = test.name().lastIndexOf("::");
        return separator < 0 ? test.name() : test.name().substring(separator + 2);
    }

    private record PhaseStatus(String mark, String label, Accent accent) {}

    private static PhaseStatus phaseStatus(TestStatus status) {
        return switch (status) {
            case PASSED -> new PhaseStatus("✓", "ok", Accent.OK);
            case FAILED -> new PhaseStatus("✗", "FAIL", Accent.FAIL);
            case SKIPPED -> new PhaseStatus("-", "skipped", Accent.SKIP);
        };
    }

    private static String phaseCaseLine(TestResult test, boolean color) {
        PhaseStatus status = phaseStatus(test.status());
        String name = padCell(truncateVisible(phaseTestName(test), PHASE_NAME_WIDTH), PHASE_NAME_WIDTH, Align.LEFT);
        String label = Theme.paintWhen(color, padCell(status.label(), 7, Align.LEFT), status.accent());
        String timing = padCell(U64.toString(test.durationMs()) + "ms", 7, Align.RIGHT);
        return "    " + Theme.paintWhen(color, status.mark(), status.accent()) + " " + name + " " + label + timing;
    }

    private static @Nullable String phaseReasonLine(TestResult test, boolean color) {
        String marker = switch (test.status()) {
            case FAILED -> "!";
            case SKIPPED -> "-";
            case PASSED -> null;
        };
        if (marker == null || test.message() == null) {
            return null;
        }
        String firstLine = null;
        for (String line : RustText.lines(test.message())) {
            String trimmed = RustText.trim(line);
            if (!trimmed.isEmpty()) {
                firstLine = trimmed;
                break;
            }
        }
        if (firstLine == null) {
            return null;
        }
        String text = truncateVisible(marker + " " + firstLine, VERBOSE_WIDTH - 6);
        return "      "
                + Theme.paintWhen(color, text, phaseStatus(test.status()).accent());
    }

    private static String phaseTotalsLine(List<TestResult> tests) {
        long passed = tests.stream()
                .filter(test -> test.status() == TestStatus.PASSED)
                .count();
        long failed = tests.stream()
                .filter(test -> test.status() == TestStatus.FAILED)
                .count();
        long skipped = tests.stream()
                .filter(test -> test.status() == TestStatus.SKIPPED)
                .count();
        long elapsed = 0;
        for (TestResult test : tests) {
            elapsed += test.durationMs();
        }
        return "  " + tests.size() + " tests · " + passed + " ok · " + failed + " failed · " + skipped + " skipped · "
                + U64.toString(elapsed) + "ms";
    }

    public static String formatStatusRow(ReportSnapshot snapshot) {
        String overall = "cherry-test " + (snapshot.overallOk() ? "OK" : "FAIL");
        String run = "run:" + snapshot.runId();
        String packages = snapshot.packagesFailed() > 0
                ? "pkg " + snapshot.packagesPassed() + "/" + snapshot.packagesTotal() + " fail:"
                        + snapshot.packagesFailed()
                : "pkg " + snapshot.packagesPassed() + "/" + snapshot.packagesTotal();
        String itOk = snapshot.integrationRun() > 0
                ? RustFloatFormat.fixed(
                                ((double) (snapshot.integrationRun() - snapshot.integrationFailed())
                                                / (double) snapshot.integrationRun())
                                        * 100.0,
                                0)
                        + "%"
                : "-";
        String it = "tests run:" + snapshot.integrationRun() + " skip:" + snapshot.integrationSkipped() + " fail:"
                + snapshot.integrationFailed() + " ok:" + itOk;
        String coverage = snapshot.coverageEnabled()
                ? "all:" + pct(snapshot.totals().merged().linesPercent()) + " unit:"
                        + pctOpt(snapshot.totals().unit())
                        + " it:" + pctOpt(snapshot.totals().integration()) + " diff:"
                        + pct(snapshot.diffCoverage().merged().linesPercent())
                : "cov:-";
        String report = "report: " + snapshot.reportPath();
        return formatRow(List.of(
                new Cell(overall, STATUS_OVERALL),
                new Cell(run, STATUS_RUN),
                new Cell(packages, STATUS_PKG),
                new Cell(it, STATUS_IT),
                new Cell(coverage, STATUS_COV),
                new Cell(report, STATUS_REPORT)));
    }

    public static String formatProjectsHeader() {
        return formatRow(List.of(
                new Cell("project", PRJ_PROJECT),
                new Cell("package", PRJ_PACKAGE),
                new Cell("unit", PRJ_UNIT),
                new Cell("unit_fn", PRJ_UNIT_FN),
                new Cell("it", PRJ_IT),
                new Cell("it_fn", PRJ_IT_FN),
                new Cell("merged", PRJ_MERGED),
                new Cell("status", PRJ_STATUS)));
    }

    public static String formatProjectRow(ProjectReport project, boolean color) {
        String status = project.testsPassed() ? "ok" : "FAIL";
        String statusOut = color ? Theme.paint(status, Theme.statusAccent(status)) : status;
        return formatRow(List.of(
                new Cell(project.projectId(), PRJ_PROJECT),
                new Cell(project.packageName(), PRJ_PACKAGE),
                new Cell(linesCell(project.diffCoverage().unit(), color), PRJ_UNIT),
                new Cell(fnCell(project.diffCoverage().unit(), color), PRJ_UNIT_FN),
                new Cell(linesCell(project.diffCoverage().integration(), color), PRJ_IT),
                new Cell(fnCell(project.diffCoverage().integration(), color), PRJ_IT_FN),
                new Cell(linesCell(project.diffCoverage().merged(), color), PRJ_MERGED),
                new Cell(statusOut, PRJ_STATUS)));
    }

    public static String formatProjectsFooter(ReportSnapshot snapshot, boolean color) {
        CoverageTotals diff = snapshot.diffCoverage();
        String statusOut = color ? Theme.paint("ALL", Accent.NEUTRAL) : "ALL";
        return formatRow(List.of(
                new Cell("ALL", PRJ_PROJECT),
                new Cell("(changed)", PRJ_PACKAGE),
                new Cell(linesCell(diff.unit(), color), PRJ_UNIT),
                new Cell(fnCell(diff.unit(), color), PRJ_UNIT_FN),
                new Cell(linesCell(diff.integration(), color), PRJ_IT),
                new Cell(fnCell(diff.integration(), color), PRJ_IT_FN),
                new Cell(linesCell(diff.merged(), color), PRJ_MERGED),
                new Cell(statusOut, PRJ_STATUS)));
    }

    public static String formatIntegrationHeader() {
        return formatRow(List.of(
                new Cell("project", IT_PROJECT),
                new Cell("suite", IT_SUITE),
                new Cell("test", IT_TEST),
                new Cell("scope", IT_SCOPE),
                new Cell("target", IT_TARGET),
                new Cell("ms", IT_MS),
                new Cell("status", IT_STATUS),
                new Cell("http", IT_HTTP)));
    }

    public static List<String> formatGroupedTestLines(TestsReport report, boolean color) {
        List<String> lines = new ArrayList<>();
        for (ProjectTestView project : report.projects()) {
            lines.add(paintedLabel(project.project(), 10, suiteAccentOf(project.stats()), color) + "  "
                    + TestView.formatTimingStats(project.stats()));
            for (SuiteTestView suite : project.suites()) {
                lines.add("  " + paintedLabel(suite.suite(), 12, suiteAccentOf(suite.stats()), color) + "  "
                        + TestView.formatTimingStats(suite.stats()));
                for (TestCaseView testCase : suite.cases()) {
                    lines.add(formatGroupedCase(testCase, color));
                }
            }
        }
        return lines;
    }

    public static List<String> formatMiniReportLines(TestsReport report, boolean color) {
        List<String> lines = new ArrayList<>();
        List<TestView.StatusTiming> samples = new ArrayList<>();
        for (ProjectTestView project : report.projects()) {
            for (SuiteTestView suite : project.suites()) {
                lines.add(miniLine(suite.suite(), suite.stats(), color));
                for (TestCaseView testCase : suite.cases()) {
                    samples.add(new TestView.StatusTiming(testCase.statusLabel(), testCase.elapsedMs()));
                }
            }
        }
        if (!lines.isEmpty()) {
            lines.add(miniLine("all", TestView.timingStats(samples), color));
        }
        return lines;
    }

    private static String formatGroupedCase(TestCaseView testCase, boolean color) {
        String status = Theme.paintWhen(color, testCase.statusLabel(), Theme.statusAccent(testCase.statusLabel()));
        return "    " + padCell(testCase.test(), 72, Align.LEFT) + " " + padCell(testCase.scope(), 24, Align.LEFT) + " "
                + padCell(testCase.target(), 28, Align.LEFT) + " "
                + padCell(U64.toString(testCase.elapsedMs()), 8, Align.RIGHT) + " " + padCell(status, 8, Align.RIGHT)
                + " "
                + padCell(testCase.http(), 8, Align.RIGHT);
    }

    private static String miniLine(String label, TestTimingStats stats, boolean color) {
        return "  " + paintedLabel(label, 14, suiteAccentOf(stats), color) + "  " + TestView.formatTimingStats(stats);
    }

    static Accent suiteAccentOf(TestTimingStats stats) {
        return Theme.suiteAccent(stats.fail(), stats.skip());
    }

    private static String paintedLabel(String text, int width, Accent accent, boolean color) {
        String shown = truncateVisible(text, width);
        String painted = Theme.paintWhen(color, shown, accent);
        int visible = visibleWidth(shown);
        return visible >= width ? painted : painted + " ".repeat(width - visible);
    }

    public static String formatIntegrationRow(IntegrationRow row, boolean color) {
        String statusOut =
                color ? Theme.paint(row.statusLabel(), Theme.statusAccent(row.statusLabel())) : row.statusLabel();
        return formatRow(List.of(
                new Cell(row.project(), IT_PROJECT),
                new Cell(row.suite(), IT_SUITE),
                new Cell(row.test(), IT_TEST),
                new Cell(row.scope(), IT_SCOPE),
                new Cell(row.target(), IT_TARGET),
                new Cell(U64.toString(row.elapsedMs()), IT_MS),
                new Cell(statusOut, IT_STATUS),
                new Cell(row.http(), IT_HTTP)));
    }

    private static String linesCell(@Nullable CoverageSummary summary, boolean color) {
        return color ? CoverageCell.formatLinesCellColored(summary, true) : CoverageCell.formatLinesCell(summary, true);
    }

    private static String fnCell(@Nullable CoverageSummary summary, boolean color) {
        return color ? CoverageCell.formatFnCellColored(summary, true) : CoverageCell.formatFnCell(summary, true);
    }

    private static String pct(double value) {
        return Double.isNaN(value) ? "-" : RustFloatFormat.fixed(value, 1) + "%";
    }

    private static String pctOpt(@Nullable CoverageSummary summary) {
        return summary == null ? "-" : pct(summary.linesPercent());
    }
}
