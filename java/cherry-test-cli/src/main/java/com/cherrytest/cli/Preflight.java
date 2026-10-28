package com.cherrytest.cli;

import com.cherrytest.cli.nativeos.Terminal;
import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.common.rustcompat.RustIoError;
import com.cherrytest.common.rustcompat.RustText;
import com.cherrytest.core.ExternalRunner;
import com.cherrytest.core.PackageConfig;
import com.cherrytest.core.PackageRuntime;
import com.cherrytest.core.ProjectConfig;
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
                    JavaSelection selection = javaSelection(config, member.name(), project.id());
                    if (selection != null) {
                        discovered.addAll(discoverJavaPackageTests(workspaceRoot, member, config, selection));
                    }
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

    private static List<Path> walkJavaFiles(Path directory) {
        List<Path> files = new ArrayList<>();
        if (!Files.isDirectory(directory)) {
            return files;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path path : entries) {
                if (Files.isDirectory(path)) {
                    files.addAll(walkJavaFiles(path));
                } else {
                    Path name = path.getFileName();
                    String text = name == null ? "" : name.toString();
                    int dot = text.lastIndexOf('.');
                    if (dot > 0 && text.substring(dot + 1).equals("java")) {
                        files.add(path);
                    }
                }
            }
        } catch (IOException exception) {
            throw new AnyhowError(RustIoError.display(exception), null);
        }
        return files;
    }

    /** Classes and methods a Maven integration phase runs: {@code -Dit.test} / {@code -Dtest}, else Failsafe's includes. */
    record JavaSelection(List<JavaPattern> includes, List<JavaPattern> excludes) {
        boolean selects(String classPath, String method) {
            return includes.stream().anyMatch(pattern -> pattern.matches(classPath, method))
                    && excludes.stream().noneMatch(pattern -> pattern.matches(classPath, method));
        }

        boolean selectsClass(String classPath) {
            return includes.stream()
                    .anyMatch(pattern -> globMatches(pattern.classPattern(), pattern.target(classPath)));
        }
    }

    record JavaPattern(String className, List<String> methods) {
        String classPattern() {
            return className.replace('.', '/');
        }

        String target(String classPath) {
            if (className.contains("/") || className.contains(".")) {
                return classPath;
            }
            int slash = classPath.lastIndexOf('/');
            return slash < 0 ? classPath : classPath.substring(slash + 1);
        }

        boolean matches(String classPath, String method) {
            return globMatches(classPattern(), target(classPath).replace('.', '/'))
                    && (methods.isEmpty() || methods.stream().anyMatch(m -> globMatches(m, method)));
        }
    }

    static @Nullable JavaSelection javaSelection(TestItConfig config, String packageName, String projectId) {
        ProjectConfig project = config.projects().get(projectId);
        PackageConfig pkg = project == null ? null : project.packages().get(packageName);
        if (pkg == null) {
            return null;
        }
        ExternalRunner runner = pkg.integration();
        String adapter = runner != null && runner.adapter() != null ? runner.adapter() : pkg.adapter();
        if (!"maven".equals(adapter)) {
            return null;
        }
        return parseJavaSelection(runner == null ? List.of() : runner.args());
    }

    static JavaSelection parseJavaSelection(List<String> args) {
        String value = null;
        for (int at = args.size() - 1; at >= 0 && value == null; at--) {
            String arg = args.get(at);
            if (arg.startsWith("-Dit.test=")) {
                value = arg.substring("-Dit.test=".length());
            } else if (arg.startsWith("-Dtest=")) {
                value = arg.substring("-Dtest=".length());
            }
        }
        if (value == null) {
            List<JavaPattern> defaults = new ArrayList<>();
            for (String pattern : List.of("IT*", "*IT", "*ITCase")) {
                defaults.add(new JavaPattern(pattern, List.of()));
            }
            return new JavaSelection(defaults, List.of());
        }
        List<JavaPattern> includes = new ArrayList<>();
        List<JavaPattern> excludes = new ArrayList<>();
        for (String raw : value.split(",", -1)) {
            String token = RustText.trim(raw);
            if (token.isEmpty()) {
                continue;
            }
            boolean exclude = token.startsWith("!");
            if (exclude) {
                token = token.substring(1);
            }
            String className = token;
            List<String> methods = new ArrayList<>();
            int hash = token.indexOf('#');
            if (hash >= 0) {
                className = token.substring(0, hash);
                for (String method : token.substring(hash + 1).split("\\+", -1)) {
                    if (!method.isEmpty()) {
                        methods.add(method);
                    }
                }
            }
            while (className.startsWith("**/")) {
                className = className.substring(3);
            }
            if (className.endsWith(".java")) {
                className = className.substring(0, className.length() - ".java".length());
            }
            if (className.endsWith(".class")) {
                className = className.substring(0, className.length() - ".class".length());
            }
            (exclude ? excludes : includes).add(new JavaPattern(className, List.copyOf(methods)));
        }
        return new JavaSelection(includes, excludes);
    }

    static boolean globMatches(String pattern, String text) {
        return globMatches(pattern.codePoints().toArray(), 0, text.codePoints().toArray(), 0);
    }

    private static boolean globMatches(int[] pattern, int p, int[] text, int t) {
        if (p == pattern.length) {
            return t == text.length;
        }
        if (pattern[p] == '*') {
            for (int skip = t; skip <= text.length; skip++) {
                if (globMatches(pattern, p + 1, text, skip)) {
                    return true;
                }
            }
            return false;
        }
        if (t == text.length) {
            return false;
        }
        return (pattern[p] == '?' || pattern[p] == text[t]) && globMatches(pattern, p + 1, text, t + 1);
    }

    private static List<DiscoveredTest> discoverJavaPackageTests(
            Path workspaceRoot, WorkspaceMember member, TestItConfig config, JavaSelection selection) {
        Path parent = member.manifestPath().getParent();
        Path packageDir = workspaceRoot.resolve(parent == null ? Path.of(".") : parent);
        Path sources = packageDir.resolve("src").resolve("test").resolve("java");
        List<Path> entries = walkJavaFiles(sources);
        entries.sort(null);
        List<DiscoveredTest> out = new ArrayList<>();
        for (Path entry : entries) {
            Path inside = entry.startsWith(sources) ? sources.relativize(entry) : entry;
            String file = inside.toString().replace('\\', '/');
            String classPath = file.endsWith(".java") ? file.substring(0, file.length() - ".java".length()) : file;
            if (!selection.selectsClass(classPath)) {
                continue;
            }
            Path relative = entry.startsWith(workspaceRoot) ? workspaceRoot.relativize(entry) : entry;
            String content;
            try {
                content = Files.readString(entry, StandardCharsets.UTF_8);
            } catch (IOException exception) {
                throw new AnyhowError(RustIoError.display(exception), null);
            }
            for (DiscoveredTest test :
                    parseJavaTestSources(member.name(), classPath.replace('/', '.'), relative, content, config)) {
                String function = test.function();
                int separator = function.lastIndexOf("::");
                String method = separator < 0 ? function : function.substring(separator + 2);
                if (selection.selects(classPath, method)) {
                    out.add(test);
                }
            }
        }
        return out;
    }

    /**
     * {@code @CherryTest(scope = "...", auth = "...")} methods of a JUnit source, the Java form of {@code #[cherry_test]}. A
     * test is named {@code <class>::<method>}, as the Maven and Gradle adapters report it.
     */
    static List<DiscoveredTest> parseJavaTestSources(
            String packageName, String className, Path sourcePath, String content, TestItConfig config) {
        List<DiscoveredTest> out = new ArrayList<>();
        List<String> lines = RustText.lines(content);
        int index = 0;
        while (index < lines.size()) {
            String line = RustText.trim(lines.get(index));
            String rest = line.startsWith("@CherryTest")
                    ? line.substring("@CherryTest".length())
                    : line.startsWith("@com.cherrytest.junit.CherryTest")
                            ? line.substring("@com.cherrytest.junit.CherryTest".length())
                            : null;
            boolean annotation = rest != null && (rest.isEmpty() || rest.startsWith("(") || rest.startsWith(" "));
            if (!annotation) {
                index++;
                continue;
            }
            StringBuilder collected = new StringBuilder(line);
            while (collected.indexOf("(") >= 0 && collected.indexOf(")") < 0 && index + 1 < lines.size()) {
                index++;
                collected.append(' ').append(RustText.trim(lines.get(index)));
            }
            String attribute = normalizeJavaAttr(collected.toString());
            String attrScope = parseStringAttr(attribute, "scope");
            String attrAuth = parseStringAttr(attribute, "auth");
            index++;
            while (index < lines.size()) {
                String next = RustText.trim(lines.get(index));
                if (next.startsWith("@CherryTest") || next.contains(" class ") || next.startsWith("class ")) {
                    break;
                }
                if (!next.startsWith("@") && !next.startsWith("//") && next.contains("(")) {
                    String name = parseJavaMethodName(next);
                    if (name != null && TestId.isValid(name)) {
                        TestId id = TestId.parse(name);
                        out.add(new DiscoveredTest(
                                packageName,
                                className + "::" + name,
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
                index++;
            }
        }
        return out;
    }

    /** Writes {@code key="v"}, {@code key ="v"} and {@code key= "v"} as {@code key = "v"}, the form the shared parser reads. */
    static String normalizeJavaAttr(String attribute) {
        String compact = attribute;
        while (true) {
            String next = compact.replace(" =", "=").replace("= ", "=");
            if (next.equals(compact)) {
                break;
            }
            compact = next;
        }
        return compact.replace("=\"", " = \"");
    }

    private static @Nullable String parseJavaMethodName(String line) {
        int paren = line.indexOf('(');
        String before = RustText.trim(paren < 0 ? line : line.substring(0, paren));
        int space = Math.max(before.lastIndexOf(' '), before.lastIndexOf('\t'));
        String name = before.substring(space + 1);
        if (name.isEmpty() || !name.codePoints().allMatch(c -> Character.isLetterOrDigit(c) || c == '_')) {
            return null;
        }
        return name;
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
