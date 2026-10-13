package com.cherrytest.cli.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.cherrytest.cli.adapters.Formats;
import com.cherrytest.cli.coverage.CoverageSummary;
import com.cherrytest.cli.crap.Crap;
import com.cherrytest.cli.crap.CrapChange;
import com.cherrytest.cli.crap.CrapFunction;
import com.cherrytest.cli.crap.CrapProjectTotals;
import com.cherrytest.cli.crap.CrapReport;
import com.cherrytest.cli.crap.CrapSourceLine;
import com.cherrytest.cli.crap.LineMeasure;
import com.cherrytest.common.adapter.TestResult;
import com.cherrytest.common.adapter.TestStatus;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class TerminalReportTest {
    @Nested
    class phase_results {
        private static TestResult result(String name, TestStatus status, @Nullable String message, long ms) {
            return Formats.testCase(name, status, message, ms);
        }

        private static List<TestResult> sample() {
            return List.of(
                    result("application::tests::catalog_service::lists_products", TestStatus.PASSED, null, 3),
                    result(
                            "application::tests::catalog_service::reports_a_price_mismatch",
                            TestStatus.FAILED,
                            "assertion `left == right` failed: listed price did not match the expected 1500\n  left: 1800",
                            1),
                    result(
                            "application::tests::checkout::rejects_empty_cart",
                            TestStatus.SKIPPED,
                            "database unavailable",
                            0));
        }

        private static int indexOf(List<String> lines, String fragment) {
            for (int index = 0; index < lines.size(); index++) {
                if (lines.get(index).contains(fragment)) {
                    return index;
                }
            }
            throw new AssertionError("no line contains " + fragment);
        }

        @Test
        void groups_tests_under_their_suite() {
            List<String> lines = TerminalReport.formatPhaseResults(sample(), false);
            assertThat(lines.getFirst()).isEqualTo("  catalog_service");
            assertThat(lines).contains("  checkout");
        }

        @Test
        void shows_result_and_time_for_each_test() {
            List<String> lines = TerminalReport.formatPhaseResults(sample(), false);
            String passed = lines.get(indexOf(lines, "lists_products"));
            assertThat(passed)
                    .startsWith("    ✓ lists_products")
                    .contains(" ok ")
                    .endsWith(" 3ms");
        }

        @Test
        void shows_the_first_line_of_a_failure_reason() {
            List<String> lines = TerminalReport.formatPhaseResults(sample(), false);
            int index = indexOf(lines, "reports_a_price_mismatch");
            assertThat(lines.get(index)).contains("FAIL");
            assertThat(lines.get(index + 1))
                    .isEqualTo(
                            "      ! assertion `left == right` failed: listed price did not match the expected 1500");
        }

        @Test
        void shows_why_a_test_was_skipped() {
            List<String> lines = TerminalReport.formatPhaseResults(sample(), false);
            int index = indexOf(lines, "rejects_empty_cart");
            assertThat(lines.get(index)).contains("skipped");
            assertThat(lines.get(index + 1)).isEqualTo("      - database unavailable");
        }

        @Test
        void ends_with_the_phase_totals() {
            assertThat(TerminalReport.formatPhaseResults(sample(), false).getLast())
                    .isEqualTo("  3 tests · 1 ok · 1 failed · 1 skipped · 4ms");
        }

        @Test
        void keeps_every_line_within_the_terminal_width() {
            String longName = "n".repeat(300);
            assertThat(TerminalReport.formatPhaseResults(
                            List.of(result(longName, TestStatus.FAILED, longName, 2)), false))
                    .allMatch(line -> TerminalReport.visibleWidth(line) <= TerminalReport.VERBOSE_WIDTH);
        }

        @Test
        void says_when_a_phase_reported_no_tests() {
            assertThat(TerminalReport.formatPhaseResults(List.of(), false)).containsExactly("  no tests reported");
        }
    }

    private static CoverageSummary sample_summary(
            long linesHit, long linesFound, long functionsHit, long functionsFound) {
        return new CoverageSummary(linesHit, linesFound, functionsHit, functionsFound);
    }

    private static IntegrationRow walletRow(String target, String url) {
        return new IntegrationRow(
                "mini",
                "wallet",
                "get_address_value_usdt",
                "mini_project_wallet_suite_get_address_value_usdt_test",
                "mini_wallet",
                target,
                2,
                "ok",
                "200",
                null,
                "GET",
                url,
                List.of(),
                null);
    }

    private static ReportSnapshot sample_snapshot() {
        Snapshots snapshot = new Snapshots();
        snapshot.runId = "20260523-064607-178a68";
        snapshot.packagesPassed = 2;
        snapshot.packagesTotal = 2;
        snapshot.integrationRun = 2;
        snapshot.totals = new CoverageTotals(
                sample_summary(406, 1000, 0, 0), sample_summary(0, 330, 0, 80), sample_summary(268, 1000, 0, 0));
        snapshot.diffCoverage = new CoverageTotals(
                sample_summary(26, 64, 7, 17), sample_summary(0, 33, 0, 8), sample_summary(17, 88, 0, 0));
        snapshot.projects.add(new ProjectReport(
                "mini",
                "mini-rs-lib-mcap",
                true,
                PackageCoverage.EMPTY,
                new CoverageTotals(sample_summary(17, 30, 4, 9), null, sample_summary(17, 30, 0, 0)),
                List.of()));
        snapshot.projects.add(new ProjectReport(
                "mini",
                "mini-rs-api",
                true,
                PackageCoverage.EMPTY,
                new CoverageTotals(
                        sample_summary(9, 34, 3, 8), sample_summary(0, 33, 0, 8), sample_summary(0, 58, 0, 0)),
                List.of()));
        snapshot.integrationRows.add(walletRow("mini.mcap", "/x"));
        snapshot.integrationRows.add(walletRow("mini.api", "/y"));
        snapshot.reportPath = Path.of("target/cherry-test/runs/20260523-064607-178a68/reports/index.html");
        return snapshot.build();
    }

    private static CrapFunction crapFunction(
            String path,
            String name,
            long line,
            long complexity,
            long coveredLines,
            long measuredLines,
            double coverage,
            double score,
            double load,
            List<CrapSourceLine> source) {
        return new CrapFunction(
                path, name, line, complexity, coveredLines, measuredLines, coverage, score, load, source);
    }

    @Test
    void crap_lines_appear_only_when_the_report_is_enabled() {
        assertThat(TerminalReport.formatCrapLines(CrapReport.DISABLED, false)).isEmpty();
        CrapReport enabledEmpty = new CrapReport(true, List.of(), Crap.crapTotals(List.of()), List.of(), null);
        assertThat(TerminalReport.formatCrapLines(enabledEmpty, false))
                .containsExactly(
                        "crap: 0 functions · worst 0.0 · 0 at or above 30 (0.0%) · crap load 0.0",
                        "complexity 0 · total CRAP 0.0 · mean 0.0 · median 0.0");
        List<CrapFunction> functions = List.of(crapFunction(
                "src/price.rs",
                "price",
                4,
                10,
                0,
                4,
                0.0,
                110.0,
                Crap.crapLoad(10, 0.0),
                List.of(new CrapSourceLine(5, "    cents * 2", LineMeasure.UNCOVERED))));
        CrapReport report = new CrapReport(
                true,
                functions,
                Crap.crapTotals(functions),
                List.of(new CrapProjectTotals("shop", Crap.crapTotals(functions))),
                null);
        List<String> lines = TerminalReport.formatCrapLines(report, false);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0))
                .isEqualTo("crap: 1 functions · worst 110.0 · 1 at or above 30 (100.0%) · crap load 10.3");
        assertThat(lines.get(1)).isEqualTo("  110.0  price             price.rs:4  cents * 2");
        assertThat(lines).allMatch(line -> TerminalReport.visibleWidth(line) <= TerminalReport.VERBOSE_WIDTH);
    }

    @Test
    void crap_summary_uses_totals_for_functions_past_the_table() {
        List<CrapFunction> functions = new ArrayList<>();
        for (int index = 0; index < 30; index++) {
            functions.add(crapFunction("src/low" + index + ".rs", "low" + index, 1, 1, 1, 1, 1.0, 1.0, 0.0, List.of()));
        }
        functions.add(crapFunction("src/high.rs", "high", 1, 10, 0, 1, 0.0, 110.0, Crap.crapLoad(10, 0.0), List.of()));
        CrapReport report = new CrapReport(true, functions, Crap.crapTotals(functions), List.of(), null);
        List<String> lines = TerminalReport.formatCrapLines(report, false);
        assertThat(lines.get(0))
                .isEqualTo("crap: 31 functions · worst 110.0 · 1 at or above 30 (3.2%) · crap load 10.3");
        assertThat(lines.get(1)).contains("high").contains("high.rs:1").doesNotContain("src/");
        assertThat(lines).noneMatch(line -> line.contains("more functions"));
        assertThat(lines).hasSize(5);
    }

    @Test
    void crap_focus_line_keeps_the_uncovered_source_line() {
        String snippet = "Self::InvalidMoney => write!(formatter, \"amount must not be negative\"),";
        List<CrapFunction> functions = List.of(crapFunction(
                "shop-rs-api/src/domain/model.rs",
                "fmt",
                24,
                2,
                1,
                4,
                0.25,
                12.2,
                0.0,
                List.of(
                        new CrapSourceLine(
                                22,
                                "    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {",
                                LineMeasure.COVERED),
                        new CrapSourceLine(24, "            " + snippet, LineMeasure.UNCOVERED))));
        CrapReport report = new CrapReport(true, functions, Crap.crapTotals(functions), List.of(), null);
        List<String> lines = TerminalReport.formatCrapLines(report, false);
        assertThat(lines.get(1))
                .isEqualTo("   12.2  fmt    model.rs:24  " + snippet)
                .doesNotContain("shop-rs-api");
        assertThat(TerminalReport.visibleWidth(lines.get(1))).isLessThanOrEqualTo(TerminalReport.VERBOSE_WIDTH);
        List<String> colored = TerminalReport.formatCrapLines(report, true);
        assertThat(colored.get(1)).contains("\u001b[33m 12.2\u001b[0m").contains(snippet);
    }

    @Test
    void crap_change_line_precedes_the_workspace_totals() {
        List<CrapFunction> changed = List.of(
                crapFunction("shop-rs-api/src/domain/model.rs", "parse", 90, 3, 2, 3, 2.0 / 3.0, 4.0, 0.0, List.of()));
        List<CrapFunction> functions = new ArrayList<>(changed);
        functions.add(
                crapFunction("src/other.rs", "other", 1, 10, 0, 1, 0.0, 110.0, Crap.crapLoad(10, 0.0), List.of()));
        CrapReport report = new CrapReport(
                true,
                functions,
                Crap.crapTotals(functions),
                List.of(),
                new CrapChange(changed, Crap.crapTotals(changed)));
        List<String> lines = TerminalReport.formatCrapLines(report, false);
        assertThat(lines.get(0)).isEqualTo("change: 1 functions · worst 4.0 · 0 at or above 30 (0.0%) · crap load 0.0");
        assertThat(lines.get(1)).startsWith("crap: 2 functions");
    }

    @Test
    void visible_width_ignores_ansi() {
        assertThat(TerminalReport.visibleWidth("ok")).isEqualTo(2);
        assertThat(TerminalReport.visibleWidth(Theme.paint("ok", Accent.OK))).isEqualTo(2);
        assertThat(TerminalReport.visibleWidth(Theme.paintWhen(true, "ok", Accent.OK)))
                .isEqualTo(2);
    }

    @Test
    void format_row_is_exactly_200_chars() {
        assertThat(TerminalReport.visibleWidth(TerminalReport.formatProjectsHeader()))
                .isEqualTo(TerminalReport.TERMINAL_WIDTH);
        ReportSnapshot snapshot = sample_snapshot();
        for (ProjectReport project : snapshot.projects()) {
            assertThat(TerminalReport.visibleWidth(TerminalReport.formatProjectRow(project, false)))
                    .isEqualTo(TerminalReport.TERMINAL_WIDTH);
        }
        assertThat(TerminalReport.visibleWidth(TerminalReport.formatProjectsFooter(snapshot, false)))
                .isEqualTo(TerminalReport.TERMINAL_WIDTH);
        String status = TerminalReport.formatStatusRow(snapshot);
        assertThat(TerminalReport.visibleWidth(status)).isEqualTo(TerminalReport.TERMINAL_WIDTH);
        assertThat(status.toLowerCase(Locale.ROOT)).doesNotContain("crap");
        assertThat(TerminalReport.visibleWidth(TerminalReport.formatIntegrationHeader()))
                .isEqualTo(TerminalReport.TERMINAL_WIDTH);
        for (IntegrationRow row : snapshot.integrationRows()) {
            assertThat(TerminalReport.visibleWidth(TerminalReport.formatIntegrationRow(row, false)))
                    .isEqualTo(TerminalReport.TERMINAL_WIDTH);
        }
    }

    @Test
    void status_and_project_columns_align() {
        String row =
                TerminalReport.formatProjectRow(sample_snapshot().projects().getFirst(), false);
        assertThat(TerminalReport.visibleWidth(row)).isEqualTo(TerminalReport.TERMINAL_WIDTH);
        assertThat(row)
                .endsWith(TerminalReport.padCell(
                        "ok", TerminalReport.PRJ_STATUS.width(), TerminalReport.PRJ_STATUS.align()));
    }

    @Test
    void truncate_visible_adds_ellipsis() {
        String out = TerminalReport.truncateVisible("hello world", 6);
        assertThat(TerminalReport.visibleWidth(out)).isEqualTo(6);
        assertThat(out).endsWith("…");
    }

    @Test
    void rule_line_width() {
        assertThat(TerminalReport.ruleLine()).hasSize(TerminalReport.TERMINAL_WIDTH);
    }

    private static TestCaseView sample_case(String suite, String test, String status, long elapsedMs) {
        return new TestCaseView(
                "shop",
                suite,
                test,
                test,
                "shop_checkout",
                "shop.api",
                elapsedMs,
                status,
                "200",
                null,
                "GET",
                "/health",
                List.of(),
                List.of(),
                null);
    }

    private static TestTimingStats stats(Object... pairs) {
        List<TestView.StatusTiming> items = new ArrayList<>();
        for (int index = 0; index < pairs.length; index += 2) {
            items.add(new TestView.StatusTiming((String) pairs[index], ((Number) pairs[index + 1]).longValue()));
        }
        return TestView.timingStats(items);
    }

    private static TestsReport sample_tests(List<SuiteTestView> extraSuites, @Nullable String checkoutReason) {
        TestCaseView failed = sample_case("checkout", "rejects_empty", "FAIL", 4);
        if (checkoutReason != null) {
            failed = new TestCaseView(
                    failed.project(),
                    failed.suite(),
                    failed.test(),
                    failed.fullName(),
                    failed.scope(),
                    failed.target(),
                    failed.elapsedMs(),
                    failed.statusLabel(),
                    failed.http(),
                    checkoutReason,
                    failed.method(),
                    failed.url(),
                    failed.tags(),
                    failed.snippet(),
                    failed.snippetPath());
        }
        List<SuiteTestView> suites = new ArrayList<>(List.of(
                new SuiteTestView("cart", stats("ok", 12), List.of(sample_case("cart", "adds_line", "ok", 12))),
                new SuiteTestView("checkout", stats("FAIL", 4), List.of(failed)),
                new SuiteTestView(
                        "stock",
                        stats("skipped", 0),
                        List.of(sample_case("stock", "waits_for_docker", "skipped", 0)))));
        suites.addAll(extraSuites);
        return new TestsReport(
                List.of(new ProjectTestView("shop", stats("ok", 12, "skipped", 0, "FAIL", 4), suites)), List.of());
    }

    @Test
    void grouped_tests_color_each_suite() {
        List<String> lines = TerminalReport.formatGroupedTestLines(sample_tests(List.of(), null), true);
        assertThat(lines)
                .anyMatch(line -> line.contains("\u001b[32mcart\u001b[0m"))
                .anyMatch(line -> line.contains("\u001b[33mstock\u001b[0m"))
                .anyMatch(line -> line.contains("\u001b[31mcheckout\u001b[0m"))
                .anyMatch(line -> line.contains("adds_line") && line.contains("\u001b[32mok\u001b[0m"))
                .anyMatch(line -> line.contains("waits_for_docker") && line.contains("\u001b[33mskipped\u001b[0m"))
                .anyMatch(line -> line.contains("rejects_empty") && line.contains("\u001b[31mFAIL\u001b[0m"))
                .allMatch(line -> TerminalReport.visibleWidth(line) <= TerminalReport.TERMINAL_WIDTH);
        int cartAt = -1;
        int caseAt = -1;
        for (int index = 0; index < lines.size(); index++) {
            if (cartAt < 0 && lines.get(index).contains("cart")) {
                cartAt = index;
            }
            if (caseAt < 0 && lines.get(index).contains("adds_line")) {
                caseAt = index;
            }
        }
        assertThat(caseAt).isGreaterThan(cartAt);
    }

    @Test
    void mini_report_lists_suites_and_the_total() {
        List<String> lines = TerminalReport.formatMiniReportLines(sample_tests(List.of(), null), true);
        assertThat(lines).hasSize(4);
        assertThat(lines.get(0)).contains("\u001b[32mcart\u001b[0m").contains("1 run · 1 ok · 0 fail · 0 skip");
        assertThat(lines.get(1)).contains("\u001b[31mcheckout\u001b[0m");
        assertThat(lines.get(2)).contains("\u001b[33mstock\u001b[0m").contains("0 run · 0 ok · 0 fail · 1 skip");
        assertThat(lines.get(3))
                .contains("all")
                .contains("\u001b[31mall\u001b[0m")
                .contains("2 run · 1 ok · 1 fail · 1 skip")
                .contains("16ms total");
        assertThat(lines).allMatch(line -> TerminalReport.visibleWidth(line) <= TerminalReport.TERMINAL_WIDTH);
    }

    @Test
    void verbose_lines_group_suites_and_mark_failures() {
        TestCaseView unit =
                sample_case("unit", "shop-rs-api: application::tests::catalog_service::lists_products", "ok", 2);
        TestCaseView listed = new TestCaseView(
                unit.project(),
                unit.suite(),
                unit.test(),
                unit.fullName(),
                "none",
                "shop.shop-rs-api",
                unit.elapsedMs(),
                unit.statusLabel(),
                unit.http(),
                unit.reason(),
                unit.method(),
                unit.url(),
                unit.tags(),
                unit.snippet(),
                unit.snippetPath());
        TestsReport report = sample_tests(
                List.of(new SuiteTestView("unit", stats("ok", 2), List.of(listed))),
                "listed price did not match the expected 1500");
        List<String> lines = TerminalReport.formatVerboseLines(report, true);
        assertThat(lines)
                .anyMatch(line -> line.contains("==> shop/shop-rs-api/integration") && line.contains("\u001b[94m"))
                .anyMatch(line -> line.contains("\u001b[32mcart\u001b[0m"))
                .anyMatch(line -> line.contains("✓") && line.contains("adds_line") && line.contains("\u001b[32m"))
                .anyMatch(line -> line.contains("✗") && line.contains("rejects_empty") && line.contains("\u001b[31m"))
                .anyMatch(line -> line.contains("!") && line.contains("listed price did not match the expected 1500"))
                .anyMatch(line -> line.contains("==> shop/shop-rs-api/unit"))
                .anyMatch(line -> line.contains("catalog_service"))
                .anyMatch(line -> line.contains("lists_products"))
                .noneMatch(line -> line.contains("cherry-test FAIL"))
                .allMatch(line -> TerminalReport.visibleWidth(line) <= TerminalReport.VERBOSE_WIDTH);
        String summary = TerminalReport.verboseSummaryLine(report, true);
        assertThat(summary).contains("cherry-test FAIL").contains("1 fail");
        String wide = TerminalReport.verboseCaseLine(sample_case("cart", "n".repeat(200), "ok", 3), false);
        assertThat(TerminalReport.visibleWidth(wide)).isEqualTo(TerminalReport.VERBOSE_WIDTH);
        String path = TerminalReport.formatVerboseReportPath(
                Path.of("/tmp/workspace/target/cherry-test/latest/reports/index.html"));
        assertThat(path)
                .isEqualTo("report  target/cherry-test/latest/reports/index.html")
                .doesNotContain("/tmp");
    }

    @Test
    void write_shop_verbose_cast() throws IOException {
        Path root = Path.of("../../site/demo");
        Path htmlPath = root.resolve("report/index.html");
        if (!Files.isRegularFile(htmlPath)) {
            return;
        }
        TestsReport report = ShopDemo.reportFromHtml(Files.readString(htmlPath, StandardCharsets.UTF_8));
        assertThat(report.projects()).hasSize(1);
        assertThat(report.projects().getFirst().stats().run()).isEqualTo(102);
        assertThat(report.projects().getFirst().stats().ok()).isEqualTo(101);
        assertThat(report.projects().getFirst().stats().fail()).isEqualTo(1);
        List<String> lines = TerminalReport.formatVerboseLines(report, true);
        assertThat(lines).allMatch(line -> TerminalReport.visibleWidth(line) <= TerminalReport.VERBOSE_WIDTH);
        String joined = String.join("\n", lines);
        assertThat(joined)
                .contains("==> shop/shop-rs-api/integration")
                .contains("==> shop/shop-rs-api/unit")
                .contains("==> shop/shop-ts-lib-cart/unit")
                .contains("==> shop/shop-ts-web-react/functional")
                .contains("==> shop/shop-ts-web-react/visual")
                .contains("reports_a_price_mismatch")
                .contains("listed price did not match the expected 1500")
                .doesNotContain("<")
                .contains("✓")
                .contains("✗")
                .doesNotContain("cherry-test FAIL")
                .doesNotContain("/Users")
                .doesNotContain("/private");
        String summary = TerminalReport.verboseSummaryLine(report, true);
        assertThat(summary).contains("cherry-test FAIL").contains("1 fail");
        Path castPath = root.resolve("shop.cast");
        if (!Files.isRegularFile(castPath)) {
            return;
        }
        String cast = Files.readString(castPath, StandardCharsets.UTF_8);
        String visible = ShopDemo.stripAnsi(cast.replace("\\u001b", "\u001b"));
        assertThat(visible)
                .contains("Multiple mono-repositories. Single test workflow.")
                .contains("reports_a_price_mismatch")
                .contains("cherry-test FAIL")
                .contains("amount must not be negative")
                .doesNotContain("more functions");
        assertThat(cast).contains("cherry-test --root app/shop --verbose");
        assertThat(visible.indexOf("crap:"))
                .isGreaterThanOrEqualTo(0)
                .isLessThan(visible.lastIndexOf("cherry-test FAIL"));
        assertThat(cast)
                .contains("\\u001b[31m")
                .contains("\\u001b[32m")
                .contains("\\u001b[38;2;226;59;50m")
                .doesNotContain("/Users")
                .doesNotContain("/private");
    }
}
