package com.cherrytest.cli.report;

import com.cherrytest.cli.ChangedFiles;
import com.cherrytest.cli.PreflightPlan;
import com.cherrytest.cli.ProjectGroup;
import com.cherrytest.cli.RunDir;
import com.cherrytest.cli.RunOutcome;
import com.cherrytest.cli.TestKind;
import com.cherrytest.cli.VitestReport;
import com.cherrytest.cli.adapters.Adapters;
import com.cherrytest.cli.coverage.ChangedFileCoverage;
import com.cherrytest.cli.coverage.Coverage;
import com.cherrytest.cli.coverage.CoverageIgnore;
import com.cherrytest.cli.coverage.CoveragePaths;
import com.cherrytest.cli.coverage.CoverageSummary;
import com.cherrytest.cli.coverage.FileCoverage;
import com.cherrytest.cli.coverage.FileLineCoverage;
import com.cherrytest.cli.crap.Crap;
import com.cherrytest.cli.crap.CrapChange;
import com.cherrytest.cli.crap.CrapProjectRoot;
import com.cherrytest.cli.crap.CrapReport;
import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.common.rustcompat.RustOrdering;
import com.cherrytest.common.rustcompat.RustText;
import com.cherrytest.common.rustcompat.U64;
import com.cherrytest.core.IntegrationRecords;
import com.cherrytest.core.ProjectConfig;
import com.cherrytest.core.Scope;
import com.cherrytest.core.TestItConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

public final class ReportModel {
    private ReportModel() {}

    public static ReportSnapshot buildReportSnapshot(
            Path workspaceRoot,
            RunDir runDir,
            List<ProjectGroup> projects,
            RunOutcome outcome,
            PreflightPlan preflight,
            CoverageTotals totals,
            TestItConfig config,
            boolean coverageEnabled,
            boolean reportCrap) {
        Map<String, List<String>> changedByProject =
                ChangedFiles.changedFilesByProject(workspaceRoot, projects, config);
        CoverageIgnore coverageIgnore = CoverageIgnore.load(workspaceRoot, config);
        List<IntegrationRow> integrationRows = loadIntegrationRows(runDir.reports());
        long[] stats = integrationStats(integrationRows, preflight);
        java.util.SortedSet<String> exercisedProjects =
                exercisedProjectsForCoverage(integrationRows, config, preflight.scopesToBoot());
        List<ProjectReport> projectReports = new ArrayList<>();
        List<String> diffPaths = new ArrayList<>();
        for (ProjectRunResult result : outcome.results()) {
            List<String> gitChanged = changedByProject.getOrDefault(result.projectId(), List.of());
            diffPaths.addAll(gitChanged);
            ProjectGroup project = projects.stream()
                    .filter(group -> group.id().equals(result.projectId()))
                    .findFirst()
                    .orElseThrow(() -> new AnyhowError("unknown project " + result.projectId()));
            List<String> prefixes = ChangedFiles.packagePrefixes(config, project);
            CoveragePaths paths = new CoveragePaths(runDir, result.projectId());
            Path unitLcov = paths.kindLcovFile(result.packageName(), TestKind.UNIT);
            Path integrationLcov = paths.kindLcovFile(result.packageName(), TestKind.INTEGRATION);
            Path mergedLcov = paths.lcovFile(result.packageName());
            List<FileCoverage> unitFiles = Files.exists(unitLcov)
                    ? coverageIgnore.filterFileCoverage(Coverage.parseLcovFiles(unitLcov))
                    : List.of();
            List<FileCoverage> integrationFiles = Files.exists(integrationLcov)
                    ? coverageIgnore.filterFileCoverage(Coverage.parseLcovFiles(integrationLcov))
                    : List.of();
            List<FileCoverage> mergedFiles = Files.exists(mergedLcov)
                    ? coverageIgnore.filterFileCoverage(Coverage.parseLcovFiles(mergedLcov))
                    : List.of();
            List<String> changed = exercisedProjects.contains(result.projectId())
                    ? ChangedFiles.expandChangedWithLcovSources(gitChanged, mergedFiles, prefixes)
                    : gitChanged;
            CoverageSummary diffUnit =
                    changed.isEmpty() ? null : Coverage.summaryForFiles(unitFiles, changed, coverageIgnore);
            CoverageSummary diffIntegration =
                    changed.isEmpty() ? null : Coverage.summaryForFiles(integrationFiles, changed, coverageIgnore);
            CoverageSummary diffMerged = changed.isEmpty()
                    ? CoverageSummary.ZERO
                    : Coverage.summaryForFiles(mergedFiles, changed, coverageIgnore);
            List<ChangedFileCoverage> changedFiles;
            if (coverageEnabled) {
                changedFiles = Coverage.fileCoverageForProject(
                        Files.exists(unitLcov) ? unitLcov : null,
                        Files.exists(integrationLcov) ? integrationLcov : null,
                        Files.exists(mergedLcov) ? mergedLcov : null,
                        changed,
                        coverageIgnore);
            } else {
                changedFiles = changed.stream()
                        .map(path -> new ChangedFileCoverage(path, null, null, CoverageSummary.ZERO))
                        .toList();
            }
            projectReports.add(new ProjectReport(
                    result.projectId(),
                    result.packageName(),
                    result.testsPassed(),
                    result.coverage(),
                    new CoverageTotals(diffUnit, diffIntegration, diffMerged),
                    changedFiles));
        }
        CoverageTotals diffCoverage = aggregateDiffCoverage(projectReports);
        List<ProjectDiffSection> projectDiffSections = DiffView.buildProjectDiffSections(
                workspaceRoot, runDir, config, projects, projectReports, coverageIgnore);
        List<String> uniqueDiffPaths = com.cherrytest.common.gitdiff.GitDiff.sortedDedup(diffPaths);
        List<AddedLines> addedLines = DiffView.addedLinesByFile(projectDiffSections);
        long packagesPassed =
                outcome.results().stream().filter(ProjectRunResult::testsPassed).count();
        long packagesFailed = U64.saturatingSub(outcome.results().size(), packagesPassed);
        TestsReport testsReport = TestView.buildTestsReport(integrationRows, preflight, workspaceRoot);
        return new ReportSnapshot(
                runDir.id(),
                !outcome.failed(),
                packagesPassed,
                outcome.results().size(),
                packagesFailed,
                stats[0],
                stats[1],
                stats[2],
                coverageEnabled,
                totals,
                diffCoverage,
                projectReports,
                projectDiffSections,
                integrationRows,
                testsReport,
                crapReport(workspaceRoot, runDir, coverageIgnore, reportCrap, config, uniqueDiffPaths, addedLines),
                runDir.reportHtml());
    }

