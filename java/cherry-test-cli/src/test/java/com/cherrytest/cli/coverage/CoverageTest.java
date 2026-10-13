package com.cherrytest.cli.coverage;

import static org.assertj.core.api.Assertions.assertThat;

import com.cherrytest.cli.RunDir;
import com.cherrytest.cli.RunIdGenerator;
import com.cherrytest.cli.TestKind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoverageTest {
    @Test
    void kind_lcov_file_uses_kind_suffix(@TempDir Path directory) {
        RunDir run = RunDir.create(directory, Path.of("runs"), RunIdGenerator.systemClock());
        CoveragePaths paths = new CoveragePaths(run, "mini");
        assertThat(paths.kindLcovFile("mini-rs-api", TestKind.INTEGRATION))
                .isEqualTo(run.coverageDir("mini").resolve("mini-rs-api-integration.lcov"));
    }

    @Test
    void parse_lcov_content_reads_file_records() {
        List<FileCoverage> files = Coverage.parseLcovContent(
                "SF:/ws/mini/mini-rs-api/src/lib.rs\nLF:10\nLH:7\nFNF:2\nFNH:1\nend_of_record\n");
        assertThat(files).hasSize(1);
        assertThat(files.getFirst().linesFound()).isEqualTo(10);
        assertThat(files.getFirst().linesHit()).isEqualTo(7);
    }

    @Test
    void parse_lcov_line_content_reads_da_records() {
        List<FileLineCoverage> files =
                Coverage.parseLcovLineContent("SF:/ws/mini/mini-rs-api/src/lib.rs\nDA:12,1\nDA:13,0\nend_of_record\n");
        assertThat(files).hasSize(1);
        assertThat(files.getFirst().lines()).containsEntry(12L, true).containsEntry(13L, false);
    }

    @Test
    void merge_line_coverage_or_hits() {
        SortedMap<Long, Boolean> merged = Coverage.mergeLineCoverageMaps(
                new TreeMap<>(Map.of(1L, false, 2L, true)), new TreeMap<>(Map.of(1L, true, 3L, false)));
        assertThat(merged).containsEntry(1L, true).containsEntry(2L, true).containsEntry(3L, false);
    }

    @Test
    void summary_for_files_filters_changed_paths() {
        CoverageSummary summary = Coverage.summaryForFiles(
                List.of(new FileCoverage("/ws/mini/mini-rs-api/src/api.rs", 5, 10, 1, 2)),
                List.of("mini/mini-rs-api/src/api.rs"),
                CoverageIgnore.fromPatterns(List.of()));
        assertThat(summary.linesHit()).isEqualTo(5);
        assertThat(summary.linesFound()).isEqualTo(10);
    }

    @Test
    void summary_for_files_skips_ignored_changed_path() {
        CoverageSummary summary = Coverage.summaryForFiles(
                List.of(new FileCoverage("mini/mini-rs-lib-mcap/Cargo.toml", 0, 20, 0, 0)),
                List.of("mini/mini-rs-lib-mcap/Cargo.toml"),
                CoverageIgnore.fromPatterns(List.of("**/Cargo.toml")));
        assertThat(summary.linesFound()).isZero();
    }

    @Test
    void path_matches_vitest_relative_lcov_path() {
        List<String> changed = List.of("mini/mini-ts-lib-price/src/displayAddressUsdt.ts");
        assertThat(Coverage.pathMatchesChanged("src/displayAddressUsdt.ts", changed))
                .isTrue();
        assertThat(Coverage.pathMatchesChanged("mini/mini-ts-lib-price/src/displayAddressUsdt.ts", changed))
                .isTrue();
    }

    @Test
    void normalize_lcov_sf_paths_rewrites_relative_sf() {
        String normalized = Coverage.normalizeLcovSfPaths(
                "SF:src/displayAddressUsdt.ts\nDA:1,1\nend_of_record\n", Path.of("/ws"), "mini/mini-ts-lib-price");
        assertThat(normalized).contains("SF:mini/mini-ts-lib-price/src/displayAddressUsdt.ts");
    }

    @Test
    void summary_for_lcov_excludes_ignored_node_modules(@TempDir Path directory) throws IOException {
        Path lcov = directory.resolve("coverage.lcov");
        String content = """
                SF:mini/mini-ts-lib-price/src/displayAddressUsdt.ts
                LF:10
                LH:9
                end_of_record
                SF:mini/mini-ts-lib-price/node_modules/foo/index.js
                LF:100
                LH:50
                end_of_record
                """;
        Files.writeString(lcov, content, StandardCharsets.UTF_8);
        CoverageSummary summary =
                Coverage.summaryForLcov(lcov, CoverageIgnore.fromPatterns(List.of("**/node_modules/**")));
        assertThat(summary.linesFound()).isEqualTo(10);
        assertThat(summary.linesHit()).isEqualTo(9);
    }
}
