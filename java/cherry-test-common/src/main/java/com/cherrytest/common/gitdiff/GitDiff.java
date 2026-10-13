package com.cherrytest.common.gitdiff;

import com.cherrytest.common.config.ReviewChangedScope;
import com.cherrytest.common.process.CommandOutput;
import com.cherrytest.common.process.SystemCommand;
import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.common.rustcompat.RustDebug;
import com.cherrytest.common.rustcompat.RustOrdering;
import com.cherrytest.common.rustcompat.RustPath;
import com.cherrytest.common.rustcompat.RustText;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

public final class GitDiff {
    public static final String GIT_EMPTY_TREE = "4b825dc642cb6eb9a060e54bf44d4eab43ad3e839";
    static final String EMPTY_TREE = GIT_EMPTY_TREE;
    private static final String NULL_DEVICE = "/dev/null";

    enum CollectChangedMode {
        BRANCH_ONLY,
        WORKTREE_ONLY,
        BRANCH_AND_WORKTREE
    }

    public record GitScope(Path gitRoot, String pathPrefix) {}

    private GitDiff() {}

    public static Map<String, List<String>> changedFilesByProject(
            Path workspaceRoot, List<ChangedFilesProject> projects) {
        List<String> nestedPrefixes = nestedScopePrefixes(projects);
        Map<String, List<String>> mapped = new TreeMap<>(RustOrdering.STRING_ORDER);
        for (ChangedFilesProject project : projects) {
            List<String> files = new ArrayList<>();
            if (project.gitScopes().isEmpty()) {
                CollectChangedMode mode = collectChangedModeForScope(project, project.gitRoot(), project.pathPrefix());
                List<String> changed = collectChangedFilesInner(project.gitRoot(), project.diffBase(), mode);
                for (String path : changed) {
                    String prefixed = prefixWorkspacePath(project.pathPrefix(), path);
                    if (matchesPackagePrefix(prefixed, project.packagePrefixes())
                            && !pathClaimedByNestedProject(prefixed, nestedPrefixes, project)) {
                        files.add(prefixed);
                    }
                }
            } else {
                for (NestedGitScope scope : project.gitScopes()) {
                    CollectChangedMode mode = collectChangedModeForScope(project, scope.gitRoot(), scope.pathPrefix());
                    List<String> changed = collectChangedFilesInner(scope.gitRoot(), project.diffBase(), mode);
                    for (String path : changed) {
                        String prefixed = prefixWorkspacePath(scope.pathPrefix(), path);
                        if (matchesPackagePrefix(prefixed, project.packagePrefixes())
                                && !pathClaimedByNestedProject(prefixed, nestedPrefixes, project)) {
                            files.add(prefixed);
                        }
                    }
                }
            }
            List<String> unique = sortedDedup(files);
            if (!unique.isEmpty()) {
                mapped.put(project.id(), unique);
            }
        }
        return mapped;
    }

    public static List<String> sortedDedup(List<String> values) {
        List<String> sorted = new ArrayList<>(values);
        sorted.sort(RustOrdering.STRING_ORDER);
        List<String> unique = new ArrayList<>();
        for (String value : sorted) {
            if (unique.isEmpty() || !unique.getLast().equals(value)) {
                unique.add(value);
            }
        }
        return unique;
    }

    private static List<String> nestedScopePrefixes(List<ChangedFilesProject> projects) {
        List<String> nestedPrefixes = new ArrayList<>();
        for (ChangedFilesProject project : projects) {
            if (!project.gitScopes().isEmpty()) {
                for (NestedGitScope scope : project.gitScopes()) {
                    if (!scope.pathPrefix().isEmpty()) {
                        nestedPrefixes.add(scope.pathPrefix());
                    }
                }
                continue;
            }
            if (separateRepoScope(project) && !project.pathPrefix().isEmpty()) {
                nestedPrefixes.add(project.pathPrefix());
            }
        }
        return sortedDedup(nestedPrefixes);
    }

    private static CollectChangedMode collectChangedModeForScope(
            ChangedFilesProject project, Path gitRoot, String pathPrefix) {
        Path workspaceRoot = RustPath.canonicalizeOrSelf(project.workspaceRoot());
        Path canonicalGitRoot = RustPath.canonicalizeOrSelf(gitRoot);
        boolean separate = !workspaceRoot.equals(canonicalGitRoot) || !pathPrefix.isEmpty();
        return switch (project.changedScope()) {
            case BRANCH -> CollectChangedMode.BRANCH_ONLY;
            case WORKTREE -> CollectChangedMode.WORKTREE_ONLY;
            case BRANCH_AND_WORKTREE ->
                separate ? CollectChangedMode.BRANCH_ONLY : CollectChangedMode.BRANCH_AND_WORKTREE;
        };
    }

