package com.cherrytest.cli.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.cherrytest.cli.DiscoveredTest;
import com.cherrytest.cli.PreflightPlan;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestViewTest {
    private static final TestTimingStats ZERO = new TestTimingStats(0, 0, 0, 0, 0, 0, 0, 0);

    static PreflightPlan noPreflight() {
        return new PreflightPlan(false, List.of(), List.of(), List.of(), List.of());
    }

    private static TestCaseView testCase(String name, String path) {
        return new TestCaseView(
                "shop",
                "checkout",
                name,
                name,
                "shop_checkout",
                "shop.api",
                1,
                "ok",
                "201",
                null,
                "POST",
                "/v1/carts/1/checkout",
                List.of("checkout"),
                List.of(),
                Path.of(path));
    }

    static IntegrationRow sampleRow(String status, long ms) {
        return sampleRow(status, ms, "get_usdt", null);
    }

    static IntegrationRow sampleRow(String status, long ms, String test, IntegrationRow.@Nullable Location location) {
        return new IntegrationRow(
                "mini",
                "wallet",
                test,
                "mini_project_wallet_suite_get_usdt_test",
                "mini_wallet",
                "mini.api",
                ms,
                status,
                status.equals("skipped") ? "-" : status.equals("FAIL") ? "500" : "200",
                status.equals("skipped") ? "no docker" : status.equals("FAIL") ? "HTTP 500" : null,
                "GET",
                "/health",
                TestView.deriveTags("mini", "wallet", "mini_wallet", "get_usdt"),
                location);
    }

    @Test
    void tests_in_change_keep_cases_whose_source_file_is_in_the_diff() {
        TestsReport report = new TestsReport(
                List.of(new ProjectTestView(
                        "shop",
                        ZERO,
                        List.of(new SuiteTestView(
                                "checkout",
                                ZERO,
                                List.of(
                                        testCase("applies_the_mug_discount", "shop-rs-api/tests/integration.rs"),
                                        testCase("lists_seeded_products", "shop-rs-api/tests/integration.rs"),
                                        testCase("formats_dollars", "shop-ts-lib-cart/src/cart.test.ts")))))),
                List.of("checkout"));
        assertThat(TestView.testsInChange(report, List.of()).projects()).isEmpty();
        TestsReport changed = TestView.testsInChange(report, List.of("shop-rs-api/tests/integration.rs"));
        assertThat(changed.projects()).hasSize(1);
        assertThat(changed.projects().getFirst().suites().getFirst().cases()).hasSize(2);
        assertThat(changed.projects()
                        .getFirst()
                        .suites()
                        .getFirst()
                        .cases()
                        .getFirst()
                        .test())
                .isEqualTo("applies_the_mug_discount");
    }

    @Test
    void format_timing_summary_includes_label_and_stats() {
        TestTimingStats stats = new TestTimingStats(2, 1, 1, 0, 40, 20, 10, 30);
        assertThat(TestView.formatTimingStats(stats))
                .isEqualTo("2 run · 1 ok · 1 fail · 0 skip · 40ms total · 20ms avg · 10ms fastest · 30ms slowest");
        assertThat(TestView.formatTimingSummary("wallet", stats))
                .isEqualTo(
                        "wallet · 2 run · 1 ok · 1 fail · 0 skip · 40ms total · 20ms avg · 10ms fastest · 30ms slowest");
    }

    @Test
    void groups_by_project_and_suite() {
        TestsReport report = TestView.buildTestsReport(
                List.of(sampleRow("ok", 10), sampleRow("FAIL", 20, "other", null)), noPreflight(), Path.of("."));
        assertThat(report.projects()).hasSize(1);
        assertThat(report.projects().getFirst().suites()).hasSize(1);
        assertThat(report.projects().getFirst().suites().getFirst().cases()).hasSize(2);
    }

    @Test
    void stats_exclude_skipped_from_timing() {
        TestsReport report = TestView.buildTestsReport(
                List.of(sampleRow("ok", 10), sampleRow("skipped", 0), sampleRow("FAIL", 30)),
                noPreflight(),
                Path.of("."));
        assertThat(report.projects().getFirst().stats()).isEqualTo(new TestTimingStats(2, 1, 1, 1, 40, 20, 10, 30));
    }

    @Test
    void extract_snippet_highlights_fn_line() {
        List<SnippetLine> snippet = TestView.extractSnippet("line1\nline2\nline3\nline4\nline5\nline6\nline7\n", 4, 2);
        assertThat(snippet).hasSize(5).anyMatch(line -> line.highlight() && line.lineNo() == 4);
    }

    @Test
    void snippet_shows_the_failed_assertion_line(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("tests.rs");
        Files.writeString(
                file,
                IntStream.rangeClosed(1, 30)
                        .mapToObj(number -> "line " + number + "\n")
                        .collect(Collectors.joining()),
                StandardCharsets.UTF_8);
        IntegrationRow row = sampleRow("FAIL", 5, "get_usdt", new IntegrationRow.Location(file, 17));
        TestsReport report = TestView.buildTestsReport(List.of(row), noPreflight(), directory);
        assertThat(report.projects().getFirst().suites().getFirst().cases().getFirst().snippet().stream()
                        .filter(SnippetLine::highlight)
                        .map(SnippetLine::lineNo))
                .containsExactly(17L);
    }

    @Test
    void snippet_loaded_from_discovered_source(@TempDir Path directory) throws IOException {
        Path relative = Path.of("tests/integration.rs");
        Files.createDirectories(directory.resolve("tests"));
        Files.writeString(
                directory.resolve(relative),
                "#[cherry_test(scope = \"none\")]\nasync fn mini_project_wallet_suite_get_usdt_test() {}\n",
                StandardCharsets.UTF_8);
        PreflightPlan preflight = new PreflightPlan(
                false,
                List.of(),
                List.of(),
                List.of(),
                List.of(new DiscoveredTest(
                        "mini-rs-api",
                        "mini_project_wallet_suite_get_usdt_test",
                        null,
                        null,
                        "none",
                        "mini",
                        "wallet",
                        "get_usdt",
                        relative,
                        2)));
        List<SnippetLine> failed = TestView.buildTestsReport(List.of(sampleRow("FAIL", 5)), preflight, directory)
                .projects()
                .getFirst()
                .suites()
                .getFirst()
                .cases()
                .getFirst()
                .snippet();
        assertThat(failed).isNotEmpty().anyMatch(SnippetLine::highlight);
        List<SnippetLine> passed = TestView.buildTestsReport(List.of(sampleRow("ok", 5)), preflight, directory)
                .projects()
                .getFirst()
                .suites()
                .getFirst()
                .cases()
                .getFirst()
                .snippet();
        assertThat(passed).isNotEmpty();
    }
}
