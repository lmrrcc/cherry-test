package com.cherrytest.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepoRootTest {
    @TempDir
    Path root;

    private void config(Path directory) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("cherry-test.yaml"), "projects: {}\n", StandardCharsets.UTF_8);
    }

    @Test
    void finds_root_via_test_yaml_in_parent() throws IOException {
        Files.createDirectories(root.resolve("mini/pkg"));
        config(root);
        assertThat(RepoRoot.walkUpForConfig(root.resolve("mini/pkg"))).isEqualTo(root);
    }

    @Test
    void finds_root_via_test_yaml_in_cwd() throws IOException {
        config(root);
        assertThat(RepoRoot.walkUpForConfig(root)).isEqualTo(root);
    }

    @Test
    void walk_up_returns_none_when_no_config() throws IOException {
        Files.createDirectories(root.resolve("sub"));
        assertThat(RepoRoot.walkUpForConfig(root.resolve("sub"))).isNull();
    }

    @Test
    void find_nested_config_dirs_finds_immediate_children() throws IOException {
        Files.createDirectories(root.resolve("mini/pkg"));
        config(root.resolve("mini"));
        config(root.resolve("other"));
        List<Path> found = RepoRoot.findNestedConfigDirs(root);
        assertThat(found).hasSize(2).anyMatch(path -> path.endsWith("mini")).anyMatch(path -> path.endsWith("other"));
    }

    @Test
    void find_nested_config_dirs_skips_target_and_test() throws IOException {
        config(root.resolve("target"));
        Files.createDirectories(root.resolve("test"));
        assertThat(RepoRoot.findNestedConfigDirs(root)).isEmpty();
    }

    @Test
    void select_nested_roots_single_picks_it() throws IOException {
        Path mini = Files.createDirectories(root.resolve("mini"));
        assertThat(RepoRoot.selectNestedRoots(root, List.of(mini), null)).containsExactly(mini);
    }

    @Test
    void select_nested_roots_filter_by_dir_name() throws IOException {
        Path mini = Files.createDirectories(root.resolve("mini"));
        Path other = Files.createDirectories(root.resolve("other"));
        assertThat(RepoRoot.selectNestedRoots(root, List.of(mini, other), "mini"))
                .containsExactly(mini);
    }

    @Test
    void select_nested_roots_multiple_without_filter() throws IOException {
        Path a = Files.createDirectories(root.resolve("a"));
        Path b = Files.createDirectories(root.resolve("b"));
        assertThat(RepoRoot.selectNestedRoots(root, List.of(a, b), null)).containsExactly(a, b);
    }
}
