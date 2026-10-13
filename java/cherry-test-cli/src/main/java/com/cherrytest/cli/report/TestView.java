package com.cherrytest.cli.report;

import com.cherrytest.cli.DiscoveredTest;
import com.cherrytest.cli.PreflightPlan;
import com.cherrytest.cli.coverage.Coverage;
import com.cherrytest.common.rustcompat.RustOrdering;
import com.cherrytest.common.rustcompat.RustText;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

public final class TestView {
    private static final long FAILURE_CONTEXT_LINES = 6;

    private TestView() {}

    public static TestsReport buildTestsReport(List<IntegrationRow> rows, PreflightPlan preflight, Path workspaceRoot) {
        Map<String, DiscoveredTest> sourceIndex = new HashMap<>();
        for (DiscoveredTest discovered : preflight.discovered()) {
            sourceIndex.put(discovered.function(), discovered);
        }
        TreeMap<String, TreeMap<String, List<TestCaseView>>> byProject = new TreeMap<>(RustOrdering.STRING_ORDER);
        TreeSet<String> tagSet = new TreeSet<>(RustOrdering.STRING_ORDER);
        for (IntegrationRow row : rows) {
            TestCaseView testCase = buildCaseView(row, sourceIndex, workspaceRoot);
            tagSet.addAll(testCase.tags());
            byProject
                    .computeIfAbsent(testCase.project(), key -> new TreeMap<>(RustOrdering.STRING_ORDER))
                    .computeIfAbsent(testCase.suite(), key -> new ArrayList<>())
                    .add(testCase);
        }
        List<ProjectTestView> projects = new ArrayList<>();
        for (Map.Entry<String, TreeMap<String, List<TestCaseView>>> project : byProject.entrySet()) {
            List<SuiteTestView> suites = new ArrayList<>();
            List<TestCaseView> projectCases = new ArrayList<>();
            for (Map.Entry<String, List<TestCaseView>> suite :
                    project.getValue().entrySet()) {
                List<TestCaseView> cases = new ArrayList<>(suite.getValue());
                cases.sort((left, right) -> RustOrdering.compareStrings(left.test(), right.test()));
                TestTimingStats stats = computeStats(cases);
                projectCases.addAll(cases);
                suites.add(new SuiteTestView(suite.getKey(), stats, cases));
            }
            suites.sort((left, right) -> RustOrdering.compareStrings(left.suite(), right.suite()));
            projects.add(new ProjectTestView(project.getKey(), computeStats(projectCases), suites));
        }
        return new TestsReport(projects, List.copyOf(tagSet));
    }

    public static TestsReport testsInChange(TestsReport report, List<String> changed) {
        if (changed.isEmpty()) {
            return TestsReport.EMPTY;
        }
        List<ProjectTestView> projects = new ArrayList<>();
        TreeSet<String> tags = new TreeSet<>(RustOrdering.STRING_ORDER);
        for (ProjectTestView project : report.projects()) {
            List<SuiteTestView> suites = new ArrayList<>();
            List<TestCaseView> projectCases = new ArrayList<>();
            for (SuiteTestView suite : project.suites()) {
                List<TestCaseView> cases = suite.cases().stream()
                        .filter(testCase -> testCase.snippetPath() != null
                                && Coverage.pathMatchesChanged(
                                        testCase.snippetPath().toString(), changed))
                        .toList();
                if (cases.isEmpty()) {
                    continue;
                }
                cases.forEach(testCase -> tags.addAll(testCase.tags()));
                projectCases.addAll(cases);
                suites.add(new SuiteTestView(suite.suite(), computeStats(cases), cases));
            }
            if (suites.isEmpty()) {
                continue;
            }
            projects.add(new ProjectTestView(project.project(), computeStats(projectCases), suites));
        }
        return new TestsReport(projects, List.copyOf(tags));
    }

    public static String formatTimingStats(TestTimingStats stats) {
        String timing = stats.run() > 0
                ? " · " + stats.totalMs() + "ms total · " + stats.avgMs() + "ms avg · " + stats.minMs()
                        + "ms fastest · " + stats.maxMs() + "ms slowest"
                : "";
        return stats.run() + " run · " + stats.ok() + " ok · " + stats.fail() + " fail · " + stats.skip() + " skip"
                + timing;
    }

    public static String formatTimingSummary(String label, TestTimingStats stats) {
        return label + " · " + formatTimingStats(stats);
    }

