package com.cherrytest.cli.coverage;

import com.cherrytest.cli.RunDir;
import com.cherrytest.cli.TestKind;
import com.cherrytest.cli.adapters.Lcov;
import com.cherrytest.common.process.SystemCommand;
import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.common.rustcompat.RustIoError;
import com.cherrytest.common.rustcompat.RustOrdering;
import com.cherrytest.common.rustcompat.RustText;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

public final class Coverage {
    private Coverage() {}

    public static boolean llvmCovAvailable() {
        try {
            return SystemCommand.of("cargo")
                    .args("llvm-cov", "--version")
                    .output()
                    .success();
        } catch (IOException exception) {
            return false;
        }
    }

    public static void mergeLcovFiles(List<Path> sources, Path destination) {
        if (sources.isEmpty()) {
            return;
        }
        Lcov.merge(sources, destination);
    }

    public static CoverageSummary summaryForLcov(Path lcovPath, CoverageIgnore ignore) {
        if (!Files.exists(lcovPath)) {
            return CoverageSummary.ZERO;
        }
        CoverageSummary total = CoverageSummary.ZERO;
        for (FileCoverage file : parseLcovFiles(lcovPath)) {
            if (!ignore.isIgnored(file.path())) {
                total = total.plus(file.summary());
            }
        }
        return total;
    }

