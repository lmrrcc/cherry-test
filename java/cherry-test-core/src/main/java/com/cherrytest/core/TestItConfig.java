package com.cherrytest.core;

import com.cherrytest.common.adapter.AdapterConfig;
import com.cherrytest.common.adapter.ExecutionMode;
import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.common.rustcompat.RustIoError;
import com.cherrytest.common.rustcompat.RustOrdering;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

public final class TestItConfig {
    private static final List<String> CONFIG_CANDIDATES =
            List.of("cherry-test.yaml", "cherry-test.yml", "cherry-test.toml");
    private final SortedMap<String, AdapterConfig> adapters;
    private final Path workspaceRoot;
    private final Path configPath;
    private Defaults defaults;
    private final SortedMap<String, AuthProfileConfig> auth;
    private final SortedMap<String, ProjectConfig> projects;
    private final SortedMap<String, ScopeConfig> scopes;

    public TestItConfig(
            SortedMap<String, AdapterConfig> adapters,
            Path workspaceRoot,
            Path configPath,
            Defaults defaults,
            SortedMap<String, AuthProfileConfig> auth,
            SortedMap<String, ProjectConfig> projects,
            SortedMap<String, ScopeConfig> scopes) {
        this.adapters = sorted(adapters);
        this.workspaceRoot = workspaceRoot;
        this.configPath = configPath;
        this.defaults = defaults;
        this.auth = sorted(auth);
        this.projects = sorted(projects);
        this.scopes = sorted(scopes);
    }

    public static TestItConfig empty(Path workspaceRoot) {
        return new TestItConfig(
                new TreeMap<>(),
                workspaceRoot,
                Path.of(""),
                Defaults.standard(),
                new TreeMap<>(),
                new TreeMap<>(),
                new TreeMap<>());
    }

    public SortedMap<String, AdapterConfig> adapters() {
        return adapters;
    }

    public Path workspaceRoot() {
        return workspaceRoot;
    }

    public Path configPath() {
        return configPath;
    }

    public Defaults defaults() {
        return defaults;
    }

    public void defaults(Defaults replacement) {
        defaults = replacement;
    }

    public SortedMap<String, AuthProfileConfig> auth() {
        return auth;
    }

    public SortedMap<String, ProjectConfig> projects() {
        return projects;
    }

    public SortedMap<String, ScopeConfig> scopes() {
        return scopes;
    }

    public TestItConfig withExecution(ExecutionMode execution) {
        defaults = defaults.withExecution(execution);
        for (Map.Entry<String, ProjectConfig> project : projects.entrySet()) {
            SortedMap<String, PackageConfig> packages = new TreeMap<>(RustOrdering.STRING_ORDER);
            project.getValue().packages().forEach((name, config) -> packages.put(name, config.withoutExecution()));
            project.setValue(project.getValue().withPackages(packages));
        }
        return this;
    }

    public static TestItConfig load(Path workspaceRoot) {
        Path path = locateConfig(workspaceRoot);
        if (!Files.exists(path)) {
            return empty(workspaceRoot);
        }
        String contents;
        try {
            contents = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw AnyhowError.context("read " + path, new AnyhowError(RustIoError.display(exception)));
        }
        Map<String, Object> raw;
        try {
            raw = parseRaw(path, contents);
        } catch (RuntimeException exception) {
            throw AnyhowError.context("parse " + path, exception);
        }
        SortedMap<String, ProjectConfig> projects = parseProjects(raw);
        mergeLegacyPackages(projects, parsePackages(YamlValues.field(raw, "packages"), "packages."), workspaceRoot);
        for (Map.Entry<String, ProjectConfig> project : projects.entrySet()) {
            if (project.getValue().path() == null) {
                project.setValue(project.getValue().withPath(project.getKey()));
            }
        }
        if (contents.contains("report_dir:") || contents.contains("report_dir =")) {
            System.err.println(
                    "warning: defaults.report_dir is deprecated; use defaults.runs_base and per-run directories under {runs_base}/{run-id}/");
        }
        return new TestItConfig(
                parseAdapters(raw),
                workspaceRoot,
                path,
                parseDefaults(raw),
                parseAuth(raw),
                projects,
                parseScopes(raw));
    }

