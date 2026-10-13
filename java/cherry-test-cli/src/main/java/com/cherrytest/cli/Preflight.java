package com.cherrytest.cli;

import com.cherrytest.cli.nativeos.Terminal;
import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.common.rustcompat.RustIoError;
import com.cherrytest.common.rustcompat.RustText;
import com.cherrytest.core.PackageRuntime;
import com.cherrytest.core.Scope;
import com.cherrytest.core.TestId;
import com.cherrytest.core.TestItConfig;
import com.cherrytest.core.Workspace;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

public final class Preflight {
    private Preflight() {}

    public static PreflightPlan buildPreflightPlan(
            Path workspaceRoot, List<ProjectGroup> projects, TestItConfig config, boolean quiet) {
        List<DiscoveredTest> discovered = new ArrayList<>();
        for (ProjectGroup project : projects) {
            for (WorkspaceMember member : project.members()) {
                List<String> kinds = config.packageKinds(member.name(), project.id());
                if (kinds.contains("integration")) {
                    discovered.addAll(discoverPackageTests(workspaceRoot, member, config));
                }
                if (config.packageRuntime(member.name(), project.id()) == PackageRuntime.NODE
                        && kinds.contains("unit")) {
                    discovered.addAll(NodeDiscover.discoverPackageNodeTests(workspaceRoot, member, project.id()));
                }
            }
        }
        boolean dockerRequired = discovered.stream().anyMatch(test -> Scope.needsDocker(test.effectiveScope()));
        boolean dockerOk = !dockerRequired || Workspace.dockerAvailable();
        List<String> skipTests = new ArrayList<>();
        List<SkippedTest> skipped = new ArrayList<>();
        List<String> scopesToBoot = new ArrayList<>();
        if (dockerRequired && dockerOk) {
            for (DiscoveredTest test : discovered) {
                if (Scope.needsDocker(test.effectiveScope()) && !scopesToBoot.contains(test.effectiveScope())) {
                    scopesToBoot.add(test.effectiveScope());
                }
            }
        }
        if (dockerRequired && !dockerOk) {
            if (!quiet) {
                Terminal.eprintln("warning: Docker not available; skipping scoped integration test(s):");
            }
            for (DiscoveredTest test : discovered) {
                if (!Scope.needsDocker(test.effectiveScope())) {
                    continue;
                }
                if (!quiet) {
                    Terminal.eprintln("  - " + test.function() + " (scope=" + test.effectiveScope() + ")");
                }
                skipTests.add(test.function());
                skipped.add(new SkippedTest(
                        test.packageName(),
                        test.function(),
                        test.effectiveScope(),
                        test.project(),
                        test.suite(),
                        test.test(),
                        "no docker"));
            }
        }
        return new PreflightPlan(dockerOk, skipTests, skipped, scopesToBoot, discovered);
    }

    private static List<DiscoveredTest> discoverPackageTests(
            Path workspaceRoot, WorkspaceMember member, TestItConfig config) {
        Path parent = member.manifestPath().getParent();
        Path packageDir = workspaceRoot.resolve(parent == null ? Path.of(".") : parent);
        Path testsDir = packageDir.resolve("tests");
        if (!Files.exists(testsDir)) {
            return List.of();
        }
        List<DiscoveredTest> out = new ArrayList<>();
        for (Path entry : walkRsFiles(testsDir)) {
            Path relative = entry.startsWith(workspaceRoot) ? workspaceRoot.relativize(entry) : entry;
            String content;
            try {
                content = Files.readString(entry, StandardCharsets.UTF_8);
            } catch (IOException exception) {
                throw new AnyhowError(RustIoError.display(exception), null);
            }
            out.addAll(parseTestSources(member.name(), relative, content, config));
        }
        return out;
    }

    private static List<Path> walkRsFiles(Path directory) {
        List<Path> files = new ArrayList<>();
        if (!Files.isDirectory(directory)) {
            return files;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path path : entries) {
                if (Files.isDirectory(path)) {
                    files.addAll(walkRsFiles(path));
                } else {
                    Path name = path.getFileName();
                    String text = name == null ? "" : name.toString();
                    int dot = text.lastIndexOf('.');
                    if (dot > 0 && text.substring(dot + 1).equals("rs")) {
                        files.add(path);
                    }
                }
            }
        } catch (IOException exception) {
            throw new AnyhowError(RustIoError.display(exception), null);
        }
        return files;
    }

    static List<DiscoveredTest> parseTestSources(
            String packageName, Path sourcePath, String content, TestItConfig config) {
        List<DiscoveredTest> out = new ArrayList<>();
        List<String> lines = RustText.lines(content);
        int index = 0;
        while (index < lines.size()) {
            String line = RustText.trim(lines.get(index));
            if (line.startsWith("#[cherry_test")) {
                StringBuilder attribute = new StringBuilder(line);
                while (attribute.indexOf("]") < 0 && index + 1 < lines.size()) {
                    index++;
                    attribute.append(' ').append(RustText.trim(lines.get(index)));
                }
                String attrScope = parseStringAttr(attribute.toString(), "scope");
                String attrAuth = parseStringAttr(attribute.toString(), "auth");
                index++;
                while (index < lines.size()) {
                    String next = RustText.trim(lines.get(index));
                    if (next.startsWith("async fn ")) {
                        String name = parseFnName(next);
                        if (name != null && TestId.isValid(name)) {
                            TestId id = TestId.parse(name);
                            out.add(new DiscoveredTest(
                                    packageName,
                                    name,
                                    attrScope,
                                    attrAuth,
                                    Scope.effectiveScope(config, attrScope),
                                    id.project(),
                                    id.suite(),
                                    id.test(),
                                    sourcePath,
                                    index + 1L));
                        }
                        break;
                    }
                    if (next.startsWith("#[")) {
                        break;
                    }
                    index++;
                }
            }
            index++;
        }
        return out;
    }

    static @Nullable String parseStringAttr(String attribute, String key) {
        String needle = key + " = \"";
        int at = attribute.lastIndexOf(needle);
        if (at < 0) {
            return null;
        }
        String rest = attribute.substring(at + needle.length());
        int end = rest.indexOf('"');
        if (end < 0) {
            return null;
        }
        return rest.substring(0, end);
    }

    private static @Nullable String parseFnName(String line) {
        if (!line.startsWith("async fn ")) {
            return null;
        }
        String rest = line.substring("async fn ".length());
        int paren = rest.indexOf('(');
        return RustText.trim(paren < 0 ? rest : rest.substring(0, paren));
    }
}
