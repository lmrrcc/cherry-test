package com.cherrytest.cli;

import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.common.rustcompat.RustIoError;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class NodeDiscover {
    private static final Pattern TEST_NAME = Pattern.compile(
            "(?:\\bit|\\btest)\\(\\s*['\"]([^'\"]+)['\"]", Pattern.MULTILINE | Pattern.UNICODE_CHARACTER_CLASS);

    private NodeDiscover() {}

    public static List<DiscoveredTest> discoverPackageNodeTests(
            Path workspaceRoot, WorkspaceMember member, String projectId) {
        Path parent = member.manifestPath().getParent();
        Path packageDir = workspaceRoot.resolve(parent == null ? Path.of(".") : parent);
        if (!Files.isDirectory(packageDir)) {
            return List.of();
        }
        List<DiscoveredTest> out = new ArrayList<>();
        for (Path file : walkTestFiles(packageDir)) {
            Path relative = file.startsWith(workspaceRoot) ? workspaceRoot.relativize(file) : file;
            String content;
            try {
                content = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException exception) {
                throw new AnyhowError(RustIoError.display(exception), null);
            }
            Matcher matcher = TEST_NAME.matcher(content);
            while (matcher.find()) {
                String title = matcher.group(1) == null ? "unnamed" : matcher.group(1);
                String testSlug = VitestReport.slugify(title);
                String function = projectId + "_project_unit_suite_" + testSlug + "_test";
                out.add(new DiscoveredTest(
                        member.name(),
                        function,
                        null,
                        null,
                        "none",
                        projectId,
                        "unit",
                        testSlug,
                        relative,
                        lineNumberForMatch(content, matcher.start())));
            }
        }
        return out;
    }

    private static List<Path> walkTestFiles(Path directory) {
        List<Path> files = new ArrayList<>();
        if (!Files.isDirectory(directory)) {
            return files;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path path : entries) {
                if (Files.isDirectory(path)) {
                    Path name = path.getFileName();
                    if (name != null && name.toString().equals("node_modules")) {
                        continue;
                    }
                    files.addAll(walkTestFiles(path));
                    continue;
                }
                if (isTestFile(path)) {
                    files.add(path);
                }
            }
        } catch (IOException exception) {
            throw new AnyhowError(RustIoError.display(exception), null);
        }
        return files;
    }

    private static boolean isTestFile(Path path) {
        Path fileName = path.getFileName();
        String name = fileName == null ? "" : fileName.toString();
        return (name.endsWith(".test.ts")
                        || name.endsWith(".test.tsx")
                        || name.endsWith(".spec.ts")
                        || name.endsWith(".spec.tsx")
                        || name.endsWith(".test.vue")
                        || name.endsWith(".spec.vue"))
                && Files.isRegularFile(path);
    }

    private static long lineNumberForMatch(String content, int offset) {
        long count = 0;
        int end = Math.min(offset, content.length());
        for (int index = 0; index < end; index++) {
            if (content.charAt(index) == '\n') {
                count++;
            }
        }
        return count + 1;
    }
}