    static Map<String, Object> parseRaw(Path path, String contents) {
        String fileName = path.getFileName() == null ? "" : path.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String extension = dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        return switch (extension) {
            case "yaml", "yml" -> YamlValues.parseDocument(contents);
            default -> throw new AnyhowError("unsupported config format " + path);
        };
    }

    public AuthProfileConfig authProfile(String name) {
        AuthProfileConfig profile = auth.get(name);
        if (profile == null) {
            throw new AnyhowError("unknown auth profile `" + name + "` in cherry-test.yaml");
        }
        return profile;
    }

    public String defaultScope() {
        return defaults.scope();
    }

    public String diffBaseForProject(String projectId) {
        ProjectConfig project = projects.get(projectId);
        if (project != null && project.diffBase() != null) {
            return project.diffBase();
        }
        return defaults.diffBase();
    }

    public Path configBase() {
        Path parent = configPath.getParent();
        if (parent != null && !parent.toString().isEmpty()) {
            return parent;
        }
        return workspaceRoot;
    }

    public ProjectConfig project(String name) {
        ProjectConfig project = projects.get(name);
        if (project == null) {
            throw new AnyhowError("unknown cherry-test project `" + name + "` in cherry-test.yaml");
        }
        return project;
    }

    public PackageConfig packageConfig(String projectId, String packageName) {
        PackageConfig config = project(projectId).packages().get(packageName);
        if (config == null) {
            throw new AnyhowError("unknown package `" + packageName + "` in cherry-test project `" + projectId + "`");
        }
        return config;
    }

    public Path projectPath(String projectId) {
        ProjectConfig project = project(projectId);
        return workspaceRoot.resolve(project.path() == null ? projectId : project.path());
    }

    public List<String> packageKinds(String packageName, String projectId) {
        ProjectConfig project = projects.get(projectId);
        if (project != null) {
            PackageConfig config = project.packages().get(packageName);
            if (config != null && !config.kinds().isEmpty()) {
                return config.kinds();
            }
            List<String> kinds = new ArrayList<>(List.of("unit", "integration"));
            if (project.functional() != null) {
                kinds.add("functional");
            }
            if (project.ui() != null) {
                kinds.add("ui");
            }
            return kinds;
        }
        return List.of("unit", "integration", "functional", "ui");
    }

    public @Nullable ExternalRunner functionalRunner(String packageName, String projectId) {
        ProjectConfig project = projects.get(projectId);
        if (project == null) {
            return null;
        }
        PackageConfig config = project.packages().get(packageName);
        if (config != null && config.functional() != null) {
            return config.functional();
        }
        return project.functional();
    }

    public @Nullable ExternalRunner uiRunner(String packageName, String projectId) {
        ProjectConfig project = projects.get(projectId);
        if (project == null) {
            return null;
        }
        PackageConfig config = project.packages().get(packageName);
        if (config != null && config.ui() != null) {
            return config.ui();
        }
        return project.ui();
    }

    public @Nullable ExternalRunner unitRunner(String packageName, String projectId) {
        ProjectConfig project = projects.get(projectId);
        if (project == null) {
            return null;
        }
        PackageConfig config = project.packages().get(packageName);
        return config == null ? null : config.unit();
    }

    public PackageRuntime packageRuntime(String packageName, String projectId) {
        ProjectConfig project = projects.get(projectId);
        if (project == null) {
            return PackageRuntime.RUST;
        }
        PackageConfig config = project.packages().get(packageName);
        return config == null ? PackageRuntime.RUST : config.runtime();
    }

    public List<Path> composeFilesForProjects(List<String> projectNames) {
        List<Path> files = new ArrayList<>();
        for (String name : projectNames) {
            ProjectConfig project = project(name);
            if (project.compose() != null) {
                files.add(workspaceRoot.resolve(project.compose()));
            }
        }
        if (files.isEmpty() && !projectNames.isEmpty()) {
            throw new AnyhowError("no compose files for scope projects");
        }
        return files;
    }

