package com.cherrytest.cli.adapters;

import com.cherrytest.common.adapter.Attachment;
import com.cherrytest.common.adapter.TestResult;
import com.cherrytest.common.adapter.TestStatus;
import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.common.rustcompat.RustIoError;
import com.cherrytest.common.rustcompat.RustOrdering;
import com.cherrytest.common.rustcompat.RustParse;
import com.cherrytest.common.rustcompat.RustText;
import com.cherrytest.common.rustcompat.SerdeJsonWriter;
import com.cherrytest.common.rustcompat.U64;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.w3c.dom.Element;
import tools.jackson.databind.JsonNode;

public final class Formats {
    private Formats() {}

    public static TestResult testCase(String name, TestStatus status, @Nullable String message, long durationMs) {
        return new TestResult(name, status, durationMs, message, null, null, List.of());
    }

    public static List<TestResult> junit(String content) {
        Element root;
        try {
            root = XmlNodes.parse(content);
        } catch (AnyhowError error) {
            throw AnyhowError.context("parse JUnit XML", error);
        }
        String rootName = XmlNodes.localName(root);
        if (!rootName.equals("testsuite") && !rootName.equals("testsuites")) {
            throw new AnyhowError("expected JUnit testsuite or testsuites");
        }
        List<TestResult> cases = new ArrayList<>();
        for (Element node : XmlNodes.descendants(root, "testcase")) {
            String name = XmlNodes.attribute(node, "name");
            if (name == null) {
                throw new AnyhowError("JUnit testcase missing name");
            }
            List<Element> failures = XmlNodes.children(node, "failure", "error");
            Element failure = failures.isEmpty() ? null : failures.getFirst();
            List<Element> skips = XmlNodes.children(node, "skipped");
            Element skip = skips.isEmpty() ? null : skips.getFirst();
            TestStatus status =
                    failure != null ? TestStatus.FAILED : skip != null ? TestStatus.SKIPPED : TestStatus.PASSED;
            Element detailNode = failure != null ? failure : skip;
            String detail = null;
            if (detailNode != null) {
                String message = XmlNodes.attribute(detailNode, "message");
                String text = XmlNodes.text(detailNode);
                detail = (message == null ? "" : message) + "\n" + (text == null ? "" : text);
            }
            String timeText = XmlNodes.attribute(node, "time");
            double seconds;
            try {
                seconds = RustParse.f64(timeText == null ? "0" : timeText);
            } catch (AnyhowError error) {
                throw AnyhowError.context("JUnit duration", error);
            }
            if (!Double.isFinite(seconds) || seconds < 0.0) {
                throw new AnyhowError("invalid JUnit duration");
            }
            String classname = XmlNodes.attribute(node, "classname");
            String file = XmlNodes.attribute(node, "file");
            String lineText = XmlNodes.attribute(node, "line");
            Long line = lineText == null ? null : RustParse.u32(lineText);
            cases.add(new TestResult(
                    (classname == null ? "tests" : classname) + "::" + name,
                    status,
                    U64.fromF64(seconds * 1000.0),
                    detail,
                    file == null ? null : Path.of(file),
                    line,
                    List.of()));
        }
        for (Element suite : XmlNodes.descendants(root, "testsuite")) {
            for (String field : List.of("failures", "errors")) {
                String declaredText = XmlNodes.attribute(suite, field);
                long declared;
                try {
                    declared = RustParse.usize(declaredText == null ? "0" : declaredText);
                } catch (AnyhowError error) {
                    throw AnyhowError.context("JUnit suite count", error);
                }
                String tag = field.equals("failures") ? "failure" : "error";
                long recorded = XmlNodes.descendants(suite, tag).size();
                if (Long.compareUnsigned(declared, recorded) > 0) {
                    throw new AnyhowError(
                            "JUnit declares " + U64.toString(declared) + " " + field + " but reports only " + recorded);
                }
            }
            for (Element failure : XmlNodes.children(suite, "failure", "error")) {
                String suiteName = XmlNodes.attribute(suite, "name");
                String text = XmlNodes.text(failure);
                cases.add(testCase(
                        suiteName == null ? "suite setup" : suiteName,
                        TestStatus.FAILED,
                        text == null ? "suite failed" : text,
                        0));
            }
        }
        return cases;
    }

