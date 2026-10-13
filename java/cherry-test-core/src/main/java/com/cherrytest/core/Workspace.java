package com.cherrytest.core;

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
    private static final Set<String> BOOTED_SCOPES = new LinkedHashSet<>();
    private static final List<Path> BOOTED_COMPOSE_FILES = new ArrayList<>();

    private Workspace() {}

    public static Path workspaceRoot() {
        String root = ProcessEnvironment.get("CHERRY_TEST_WORKSPACE_ROOT");
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

    public static String composeProject() {
        String project = ProcessEnvironment.get("CHERRY_TEST_COMPOSE_PROJECT");
        return project == null ? COMPOSE_PROJECT : project;
    }

    private static boolean skipDocker() {
        String value = ProcessEnvironment.get("CHERRY_TEST_SKIP_DOCKER");
        return value != null
                && (value.equals("1") || RustText.toAsciiLowercase(value).equals("true"));
    }

    public static synchronized Map<String, ServiceEndpoint> bootScope(String scope) {
        if (Scope.isNone(scope)) {
            return loadRegistryFromEnv(null);
        }
        if (skipDocker()) {
            return loadRegistryFromEnv(scope);
        }
        if (BOOTED_SCOPES.contains(scope)) {
            return loadRegistryFromEnv(scope);
        }
        if (!dockerAvailable()) {
            throw new AnyhowError(
                    "docker is not available; install Docker or set scope=none / CHERRY_TEST_SKIP_DOCKER=1");
        }
        Path root = workspaceRoot();
        TestItConfig config = TestItConfig.load(root);
        List<String> projectNames = Scope.resolveProjects(config, scope);
        List<Path> composeFiles = config.composeFilesForProjects(projectNames);
        List<String> selected = new ArrayList<>();
        for (String projectName : projectNames) {
            ProjectConfig project = config.projects().get(projectName);
            if (project == null) {
                continue;
            }
            project.services().forEach((name, service) -> {
                if (Scope.serviceFilter(config, scope, projectName, name)) {
                    selected.add(service.service());
                }
            });
        }
        for (Path file : composeFiles) {
            if (!BOOTED_COMPOSE_FILES.contains(file)) {
                BOOTED_COMPOSE_FILES.add(file);
            }
        }
        ScopeProvisioner provisioner = ScopeProvisioner.selected();
        provisioner.up(root, config, scope, projectNames, composeFiles, selected);
        Map<String, ServiceEndpoint> registry = new LinkedHashMap<>();
        TreeMap<String, ObjectNode> snapshot = new TreeMap<>(RustOrdering.STRING_ORDER);
        for (String projectName : projectNames) {
            ProjectConfig project = config.projects().get(projectName);
            if (project == null) {
                continue;
            }
            for (Map.Entry<String, ServiceConfig> service : project.services().entrySet()) {
                if (!Scope.serviceFilter(config, scope, projectName, service.getKey())) {
                    continue;
                }
                ServiceEndpoint endpoint = resolveEndpoint(
                        provisioner, root, composeFiles, projectName, service.getKey(), service.getValue());
                exportServiceEnv(endpoint);
                ObjectNode entry = JsonNodeFactory.instance.objectNode();
                entry.put("host", endpoint.host());
                entry.put("kind", endpoint.kind());
                entry.put("port", endpoint.port());
                if (endpoint.url() == null) {
                    entry.putNull("url");
                } else {
                    entry.put("url", endpoint.url());
                }
                snapshot.put(projectName + "." + service.getKey(), entry);
                registry.put(projectName + "\u0000" + service.getKey(), endpoint);
            }
        }
        writeEnvJson(root, snapshot);
        BOOTED_SCOPES.add(scope);
        return registry;
    }

    public static synchronized void teardownScopes() {
        if (skipDocker()) {
            BOOTED_SCOPES.clear();
            BOOTED_COMPOSE_FILES.clear();
            return;
        }
        if (BOOTED_COMPOSE_FILES.isEmpty()) {
            return;
        }
        ScopeProvisioner.selected().down(workspaceRoot(), List.copyOf(BOOTED_COMPOSE_FILES));
        BOOTED_SCOPES.clear();
        BOOTED_COMPOSE_FILES.clear();
    }

    private static ServiceEndpoint resolveEndpoint(
            ScopeProvisioner provisioner,
            Path root,
            List<Path> composeFiles,
            String project,
            String serviceName,
            ServiceConfig service) {
        String kind = service.kind() == null ? "http" : service.kind();
        String mapped = provisioner.mappedAddress(root, composeFiles, project, service.service(), service.port());
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

    private static void exportServiceEnv(ServiceEndpoint endpoint) {
        String prefix = "CHERRY_TEST_" + RustText.toUppercase(endpoint.project()) + "_"
                + RustText.toUppercase(endpoint.service());
        ProcessEnvironment.set(prefix + "_HOST", endpoint.host());
        ProcessEnvironment.set(prefix + "_PORT", Integer.toString(endpoint.port()));
        if (endpoint.url() != null) {
            ProcessEnvironment.set(prefix + "_URL", endpoint.url());
        }
    }

    private static void writeEnvJson(Path root, Map<String, ObjectNode> snapshot) {
        String runDir = ProcessEnvironment.get("CHERRY_TEST_RUN_DIR");
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

    private static Map<String, ServiceEndpoint> loadRegistryFromEnv(@Nullable String scope) {
        Path root = workspaceRoot();
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
                String url = ProcessEnvironment.get(prefix + "_URL");
                String host = ProcessEnvironment.get(prefix + "_HOST");
                String portText = ProcessEnvironment.get(prefix + "_PORT");
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