    private static CrapReport crapReport(
            Path workspaceRoot,
            RunDir runDir,
            CoverageIgnore coverageIgnore,
            boolean reportCrap,
            TestItConfig config,
            List<String> changed,
            List<AddedLines> addedLines) {
        if (!reportCrap) {
            return CrapReport.DISABLED;
        }
        List<FileLineCoverage> files =
                coverageIgnore.filterLineCoverage(Coverage.parseLcovLineCoverage(runDir.mergedLcov()));
        CrapReport report = Crap.scoreReport(workspaceRoot, files, crapProjectRoots(workspaceRoot, config));
        CrapChange change = Crap.changeOnAddedLines(report.functions(), addedLines);
        if (change == null) {
            change = Crap.changeSlice(report.functions(), changed);
        }
        return report.withChanged(change);
    }

    private static List<CrapProjectRoot> crapProjectRoots(Path workspaceRoot, TestItConfig config) {
        List<CrapProjectRoot> roots = new ArrayList<>();
        for (Map.Entry<String, ProjectConfig> entry : config.projects().entrySet()) {
            String path = entry.getValue().path() == null
                    ? entry.getKey()
                    : entry.getValue().path();
            roots.add(new CrapProjectRoot(entry.getKey(), workspaceRoot.resolve(path)));
        }
        return roots;
    }