    public static GitScope resolveProjectGitScope(Path configRoot, Path projectRoot, @Nullable Boolean forceSubmodule) {
        boolean separate = forceSubmodule != null ? forceSubmodule : isSeparateGitRepository(configRoot, projectRoot);
        String pathPrefix = separate ? workspacePathPrefix(configRoot, projectRoot) : "";
        Path gitRoot = separate ? projectRoot : configRoot;
        return new GitScope(gitRoot, pathPrefix);
    }

    public static boolean isSeparateGitRepository(Path configRoot, Path projectRoot) {
        Path configTop = gitToplevel(configRoot);
        if (configTop == null) {
            return false;
        }
        Path projectTop = gitToplevel(projectRoot);
        if (projectTop == null) {
            return false;
        }
        return !configTop.equals(projectTop);
    }

    public static String workspacePathPrefix(Path configRoot, Path projectRoot) {
        Path canonicalConfig = RustPath.canonicalizeOrSelf(configRoot);
        Path canonicalProject = RustPath.canonicalizeOrSelf(projectRoot);
        Path relative = RustPath.stripPrefix(canonicalProject, canonicalConfig);
        if (relative == null) {
            return "";
        }
        String rel = relative.toString().replace('\\', '/');
        return rel.isEmpty() ? "" : rel + "/";
    }

    public static String prefixWorkspacePath(String prefix, String relPath) {
        String stripped = relPath.startsWith("./") ? relPath.substring(2) : relPath;
        if (prefix.isEmpty()) {
            return stripped.replace('\\', '/');
        }
        String trimmedPrefix = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        return (trimmedPrefix + "/" + stripped).replace('\\', '/');
    }

    public static String stripWorkspacePathPrefix(String prefix, String workspacePath) {
        String stripped = workspacePath.startsWith("./") ? workspacePath.substring(2) : workspacePath;
        if (prefix.isEmpty()) {
            return stripped.replace('\\', '/');
        }
        String trimmedPrefix = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        if (stripped.equals(trimmedPrefix)) {
            return "";
        }
        String withSlash = trimmedPrefix + "/";
        if (stripped.startsWith(withSlash)) {
            return stripped.substring(withSlash.length()).replace('\\', '/');
        }
        return stripped.replace('\\', '/');
    }

    private static boolean separateRepoScope(ChangedFilesProject project) {
        if (!project.gitScopes().isEmpty()) {
            return true;
        }
        Path workspaceRoot = RustPath.canonicalizeOrSelf(project.workspaceRoot());
        Path gitRoot = RustPath.canonicalizeOrSelf(project.gitRoot());
        return !workspaceRoot.equals(gitRoot);
    }

    private static boolean pathClaimedByNestedProject(
            String path, List<String> nestedPrefixes, ChangedFilesProject project) {
        if (separateRepoScope(project)) {
            return false;
        }
        return nestedPrefixes.stream().anyMatch(path::startsWith);
    }

    public static String gitUnifiedDiffScoped(
            Path workspaceRoot,
            Path gitRoot,
            String pathPrefix,
            String workspaceRelativePath,
            String diffBase,
            long context) {
        String gitPath = pathPrefix.isEmpty()
                ? workspacePathInGitRepo(workspaceRoot, gitRoot, workspaceRelativePath)
                : stripWorkspacePathPrefix(pathPrefix, workspaceRelativePath);
        String unified = "-U" + context;
        Path full = RustJoin.join(workspaceRoot, workspaceRelativePath);
        if (!Files.exists(full)) {
            return "";
        }
        if (isUntracked(gitRoot, gitPath)) {
            String out = gitOutput(
                    gitRoot, List.of("diff", unified, "--no-color", "--no-index", "--", NULL_DEVICE, gitPath));
            if (!RustText.trim(out).isEmpty()) {
                return out;
            }
        }
        String cached = gitOutput(gitRoot, List.of("diff", unified, "--no-color", "--cached", "--", gitPath));
        if (!RustText.trim(cached).isEmpty()) {
            return cached;
        }
        String unstaged = gitOutput(gitRoot, List.of("diff", unified, "--no-color", "--", gitPath));
        if (!RustText.trim(unstaged).isEmpty()) {
            return unstaged;
        }
        if (headExists(gitRoot)) {
            if (isEmptyTreeBase(diffBase)) {
                String out = gitOutput(
                        gitRoot, List.of("diff", unified, "--no-color", "--no-index", "--", NULL_DEVICE, gitPath));
                if (!RustText.trim(out).isEmpty()) {
                    return out;
                }
            } else {
                String base = resolveDiffBase(gitRoot, diffBase);
                if (base != null) {
                    String range = branchDiffRange(base);
                    String out = gitOutput(gitRoot, List.of("diff", unified, "--no-color", range, "--", gitPath));
                    if (!RustText.trim(out).isEmpty()) {
                        return out;
                    }
                }
            }
            String head = gitOutput(gitRoot, List.of("diff", unified, "--no-color", "HEAD", "--", gitPath));
            if (!RustText.trim(head).isEmpty()) {
                return head;
            }
        }
        return "";
    }

