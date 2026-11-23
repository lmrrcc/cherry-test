package com.cherrytest.cli.adapters;

import com.cherrytest.common.adapter.AdapterRequest;
import com.cherrytest.common.adapter.AdapterResponse;
import com.cherrytest.common.gitdiff.GitDiff;
import com.cherrytest.common.process.CommandOutput;
import com.cherrytest.common.process.SystemCommand;
import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.common.rustcompat.SerdeJsonWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

public final class Pitest {
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final Set<String> STATUSES = Set.of(
            "KILLED", "SURVIVED", "NO_COVERAGE", "TIMED_OUT", "NON_VIABLE", "MEMORY_ERROR", "RUN_ERROR", "EQUIVALENT");
    private static final Pattern PACKAGE = Pattern.compile(
            "(?m)^\\s*package\\s+([\\p{L}_$][\\p{L}\\p{N}_$]*(?:\\s*\\.\\s*[\\p{L}_$][\\p{L}\\p{N}_$]*)*)\\s*(?:;|$)",
            Pattern.UNICODE_CHARACTER_CLASS);
    private static final String VERSION = "1.30.2";
    private static final long REPORT_LIMIT = 32L * 1024 * 1024;

    private Pitest() {}

    public static AdapterResponse run(AdapterRequest request) {
        try {
            if (!request.phase().equals("mutation")
                    || request.execution() != com.cherrytest.common.adapter.ExecutionMode.LOCAL)
                throw new AnyhowError("PIT requires a local mutation phase");
            if (Files.exists(request.output().resolve("mutation.json"))
                    || Files.exists(request.output().resolve("pit-reports")))
                throw new AnyhowError("PIT output directory already contains mutation results");
            List<String> sources = changedSources(request);
            if (sources.isEmpty()) {
                writeReport(request, "no_changes", sources, List.of());
                return AdapterResponse.empty();
            }
            if (Files.exists(request.output().resolve("build"), LinkOption.NOFOLLOW_LINKS))
                throw new AnyhowError("PIT build directory must be fresh");
            String tool = option(request, "mutation_build_tool", "");
            Map<String, String> classes;
            boolean success;
            if (tool.equals("maven")) {
                Path manifest = request.manifest();
                Path parent = manifest.getParent();
                if (parent == null) throw new AnyhowError("PIT Maven manifest has no parent");
                String program = wrapper(request.cwd(), "mvnw", "mvn");
                List<String> effectiveExecutions = readMavenExecutions(request, program);
                Path temporary = Files.createTempFile(parent, ".cherry-test-pit-", ".xml");
                try {
                    writeMavenPom(request, temporary, List.of(), effectiveExecutions);
                    List<String> args = mavenArguments(request, temporary);
                    args.add("test-compile");
                    if (!ProcessRunner.run(request, program, args, "mutation-compile"))
                        throw new AnyhowError("PIT test compilation failed");
                    classes =
                            classesForSources(request, sources, request.output().resolve("build/classes"));
                    writeMavenPom(request, temporary, new ArrayList<>(classes.keySet()), effectiveExecutions);
                    args = mavenArguments(request, temporary);
                    args.add("org.pitest:pitest-maven:" + VERSION + ":mutationCoverage@cherry-test-pit");
                    success = ProcessRunner.run(request, program, args, "mutation");
                } finally {
                    Files.delete(temporary);
                }
            } else if (tool.equals("gradle")) {
                Path init = request.output().resolve("pitest.init.gradle");
                Path settings = request.output().resolve("pitest-settings.json");
                Files.writeString(init, gradleInit(), StandardCharsets.UTF_8);
                writeGradleSettings(request, settings, List.of());
                String program = wrapper(request.cwd(), "gradlew", "gradle");
                List<String> args = gradleArguments(request, init, settings);
                args.add("testClasses");
                if (!ProcessRunner.run(request, program, args, "mutation-compile"))
                    throw new AnyhowError("PIT test compilation failed");
                classes = classesForSources(request, sources, request.output().resolve("build/classes"));
                writeGradleSettings(request, settings, new ArrayList<>(classes.keySet()));
                args = gradleArguments(request, init, settings);
                args.add("pitest");
                success = ProcessRunner.run(request, program, args, "mutation");
            } else {
                throw new AnyhowError("PIT requires mutation_build_tool maven or gradle");
            }
            List<ObjectNode> mutants = readXmlReport(request.output(), classes);
            if (mutants.isEmpty()) throw new AnyhowError("PIT produced no mutations for changed production sources");
            writeReport(request, "complete", sources, mutants);
            boolean errors = mutants.stream()
                    .anyMatch(mutant -> Set.of("NON_VIABLE", "MEMORY_ERROR", "RUN_ERROR")
                            .contains(mutant.path("status").asString()));
            if (errors) return AdapterResponse.failure("PIT reported mutation execution errors; see mutation report");
            return success
                    ? AdapterResponse.empty()
                    : AdapterResponse.failure("PIT exited with an error; see mutation logs");
        } catch (IOException exception) {
            throw new AnyhowError("PIT: " + exception.getMessage(), exception);
        }
    }