    public static String jacoco(String content, List<Path> sourceRoots) {
        String source = content;
        int start = content.indexOf("<!DOCTYPE");
        if (start >= 0) {
            int relativeEnd = content.substring(start).indexOf('>');
            if (relativeEnd < 0) {
                throw new AnyhowError("invalid JaCoCo doctype");
            }
            int end = relativeEnd + start;
            if (content.substring(start, end).contains("[")) {
                throw new AnyhowError("JaCoCo internal DTD is not supported");
            }
            source = content.substring(0, start) + content.substring(end + 1);
        }
        Element root;
        try {
            root = XmlNodes.parse(source);
        } catch (AnyhowError error) {
            throw AnyhowError.context("parse JaCoCo XML", error);
        }
        StringBuilder out = new StringBuilder();
        for (Element packageNode : XmlNodes.descendants(root, "package")) {
            String packageName = XmlNodes.attribute(packageNode, "name");
            if (packageName == null) {
                throw new AnyhowError("JaCoCo package name");
            }
            for (Element sourceFile : XmlNodes.children(packageNode, "sourcefile")) {
                String name = XmlNodes.attribute(sourceFile, "name");
                if (name == null) {
                    throw new AnyhowError("JaCoCo source name");
                }
                Path relative = Path.of(packageName).resolve(name);
                boolean parent = false;
                for (Path component : relative) {
                    if (component.toString().equals("..")) {
                        parent = true;
                    }
                }
                if (relative.isAbsolute() || parent) {
                    throw new AnyhowError("invalid JaCoCo source path");
                }
                List<Path> matches = sourceRoots.stream()
                        .map(rootPath -> rootPath.resolve(relative))
                        .filter(Files::isRegularFile)
                        .toList();
                if (matches.size() != 1) {
                    throw new AnyhowError("JaCoCo source " + relative + " must resolve uniquely");
                }
                out.append("SF:").append(matches.getFirst()).append('\n');
                for (Element line : XmlNodes.children(sourceFile, "line")) {
                    String numberText = XmlNodes.attribute(line, "nr");
                    if (numberText == null) {
                        throw new AnyhowError("JaCoCo line number");
                    }
                    long number = RustParse.u32(numberText);
                    String coveredText = XmlNodes.attribute(line, "ci");
                    if (coveredText == null) {
                        throw new AnyhowError("JaCoCo covered instructions");
                    }
                    long covered = RustParse.u64(coveredText);
                    if (number == 0) {
                        throw new AnyhowError("invalid JaCoCo line zero");
                    }
                    out.append("DA:")
                            .append(number)
                            .append(',')
                            .append(covered != 0 ? 1 : 0)
                            .append('\n');
                }
                out.append("end_of_record\n");
            }
        }
        return out.toString();
    }

    public static List<TestResult> rustTests(String output) {
        List<TestResult> results = new ArrayList<>();
        for (String line : RustText.lines(output)) {
            if (!line.startsWith("test ")) {
                continue;
            }
            String value = line.substring(5);
            int separator = value.lastIndexOf(" ... ");
            if (separator < 0) {
                continue;
            }
            String name = value.substring(0, separator);
            String status = RustText.trim(value.substring(separator + 5));
            TestStatus state;
            if (status.equals("ok")) {
                state = TestStatus.PASSED;
            } else if (status.equals("FAILED")) {
                state = TestStatus.FAILED;
            } else if (status.startsWith("ignored")) {
                state = TestStatus.SKIPPED;
            } else {
                continue;
            }
            RustPanic panic = state == TestStatus.FAILED ? rustPanic(output, name) : null;
            Path source = null;
            Long lineNumber = null;
            if (panic != null && panic.location() != null) {
                source = panic.location().path();
                lineNumber = panic.location().line();
            }
            results.add(new TestResult(
                    name, state, 0, panic == null ? null : panic.message(), source, lineNumber, List.of()));
        }
        return results;
    }

    private record RustLocation(Path path, long line) {}

