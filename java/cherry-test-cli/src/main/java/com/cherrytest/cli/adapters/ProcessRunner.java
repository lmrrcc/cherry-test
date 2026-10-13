package com.cherrytest.cli.adapters;

import com.cherrytest.cli.nativeos.ExitStatus;
import com.cherrytest.cli.nativeos.Libc;
import com.cherrytest.cli.nativeos.OsError;
import com.cherrytest.common.adapter.AdapterRequest;
import com.cherrytest.common.adapter.ExecutionMode;
import com.cherrytest.common.process.ExecutableLookup;
import com.cherrytest.common.process.ProcessEnvironment;
import com.cherrytest.common.process.SystemCommand;
import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.common.rustcompat.RustIoError;
import com.cherrytest.common.rustcompat.RustPath;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

public final class ProcessRunner {
    private static final AtomicBoolean CANCELLED = new AtomicBoolean(false);
    private static final Path NULL_DEVICE = Path.of("/dev/null");
    private static final Pattern ERRNO = Pattern.compile("error=(\\d+)");
    private static final long POLL_MILLIS = 25;
    private static final long GRACE_POLL_MILLIS = 50;
    private static final long GRACE_SECONDS = 30;

    private ProcessRunner() {}

    public record Spec(
            String program,
            List<String> arguments,
            Map<String, String> environmentOverrides,
            @Nullable Path workingDirectory,
            @Nullable Path stdin) {
        public Spec {
            arguments = List.copyOf(arguments);
            environmentOverrides = new LinkedHashMap<>(environmentOverrides);
        }
    }

    public static boolean cancelled() {
        return CANCELLED.get();
    }

    public static void installCancellation() {
        SignalCancellation.install(List.of("INT", "TERM"), () -> CANCELLED.set(true));
    }

    public static boolean capture(Spec spec, Path prefix, long timeoutSeconds, boolean group) {
        Path parent = prefix.getParent();
        if (parent == null) {
            throw new AnyhowError("log directory");
        }
        createDirectories(parent);
        Path stdout = RustPath.withExtension(prefix, "stdout");
        Path stderr = RustPath.withExtension(prefix, "stderr");
        return group
                ? captureGroup(spec, prefix, stdout, stderr, timeoutSeconds)
                : captureChild(spec, prefix, stdout, stderr, timeoutSeconds);
    }

    private static boolean captureGroup(Spec spec, Path prefix, Path stdout, Path stderr, long timeoutSeconds) {
        Map<String, String> environment = ProcessEnvironment.snapshot();
        environment.putAll(spec.environmentOverrides());
        Path workingDirectory =
                spec.workingDirectory() == null ? Path.of("").toAbsolutePath() : spec.workingDirectory();
        String executable = spec.program().contains("/")
                ? spec.program()
                : ExecutableLookup.resolve(spec.program(), ProcessEnvironment.get("PATH"), null);
        touch(stdout);
        touch(stderr);
        int pid;
        try {
            pid = Libc.spawnInNewGroup(
                    executable,
                    spec.arguments(),
                    environment,
                    workingDirectory,
                    spec.stdin() == null ? NULL_DEVICE : spec.stdin(),
                    stdout,
                    stderr);
        } catch (AnyhowError error) {
            throw AnyhowError.context("start test process `" + spec.program() + "`", error);
        }
        long start = System.nanoTime();
        while (true) {
            ExitStatus status = Libc.tryWait(pid);
            if (!status.running()) {
                return status.success();
            }
            if (cancelled() || elapsedSeconds(start) >= timeoutSeconds) {
                signalGroup("-TERM", pid);
                long grace = System.nanoTime();
                while (Libc.tryWait(pid).running() && elapsedSeconds(grace) < GRACE_SECONDS) {
                    sleep(GRACE_POLL_MILLIS);
                }
                ExitStatus after = Libc.tryWait(pid);
                if (after.running()) {
                    signalGroup("-KILL", pid);
                    ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
                    while (Libc.tryWait(pid).running()) {
                        sleep(GRACE_POLL_MILLIS);
                    }
                }
                throw new AnyhowError("test process cancelled or exceeded " + timeoutSeconds + "s; see " + prefix);
            }
            sleep(POLL_MILLIS);
        }
    }