    static List<String> changedSources(AdapterRequest request) throws IOException {
        Path cwd = request.cwd().toRealPath();
        Path manifestDirectory = request.manifest().getParent();
        if (manifestDirectory == null || !manifestDirectory.toRealPath().equals(cwd))
            throw new AnyhowError(
                    "PIT mutation cwd must be the package manifest directory; set mutation.cwd to the directory containing the manifest");
        Path gitRoot =
                Path.of(git(request, "rev-parse", "--show-toplevel").trim()).toRealPath();
        String base = option(request, "mutation_diff_base", "main");
        TreeSet<String> names = new TreeSet<>();
        if (GitDiff.isEmptyTreeBase(base)) {
            addNames(names, git(request, "ls-files", "--full-name", "-z", "--cached"));
        } else {
            String commit = git(request, "rev-parse", "--verify", "--end-of-options", base + "^{commit}")
                    .trim();
            String merge = git(request, "merge-base", commit, "HEAD").trim();
            addNames(
                    names,
                    git(request, "diff", "--no-relative", "--name-only", "-z", "--diff-filter=ACMRT", merge, "--"));
        }
        addNames(
                names,
                git(request, "diff", "--no-relative", "--cached", "--name-only", "-z", "--diff-filter=ACMRT", "--"));
        addNames(names, git(request, "ls-files", "--full-name", "--others", "--exclude-standard", "-z"));
        Path root = request.root().toRealPath();
        if (!cwd.startsWith(root)) throw new AnyhowError("mutation package is outside the workspace");
        TreeSet<String> sources = new TreeSet<>();
        for (String name : names) {
            Path source = gitRoot.resolve(name).normalize();
            if (!source.startsWith(cwd)) continue;
            Path relative = cwd.relativize(source);
            String value = relative.toString().replace('\\', '/');
            if (!(value.startsWith("src/main/java/") && value.endsWith(".java"))
                    && !(value.startsWith("src/main/kotlin/") && value.endsWith(".kt"))) continue;
            if (Files.isSymbolicLink(source)) throw new AnyhowError("changed mutation source is a symlink: " + name);
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) continue;
            Path real = source.toRealPath();
            if (!real.startsWith(root) || !real.startsWith(cwd))
                throw new AnyhowError("mutation source escapes workspace");
            sources.add(root.relativize(real).toString().replace('\\', '/'));
        }
        return List.copyOf(sources);
    }

    private static void addNames(Set<String> names, String output) {
        for (String name : output.split("\u0000", -1)) if (!name.isEmpty()) names.add(name);
    }

    private static String git(AdapterRequest request, String... args) throws IOException {
        CommandOutput output = SystemCommand.of("git")
                .args(args)
                .currentDir(request.cwd())
                .environment(request.env())
                .output();
        if (!output.success())
            throw new AnyhowError("PIT changed-source git command failed: "
                    + output.stderrLossy().trim());
        return output.stdoutUtf8Strict();
    }

    static Map<String, String> classesForSources(AdapterRequest request, List<String> sources, Path directory)
            throws IOException {
        Map<String, List<String>> identities = new TreeMap<>();
        for (String source : sources) {
            Path file = request.root().resolve(source);
            String parsedPackage = sourcePackage(Files.readString(file));
            String packageName = parsedPackage.isEmpty() ? "" : parsedPackage.replace('.', '/') + "/";
            identities
                    .computeIfAbsent(packageName + file.getFileName(), ignored -> new ArrayList<>())
                    .add(source);
        }
        Map<String, String> result = new TreeMap<>();
        List<Path> roots = option(request, "mutation_build_tool", "").equals("gradle")
                        && directory
                                .normalize()
                                .equals(request.output()
                                        .resolve("build/classes")
                                        .normalize())
                ? List.of(directory.resolve("java/main"), directory.resolve("kotlin/main"))
                : List.of(directory);
        Path outputRoot = request.output().toRealPath();
        for (Path classes : roots) {
            if (!Files.isDirectory(classes)) continue;
            Path classRoot = classes.toRealPath();
            if (!classRoot.startsWith(outputRoot)) throw new AnyhowError("compiled classes escape build output");
            try (var paths = Files.walk(classes)) {
                for (Path path : paths.filter(file -> file.toString().endsWith(".class"))
                        .sorted()
                        .toList()) {
                    if (Files.isSymbolicLink(path) || !path.toRealPath().startsWith(classRoot))
                        throw new AnyhowError("compiled class escapes build output");
                    ClassReader reader = new ClassReader(Files.readAllBytes(path));
                    String[] sourceFile = {""};
                    reader.accept(
                            new ClassVisitor(Opcodes.ASM9) {
                                @Override
                                public void visitSource(@Nullable String source, @Nullable String debug) {
                                    sourceFile[0] = source == null ? "" : source;
                                }
                            },
                            ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
                    if (sourceFile[0].isEmpty()) continue;
                    String name = reader.getClassName();
                    int slash = name.lastIndexOf('/');
                    String identity = (slash < 0 ? "" : name.substring(0, slash + 1)) + sourceFile[0];
                    List<String> matches = identities.get(identity);
                    if (matches == null) continue;
                    if (matches.size() != 1) throw new AnyhowError("ambiguous mutation source for " + name);
                    if (name.chars().anyMatch(character -> "*?,[]".indexOf(character) >= 0))
                        throw new AnyhowError("class name cannot be represented as an exact PIT target: " + name);
                    result.put(name.replace('/', '.'), matches.getFirst());
                }
            }
        }
        for (String source : sources)
            if (!result.containsValue(source))
                throw new AnyhowError("changed production source has no compiled classes: " + source);
        return result;
    }

    static String sourcePackage(String source) {
        StringBuilder stripped = new StringBuilder();
        int index = 0;
        int depth = 0;
        while (index < source.length()) {
            if (source.startsWith("/*", index)) {
                depth++;
                index += 2;
            } else if (depth > 0 && source.startsWith("*/", index)) {
                depth--;
                index += 2;
                stripped.append(' ');
            } else if (depth > 0) {
                if (source.charAt(index) == '\n') stripped.append('\n');
                index++;
            } else if (source.startsWith("//", index)) {
                while (index < source.length() && source.charAt(index) != '\n') index++;
            } else if (source.charAt(index) == '"' || source.charAt(index) == '\'') {
                char quote = source.charAt(index);
                boolean triple = quote == '"' && source.startsWith("\"\"\"", index);
                index += triple ? 3 : 1;
                while (index < source.length()) {
                    if (triple && source.startsWith("\"\"\"", index)) {
                        index += 3;
                        break;
                    }
                    if (!triple && source.charAt(index) == quote) {
                        index++;
                        break;
                    }
                    if (!triple && source.charAt(index) == '\\') index++;
                    index++;
                }
                stripped.append(' ');
            } else {
                stripped.append(source.charAt(index));
                index++;
            }
        }
        var matcher = PACKAGE.matcher(stripped);
        return matcher.find() ? matcher.group(1).replaceAll("(?U)\\s+", "") : "";
    }

    static List<ObjectNode> readXmlReport(Path output, Map<String, String> classes) throws IOException {
        Path xml = output.resolve("pit-reports/mutations.xml");
        if (!Files.isRegularFile(xml)) throw new AnyhowError("PIT mutation XML report missing");
        if (!xml.toRealPath().startsWith(output.toRealPath())) throw new AnyhowError("PIT XML escapes adapter output");
        if (Files.size(xml) > REPORT_LIMIT) throw new AnyhowError("PIT XML exceeds 32 MiB");
        return parseXml(Files.readString(xml), classes);
    }

    static List<ObjectNode> parseXml(String content, Map<String, String> classes) {
        Element root = XmlNodes.parse(content);
        if (!XmlNodes.localName(root).equals("mutations")) throw new AnyhowError("expected PIT mutations XML");
        for (Node child = root.getFirstChild(); child != null; child = child.getNextSibling())
            if (child instanceof Element && !XmlNodes.hasTagName(child, "mutation"))
                throw new AnyhowError("unexpected PIT report element");
        List<ObjectNode> mutants = new ArrayList<>();
        for (Element mutation : XmlNodes.children(root, "mutation")) {
            String status = requiredAttribute(mutation, "status");
            String detected = requiredAttribute(mutation, "detected");
            if (!STATUSES.contains(status) || !(detected.equals("true") || detected.equals("false")))
                throw new AnyhowError("invalid PIT mutation status");
            validateDetection(status, detected.equals("true"));
            String className = requiredChild(mutation, "mutatedClass");
            String source = classes.get(className);
            if (source == null)
                throw new AnyhowError("PIT mutated a class outside changed production sources: " + className);
            String filename = requiredChild(mutation, "sourceFile");
            Path expectedFilename = Path.of(source).getFileName();
            if (expectedFilename == null || !expectedFilename.toString().equals(filename))
                throw new AnyhowError("PIT source filename does not match changed source");
            long line;
            try {
                line = Long.parseLong(requiredChild(mutation, "lineNumber"));
            } catch (NumberFormatException error) {
                throw new AnyhowError("invalid PIT line number", error);
            }
            if (line <= 0 || line > 0xffff_ffffL) throw new AnyhowError("invalid PIT line number");
            ObjectNode node = NODES.objectNode();
            node.put("status", status);
            node.put("detected", detected.equals("true"));
            node.put("source", source);
            node.put("line", line);
            node.put("class", className);
            node.put("method", requiredChild(mutation, "mutatedMethod"));
            node.put("mutator", requiredChild(mutation, "mutator"));
            node.put("description", requiredChild(mutation, "description"));
            List<Element> killing = XmlNodes.children(mutation, "killingTest");
            String test = killing.isEmpty() ? null : XmlNodes.text(killing.getFirst());
            if (test == null || test.isBlank()) node.putNull("killing_test");
            else node.put("killing_test", test);
            mutants.add(node);
        }
        return mutants;
    }

    private static void validateDetection(String status, boolean detected) {
        if ((Set.of("KILLED", "EQUIVALENT").contains(status) && !detected)
                || (Set.of("SURVIVED", "NO_COVERAGE").contains(status) && detected))
            throw new AnyhowError("inconsistent PIT detected status");
    }

    private static String requiredAttribute(Element node, String name) {
        String value = XmlNodes.attribute(node, name);
        if (value == null || value.isBlank()) throw new AnyhowError("PIT mutation missing " + name);
        return value;
    }

    private static String requiredChild(Element node, String name) {
        List<Element> children = XmlNodes.children(node, name);
        String value = children.size() == 1 ? XmlNodes.text(children.getFirst()) : null;
        if (value == null || value.isBlank()) throw new AnyhowError("PIT mutation missing " + name);
        return value;
    }

    private static void writeReport(
            AdapterRequest request, String status, List<String> sources, List<ObjectNode> mutants) throws IOException {
        ObjectNode report = NODES.objectNode();
        report.put("tool", "pitest");
        report.put("status", status);
        var files = report.putArray("sources");
        sources.forEach(files::add);
        var results = report.putArray("mutants");
        mutants.forEach(results::add);
        Files.writeString(
                request.output().resolve("mutation.json"), SerdeJsonWriter.pretty(report), StandardCharsets.UTF_8);
    }

    public static JsonNode readReport(Path path) {
        try {
            if (!Files.isRegularFile(path) || Files.size(path) > REPORT_LIMIT)
                throw new AnyhowError("missing or oversized PIT mutation report");
            Path parent = path.getParent();
            if (parent == null || !path.toRealPath().startsWith(parent.toRealPath()))
                throw new AnyhowError("mutation report escapes output directory");
            JsonNode report = Json.parse(Files.readString(path));
            String status = string(report, "status");
            JsonNode sources = Json.index(report, "sources");
            JsonNode mutants = Json.index(report, "mutants");
            if (!string(report, "tool").equals("pitest")
                    || !Set.of("complete", "no_changes").contains(status)
                    || !sources.isArray()
                    || !mutants.isArray()) throw new AnyhowError("invalid PIT mutation report");
            Set<String> files = new TreeSet<>();
            for (JsonNode source : sources) {
                if (!source.isString() || source.stringValue().isBlank())
                    throw new AnyhowError("invalid mutation source");
                String value = source.stringValue();
                Path sourcePath = Path.of(value);
                if (sourcePath.isAbsolute()
                        || !sourcePath.normalize().equals(sourcePath)
                        || sourcePath.normalize().startsWith("..")
                        || !files.add(value)) throw new AnyhowError("invalid mutation source path");
            }
            if (status.equals("no_changes") && (!sources.isEmpty() || !mutants.isEmpty()))
                throw new AnyhowError("no_changes mutation report contains results");
            if (status.equals("complete") && (sources.isEmpty() || mutants.isEmpty()))
                throw new AnyhowError("complete mutation report is empty");
            for (JsonNode mutant : mutants) {
                if (!STATUSES.contains(string(mutant, "status"))
                        || !Json.index(mutant, "detected").isBoolean()
                        || !files.contains(string(mutant, "source"))
                        || !Json.index(mutant, "line").isIntegralNumber()
                        || Json.index(mutant, "line").bigIntegerValue().signum() <= 0
                        || Json.index(mutant, "line").bigIntegerValue().bitLength() > 32)
                    throw new AnyhowError("invalid PIT mutant");
                validateDetection(
                        string(mutant, "status"), Json.index(mutant, "detected").booleanValue());
                for (String field : List.of("class", "method", "mutator", "description"))
                    if (string(mutant, field).isBlank()) throw new AnyhowError("missing PIT mutant " + field);
                JsonNode killing = Json.index(mutant, "killing_test");
                if (!killing.isNull() && !killing.isString()) throw new AnyhowError("invalid PIT killing test");
            }
            return report;
        } catch (IOException exception) {
            throw new AnyhowError("read PIT mutation report: " + exception.getMessage(), exception);
        }
    }

    private static String string(JsonNode node, String name) {
        JsonNode value = Json.index(node, name);
        if (!value.isString()) throw new AnyhowError("missing mutation field " + name);
        return value.stringValue();
    }

    private static String option(AdapterRequest request, String name, String fallback) {
        JsonNode value = request.options().get(name);
        if (value == null) return fallback;
        if (!value.isString()) throw new AnyhowError("PIT option " + name + " must be a string");
        return value.stringValue();
    }

    private static String wrapper(Path cwd, String wrapper, String fallback) {
        for (Path path = cwd; path != null; path = path.getParent())
            if (Files.isRegularFile(path.resolve(wrapper)))
                return path.resolve(wrapper).toString();
        return fallback;
    }

    static List<String> arguments(List<String> input, boolean maven) {
        List<String> result = new ArrayList<>();
        Set<String> withValue = maven
                ? Set.of("-P", "--activate-profiles", "-s", "--settings", "-gs", "--global-settings")
                : Set.of("-I", "--init-script", "-g", "--gradle-user-home");
        for (int index = 0; index < input.size(); index++) {
            String value = input.get(index);
            if (withValue.contains(value)) {
                if (++index >= input.size()) throw new AnyhowError("PIT option is missing its value: " + value);
                result.add(value);
                result.add(input.get(index));
                continue;
            }
            List<String> forbidden = maven
                    ? List.of("-f", "--file", "-pl", "--projects", "-rf", "--resume-from", "-am", "--also-make")
                    : List.of("-p", "--project-dir", "-b", "--build-file", "-c", "--settings-file", "--include-build");
            if (forbidden.stream().anyMatch(value::startsWith))
                throw new AnyhowError("PIT cannot override the selected package: " + value);
            if (List.of(
                            "-Dpit.dryRun",
                            "-Dskip",
                            "-Dmaven.test.skip",
                            "-DtargetClasses",
                            "-DreportsDirectory",
                            "-DoutputFormats",
                            "-DfailWhenNoMutations",
                            "-DcrossModule")
                    .stream()
                    .anyMatch(value::startsWith))
                throw new AnyhowError("PIT cannot override managed mutation settings: " + value);
            if (!value.startsWith("-")) {
                if (Set.of("test-compile", "testClasses", "pitest", "mutationCoverage")
                                .contains(value)
                        || value.endsWith(":mutationCoverage")) continue;
                throw new AnyhowError("PIT accepts build options, not additional goals: " + value);
            }
            result.add(value);
        }
        return result;
    }

    private static List<String> mavenArguments(AdapterRequest request, Path pom) {
        List<String> args = arguments(request.args(), true);
        args.addAll(List.of(
                "-B",
                "-ntp",
                "-N",
                "-f",
                pom.toString(),
                "-Dtestit.buildDirectory=" + request.output().resolve("build")));
        return args;
    }

    static List<String> effectiveMavenExecutions(String content) {
        Element root = XmlNodes.parse(content);
        if (!XmlNodes.hasTagName(root, "project")) throw new AnyhowError("expected effective Maven project");
        TreeSet<String> ids = new TreeSet<>();
        for (Element build : XmlNodes.children(root, "build")) {
            for (Element plugins : XmlNodes.children(build, "plugins")) {
                for (Element plugin : XmlNodes.children(plugins, "plugin")) {
                    if (!isPitPlugin(plugin)) continue;
                    for (Element executions : XmlNodes.children(plugin, "executions")) {
                        for (Element execution : XmlNodes.children(executions, "execution")) {
                            String id = requiredChild(execution, "id").trim();
                            if (id.isEmpty() || id.equals("cherry-test-pit"))
                                throw new AnyhowError(
                                        "Maven PIT execution cherry-test-pit is reserved for mutation runs");
                            ids.add(id);
                        }
                    }
                }
            }
        }
        return List.copyOf(ids);
    }

    static List<String> readMavenExecutions(AdapterRequest request, String program) throws IOException {
        Path output = Files.createTempFile(request.output(), ".pitest-effective-pom-", ".xml");
        try {
            List<String> args = mavenArguments(request, request.manifest());
            args.add("org.apache.maven.plugins:maven-help-plugin:3.5.2:effective-pom");
            args.add("-Doutput=" + output);
            if (!ProcessRunner.run(request, program, args, "mutation-model"))
                throw new AnyhowError("PIT Maven model resolution failed; inspect mutation-model logs");
            if (!output.toRealPath().startsWith(request.output().toRealPath()))
                throw new AnyhowError("effective Maven output escapes adapter output");
            if (Files.size(output) > REPORT_LIMIT) throw new AnyhowError("effective Maven output exceeds 32 MiB");
            return effectiveMavenExecutions(Files.readString(output));
        } finally {
            Files.deleteIfExists(output);
        }
    }

    static void writeMavenPom(
            AdapterRequest request, Path target, List<String> classes, List<String> effectiveExecutions)
            throws IOException {
        Element root = XmlNodes.parse(Files.readString(request.manifest()));
        if (!XmlNodes.hasTagName(root, "project")) throw new AnyhowError("expected Maven project");
        var document = root.getOwnerDocument();
        var allPlugins = root.getElementsByTagNameNS("*", "plugin");
        for (int index = 0; index < allPlugins.getLength(); index++) {
            Element existing = (Element) allPlugins.item(index);
            if (!isPitPlugin(existing)) continue;
            child(child(existing, "configuration"), "skip").setTextContent("true");
            for (Element executions : XmlNodes.children(existing, "executions")) {
                for (Element execution : XmlNodes.children(executions, "execution")) {
                    child(execution, "phase").setTextContent("none");
                    for (Element id : XmlNodes.children(execution, "id")) {
                        if ("cherry-test-pit".equals(XmlNodes.text(id)))
                            throw new AnyhowError("Maven PIT execution cherry-test-pit is reserved for mutation runs");
                    }
                }
            }
        }
        Element build = child(root, "build");
        for (Element node : XmlNodes.children(build, "directory", "outputDirectory", "testOutputDirectory"))
            build.removeChild(node);
        append(build, "directory", request.output().resolve("build").toString());
        append(
                build,
                "outputDirectory",
                request.output().resolve("build/classes").toString());
        append(
                build,
                "testOutputDirectory",
                request.output().resolve("build/test-classes").toString());
        Element plugins = child(build, "plugins");
        Element plugin = XmlNodes.children(plugins, "plugin").stream()
                .filter(Pitest::isPitPlugin)
                .findFirst()
                .orElse(null);
        if (plugin == null) {
            plugin = append(plugins, "plugin", null);
            append(plugin, "groupId", "org.pitest");
            append(plugin, "artifactId", "pitest-maven");
        }
        child(plugin, "version").setTextContent(VERSION);
        child(child(plugin, "configuration"), "skip").setTextContent("true");
        Element dependencies = child(plugin, "dependencies");
        for (Element dependency : XmlNodes.children(dependencies, "dependency")) {
            if (named(dependency, "groupId", "org.pitest") && named(dependency, "artifactId", "pitest-junit5-plugin"))
                dependencies.removeChild(dependency);
        }
        Element dependency = append(dependencies, "dependency", null);
        append(dependency, "groupId", "org.pitest");
        append(dependency, "artifactId", "pitest-junit5-plugin");
        append(dependency, "version", "1.2.3");
        Element executions = child(plugin, "executions");
        for (String id : effectiveExecutions) {
            if (XmlNodes.children(executions, "execution").stream().anyMatch(existing -> named(existing, "id", id)))
                continue;
            Element inherited = append(executions, "execution", null);
            append(inherited, "id", id);
            append(inherited, "phase", "none");
        }
        Element execution = append(executions, "execution", null);
        append(execution, "id", "cherry-test-pit");
        Element configuration = append(execution, "configuration", null);
        Element targets = append(configuration, "targetClasses", null);
        targets.setAttribute("combine.self", "override");
        classes.forEach(name -> append(targets, "param", name));
        Element formats = append(configuration, "outputFormats", null);
        formats.setAttribute("combine.self", "override");
        append(formats, "param", "XML");
        append(formats, "param", "HTML");
        append(
                configuration,
                "reportsDirectory",
                request.output().resolve("pit-reports").toString());
        append(configuration, "timestampedReports", "false");
        append(configuration, "failWhenNoMutations", "true");
        append(configuration, "crossModule", "false");
        append(configuration, "skip", "false");
        append(configuration, "dryRun", "false");
        try {
            TransformerFactory factory = TransformerFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            factory.newTransformer().transform(new DOMSource(document), new StreamResult(target.toFile()));
        } catch (javax.xml.transform.TransformerException exception) {
            throw new AnyhowError("write PIT Maven configuration", exception);
        }
    }

    private static boolean isPitPlugin(Element node) {
        return named(node, "groupId", "org.pitest") && named(node, "artifactId", "pitest-maven");
    }

    private static boolean named(Element node, String name, String value) {
        return XmlNodes.children(node, name).stream().anyMatch(child -> value.equals(XmlNodes.text(child)));
    }

    private static Element child(Element parent, String name) {
        List<Element> children = XmlNodes.children(parent, name);
        return children.isEmpty() ? append(parent, name, null) : children.getFirst();
    }

    private static Element append(Element parent, String name, @Nullable String text) {
        Element node = parent.getOwnerDocument().createElementNS(parent.getNamespaceURI(), name);
        if (text != null) node.setTextContent(text);
        parent.appendChild(node);
        return node;
    }

    private static List<String> gradleArguments(AdapterRequest request, Path init, Path settings) {
        List<String> args = arguments(request.args(), false);
        args.addAll(List.of(
                "--no-daemon",
                "-I",
                init.toString(),
                "-DcherryTestPitSettings=" + settings,
                "-PtestItBuildDir=" + request.output().resolve("build")));
        return args;
    }

    private static void writeGradleSettings(AdapterRequest request, Path settings, List<String> classes)
            throws IOException {
        ObjectNode node = NODES.objectNode();
        node.put("project", request.cwd().toRealPath().toString());
        node.put("build", request.output().resolve("build").toString());
        node.put("report", request.output().resolve("pit-reports").toString());
        var targets = node.putArray("classes");
        classes.forEach(targets::add);
        Files.writeString(settings, SerdeJsonWriter.compact(node), StandardCharsets.UTF_8);
    }

    private static String gradleInit() {
        return """
                initscript {
                    repositories { mavenCentral(); maven { url = uri('https://plugins.gradle.org/m2/') } }
                    dependencies { classpath 'info.solidsoft.gradle.pitest:gradle-pitest-plugin:1.19.0' }
                }
                def settings = new groovy.json.JsonSlurper().parse(new File(System.getProperty('cherryTestPitSettings')))
                gradle.beforeProject { project ->
                    if (project.projectDir.canonicalPath == settings.project) {
                        project.layout.buildDirectory.set(new File(settings.build))
                        project.afterEvaluate {
                            if (project.extensions.findByName('pitest') == null) {
                                project.pluginManager.apply(info.solidsoft.gradle.pitest.PitestPlugin)
                            }
                            project.configurations.matching { it.name.toLowerCase().contains('pitest') }.configureEach {
                                resolutionStrategy.deactivateDependencyLocking()
                            }
                        }
                    }
                }
                gradle.projectsEvaluated {
                    gradle.rootProject.allprojects { project ->
                        if (project.projectDir.canonicalPath == settings.project) {
                            project.layout.buildDirectory.set(new File(settings.build))
                            def pit = project.extensions.getByName('pitest')
                            if (!pit.targetTests.isPresent()) {
                                pit.targetTests.set(['*'] as Set)
                            }
                            pit.pitestVersion.set('1.30.2')
                            pit.junit5PluginVersion.set('1.2.3')
                            pit.targetClasses.set(settings.classes as Set)
                            pit.outputFormats.set(['XML', 'HTML'] as Set)
                            pit.reportDir.set(new File(settings.report))
                            pit.timestampedReports.set(false)
                            pit.failWhenNoMutations.set(true)
                            project.tasks.named('pitest').configure { enabled = !settings.classes.isEmpty() }
                        } else {
                            project.tasks.matching { it.name == 'pitest' }.configureEach { enabled = false }
                        }
                    }
                }
                """;
    }
}