    private static boolean isUntracked(Path workspaceRoot, String path) {
        try {
            CommandOutput output = SystemCommand.of("git")
                    .currentDir(workspaceRoot)
                    .args("ls-files", "--error-unmatch", path)
                    .output();
            return !output.success();
        } catch (IOException exception) {
            throw AnyhowError.context("git ls-files --error-unmatch", exception);
        }
    }

    private static boolean headExists(Path workspaceRoot) {
        return gitRevParse(workspaceRoot, "HEAD");
    }

    static List<String> collectChangedFilesInner(Path gitCwd, String diffBase, CollectChangedMode mode) {
        Path gitRoot = gitToplevel(gitCwd);
        if (gitRoot == null) {
            return List.of();
        }
        if (mode == CollectChangedMode.BRANCH_ONLY) {
            return finishChangedFilePaths(gitCwd, gitRoot, collectRangeDiffFiles(gitRoot, diffBase, true));
        }
        if (mode == CollectChangedMode.WORKTREE_ONLY) {
            return finishChangedFilePaths(gitCwd, gitRoot, collectWorktreeFiles(gitRoot));
        }
        List<String> files = new ArrayList<>();
        if (isEmptyTreeBase(diffBase)) {
            if (headExists(gitRoot)) {
                files.addAll(gitNameOnly(gitRoot, List.of("ls-tree", "-r", "--name-only", "HEAD")));
            }
        } else {
            String base = resolveDiffBaseForScope(gitRoot, diffBase, false);
            if (base != null && headExists(gitRoot)) {
                files.addAll(gitNameOnly(gitRoot, List.of("diff", "--name-only", branchDiffRange(base))));
            }
        }
        files.addAll(collectWorktreeFiles(gitRoot));
        return finishChangedFilePaths(gitCwd, gitRoot, files);
    }

    private static List<String> collectWorktreeFiles(Path gitRoot) {
        List<String> files = new ArrayList<>();
        files.addAll(gitNameOnly(gitRoot, List.of("diff", "--name-only")));
        files.addAll(gitNameOnly(gitRoot, List.of("diff", "--cached", "--name-only")));
        files.addAll(gitNameOnly(gitRoot, List.of("ls-files", "--others", "--exclude-standard")));
        if (headExists(gitRoot)) {
            files.addAll(gitNameOnly(gitRoot, List.of("diff", "--name-only", "HEAD")));
        }
        return files;
    }

    private static List<String> finishChangedFilePaths(Path workspaceRoot, Path gitRoot, List<String> files) {
        List<String> result = new ArrayList<>();
        for (String path : sortedDedup(files)) {
            String relative = pathRelativeToWorkspace(workspaceRoot, gitRoot, path);
            if (relative != null) {
                result.add(normalizeChangedPath(workspaceRoot, relative));
            }
        }
        return result;
    }

    public static @Nullable Path gitToplevel(Path workspaceRoot) {
        CommandOutput output;
        try {
            output = SystemCommand.of("git")
                    .currentDir(workspaceRoot)
                    .args("rev-parse", "--show-toplevel")
                    .output();
        } catch (IOException exception) {
            return null;
        }
        if (!output.success()) {
            return null;
        }
        String path;
        try {
            path = RustText.trim(output.stdoutUtf8Strict());
        } catch (AnyhowError error) {
            return null;
        }
        if (path.isEmpty()) {
            return null;
        }
        return Path.of(path);
    }

