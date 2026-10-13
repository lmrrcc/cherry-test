package com.cherrytest.common.gitdiff;

import static org.assertj.core.api.Assertions.assertThat;

import com.cherrytest.common.config.ReviewChangedScope;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitDiffTest {
    @TempDir
    Path dir;

    private static String git(Path cwd, String... arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command)
                .directory(cwd.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        process.waitFor();
        return output;
    }

    private static void commit(Path cwd, String message) throws IOException, InterruptedException {
        git(cwd, "-c", "user.email=test@test", "-c", "user.name=test", "commit", "-m", message);
    }

    private static void write(Path file, String content) throws IOException {
        Path parent = Objects.requireNonNull(file.getParent());
        Files.createDirectories(parent);
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static ChangedFilesProject project(
            String id,
            List<String> prefixes,
            Path gitRoot,
            Path workspaceRoot,
            String pathPrefix,
            List<NestedGitScope> scopes) {
        return new ChangedFilesProject(
                id, "main", ReviewChangedScope.BRANCH, prefixes, gitRoot, workspaceRoot, pathPrefix, scopes);
    }

    @Test
    void maps_changed_files_to_project_prefix() {
        Map<String, List<String>> mapped = GitDiff.mapFilesToProjects(
                Path.of("/ws"),
                List.of("mini"),
                List.of(
                        "mini/mini-rs-api/src/api.rs",
                        "mini/mini-rs-lib-mcap/src/lib.rs",
                        "test/cherry-test/src/lib.rs"));
        assertThat(mapped.get("mini"))
                .containsExactly("mini/mini-rs-api/src/api.rs", "mini/mini-rs-lib-mcap/src/lib.rs");
    }

    @Test
    void collects_untracked_files_without_commits() throws IOException, InterruptedException {
        write(dir.resolve("mini/mini-rs-api/src/lib.rs"), "fn main() {}\n");
        git(dir, "init");
        assertThat(GitDiff.collectChangedFilesForBase(dir, "main"))
                .anyMatch(path -> path.endsWith("mini/mini-rs-api/src/lib.rs"));
    }

    @Test
    void git_unified_diff_shows_untracked_file_without_commits() throws IOException, InterruptedException {
        write(dir.resolve("mini/mini-rs-api/src/lib.rs"), "fn main() {}\n");
        git(dir, "init");
        String diff = GitDiff.gitUnifiedDiff(dir, "mini/mini-rs-api/src/lib.rs", "main", 5);
        assertThat(diff).contains("@@").contains("+fn main()");
    }

    @Test
    void module_path_for_file_uses_longest_prefix() {
        List<String> prefixes = List.of("mini/", "mini/mini-rs-api/", "mini/mini-ts-web-react/");
        assertThat(GitDiff.modulePathForFile("mini/mini-rs-api/src/lib.rs", prefixes))
                .isEqualTo("mini/mini-rs-api");
        assertThat(GitDiff.modulePathForFile("mini/mini-ts-web-react/src/App.tsx", prefixes))
                .isEqualTo("mini/mini-ts-web-react");
    }

    @Test
    void module_path_for_file_falls_back_to_two_segments() {
        assertThat(GitDiff.modulePathForFile("other/pkg/src/main.rs", List.of()))
                .isEqualTo("other/pkg");
    }

    @Test
    void module_path_for_file_extends_coarse_project_prefix() {
        List<String> prefixes = List.of("mini/");
        assertThat(GitDiff.modulePathForFile("mini/mini-rs-api/src/lib.rs", prefixes))
                .isEqualTo("mini/mini-rs-api");
        assertThat(GitDiff.modulePathForFile("mini/cherry-test.yaml", prefixes)).isEqualTo("mini");
    }

    @Test
    void package_prefix_filter_keeps_package_tree_only() {
        assertThat(GitDiff.matchesPackagePrefix("mini/mini-rs-api/src/lib.rs", List.of("mini/mini-rs-api/")))
                .isTrue();
        assertThat(GitDiff.matchesPackagePrefix("mini/docker-compose.test.yml", List.of("mini/mini-rs-api/")))
                .isFalse();
    }

    @Test
    void resolve_diff_base_empty_tree_aliases() {
        for (String base : List.of("empty", "empty-tree", "root", "EMPTY")) {
            assertThat(GitDiff.isEmptyTreeBase(base)).as("base=" + base).isTrue();
            assertThat(GitDiff.resolveDiffBase(dir, base)).as("base=" + base).isEqualTo(GitDiff.EMPTY_TREE);
        }
    }

    @Test
    void prefix_workspace_path_joins_prefix_and_relative_path() {
        assertThat(GitDiff.prefixWorkspacePath("app/backoffice/", "src/lib.rs")).isEqualTo("app/backoffice/src/lib.rs");
        assertThat(GitDiff.prefixWorkspacePath("", "src/lib.rs")).isEqualTo("src/lib.rs");
    }

    @Test
    void strip_workspace_path_prefix_removes_prefix() {
        assertThat(GitDiff.stripWorkspacePathPrefix("app/backoffice/", "app/backoffice/src/lib.rs"))
                .isEqualTo("src/lib.rs");
        assertThat(GitDiff.stripWorkspacePathPrefix("", "src/lib.rs")).isEqualTo("src/lib.rs");
    }

    @Test
    void range_only_master_fallback_when_main_missing() throws IOException, InterruptedException {
        Path sub = dir.resolve("app/service");
        write(sub.resolve("src/lib.rs"), "fn v1() {}\n");
        git(sub, "init", "-b", "master");
        git(sub, "add", ".");
        commit(sub, "init");
        git(sub, "checkout", "-b", "feature");
        write(sub.resolve("src/lib.rs"), "fn v2() {}\n");
        git(sub, "add", "src/lib.rs");
        commit(sub, "change");
        assertThat(GitDiff.collectRangeDiffFiles(sub, "main", true)).containsExactly("src/lib.rs");
        write(sub.resolve("src/untracked.rs"), "fn extra() {}\n");
        assertThat(GitDiff.collectChangedFilesInner(sub, "main", GitDiff.CollectChangedMode.BRANCH_ONLY))
                .hasSize(1);
        assertThat(GitDiff.collectChangedFilesInner(sub, "main", GitDiff.CollectChangedMode.BRANCH_AND_WORKTREE))
                .anyMatch(path -> path.endsWith("untracked.rs"));
    }

    @Test
    void range_only_unresolved_diff_base_returns_empty() throws IOException, InterruptedException {
        Path sub = dir.resolve("repo");
        write(sub.resolve("src/a.rs"), "fn a() {}\n");
        git(sub, "init", "-b", "feature-only");
        git(sub, "add", ".");
        commit(sub, "init");
        write(sub.resolve("src/dirty.rs"), "fn dirty() {}\n");
        assertThat(GitDiff.collectRangeDiffFiles(sub, "main", true)).isEmpty();
    }

    @Test
    void range_only_empty_tree_diff_base_returns_empty() throws IOException, InterruptedException {
        Path sub = dir.resolve("repo");
        write(sub.resolve("src/a.rs"), "fn a() {}\n");
        git(sub, "init", "-b", "main");
        git(sub, "add", ".");
        commit(sub, "init");
        assertThat(GitDiff.collectRangeDiffFiles(sub, "empty-tree", true)).isEmpty();
        assertThat(GitDiff.collectChangedFilesInner(sub, "empty-tree", GitDiff.CollectChangedMode.BRANCH_AND_WORKTREE))
                .anyMatch(path -> path.endsWith("src/a.rs"));
    }

    @Test
    void range_only_dirty_worktree_ignored_when_range_empty() throws IOException, InterruptedException {
        Path sub = dir.resolve("app/backoffice");
        write(sub.resolve("src/tracked.rs"), "fn tracked() {}\n");
        git(sub, "init", "-b", "main");
        git(sub, "add", ".");
        commit(sub, "init");
        write(sub.resolve("src/dirty.rs"), "fn dirty() {}\n");
        write(sub.resolve("src/untracked.rs"), "fn new() {}\n");
        assertThat(GitDiff.collectChangedFilesInner(sub, "main", GitDiff.CollectChangedMode.BRANCH_ONLY))
                .isEmpty();
    }

    @Test
    void branch_range_empty_when_head_is_ancestor_of_diff_base() throws IOException, InterruptedException {
        Path repo = dir.resolve("repo");
        write(repo.resolve("src/lib.rs"), "fn sample() {}\n");
        git(repo, "init", "-b", "mini");
        git(repo, "add", ".");
        commit(repo, "full stack");
        git(repo, "branch", "-f", "main");
        git(repo, "checkout", "main");
        Files.delete(repo.resolve("src/lib.rs"));
        write(repo.resolve("CHANGES"), "");
        git(repo, "add", "-A");
        commit(repo, "placeholder main");
        git(repo, "checkout", "mini");
        assertThat(GitDiff.collectRangeDiffFiles(repo, "main", true))
                .as("branch scope uses three-dot only; no tree-diff when HEAD has no commits ahead of base")
                .isEmpty();
        assertThat(GitDiff.branchRangeChangedFiles(repo, "main").command()).contains("main...HEAD");
    }

    @Test
    void resolve_diff_base_prefers_origin_before_local_main() throws IOException, InterruptedException {
        write(dir.resolve("src/base.rs"), "fn base() {}\n");
        git(dir, "init", "-b", "main");
        git(dir, "add", ".");
        commit(dir, "base");
        String originMain = git(dir, "rev-parse", "HEAD").strip();
        git(dir, "update-ref", "refs/remotes/origin/main", originMain);
        git(dir, "checkout", "-b", "feature");
        write(dir.resolve("src/branch.rs"), "fn branch() {}\n");
        git(dir, "add", "src/branch.rs");
        commit(dir, "on branch");
        git(dir, "checkout", "main");
        write(dir.resolve("src/main_only.rs"), "fn main_only() {}\n");
        git(dir, "add", "src/main_only.rs");
        commit(dir, "advance local main");
        git(dir, "checkout", "feature");
        assertThat(GitDiff.resolveDiffBaseForScope(dir, "main", true)).isEqualTo("origin/main");
        assertThat(GitDiff.collectRangeDiffFiles(dir, "main", true)).containsExactly("src/branch.rs");
        assertThat(GitDiff.branchRangeChangedFiles(dir, "origin/main").command())
                .contains("origin/main...HEAD");
    }

    @Test
    void shell_commands_match_branch_only_collect_mode() throws IOException, InterruptedException {
        Path sub = dir.resolve("app/service");
        write(sub.resolve("src/lib.rs"), "fn lib() {}\n");
        git(sub, "init", "-b", "main");
        git(sub, "add", ".");
        commit(sub, "init");
        List<String> commands = GitDiff.collectChangedFilesShellCommands(sub, "main", ReviewChangedScope.BRANCH, true);
        assertThat(commands).anyMatch(command -> command.contains("diff --name-only") && command.contains("main"));
        assertThat(commands).noneMatch(command -> command.contains("ls-files --others"));
    }

    @Test
    void separate_repo_scope_uses_only_diff_base_range_not_worktree() throws IOException, InterruptedException {
        Path sub = dir.resolve("app/backoffice");
        write(sub.resolve("src/tracked.rs"), "fn tracked() {}\n");
        write(sub.resolve("src/dirty.rs"), "fn dirty() {}\n");
        git(sub, "init", "-b", "main");
        git(sub, "add", "src/tracked.rs", "src/dirty.rs");
        commit(sub, "init");
        write(sub.resolve("src/dirty.rs"), "fn dirty() { println!(); }\n");
        write(sub.resolve("src/untracked.rs"), "fn new() {}\n");
        GitDiff.GitScope scope = GitDiff.resolveProjectGitScope(dir, sub, null);
        Map<String, List<String>> mapped = GitDiff.changedFilesByProject(
                dir,
                List.of(project(
                        "backoffice",
                        List.of("app/backoffice/"),
                        scope.gitRoot(),
                        dir,
                        scope.pathPrefix(),
                        List.of())));
        assertThat(mapped.getOrDefault("backoffice", List.of())).isEmpty();
        git(sub, "checkout", "-b", "feature");
        write(sub.resolve("src/tracked.rs"), "fn tracked() { println!(); }\n");
        git(sub, "add", "src/tracked.rs");
        commit(sub, "change");
        Map<String, List<String>> changed = GitDiff.changedFilesByProject(
                dir,
                List.of(project("backoffice", List.of("app/backoffice/"), sub, dir, "app/backoffice/", List.of())));
        assertThat(changed.get("backoffice")).containsExactly("app/backoffice/src/tracked.rs");
    }

    @Test
    void container_app_project_merges_module_git_scopes() throws IOException, InterruptedException {
        Path appRoot = dir.resolve("app");
        Path mini = appRoot.resolve("mini");
        Path backoffice = appRoot.resolve("backoffice");
        write(mini.resolve("src/lib.rs"), "fn mini() {}\n");
        write(backoffice.resolve("src/lib.rs"), "fn backoffice() {}\n");
        git(dir, "init", "-b", "main");
        for (Path sub : List.of(mini, backoffice)) {
            git(sub, "init", "-b", "main");
            git(sub, "add", ".");
            commit(sub, "init");
            git(sub, "checkout", "-b", "feature");
        }
        write(mini.resolve("src/lib.rs"), "fn mini() { println!(); }\n");
        write(backoffice.resolve("src/lib.rs"), "fn backoffice() { println!(); }\n");
        git(mini, "add", "src/lib.rs");
        commit(mini, "mini change");
        git(backoffice, "add", "src/lib.rs");
        commit(backoffice, "backoffice change");
        List<NestedGitScope> scopes =
                GitDiff.discoverNestedGitScopes(dir, appRoot, List.of("mini", "backoffice"), null);
        assertThat(scopes).hasSize(2);
        Map<String, List<String>> mapped = GitDiff.changedFilesByProject(
                dir, List.of(project("app", List.of("app/mini/", "app/backoffice/"), mini, dir, "app/mini/", scopes)));
        assertThat(mapped.get("app")).contains("app/mini/src/lib.rs", "app/backoffice/src/lib.rs");
    }

    @Test
    void nested_git_repo_changed_files_are_prefixed_for_monorepo() throws IOException, InterruptedException {
        Path sub = dir.resolve("app/backoffice");
        write(sub.resolve("src/lib.rs"), "fn main() {}\n");
        git(dir, "init");
        git(sub, "init");
        git(sub, "add", ".");
        commit(sub, "init");
        git(sub, "checkout", "-b", "feature");
        write(sub.resolve("src/lib.rs"), "fn main() { println!(); }\n");
        git(sub, "add", "src/lib.rs");
        commit(sub, "change");
        GitDiff.GitScope scope = GitDiff.resolveProjectGitScope(dir, sub, null);
        assertThat(GitDiff.isSeparateGitRepository(dir, sub)).isTrue();
        assertThat(scope.pathPrefix()).isEqualTo("app/backoffice/");
        Map<String, List<String>> mapped = GitDiff.changedFilesByProject(
                dir,
                List.of(project(
                        "backoffice",
                        List.of("app/backoffice/"),
                        scope.gitRoot(),
                        dir,
                        scope.pathPrefix(),
                        List.of())));
        assertThat(mapped.get("backoffice")).contains("app/backoffice/src/lib.rs");
    }

    @Test
    void git_unified_diff_scoped_reads_nested_repository() throws IOException, InterruptedException {
        Path sub = dir.resolve("app/backoffice");
        write(sub.resolve("src/lib.rs"), "fn main() {}\n");
        git(dir, "init");
        git(sub, "init");
        git(sub, "add", ".");
        commit(sub, "init");
        write(sub.resolve("src/lib.rs"), "fn main() { println!(); }\n");
        GitDiff.GitScope scope = GitDiff.resolveProjectGitScope(dir, sub, null);
        String diff = GitDiff.gitUnifiedDiffScoped(
                dir, scope.gitRoot(), scope.pathPrefix(), "app/backoffice/src/lib.rs", "main", 3);
        assertThat(diff).contains("println");
    }

    @Test
    void empty_diff_base_lists_tracked_files_since_empty_tree() throws IOException, InterruptedException {
        write(dir.resolve("pkg/src/lib.rs"), "fn main() {}\n");
        git(dir, "init");
        git(dir, "add", ".");
        commit(dir, "init");
        assertThat(GitDiff.collectChangedFilesForBase(dir, "empty")).anyMatch(path -> path.endsWith("pkg/src/lib.rs"));
    }
}
