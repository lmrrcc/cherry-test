package com.cherrytest.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.cherrytest.common.process.ProcessEnvironment;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunDirTest {
    @TempDir
    Path workspace;

    @Test
    void run_id_has_expected_shape() {
        String id = RunIdGenerator.systemClock().next();
        assertThat(id).hasSize(22);
        assertThat(id.chars().filter(character -> character == '-').count()).isEqualTo(2);
        assertThat(RunIdGenerator.fromInstant(Instant.parse("2026-05-23T14:30:22.000000001Z")))
                .isEqualTo("20260523-143022-000001");
    }

    @Test
    void layout_paths_under_run_root() {
        RunDir run = RunDir.create(workspace, Path.of("runs"), RunIdGenerator.systemClock());
        assertThat(run.root()).startsWith(workspace.resolve("runs"));
        assertThat(run.targetsDir("mini")).isEqualTo(run.root().resolve("targets/mini"));
        assertThat(run.coverageDir("mini")).isEqualTo(run.coverage().resolve("mini"));
        assertThat(run.mergedLcov()).isEqualTo(run.coverage().resolve("merged.lcov"));
        assertThat(run.reportHtml()).isEqualTo(run.reports().resolve("index.html"));
        assertThat(run.reports()).isDirectory();
        assertThat(run.coverage()).isDirectory();
    }

    @Test
    void unique_run_dirs() throws InterruptedException {
        RunDir first = RunDir.create(workspace, Path.of("runs"), RunIdGenerator.systemClock());
        Thread.sleep(1);
        RunDir second = RunDir.create(workspace, Path.of("runs"), RunIdGenerator.systemClock());
        assertThat(first.root()).isNotEqualTo(second.root());
    }

    @Test
    void env_names_run_and_report_dirs() {
        RunDir run = RunDir.create(workspace, Path.of("runs"), RunIdGenerator.systemClock());
        assertThat(run.env())
                .containsExactly(
                        Map.entry("CHERRY_TEST_RUN_DIR", run.root().toString()),
                        Map.entry("CHERRY_TEST_REPORT_DIR", run.reports().toString()));
        assertThat(ProcessEnvironment.get("CHERRY_TEST_RUN_DIR"))
                .isNotEqualTo(run.root().toString());
    }

    @Test
    void merges_job_reports_in_job_order() throws IOException {
        RunDir run = RunDir.create(workspace, Path.of("runs"), RunIdGenerator.systemClock());
        Path first = run.reports().resolve("adapters/shop/api/unit/report");
        Path second = run.reports().resolve("adapters/shop/web/unit/report");
        for (Map.Entry<Path, String[]> job : List.of(
                Map.entry(first, new String[] {"api\n", "api-v1"}),
                Map.entry(second, new String[] {"web\n", "web-v2"}))) {
            Files.createDirectories(job.getKey().resolve("integration"));
            Files.createDirectories(job.getKey().resolve("screenshots"));
            Files.writeString(
                    job.getKey().resolve("integration/shop.jsonl"), job.getValue()[0], StandardCharsets.UTF_8);
            Files.writeString(job.getKey().resolve("screenshots/home.png"), job.getValue()[1], StandardCharsets.UTF_8);
        }
        Files.writeString(second.resolve("screenshots/web.png"), "web", StandardCharsets.UTF_8);
        run.mergeJobReport(first);
        run.mergeJobReport(second);
        run.mergeJobReport(run.reports().resolve("adapters/shop/none/unit/report"));
        assertThat(Files.readString(run.reports().resolve("integration/shop.jsonl"), StandardCharsets.UTF_8))
                .isEqualTo("api\nweb\n");
        assertThat(Files.readString(run.reports().resolve("screenshots/home.png"), StandardCharsets.UTF_8))
                .isEqualTo("web-v2");
        assertThat(run.reports().resolve("screenshots/web.png")).isRegularFile();
        assertThat(first).doesNotExist();
        assertThat(second).doesNotExist();
    }
}