    private record RustPanic(
            @Nullable String message, @Nullable RustLocation location) {}

    private static @Nullable RustPanic rustPanic(String output, String name) {
        String header = "---- " + name + " stdout ----";
        int headerIndex = output.indexOf(header);
        if (headerIndex < 0) {
            return null;
        }
        String block = output.substring(headerIndex + header.length());
        int marker = block.indexOf("panicked at");
        if (marker < 0) {
            return null;
        }
        String afterMarker = block.substring(marker + "panicked at".length());
        List<String> lines = RustText.lines(afterMarker);
        int cursor = 0;
        String first = cursor < lines.size() ? RustText.trim(lines.get(cursor++)) : "";
        String locationText;
        if (first.isEmpty()) {
            locationText = cursor < lines.size() ? RustText.trim(lines.get(cursor++)) : "";
        } else {
            locationText = first;
        }
        List<String> messageLines = new ArrayList<>();
        while (cursor < lines.size()) {
            String line = lines.get(cursor++);
            if (line.isEmpty() || line.startsWith("note: ") || line.startsWith("---- ")) {
                break;
            }
            messageLines.add(line);
        }
        String message = String.join("\n", messageLines);
        return new RustPanic(message.isEmpty() ? null : message, rustLocation(locationText));
    }

    private static @Nullable RustLocation rustLocation(String text) {
        String trimmed = text;
        while (trimmed.endsWith(":")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        int lastColon = trimmed.lastIndexOf(':');
        if (lastColon < 0) {
            return null;
        }
        String column = trimmed.substring(lastColon + 1);
        String rest = trimmed.substring(0, lastColon);
        int secondColon = rest.lastIndexOf(':');
        if (secondColon < 0) {
            return null;
        }
        long line;
        try {
            line = RustParse.u32(rest.substring(secondColon + 1));
        } catch (AnyhowError error) {
            return null;
        }
        String path = rest.substring(0, secondColon);
        try {
            RustParse.u32(column);
        } catch (AnyhowError error) {
            return null;
        }
        if (path.isEmpty()) {
            return null;
        }
        return new RustLocation(Path.of(path), line);
    }

    public static List<TestResult> vitest(String content) {
        JsonNode data = Json.parse(content);
        JsonNode suites = Json.index(data, "testResults");
        if (!suites.isArray()) {
            throw new AnyhowError("Vitest testResults missing");
        }
        List<TestResult> out = new ArrayList<>();
        for (JsonNode suite : suites) {
            JsonNode assertions = Json.index(suite, "assertionResults");
            if (!assertions.isArray()) {
                throw new AnyhowError("Vitest assertionResults missing");
            }
            for (JsonNode test : assertions) {
                String statusText = Json.string(Json.index(test, "status"));
                TestStatus status = "passed".equals(statusText)
                        ? TestStatus.PASSED
                        : "skipped".equals(statusText) || "pending".equals(statusText) || "todo".equals(statusText)
                                ? TestStatus.SKIPPED
                                : TestStatus.FAILED;
                String name = Json.string(Json.index(test, "fullName"));
                if (name == null) {
                    name = Json.string(Json.index(test, "title"));
                }
                if (name == null) {
                    throw new AnyhowError("Vitest test name missing");
                }
                JsonNode failureMessages = Json.index(test, "failureMessages");
                String message = null;
                if (failureMessages.isArray()) {
                    List<String> messages = new ArrayList<>();
                    for (JsonNode element : failureMessages) {
                        if (element.isString()) {
                            messages.add(element.stringValue());
                        }
                    }
                    message = String.join("\n", messages);
                }
                Double duration = Json.f64(Json.index(test, "duration"));
                double clamped = Math.max(duration == null ? 0.0 : duration, 0.0);
                String suiteName = Json.string(Json.index(suite, "name"));
                out.add(new TestResult(
                        name,
                        status,
                        U64.fromF64(clamped),
                        message,
                        suiteName == null ? null : Path.of(suiteName),
                        null,
                        List.of()));
            }
        }
        return out;
    }

    public static List<TestResult> playwright(String content) {
        JsonNode root = Json.parse(content);
        List<TestResult> out = new ArrayList<>();
        visit(root, out);
        JsonNode errors = Json.index(root, "errors");
        if (errors.isArray()) {
            for (JsonNode error : errors) {
                out.add(testCase("Playwright setup", TestStatus.FAILED, Json.string(Json.index(error, "message")), 0));
            }
        }
        return out;
    }

    private static void visit(JsonNode suite, List<TestResult> out) {
        JsonNode specs = Json.index(suite, "specs");
        if (specs.isArray()) {
            for (JsonNode spec : specs) {
                JsonNode tests = Json.index(spec, "tests");
                if (!tests.isArray()) {
                    throw new AnyhowError("Playwright tests missing");
                }
                for (JsonNode test : tests) {
                    String statusText = Json.string(Json.index(test, "status"));
                    TestStatus status = "expected".equals(statusText)
                            ? TestStatus.PASSED
                            : "skipped".equals(statusText) ? TestStatus.SKIPPED : TestStatus.FAILED;
                    JsonNode runs = Json.index(test, "results");
                    if (!runs.isArray()) {
                        throw new AnyhowError("Playwright results missing");
                    }
                    String file = Json.string(Json.index(spec, "file"));
                    String title = Json.string(Json.index(spec, "title"));
                    String project = Json.string(Json.index(test, "projectName"));
                    String name = (file == null ? "" : file) + ":" + SerdeJsonWriter.compact(Json.index(spec, "line"))
                            + ":" + (title == null ? "" : title) + " [" + (project == null ? "" : project) + "]";
                    String message = null;
                    if (!runs.isEmpty()) {
                        message = Json.string(Json.index(Json.index(runs.get(runs.size() - 1), "error"), "message"));
                    }
                    long duration = 0;
                    for (JsonNode run : runs) {
                        Long value = Json.u64(Json.index(run, "duration"));
                        if (value != null) {
                            duration += value;
                        }
                    }
                    Long lineValue = Json.u64(Json.index(spec, "line"));
                    List<Attachment> attachments = new ArrayList<>();
                    for (JsonNode run : runs) {
                        JsonNode runAttachments = Json.index(run, "attachments");
                        if (!runAttachments.isArray()) {
                            continue;
                        }
                        for (JsonNode attachment : runAttachments) {
                            String path = Json.string(Json.index(attachment, "path"));
                            if (path == null) {
                                continue;
                            }
                            String attachmentName = Json.string(Json.index(attachment, "name"));
                            String contentType = Json.string(Json.index(attachment, "contentType"));
                            attachments.add(new Attachment(
                                    attachmentName == null ? "attachment" : attachmentName,
                                    Path.of(path),
                                    contentType == null ? "application/octet-stream" : contentType));
                        }
                    }
                    out.add(new TestResult(
                            name,
                            status,
                            duration,
                            message,
                            file == null ? null : Path.of(file),
                            lineValue == null ? null : lineValue & 0xFFFFFFFFL,
                            attachments));
                }
            }
        }
        JsonNode children = Json.index(suite, "suites");
        if (children.isArray()) {
            for (JsonNode child : children) {
                visit(child, out);
            }
        }
    }

    public static List<Path> files(Path root, String extension) {
        List<Path> out = new ArrayList<>();
        if (!Files.exists(root)) {
            return out;
        }
        if (Files.isRegularFile(root)) {
            Path fileName = root.getFileName();
            String name = fileName == null ? "" : fileName.toString();
            int dot = name.lastIndexOf('.');
            if (dot > 0 && name.substring(dot + 1).equals(extension)) {
                out.add(root);
            }
            return out;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
            for (Path item : entries) {
                if (Files.isSymbolicLink(item)) {
                    continue;
                }
                if (Files.isDirectory(item, LinkOption.NOFOLLOW_LINKS)
                        || Files.isRegularFile(item, LinkOption.NOFOLLOW_LINKS)) {
                    out.addAll(files(item, extension));
                }
            }
        } catch (IOException exception) {
            throw new AnyhowError(RustIoError.display(exception), null);
        }
        out.sort(Comparator.comparing(Path::toString, RustOrdering.PATH_ORDER));
        return out;
    }
}