    private static TestCaseView buildCaseView(
            IntegrationRow row, Map<String, DiscoveredTest> sourceIndex, Path workspaceRoot) {
        Snippet snippet = loadSnippet(row, sourceIndex, workspaceRoot);
        return new TestCaseView(
                row.project,
                row.suite,
                row.test,
                row.fullName,
                row.scope,
                row.target,
                row.elapsedMs,
                row.statusLabel,
                row.http,
                row.reason,
                row.method,
                row.url,
                row.tags,
                snippet.lines(),
                snippet.path());
    }

    private record Snippet(
            List<SnippetLine> lines, @Nullable Path path) {}

    private static Snippet loadSnippet(
            IntegrationRow row, Map<String, DiscoveredTest> sourceIndex, Path workspaceRoot) {
        if (row.location != null) {
            String content = readOrNull(row.location.source());
            if (content != null) {
                return new Snippet(
                        extractSnippet(content, row.location.line(), FAILURE_CONTEXT_LINES), row.location.source());
            }
        }
        DiscoveredTest discovered = sourceIndex.get(row.fullName);
        if (discovered == null) {
            return new Snippet(List.of(), null);
        }
        String content = readOrNull(workspaceRoot.resolve(discovered.sourcePath()));
        if (content == null) {
            return new Snippet(List.of(), null);
        }
        return new Snippet(extractSnippet(content, discovered.fnLine(), 20), discovered.sourcePath());
    }

    private static @Nullable String readOrNull(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException | java.io.UncheckedIOException exception) {
            return null;
        }
    }

    public static List<SnippetLine> extractSnippet(String content, long fnLine, long context) {
        List<String> lines = RustText.lines(content);
        if (fnLine == 0 || fnLine > lines.size()) {
            return List.of();
        }
        long center = fnLine - 1;
        long start = Math.max(0, center - context);
        long end = Math.min(center + context + 1, lines.size());
        List<SnippetLine> out = new ArrayList<>();
        for (long index = start; index < end; index++) {
            out.add(new SnippetLine(index + 1, lines.get((int) index), index + 1 == fnLine));
        }
        return out;
    }

    private static TestTimingStats computeStats(List<TestCaseView> cases) {
        List<StatusTiming> items = new ArrayList<>();
        for (TestCaseView testCase : cases) {
            items.add(new StatusTiming(testCase.statusLabel(), testCase.elapsedMs()));
        }
        return timingStats(items);
    }

    public record StatusTiming(String status, long elapsedMs) {}

    public static TestTimingStats timingStats(List<StatusTiming> items) {
        long run = 0;
        long ok = 0;
        long fail = 0;
        long skip = 0;
        List<Long> timed = new ArrayList<>();
        for (StatusTiming item : items) {
            switch (item.status()) {
                case "skipped" -> skip++;
                case "FAIL" -> {
                    run++;
                    fail++;
                    if (item.elapsedMs() != 0) {
                        timed.add(item.elapsedMs());
                    }
                }
                default -> {
                    run++;
                    ok++;
                    if (item.elapsedMs() != 0) {
                        timed.add(item.elapsedMs());
                    }
                }
            }
        }
        if (timed.isEmpty()) {
            return new TestTimingStats(run, ok, fail, skip, 0, 0, 0, 0);
        }
        long total = 0;
        long minimum = timed.getFirst();
        long maximum = timed.getFirst();
        for (long value : timed) {
            total += value;
            if (Long.compareUnsigned(value, minimum) < 0) {
                minimum = value;
            }
            if (Long.compareUnsigned(value, maximum) > 0) {
                maximum = value;
            }
        }
        return new TestTimingStats(
                run, ok, fail, skip, total, Long.divideUnsigned(total, timed.size()), minimum, maximum);
    }

    public static List<String> deriveTags(String project, String suite, String scope, String test) {
        List<String> tags = new ArrayList<>(List.of(project, suite, scope));
        for (String part : test.split("_", -1)) {
            if (!part.isEmpty()) {
                tags.add(part);
            }
        }
        tags.sort(RustOrdering.STRING_ORDER);
        List<String> unique = new ArrayList<>();
        for (String tag : tags) {
            if (unique.isEmpty() || !unique.getLast().equals(tag)) {
                unique.add(tag);
            }
        }
        return unique;
    }

    public static String tagsAttr(List<String> tags) {
        return String.join(" ", tags);
    }
}
