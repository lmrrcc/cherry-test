package com.cherrytest.cli.report;

import com.cherrytest.cli.ChangedFiles;
import com.cherrytest.cli.ProjectGroup;
import com.cherrytest.cli.RunDir;
import com.cherrytest.cli.coverage.ChangedFileCoverage;
import com.cherrytest.cli.coverage.Coverage;
import com.cherrytest.cli.coverage.CoverageIgnore;
import com.cherrytest.common.gitdiff.GitDiff;
import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.common.rustcompat.RustFloatFormat;
import com.cherrytest.common.rustcompat.RustOrdering;
import com.cherrytest.common.rustcompat.RustText;
import com.cherrytest.core.TestItConfig;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

public final class DiffView {
    private DiffView() {}

    public static List<AddedLines> addedLinesByFile(List<ProjectDiffSection> sections) {
        List<AddedLines> files = new ArrayList<>();
        for (ProjectDiffSection project : sections) {
            for (ModuleDiffSection module : project.modules()) {
                for (FileDiffSection file : module.files()) {
                    TreeSet<Long> lines = new TreeSet<>(Long::compareUnsigned);
                    for (DiffHunk hunk : file.hunks()) {
                        for (DiffLine line : hunk.lines()) {
                            if (line.tag() == DiffTag.ADD && line.newNo() != null) {
                                lines.add(line.newNo());
                            }
                        }
                    }
                    if (!lines.isEmpty()) {
                        files.add(new AddedLines(file.path(), lines));
                    }
                }
            }
        }
        return files;
    }

    public static List<ProjectDiffSection> buildProjectDiffSections(
            Path workspaceRoot,
            RunDir runDir,
            TestItConfig config,
            List<ProjectGroup> projects,
            List<ProjectReport> projectReports,
            CoverageIgnore coverageIgnore) {
        TreeSet<String> projectIds = new TreeSet<>(RustOrdering.STRING_ORDER);
        projectReports.forEach(report -> projectIds.add(report.projectId()));
        List<ProjectDiffSection> out = new ArrayList<>();
        for (String projectId : projectIds) {
            List<ProjectReport> reports = projectReports.stream()
                    .filter(report -> report.projectId().equals(projectId))
                    .toList();
            boolean testsPassed = reports.stream().allMatch(ProjectReport::testsPassed);
            TreeSet<String> paths = new TreeSet<>(RustOrdering.STRING_ORDER);
            for (ProjectReport report : reports) {
                for (ChangedFileCoverage file : report.changedFiles()) {
                    paths.add(file.path());
                }
            }
            String diffBase = config.diffBaseForProject(projectId);
            ProjectGroup project = projects.stream()
                    .filter(group -> group.id().equals(projectId))
                    .findFirst()
                    .orElseThrow(() -> new AnyhowError("unknown project " + projectId));
            List<String> modulePrefixes = ChangedFiles.packagePrefixes(config, project);
            List<FileDiffSection> files = new ArrayList<>();
            for (String path : paths) {
                List<String> packages =
                        reports.stream().map(ProjectReport::packageName).toList();
                boolean coverageIgnored = coverageIgnore.isIgnored(path);
                SortedMap<Long, Boolean> lineCov = coverageIgnored
                        ? new TreeMap<>()
                        : Coverage.mergeLineCoverageForProjectFile(runDir, projectId, packages, path, coverageIgnore);
                Path configBase = config.configBase();
                Path projectRoot;
                try {
                    projectRoot = config.projectPath(projectId);
                } catch (AnyhowError error) {
                    projectRoot = workspaceRoot.resolve(projectId);
                }
                GitDiff.GitScope scope = GitDiff.resolveProjectGitScope(configBase, projectRoot, null);
                String diffText = GitDiff.gitUnifiedDiffScoped(
                        configBase, scope.gitRoot(), scope.pathPrefix(), path, diffBase, 5);
                boolean packageFailed = packageFailedForPath(path, projectId, reports, modulePrefixes);
                FileDiffSection section = buildFileDiffSection(path, diffText, lineCov, packageFailed);
                if (coverageIgnored) {
                    section = stripCoverage(section);
                }
                if (section.hunks().isEmpty()
                        && section.adds() == 0
                        && section.dels() == 0
                        && !section.unparsedDiff()
                        && lineCov.isEmpty()) {
                    continue;
                }
                files.add(section);
            }
            if (files.isEmpty()) {
                continue;
            }
            files.sort((left, right) -> RustOrdering.compareStrings(left.path(), right.path()));
            List<ModuleDiffSection> modules = groupFilesIntoModules(files, modulePrefixes);
            long fileCount =
                    modules.stream().mapToLong(ModuleDiffSection::fileCount).sum();
            out.add(new ProjectDiffSection(
                    projectId,
                    testsPassed,
                    fileCount,
                    aggregatePctValues(
                            modules.stream().map(ModuleDiffSection::coveragePct).toList()),
                    aggregatePctValues(modules.stream()
                            .map(ModuleDiffSection::uncoveredPct)
                            .toList()),
                    modules));
        }
        return out;
    }

