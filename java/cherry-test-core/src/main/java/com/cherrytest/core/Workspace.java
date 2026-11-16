package com.cherrytest.core;

import com.cherrytest.common.container.ContainerLabels;
import com.cherrytest.common.container.PullPolicy;
import com.cherrytest.common.container.SidecarService;
import com.cherrytest.common.process.ProcessEnvironment;
import com.cherrytest.common.process.SystemCommand;
import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.common.rustcompat.RustIoError;
import com.cherrytest.common.rustcompat.RustOrdering;
import com.cherrytest.common.rustcompat.RustText;
import com.cherrytest.common.rustcompat.SerdeJsonWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

public final class Workspace {
    public static final String COMPOSE_PROJECT = "cherry-test";
    private static final ScopeState PROCESS_SCOPES = new ScopeState();

    private Workspace() {}

    /**
     * The scopes one owner has started, the Compose files they used, in start order, and the image service
     * containers it started.
     */
    public static final class ScopeState {
        private final Set<String> booted = new LinkedHashSet<>();
        private final Set<String> published = new LinkedHashSet<>();
        private final List<Path> composeFiles = new ArrayList<>();
        private final List<String> containers = new ArrayList<>();

        public boolean isBooted(String scope) {
            return booted.contains(scope);
        }

        /** Whether the scope's image services run with published ports for this owner. */
        public boolean isPublished(String scope) {
            return published.contains(scope);
        }

        /** Image service containers this owner started and has not removed yet. */
        public List<String> containers() {
            return List.copyOf(containers);
        }

        public List<Path> composeFiles() {
            return List.copyOf(composeFiles);
        }

        private void rememberFiles(List<Path> files) {
            for (Path file : files) {
                if (!composeFiles.contains(file)) {
                    composeFiles.add(file);
                }
            }
        }

        private void clear() {
            booted.clear();
            published.clear();
            composeFiles.clear();
            containers.clear();
        }
    }

    /** Endpoints of one booted scope and the {@code CHERRY_TEST_<PROJECT>_<SERVICE>_*} variables describing them. */
    public record ScopeBoot(Map<String, ServiceEndpoint> registry, List<Map.Entry<String, String>> exports) {
        public ScopeBoot {
            registry = new LinkedHashMap<>(registry);
            exports = List.copyOf(exports);
        }
    }

    public static Path workspaceRoot() {
        return workspaceRoot(ProcessEnvironment.snapshot());
    }

    /** The configuration root named by {@code CHERRY_TEST_WORKSPACE_ROOT} in {@code environment}, or the one found from the working directory. */
    public static Path workspaceRoot(Map<String, String> environment) {
        String root = environment.get("CHERRY_TEST_WORKSPACE_ROOT");
        if (root != null && !root.isEmpty()) {
            Path path = Path.of(root);
            if (Files.isDirectory(path)) {
                return path;
            }
        }
        return RepoRoot.resolveRepoRoot(null);
    }

    public static boolean dockerAvailable() {
        try {
            return SystemCommand.of("docker").args("info").output().success();
        } catch (IOException exception) {
            return false;
        }
    }

    /** Like {@link #dockerAvailable()}, with {@code docker info} started in {@code environment}. */
    public static boolean dockerAvailable(Map<String, String> environment) {
        try {
            return SystemCommand.of("docker")
                    .environment(environment)
                    .args("info")
                    .output()
                    .success();
        } catch (IOException exception) {
            return false;
        }
    }

    public static String composeProject() {
        return composeProject(ProcessEnvironment.snapshot());
    }

    static String composeProject(Map<String, String> environment) {
        String project = environment.get("CHERRY_TEST_COMPOSE_PROJECT");
        return project == null ? COMPOSE_PROJECT : project;
    }

    private static boolean skipDocker(Map<String, String> environment) {
        String value = environment.get("CHERRY_TEST_SKIP_DOCKER");
        return value != null
                && (value.equals("1") || RustText.toAsciiLowercase(value).equals("true"));
    }

