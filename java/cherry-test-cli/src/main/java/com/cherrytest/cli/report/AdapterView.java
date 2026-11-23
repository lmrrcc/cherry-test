package com.cherrytest.cli.report;

import com.cherrytest.cli.adapters.AdapterJson;
import com.cherrytest.cli.adapters.Builtins;
import com.cherrytest.cli.adapters.Formats;
import com.cherrytest.cli.adapters.Json;
import com.cherrytest.common.adapter.AdapterRequest;
import com.cherrytest.common.adapter.AdapterResponse;
import com.cherrytest.common.adapter.Attachment;
import com.cherrytest.common.adapter.Isolation;
import com.cherrytest.common.adapter.IsolationClass;
import com.cherrytest.common.adapter.TestResult;
import com.cherrytest.common.reportshell.ReportShell;
import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.common.rustcompat.RustIoError;
import com.cherrytest.common.rustcompat.RustOrdering;
import com.cherrytest.common.rustcompat.RustParse;
import com.cherrytest.common.rustcompat.RustPath;
import com.cherrytest.common.rustcompat.RustText;
import com.cherrytest.common.rustcompat.U64;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

final class AdapterView {
    private static final long SNIPPET_CONTEXT = 3;

    private AdapterView() {}

    enum LineMark {
        CONTEXT,
        COVERED,
        UNCOVERED,
        GAP
    }

    record MarkedLine(long number, String text, LineMark mark) {}

    record CoveredFile(String relative, String source, SortedMap<Long, Long> hits, boolean failed) {}

    private record PhaseSection(String phase, String head, List<CoveredFile> files) {}

    static List<MarkedLine> snippetLines(String source, SortedMap<Long, Long> hits) {
        List<String> lines = RustText.lines(source);
        long lineCount = lines.size();
        if (hits.isEmpty() || lineCount == 0) {
            return List.of();
        }
        List<Long> measured = new ArrayList<>();
        for (long number : hits.keySet()) {
            if (number >= 1 && number <= lineCount) {
                measured.add(number);
            }
        }
        if (measured.isEmpty()) {
            return List.of();
        }
        long runStart = measured.getFirst();
        long runEnd = measured.getFirst();
        List<long[]> windows = new ArrayList<>();
        for (int index = 1; index < measured.size(); index++) {
            long number = measured.get(index);
            if (number == runEnd + 1) {
                runEnd = number;
            } else {
                pushRun(runStart, runEnd, lineCount, windows);
                runStart = number;
                runEnd = number;
            }
        }
        pushRun(runStart, runEnd, lineCount, windows);
        List<MarkedLine> marked = new ArrayList<>();
        for (int index = 0; index < windows.size(); index++) {
            if (index > 0) {
                marked.add(new MarkedLine(0, "…", LineMark.GAP));
            }
            long[] window = windows.get(index);
            for (long number = window[0]; number <= window[1]; number++) {
                Long hit = hits.get(number);
                LineMark mark = hit == null ? LineMark.CONTEXT : hit == 0 ? LineMark.UNCOVERED : LineMark.COVERED;
                marked.add(new MarkedLine(number, lines.get((int) (number - 1)), mark));
            }
        }
        return marked;
    }

    private static void pushRun(long start, long end, long lineCount, List<long[]> windows) {
        long from = Math.max(Math.max(0, start - SNIPPET_CONTEXT), 1);
        long to = Math.min(end + SNIPPET_CONTEXT, lineCount);
        if (!windows.isEmpty()) {
            long[] last = windows.getLast();
            if (from <= last[1] + 1) {
                last[1] = Math.max(last[1], to);
                return;
            }
        }
        windows.add(new long[] {from, to});
    }

    static SortedSet<Long> renderedLineNumbers(String source, SortedMap<Long, Long> hits) {
        TreeSet<Long> numbers = new TreeSet<>();
        for (MarkedLine line : snippetLines(source, hits)) {
            if (line.mark() != LineMark.GAP) {
                numbers.add(line.number());
            }
        }
        return numbers;
    }