    static boolean packageFailedForPath(
            String path, String projectId, List<ProjectReport> reports, List<String> modulePrefixes) {
        String module = ChangedFiles.modulePathForFile(path, modulePrefixes);
        for (ProjectReport report : reports) {
            if (module.equals(projectId + "/" + report.packageName())) {
                return !report.testsPassed();
            }
        }
        return false;
    }

    public static List<ModuleDiffSection> groupFilesIntoModules(
            List<FileDiffSection> files, List<String> modulePrefixes) {
        TreeMap<String, List<FileDiffSection>> grouped = new TreeMap<>(RustOrdering.STRING_ORDER);
        for (FileDiffSection file : files) {
            grouped.computeIfAbsent(
                            ChangedFiles.modulePathForFile(file.path(), modulePrefixes), key -> new ArrayList<>())
                    .add(file);
        }
        List<ModuleDiffSection> modules = new ArrayList<>();
        for (Map.Entry<String, List<FileDiffSection>> entry : grouped.entrySet()) {
            List<FileDiffSection> moduleFiles = new ArrayList<>(entry.getValue());
            moduleFiles.sort((left, right) -> RustOrdering.compareStrings(left.path(), right.path()));
            boolean hasUncovered = moduleFiles.stream().anyMatch(FileDiffSection::hasUncovered);
            modules.add(new ModuleDiffSection(
                    entry.getKey(),
                    moduleFiles.size(),
                    aggregatePctValues(moduleFiles.stream()
                            .map(FileDiffSection::coveragePct)
                            .toList()),
                    aggregatePctValues(moduleFiles.stream()
                            .map(FileDiffSection::uncoveredPct)
                            .toList()),
                    hasUncovered,
                    moduleFiles));
        }
        return modules;
    }

    static FileDiffSection buildFileDiffSection(
            String path, String diffText, SortedMap<Long, Boolean> lineCov, boolean projectFailed) {
        List<DiffHunk> hunks = parseUnifiedDiff(diffText, lineCov, projectFailed);
        boolean unparsedDiff = !RustText.trim(diffText).isEmpty() && hunks.isEmpty();
        long adds = 0;
        long dels = 0;
        boolean hasUncovered = false;
        long failLines = 0;
        for (DiffHunk hunk : hunks) {
            for (DiffLine line : hunk.lines()) {
                switch (line.tag()) {
                    case ADD -> adds++;
                    case DEL -> dels++;
                    case CONTEXT -> {}
                }
                switch (line.coverage()) {
                    case UNCOVERED -> hasUncovered = true;
                    case FAIL -> {
                        hasUncovered = true;
                        failLines++;
                    }
                    case COVERED, NA -> {}
                }
            }
        }
        if (hunks.isEmpty() && !lineCov.isEmpty()) {
            for (boolean covered : lineCov.values()) {
                if (!covered) {
                    hasUncovered = true;
                    break;
                }
            }
        }
        double[] breakdown = hunks.isEmpty() && !lineCov.isEmpty()
                ? lineMapCoverageBreakdown(lineCov)
                : diffCoverageBreakdown(hunks);
        return new FileDiffSection(
                path, adds, dels, breakdown[0], breakdown[1], hasUncovered, failLines, unparsedDiff, hunks);
    }

    static FileDiffSection stripCoverage(FileDiffSection section) {
        List<DiffHunk> hunks = new ArrayList<>();
        for (DiffHunk hunk : section.hunks()) {
            hunks.add(new DiffHunk(
                    hunk.header(),
                    hunk.lines().stream()
                            .map(line -> line.withCoverage(LineCov.NA))
                            .toList()));
        }
        double[] breakdown = diffCoverageBreakdown(hunks);
        return new FileDiffSection(
                section.path(),
                section.adds(),
                section.dels(),
                breakdown[0],
                breakdown[1],
                false,
                0,
                section.unparsedDiff(),
                hunks);
    }

    public static double[] diffCoverageBreakdown(List<DiffHunk> hunks) {
        long hit = 0;
        long found = 0;
        for (DiffHunk hunk : hunks) {
            for (DiffLine line : hunk.lines()) {
                switch (line.coverage()) {
                    case COVERED -> {
                        found++;
                        hit++;
                    }
                    case UNCOVERED, FAIL -> found++;
                    case NA -> {}
                }
            }
        }
        if (found == 0) {
            return new double[] {Double.NaN, Double.NaN};
        }
        double covered = ((double) hit / (double) found) * 100.0;
        return new double[] {covered, 100.0 - covered};
    }

