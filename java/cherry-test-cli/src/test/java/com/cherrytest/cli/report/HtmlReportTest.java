package com.cherrytest.cli.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.cherrytest.cli.DiscoveredTest;
import com.cherrytest.cli.PreflightPlan;
import com.cherrytest.cli.coverage.CoverageSummary;
import com.cherrytest.cli.crap.Crap;
import com.cherrytest.cli.crap.CrapChange;
import com.cherrytest.cli.crap.CrapFunction;
import com.cherrytest.cli.crap.CrapProjectTotals;
import com.cherrytest.cli.crap.CrapReport;
import com.cherrytest.cli.crap.CrapSourceLine;
import com.cherrytest.cli.crap.CrapTotals;
import com.cherrytest.cli.crap.LineMeasure;
import com.cherrytest.common.reportshell.ReportShell;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HtmlReportTest {
    private static String renderHtml(ReportSnapshot snapshot) {
        return HtmlReport.renderReport(snapshot, JumpIndex.empty());
    }

    private static CoverageSummary summary(long linesHit, long linesFound, long functionsHit, long functionsFound) {
        return new CoverageSummary(linesHit, linesFound, functionsHit, functionsFound);
    }

    private static ModuleDiffSection moduleWithFile(String modulePath, FileDiffSection file) {
        return new ModuleDiffSection(
                modulePath, 1, file.coveragePct(), file.uncoveredPct(), file.hasUncovered(), List.of(file));
    }

    private static FileDiffSection file(
            String path,
            long adds,
            long dels,
            double covered,
            double uncovered,
            boolean hasUncovered,
            long failLines,
            List<DiffHunk> hunks) {
        return new FileDiffSection(path, adds, dels, covered, uncovered, hasUncovered, failLines, false, hunks);
    }

    private static ProjectReport project(
            String projectId, String packageName, boolean passed, PackageCoverage coverage) {
        return new ProjectReport(projectId, packageName, passed, coverage, CoverageTotals.EMPTY, List.of());
    }

    private static int count(String text, String needle) {
        Matcher matcher = Pattern.compile(Pattern.quote(needle)).matcher(text);
        int found = 0;
        while (matcher.find()) {
            found++;
        }
        return found;
    }

    @Nested
    class failure_reason {
        private static final String PLAYWRIGHT_REASON = """
                Error: page.goto: Page crashed

                   6 | test(`shows value`, async ({ page }) => {
                >  7 |   await page.goto(url);
                     |              ^
                   8 | });
                    at /opt/mini/polyglot/browser/functional.spec.ts:7:16""";

        private static TestCaseView failedCase(String reason) {
            return failedCase(reason, List.of(), null);
        }

        private static TestCaseView failedCase(String reason, List<SnippetLine> snippet, @Nullable Path snippetPath) {
            return new TestCaseView(
                    "prices",
                    "visual",
                    "vue shows value",
                    "vue shows value",
                    "none",
                    "prices.mini-java-spring-mcap",
                    30882,
                    "FAIL",
                    "-",
                    reason,
                    "",
                    "",
                    List.of(),
                    snippet,
                    snippetPath);
        }

        private static String rendered(String reason) {
            return HtmlReport.renderTestCase(failedCase(reason), 0, false, JumpIndex.empty());
        }

        @Test
        void renders_the_code_frame_as_a_formatted_snippet() {
            assertThat(rendered(PLAYWRIGHT_REASON)).contains("<div class=\"test-snippet diff fail\">");
        }

        @Test
        void highlights_the_code_in_the_frame() {
            assertThat(rendered(PLAYWRIGHT_REASON)).contains("tok-kw");
        }

        @Test
        void marks_the_failing_line_of_the_frame() {
            String html = rendered(PLAYWRIGHT_REASON);
            assertThat(html.substring(html.indexOf("fail-line"))).contains("goto");
        }

        @Test
        void keeps_the_error_message_as_reason_text() {
            assertThat(rendered(PLAYWRIGHT_REASON))
                    .contains("<div class=\"test-reason\"><span class=\"neutral\">reason</span>: Error: page.goto: "
                            + "Page crashed</div>");
        }

        @Test
        void does_not_repeat_the_frame_as_plain_text() {
            assertThat(rendered(PLAYWRIGHT_REASON)).doesNotContain(">  7 |");
        }

        @Test
        void highlights_the_snippet_in_the_language_of_its_file() {
            TestCaseView testCase = failedCase(
                    "listed price did not match",
                    List.of(new SnippetLine(217, "        let mug = products.iter();", true)),
                    Path.of("shop-rs-api/src/application/tests.rs"));
            assertThat(HtmlReport.renderTestCase(testCase, 0, false, JumpIndex.empty()))
                    .contains("<span class=\"tok-kw\">let</span>");
        }

        @Test
        void keeps_a_reason_without_a_frame_unchanged() {
            assertThat(rendered("listed price did not match"))
                    .contains("<div class=\"test-reason\"><span class=\"neutral\">reason</span>: listed price did not "
                            + "match</div>");
        }
    }

    @Test
    void html_all_row_shows_aggregated_counts() {
        Snapshots snapshot = new Snapshots();
        snapshot.packagesPassed = 1;
        snapshot.packagesTotal = 1;
        snapshot.diffCoverage = new CoverageTotals(summary(15, 50, 2, 8), null, summary(15, 50, 0, 0));
        assertThat(HtmlReport.renderProjectsTable(snapshot.build()))
                .contains("(changed)")
                .contains("15/50")
                .contains("35 unc")
                .contains("30.0%");
    }

    @Test
    void html_status_separates_unit_and_it_spans() {
        Snapshots snapshot = new Snapshots();
        snapshot.packagesPassed = 2;
        snapshot.packagesTotal = 2;
        snapshot.totals = new CoverageTotals(summary(406, 1000, 0, 0), summary(0, 10, 0, 0), summary(268, 1000, 0, 0));
        snapshot.diffCoverage = Snapshots.merged(summary(193, 1000, 0, 0));
        assertThat(HtmlReport.buildStatusHtml(snapshot.build()))
                .contains("unit:<span")
                .contains(" it:<span")
                .contains("</span> it:");
    }

    private static IntegrationRow skippedHealthRow() {
        return new IntegrationRow(
                "mini",
                "it",
                "health",
                "mini_project_it_suite_health_test",
                "local",
                "http",
                10,
                "skipped",
                "-",
                "no docker",
                "",
                "",
                TestView.deriveTags("mini", "it", "local", "health"),
                null);
    }

    @Test
    void html_contains_accent_classes() {
        Snapshots snapshot = new Snapshots();
        snapshot.overallOk = false;
        snapshot.packagesTotal = 1;
        snapshot.diffCoverage = Snapshots.merged(summary(1, 4, 0, 0));
        snapshot.projects.add(project(
                "mini", "mini-rs-api", false, new PackageCoverage(summary(1, 4, 0, 0), null, CoverageSummary.ZERO)));
        snapshot.projectDiffSections.add(new ProjectDiffSection(
                "mini",
                false,
                1,
                25.0,
                75.0,
                List.of(moduleWithFile(
                        "mini/mini-rs-api",
                        file(
                                "mini/mini-rs-api/src/lib.rs",
                                3,
                                1,
                                25.0,
                                75.0,
                                true,
                                1,
                                List.of(new DiffHunk(
                                        "@@",
                                        List.of(
                                                new DiffLine(1L, null, DiffTag.DEL, "fn legacy() {}", LineCov.NA),
                                                new DiffLine(null, 1L, DiffTag.ADD, "fn main() {}", LineCov.FAIL),
                                                new DiffLine(null, 2L, DiffTag.ADD, "fn covered() {}", LineCov.COVERED),
                                                new DiffLine(null, 3L, DiffTag.ADD, "fn gap() {}", LineCov.UNCOVERED),
                                                new DiffLine(
                                                        4L,
                                                        4L,
                                                        DiffTag.CONTEXT,
                                                        "fn kept() {}",
                                                        LineCov.COVERED)))))))));
        snapshot.integrationRows.add(skippedHealthRow());
        snapshot.testsReport =
                TestView.buildTestsReport(List.of(skippedHealthRow()), TestViewTest.noPreflight(), Path.of("."));
        assertThat(renderHtml(snapshot.build()))
                .contains("class=\"low\"")
                .contains("class=\"skip\"")
                .contains("row add uncovered fail-line")
                .contains("<span class=\"mark\">++</span>")
                .contains("<span class=\"mark\">--</span>")
                .contains("row add covered")
                .contains("class=\"row add uncovered\"")
                .contains("row ctx covered")
                .contains("aria-label=\"failed line\">!</button>")
                .contains("class=\"tok-kw\">fn</span>")
                .contains("class=\"fail\"")
                .contains("1 fail")
                .contains("<span class=\"fail\">cherry-test FAIL</span>")
                .contains("<td><span class=\"fail\">FAIL</span></td></tr>")
                .doesNotContain("<td>ALL</td></tr>")
                .contains("class=\"covered\"")
                .contains("class=\"uncovered\"")
                .contains(" covered / ")
                .contains(" uncovered");
    }

    @Test
    void html_diff_summary_shows_covered_uncovered_accents() {
        Snapshots snapshot = new Snapshots();
        snapshot.packagesPassed = 1;
        snapshot.packagesTotal = 1;
        snapshot.coverageEnabled = false;
        snapshot.projectDiffSections.add(new ProjectDiffSection(
                "mini",
                true,
                1,
                39.0,
                61.0,
                List.of(moduleWithFile(
                        "mini/mini-rs-api",
                        file("mini/mini-rs-api/src/lib.rs", 45, 0, 39.0, 61.0, true, 0, List.of())))));
        assertThat(renderHtml(snapshot.build()))
                .contains("+45 -0")
                .contains("39%")
                .contains("61%")
                .contains("class=\"covered\">39%</span> covered / <span class=\"uncovered\">61%</span> uncovered");
    }

    @Test
    void html_diff_separates_hunks_with_an_ellipsis() {
        Snapshots snapshot = new Snapshots();
        snapshot.packagesPassed = 1;
        snapshot.packagesTotal = 1;
        snapshot.coverageEnabled = false;
        snapshot.projectDiffSections.add(new ProjectDiffSection(
                "shop",
                true,
                1,
                100.0,
                0.0,
                List.of(moduleWithFile(
                        "shop/shop-rs-api",
                        file(
                                "shop-rs-api/src/lib.rs",
                                1,
                                0,
                                100.0,
                                0.0,
                                false,
                                0,
                                List.of(
                                        new DiffHunk(
                                                "@@ -1 +1 @@",
                                                List.of(new DiffLine(1L, 1L, DiffTag.CONTEXT, "above", LineCov.NA))),
                                        new DiffHunk(
                                                "@@ -8 +8 @@",
                                                List.of(new DiffLine(
                                                        null, 9L, DiffTag.ADD, "covered", LineCov.COVERED)))))))));
        String html = renderHtml(snapshot.build());
        int above = html.indexOf("above");
        int ellipsis = html.indexOf("row ellipsis");
        int covered = html.indexOf("row add covered");
        assertThat(above).isNotNegative().isLessThan(ellipsis);
        assertThat(ellipsis).isLessThan(covered);
        assertThat(html)
                .contains("column-labels")
                .contains("<span class=\"no old\">old</span>")
                .contains("<span class=\"no new\">new</span>")
                .contains(">code</span>")
                .contains("<span class=\"rail run\">run</span>")
                .contains("<span class=\"mark\">++</span>")
                .contains("<span class=\"no old\">1</span><span class=\"no new\">1</span>")
                .contains("<span class=\"no old\"></span><span class=\"no new\">9</span>");
    }

    @Test
    void diff_line_links_a_unique_symbol_to_the_phase_page() {
        JumpIndex jumps = JumpIndex.empty();
        SortedSet<Long> rendered = new TreeSet<>(List.of(4L));
        Highlight.recordRenderedDefinitions(
                jumps, "integration", "shop-rs-api/src/service.rs", "\n\n\nstruct CatalogService;\n", rendered);
        DiffLine line = new DiffLine(null, 9L, DiffTag.ADD, "let service = CatalogService::new();", LineCov.COVERED);
        assertThat(HtmlReport.renderDiffLine("shop-rs-api/src/app.rs", HighlightLang.RUST, line, jumps))
                .contains("href=\"phases.html#cov-integration-shop-rs-api-src-service-rs-L4\"")
                .contains("tok-ty")
                .doesNotContain(" id=\"");
    }

    @Test
    void html_contains_foldable_project_and_coverage_classes() {
        Snapshots snapshot = new Snapshots();
        snapshot.runId = "20260523-143022-a1b2c3";
        snapshot.packagesPassed = 1;
        snapshot.packagesTotal = 1;
        snapshot.coverageEnabled = false;
        snapshot.projects.add(project("mini", "mini-rs-api", true, PackageCoverage.EMPTY));
        snapshot.projectDiffSections.add(new ProjectDiffSection(
                "mini",
                true,
                1,
                50.0,
                50.0,
                List.of(moduleWithFile(
                        "mini/mini-rs-api",
                        file(
                                "mini/mini-rs-api/src/lib.rs",
                                1,
                                0,
                                50.0,
                                50.0,
                                true,
                                0,
                                List.of(new DiffHunk(
                                        "@@",
                                        List.of(new DiffLine(
                                                null, 1L, DiffTag.ADD, "fn main() {}", LineCov.UNCOVERED)))))))));
        String html = renderHtml(snapshot.build());
        assertThat(html)
                .contains("details class=\"project\"")
                .contains("details class=\"module\"")
                .contains("details class=\"file\"")
                .contains("row add uncovered")
                .contains("id=\"projects_summary\"");
        String changes = html.substring(html.indexOf("section id=\"changes\""));
        assertThat(changes)
                .contains("id=\"project-mini\" open>")
                .contains("<details class=\"module\" open>")
                .doesNotContain("<details class=\"file\" open>");
    }

    @Test
    void html_contains_project_section_ids() {
        Snapshots snapshot = new Snapshots();
        snapshot.runId = "20260523-143022-a1b2c3";
        snapshot.packagesPassed = 1;
        snapshot.packagesTotal = 1;
        snapshot.coverageEnabled = false;
        snapshot.projects.add(project("mini", "mini-rs-api", true, PackageCoverage.EMPTY));
        snapshot.projectDiffSections.add(new ProjectDiffSection("mini", true, 0, 0.0, Double.NaN, List.of()));
        assertThat(renderHtml(snapshot.build())).contains("id=\"project-mini\"").contains("id=\"projects_summary\"");
    }

    private static IntegrationRow walletRow(
            String test,
            String fullName,
            String status,
            long elapsedMs,
            String http,
            @Nullable String reason,
            String method,
            String url) {
        return new IntegrationRow(
                "mini",
                "wallet",
                test,
                fullName,
                "mini_wallet",
                "mini.api",
                elapsedMs,
                status,
                http,
                reason,
                method,
                url,
                TestView.deriveTags("mini", "wallet", "mini_wallet", test),
                null);
    }

    @Test
    void html_tests_section_has_toolbar_and_foldable_cases(@TempDir Path directory) throws IOException {
        Path relative = Path.of("mini/mini-rs-api/tests/integration.rs");
        Path source = directory.resolve(relative);
        Files.createDirectories(Objects.requireNonNull(source.getParent()));
        String content = """
                #[cherry_test(scope = "mini_wallet")]
                async fn mini_project_wallet_suite_get_usdt_test() {}
                async fn mini_project_wallet_suite_ping_test() {}
                """;
        Files.writeString(source, content, StandardCharsets.UTF_8);
        PreflightPlan preflight = new PreflightPlan(
                false,
                List.of(),
                List.of(),
                List.of(),
                List.of(
                        new DiscoveredTest(
                                "mini-rs-api",
                                "mini_project_wallet_suite_get_usdt_test",
                                "mini_wallet",
                                null,
                                "mini_wallet",
                                "mini",
                                "wallet",
                                "get_usdt",
                                relative,
                                2),
                        new DiscoveredTest(
                                "mini-rs-api",
                                "mini_project_wallet_suite_ping_test",
                                "mini_wallet",
                                null,
                                "mini_wallet",
                                "mini",
                                "wallet",
                                "ping",
                                relative,
                                3)));
        String getUsdt = "mini_project_wallet_suite_get_usdt_test";
        List<IntegrationRow> rows = List.of(
                walletRow("get_usdt", getUsdt, "FAIL", 42, "500", "HTTP 500: boom", "GET", "/health"),
                walletRow("get_usdt", getUsdt, "skipped", 0, "-", "no docker", "", ""),
                walletRow("ping", "mini_project_wallet_suite_ping_test", "ok", 5, "200", null, "GET", "/health"));
        Snapshots snapshot = new Snapshots();
        snapshot.overallOk = false;
        snapshot.packagesTotal = 1;
        snapshot.integrationRun = 2;
        snapshot.integrationSkipped = 1;
        snapshot.integrationFailed = 1;
        snapshot.coverageEnabled = false;
        snapshot.integrationRows.addAll(rows);
        snapshot.testsReport = TestView.buildTestsReport(rows, preflight, directory);
        String html = renderHtml(snapshot.build());
        assertThat(html)
                .contains("id=\"tests\"")
                .contains("id=\"test-filter\"")
                .contains("id=\"test-view-toggle\"")
                .contains("id=\"test-list-grouped\"")
                .contains("id=\"test-list-flat\"")
                .contains("data-view=\"grouped\"")
                .contains("id=\"test-tag\"")
                .contains("id=\"test-status\"")
                .contains("id=\"test-sort\"")
                .contains("details class=\"test-project\"")
                .contains("details class=\"test-suite\"")
                .contains("details class=\"test-case\"")
                .contains("class=\"fold-summary\"")
                .contains("class=\"fold-stats\"")
                .contains("2 run · 1 ok · <span class=\"fail\">1 fail</span> · <span class=\"skip\">1 skip</span>")
                .contains("<span class=\"fail\">fail:1</span>")
                .contains("<span class=\"fold-title\"><span class=\"fail\">mini</span></span>")
                .contains("<span class=\"fold-title\"><span class=\"fail\">wallet</span></span>")
                .contains("test-snippet")
                .contains("class=\"test-reason\"")
                .contains("class=\"why\"")
                .contains("class=\"flag ask\"")
                .contains("class=\"why-pop\"")
                .contains("no docker")
                .contains("HTTP 500: boom")
                .doesNotContain("id=\"integration\"");
        int flatStart = html.indexOf("id=\"test-list-flat\"");
        String flat = html.substring(flatStart);
        assertThat(flat)
                .contains("class=\"test-path\">mini &gt; wallet &gt;</span> get_usdt")
                .contains("· scope mini_wallet · target mini.api")
                .contains("class=\"test-case test-case-flat\"");
        String grouped = html.substring(0, flatStart);
        assertThat(grouped)
                .contains("class=\"fold-title\">get_usdt <span class=\"test-meta\">· scope mini_wallet · target "
                        + "mini.api</span></span>")
                .doesNotContain("class=\"test-path\">mini &gt; wallet &gt;</span> get_usdt")
                .contains("details class=\"test-project\" data-project=\"mini\" open>")
                .contains("details class=\"test-suite\" data-suite=\"wallet\" open>")
                .doesNotContain("details class=\"test-case\" data-status=\"FAIL\" open>");
    }

    @Test
    void html_changes_groups_files_under_module() {
        Snapshots snapshot = new Snapshots();
        snapshot.packagesPassed = 2;
        snapshot.packagesTotal = 2;
        snapshot.coverageEnabled = false;
        snapshot.projectDiffSections.add(new ProjectDiffSection(
                "mini",
                true,
                2,
                85.0,
                15.0,
                List.of(
                        moduleWithFile(
                                "mini/mini-ts-web-react",
                                file("mini/mini-ts-web-react/src/App.tsx", 10, 0, 80.0, 20.0, true, 0, List.of())),
                        moduleWithFile(
                                "mini/mini-ts-lib-price",
                                file(
                                        "mini/mini-ts-lib-price/src/displayAddressUsdt.ts",
                                        27,
                                        0,
                                        90.0,
                                        10.0,
                                        true,
                                        0,
                                        List.of())))));
        String html = renderHtml(snapshot.build());
        int module = html.indexOf("class=\"fold-title\">mini/mini-ts-web-react</span>");
        int file = html.indexOf("class=\"fold-title\">mini/mini-ts-web-react/src/App.tsx</span>");
        assertThat(module).isNotNegative().isLessThan(file);
        assertThat(html)
                .contains("class=\"fold-title\">mini/mini-ts-lib-price</span>")
                .contains("class=\"fold-title\">mini/mini-ts-lib-price/src/displayAddressUsdt.ts</span>");
    }

    @Test
    void html_fold_summary_splits_title_and_stats() {
        Snapshots snapshot = new Snapshots();
        snapshot.packagesPassed = 1;
        snapshot.packagesTotal = 1;
        snapshot.coverageEnabled = false;
        snapshot.projectDiffSections.add(new ProjectDiffSection(
                "mini",
                true,
                5,
                28.0,
                72.0,
                List.of(moduleWithFile(
                        "mini/mini-rs-lib-mcap",
                        file("mini/mini-rs-lib-mcap/Cargo.toml", 18, 0, 0.0, Double.NaN, true, 0, List.of())))));
        assertThat(renderHtml(snapshot.build()))
                .contains("class=\"fold-summary\"")
                .contains("class=\"fold-title\"")
                .contains("class=\"fold-stats\"")
                .contains(">mini</span>")
                .contains("5 files")
                .contains("mini/mini-rs-lib-mcap/Cargo.toml")
                .contains("+18 -0");
    }

    @Test
    void html_shell_marks_add_del_in_the_gutter() {
        assertThat(ReportShell.REPORT_HTML_STYLES)
                .contains(".diff .row.add .no { background: var(--diff-add-gutter); }")
                .contains(".diff .row.del .no { background: var(--diff-del-gutter); }")
                .contains("--covered: #dbeafe;");
    }

    private static Snapshots emptySnapshot(long packages) {
        Snapshots snapshot = new Snapshots();
        snapshot.packagesPassed = packages;
        snapshot.packagesTotal = packages;
        snapshot.coverageEnabled = false;
        return snapshot;
    }

    @Test
    void html_header_is_compact_bar() {
        assertThat(renderHtml(emptySnapshot(1).build()))
                .contains("header-bar")
                .contains("id=\"theme-toggle\"")
                .doesNotContain(">theme<")
                .doesNotContain("href='#projects_summary'")
                .doesNotContain("href='#tests'")
                .contains("white-space: nowrap");
    }

    @Test
    void html_ignored_file_diff_summary_shows_dash() {
        Snapshots snapshot = emptySnapshot(1);
        snapshot.projectDiffSections.add(new ProjectDiffSection(
                "mini",
                true,
                1,
                Double.NaN,
                Double.NaN,
                List.of(moduleWithFile(
                        "mini/mini-rs-lib-mcap",
                        file(
                                "mini/mini-rs-lib-mcap/Cargo.toml",
                                18,
                                0,
                                Double.NaN,
                                Double.NaN,
                                false,
                                0,
                                List.of())))));
        assertThat(renderHtml(snapshot.build()))
                .contains("mini/mini-rs-lib-mcap/Cargo.toml")
                .contains(" covered / ")
                .contains(" uncovered");
    }

    @Test
    void should_open_report_respects_flags() {
        assertThat(HtmlReport.shouldOpenReportWith(true, true, false, false)).isFalse();
        assertThat(HtmlReport.shouldOpenReportWith(false, false, false, false)).isFalse();
        assertThat(HtmlReport.shouldOpenReportWith(false, true, true, false)).isFalse();
        assertThat(HtmlReport.shouldOpenReportWith(false, true, false, true)).isFalse();
        assertThat(HtmlReport.shouldOpenReportWith(false, true, false, false)).isTrue();
    }

    @Test
    void html_report_uses_full_width_layout() {
        assertThat(renderHtml(emptySnapshot(0).build()))
                .contains("max-width: none")
                .contains("width: 100%");
    }

    @Test
    void html_tables_are_wrapped_for_horizontal_overflow() {
        Snapshots snapshot = emptySnapshot(1);
        snapshot.coverageEnabled = true;
        snapshot.projects.add(project("mini", "mini-rs-api", true, PackageCoverage.EMPTY));
        ReportSnapshot built = snapshot.build();
        assertThat(HtmlReport.renderProjectsTable(built)).contains("table-wrap").contains("table-scroll");
        assertThat(renderHtml(built)).contains("class=\"table-wrap table-scroll\"");
    }

    private static ReportSnapshot snapshotWithCrap(CrapReport crap) {
        Snapshots snapshot = new Snapshots();
        snapshot.packagesPassed = 1;
        snapshot.packagesTotal = 1;
        snapshot.crap = crap;
        return snapshot.build();
    }

    private static CrapFunction crapFunction(
            String path,
            String name,
            long line,
            long complexity,
            long covered,
            double coverage,
            double score,
            double load,
            List<CrapSourceLine> source) {
        return new CrapFunction(
                path, name, line, complexity, covered, Math.max(1, covered), coverage, score, load, source);
    }

    @Test
    void html_crap_section_shows_totals_only_when_enabled() {
        assertThat(renderHtml(snapshotWithCrap(CrapReport.DISABLED))).doesNotContain("id=\"crap\"");
        List<CrapFunction> functions = new ArrayList<>();
        functions.add(
                new CrapFunction("a&b.rs", "<price>", 4, 10, 0, 4, 0.0, 110.0, Crap.crapLoad(10, 0.0), List.of()));
        for (int index = 0; index < 30; index++) {
            functions.add(crapFunction("src/low" + index + ".rs", "low" + index, 1, 1, 1, 1.0, 1.0, 0.0, List.of()));
        }
        CrapTotals totals = Crap.crapTotals(functions);
        String html = renderHtml(snapshotWithCrap(
                new CrapReport(true, functions, totals, List.of(new CrapProjectTotals("shop", totals)), null)));
        assertThat(html)
                .contains("id=\"crap\"")
                .contains("line coverage, not basis-path coverage")
                .contains("The aggregate LCOV percentage is not a CRAP score.")
                .contains("31 functions")
                .contains("at or above 30")
                .contains("crap load 10.3")
                .contains("complexity 40")
                .contains("total CRAP 140.0")
                .contains("mean 4.5")
                .contains("median 1.0")
                .contains(">Crap load<")
                .contains("&lt;price&gt;")
                .contains("a&amp;b.rs:4")
                .contains(">shop<")
                .contains("1 more functions")
                .doesNotContain("<price>");
    }

    @Nested
    class crap_section {
        private static CrapFunction scored(String name, double score) {
            return new CrapFunction(
                    "shop-rs-api/src/domain/model.rs",
                    name,
                    2,
                    3,
                    1,
                    2,
                    0.5,
                    score,
                    0.0,
                    List.of(
                            new CrapSourceLine(2, "fn price(x: u32) -> u32 {", LineMeasure.COVERED),
                            new CrapSourceLine(3, "    if x > 1 { 2 } else { 3 }", LineMeasure.UNCOVERED)));
        }

        private static String rendered(List<CrapFunction> functions) {
            return renderHtml(
                    snapshotWithCrap(new CrapReport(true, functions, Crap.crapTotals(functions), List.of(), null)));
        }

        private static List<CrapFunction> many(int count) {
            List<CrapFunction> functions = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                functions.add(scored("f" + index, 1.0));
            }
            return functions;
        }

        @Test
        void shows_the_changed_functions_before_the_workspace_list() {
            CrapFunction changed = scored("parse", 4.0);
            CrapFunction other = new CrapFunction("src/other.rs", "other", 1, 1, 1, 1, 1.0, 1.0, 0.0, List.of());
            List<CrapFunction> functions = List.of(changed, other);
            String html = renderHtml(snapshotWithCrap(new CrapReport(
                    true,
                    functions,
                    Crap.crapTotals(functions),
                    List.of(),
                    new CrapChange(List.of(changed), Crap.crapTotals(List.of(changed))))));
            int change = html.indexOf("<section id=\"crap-change\">");
            int end = html.indexOf("</section>", change);
            String block = html.substring(change, end);
            assertThat(block).contains("On this change").contains(">parse<").doesNotContain(">other<");
            assertThat(html.substring(end)).contains(">other<");
        }

        @Test
        void comes_after_every_other_section() {
            String html = rendered(List.of(scored("price", 40.0)));
            int crap = html.indexOf("<section id=\"crap\"");
            assertThat(html.substring(crap)).doesNotContain("<section id=\"tests\"");
            assertThat(html.indexOf("id=\"projects_summary\"")).isLessThan(crap);
        }

        @Test
        void expands_each_function_to_its_code() {
            String html = rendered(List.of(scored("price", 40.0)));
            String row = html.substring(html.indexOf("<details class=\"crap-fn crap-critical\""));
            assertThat(row).contains("class=\"test-snippet diff\"").contains("u32");
        }

        @Test
        void marks_covered_and_uncovered_lines_of_the_function() {
            String html = rendered(List.of(scored("price", 40.0)));
            String row = html.substring(html.indexOf("<details class=\"crap-fn"));
            assertThat(row).contains("ctx covered").contains("ctx uncovered");
        }

        @Test
        void colors_rows_by_severity() {
            String html = rendered(List.of(scored("a", 40.0), scored("b", 20.0), scored("c", 6.0), scored("d", 1.0)));
            for (String cssClass : List.of("crap-critical", "crap-high", "crap-moderate", "crap-low")) {
                assertThat(html).as(cssClass).contains("class=\"crap-fn " + cssClass + "\"");
            }
        }

        @Test
        void lists_the_remaining_functions_in_a_collapsed_section() {
            String html = rendered(many(32));
            int more = html.indexOf("<details class=\"crap-more\"><summary>2 more functions</summary>");
            assertThat(more).isNotNegative();
            assertThat(count(html.substring(more), "<details class=\"crap-fn")).isEqualTo(2);
        }

        @Test
        void shows_every_function() {
            assertThat(count(rendered(many(32)), "<details class=\"crap-fn")).isEqualTo(32);
        }
    }
}