    public static boolean configExists(Path workspaceRoot) {
        return CONFIG_CANDIDATES.stream().anyMatch(name -> Files.exists(workspaceRoot.resolve(name)));
    }

    private static Path locateConfig(Path workspaceRoot) {
        for (String name : CONFIG_CANDIDATES) {
            Path path = workspaceRoot.resolve(name);
            if (Files.exists(path)) {
                return path;
            }
        }
        return workspaceRoot.resolve("cherry-test.yaml");
    }

    private static SortedMap<String, AdapterConfig> parseAdapters(Map<String, Object> raw) {
        SortedMap<String, AdapterConfig> adapters = new TreeMap<>(RustOrdering.STRING_ORDER);
        Object value = YamlValues.field(raw, "adapters");
        if (value == null) {
            return adapters;
        }
        YamlValues.mapping(value, "adapters").forEach((name, entry) -> {
            String path = "adapters." + name + ".";
            Map<String, Object> map = YamlValues.mapping(entry, "adapters." + name);
            adapters.put(
                    name,
                    new AdapterConfig(
                            YamlValues.requiredString(map, "command", path),
                            YamlValues.stringList(map, "args", path),
                            YamlValues.unsignedOr(
                                    map, "timeout_secs", path, Long.MAX_VALUE, AdapterConfig.DEFAULT_TIMEOUT_SECS)));
        });
        return adapters;
    }

    static Defaults parseDefaults(Map<String, Object> raw) {
        Object value = YamlValues.field(raw, "defaults");
        Defaults standard = Defaults.standard();
        if (value == null) {
            return standard;
        }
        Map<String, Object> map = YamlValues.mapping(value, "defaults");
        String path = "defaults.";
        String execution = YamlValues.optionalString(map, "execution", path);
        Path runsBase = YamlValues.optionalPath(map, "runs_base", path);
        Path reportDir = YamlValues.optionalPath(map, "report_dir", path);
        Path coverageIgnore = YamlValues.optionalPath(map, "coverage_ignore", path);
        return new Defaults(
                execution == null ? ExecutionMode.LOCAL : ExecutionMode.fromWire(execution),
                YamlValues.boolOr(map, "strict", path, false),
                YamlValues.boolOr(map, "coverage", path, true),
                YamlValues.boolOr(map, "html_report", path, true),
                YamlValues.boolOr(map, "open_report", path, true),
                runsBase == null ? standard.runsBase() : runsBase,
                reportDir == null ? standard.reportDir() : reportDir,
                YamlValues.stringOr(map, "scope", path, standard.scope()),
                YamlValues.stringOr(map, "diff_base", path, standard.diffBase()),
                coverageIgnore == null ? standard.coverageIgnore() : coverageIgnore);
    }

    private static SortedMap<String, AuthProfileConfig> parseAuth(Map<String, Object> raw) {
        SortedMap<String, AuthProfileConfig> profiles = new TreeMap<>(RustOrdering.STRING_ORDER);
        Object value = YamlValues.field(raw, "auth");
        if (value == null) {
            return profiles;
        }
        YamlValues.mapping(value, "auth").forEach((name, entry) -> {
            String path = "auth." + name + ".";
            Map<String, Object> map = YamlValues.mapping(entry, "auth." + name);
            String cachePath = YamlValues.requiredString(map, "cache_path", path);
            profiles.put(
                    name,
                    new AuthProfileConfig(
                            YamlValues.stringOr(map, "profile", path, ""),
                            YamlValues.requiredString(map, "issuer", path),
                            YamlValues.requiredString(map, "client_id", path),
                            YamlValues.requiredString(map, "client_secret", path),
                            YamlValues.requiredString(map, "audience", path),
                            YamlValues.requiredString(map, "email", path),
                            YamlValues.unsignedOr(map, "access_ttl_secs", path, Long.MAX_VALUE, 3600),
                            YamlValues.unsignedOr(map, "refresh_ttl_secs", path, Long.MAX_VALUE, 28800),
                            YamlValues.optionalString(map, "token_url", path),
                            Path.of(cachePath),
                            YamlValues.optionalString(map, "okta_api_project", path),
                            YamlValues.optionalString(map, "okta_api_service", path)));
        });
        return profiles;
    }