    /** Boots {@code scope} for the current process and exports its endpoints to the process environment. */
    public static synchronized Map<String, ServiceEndpoint> bootScope(String scope) {
        ScopeBoot boot = bootScope(scope, PROCESS_SCOPES, ProcessEnvironment.snapshot());
        boot.exports().forEach(variable -> ProcessEnvironment.set(variable.getKey(), variable.getValue()));
        return boot.registry();
    }

    /**
     * Boots {@code scope} once for the owner of {@code state}; Docker runs in {@code environment} and nothing global
     * changes. Compose services start through the selected provisioner; image services start with their ports
     * published on {@code 127.0.0.1}. Each port lookup sees the variables of the services resolved before it. A
     * scope the runner lists in {@code CHERRY_TEST_SCOPE_PROVISIONED} is read from {@code environment} without
     * starting anything.
     */
    public static ScopeBoot bootScope(String scope, ScopeState state, Map<String, String> environment) {
        return boot(scope, state, environment, true);
    }

    /**
     * Like {@link #bootScope(String, ScopeState, Map)} for a job that runs the scope's image services in its own
     * network: only the Compose services start.
     */
    public static ScopeBoot bootCompose(String scope, ScopeState state, Map<String, String> environment) {
        return boot(scope, state, environment, false);
    }

    private static ScopeBoot boot(String scope, ScopeState state, Map<String, String> environment, boolean images) {
        if (Scope.isNone(scope)) {
            return new ScopeBoot(loadRegistryFrom(environment, null), List.of());
        }
        boolean done = state.isBooted(scope) && (!images || state.isPublished(scope));
        if (skipDocker(environment) || Sidecars.provisioned(environment, scope) || done) {
            return new ScopeBoot(loadRegistryFrom(environment, scope), List.of());
        }
        if (!dockerAvailable(environment)) {
            throw new AnyhowError(
                    "docker is not available; install Docker or set scope=none / CHERRY_TEST_SKIP_DOCKER=1");
        }
        Path root = workspaceRoot(environment);
        TestItConfig config = TestItConfig.load(root);
        List<String> projectNames = Scope.resolveProjects(config, scope);
        List<Sidecars.ImageService> imageServices = Sidecars.imageServices(config, List.of(scope));
        Map<String, ServiceEndpoint> registry = new LinkedHashMap<>();
        List<Map.Entry<String, String>> exports = new ArrayList<>();
        Map<String, String> portEnvironment = new LinkedHashMap<>(environment);
        TreeMap<String, ObjectNode> snapshot = new TreeMap<>(RustOrdering.STRING_ORDER);
        boolean composeRan = false;
        if (!state.isBooted(scope)) {
            List<String> selected = new ArrayList<>();
            for (String projectName : projectNames) {
                ProjectConfig project = config.projects().get(projectName);
                if (project == null) {
                    continue;
                }
                project.services().forEach((name, service) -> {
                    if (!service.isImage() && Scope.serviceFilter(config, scope, projectName, name)) {
                        selected.add(service.service());
                    }
                });
            }
            if (!selected.isEmpty() || imageServices.isEmpty()) {
                List<Path> composeFiles = config.composeFilesForProjects(projectNames);
                state.rememberFiles(composeFiles);
                ScopeProvisioner provisioner = ScopeProvisioner.selected(environment);
                provisioner.up(root, config, scope, projectNames, composeFiles, selected, environment);
                for (String projectName : projectNames) {
                    ProjectConfig project = config.projects().get(projectName);
                    if (project == null) {
                        continue;
                    }
                    for (Map.Entry<String, ServiceConfig> service :
                            project.services().entrySet()) {
                        if (service.getValue().isImage()
                                || !Scope.serviceFilter(config, scope, projectName, service.getKey())) {
                            continue;
                        }
                        ServiceEndpoint endpoint = resolveEndpoint(
                                provisioner,
                                root,
                                composeFiles,
                                projectName,
                                service.getKey(),
                                service.getValue(),
                                portEnvironment);
                        for (Map.Entry<String, String> variable : serviceEnv(endpoint)) {
                            portEnvironment.put(variable.getKey(), variable.getValue());
                            exports.add(variable);
                        }
                        snapshot.put(projectName + "." + service.getKey(), snapshotEntry(endpoint));
                        registry.put(projectName + "\u0000" + service.getKey(), endpoint);
                    }
                }
                state.rememberFiles(composeFiles);
                composeRan = true;
            }
            state.booted.add(scope);
        }
        boolean imagesRan = false;
        if (images && !state.isPublished(scope)) {
            String run = environment.get("CHERRY_TEST_RUN_ID");
            Sidecars.Labels labels = new Sidecars.Labels(
                    run == null ? composeProject(environment) : run, "scope/" + scope, ownerPid(environment));
            Path logs = servicesLogDir(root, environment);
            for (Sidecars.ImageService service : imageServices) {
                SidecarService resolved = Sidecars.resolveService(
                        root,
                        service.project(),
                        service.service(),
                        service.config(),
                        ContainerLabels.sanitizeName("ct-" + composeProject(environment) + "-" + service.project() + "-"
                                + service.service()),
                        PullPolicy.NEVER);
                ServiceEndpoint endpoint = Sidecars.startPublished(environment, resolved, labels, logs);
                state.containers.add(resolved.name());
                for (Map.Entry<String, String> variable : serviceEnv(endpoint)) {
                    portEnvironment.put(variable.getKey(), variable.getValue());
                    exports.add(variable);
                }
                snapshot.put(service.project() + "." + service.service(), snapshotEntry(endpoint));
                registry.put(service.project() + "\u0000" + service.service(), endpoint);
                imagesRan = true;
            }
            state.published.add(scope);
        }
        if (composeRan || imagesRan) {
            writeEnvJson(root, environment, snapshot);
        }
        if (!composeRan) {
            registry = loadRegistryFrom(portEnvironment, scope);
        }
        return new ScopeBoot(registry, exports);
    }