    private static @Nullable String pathRelativeToWorkspace(Path workspaceRoot, Path gitRoot, String gitPath) {
        String stripped = gitPath.startsWith("./") ? gitPath.substring(2) : gitPath;
        Path canonicalWorkspace = RustPath.canonicalizeOrSelf(workspaceRoot);
        Path canonicalGit = RustPath.canonicalizeOrSelf(gitRoot);
        Path full = RustPath.canonicalizeOrSelf(RustJoin.join(canonicalGit, stripped));
        Path relative = RustPath.stripPrefix(full, canonicalWorkspace);
        return relative == null ? null : relative.toString().replace('\\', '/');
    }

    private static String workspacePathInGitRepo(Path workspaceRoot, Path gitRoot, String workspaceRelativePath) {
        Path canonicalWorkspace = RustPath.canonicalizeOrSelf(workspaceRoot);
        Path canonicalGit = RustPath.canonicalizeOrSelf(gitRoot);
        Path full = RustJoin.join(canonicalWorkspace, workspaceRelativePath);
        Path relative = RustPath.stripPrefix(full, canonicalGit);
        return relative == null
                ? workspaceRelativePath.replace('\\', '/')
                : relative.toString().replace('\\', '/');
    }

    public static boolean matchesPackagePrefix(String path, List<String> prefixes) {
        return prefixes.stream().anyMatch(prefix -> pathMatchesPrefix(path, prefix));
    }

    public static boolean isInstrumentableSourcePath(String path) {
        int slash = path.lastIndexOf('/');
        String name = slash < 0 ? path : path.substring(slash + 1);
        if (name.equals("Cargo.toml") || name.equals("package.json")) {
            return false;
        }
        int dot = path.lastIndexOf('.');
        String extension = dot < 0 ? path : path.substring(dot + 1);
        return switch (extension) {
            case "rs", "ts", "tsx", "js", "jsx", "vue", "kt", "java", "c", "h", "cpp", "cc", "cxx", "hpp", "hxx" ->
                true;
            default -> false;
        };
    }

    private static boolean pathMatchesPrefix(String path, String prefix) {
        return path.startsWith(prefix) || path.startsWith("./" + prefix) || (path + "/").startsWith(prefix);
    }

    public static String modulePathForFile(String path, List<String> prefixes) {
        String normalized = path.startsWith("./") ? path.substring(2) : path;
        String bestPrefix = null;
        for (String prefix : prefixes) {
            if (pathMatchesPrefix(normalized, prefix)) {
                String stem = trimEndSlashes(prefix);
                if (bestPrefix == null || RustText.utf8Length(stem) > RustText.utf8Length(bestPrefix)) {
                    bestPrefix = stem;
                }
            }
        }
        if (bestPrefix != null) {
            return moduleUnderPrefix(normalized, bestPrefix);
        }
        String[] segments = normalized.split("/", -1);
        List<String> firstTwo = new ArrayList<>();
        for (int index = 0; index < segments.length && index < 2; index++) {
            firstTwo.add(segments[index]);
        }
        return String.join("/", firstTwo);
    }

    private static String trimEndSlashes(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }

    private static String moduleUnderPrefix(String normalized, String prefixStem) {
        if (normalized.equals(prefixStem)) {
            return prefixStem;
        }
        String withSlash = prefixStem + "/";
        if (!normalized.startsWith(withSlash)) {
            return prefixStem;
        }
        String rest = normalized.substring(withSlash.length());
        if (rest.isEmpty() || !rest.contains("/")) {
            return prefixStem;
        }
        if (prefixStem.contains("/")) {
            return prefixStem;
        }
        String segment = rest.split("/", -1)[0];
        return prefixStem + "/" + segment;
    }

    public static boolean isEmptyTreeBase(String diffBase) {
        return switch (RustText.toAsciiLowercase(RustText.trim(diffBase))) {
            case "empty", "empty-tree", "root" -> true;
            default -> false;
        };
    }

    public static @Nullable String resolveDiffBase(Path workspaceRoot, String diffBase) {
        return resolveDiffBaseForScope(workspaceRoot, diffBase, false);
    }

