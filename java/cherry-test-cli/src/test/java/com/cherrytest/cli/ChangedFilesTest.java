package com.cherrytest.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.cherrytest.cli.coverage.FileCoverage;
import com.cherrytest.common.gitdiff.GitDiff;
import com.cherrytest.core.PackageRuntime;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChangedFilesTest {
    @TempDir
    Path root;

    private static void git(Path cwd, String... arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command)
                .directory(cwd.toFile())
                .redirectErrorStream(true)
                .start();
        process.getInputStream().readAllBytes();
        process.waitFor();
    }

    private void source(String relative) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "fn main() {}\n", StandardCharsets.UTF_8);
    }

    @Test
    void maps_changed_files_to_project_prefix() {
        ProjectGroup mini = new ProjectGroup(
                "mini",
                List.of(
                        new WorkspaceMember(
                                "mini-rs-api", Path.of("mini/mini-rs-api/Cargo.toml"), "mini", PackageRuntime.RUST),
                        new WorkspaceMember(
                                "mini-rs-lib-mcap",
                                Path.of("mini/mini-rs-lib-mcap/Cargo.toml"),
                                "mini",
                                PackageRuntime.RUST)));
        List<String> changed = List.of(
                "mini/mini-rs-api/src/api.rs", "mini/mini-rs-lib-mcap/src/lib.rs", "test/cherry-test/src/lib.rs");
        assertThat(GitDiff.mapFilesToProjects(Path.of("/ws"), List.of(mini.id()), changed)
                        .get("mini"))
                .containsExactly("mini/mini-rs-api/src/api.rs", "mini/mini-rs-lib-mcap/src/lib.rs");
    }

    @Test
    void collects_untracked_files_without_commits() throws IOException, InterruptedException {
        source("mini/mini-rs-api/src/lib.rs");
        git(root, "init");
        assertThat(GitDiff.collectChangedFilesForBase(root, "main"))
                .anyMatch(path -> path.endsWith("mini/mini-rs-api/src/lib.rs"));
    }

    @Test
    void git_unified_diff_shows_untracked_file_without_commits() throws IOException, InterruptedException {
        source("mini/mini-rs-api/src/lib.rs");
        git(root, "init");
        assertThat(GitDiff.gitUnifiedDiff(root, "mini/mini-rs-api/src/lib.rs", "main", 5))
                .contains("@@")
                .contains("+fn main()");
    }

    @Test
    void module_path_for_file_uses_longest_prefix() {
        List<String> prefixes = List.of("mini/", "mini/mini-rs-api/", "mini/mini-ts-web-react/");
        assertThat(ChangedFiles.modulePathForFile("mini/mini-rs-api/src/lib.rs", prefixes))
                .isEqualTo("mini/mini-rs-api");
        assertThat(ChangedFiles.modulePathForFile("mini/mini-ts-web-react/src/App.tsx", prefixes))
                .isEqualTo("mini/mini-ts-web-react");
    }

    @Test
    void module_path_for_file_falls_back_to_two_segments() {
        assertThat(ChangedFiles.modulePathForFile("other/pkg/src/main.rs", List.of()))
                .isEqualTo("other/pkg");
    }

    @Test
    void package_prefix_filter_keeps_package_tree_only() {
        assertThat(GitDiff.matchesPackagePrefix("mini/mini-rs-api/src/lib.rs", List.of("mini/mini-rs-api/")))
                .isTrue();
        assertThat(GitDiff.matchesPackagePrefix("mini/docker-compose.test.yml", List.of("mini/mini-rs-api/")))
                .isFalse();
    }

    @Test
    void expand_changed_adds_lcov_sources_for_exercised_project() {
        List<String> expanded = ChangedFiles.expandChangedWithLcovSources(
                List.of("mini/mini-rs-lib-mcap/Dockerfile"),
                List.of(new FileCoverage("mini/mini-rs-lib-mcap/src/lib.rs", 8, 10, 0, 0)),
                List.of("mini/mini-rs-lib-mcap/"));
        assertThat(expanded).contains("mini/mini-rs-lib-mcap/Dockerfile", "mini/mini-rs-lib-mcap/src/lib.rs");
    }

    @Test
    void resolve_diff_base_empty_tree_aliases() {
        for (String base : List.of("empty", "empty-tree", "root", "EMPTY")) {
            assertThat(GitDiff.isEmptyTreeBase(base)).as("base=" + base).isTrue();
            assertThat(GitDiff.resolveDiffBase(root, base)).as("base=" + base).isEqualTo(GitDiff.GIT_EMPTY_TREE);
        }
    }

    @Test
    void empty_diff_base_lists_tracked_files_since_empty_tree() throws IOException, InterruptedException {
        source("pkg/src/lib.rs");
        git(root, "init");
        git(root, "add", ".");
        git(root, "-c", "user.email=test@test", "-c", "user.name=test", "commit", "-m", "init");
        assertThat(GitDiff.collectChangedFilesForBase(root, "empty")).anyMatch(path -> path.endsWith("pkg/src/lib.rs"));
    }
}