    private static ObjectNode snapshotEntry(ServiceEndpoint endpoint) {
        ObjectNode entry = JsonNodeFactory.instance.objectNode();
        entry.put("host", endpoint.host());
        entry.put("kind", endpoint.kind());
        entry.put("port", endpoint.port());
        if (endpoint.url() == null) {
            entry.putNull("url");
        } else {
            entry.put("url", endpoint.url());
        }
        return entry;
    }

    private static long ownerPid(Map<String, String> environment) {
        String value = environment.get(ContainerLabels.OWNER_PID_ENV);
        if (value != null) {
            try {
                return Integer.toUnsignedLong(Integer.parseUnsignedInt(value));
            } catch (NumberFormatException ignored) {
                return ProcessHandle.current().pid();
            }
        }
        return ProcessHandle.current().pid();
    }

    /** Where the logs of an image service that failed to start are saved: {@code services/} in the run directory. */
    private static Path servicesLogDir(Path root, Map<String, String> environment) {
        String runDir = environment.get("CHERRY_TEST_RUN_DIR");
        return runDir == null
                ? root.resolve("target/cherry-test/services")
                : Path.of(runDir).resolve("services");
    }

    /** Stops every scope the current process booted through {@link #bootScope(String)}. */
    public static synchronized void teardownScopes() {
        teardownScopes(PROCESS_SCOPES, ProcessEnvironment.snapshot());
    }

    /**
     * Stops every scope recorded in {@code state}: removes its image service containers, then runs one teardown
     * started in {@code environment}.
     */
    public static void teardownScopes(ScopeState state, Map<String, String> environment) {
        if (skipDocker(environment)) {
            state.clear();
            return;
        }
        List<String> containers = List.copyOf(state.containers);
        state.containers.clear();
        RuntimeException removed = null;
        try {
            Sidecars.remove(environment, containers);
        } catch (RuntimeException error) {
            removed = error;
        }
        if (state.composeFiles.isEmpty()) {
            state.clear();
            if (removed != null) {
                throw removed;
            }
            return;
        }
        ScopeProvisioner.selected(environment)
                .down(workspaceRoot(environment), List.copyOf(state.composeFiles), environment);
        state.clear();
        if (removed != null) {
            throw removed;
        }
    }