    private static java.util.SortedSet<String> exercisedProjectsForCoverage(
            List<IntegrationRow> integrationRows, TestItConfig config, List<String> scopesToBoot) {
        TreeSet<String> out = new TreeSet<>(RustOrdering.STRING_ORDER);
        for (IntegrationRow row : integrationRows) {
            if (row.target.equals("-")) {
                continue;
            }
            String project = row.target.split("\\.", -1)[0];
            if (!project.isEmpty()) {
                out.add(project);
            }
        }
        for (String scope : scopesToBoot) {
            out.addAll(resolveProjectsOrEmpty(config, scope));
        }
        return out;
    }

    private static List<String> resolveProjectsOrEmpty(TestItConfig config, String scope) {
        try {
            return Scope.resolveProjects(config, scope);
        } catch (AnyhowError unknownScope) {
            return List.of();
        }
    }

    private static CoverageTotals aggregateDiffCoverage(List<ProjectReport> projects) {
        List<CoverageSummary> unit = new ArrayList<>();
        List<CoverageSummary> integration = new ArrayList<>();
        List<CoverageSummary> merged = new ArrayList<>();
        for (ProjectReport project : projects) {
            if (project.diffCoverage().unit() != null) {
                unit.add(project.diffCoverage().unit());
            }
            if (project.diffCoverage().integration() != null) {
                integration.add(project.diffCoverage().integration());
            }
            merged.add(project.diffCoverage().merged());
        }
        return new CoverageTotals(
                unit.isEmpty() ? null : Coverage.mergeSummaries(unit),
                integration.isEmpty() ? null : Coverage.mergeSummaries(integration),
                Coverage.mergeSummaries(merged));
    }

    static long[] integrationStats(List<IntegrationRow> rows, PreflightPlan preflight) {
        long run = 0;
        long skipped = 0;
        long failed = 0;
        for (IntegrationRow row : rows) {
            switch (row.statusLabel) {
                case "skipped" -> skipped++;
                case "FAIL" -> {
                    run++;
                    failed++;
                }
                default -> run++;
            }
        }
        if (rows.isEmpty() && !preflight.skipped().isEmpty()) {
            skipped = preflight.skipped().size();
        }
        return new long[] {run, skipped, failed};
    }

    static List<IntegrationRow> loadIntegrationRows(Path reportDir) {
        List<JsonNode> rows = new ArrayList<>(IntegrationRecords.readAllRecords(reportDir));
        rows.addAll(VitestReport.readUnitRows(reportDir));
        rows.addAll(Adapters.readRows(reportDir));
        TreeMap<String, IntegrationRow> tests = new TreeMap<>(RustOrdering.STRING_ORDER);
        for (JsonNode raw : rows) {
            IntegrationRow row = parseIntegrationRow(raw);
            if (row == null) {
                continue;
            }
            IntegrationRow test = tests.get(row.fullName);
            if (test == null) {
                tests.put(row.fullName, row);
                continue;
            }
            test.elapsedMs = U64.saturatingAdd(test.elapsedMs, row.elapsedMs);
            if (row.statusLabel.equals("FAIL")
                    || (!test.statusLabel.equals("FAIL") && row.statusLabel.equals("skipped"))) {
                test.statusLabel = row.statusLabel;
                test.reason = row.reason;
            }
            test.method = appendUnique(test.method, row.method);
            test.url = appendUnique(test.url, row.url);
        }
        return List.copyOf(tests.values());
    }

    private static String appendUnique(String existing, String value) {
        if (value.isEmpty()) {
            return existing;
        }
        for (String part : existing.split("\n", -1)) {
            if (part.equals(value)) {
                return existing;
            }
        }
        return existing.isEmpty() ? value : existing + "\n" + value;
    }

    private static @Nullable String text(JsonNode row, String field) {
        JsonNode value = row.get(field);
        return value != null && value.isString() ? value.stringValue() : null;
    }

