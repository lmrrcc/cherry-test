package com.cherrytest.core;

import com.cherrytest.common.process.ProcessEnvironment;
import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.common.rustcompat.RustText;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.HealthState;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.ComposeContainer;

final class TestcontainersProvisioner implements ScopeProvisioner {
    static final TestcontainersProvisioner INSTANCE = new TestcontainersProvisioner();
    private static final Duration READY_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration POLL = Duration.ofMillis(500);
    private static final String PROJECT_LABEL = "com.docker.compose.project";
    private static final String SERVICE_LABEL = "com.docker.compose.service";
    private final Map<String, Environment> environments = new LinkedHashMap<>();

    private record Environment(ComposeContainer compose, String composeProject) {}

    private TestcontainersProvisioner() {}

    @Override
    public synchronized void up(
            Path root,
            TestItConfig config,
            String scope,
            List<String> projectNames,
            List<Path> composeFiles,
            List<String> services) {
        for (String projectName : projectNames) {
            ProjectConfig project = config.project(projectName);
            if (project.compose() == null || environments.containsKey(projectName)) {
                continue;
            }
            Path composeFile = root.resolve(project.compose());
            List<String> projectServices = project.services().values().stream()
                    .map(ServiceConfig::service)
                    .distinct()
                    .toList();
            String identifier = identifier(projectName);
            ComposeContainer compose = new ComposeContainer(identifier, List.of(composeFile.toFile()))
                    .withBuild(true)
                    .withRemoveVolumes(true)
                    .withServices(projectServices.toArray(String[]::new));
            try {
                compose.start();
            } catch (RuntimeException exception) {
                throw AnyhowError.context("testcontainers compose up failed for scope `" + scope + "`", exception);
            }
            String composeProject = waitUntilReady(projectServices, identifier);
            environments.put(projectName, new Environment(compose, composeProject));
            ProcessEnvironment.set("CHERRY_TEST_COMPOSE_PROJECT", composeProject);
            ProcessEnvironment.set(
                    "CHERRY_TEST_" + RustText.toUppercase(projectName) + "_COMPOSE_PROJECT", composeProject);
        }
        ProcessEnvironment.setForChildren("CHERRY_TEST_SKIP_DOCKER", "1");
    }

    private static String identifier(String projectName) {
        String run = ProcessEnvironment.get("CHERRY_TEST_RUN_ID");
        String unique =
                run == null || run.isEmpty() ? UUID.randomUUID().toString().substring(0, 8) : run;
        return "cherry-test-" + unique + "-" + projectName + "-";
    }

    private static String waitUntilReady(List<String> services, String identifier) {
        DockerClient client = DockerClientFactory.instance().client();
        Instant deadline = Instant.now().plus(READY_TIMEOUT);
        String composeProject = "";
        for (String service : services) {
            Container container = containerOf(client, identifier, service, deadline);
            composeProject = container.getLabels().getOrDefault(PROJECT_LABEL, "");
            awaitHealthy(client, container.getId(), service, deadline);
        }
        return composeProject;
    }

    private static Container containerOf(DockerClient client, String identifier, String service, Instant deadline) {
        while (true) {
            for (Container container : client.listContainersCmd()
                    .withLabelFilter(Map.of(SERVICE_LABEL, service))
                    .exec()) {
                if (container
                        .getLabels()
                        .getOrDefault(PROJECT_LABEL, "")
                        .startsWith(identifier.toLowerCase(java.util.Locale.ROOT))) {
                    return container;
                }
            }
            pause(service, deadline);
        }
    }

    private static void awaitHealthy(DockerClient client, String containerId, String service, Instant deadline) {
        while (true) {
            InspectContainerResponse.ContainerState state =
                    client.inspectContainerCmd(containerId).exec().getState();
            HealthState health = state.getHealth();
            if (health == null && Boolean.TRUE.equals(state.getRunning())) {
                return;
            }
            if (health != null && "healthy".equals(health.getStatus())) {
                return;
            }
            if (health != null && "unhealthy".equals(health.getStatus())) {
                throw new AnyhowError("container for service `" + service + "` is unhealthy");
            }
            pause(service, deadline);
        }
    }

    private static void pause(String service, Instant deadline) {
        if (Instant.now().isAfter(deadline)) {
            throw new AnyhowError("service `" + service + "` was not ready within " + READY_TIMEOUT.toSeconds() + "s");
        }
        try {
            Thread.sleep(POLL);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AnyhowError("interrupted while waiting for service `" + service + "`", exception);
        }
    }

    @Override
    public synchronized String mappedAddress(
            Path root, List<Path> composeFiles, String project, String service, int port) {
        Environment environment = environments.get(project);
        if (environment == null) {
            throw new AnyhowError("no testcontainers environment for project `" + project + "`");
        }
        DockerClient client = DockerClientFactory.instance().client();
        for (Container container : client.listContainersCmd()
                .withLabelFilter(Map.of(PROJECT_LABEL, environment.composeProject(), SERVICE_LABEL, service))
                .exec()) {
            Ports.Binding binding =
                    binding(client.inspectContainerCmd(container.getId()).exec(), port);
            if (binding != null) {
                String host = DockerClientFactory.instance().dockerHostIpAddress();
                return (host.equals("localhost") ? "127.0.0.1" : host) + ":" + binding.getHostPortSpec();
            }
        }
        throw new AnyhowError("docker compose port " + service + " " + port + " failed: port is not published");
    }

    private static Ports.@Nullable Binding binding(InspectContainerResponse container, int port) {
        Ports.Binding[] bindings =
                container.getNetworkSettings().getPorts().getBindings().get(new ExposedPort(port));
        return bindings == null || bindings.length == 0 ? null : bindings[0];
    }

    @Override
    public synchronized void down(Path root, List<Path> composeFiles) {
        environments.values().forEach(environment -> environment.compose().stop());
        environments.clear();
    }
}