    /** The {@code _HOST}, {@code _PORT} and {@code _URL} variables of one endpoint, in that order. */
    public static List<Map.Entry<String, String>> serviceEnv(ServiceEndpoint endpoint) {
        String prefix = "CHERRY_TEST_" + RustText.toUppercase(endpoint.project()) + "_"
                + RustText.toUppercase(endpoint.service());
        List<Map.Entry<String, String>> variables = new ArrayList<>();
        variables.add(Map.entry(prefix + "_HOST", endpoint.host()));
        variables.add(Map.entry(prefix + "_PORT", Integer.toString(endpoint.port())));
        if (endpoint.url() != null) {
            variables.add(Map.entry(prefix + "_URL", endpoint.url()));
        }
        return variables;
    }

    private static ServiceEndpoint resolveEndpoint(
            ScopeProvisioner provisioner,
            Path root,
            List<Path> composeFiles,
            String project,
            String serviceName,
            ServiceConfig service,
            Map<String, String> environment) {
        String kind = service.kind() == null ? "http" : service.kind();
        String mapped =
                provisioner.mappedAddress(root, composeFiles, project, service.service(), service.port(), environment);
        int colon = mapped.lastIndexOf(':');
        if (colon < 0) {
            throw new AnyhowError("invalid docker compose port output: " + mapped);
        }
        String host = mapped.substring(0, colon).replace("0.0.0.0", "127.0.0.1");
        int mappedPort;
        try {
            mappedPort = Integer.parseInt(mapped.substring(colon + 1));
            if (mappedPort < 0 || mappedPort > 0xFFFF) {
                throw new NumberFormatException("out of range");
            }
        } catch (NumberFormatException exception) {
            throw AnyhowError.context("mapped port", new AnyhowError("invalid digit found in string"));
        }
        String url = kind.equals("http") ? "http://" + host + ":" + mappedPort : null;
        return new ServiceEndpoint(project, serviceName, kind, url, host, mappedPort);
    }

    private static void writeEnvJson(Path root, Map<String, String> environment, Map<String, ObjectNode> snapshot) {
        String runDir = environment.get("CHERRY_TEST_RUN_DIR");
        Path path;
        try {
            if (runDir != null) {
                path = Path.of(runDir).resolve("env.json");
            } else {
                Path directory = root.resolve("target/cherry-test");
                Files.createDirectories(directory);
                path = directory.resolve("env.json");
            }
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            ObjectNode document = JsonNodeFactory.instance.objectNode();
            snapshot.forEach(document::set);
            Files.writeString(path, SerdeJsonWriter.pretty(document), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new AnyhowError(RustIoError.display(exception), null);
        }
    }

    private static Map<String, ServiceEndpoint> loadRegistryFrom(
            Map<String, String> environment, @Nullable String scope) {
        Path root = workspaceRoot(environment);
        TestItConfig config = TestItConfig.load(root);
        List<String> projectNames = scope != null && !Scope.isNone(scope)
                ? Scope.resolveProjects(config, scope)
                : List.copyOf(config.projects().keySet());
        Map<String, ServiceEndpoint> registry = new LinkedHashMap<>();
        for (String projectName : projectNames) {
            ProjectConfig project = config.projects().get(projectName);
            if (project == null) {
                continue;
            }
            for (Map.Entry<String, ServiceConfig> service : project.services().entrySet()) {
                if (scope != null && !Scope.serviceFilter(config, scope, projectName, service.getKey())) {
                    continue;
                }
                String prefix = "CHERRY_TEST_" + RustText.toUppercase(projectName) + "_"
                        + RustText.toUppercase(service.getKey());
                String url = environment.get(prefix + "_URL");
                String host = environment.get(prefix + "_HOST");
                String portText = environment.get(prefix + "_PORT");
                int port = service.getValue().port();
                if (portText != null) {
                    try {
                        port = Integer.parseInt(portText);
                    } catch (NumberFormatException exception) {
                        port = service.getValue().port();
                    }
                }
                String kind = service.getValue().kind() == null
                        ? "http"
                        : service.getValue().kind();
                registry.put(
                        projectName + "\u0000" + service.getKey(),
                        new ServiceEndpoint(
                                projectName, service.getKey(), kind, url, host == null ? "127.0.0.1" : host, port));
            }
        }
        return registry;
    }
}