    static @Nullable IntegrationRow parseIntegrationRow(JsonNode row) {
        String project = text(row, "project");
        String suite = text(row, "suite");
        String test = text(row, "test");
        if (project == null || suite == null || test == null) {
            return null;
        }
        String scopeValue = text(row, "scope");
        String scope = scopeValue == null ? "" : scopeValue;
        String fullNameValue = text(row, "full_name");
        String fullName =
                fullNameValue != null ? fullNameValue : project + "_project_" + suite + "_suite_" + test + "_test";
        String targetProject = text(row, "target_project");
        String targetService = text(row, "target_service");
        JsonNode elapsedNode = row.get("elapsed_ms");
        Long elapsedValue = elapsedNode == null ? null : com.cherrytest.cli.adapters.Json.u64(elapsedNode);
        long elapsed = elapsedValue == null ? 0 : elapsedValue;
        String methodValue = text(row, "method");
        String urlValue = text(row, "url");
        JsonNode statusField = row.get("status");
        List<String> tags = TestView.deriveTags(project, suite, scope, test);
        String target = targetProject == null || targetProject.isEmpty()
                ? "-"
                : targetProject + "." + (targetService == null ? "" : targetService);
        String statusLabel;
        String http;
        String reason;
        String rawReason = text(row, "reason");
        if (statusField != null
                && statusField.isString()
                && statusField.stringValue().equals("skipped")) {
            statusLabel = "skipped";
            http = rawReason == null ? "" : rawReason;
            reason = rawReason == null || rawReason.isEmpty() ? null : rawReason;
        } else if (statusField != null && statusField.isString()) {
            String label = statusField.stringValue();
            statusLabel = label;
            http = label.equals("ok") ? "ok" : label;
            reason = rawReason == null || rawReason.isEmpty() ? null : rawReason;
        } else {
            Long codeValue = statusField == null ? null : com.cherrytest.cli.adapters.Json.u64(statusField);
            long code = codeValue == null ? 0 : codeValue;
            http = code == 0 ? "ERR" : U64.toString(code);
            JsonNode passed = row.get("passed");
            if (passed != null && passed.isBoolean()) {
                statusLabel = passed.booleanValue() ? "ok" : "FAIL";
            } else {
                statusLabel = code >= 200 && code < 300 ? "ok" : "FAIL";
            }
            if (statusLabel.equals("FAIL")) {
                reason = rawReason != null && !rawReason.isEmpty()
                        ? rawReason
                        : failReason(code, text(row, "response_body"));
            } else {
                reason = null;
            }
        }
        IntegrationRow.Location location = null;
        String source = text(row, "source");
        JsonNode lineNode = row.get("line");
        Long lineValue = lineNode == null ? null : com.cherrytest.cli.adapters.Json.u64(lineNode);
        if (source != null && lineValue != null && Long.compareUnsigned(lineValue, 0xFFFFFFFFL) <= 0) {
            location = new IntegrationRow.Location(Path.of(source), lineValue);
        }
        return new IntegrationRow(
                project,
                suite,
                test,
                fullName,
                scope,
                target,
                elapsed,
                statusLabel,
                http,
                reason,
                methodValue == null ? "" : methodValue,
                urlValue == null ? "" : urlValue,
                tags,
                location);
    }

    private static String failReason(long code, @Nullable String body) {
        String prefix = code == 0 ? "HTTP ERR" : "HTTP " + U64.toString(code);
        String trimmed = RustText.trim(body == null ? "" : body);
        if (trimmed.isEmpty()) {
            return prefix;
        }
        StringBuilder snippet = new StringBuilder();
        int count = 0;
        int index = 0;
        while (index < trimmed.length() && count < 500) {
            int codePoint = trimmed.codePointAt(index);
            snippet.appendCodePoint(codePoint);
            index += Character.charCount(codePoint);
            count++;
        }
        return prefix + ": " + snippet;
    }

    public static String formatStatusLine(ReportSnapshot snapshot, int maxLength) {
        return truncateLine(TerminalReport.formatStatusRow(snapshot), maxLength);
    }

    private static String truncateLine(String line, int maxLength) {
        if (RustText.charCount(line) <= maxLength) {
            return line;
        }
        if (maxLength <= 3) {
            return "...".substring(0, maxLength);
        }
        return prefixCodePoints(line, maxLength - 3) + "...";
    }

    static String prefixCodePoints(String text, int count) {
        StringBuilder out = new StringBuilder();
        int index = 0;
        int taken = 0;
        while (index < text.length() && taken < count) {
            int codePoint = text.codePointAt(index);
            out.appendCodePoint(codePoint);
            index += Character.charCount(codePoint);
            taken++;
        }
        return out.toString();
    }
}
