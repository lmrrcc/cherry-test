package com.cherrytest.core;

import com.cherrytest.common.container.BindMount;
import com.cherrytest.common.container.ContainerLabels;
import com.cherrytest.common.container.Durations;
import com.cherrytest.common.container.HealthCheck;
import com.cherrytest.common.container.NetworkMode;
import com.cherrytest.common.container.PullPolicy;
import com.cherrytest.common.container.SidecarService;
import com.cherrytest.common.container.SidecarSpec;
import com.cherrytest.common.process.CommandOutput;
import com.cherrytest.common.process.SystemCommand;
import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.common.rustcompat.RustIoError;
import com.cherrytest.common.rustcompat.RustOrdering;
import com.cherrytest.common.rustcompat.RustParse;
import com.cherrytest.common.rustcompat.RustText;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;

/**
 * Image services: containers Cherry Test starts from {@code projects.<id>.services.<name>.image}.
 *
 * <p>A hardened container job gets its own: a holder container owns a private network namespace, every service and
 * the job itself join it, and each service listens on {@code 127.0.0.1:<port>}. In local execution a scope's image
 * services publish their port on a random {@code 127.0.0.1} port.
 */
public final class Sidecars {
    /**
     * Variable the runner sets to the comma-separated scopes it provisioned for a job; a test process reads the
     * endpoints of these scopes from its environment instead of starting anything.
     */
    public static final String SCOPE_PROVISIONED_ENV = "CHERRY_TEST_SCOPE_PROVISIONED";

    private static final Duration PROBE_INTERVAL = Duration.ofMillis(500);

    private Sidecars() {}

    /** The labels every container of a job, or of a scope, carries. */
    public record Labels(String run, String job, long ownerPid) {}

    /** One image service a scope selects, with its configuration. */
    public record ImageService(String project, String service, ServiceConfig config) {}

