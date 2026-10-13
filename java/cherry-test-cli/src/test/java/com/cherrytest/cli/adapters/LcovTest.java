package com.cherrytest.cli.adapters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LcovTest {
    @TempDir
    Path directory;

    @Test
    void merges_overlapping_phases_without_double_counting_lines() throws IOException {
        Path unit = directory.resolve("unit.lcov");
        Path integration = directory.resolve("integration.lcov");
        Path merged = directory.resolve("merged.lcov");
        Files.writeString(
                unit, "SF:src/service.java\nDA:4,1\nDA:5,0\nBRDA:4,0,0,-\nend_of_record\n", StandardCharsets.UTF_8);
        Files.writeString(
                integration,
                "SF:src/service.java\nDA:4,2\nDA:5,1\nDA:6,0\nBRDA:4,0,0,2\nend_of_record\n",
                StandardCharsets.UTF_8);
        Lcov.merge(List.of(unit, integration), merged);
        String content = Files.readString(merged, StandardCharsets.UTF_8);
        assertThat(content.split("SF:", -1)).hasSize(2);
        assertThat(content).contains("DA:4,3\n").contains("LF:3\nLH:2\n").contains("BRDA:4,0,0,2\n");
        Lcov.merge(List.of(merged), merged);
        assertThat(Files.readString(merged, StandardCharsets.UTF_8)).isEqualTo(content);
    }

    @Test
    void missing_coverage_is_an_error() {
        assertThatThrownBy(() -> Lcov.merge(List.of(directory.resolve("missing")), directory.resolve("merged")))
                .isInstanceOf(RuntimeException.class);
    }
}