    private static double[] lineMapCoverageBreakdown(SortedMap<Long, Boolean> lineCov) {
        if (lineCov.isEmpty()) {
            return new double[] {Double.NaN, Double.NaN};
        }
        long found = lineCov.size();
        long hit = lineCov.values().stream().filter(Boolean::booleanValue).count();
        double covered = ((double) hit / (double) found) * 100.0;
        return new double[] {covered, 100.0 - covered};
    }

    public static String[] formatCoveredUncoveredPair(double covered, double uncovered) {
        return new String[] {formatPct(covered), formatPct(uncovered)};
    }

    private static double aggregatePctValues(List<Double> values) {
        List<Double> present =
                values.stream().filter(value -> !Double.isNaN(value)).toList();
        if (present.isEmpty()) {
            return Double.NaN;
        }
        double sum = 0.0;
        for (double value : present) {
            sum += value;
        }
        return sum / (double) present.size();
    }

    public static List<DiffHunk> parseUnifiedDiff(
            String diffText, SortedMap<Long, Boolean> lineCov, boolean projectFailed) {
        List<DiffHunk> hunks = new ArrayList<>();
        String header = null;
        List<DiffLine> lines = new ArrayList<>();
        long oldLine = 0;
        long newLine = 0;
        for (String line : RustText.lines(diffText)) {
            if (line.startsWith("@@ ")) {
                if (header != null && !lines.isEmpty()) {
                    hunks.add(new DiffHunk(header, lines));
                }
                long[] parsed = parseHunkHeader(line);
                oldLine = parsed[0];
                newLine = parsed[1];
                header = line;
                lines = new ArrayList<>();
                continue;
            }
            if (header == null) {
                continue;
            }
            if (line.startsWith("\\")) {
                continue;
            }
            if (line.isEmpty()) {
                continue;
            }
            char tagByte = line.charAt(0);
            String text = line.codePointAt(0) < 0x80 ? line.substring(1) : "";
            DiffTag tag;
            Long oldNo;
            Long newNo;
            if (tagByte == '+') {
                newNo = newLine;
                newLine = (newLine + 1) & 0xFFFFFFFFL;
                tag = DiffTag.ADD;
                oldNo = null;
            } else if (tagByte == '-') {
                oldNo = oldLine;
                oldLine = (oldLine + 1) & 0xFFFFFFFFL;
                tag = DiffTag.DEL;
                newNo = null;
            } else {
                oldNo = oldLine;
                newNo = newLine;
                oldLine = (oldLine + 1) & 0xFFFFFFFFL;
                newLine = (newLine + 1) & 0xFFFFFFFFL;
                tag = DiffTag.CONTEXT;
            }
            lines.add(new DiffLine(oldNo, newNo, tag, text, coverageForLine(newNo, tag, lineCov, projectFailed)));
        }
        if (header != null && !lines.isEmpty()) {
            hunks.add(new DiffHunk(header, lines));
        }
        return hunks;
    }

    static LineCov coverageForLine(
            @Nullable Long newNo, DiffTag tag, SortedMap<Long, Boolean> lineCov, boolean projectFailed) {
        if (tag == DiffTag.DEL || newNo == null) {
            return LineCov.NA;
        }
        Boolean covered = lineCov.get(newNo);
        if (covered == null) {
            return LineCov.NA;
        }
        if (covered) {
            return LineCov.COVERED;
        }
        return projectFailed ? LineCov.FAIL : LineCov.UNCOVERED;
    }

    private static long[] parseHunkHeader(String line) {
        long oldStart = 1;
        long newStart = 1;
        List<String> parts = RustText.splitWhitespace(line);
        if (parts.size() >= 3) {
            if (parts.get(1).startsWith("-")) {
                oldStart = parseOr(parts.get(1).substring(1).split(",", -1)[0], 1);
            }
            if (parts.get(2).startsWith("+")) {
                newStart = parseOr(parts.get(2).substring(1).split(",", -1)[0], 1);
            }
        }
        return new long[] {oldStart, newStart};
    }

    private static long parseOr(String text, long fallback) {
        try {
            return com.cherrytest.common.rustcompat.RustParse.u32(text);
        } catch (AnyhowError error) {
            return fallback;
        }
    }

    public static String formatPct(double value) {
        if (Double.isNaN(value)) {
            return "-";
        }
        return RustFloatFormat.fixed(value, 0) + "%";
    }
}