    static String renderCoverageSnippets(CoveredFile file, String phase, JumpIndex index) {
        List<MarkedLine> marked = snippetLines(file.source(), file.hits());
        if (marked.isEmpty()) {
            return "";
        }
        HighlightLang lang = Highlight.highlightLangForPath(file.relative());
        StringBuilder html = new StringBuilder("<div class=\"diff\" data-phase-outcome=\"")
                .append(file.failed() ? "failed" : "passed")
                .append("\">");
        html.append(ReportShell.diffRowHtml("column-labels", "old", "new", "±", "code"));
        for (MarkedLine line : marked) {
            if (line.mark() == LineMark.GAP) {
                html.append(ReportShell.diffRowHtml("ellipsis", "", "", "", "…"));
                continue;
            }
            String cssClass = switch (line.mark()) {
                case COVERED -> "ctx covered";
                case UNCOVERED -> "ctx uncovered";
                case CONTEXT, GAP -> "ctx";
            };
            String number = Long.toString(line.number());
            String anchorValue = index.rowAnchor(phase, file.relative(), line.number());
            String anchor = anchorValue == null ? "" : anchorValue;
            String code = Highlight.highlightLinked(
                    lang, line.text(), name -> index.href(phase, file.relative(), line.number(), name));
            html.append(ReportShell.diffRowWithId(anchor, cssClass, number, number, "", code));
        }
        html.append("</div>");
        return html.toString();
    }