    static SortedMap<String, ProjectConfig> parseProjects(Map<String, Object> raw) {
        SortedMap<String, ProjectConfig> projects = new TreeMap<>(RustOrdering.STRING_ORDER);
        Object value = YamlValues.field(raw, "projects");
        if (value == null) {
            return projects;
        }
        YamlValues.mapping(value, "projects").forEach((id, entry) -> {
            String path = "projects." + id + ".";
            Map<String, Object> map = YamlValues.mapping(entry, "projects." + id);
            projects.put(
                    id,
                    new ProjectConfig(
                            YamlValues.optionalString(map, "path", path),
                            YamlValues.optionalString(map, "diff_base", path),
                            YamlValues.optionalString(map, "compose", path),
                            parseServices(YamlValues.field(map, "services"), path + "services."),
                            parsePackages(YamlValues.field(map, "packages"), path + "packages."),
                            parseRunner(YamlValues.field(map, "functional"), path + "functional."),
                            parseRunner(YamlValues.field(map, "ui"), path + "ui.")));
        });
        return projects;
    }

    private static Map<String, ServiceConfig> parseServices(@Nullable Object value, String path) {
        Map<String, ServiceConfig> services = new LinkedHashMap<>();
        if (value == null) {
            return services;
        }
        YamlValues.mapping(value, path).forEach((name, entry) -> {
            String servicePath = path + name + ".";
            Map<String, Object> map = YamlValues.mapping(entry, path + name);
            Object port = YamlValues.field(map, "port");
            if (port == null) {
                throw new AnyhowError(servicePath + ": missing field `port`");
            }
            services.put(
                    name,
                    new ServiceConfig(
                            YamlValues.requiredString(map, "service", servicePath),
                            (int) YamlValues.unsigned(port, servicePath + "port", 0xFFFFL),
                            YamlValues.optionalString(map, "health", servicePath),
                            YamlValues.optionalString(map, "kind", servicePath)));
        });
        return services;
    }

    static SortedMap<String, PackageConfig> parsePackages(@Nullable Object value, String path) {
        SortedMap<String, PackageConfig> packages = new TreeMap<>(RustOrdering.STRING_ORDER);
        if (value == null) {
            return packages;
        }
        YamlValues.mapping(value, path)
                .forEach((name, entry) -> packages.put(name, parsePackage(entry, path + name + ".")));
        return packages;
    }

    private static PackageConfig parsePackage(Object value, String path) {
        Map<String, Object> map = YamlValues.mapping(value, path);
        String execution = YamlValues.optionalString(map, "execution", path);
        String runtime = YamlValues.optionalString(map, "runtime", path);
        return new PackageConfig(
                YamlValues.optionalString(map, "adapter", path),
                execution == null ? null : ExecutionMode.fromWire(execution),
                parseOptions(YamlValues.field(map, "options"), path + "options"),
                parseRunner(YamlValues.field(map, "integration"), path + "integration."),
                parseRunner(YamlValues.field(map, "visual"), path + "visual."),
                runtime == null ? PackageRuntime.RUST : parseRuntime(runtime, path + "runtime"),
                YamlValues.stringList(map, "kinds", path),
                YamlValues.optionalPath(map, "manifest", path),
                parseRunner(YamlValues.field(map, "unit"), path + "unit."),
                parseRunner(YamlValues.field(map, "functional"), path + "functional."),
                parseRunner(YamlValues.field(map, "ui"), path + "ui."),
                parseRunner(YamlValues.field(map, "mutation"), path + "mutation."));
    }

    private static PackageRuntime parseRuntime(String value, String path) {
        return switch (value) {
            case "rust" -> PackageRuntime.RUST;
            case "node" -> PackageRuntime.NODE;
            default -> throw new AnyhowError(path + ": unknown variant `" + value + "`, expected `rust` or `node`");
        };
    }

