package com.cherrytest.cli.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class DiffViewTest {
    private static SortedMap<Long, Boolean> coverage(Map<Long, Boolean> values) {
        return new TreeMap<>(values);
    }

    private static FileDiffSection file(String path, long adds, double covered, double uncovered) {
        return new FileDiffSection(path, adds, 0, covered, uncovered, true, 0, false, List.of());
    }

    private static ProjectReport report(String packageName, boolean passed) {
        return new ProjectReport("mini", packageName, passed, PackageCoverage.EMPTY, CoverageTotals.EMPTY, List.of());
    }

    @Test
    void parse_unified_diff_builds_hunk_lines() {
        String diff = "diff --git a/src/lib.rs b/src/lib.rs\n@@ -1,3 +1,4 @@\n fn main() {\n+    let x = 1;\n }\n";
        List<DiffHunk> hunks = DiffView.parseUnifiedDiff(diff, coverage(Map.of(2L, true, 3L, false)), false);
        assertThat(hunks).hasSize(1);
        assertThat(hunks.getFirst().lines()).hasSize(3);
        assertThat(hunks.getFirst().lines().get(1).tag()).isEqualTo(DiffTag.ADD);
        assertThat(hunks.getFirst().lines().get(1).coverage()).isEqualTo(LineCov.COVERED);
    }

    @Test
    void file_coverage_pct_counts_executable_lines() {
        List<DiffHunk> hunks = List.of(new DiffHunk(
                "@@",
                List.of(
                        new DiffLine(null, 1L, DiffTag.ADD, "a", LineCov.COVERED),
                        new DiffLine(null, 2L, DiffTag.ADD, "b", LineCov.UNCOVERED))));
        assertThat(DiffView.diffCoverageBreakdown(hunks)).containsExactly(50.0, 50.0);
    }

    @Test
    void diff_coverage_breakdown_is_nan_without_executable_lines() {
        List<DiffHunk> hunks =
                List.of(new DiffHunk("@@", List.of(new DiffLine(1L, 1L, DiffTag.CONTEXT, "ctx", LineCov.NA))));
        double[] breakdown = DiffView.diffCoverageBreakdown(hunks);
        assertThat(breakdown[0]).isNaN();
        assertThat(breakdown[1]).isNaN();
    }

    @Test
    void format_covered_uncovered_pair_shows_dash_when_missing() {
        assertThat(DiffView.formatCoveredUncoveredPair(Double.NaN, Double.NaN)).containsExactly("-", "-");
        assertThat(DiffView.formatCoveredUncoveredPair(39.0, 61.0)).containsExactly("39%", "61%");
    }

    @Test
    void build_file_diff_section_counts_fail_lines() {
        String diff = "@@ -1,1 +1,3 @@\n fn main() {\n+    let x = 1;\n+    let y = 2;\n }\n";
        FileDiffSection section =
                DiffView.buildFileDiffSection("src/lib.rs", diff, coverage(Map.of(2L, false, 3L, false)), true);
        assertThat(section.failLines()).isEqualTo(2);
        assertThat(section.hasUncovered()).isTrue();
    }

    @Test
    void strip_coverage_clears_stats() {
        FileDiffSection section = new FileDiffSection(
                "mini/mini-rs-lib-mcap/Cargo.toml",
                1,
                0,
                0.0,
                100.0,
                true,
                0,
                false,
                List.of(new DiffHunk("@@", List.of(new DiffLine(null, 1L, DiffTag.ADD, "x", LineCov.UNCOVERED)))));
        FileDiffSection stripped = DiffView.stripCoverage(section);
        assertThat(stripped.coveragePct()).isNaN();
        assertThat(stripped.uncoveredPct()).isNaN();
        assertThat(stripped.hasUncovered()).isFalse();
        assertThat(stripped.hunks().stream().flatMap(hunk -> hunk.lines().stream()))
                .allMatch(line -> line.coverage() == LineCov.NA);
    }

    @Test
    void group_files_into_modules_splits_by_package() {
        List<ModuleDiffSection> modules = DiffView.groupFilesIntoModules(
                List.of(
                        file("mini/mini-ts-web-react/src/App.tsx", 1, 80.0, 20.0),
                        file("mini/mini-ts-lib-price/src/displayAddressUsdt.ts", 2, 90.0, 10.0)),
                List.of("mini/mini-ts-web-react/", "mini/mini-ts-lib-price/"));
        assertThat(modules).hasSize(2);
        assertThat(modules.get(0).path()).isEqualTo("mini/mini-ts-lib-price");
        assertThat(modules.get(0).fileCount()).isEqualTo(1);
        assertThat(modules.get(1).path()).isEqualTo("mini/mini-ts-web-react");
        assertThat(modules.get(1).fileCount()).isEqualTo(1);
    }

    @Test
    void package_failed_for_path_uses_owning_package() {
        List<ProjectReport> reports = List.of(report("mini-rs-api", true), report("mini-ts-lib-price", false));
        List<String> prefixes = List.of("mini/mini-rs-api/", "mini/mini-ts-lib-price/");
        assertThat(DiffView.packageFailedForPath("mini/mini-rs-api/src/lib.rs", "mini", reports, prefixes))
                .isFalse();
        assertThat(DiffView.packageFailedForPath(
                        "mini/mini-ts-lib-price/src/displayAddressUsdt.ts", "mini", reports, prefixes))
                .isTrue();
    }

    @Test
    void uncovered_becomes_fail_when_project_failed() {
        SortedMap<Long, Boolean> lineCoverage = coverage(Map.of(2L, false));
        assertThat(DiffView.coverageForLine(2L, DiffTag.ADD, lineCoverage, true))
                .isEqualTo(LineCov.FAIL);
        assertThat(DiffView.coverageForLine(2L, DiffTag.ADD, lineCoverage, false))
                .isEqualTo(LineCov.UNCOVERED);
    }
}
