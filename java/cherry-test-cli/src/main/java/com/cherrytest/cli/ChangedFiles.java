package com.cherrytest.cli;

import com.cherrytest.cli.coverage.Coverage;
import com.cherrytest.cli.coverage.FileCoverage;
import com.cherrytest.common.config.ReviewChangedScope;
import com.cherrytest.common.gitdiff.ChangedFilesProject;
import com.cherrytest.common.gitdiff.GitDiff;
import com.cherrytest.common.rustcompat.RustOrdering;
import com.cherrytest.core.ProjectConfig;
import com.cherrytest.core.TestItConfig;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

public final class ChangedFiles {
    private ChangedFiles() {}

    public static Map<String, List<String>> changedFilesByProject(
            Path workspaceRoot, List<ProjectGroup> projects, TestItConfig config) {
        Path configBase = config.configBase();
        List<ChangedFilesProject> changedProjects = new ArrayList<>();
        for (ProjectGroup project : projects) {
            Path root = projectRoot(workspaceRoot, config, project);
            GitDiff.GitScope scope = GitDiff.resolveProjectGitScope(configBase, root, null);
            changedProjects.add(new ChangedFilesProject(
                    project.id(),
                    config.diffBaseForProject(project.id()),
                    ReviewChangedScope.BRANCH,
                    packagePrefixes(config, project),
                    scope.gitRoot(),
                    configBase,
                    scope.pathPrefix(),
                    List.of()));
        }
        return GitDiff.changedFilesByProject(workspaceRoot, changedProjects);
    }

    private static Path projectRoot(Path workspaceRoot, TestItConfig config, ProjectGroup group) {
        ProjectConfig project = config.projects().get(group.id());
        if (project != null && project.path() != null) {
            return workspaceRoot.resolve(project.path());
        }
        return workspaceRoot.resolve(group.id());
    }

    public static List<String> packagePrefixes(TestItConfig config, ProjectGroup project) {
        List<String> prefixes = new ArrayList<>();
        ProjectConfig projectConfig = config.projects().get(project.id());
        if (projectConfig != null) {
            String base = projectConfig.path() == null ? project.id() : projectConfig.path();
            for (String packageName : projectConfig.packages().keySet()) {
                if (base.equals(".") || base.isEmpty()) {
                    prefixes.add(packageName + "/");
                } else {
                    prefixes.add(base + "/" + packageName + "/");
                }
            }
        }
        for (WorkspaceMember member : project.members()) {
            Path parent = member.manifestPath().getParent();
            String relative = parent == null ? "" : parent.toString().replace('\\', '/');
            if (parent != null || !member.manifestPath().toString().isEmpty()) {
                prefixes.add(relative + "/");
            }
            prefixes.add(member.name() + "/");
        }
        if (prefixes.isEmpty()) {
            prefixes.add(project.id() + "/");
        }
        return GitDiff.sortedDedup(prefixes);
    }

    public static List<String> expandChangedWithLcovSources(
            List<String> gitChanged, List<FileCoverage> lcovFiles, List<String> prefixes) {
        TreeSet<String> paths = new TreeSet<>(RustOrdering.STRING_ORDER);
        paths.addAll(gitChanged);
        for (FileCoverage file : lcovFiles) {
            if (file.linesFound() == 0) {
                continue;
            }
            if (!GitDiff.isInstrumentableSourcePath(file.path())) {
                continue;
            }
            String normalized = Coverage.normalizePath(file.path());
            if (!GitDiff.matchesPackagePrefix(normalized, prefixes)) {
                continue;
            }
            paths.add(normalized);
        }
        return List.copyOf(paths);
    }

    public static String modulePathForFile(String path, List<String> prefixes) {
        String normalized = path.startsWith("./") ? path.substring(2) : path;
        String best = null;
        for (String prefix : prefixes) {
            if (pathMatchesPrefix(normalized, prefix)) {
                String module = trimEndSlashes(prefix);
                if (best == null || utf8Length(module) > utf8Length(best)) {
                    best = module;
                }
            }
        }
        if (best != null) {
            return best;
        }
        String[] segments = normalized.split("/", -1);
        List<String> firstTwo = new ArrayList<>();
        for (int index = 0; index < segments.length && index < 2; index++) {
            firstTwo.add(segments[index]);
        }
        return String.join("/", firstTwo);
    }

    private static int utf8Length(String value) {
        return com.cherrytest.common.rustcompat.RustText.utf8Length(value);
    }

    private static String trimEndSlashes(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }

    private static boolean pathMatchesPrefix(String path, String prefix) {
        return path.startsWith(prefix) || path.startsWith("./" + prefix) || (path + "/").startsWith(prefix);
    }
}
