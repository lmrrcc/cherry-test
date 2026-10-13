package com.cherrytest.cli.report;

import com.cherrytest.cli.coverage.CoverageSummary;
import com.cherrytest.cli.crap.Crap;
import com.cherrytest.cli.crap.CrapChange;
import com.cherrytest.cli.crap.CrapFunction;
import com.cherrytest.cli.crap.CrapProjectTotals;
import com.cherrytest.cli.crap.CrapSourceLine;
import com.cherrytest.cli.crap.CrapTotals;
import com.cherrytest.common.process.ProcessEnvironment;
import com.cherrytest.common.process.SystemCommand;
import com.cherrytest.common.reportshell.ReportShell;
import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.common.rustcompat.RustFloatFormat;
import com.cherrytest.common.rustcompat.RustIoError;
import com.cherrytest.common.rustcompat.RustText;
import com.cherrytest.common.rustcompat.U64;
import com.cherrytest.core.Defaults;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

public final class HtmlReport {
    private static final int CRAP_FUNCTIONS_SHOWN = 30;

    private HtmlReport() {}

    public static void writeHtmlReport(ReportSnapshot snapshot) {
        Path parent = snapshot.reportPath().getParent();
        JumpIndex jumps;
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException exception) {
                throw AnyhowError.context("create " + parent, new AnyhowError(RustIoError.display(exception)));
            }
            jumps = AdapterView.write(parent);
        } else {
            jumps = JumpIndex.empty();
        }
        try {
            Files.writeString(snapshot.reportPath(), renderReport(snapshot, jumps), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw AnyhowError.context(
                    "write " + snapshot.reportPath(), new AnyhowError(RustIoError.display(exception)));
        }
    }

    public static boolean isCi() {
        return ciEnvSet("CI") || ciEnvSet("GITHUB_ACTIONS") || ciEnvSet("GITLAB_CI") || ciEnvSet("BUILD_ID");
    }

    private static boolean ciEnvSet(String name) {
        String value = ProcessEnvironment.get(name);
        return value != null
                && !value.isEmpty()
                && !value.equals("0")
                && !RustText.toAsciiLowercase(value).equals("false");
    }

    private static boolean skipOpenEnv() {
        String value = ProcessEnvironment.get("CHERRY_TEST_NO_OPEN");
        return value != null
                && (value.equals("1") || RustText.toAsciiLowercase(value).equals("true"));
    }

    public static boolean shouldOpenReport(boolean noOpen, Defaults defaults) {
        return shouldOpenReportWith(noOpen, defaults.openReport(), isCi(), skipOpenEnv());
    }

    static boolean shouldOpenReportWith(boolean noOpen, boolean openReport, boolean ci, boolean noOpenEnv) {
        return !(noOpen || !openReport || noOpenEnv || ci);
    }

    public static void openHtmlReport(Path path) {
        if (!Files.exists(path)) {
            throw new AnyhowError("html report not found at " + path);
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        SystemCommand command = os.contains("mac")
                ? SystemCommand.of("open").arg(path.toString())
                : SystemCommand.of("xdg-open").arg(path.toString());
        int status;
        try {
            status = command.status();
        } catch (IOException exception) {
            throw AnyhowError.context("open " + path, new AnyhowError(RustIoError.display(exception)));
        }
        if (status != 0) {
            throw new AnyhowError("open " + path + " failed");
        }
    }

    static String renderReport(ReportSnapshot snapshot, JumpIndex jumps) {
        String status = buildStatusHtml(snapshot);
        StringBuilder body = new StringBuilder("<section id=\"projects_summary\"><h2>projects</h2>");
        body.append(renderProjectsTable(snapshot));
        body.append("</section><p><a href=\"phases.html\">Test phases, screenshots, logs and source coverage</a></p>");
        body.append(renderTestsSection(snapshot, jumps));
        body.append(renderProjectSections(snapshot, jumps));
        if (snapshot.crap().enabled()) {
            body.append(renderCrapSection(snapshot));
        }
        return ReportShell.renderCombinedReportDocument("cherry-test report", status, body.toString());
    }

    private static String crapTotalsParagraph(CrapTotals totals) {
        return "<p>" + totals.functions() + " functions · worst " + RustFloatFormat.fixed(totals.worstCrap(), 1) + " · "
                + totals.crappyFunctions() + " at or above " + RustFloatFormat.fixed(Crap.CRAP_THRESHOLD, 0) + " ("
                + RustFloatFormat.fixed(totals.crappyPercent(), 1) + "%) · crap load "
                + RustFloatFormat.fixed(totals.crapLoad(), 1) + "</p>";
    }

    private static final String CRAP_HEAD =
            "<div class=\"crap-list\"><div class=\"crap-row crap-head\"><span>Score</span><span>Complexity</span><span>Coverage</span><span>Crap load</span><span>Function</span><span>Location</span></div>";

    private static String renderCrapChange(CrapChange change) {
        StringBuilder html = new StringBuilder("<section id=\"crap-change\"><h3>On this change</h3>");
        html.append(crapTotalsParagraph(change.totals()));
        if (!change.functions().isEmpty()) {
            html.append(CRAP_HEAD);
            change.functions().forEach(function -> html.append(crapFunctionHtml(function)));
            html.append("</div>");
        }
        html.append("</section>");
        return html.toString();
    }

    static String renderCrapSection(ReportSnapshot snapshot) {
        CrapTotals totals = snapshot.crap().totals();
        StringBuilder html = new StringBuilder("<section id=\"crap\"><h2>CRAP</h2>");
        html.append(
                "<p class=\"muted\">Cyclomatic complexity and the fraction of measured lines in each function. CRAP = complexity^2 * (1 - coverage)^3 + complexity. This is line coverage, not basis-path coverage. The aggregate LCOV percentage is not a CRAP score.</p>");
        if (snapshot.crap().changed() != null) {
            html.append(renderCrapChange(snapshot.crap().changed()));
        }
        html.append(crapTotalsParagraph(totals));
        html.append("<p>complexity ")
                .append(totals.complexity())
                .append(" · total CRAP ")
                .append(RustFloatFormat.fixed(totals.totalCrap(), 1))
                .append(" · mean ")
                .append(RustFloatFormat.fixed(totals.meanCrap(), 1))
                .append(" · median ")
                .append(RustFloatFormat.fixed(totals.medianCrap(), 1))
                .append("</p>");
        if (!snapshot.crap().projects().isEmpty()) {
            html.append(
                    "<div class=\"table-wrap table-scroll\"><table class=\"table\"><thead><tr><th>Project</th><th>Functions</th><th>Complexity</th><th>Total CRAP</th><th>Mean</th><th>Median</th><th>Worst</th><th>At or above 30</th><th>Crap load</th></tr></thead><tbody>");
            for (CrapProjectTotals project : snapshot.crap().projects()) {
                CrapTotals projectTotals = project.totals();
                html.append("<tr><td>")
                        .append(htmlEscape(project.project()))
                        .append("</td><td>")
                        .append(projectTotals.functions())
                        .append("</td><td>")
                        .append(projectTotals.complexity())
                        .append("</td><td>")
                        .append(RustFloatFormat.fixed(projectTotals.totalCrap(), 1))
                        .append("</td><td>")
                        .append(RustFloatFormat.fixed(projectTotals.meanCrap(), 1))
                        .append("</td><td>")
                        .append(RustFloatFormat.fixed(projectTotals.medianCrap(), 1))
                        .append("</td><td>")
                        .append(RustFloatFormat.fixed(projectTotals.worstCrap(), 1))
                        .append("</td><td>")
                        .append(projectTotals.crappyFunctions())
                        .append(" (")
                        .append(RustFloatFormat.fixed(projectTotals.crappyPercent(), 1))
                        .append("%)</td><td>")
                        .append(RustFloatFormat.fixed(projectTotals.crapLoad(), 1))
                        .append("</td></tr>");
            }
            html.append("</tbody></table></div>");
        }
        List<CrapFunction> functions = snapshot.crap().functions();
        if (!functions.isEmpty()) {
            int split = Math.min(functions.size(), CRAP_FUNCTIONS_SHOWN);
            List<CrapFunction> shown = functions.subList(0, split);
            List<CrapFunction> rest = functions.subList(split, functions.size());
            html.append(CRAP_HEAD);
            shown.forEach(function -> html.append(crapFunctionHtml(function)));
            if (!rest.isEmpty()) {
                html.append("<details class=\"crap-more\"><summary>")
                        .append(rest.size())
                        .append(" more functions</summary>");
                rest.forEach(function -> html.append(crapFunctionHtml(function)));
                html.append("</details>");
            }
            html.append("</div>");
        }
        html.append("</section>");
        return html.toString();
    }

    private static String crapFunctionHtml(CrapFunction function) {
        String severity = switch (Crap.crapSeverity(function.score())) {
            case CRITICAL -> "crap-critical";
            case HIGH -> "crap-high";
            case MODERATE -> "crap-moderate";
            case LOW -> "crap-low";
        };
        StringBuilder html = new StringBuilder("<details class=\"crap-fn ")
                .append(severity)
                .append("\"><summary class=\"crap-row\"><span class=\"crap-score\">")
                .append(RustFloatFormat.fixed(function.score(), 1))
                .append("</span><span>")
                .append(function.complexity())
                .append("</span><span>")
                .append(RustFloatFormat.fixed(function.coverage() * 100.0, 0))
                .append("%</span><span>")
                .append(RustFloatFormat.fixed(function.crapLoad(), 1))
                .append("</span><span class=\"crap-name\">")
                .append(htmlEscape(function.name()))
                .append("</span><span class=\"crap-location\">")
                .append(htmlEscape(function.path()))
                .append(':')
                .append(function.line())
                .append("</span></summary>");
        if (function.source().isEmpty()) {
            html.append("<p class=\"muted crap-no-source\">source not available</p>");
        } else {
            HighlightLang lang = Highlight.highlightLangForPath(function.path());
            html.append("<div class=\"test-snippet diff\">");
            for (CrapSourceLine line : function.source()) {
                String cssClass = switch (line.measure()) {
                    case COVERED -> "ctx covered";
                    case UNCOVERED -> "ctx uncovered";
                    case NOT_MEASURED -> "ctx";
                };
                String number = Long.toString(line.number());
                String code = Highlight.highlightLinked(lang, line.text(), name -> null);
                html.append(ReportShell.diffRowHtml(cssClass, number, number, "", code));
            }
            html.append("</div>");
        }
        html.append("</details>");
        return html.toString();
    }

    static String buildStatusHtml(ReportSnapshot snapshot) {
        String overall = snapshot.overallOk() ? "OK" : "FAIL";
        Accent overallAccent = snapshot.overallOk() ? Accent.OK : Accent.FAIL;
        StringBuilder line = new StringBuilder(spanAccent("cherry-test " + overall, overallAccent));
        line.append(" | pkg ").append(htmlEscape(snapshot.packagesPassed() + "/" + snapshot.packagesTotal()));
        if (snapshot.packagesFailed() > 0) {
            line.append(' ').append(spanAccent("pkg-fail:" + snapshot.packagesFailed(), Accent.FAIL));
        }
        line.append(" | tests run:").append(htmlEscape(Long.toString(snapshot.integrationRun())));
        if (snapshot.integrationSkipped() > 0) {
            line.append(' ').append(spanAccent("skip:" + snapshot.integrationSkipped(), Accent.SKIP));
        } else {
            line.append(" skip:").append(htmlEscape(Long.toString(snapshot.integrationSkipped())));
        }
        if (snapshot.integrationFailed() > 0) {
            line.append(' ').append(spanAccent("fail:" + snapshot.integrationFailed(), Accent.FAIL));
        } else {
            line.append(" fail:").append(htmlEscape(Long.toString(snapshot.integrationFailed())));
        }
        String itSuccess = snapshot.integrationRun() > 0
                ? RustFloatFormat.fixed(
                                ((double) (snapshot.integrationRun() - snapshot.integrationFailed())
                                                / (double) snapshot.integrationRun())
                                        * 100.0,
                                0)
                        + "%"
                : "-";
        line.append(" ok:").append(htmlEscape(itSuccess));
        if (snapshot.coverageEnabled()) {
            line.append(" | ")
                    .append(covSegmentHtml(
                            "all:",
                            statusPct(snapshot.totals().merged().linesPercent()),
                            Theme.coverageAccent(snapshot.totals().merged().linesPercent())));
            line.append(' ')
                    .append(covSegmentHtml(
                            "unit:",
                            statusPctOpt(snapshot.totals().unit()),
                            Theme.coverageAccentOpt(snapshot.totals().unit())));
            line.append(' ')
                    .append(covSegmentHtml(
                            "it:",
                            statusPctOpt(snapshot.totals().integration()),
                            Theme.coverageAccentOpt(snapshot.totals().integration())));
            line.append(" | diff:")
                    .append(statusDiffLinesHtml(snapshot.diffCoverage().merged()));
        } else {
            line.append(" | cov:-");
        }
        return line.toString();
    }

    private static String statusDiffLinesHtml(CoverageSummary summary) {
        if (summary.linesFound() == 0) {
            return htmlEscape("-");
        }
        return CoverageCell.linesCellHtml(summary);
    }

    private static String covSegmentHtml(String label, String value, Accent accent) {
        return label + spanAccent(value, accent);
    }

    private static String statusPct(double value) {
        return Double.isNaN(value) ? "-" : RustFloatFormat.fixed(value, 1) + "%";
    }

    private static String statusPctOpt(@Nullable CoverageSummary summary) {
        return summary == null ? "-" : statusPct(summary.linesPercent());
    }

    static String renderProjectsTable(ReportSnapshot snapshot) {
        StringBuilder rows = new StringBuilder(
                "<table><thead><tr><th>project</th><th>package</th><th class=\"num cell-detail\">unit</th><th class=\"num cell-detail\">unit_fn</th><th class=\"num cell-detail\">it</th><th class=\"num cell-detail\">it_fn</th><th class=\"num cell-detail\">merged</th><th>status</th></tr></thead><tbody>");
        for (ProjectReport project : snapshot.projects()) {
            String status = project.testsPassed() ? "ok" : "FAIL";
            String statusCell = spanAccent(status, Theme.statusAccent(status));
            CoverageTotals diff = project.diffCoverage();
            rows.append("<tr><td>")
                    .append(htmlEscape(project.projectId()))
                    .append("</td><td>")
                    .append(htmlEscape(project.packageName()))
                    .append("</td><td class=\"num cell-detail\">")
                    .append(CoverageCell.linesCellHtml(diff.unit()))
                    .append("</td><td class=\"num cell-detail\">")
                    .append(CoverageCell.fnCellHtml(diff.unit()))
                    .append("</td><td class=\"num cell-detail\">")
                    .append(CoverageCell.linesCellHtml(diff.integration()))
                    .append("</td><td class=\"num cell-detail\">")
                    .append(CoverageCell.fnCellHtml(diff.integration()))
                    .append("</td><td class=\"num cell-detail\">")
                    .append(CoverageCell.linesCellHtml(diff.merged()))
                    .append("</td><td>")
                    .append(statusCell)
                    .append("</td></tr>");
        }
        if (snapshot.coverageEnabled()) {
            CoverageTotals diff = snapshot.diffCoverage();
            String overall = snapshot.overallOk() ? "ok" : "FAIL";
            String overallCell = spanAccent(overall, Theme.statusAccent(overall));
            rows.append("<tr><td>ALL</td><td>(changed)</td><td class=\"num cell-detail\">")
                    .append(CoverageCell.linesCellHtml(diff.unit()))
                    .append("</td><td class=\"num cell-detail\">")
                    .append(CoverageCell.fnCellHtml(diff.unit()))
                    .append("</td><td class=\"num cell-detail\">")
                    .append(CoverageCell.linesCellHtml(diff.integration()))
                    .append("</td><td class=\"num cell-detail\">")
                    .append(CoverageCell.fnCellHtml(diff.integration()))
                    .append("</td><td class=\"num cell-detail\">")
                    .append(CoverageCell.linesCellHtml(diff.merged()))
                    .append("</td><td>")
                    .append(overallCell)
                    .append("</td></tr>");
        }
        rows.append("</tbody></table>");
        return "<div class=\"table-wrap table-scroll\">" + rows + "</div>";
    }

    private static String renderTestsSection(ReportSnapshot snapshot, JumpIndex jumps) {
        TestsReport report = snapshot.testsReport();
        if (report.projects().isEmpty()) {
            return "<section id=\"tests\"><h2>tests</h2><p class=\"muted\">no integration tests recorded</p></section>";
        }
        StringBuilder html = new StringBuilder("<section id=\"tests\" data-view=\"grouped\"><h2>tests</h2>");
        html.append("<div class=\"test-toolbar\">");
        html.append(
                "<button type=\"button\" id=\"test-view-toggle\" aria-label=\"toggle grouped and flat test views\">grouped</button>");
        html.append("<input type=\"search\" id=\"test-filter\" placeholder=\"filter text or tag\">");
        html.append("<select id=\"test-tag\"><option value=\"\">all tags</option>");
        for (String tag : report.allTags()) {
            html.append("<option value=\"")
                    .append(htmlEscape(tag))
                    .append("\">")
                    .append(htmlEscape(tag))
                    .append("</option>");
        }
        html.append("</select>");
        html.append(
                "<select id=\"test-status\"><option value=\"all\">all</option><option value=\"fail\">failures</option><option value=\"skip\">skipped</option></select>");
        html.append(
                "<select id=\"test-sort\"><option value=\"default\">default order</option><option value=\"fast\">fastest first</option><option value=\"slow\">slowest first</option></select>");
        html.append("</div>");
        html.append("<div id=\"test-list-grouped\" class=\"test-view\">");
        for (ProjectTestView project : report.projects()) {
            String stats = timingStatsHtml(project.stats());
            String title = foldTitle(
                    project.project(), project.stats().fail(), project.stats().skip());
            html.append("<details class=\"test-project\" data-project=\"")
                    .append(htmlEscape(project.project()))
                    .append('"')
                    .append(ReportOutput.treeDepthOpen(1))
                    .append('>')
                    .append(foldSummary(title, stats));
            for (SuiteTestView suite : project.suites()) {
                String suiteStats = timingStatsHtml(suite.stats());
                String suiteTitle = foldTitle(
                        suite.suite(), suite.stats().fail(), suite.stats().skip());
                html.append("<details class=\"test-suite\" data-suite=\"")
                        .append(htmlEscape(suite.suite()))
                        .append('"')
                        .append(ReportOutput.treeDepthOpen(2))
                        .append('>')
                        .append(foldSummary(suiteTitle, suiteStats));
                for (int order = 0; order < suite.cases().size(); order++) {
                    html.append(renderTestCase(suite.cases().get(order), order, false, jumps));
                }
                html.append("</details>");
            }
            html.append("</details>");
        }
        html.append("</div><div id=\"test-list-flat\" class=\"test-view\">");
        long flatOrder = 0;
        for (ProjectTestView project : report.projects()) {
            for (SuiteTestView suite : project.suites()) {
                for (TestCaseView testCase : suite.cases()) {
                    html.append(renderTestCase(testCase, flatOrder, true, jumps));
                    flatOrder++;
                }
            }
        }
        html.append("</div></section>");
        html.append(renderChangedTestsSection(snapshot, jumps));
        return html.toString();
    }

    private static String renderChangedTestsSection(ReportSnapshot snapshot, JumpIndex jumps) {
        TestsReport changed = TestView.testsInChange(snapshot.testsReport(), changedPaths(snapshot));
        if (changed.projects().isEmpty()) {
            return "";
        }
        StringBuilder html = new StringBuilder("<section id=\"tests-change\"><h2>Tests on this change</h2>");
        html.append("<p class=\"muted\">Recorded tests whose source file is in the diff.</p>");
        for (ProjectTestView project : changed.projects()) {
            for (SuiteTestView suite : project.suites()) {
                for (int order = 0; order < suite.cases().size(); order++) {
                    html.append(renderTestCase(suite.cases().get(order), order, true, jumps));
                }
            }
        }
        html.append("</section>");
        return html.toString();
    }

    private static List<String> changedPaths(ReportSnapshot snapshot) {
        List<String> paths = new ArrayList<>();
        for (ProjectDiffSection project : snapshot.projectDiffSections()) {
            for (ModuleDiffSection module : project.modules()) {
                for (FileDiffSection file : module.files()) {
                    if (file.adds() > 0 || file.dels() > 0 || file.unparsedDiff()) {
                        paths.add(file.path());
                    }
                }
            }
        }
        return paths;
    }

    private static String testCaseScopeTargetHtml(TestCaseView testCase) {
        return " <span class=\"test-meta\">· scope " + htmlEscape(testCase.scope()) + " · target "
                + htmlEscape(testCase.target()) + "</span>";
    }

    private static String flatTestTitleHtml(TestCaseView testCase) {
        return "<span class=\"test-path\">" + htmlEscape(testCase.project()) + " &gt; " + htmlEscape(testCase.suite())
                + " &gt;</span> " + htmlEscape(testCase.test()) + testCaseScopeTargetHtml(testCase);
    }

    private static String groupedTestTitleHtml(TestCaseView testCase) {
        return htmlEscape(testCase.test()) + testCaseScopeTargetHtml(testCase);
    }

    private static String failureWhyHtml(String reason) {
        return "<span class=\"why\"><button type=\"button\" class=\"flag bang\" aria-label=\"failed\">!</button><button type=\"button\" class=\"flag ask\" aria-expanded=\"false\" aria-label=\"why this failed\">?</button><span class=\"why-pop\" role=\"tooltip\">"
                + htmlEscape(reason) + "</span></span>";
    }

    private static String failureReasonHtml(String reason, boolean failed) {
        StringBuilder html = new StringBuilder();
        boolean labelled = false;
        for (CodeFrame.ReasonPart part : CodeFrame.splitReason(reason)) {
            switch (part) {
                case CodeFrame.TextPart text -> {
                    html.append("<div class=\"test-reason\">");
                    if (!labelled) {
                        html.append(spanAccent("reason", Accent.NEUTRAL)).append(": ");
                        labelled = true;
                    }
                    html.append(htmlEscape(text.text())).append("</div>");
                }
                case CodeFrame.FramePart frame -> {
                    HighlightLang lang;
                    if (frame.source() != null) {
                        lang = Highlight.highlightLangForPath(frame.source());
                    } else {
                        List<String> texts = new ArrayList<>();
                        frame.lines().forEach(line -> texts.add(line.text()));
                        lang = Highlight.snippetLanguage(String.join("\n", texts));
                    }
                    html.append("<div class=\"test-snippet diff")
                            .append(failed ? " fail" : "")
                            .append("\">");
                    for (CodeFrame.FrameLine line : frame.lines()) {
                        String cssClass =
                                line.marked() ? (failed ? "ctx highlight fail-line" : "ctx highlight") : "ctx";
                        String number = line.number() == null ? "" : Long.toString(line.number());
                        String code = line.number() != null
                                ? Highlight.highlightLinked(lang, line.text(), name -> null)
                                : htmlEscape(line.text());
                        html.append(ReportShell.diffRowHtml(cssClass, number, number, "", code));
                    }
                    html.append("</div>");
                }
            }
        }
        return html.toString();
    }

    static String renderTestCase(TestCaseView testCase, long order, boolean flat, JumpIndex jumps) {
        String statusSpan = spanAccent(testCase.statusLabel(), Theme.statusAccent(testCase.statusLabel()));
        String why = testCase.statusLabel().equals("FAIL")
                ? failureWhyHtml(testCase.reason() == null ? "test failed" : testCase.reason())
                : "";
        String elapsed = U64.toString(testCase.elapsedMs());
        String stats =
                why.isEmpty() ? statusSpan + " · " + elapsed + "ms" : why + " " + statusSpan + " · " + elapsed + "ms";
        String tags = TestView.tagsAttr(testCase.tags());
        String label = testCase.test() + " " + testCase.suite() + " " + testCase.project();
        String title = flat ? flatTestTitleHtml(testCase) : groupedTestTitleHtml(testCase);
        String caseClass = flat ? "test-case test-case-flat" : "test-case";
        StringBuilder html = new StringBuilder("<details class=\"")
                .append(caseClass)
                .append("\" data-status=\"")
                .append(htmlEscape(testCase.statusLabel()))
                .append("\" data-ms=\"")
                .append(elapsed)
                .append("\" data-order=\"")
                .append(order)
                .append("\" data-tags=\"")
                .append(htmlEscape(tags))
                .append("\" data-label=\"")
                .append(htmlEscape(label))
                .append("\">")
                .append(foldSummary(title, stats));
        StringBuilder detail = new StringBuilder();
        if ((testCase.statusLabel().equals("skipped") || testCase.statusLabel().equals("FAIL"))
                && testCase.reason() != null) {
            detail.append(
                    failureReasonHtml(testCase.reason(), testCase.statusLabel().equals("FAIL")));
        }
        if (testCase.statusLabel().equals("FAIL")
                && (!testCase.method().isEmpty() || !testCase.url().isEmpty())) {
            detail.append("<div class=\"test-meta\">")
                    .append(htmlEscape(testCase.method() + " " + testCase.url()))
                    .append(" · HTTP ")
                    .append(htmlEscape(testCase.http()))
                    .append("</div>");
        }
        if (!testCase.snippet().isEmpty()) {
            detail.append("<div class=\"test-snippet diff")
                    .append(testCase.statusLabel().equals("FAIL") ? " fail" : "")
                    .append("\">");
            List<String> texts = new ArrayList<>();
            testCase.snippet().forEach(line -> texts.add(line.text()));
            HighlightLang lang = testCase.snippetPath() != null
                    ? Highlight.highlightLangForPath(testCase.snippetPath().toString())
                    : Highlight.snippetLanguage(String.join("\n", texts));
            for (SnippetLine line : testCase.snippet()) {
                String highlight = line.highlight() && testCase.statusLabel().equals("FAIL")
                        ? " highlight fail-line"
                        : line.highlight() ? " highlight" : "";
                String number = Long.toString(line.lineNo());
                String code =
                        Highlight.highlightLinked(lang, line.text(), name -> jumps.href(null, "", line.lineNo(), name));
                detail.append(ReportShell.diffRowHtml("ctx" + highlight, number, number, "", code));
            }
            detail.append("</div>");
        }
        if (!detail.isEmpty()) {
            html.append("<div class=\"test-detail\">").append(detail).append("</div>");
        }
        html.append("</details>");
        return html.toString();
    }

    private static String renderProjectSections(ReportSnapshot snapshot, JumpIndex jumps) {
        if (snapshot.projectDiffSections().isEmpty()) {
            return "<section><h2>changes</h2><p class=\"muted\">no changed files</p></section>";
        }
        StringBuilder html = new StringBuilder("<section id=\"changes\"><h2>changes</h2>");
        for (ProjectDiffSection project : snapshot.projectDiffSections()) {
            html.append(renderProjectDiff(snapshot, project, jumps));
        }
        html.append("</section>");
        return html.toString();
    }

    private static @Nullable CoverageSummary mergedDiffCoverageForProject(ReportSnapshot snapshot, String projectId) {
        long hit = 0;
        long found = 0;
        long functionsHit = 0;
        long functionsFound = 0;
        boolean any = false;
        for (ProjectReport project : snapshot.projects()) {
            if (!project.projectId().equals(projectId)) {
                continue;
            }
            CoverageSummary merged = project.diffCoverage().merged();
            if (merged.linesFound() == 0) {
                continue;
            }
            any = true;
            hit += merged.linesHit();
            found += merged.linesFound();
            functionsHit += merged.functionsHit();
            functionsFound += merged.functionsFound();
        }
        return any ? new CoverageSummary(hit, found, functionsHit, functionsFound) : null;
    }

    private static String projectDiffCovStatsHtml(ReportSnapshot snapshot, ProjectDiffSection project) {
        CoverageSummary summary = mergedDiffCoverageForProject(snapshot, project.projectId());
        if (summary != null) {
            return CoverageCell.linesCellHtml(summary);
        }
        return diffCoverageStatsHtml(project.coveragePct(), project.uncoveredPct());
    }

    private static String renderProjectDiff(ReportSnapshot snapshot, ProjectDiffSection project, JumpIndex jumps) {
        String status = project.testsPassed() ? "ok" : "FAIL";
        String statusSpan = spanAccent(status, Theme.statusAccent(status));
        String covStats = projectDiffCovStatsHtml(snapshot, project);
        String stats = project.fileCount() + " files · " + covStats + " · " + statusSpan;
        StringBuilder html = new StringBuilder("<details class=\"project\" id=\"project-")
                .append(htmlEscape(project.projectId()))
                .append('"')
                .append(ReportOutput.treeDepthOpen(1))
                .append('>')
                .append(foldSummary(htmlEscape(project.projectId()), stats));
        if (project.modules().isEmpty()) {
            html.append("<p class=\"muted\">no changed files</p>");
        } else {
            project.modules().forEach(module -> html.append(renderModuleDiff(module, jumps)));
        }
        html.append("</details>");
        return html.toString();
    }

    private static String renderModuleDiff(ModuleDiffSection module, JumpIndex jumps) {
        String covStats = diffCoverageStatsHtml(module.coveragePct(), module.uncoveredPct());
        String stats = module.fileCount() + " files · " + covStats;
        StringBuilder html = new StringBuilder("<details class=\"module\"")
                .append(ReportOutput.treeDepthOpen(2))
                .append('>')
                .append(foldSummary(htmlEscape(module.path()), stats));
        module.files().forEach(file -> html.append(renderFileDiff(file, jumps)));
        html.append("</details>");
        return html.toString();
    }

    private static String renderFileDiff(FileDiffSection file, JumpIndex jumps) {
        String covStats = diffCoverageStatsHtml(file.coveragePct(), file.uncoveredPct());
        String failBadge = file.failLines() > 0 ? " · " + spanAccent(file.failLines() + " fail", Accent.FAIL) : "";
        String stats = "+" + file.adds() + " -" + file.dels() + " · " + covStats + failBadge;
        StringBuilder html = new StringBuilder("<details class=\"file\"")
                .append(ReportOutput.treeDepthOpen(3))
                .append('>')
                .append(foldSummary(htmlEscape(file.path()), stats));
        if (file.hunks().isEmpty()) {
            if (file.unparsedDiff()) {
                html.append("<p class=\"muted\">binary or unparsed diff</p>");
            }
        } else {
            html.append("<div class=\"diff\">");
            html.append(ReportShell.diffRowHtml("column-labels", "old", "new", "±", "code"));
            for (int index = 0; index < file.hunks().size(); index++) {
                if (index > 0) {
                    html.append(ReportShell.diffRowHtml("ellipsis", "", "", "", "…"));
                }
                HighlightLang lang = Highlight.highlightLangForPath(file.path());
                for (DiffLine line : file.hunks().get(index).lines()) {
                    html.append(renderDiffLine(file.path(), lang, line, jumps));
                }
            }
            html.append("</div>");
        }
        html.append("</details>");
        return html.toString();
    }

    static String renderDiffLine(String path, HighlightLang lang, DiffLine line, JumpIndex jumps) {
        String mark = switch (line.tag()) {
            case ADD -> "++";
            case DEL -> "--";
            case CONTEXT -> "";
        };
        String tagClass = switch (line.tag()) {
            case ADD -> "add";
            case DEL -> "del";
            case CONTEXT -> "ctx";
        };
        String covClass = switch (line.coverage()) {
            case COVERED -> " covered";
            case UNCOVERED -> " uncovered";
            case FAIL -> " uncovered fail-line";
            case NA -> "";
        };
        String oldNumber = line.oldNo() == null ? "" : Long.toString(line.oldNo());
        String newNumber = line.newNo() == null ? "" : Long.toString(line.newNo());
        long lineNo = line.newNo() == null ? 0 : line.newNo();
        String code = Highlight.highlightLinked(lang, line.text(), name -> jumps.href(null, path, lineNo, name));
        return ReportShell.diffRowHtml(tagClass + covClass, oldNumber, newNumber, mark, code);
    }

    private static String spanAccent(String text, Accent accent) {
        return "<span class=\"" + Theme.htmlClass(accent) + "\">" + htmlEscape(text) + "</span>";
    }

    private static String diffCoverageStatsHtml(double coveredPct, double uncoveredPct) {
        String[] pair = DiffView.formatCoveredUncoveredPair(coveredPct, uncoveredPct);
        return spanAccent(pair[0], Accent.COVERED) + " covered / " + spanAccent(pair[1], Accent.UNCOVERED)
                + " uncovered";
    }

    private static String foldTitle(String name, long fail, long skip) {
        Accent accent = Theme.suiteAccent(fail, skip);
        return accent == Accent.OK ? htmlEscape(name) : spanAccent(name, accent);
    }

    private static String countToken(long count, String label, Accent accent) {
        String text = count + " " + label;
        return count > 0 ? spanAccent(text, accent) : text;
    }

    private static String timingStatsHtml(TestTimingStats stats) {
        String timing = stats.run() > 0
                ? " · " + stats.totalMs() + "ms total · " + stats.avgMs() + "ms avg · " + stats.minMs()
                        + "ms fastest · " + stats.maxMs() + "ms slowest"
                : "";
        String fail = countToken(stats.fail(), "fail", Accent.FAIL);
        String skip = countToken(stats.skip(), "skip", Accent.SKIP);
        return stats.run() + " run · " + stats.ok() + " ok · " + fail + " · " + skip + timing;
    }

    private static String foldSummary(String title, String stats) {
        return "<summary class=\"fold-summary\"><span class=\"fold-title\">" + title
                + "</span><span class=\"fold-stats\">" + stats + "</span></summary>";
    }

    private static String htmlEscape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