    /** Whether {@code scope} is listed in {@link #SCOPE_PROVISIONED_ENV}. */
    public static boolean provisioned(Map<String, String> environment, String scope) {
        String value = environment.get(SCOPE_PROVISIONED_ENV);
        if (value == null) {
            return false;
        }
        for (String entry : value.split(",", -1)) {
            if (entry.equals(scope)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Image services the scopes select, each once, in scope order and then in project and service name order.
     */
    public static List<ImageService> imageServices(TestItConfig config, List<String> scopes) {
        List<ImageService> found = new ArrayList<>();
        for (String scope : scopes) {
            for (String projectName : Scope.resolveProjects(config, scope)) {
                ProjectConfig project = config.projects().get(projectName);
                if (project == null) {
                    continue;
                }
                TreeMap<String, ServiceConfig> services = new TreeMap<>(RustOrdering.STRING_ORDER);
                services.putAll(project.services());
                for (Map.Entry<String, ServiceConfig> service : services.entrySet()) {
                    String name = service.getKey();
                    if (!service.getValue().isImage() || !Scope.serviceFilter(config, scope, projectName, name)) {
                        continue;
                    }
                    boolean known = found.stream()
                            .anyMatch(entry -> entry.project().equals(projectName)
                                    && entry.service().equals(name));
                    if (!known) {
                        found.add(new ImageService(projectName, name, service.getValue()));
                    }
                }
            }
        }
        return found;
    }

    /** The resolved settings of one image service; mount sources resolve against {@code root}. */
    public static SidecarService resolveService(
            Path root, String project, String service, ServiceConfig config, String name, PullPolicy defaultPull) {
        List<String> exec = List.of();
        Long timeout = null;
        if (config.health() instanceof ServiceHealth.Probe(HealthProbe probe)) {
            exec = probe.exec() == null ? List.of() : probe.exec();
            timeout = probe.timeout();
        }
        List<BindMount> mounts = new ArrayList<>();
        for (ServiceMount mount : config.mounts()) {
            mounts.add(new BindMount(root.resolve(mount.source()).normalize(), mount.target(), true));
        }
        return new SidecarService(
                project,
                service,
                name,
                config.image() == null ? "" : config.image(),
                config.pull() == null ? defaultPull : config.pull(),
                config.port(),
                config.kind() == null ? "http" : config.kind(),
                config.env(),
                config.tmpfs(),
                mounts,
                new HealthCheck(exec, timeout == null ? Durations.DEFAULT_HEALTH_TIMEOUT_SECS : timeout));
    }

    /**
     * The private network of a hardened job: holder {@code <container>-net}, services
     * {@code <container>-<project>-<service>}; {@code null} without image services.
     */
    public static @Nullable SidecarSpec jobSidecars(
            Path root, String container, List<ImageService> services, PullPolicy defaultPull) {
        if (services.isEmpty()) {
            return null;
        }
        List<SidecarService> resolved = new ArrayList<>();
        for (ImageService service : services) {
            resolved.add(resolveService(
                    root,
                    service.project(),
                    service.service(),
                    service.config(),
                    ContainerLabels.sanitizeName(container + "-" + service.project() + "-" + service.service()),
                    defaultPull));
        }
        return new SidecarSpec(ContainerLabels.sanitizeName(container + "-net"), resolved);
    }

    /** The {@code CHERRY_TEST_{PROJECT}_{SERVICE}_*} variables of a service a job reaches at {@code 127.0.0.1:<port>}. */
    public static List<Map.Entry<String, String>> sidecarEnv(SidecarService service) {
        String url = service.kind().equals("http") ? "http://127.0.0.1:" + service.port() : null;
        return Workspace.serviceEnv(new ServiceEndpoint(
                service.project(), service.service(), service.kind(), url, "127.0.0.1", service.port()));
    }

    /** The prefix {@code CHERRY_TEST_{PROJECT}_{SERVICE}_} of a service's variables. */
    public static String envPrefix(String project, String service) {
        return "CHERRY_TEST_" + RustText.toUppercase(project) + "_" + RustText.toUppercase(service) + "_";
    }

    private static void push(List<String> argv, String option, String value) {
        argv.add(option);
        argv.add(value);
    }

    private static void pushLabels(List<String> argv, Labels labels) {
        push(argv, "--label", ContainerLabels.RUN_LABEL + "=" + labels.run());
        push(argv, "--label", ContainerLabels.JOB_LABEL + "=" + labels.job());
        push(argv, "--label", ContainerLabels.PID_LABEL + "=" + labels.ownerPid());
    }

    /**
     * {@code docker} arguments that start the holder: the job image running {@code sleep infinity} with no
     * capabilities on a read-only root, attached to the job's network mode.
     */
    public static List<String> holderArgv(
            String holder, String image, PullPolicy pull, NetworkMode network, Labels labels) {
        List<String> argv = new ArrayList<>(List.of("run", "-d"));
        push(argv, "--name", holder);
        pushLabels(argv, labels);
        push(argv, "--pull", pull.wireName());
        push(argv, "--network", network.wireName());
        if (network != NetworkMode.NONE) {
            push(argv, "--add-host", "host.docker.internal:host-gateway");
        }
        push(argv, "--cap-drop", "ALL");
        push(argv, "--security-opt", "no-new-privileges");
        argv.add("--read-only");
        push(argv, "--entrypoint", "sleep");
        argv.add(image);
        argv.add("infinity");
        return argv;
    }

    /**
     * {@code docker} arguments that start one image service: in the network of {@code holder}, or with its port
     * published on {@code 127.0.0.1} when {@code holder} is {@code null}.
     */
    public static List<String> serviceArgv(SidecarService service, @Nullable String holder, Labels labels) {
        List<String> argv = new ArrayList<>(List.of("run", "-d"));
        push(argv, "--name", service.name());
        pushLabels(argv, labels);
        push(argv, "--label", ContainerLabels.SERVICE_LABEL + "=" + service.project() + "/" + service.service());
        push(argv, "--pull", service.pull().wireName());
        if (holder != null) {
            push(argv, "--network", "container:" + holder);
        } else {
            push(argv, "-p", "127.0.0.1::" + service.port());
        }
        for (String path : service.tmpfs()) {
            push(argv, "--tmpfs", path);
        }
        for (BindMount mount : service.mounts()) {
            push(argv, "-v", mount.source() + ":" + mount.target() + ":ro");
        }
        service.env().forEach((key, value) -> push(argv, "-e", key + "=" + value));
        argv.add(service.image());
        return argv;
    }

    /**
     * {@code docker} arguments of one health probe: the {@code exec} command inside the service, or a read of the
     * listening sockets from {@code probe}, a container in the same network namespace.
     */
    public static List<String> probeArgv(SidecarService service, String probe) {
        List<String> argv = new ArrayList<>(List.of("exec"));
        if (service.health().exec().isEmpty()) {
            argv.add(probe);
            argv.addAll(List.of("cat", "/proc/net/tcp", "/proc/net/tcp6"));
        } else {
            argv.add(service.name());
            argv.addAll(service.health().exec());
        }
        return argv;
    }

    /** Whether {@code /proc/net/tcp} or {@code /proc/net/tcp6} content shows a socket listening on {@code port}. */
    public static boolean listening(String procNetTcp, int port) {
        String wanted = String.format(Locale.ROOT, "%04X", port);
        for (String line : RustText.lines(procNetTcp)) {
            List<String> fields = RustText.splitWhitespace(line);
            if (fields.size() < 4 || !fields.get(3).equals("0A")) {
                continue;
            }
            String local = fields.get(1);
            int colon = local.lastIndexOf(':');
            if (colon >= 0 && local.substring(colon + 1).equalsIgnoreCase(wanted)) {
                return true;
            }
        }
        return false;
    }

    /** {@code docker} arguments that remove containers with their anonymous volumes. */
    public static List<String> removeArgv(List<String> names) {
        List<String> argv = new ArrayList<>(List.of("rm", "-f", "-v"));
        argv.addAll(names);
        return argv;
    }

    /** Every container a job's private network uses: the holder first, then the services. */
    public static List<String> containerNames(SidecarSpec spec) {
        List<String> names = new ArrayList<>();
        names.add(spec.holder());
        spec.services().forEach(service -> names.add(service.name()));
        return names;
    }

    /** The file a service's {@code docker logs} output is saved to. */
    public static Path logPath(Path logs, SidecarService service) {
        return logs.resolve(
                "sidecar-" + ContainerLabels.sanitizeName(service.project() + "-" + service.service()) + ".log");
    }

    private static CommandOutput docker(Map<String, String> environment, List<String> args) {
        try {
            return SystemCommand.of("docker")
                    .environment(environment)
                    .args(args)
                    .output();
        } catch (IOException exception) {
            throw AnyhowError.context("start `docker`", new AnyhowError(RustIoError.display(exception)));
        }
    }

    private static String stderrLine(CommandOutput output) {
        String stderr = output.stderrLossy();
        String text = RustText.trim(stderr).isEmpty() ? output.stdoutLossy() : stderr;
        List<String> lines = RustText.lines(RustText.trim(text));
        return lines.isEmpty() ? "" : lines.getLast();
    }

    private static void startContainer(Map<String, String> environment, List<String> argv, String what) {
        CommandOutput output = docker(environment, argv);
        if (!output.success()) {
            throw new AnyhowError("could not start " + what + ": " + stderrLine(output));
        }
    }

    private static String describe(SidecarService service) {
        return "image service `" + service.project() + "/" + service.service() + "` (" + service.image() + ")";
    }

    /** Polls {@code service} until its health check passes, it stops, its timeout expires or {@code cancelled} says so. */
    public static void waitHealthy(
            Map<String, String> environment,
            SidecarService service,
            String probe,
            Path logs,
            BooleanSupplier cancelled) {
        String label = describe(service);
        long deadline = System.nanoTime()
                + Duration.ofSeconds(service.health().timeoutSecs()).toNanos();
        while (true) {
            if (cancelled.getAsBoolean()) {
                throw new AnyhowError("cancelled while waiting for " + label);
            }
            CommandOutput output = docker(environment, probeArgv(service, probe));
            boolean healthy = service.health().exec().isEmpty()
                    ? listening(output.stdoutLossy(), service.port())
                    : output.success();
            if (healthy) {
                return;
            }
            CommandOutput state =
                    docker(environment, List.of("inspect", "--format", "{{.State.Status}}", service.name()));
            String status = RustText.trim(state.stdoutLossy());
            if (status.equals("exited") || status.equals("dead") || !state.success()) {
                throw new AnyhowError(label + " stopped before it became healthy; see " + logPath(logs, service));
            }
            if (System.nanoTime() - deadline >= 0) {
                String detail;
                if (service.health().exec().isEmpty()) {
                    detail = "nothing listens on 127.0.0.1:" + service.port();
                } else {
                    String command = String.join(" ", service.health().exec());
                    String last = stderrLine(output);
                    detail = last.isEmpty()
                            ? "`" + command + "` keeps failing"
                            : "`" + command + "` keeps failing: " + last;
                }
                throw new AnyhowError(label + " did not become healthy within "
                        + service.health().timeoutSecs() + "s: " + detail + "; see " + logPath(logs, service));
            }
            try {
                Thread.sleep(PROBE_INTERVAL);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AnyhowError("interrupted while waiting for " + label, exception);
            }
        }
    }

    /** Saves each service's {@code docker logs} output beside the job's other logs; a service that never started is skipped. */
    public static void saveLogs(Map<String, String> environment, List<SidecarService> services, Path logs) {
        for (SidecarService service : services) {
            CommandOutput output;
            try {
                output = SystemCommand.of("docker")
                        .environment(environment)
                        .args("logs", service.name())
                        .output();
            } catch (IOException exception) {
                continue;
            }
            if (!output.success()) {
                continue;
            }
            ByteArrayOutputStream text = new ByteArrayOutputStream();
            text.writeBytes(output.stdout());
            text.writeBytes(output.stderr());
            Path path = logPath(logs, service);
            try {
                Files.createDirectories(logs);
                Files.write(path, text.toByteArray());
            } catch (IOException exception) {
                System.err.println("warning: could not save " + path + ": " + RustIoError.display(exception));
            }
        }
    }

    /** Removes containers and their anonymous volumes; containers that are already gone are not an error. */
    public static void remove(Map<String, String> environment, List<String> names) {
        if (names.isEmpty()) {
            return;
        }
        CommandOutput output = docker(environment, removeArgv(names));
        if (!output.success()) {
            List<String> unexpected = new ArrayList<>();
            for (String line : RustText.lines(output.stderrLossy())) {
                if (!RustText.trim(line).isEmpty() && !line.contains("No such container")) {
                    unexpected.add(line);
                }
            }
            if (!unexpected.isEmpty()) {
                throw new AnyhowError("docker rm failed: " + String.join("; ", unexpected));
            }
        }
    }

    /**
     * Starts a job's private network: the holder, then every service, then waits for each to be healthy. On failure
     * the logs are saved, everything started is removed, and the error explains which service failed.
     */
    public static void startJobNetwork(
            Map<String, String> environment,
            SidecarSpec spec,
            String image,
            PullPolicy pull,
            NetworkMode network,
            Labels labels,
            Path logs,
            BooleanSupplier cancelled) {
        try {
            startContainer(
                    environment,
                    holderArgv(spec.holder(), image, pull, network, labels),
                    "the network holder from `" + image + "`");
            for (SidecarService service : spec.services()) {
                startContainer(environment, serviceArgv(service, spec.holder(), labels), describe(service));
            }
            for (SidecarService service : spec.services()) {
                waitHealthy(environment, service, spec.holder(), logs, cancelled);
            }
        } catch (RuntimeException error) {
            saveLogs(environment, spec.services(), logs);
            try {
                remove(environment, containerNames(spec));
            } catch (RuntimeException cleanup) {
                throw AnyhowError.context("cleanup also failed: " + AnyhowError.alternate(cleanup), error);
            }
            throw error;
        }
    }

    /** Saves the services' logs and removes the job's private network. */
    public static void stopJobNetwork(Map<String, String> environment, SidecarSpec spec, Path logs) {
        saveLogs(environment, spec.services(), logs);
        remove(environment, containerNames(spec));
    }

    /**
     * Starts one image service with its port published on {@code 127.0.0.1} and returns its endpoint. A container
     * left with the same name by an earlier process is removed first.
     */
    public static ServiceEndpoint startPublished(
            Map<String, String> environment, SidecarService service, Labels labels, Path logs) {
        remove(environment, List.of(service.name()));
        try {
            startContainer(environment, serviceArgv(service, null, labels), describe(service));
            waitHealthy(environment, service, service.name(), logs, () -> false);
            CommandOutput output = docker(environment, List.of("port", service.name(), service.port() + "/tcp"));
            if (!output.success()) {
                throw new AnyhowError(
                        "docker port " + service.name() + " " + service.port() + " failed: " + stderrLine(output));
            }
            List<String> lines = RustText.lines(output.stdoutLossy());
            String first = lines.isEmpty() ? "" : RustText.trim(lines.getFirst());
            int colon = first.lastIndexOf(':');
            if (colon < 0) {
                throw new AnyhowError("invalid docker port output: " + first);
            }
            int port;
            try {
                port = RustParse.u16(first.substring(colon + 1));
            } catch (AnyhowError error) {
                throw AnyhowError.context("mapped port", error);
            }
            String host = first.substring(0, colon).replace("0.0.0.0", "127.0.0.1");
            String url = service.kind().equals("http") ? "http://" + host + ":" + port : null;
            return new ServiceEndpoint(service.project(), service.service(), service.kind(), url, host, port);
        } catch (RuntimeException error) {
            saveLogs(environment, List.of(service), logs);
            remove(environment, List.of(service.name()));
            throw error;
        }
    }

    /** Whether {@code value} is a valid environment variable name. */
    public static boolean isEnvName(String value) {
        if (value.isEmpty()) {
            return false;
        }
        char first = value.charAt(0);
        boolean alphabetic = (first >= 'a' && first <= 'z') || (first >= 'A' && first <= 'Z');
        if (!alphabetic && first != '_') {
            return false;
        }
        return value.chars()
                .allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_');
    }
}