    public static List<FileCoverage> parseLcovFiles(Path lcovPath) {
        if (!Files.exists(lcovPath)) {
            return List.of();
        }
        return parseLcovContent(read(lcovPath));
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw AnyhowError.context("read " + path, new AnyhowError(RustIoError.display(exception)));
        }
    }

    public static List<FileCoverage> parseLcovContent(String content) {
        List<FileCoverage> files = new ArrayList<>();
        MutableFile current = null;
        for (String line : RustText.lines(content)) {
            if (line.startsWith("SF:")) {
                if (current != null) {
                    files.add(current.toCoverage());
                }
                current = new MutableFile(RustText.trim(line.substring(3)));
            } else if (current != null) {
                if (line.startsWith("LH:")) {
                    current.linesHit = parseOrZero(line.substring(3));
                } else if (line.startsWith("LF:")) {
                    current.linesFound = parseOrZero(line.substring(3));
                } else if (line.startsWith("FNH:")) {
                    current.functionsHit = parseOrZero(line.substring(4));
                } else if (line.startsWith("FNF:")) {
                    current.functionsFound = parseOrZero(line.substring(4));
                } else if (line.equals("end_of_record")) {
                    files.add(current.toCoverage());
                    current = null;
                }
            }
        }
        if (current != null) {
            files.add(current.toCoverage());
        }
        return files;
    }

    private static long parseOrZero(String text) {
        Long value = RustNumbers.parseU64(RustText.trim(text));
        return value == null ? 0 : value;
    }

    public static List<FileLineCoverage> parseLcovLineCoverage(Path lcovPath) {
        if (!Files.exists(lcovPath)) {
            return List.of();
        }
        return parseLcovLineContent(read(lcovPath));
    }

    public static List<FileLineCoverage> parseLcovLineContent(String content) {
        List<FileLineCoverage> files = new ArrayList<>();
        String currentPath = null;
        TreeMap<Long, Boolean> lines = null;
        for (String line : RustText.lines(content)) {
            if (line.startsWith("SF:")) {
                if (currentPath != null && lines != null) {
                    files.add(new FileLineCoverage(currentPath, lines));
                }
                currentPath = RustText.trim(line.substring(3));
                lines = new TreeMap<>();
            } else if (currentPath != null && lines != null) {
                if (line.startsWith("DA:")) {
                    String rest = line.substring(3);
                    int comma = rest.indexOf(',');
                    if (comma >= 0) {
                        Long number = RustNumbers.parseU32(RustText.trim(rest.substring(0, comma)));
                        if (number != null) {
                            Long hits = RustNumbers.parseU64(RustText.trim(rest.substring(comma + 1)));
                            lines.put(number, (hits == null ? 0 : hits) > 0);
                        }
                    }
                } else if (line.equals("end_of_record")) {
                    files.add(new FileLineCoverage(currentPath, lines));
                    currentPath = null;
                    lines = null;
                }
            }
        }
        if (currentPath != null && lines != null) {
            files.add(new FileLineCoverage(currentPath, lines));
        }
        return files;
    }

    public static SortedMap<Long, Boolean> mergeLineCoverageMaps(Map<Long, Boolean> left, Map<Long, Boolean> right) {
        TreeMap<Long, Boolean> out = new TreeMap<>(left);
        right.forEach((line, hit) -> out.merge(line, hit, (existing, incoming) -> existing || incoming));
        return out;
    }

    public static SortedMap<Long, Boolean> lineCoverageForPath(List<FileLineCoverage> files, String changedPath) {
        SortedMap<Long, Boolean> merged = null;
        for (FileLineCoverage file : files) {
            if (pathMatchesChanged(file.path(), List.of(changedPath))) {
                merged = merged == null ? new TreeMap<>(file.lines()) : mergeLineCoverageMaps(merged, file.lines());
            }
        }
        return merged == null ? new TreeMap<>() : merged;
    }

    public static SortedMap<Long, Boolean> mergeLineCoverageForProjectFile(
            RunDir runDir, String projectId, List<String> packages, String changedPath, CoverageIgnore ignore) {
        CoveragePaths paths = new CoveragePaths(runDir, projectId);
        SortedMap<Long, Boolean> merged = new TreeMap<>();
        for (String packageName : packages) {
            Path unit = paths.kindLcovFile(packageName, TestKind.UNIT);
            Path packageLcov = paths.lcovFile(packageName);
            SortedMap<Long, Boolean> map = mergeLineCoverageFromLcovs(
                    Files.exists(unit) ? unit : null,
                    null,
                    Files.exists(packageLcov) ? packageLcov : null,
                    changedPath,
                    ignore);
            merged = mergeLineCoverageMaps(merged, map);
        }
        return merged;
    }

    public static SortedMap<Long, Boolean> mergeLineCoverageFromLcovs(
            @Nullable Path unitLcov,
            @Nullable Path integrationLcov,
            @Nullable Path mergedLcov,
            String changedPath,
            CoverageIgnore ignore) {
        SortedMap<Long, Boolean> result = null;
        for (Path path : new Path[] {unitLcov, integrationLcov, mergedLcov}) {
            if (path == null) {
                continue;
            }
            List<FileLineCoverage> files = ignore.filterLineCoverage(parseLcovLineCoverage(path));
            SortedMap<Long, Boolean> map = lineCoverageForPath(files, changedPath);
            result = result == null ? map : mergeLineCoverageMaps(result, map);
        }
        return result == null ? new TreeMap<>() : result;
    }

    public static String normalizeLcovSfPaths(String content, Path workspaceRoot, String packagePrefix) {
        String workspace = normalizePath(workspaceRoot.toString());
        String prefix = normalizePath(packagePrefix);
        StringBuilder out = new StringBuilder();
        for (String line : RustText.lines(content)) {
            if (line.startsWith("SF:")) {
                out.append("SF:")
                        .append(normalizeSfPath(RustText.trim(line.substring(3)), workspace, prefix))
                        .append('\n');
            } else {
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }

    private static String normalizeSfPath(String raw, String workspace, String packagePrefix) {
        String path = normalizePath(raw);
        if (path.startsWith(workspace + "/") || path.startsWith(packagePrefix + "/")) {
            return path;
        }
        if (path.startsWith("/") || path.contains(":/")) {
            return path;
        }
        return packagePrefix + "/" + path;
    }

    public static String normalizePath(String path) {
        return path.replace('\\', '/');
    }

    public static boolean pathMatchesChanged(String filePath, Collection<String> changedPaths) {
        String normalized = normalizePath(filePath);
        return changedPaths.stream().anyMatch(changed -> pathsReferToSameFile(normalized, normalizePath(changed)));
    }

    private static boolean pathsReferToSameFile(String left, String right) {
        if (left.equals(right)) {
            return true;
        }
        if (left.endsWith("/" + right) || right.endsWith("/" + left)) {
            return true;
        }
        String leftName = left.substring(left.lastIndexOf('/') + 1);
        String rightName = right.substring(right.lastIndexOf('/') + 1);
        if (!leftName.equals(rightName)) {
            return false;
        }
        return right.endsWith("/" + leftName) || left.endsWith("/" + rightName);
    }

    public static CoverageSummary summaryForFiles(
            List<FileCoverage> files, List<String> changedPaths, CoverageIgnore ignore) {
        List<String> filtered = ignore.filterPaths(changedPaths);
        CoverageSummary total = CoverageSummary.ZERO;
        for (FileCoverage file : files) {
            if (pathMatchesChanged(file.path(), filtered)) {
                total = total.plus(file.summary());
            }
        }
        return total;
    }

    public static List<ChangedFileCoverage> fileCoverageForProject(
            @Nullable Path unitLcov,
            @Nullable Path integrationLcov,
            @Nullable Path mergedLcov,
            List<String> changedPaths,
            CoverageIgnore ignore) {
        List<String> filtered = ignore.filterPaths(changedPaths);
        List<FileCoverage> unitFiles =
                unitLcov == null ? List.of() : ignore.filterFileCoverage(parseLcovFiles(unitLcov));
        List<FileCoverage> integrationFiles =
                integrationLcov == null ? List.of() : ignore.filterFileCoverage(parseLcovFiles(integrationLcov));
        List<FileCoverage> mergedFiles =
                mergedLcov == null ? List.of() : ignore.filterFileCoverage(parseLcovFiles(mergedLcov));
        List<ChangedFileCoverage> out = new ArrayList<>();
        for (String path : filtered) {
            CoverageSummary unit = findFileSummary(unitFiles, path);
            CoverageSummary integration = findFileSummary(integrationFiles, path);
            CoverageSummary mergedSummary = findFileSummary(mergedFiles, path);
            CoverageSummary merged = mergedSummary == null ? CoverageSummary.ZERO : mergedSummary;
            if (unit == null && integration == null && merged.linesFound() == 0) {
                out.add(new ChangedFileCoverage(path, null, null, CoverageSummary.ZERO));
                continue;
            }
            out.add(new ChangedFileCoverage(path, unit, integration, merged));
        }
        out.sort((left, right) -> RustOrdering.compareStrings(left.path(), right.path()));
        return out;
    }

    private static @Nullable CoverageSummary findFileSummary(List<FileCoverage> files, String path) {
        for (FileCoverage file : files) {
            if (pathMatchesChanged(file.path(), List.of(path))) {
                return file.summary();
            }
        }
        return null;
    }

    public static CoverageSummary mergeSummaries(List<CoverageSummary> summaries) {
        CoverageSummary total = CoverageSummary.ZERO;
        for (CoverageSummary summary : summaries) {
            total = total.plus(summary);
        }
        return total;
    }

    private static final class MutableFile {
        private final String path;
        private long linesHit;
        private long linesFound;
        private long functionsHit;
        private long functionsFound;

        private MutableFile(String path) {
            this.path = path;
        }

        private FileCoverage toCoverage() {
            return new FileCoverage(path, linesHit, linesFound, functionsHit, functionsFound);
        }
    }
}