    private static boolean captureChild(Spec spec, Path prefix, Path stdout, Path stderr, long timeoutSeconds) {
        ProcessBuilder builder = new ProcessBuilder();
        Map<String, String> environment = builder.environment();
        ProcessEnvironment.applyTo(environment);
        environment.putAll(spec.environmentOverrides());
        String executable = spec.program().contains("/")
                ? spec.program()
                : ExecutableLookup.resolve(spec.program(), environment.get("PATH"), spec.workingDirectory());
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.addAll(spec.arguments());
        builder.command(command);
        if (spec.workingDirectory() != null) {
            builder.directory(spec.workingDirectory().toFile());
        }
        builder.redirectInput(
                spec.stdin() == null ? NULL_DEVICE.toFile() : spec.stdin().toFile());
        builder.redirectOutput(stdout.toFile());
        builder.redirectError(stderr.toFile());
        Process process;
        try {
            process = builder.start();
        } catch (IOException exception) {
            throw AnyhowError.context("start test process `" + spec.program() + "`", spawnError(exception));
        }
        long start = System.nanoTime();
        while (true) {
            if (!process.isAlive()) {
                return exitValue(process) == 0;
            }
            if (cancelled() || elapsedSeconds(start) >= timeoutSeconds) {
                long grace = System.nanoTime();
                while (process.isAlive() && elapsedSeconds(grace) < GRACE_SECONDS) {
                    sleep(GRACE_POLL_MILLIS);
                }
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
                waitFor(process);
                throw new AnyhowError("test process cancelled or exceeded " + timeoutSeconds + "s; see " + prefix);
            }
            sleep(POLL_MILLIS);
        }
    }

    private static AnyhowError spawnError(IOException exception) {
        String message = exception.getMessage() == null ? "" : exception.getMessage();
        Matcher matcher = ERRNO.matcher(message);
        if (matcher.find()) {
            return new AnyhowError(OsError.describe(Integer.parseInt(matcher.group(1))));
        }
        return new AnyhowError(RustIoError.display(exception));
    }

    private static void signalGroup(String signal, int pid) {
        try {
            SystemCommand.of("kill").args(signal, "--", "-" + pid).status();
        } catch (IOException exception) {
            throw new AnyhowError(RustIoError.display(exception), null);
        }
    }

    private static int exitValue(Process process) {
        return process.exitValue();
    }

    private static void waitFor(Process process) {
        try {
            process.waitFor();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static double elapsedSeconds(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000_000.0;
    }

    private static void sleep(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static void createDirectories(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException exception) {
            throw new AnyhowError(RustIoError.display(exception), null);
        }
    }

    private static void touch(Path file) {
        try {
            Files.write(file, new byte[0]);
        } catch (IOException exception) {
            throw new AnyhowError(RustIoError.display(exception), null);
        }
    }

    public static Path mountRoot(AdapterRequest request) {
        Path current = request.root();
        while (current != null) {
            if (Files.isDirectory(current.resolve(".git"))) {
                return current;
            }
            current = current.getParent();
        }
        return request.root();
    }

    public static String mapped(AdapterRequest request, String value) {
        if (request.execution() == ExecutionMode.CONTAINER) {
            return value.replace(mountRoot(request).toString(), "/workspace");
        }
        return value;
    }

    public static boolean run(AdapterRequest request, String program, List<String> arguments, String label) {
        Spec spec;
        if (request.execution() == ExecutionMode.CONTAINER) {
            String image = request.options().containsKey("image")
                            && request.options().get("image").isString()
                    ? request.options().get("image").stringValue()
                    : null;
            if (image == null) {
                throw new AnyhowError("container execution requires options.image");
            }
            List<String> docker = new ArrayList<>(
                    List.of("run", "--rm", "--init", "--add-host", "host.docker.internal:host-gateway"));
            String runId = ProcessEnvironment.get("CHERRY_TEST_RUN_ID");
            docker.add("--label");
            docker.add("cherry-test.run=" + (runId == null ? "" : runId));
            docker.add("-v");
            docker.add(mountRoot(request) + ":/workspace");
            docker.add("-w");
            docker.add(mapped(request, request.cwd().toString()));
            for (String key : ProcessEnvironment.snapshot().keySet()) {
                if (key.startsWith("CHERRY_TEST_AUTH_")) {
                    docker.add("-e");
                    docker.add(key);
                }
            }
            for (Map.Entry<String, String> entry : request.env().entrySet()) {
                String value = mapped(request, entry.getValue())
                        .replace("127.0.0.1:", "host.docker.internal:")
                        .replace("localhost:", "host.docker.internal:");
                docker.add("-e");
                docker.add(entry.getKey() + "=" + value);
            }
            if (image.startsWith("maven:")) {
                docker.add("-v");
                docker.add("cherry-test-polyglot-maven:/root/.m2");
            }
            if (image.startsWith("gradle:")) {
                docker.add("-v");
                docker.add("cherry-test-polyglot-gradle:/home/gradle/.gradle");
            }
            if (image.startsWith("cherry-test-rust:")) {
                docker.add("-v");
                docker.add("cherry-test-polyglot-cargo:/usr/local/cargo/registry");
            }
            docker.add(image);
            docker.add(mapped(request, program));
            for (String argument : arguments) {
                docker.add(mapped(request, argument));
            }
            spec = new Spec("docker", docker, Map.of(), null, null);
        } else {
            spec = new Spec(program, arguments, request.env(), request.cwd(), null);
        }
        return capture(spec, request.output().resolve(label), 1800, false);
    }

    public static List<String> strings(String... values) {
        return new ArrayList<>(List.of(values));
    }

    static File file(Path path) {
        return path.toFile();
    }
}
