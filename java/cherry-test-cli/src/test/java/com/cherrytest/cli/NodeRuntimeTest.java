package com.cherrytest.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class NodeRuntimeTest {
    @Test
    void vitest_run_args_strips_pnpm_exec_prefix() {
        assertThat(NodeRuntime.vitestRunArgs(List.of("exec", "vitest", "run", "--coverage")))
                .containsExactly("run", "--coverage");
    }

    @Test
    void vitest_run_args_keeps_direct_args() {
        assertThat(NodeRuntime.vitestRunArgs(List.of("run", "--coverage"))).containsExactly("run", "--coverage");
    }

    @Test
    void playwright_run_args_strips_pnpm_exec_prefix() {
        assertThat(NodeRuntime.playwrightRunArgs(List.of("exec", "playwright", "test")))
                .containsExactly("test");
    }

    @Test
    void playwright_run_args_keeps_direct_args() {
        assertThat(NodeRuntime.playwrightRunArgs(List.of("test", "--project", "chromium")))
                .containsExactly("test", "--project", "chromium");
    }

    @Test
    void resolve_node_binary_finds_nvm_node_without_path() {
        Path nvmRoot = Path.of(System.getProperty("user.home"), ".nvm/versions/node");
        if (!Files.isDirectory(nvmRoot)) {
            return;
        }
        assertThat(NodeRuntime.resolveNodeBinary()).isNotNull();
    }
}
