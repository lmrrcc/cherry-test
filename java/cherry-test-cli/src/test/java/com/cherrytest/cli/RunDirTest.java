package com.cherrytest.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.cherrytest.common.process.ProcessEnvironment;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunDirTest {
    @TempDir
    Path workspace;

    @AfterEach
    void forget_exported_variables() {
        ProcessEnvironment.remove("CHERRY_TEST_RUN_DIR");
        ProcessEnvironment.remove("CHERRY_TEST_REPORT_DIR");
    }

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
    void export_env_sets_vars() {
        RunDir run = RunDir.create(workspace, Path.of("runs"), RunIdGenerator.systemClock());
        run.exportEnv();
        assertThat(ProcessEnvironment.get("CHERRY_TEST_RUN_DIR"))
                .isEqualTo(run.root().toString());
        assertThat(ProcessEnvironment.get("CHERRY_TEST_REPORT_DIR"))
                .isEqualTo(run.reports().toString());
    }
}