    public static List<String> diffBaseRefCandidates(String diffBase, boolean rangeOnly) {
        List<String> candidates = new ArrayList<>(
                diffBase.isEmpty() ? List.of("main", "origin/main") : List.of(diffBase, "origin/" + diffBase));
        if (rangeOnly) {
            Collections.reverse(candidates);
            for (String extra : List.of("origin/master", "master", "origin/develop", "develop")) {
                if (!candidates.contains(extra)) {
                    candidates.add(extra);
                }
            }
        }
        return candidates;
    }

    public static @Nullable String resolveDiffBaseForScope(Path gitRoot, String diffBase, boolean rangeOnly) {
        if (isEmptyTreeBase(diffBase)) {
            return rangeOnly ? null : EMPTY_TREE;
        }
        for (String candidate : diffBaseRefCandidates(diffBase, rangeOnly)) {
            if (gitRevParse(gitRoot, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static String branchDiffRange(String base) {
        return base + "..HEAD";
    }

    public record BranchRange(String command, List<String> files) {}

    public static BranchRange branchRangeChangedFiles(Path gitRoot, String base) {
        String range = base + "...HEAD";
        String command = "git -C " + gitRoot + " diff --name-only " + range;
        if (!headExists(gitRoot)) {
            return new BranchRange(command, List.of());
        }
        return new BranchRange(command, gitNameOnly(gitRoot, List.of("diff", "--name-only", range)));
    }

    static List<String> collectRangeDiffFiles(Path gitRoot, String diffBase, boolean rangeOnly) {
        String base = resolveDiffBaseForScope(gitRoot, diffBase, rangeOnly);
        if (base == null) {
            return List.of();
        }
        if (base.equals(EMPTY_TREE)) {
            if (rangeOnly) {
                return List.of();
            }
            if (headExists(gitRoot)) {
                return gitNameOnly(gitRoot, List.of("ls-tree", "-r", "--name-only", "HEAD"));
            }
            return List.of();
        }
        return branchRangeChangedFiles(gitRoot, base).files();
    }

    private static boolean gitRevParse(Path workspaceRoot, String revision) {
        try {
            return SystemCommand.of("git")
                    .currentDir(workspaceRoot)
                    .args("rev-parse", "--verify", revision)
                    .output()
                    .success();
        } catch (IOException exception) {
            return false;
        }
    }

    private static List<String> gitNameOnly(Path workspaceRoot, List<String> arguments) {
        CommandOutput output;
        try {
            output = SystemCommand.of("git")
                    .currentDir(workspaceRoot)
                    .args(arguments)
                    .output();
        } catch (IOException exception) {
            throw AnyhowError.context("git " + String.join(" ", arguments), exception);
        }
        if (!output.success()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (String line : RustText.lines(output.stdoutUtf8Strict())) {
            String trimmed = RustText.trim(line);
            if (!trimmed.isEmpty()) {
                lines.add(trimmed);
            }
        }
        return lines;
    }

    private static String gitOutput(Path workspaceRoot, List<String> arguments) {
        CommandOutput output;
        try {
            output = SystemCommand.of("git")
                    .currentDir(workspaceRoot)
                    .args(arguments)
                    .output();
        } catch (IOException exception) {
            throw AnyhowError.context("git " + String.join(" ", arguments), exception);
        }
        if (!output.success()) {
            String stderr = output.stderrLossy();
            if (stderr.contains("no such path") || stderr.contains("does not exist")) {
                return "";
            }
            if (stderr.contains("bad revision")) {
                return "";
            }
        }
        return output.stdoutUtf8Strict();
    }

    private static String normalizeChangedPath(Path workspaceRoot, String path) {
        String stripped = path.startsWith("./") ? path.substring(2) : path;
        Path full = RustJoin.join(workspaceRoot, stripped);
        Path relative = RustPath.stripPrefix(full, workspaceRoot);
        return relative == null
                ? stripped.replace('\\', '/')
                : relative.toString().replace('\\', '/');
    }

    public static SortedMap<String, List<String>> mapFilesToProjects(
            Path workspaceRoot, List<String> projectIds, List<String> changed) {
        SortedMap<String, List<String>> mapped = new TreeMap<>(RustOrdering.STRING_ORDER);
        for (String projectId : projectIds) {
            List<String> files = mapped.computeIfAbsent(projectId, key -> new ArrayList<>());
            String prefix = projectId + "/";
            for (String path : changed) {
                if (path.startsWith(prefix) || path.startsWith("./" + prefix)) {
                    files.add(normalizeChangedPath(workspaceRoot, path));
                }
            }
            mapped.put(projectId, sortedDedup(files));
        }
        return mapped;
    }

    public static List<String> collectChangedFilesForBase(Path workspaceRoot, String diffBase) {
        return collectChangedFilesInner(workspaceRoot, diffBase, CollectChangedMode.BRANCH_AND_WORKTREE);
    }

    public static String gitUnifiedDiff(Path workspaceRoot, String path, String diffBase, int context) {
        Path gitRoot = gitToplevel(workspaceRoot);
        if (gitRoot == null) {
            return "";
        }
        return gitUnifiedDiffScoped(workspaceRoot, gitRoot, "", path, diffBase, context);
    }

    public static List<String> collectChangedFilesShellCommands(
            Path gitCwd, String diffBase, ReviewChangedScope changedScope, boolean separateRepo) {
        Path gitRoot = gitToplevel(gitCwd);
        if (gitRoot == null) {
            return List.of("# not a git repository (cwd " + gitCwd + ")");
        }
        String root = gitRoot.toString();
        return switch (reviewCollectMode(changedScope, separateRepo)) {
            case BRANCH_ONLY -> branchOnlyShellCommands(gitRoot, diffBase, true);
            case WORKTREE_ONLY -> worktreeShellCommands(root);
            case BRANCH_AND_WORKTREE -> {
                List<String> commands = new ArrayList<>(branchOnlyShellCommands(gitRoot, diffBase, false));
                commands.addAll(worktreeShellCommands(root));
                yield commands;
            }
        };
    }

    private static CollectChangedMode reviewCollectMode(ReviewChangedScope changedScope, boolean separateRepo) {
        return switch (changedScope) {
            case BRANCH -> CollectChangedMode.BRANCH_ONLY;
            case WORKTREE -> CollectChangedMode.WORKTREE_ONLY;
            case BRANCH_AND_WORKTREE ->
                separateRepo ? CollectChangedMode.BRANCH_ONLY : CollectChangedMode.BRANCH_AND_WORKTREE;
        };
    }

    private static List<String> branchOnlyShellCommands(Path gitRoot, String diffBase, boolean rangeOnly) {
        String root = gitRoot.toString();
        String base = resolveDiffBaseForScope(gitRoot, diffBase, rangeOnly);
        if (base == null) {
            return List.of("# no git ref resolves for diff_base=" + RustDebug.string(diffBase) + " under " + root);
        }
        if (base.equals(EMPTY_TREE)) {
            if (rangeOnly) {
                return List.of("# empty-tree diff_base ignored for submodule/range-only scope under " + root);
            }
            return List.of("git -C " + root + " ls-tree -r --name-only HEAD");
        }
        String range = rangeOnly ? base + "...HEAD" : branchDiffRange(base);
        return List.of("git -C " + root + " diff --name-only " + range);
    }

    private static List<String> worktreeShellCommands(String gitRootDisplay) {
        return List.of(
                "git -C " + gitRootDisplay + " diff --name-only",
                "git -C " + gitRootDisplay + " diff --cached --name-only",
                "git -C " + gitRootDisplay + " ls-files --others --exclude-standard",
                "# when HEAD exists: git -C " + gitRootDisplay + " diff --name-only HEAD");
    }

    public static List<NestedGitScope> discoverNestedGitScopes(
            Path configRoot, Path projectRoot, List<String> moduleIds, @Nullable Boolean forceSubmodule) {
        if (Boolean.FALSE.equals(forceSubmodule)) {
            return List.of();
        }
        List<NestedGitScope> scopes = new ArrayList<>();
        for (String moduleId : moduleIds) {
            Path moduleRoot = projectRoot.resolve(moduleId);
            if (!Files.isDirectory(moduleRoot)) {
                continue;
            }
            boolean separate = Boolean.TRUE.equals(forceSubmodule) || isSeparateGitRepository(configRoot, moduleRoot);
            if (!separate) {
                continue;
            }
            scopes.add(new NestedGitScope(moduleId, moduleRoot, workspacePathPrefix(configRoot, moduleRoot)));
        }
        if (!scopes.isEmpty()) {
            return scopes;
        }
        if (Boolean.TRUE.equals(forceSubmodule) || isSeparateGitRepository(configRoot, projectRoot)) {
            return List.of(new NestedGitScope("", projectRoot, workspacePathPrefix(configRoot, projectRoot)));
        }
        return List.of();
    }
}