    static String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    static String relativeLink(Path reports, Path path) {
        Path canonical = RustPath.canonicalize(path);
        Path root = RustPath.canonicalize(reports);
        Path relative = RustPath.stripPrefix(canonical, root);
        if (relative == null) {
            throw new AnyhowError("report artifact outside reports");
        }
        StringBuilder out = new StringBuilder();
        for (byte value : relative.toString().getBytes(StandardCharsets.UTF_8)) {
            int unsigned = value & 0xff;
            boolean plain = (unsigned >= 'a' && unsigned <= 'z')
                    || (unsigned >= 'A' && unsigned <= 'Z')
                    || (unsigned >= '0' && unsigned <= '9')
                    || unsigned == '/'
                    || unsigned == '-'
                    || unsigned == '_'
                    || unsigned == '.';
            if (plain) {
                out.append((char) unsigned);
            } else {
                out.append('%')
                        .append(Character.toUpperCase(Character.forDigit(unsigned >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(unsigned & 0xF, 16)));
            }
        }
        return out.toString();
    }

    /** The {@code isolation} section: every failed test that was rerun alone, its class and its reruns. */
    static String renderIsolationSection(com.cherrytest.cli.Isolation.Report report) {
        StringBuilder html = new StringBuilder("<section id=\"isolation\"><h2>Failed tests rerun alone</h2><p>Each"
                + " failed test ran alone up to " + report.runs() + " " + (report.runs() == 1 ? "time" : "times")
                + ", each time as a fresh job. Statuses below are those of the main run; the class explains the"
                + " failure.</p><ul>");
        for (com.cherrytest.cli.Isolation.IsolatedTest test : report.tests()) {
            html.append("<li>")
                    .append(isolationBadge(test.isolationClass()))
                    .append(' ')
                    .append(escape(test.project()))
                    .append(" / ")
                    .append(escape(test.packageName()))
                    .append(" / ")
                    .append(escape(test.phase()))
                    .append(": ")
                    .append(escape(test.test()))
                    .append(isolationDetail(test.runs(), test.passed(), test.logs(), test.reason()))
                    .append("</li>");
        }
        long left = Math.max(0, report.failed() - report.tests().size());
        if (left > 0) {
            html.append("<li>")
                    .append(left)
                    .append(" more failed ")
                    .append(left == 1 ? "test" : "tests")
                    .append(" not rerun (max_tests ")
                    .append(report.maxTests())
                    .append(")</li>");
        }
        html.append("</ul></section>");
        return html.toString();
    }

    static String isolationBadge(IsolationClass value) {
        return "<strong class=\"" + com.cherrytest.cli.Isolation.badgeClass(value) + "\">" + value.wireName()
                + "</strong>";
    }

    /** {@code , 2/3 passed alone (run 1, run 2, run 3)}, with the reason when the reruns could not decide. */
    static String isolationDetail(long runs, long passed, List<String> logs, @Nullable String reason) {
        StringBuilder html = new StringBuilder();
        if (runs > 0) {
            html.append(", ").append(passed).append('/').append(runs).append(" passed alone");
            List<String> links = new ArrayList<>();
            for (int index = 0; index < logs.size(); index++) {
                links.add("<a href=\"" + escape(logs.get(index)) + "/result.json\">run " + (index + 1) + "</a>");
            }
            if (!links.isEmpty()) {
                html.append(" (").append(String.join(", ", links)).append(')');
            }
        }
        if (reason != null) {
            html.append(" <span class=\"muted\">").append(escape(reason)).append("</span>");
        }
        return html.toString();
    }

    static JumpIndex write(Path reports) {
        List<PhaseSection> sections = loadPhaseSections(reports);
        JumpIndex index = indexRenderedDefinitions(sections);
        StringBuilder body = new StringBuilder(
                "<p><a href=\"index.html\">Back to report</a></p><h1>Tests and coverage by phase</h1><p>Markers distinguish covered lines, uncovered lines, and lines with no coverage measurement. Each measured run is shown with the lines above and below it.</p>");
        com.cherrytest.cli.Isolation.Report report = com.cherrytest.cli.Isolation.readReport(reports);
        if (report != null && report.failed() > 0) {
            body.append(renderIsolationSection(report));
        }
        for (PhaseSection section : sections) {
            body.append(section.head());
            for (CoveredFile file : section.files()) {
                long hit = file.hits().values().stream()
                        .filter(count -> count != 0)
                        .count();
                body.append("<details><summary>")
                        .append(escape(file.relative()))
                        .append(": ")
                        .append(hit)
                        .append('/')
                        .append(file.hits().size())
                        .append(" covered lines</summary>")
                        .append(renderCoverageSnippets(file, section.phase(), index))
                        .append("</details>");
            }
            body.append("</section>");
        }
        String document = ReportShell.renderCombinedReportDocument("cherry-test test phases", "", body.toString());
        try {
            Files.writeString(reports.resolve("phases.html"), document, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new AnyhowError(RustIoError.display(exception), null);
        }
        return index;
    }

    private static JumpIndex indexRenderedDefinitions(List<PhaseSection> sections) {
        JumpIndex index = JumpIndex.empty();
        for (PhaseSection section : sections) {
            for (CoveredFile file : section.files()) {
                Highlight.recordRenderedDefinitions(
                        index,
                        section.phase(),
                        file.relative(),
                        file.source(),
                        renderedLineNumbers(file.source(), file.hits()));
            }
        }
        return index;
    }

    private static List<PhaseSection> loadPhaseSections(Path reports) {
        List<PhaseSection> sections = new ArrayList<>();
        for (Path result : Formats.files(reports.resolve("adapters"), "json")) {
            Path fileName = result.getFileName();
            if (fileName == null || !fileName.toString().equals("result.json")) {
                continue;
            }
            Path directory = result.getParent();
            if (directory == null) {
                throw new AnyhowError("adapter report directory");
            }
            Path requestPath = directory.resolve("request.json");
            if (!Files.isRegularFile(requestPath)) {
                continue;
            }
            AdapterRequest request = AdapterJson.parseRequest(readBytes(requestPath));
            AdapterResponse response =
                    AdapterJson.responseFrom(Json.index(Json.parseBytes(readBytes(result)), "result"));
            StringBuilder head = new StringBuilder("<section><h2>")
                    .append(escape(request.project()))
                    .append(" / ")
                    .append(escape(request.packageName()))
                    .append(" / ")
                    .append(escape(request.phase()))
                    .append("</h2>");
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
                for (Path path : entries) {
                    String extension = RustPath.extension(path);
                    if (Files.isRegularFile(path)
                            && extension != null
                            && (extension.equals("stdout") || extension.equals("stderr") || extension.equals("log"))) {
                        String link = relativeLink(reports, path);
                        Path name = path.getFileName();
                        head.append("<a href=\"")
                                .append(link)
                                .append("\">")
                                .append(escape(name == null ? "" : name.toString()))
                                .append("</a> ");
                    }
                }
            } catch (IOException exception) {
                throw new AnyhowError(RustIoError.display(exception), null);
            }
            if (MutationReport.isPitest(request) && Files.isRegularFile(directory.resolve("mutation.json"))) {
                head.append(MutationReport.details(
                        request,
                        com.cherrytest.cli.adapters.Pitest.readReport(directory.resolve("mutation.json")),
                        reports));
            }
            for (String diagnostic : response.diagnostics()) {
                head.append("<pre>").append(escape(diagnostic)).append("</pre>");
            }
            head.append("<ul>");
            for (TestResult test : response.tests()) {
                head.append("<li><strong>")
                        .append(test.status().debugName())
                        .append("</strong> ")
                        .append(escape(test.name()))
                        .append(" (")
                        .append(U64.toString(test.durationMs()))
                        .append(" ms)");
                Isolation isolation = test.isolation();
                if (isolation != null) {
                    head.append(' ')
                            .append(isolationBadge(isolation.isolationClass()))
                            .append(isolationDetail(
                                    isolation.runs(), isolation.passed(), isolation.logs(), isolation.reason()));
                }
                if (test.message() != null) {
                    head.append("<pre>").append(escape(test.message())).append("</pre>");
                }
                for (Attachment attachment : test.attachments()) {
                    String url = relativeLink(reports, attachment.path());
                    head.append("<p><a href=\"")
                            .append(url)
                            .append("\">")
                            .append(escape(attachment.name()))
                            .append("</a></p>");
                    if (attachment.mediaType().equals("image/png")) {
                        head.append("<img style=\"max-width:100%\" src=\"")
                                .append(url)
                                .append("\" alt=\"")
                                .append(escape(attachment.name()))
                                .append("\">");
                    }
                }
                head.append("</li>");
            }
            head.append("</ul>");
            TreeMap<String, SortedMap<Long, Long>> sources = new TreeMap<>(RustOrdering.STRING_ORDER);
            for (Path lcov : response.coverage()) {
                String file = null;
                for (String line : RustText.lines(readString(lcov))) {
                    if (line.startsWith("SF:")) {
                        file = line.substring(3);
                    }
                    if (file != null && line.startsWith("DA:")) {
                        String[] values = line.substring(3).split(",", -1);
                        long number = RustParse.usize(values[0]);
                        if (values.length < 2) {
                            throw new AnyhowError("LCOV hits");
                        }
                        long count = RustParse.u64(values[1]);
                        SortedMap<Long, Long> fileHits = sources.computeIfAbsent(file, key -> new TreeMap<>());
                        fileHits.put(
                                number,
                                U64.checkedAdd(fileHits.getOrDefault(number, 0L), count, "coverage counter overflow"));
                    }
                }
            }
            List<CoveredFile> files = new ArrayList<>();
            for (Map.Entry<String, SortedMap<Long, Long>> entry : sources.entrySet()) {
                Path path = Builtins.sourcePath(request, entry.getKey());
                if (!path.startsWith(request.root())) {
                    continue;
                }
                Path canonical = RustPath.canonicalize(path);
                Path root = RustPath.canonicalize(request.root());
                Path relative = RustPath.stripPrefix(canonical, root);
                if (relative == null) {
                    continue;
                }
                files.add(new CoveredFile(
                        relative.toString(), readString(canonical), entry.getValue(), !response.success()));
            }
            sections.add(new PhaseSection(request.phase(), head.toString(), files));
        }
        return sections;
    }

    private static byte[] readBytes(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new AnyhowError(RustIoError.display(exception), null);
        }
    }

    private static String readString(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new AnyhowError(RustIoError.display(exception), null);
        }
    }
}
