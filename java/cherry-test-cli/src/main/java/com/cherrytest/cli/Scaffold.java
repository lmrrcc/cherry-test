package com.cherrytest.cli;

import com.cherrytest.cli.adapters.Json;
import com.cherrytest.cli.nativeos.Terminal;
import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.common.rustcompat.RustIoError;
import com.cherrytest.common.rustcompat.RustOrdering;
import com.cherrytest.common.rustcompat.RustText;
import com.cherrytest.core.ConfigWriter;
import com.cherrytest.core.Defaults;
import com.cherrytest.core.ExternalRunner;
import com.cherrytest.core.PackageConfig;
import com.cherrytest.core.PackageRuntime;
import com.cherrytest.core.ProjectConfig;
import com.cherrytest.core.ScopeConfig;
import com.cherrytest.core.ServiceConfig;
import com.cherrytest.core.TestItConfig;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.jspecify.annotations.Nullable;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import tools.jackson.databind.JsonNode;

public final class Scaffold {
    private static final List<String> SKIP_CRATE_NAMES =
            List.of("cherry-test", "cherry-test-core", "cherry-test-macros");
    private static final List<String> SKIP_DIR_NAMES =
            List.of("target", "node_modules", ".git", ".idea", ".cursor", "dist", "build");
    private static final Pattern CMAKE_PROJECT =
            Pattern.compile("(?i)project\\s*\\(\\s*[\"]?([A-Za-z0-9_.-]+)", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern GRADLE_ROOT =
            Pattern.compile("rootProject\\.name\\s*=\\s*['\"]([^'\"]+)['\"]", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern SCOPE_ATTRIBUTE = Pattern.compile(
            "#\\[cherry_test\\s*\\(\\s*scope\\s*=\\s*\"([^\"]+)\"\\s*\\)\\]", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern PROJECT_CALL =
            Pattern.compile("\\.project\\s*\\(\\s*\"([^\"]+)\"\\s*\\)", Pattern.UNICODE_CHARACTER_CLASS);

    private record DiscoveredPackage(
            String name,
            String projectId,
            Path manifestRel,
            Path packageDirRel,
            PackageRuntime runtime,
            List<String> kinds) {}

    private Scaffold() {}

    public static TestItConfig scaffoldAndWrite(Path root, boolean quiet) {
        TestItConfig config = scaffoldConfig(root);
        ConfigWriter.write(config);
        if (!quiet) {
            long packageCount = config.projects().values().stream()
                    .mapToLong(project -> project.packages().size())
                    .sum();
            Terminal.println("cherry-test: created " + config.configPath() + " ("
                    + config.projects().size() + " projects, " + packageCount + " packages)");
        }
        return config;
    }

    public static boolean configNeedsScaffold(TestItConfig config, Path workspace) {
        if (config.projects().isEmpty()) {
            return true;
        }
        for (Map.Entry<String, ProjectConfig> entry : config.projects().entrySet()) {
            String base = entry.getValue().path() == null
                    ? entry.getKey()
                    : entry.getValue().path();
            if (!Files.isDirectory(workspace.resolve(base))) {
                return true;
            }
            for (PackageConfig packageConfig : entry.getValue().packages().values()) {
                if (packageConfig.manifest() != null
                        && !Files.isRegularFile(workspace.resolve(packageConfig.manifest()))) {
                    return true;
                }
            }
        }
        return false;
    }

    public static TestItConfig scaffoldConfig(Path root) {
        List<DiscoveredPackage> packages = discoverPackages(root);
        if (packages.isEmpty()) {
            throw new AnyhowError("no supported test packages found under " + root);
        }
        TreeMap<String, List<DiscoveredPackage>> byProject = new TreeMap<>(RustOrdering.STRING_ORDER);
        for (DiscoveredPackage discovered : packages) {
            byProject
                    .computeIfAbsent(discovered.projectId(), key -> new ArrayList<>())
                    .add(discovered);
        }
        TreeMap<String, ProjectConfig> projects = new TreeMap<>(RustOrdering.STRING_ORDER);
        for (Map.Entry<String, List<DiscoveredPackage>> entry : byProject.entrySet()) {
            SortedMap<String, PackageConfig> packageConfigs = new TreeMap<>(RustOrdering.STRING_ORDER);
            for (DiscoveredPackage discovered : entry.getValue()) {
                packageConfigs.put(discovered.name(), packageConfig(root, discovered));
            }
            projects.put(
                    entry.getKey(),
                    new ProjectConfig(
                            projectPathForPackages(entry.getValue()),
                            "main",
                            null,
                            Map.of(),
                            packageConfigs,
                            null,
                            null));
        }
        for (Map.Entry<String, ProjectConfig> entry : projects.entrySet()) {
            ProjectConfig project = entry.getValue();
            String projectPath = project.path() == null ? "." : project.path();
            String compose = findComposeFile(root, projectPath);
            if (compose != null) {
                entry.setValue(new ProjectConfig(
                        project.path(),
                        project.diffBase(),
                        compose,
                        inferServices(root, compose),
                        project.packages(),
                        project.functional(),
                        project.ui()));
            }
        }
        SortedMap<String, ScopeConfig> scopes = inferScopes(root, packages, projects);
        return new TestItConfig(
                new TreeMap<>(),
                root,
                root.resolve("cherry-test.yaml"),
                Defaults.standard(),
                new TreeMap<>(),
                projects,
                scopes);
    }

    private static List<DiscoveredPackage> discoverPackages(Path root) {
        List<DiscoveredPackage> out = new ArrayList<>();
        walkPackages(root, root, 0, out);
        out.sort((left, right) -> RustOrdering.compareStrings(left.name(), right.name()));
        return out;
    }

    private static void walkPackages(Path root, Path directory, int depth, List<DiscoveredPackage> out) {
        if (depth > 6) {
            return;
        }
        Path fileName = directory.getFileName();
        String name = fileName == null ? "" : fileName.toString();
        if (depth > 0 && shouldSkipDir(name, directory, root)) {
            return;
        }
        Path cargo = directory.resolve("Cargo.toml");
        if (Files.isRegularFile(cargo)) {
            DiscoveredPackage discovered = parseRustPackage(root, directory, cargo);
            if (discovered != null) {
                out.add(discovered);
                return;
            }
        }
        Path packageJson = directory.resolve("package.json");
        if (Files.isRegularFile(packageJson)) {
            DiscoveredPackage discovered = parseNodePackage(root, directory, packageJson);
            if (discovered != null) {
                out.add(discovered);
                return;
            }
        }
        for (String manifestName : List.of("pom.xml", "build.gradle.kts", "build.gradle", "CMakeLists.txt")) {
            Path manifest = directory.resolve(manifestName);
            if (!Files.isRegularFile(manifest)) {
                continue;
            }
            Path relative = root.relativize(directory);
            String content = read(manifest);
            String packageName;
            if (manifestName.equals("pom.xml")) {
                packageName = mavenArtifactId(content);
            } else if (manifestName.equals("CMakeLists.txt")) {
                Matcher matcher = CMAKE_PROJECT.matcher(content);
                if (!matcher.find()) {
                    throw new AnyhowError("CMake project name missing");
                }
                packageName = matcher.group(1);
            } else {
                String settings = "";
                for (String settingsName : List.of("settings.gradle.kts", "settings.gradle")) {
                    Path settingsPath = directory.resolve(settingsName);
                    if (Files.isRegularFile(settingsPath)) {
                        settings = read(settingsPath);
                        break;
                    }
                }
                Matcher matcher = GRADLE_ROOT.matcher(settings);
                packageName = matcher.find() ? matcher.group(1) : name;
            }
            out.add(new DiscoveredPackage(
                    packageName,
                    inferProjectId(relative, packageName),
                    relative.resolve(manifestName),
                    relative,
                    PackageRuntime.RUST,
                    List.of("unit")));
            return;
        }
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path path : entries) {
                if (Files.isDirectory(path)) {
                    walkPackages(root, path, depth + 1, out);
                }
            }
        } catch (IOException exception) {
            throw new AnyhowError(RustIoError.display(exception), null);
        }
    }

    private static String mavenArtifactId(String content) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Element root = factory.newDocumentBuilder()
                    .parse(new InputSource(new StringReader(content)))
                    .getDocumentElement();
            for (Node child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
                String local = child.getLocalName() == null ? child.getNodeName() : child.getLocalName();
                if (child.getNodeType() == Node.ELEMENT_NODE && local.equals("artifactId")) {
                    Node text = child.getFirstChild();
                    if (text != null && text.getNodeType() == Node.TEXT_NODE) {
                        return text.getNodeValue();
                    }
                    break;
                }
            }
        } catch (Exception exception) {
            throw AnyhowError.context("parse Maven manifest", new AnyhowError(String.valueOf(exception.getMessage())));
        }
        throw new AnyhowError("Maven artifactId missing");
    }

    private static boolean shouldSkipDir(String name, Path directory, Path root) {
        if (SKIP_DIR_NAMES.contains(name)) {
            return true;
        }
        if (name.startsWith(".")) {
            return true;
        }
        if (directory.startsWith(root)) {
            return root.relativize(directory).toString().startsWith("test/cherry-test");
        }
        return false;
    }

    private static @Nullable DiscoveredPackage parseRustPackage(Path root, Path directory, Path cargo) {
        String name = parseTomlPackageName(read(cargo));
        if (name == null || SKIP_CRATE_NAMES.contains(name)) {
            return null;
        }
        Path relative = directory.startsWith(root) ? root.relativize(directory) : directory;
        List<String> kinds = hasCherryTestIntegration(directory) ? List.of("unit", "integration") : List.of("unit");
        return new DiscoveredPackage(
                name,
                inferProjectId(relative, name),
                relative.resolve("Cargo.toml"),
                relative,
                PackageRuntime.RUST,
                kinds);
    }

    private static @Nullable DiscoveredPackage parseNodePackage(Path root, Path directory, Path packageJson) {
        JsonNode value = Json.parse(read(packageJson));
        JsonNode nameNode = Json.index(value, "name");
        if (!nameNode.isString()) {
            throw new AnyhowError("package.json missing name");
        }
        String name = nameNode.stringValue();
        if (!hasVitest(value)) {
            return null;
        }
        Path relative = directory.startsWith(root) ? root.relativize(directory) : directory;
        return new DiscoveredPackage(
                name,
                inferProjectId(relative, name),
                relative.resolve("package.json"),
                relative,
                PackageRuntime.NODE,
                List.of("unit"));
    }

    private static @Nullable String parseTomlPackageName(String contents) {
        for (String rawLine : RustText.lines(contents)) {
            String line = RustText.trim(rawLine);
            if (line.startsWith("[")) {
                continue;
            }
            if (line.startsWith("name")) {
                String rest = RustText.trim(line.substring(4));
                if (rest.startsWith("=")) {
                    String value = RustText.trim(rest.substring(1));
                    int start = 0;
                    int end = value.length();
                    while (start < end && value.charAt(start) == '"') {
                        start++;
                    }
                    while (end > start && value.charAt(end - 1) == '"') {
                        end--;
                    }
                    String trimmed = value.substring(start, end);
                    if (!trimmed.isEmpty()) {
                        return trimmed;
                    }
                }
            }
        }
        return null;
    }

    private static boolean hasVitest(JsonNode value) {
        if (Json.index(Json.index(value, "devDependencies"), "vitest").isString()) {
            return true;
        }
        if (Json.index(Json.index(value, "dependencies"), "vitest").isString()) {
            return true;
        }
        JsonNode scripts = Json.index(value, "scripts");
        if (!scripts.isObject()) {
            return false;
        }
        for (Map.Entry<String, JsonNode> script : scripts.properties()) {
            if (script.getValue().isString() && script.getValue().stringValue().contains("vitest")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasCherryTestIntegration(Path directory) {
        Path testsDir = directory.resolve("tests");
        return Files.isDirectory(testsDir) && walkRsForMarker(testsDir, "#[cherry_test");
    }

    private static boolean walkRsForMarker(Path directory, String marker) {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path path : entries) {
                if (Files.isDirectory(path)) {
                    if (walkRsForMarker(path, marker)) {
                        return true;
                    }
                } else if (path.toString().endsWith(".rs")) {
                    if (fileContains(path, marker)) {
                        return true;
                    }
                }
            }
        } catch (IOException exception) {
            return false;
        }
        return false;
    }

    private static boolean fileContains(Path path, String marker) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8).contains(marker);
        } catch (IOException | java.io.UncheckedIOException unreadable) {
            return false;
        }
    }

    private static String inferProjectId(Path relative, String packageName) {
        if (relative.getNameCount() >= 2 && !relative.toString().isEmpty()) {
            return relative.getName(0).toString();
        }
        int dash = packageName.indexOf('-');
        return dash < 0 ? packageName : packageName.substring(0, dash);
    }

    private static PackageConfig packageConfig(Path root, DiscoveredPackage discovered) {
        Path manifestName = discovered.manifestRel().getFileName();
        String adapter = switch (manifestName == null ? "" : manifestName.toString()) {
            case "pom.xml" -> "maven";
            case "build.gradle", "build.gradle.kts" -> "gradle";
            case "CMakeLists.txt" -> "cmake";
            default -> null;
        };
        ExternalRunner unit =
                discovered.runtime() == PackageRuntime.NODE ? nodeUnitRunner(root, discovered.packageDirRel()) : null;
        return new PackageConfig(
                adapter,
                null,
                new TreeMap<>(),
                null,
                null,
                discovered.runtime(),
                discovered.kinds(),
                discovered.manifestRel(),
                unit,
                null,
                null,
                null);
    }

    private static ExternalRunner nodeUnitRunner(Path root, Path packageDirRel) {
        if (prefersPnpm(root, packageDirRel)) {
            return new ExternalRunner(
                    null, null, new TreeMap<>(), "pnpm", List.of("exec", "vitest", "run", "--coverage"), packageDirRel);
        }
        return new ExternalRunner(
                null, null, new TreeMap<>(), "npx", List.of("vitest", "run", "--coverage"), packageDirRel);
    }

    private static boolean prefersPnpm(Path root, Path packageDirRel) {
        if (Files.exists(root.resolve("pnpm-lock.yaml")) || Files.exists(root.resolve("pnpm-workspace.yaml"))) {
            return true;
        }
        if (packageDirRel.getNameCount() >= 1 && !packageDirRel.toString().isEmpty()) {
            Path projectRoot = root.resolve(packageDirRel.getName(0));
            return Files.exists(projectRoot.resolve("pnpm-lock.yaml"))
                    || Files.exists(projectRoot.resolve("pnpm-workspace.yaml"));
        }
        return false;
    }

    private static String projectPathForPackages(List<DiscoveredPackage> packages) {
        Path prefix = null;
        for (DiscoveredPackage discovered : packages) {
            prefix = prefix == null ? discovered.packageDirRel() : commonPathPrefix(prefix, discovered.packageDirRel());
        }
        if (prefix == null || prefix.toString().isEmpty()) {
            return ".";
        }
        return prefix.toString();
    }

    private static Path commonPathPrefix(Path left, Path right) {
        Path shared = Path.of("");
        int count = Math.min(left.getNameCount(), right.getNameCount());
        if (left.toString().isEmpty() || right.toString().isEmpty()) {
            return shared;
        }
        for (int index = 0; index < count; index++) {
            if (left.getName(index).equals(right.getName(index))) {
                shared = shared.resolve(left.getName(index));
            } else {
                break;
            }
        }
        return shared;
    }

    private static @Nullable String findComposeFile(Path root, String projectPath) {
        Path projectDir = root.resolve(projectPath);
        for (String name : List.of("docker-compose.test.yml", "docker-compose.yml")) {
            if (Files.isRegularFile(projectDir.resolve(name))) {
                return projectPath.equals(".") ? name : projectPath + "/" + name;
            }
        }
        return null;
    }

    private static Map<String, ServiceConfig> inferServices(Path root, String composeRel) {
        Path path = root.resolve(composeRel);
        String contents;
        try {
            contents = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw AnyhowError.context("read " + path, new AnyhowError(RustIoError.display(exception)));
        }
        Object document = new Load(LoadSettings.builder().build()).loadFromString(contents);
        Map<String, ServiceConfig> out = new LinkedHashMap<>();
        if (!(document instanceof Map<?, ?> top) || !(top.get("services") instanceof Map<?, ?> services)) {
            return out;
        }
        for (Map.Entry<?, ?> entry : services.entrySet()) {
            if (!(entry.getKey() instanceof String serviceName) || !(entry.getValue() instanceof Map<?, ?> service)) {
                continue;
            }
            String image = service.get("image") instanceof String text ? text : "";
            if (RustText.toAsciiLowercase(image).contains("postgres")) {
                out.put("db", new ServiceConfig(serviceName, 5432, null, "postgres"));
                continue;
            }
            if (service.containsKey("build")) {
                Integer port = extractComposePort(service);
                if (port != null) {
                    String health = extractHealthPath(service);
                    out.put("api", new ServiceConfig(serviceName, port, health == null ? "/health" : health, null));
                }
            }
        }
        return out;
    }

    private static @Nullable String extractHealthPath(Map<?, ?> service) {
        if (!(service.get("healthcheck") instanceof Map<?, ?> health)
                || !(health.get("test") instanceof List<?> sequence)) {
            return null;
        }
        for (Object item : sequence) {
            if (item instanceof String text) {
                int at = text.indexOf("http://");
                if (at < 0) {
                    continue;
                }
                String rest = text.substring(at + 7);
                int slash = rest.indexOf('/');
                if (slash >= 0) {
                    return rest.substring(slash);
                }
            }
        }
        return null;
    }

    private static @Nullable Integer extractComposePort(Map<?, ?> service) {
        if (!(service.get("ports") instanceof List<?> ports)) {
            return null;
        }
        for (Object entry : ports) {
            if (entry instanceof String text) {
                Integer port = parsePortSpec(text);
                if (port != null) {
                    return port;
                }
            } else if (entry instanceof Number number) {
                return (int) (number.longValue() & 0xFFFF);
            }
        }
        return null;
    }

    private static @Nullable Integer parsePortSpec(String text) {
        try {
            return com.cherrytest.common.rustcompat.RustParse.u16(RustText.trim(text.split(":", -1)[0]));
        } catch (AnyhowError invalidPort) {
            return null;
        }
    }

    private static SortedMap<String, ScopeConfig> inferScopes(
            Path root, List<DiscoveredPackage> packages, SortedMap<String, ProjectConfig> projects) {
        SortedMap<String, java.util.SortedSet<String>> references = collectScopeProjects(root, packages);
        List<String> composeProjects = projects.entrySet().stream()
                .filter(entry -> entry.getValue().compose() != null)
                .map(Map.Entry::getKey)
                .toList();
        TreeMap<String, ScopeConfig> scopes = new TreeMap<>(RustOrdering.STRING_ORDER);
        for (Map.Entry<String, java.util.SortedSet<String>> entry : references.entrySet()) {
            List<String> list = new ArrayList<>(entry.getValue().isEmpty() ? composeProjects : entry.getValue());
            scopes.put(
                    entry.getKey(), new ScopeConfig(com.cherrytest.common.gitdiff.GitDiff.sortedDedup(list), Map.of()));
        }
        return scopes;
    }

    private static SortedMap<String, java.util.SortedSet<String>> collectScopeProjects(
            Path root, List<DiscoveredPackage> packages) {
        SortedMap<String, java.util.SortedSet<String>> scopes = new TreeMap<>(RustOrdering.STRING_ORDER);
        for (DiscoveredPackage discovered : packages) {
            if (discovered.runtime() != PackageRuntime.RUST) {
                continue;
            }
            Path testsDir = root.resolve(discovered.packageDirRel()).resolve("tests");
            if (!Files.isDirectory(testsDir)) {
                continue;
            }
            for (Path file : walkRsFiles(testsDir)) {
                String content = read(file);
                Matcher scopeMatcher = SCOPE_ATTRIBUTE.matcher(content);
                while (scopeMatcher.find()) {
                    String scopeName = scopeMatcher.group(1);
                    if (RustText.toAsciiLowercase(scopeName).equals("none")) {
                        continue;
                    }
                    String body = functionBodyAfterAttribute(content, scopeMatcher.start());
                    java.util.SortedSet<String> entry =
                            scopes.computeIfAbsent(scopeName, key -> new TreeSet<>(RustOrdering.STRING_ORDER));
                    Matcher projectMatcher = PROJECT_CALL.matcher(body);
                    while (projectMatcher.find()) {
                        entry.add(projectMatcher.group(1));
                    }
                }
            }
        }
        return scopes;
    }

    private static String functionBodyAfterAttribute(String content, int attributeStart) {
        String rest = content.substring(attributeStart);
        int index = rest.indexOf("async fn");
        if (index < 0) {
            return rest;
        }
        String afterFn = rest.substring(index);
        int brace = afterFn.indexOf('{');
        return brace < 0 ? afterFn : afterFn.substring(brace);
    }

    private static List<Path> walkRsFiles(Path directory) {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path path : entries) {
                if (Files.isDirectory(path)) {
                    files.addAll(walkRsFiles(path));
                } else if (path.toString().endsWith(".rs")) {
                    files.add(path);
                }
            }
        } catch (IOException exception) {
            throw new AnyhowError(RustIoError.display(exception), null);
        }
        return files;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new AnyhowError(RustIoError.display(exception), null);
        }
    }
}