    private static @Nullable ExternalRunner parseRunner(@Nullable Object value, String path) {
        if (value == null) {
            return null;
        }
        Map<String, Object> map = YamlValues.mapping(value, path);
        return new ExternalRunner(
                YamlValues.optionalString(map, "adapter", path),
                YamlValues.optionalString(map, "scope", path),
                parseOptions(YamlValues.field(map, "options"), path + "options"),
                YamlValues.stringOr(map, "command", path, ""),
                YamlValues.stringList(map, "args", path),
                YamlValues.optionalPath(map, "cwd", path));
    }

    private static SortedMap<String, JsonNode> parseOptions(@Nullable Object value, String path) {
        SortedMap<String, JsonNode> options = new TreeMap<>(RustOrdering.STRING_ORDER);
        if (value == null) {
            return options;
        }
        YamlValues.mapping(value, path).forEach((key, entry) -> options.put(key, YamlValues.json(entry)));
        return options;
    }

    private static SortedMap<String, ScopeConfig> parseScopes(Map<String, Object> raw) {
        SortedMap<String, ScopeConfig> scopes = new TreeMap<>(RustOrdering.STRING_ORDER);
        Object value = YamlValues.field(raw, "scopes");
        if (value == null) {
            return scopes;
        }
        YamlValues.mapping(value, "scopes").forEach((name, entry) -> {
            String path = "scopes." + name + ".";
            Map<String, Object> map = YamlValues.mapping(entry, "scopes." + name);
            if (YamlValues.field(map, "projects") == null) {
                throw new AnyhowError(path + ": missing field `projects`");
            }
            Map<String, List<String>> services = new LinkedHashMap<>();
            Object serviceValue = YamlValues.field(map, "services");
            if (serviceValue != null) {
                YamlValues.mapping(serviceValue, path + "services")
                        .forEach((project, list) -> services.put(
                                project,
                                YamlValues.stringList(Map.of("value", list), "value", path + "services." + project)));
            }
            scopes.put(name, new ScopeConfig(YamlValues.stringList(map, "projects", path), services));
        });
        return scopes;
    }

    private static void mergeLegacyPackages(
            SortedMap<String, ProjectConfig> projects,
            SortedMap<String, PackageConfig> legacyPackages,
            Path workspaceRoot) {
        for (Map.Entry<String, PackageConfig> legacy : legacyPackages.entrySet()) {
            String packageName = legacy.getKey();
            if (projects.values().stream()
                    .anyMatch(project -> project.packages().containsKey(packageName))) {
                continue;
            }
            String projectId = findProjectForPackage(workspaceRoot, projects, packageName);
            if (projectId == null) {
                continue;
            }
            ProjectConfig project = projects.getOrDefault(projectId, ProjectConfig.empty());
            SortedMap<String, PackageConfig> packages = new TreeMap<>(RustOrdering.STRING_ORDER);
            packages.putAll(project.packages());
            packages.put(packageName, legacy.getValue());
            projects.put(projectId, project.withPackages(packages));
        }
    }

    private static @Nullable String findProjectForPackage(
            Path workspaceRoot, SortedMap<String, ProjectConfig> projects, String packageName) {
        for (Map.Entry<String, ProjectConfig> entry : projects.entrySet()) {
            String base = entry.getValue().path() == null
                    ? entry.getKey()
                    : entry.getValue().path();
            if (Files.exists(workspaceRoot.resolve(base).resolve(packageName).resolve("Cargo.toml"))) {
                return entry.getKey();
            }
            if (Files.exists(workspaceRoot.resolve(base).resolve(packageName).resolve("package.json"))) {
                return entry.getKey();
            }
        }
        return null;
    }

    private static <V> SortedMap<String, V> sorted(SortedMap<String, V> source) {
        TreeMap<String, V> copy = new TreeMap<>(RustOrdering.STRING_ORDER);
        copy.putAll(source);
        return copy;
    }
}
