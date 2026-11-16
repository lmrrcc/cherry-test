package com.cherrytest.common.process;

import com.cherrytest.common.rustcompat.AnyhowError;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.jspecify.annotations.Nullable;

public final class SystemCommand {
    private static final File NULL_DEVICE = new File("/dev/null");
    private final String program;
    private final List<String> arguments = new ArrayList<>();
    private final Map<String, String> environment = new LinkedHashMap<>();
    private @Nullable Path workingDirectory;
    private @Nullable Map<String, String> exactEnvironment;

    private SystemCommand(String program) {
        this.program = program;
    }

    public static SystemCommand of(String program) {
        return new SystemCommand(program);
    }

    public SystemCommand arg(String argument) {
        arguments.add(argument);
        return this;
    }

    public SystemCommand args(List<String> values) {
        arguments.addAll(values);
        return this;
    }

    public SystemCommand args(String... values) {
        arguments.addAll(List.of(values));
        return this;
    }

    public SystemCommand env(String name, String value) {
        environment.put(name, value);
        return this;
    }

    /** Starts the program with exactly {@code variables}; the process environment is not inherited. */
    public SystemCommand environment(Map<String, String> variables) {
        exactEnvironment = new LinkedHashMap<>(variables);
        return this;
    }

    public SystemCommand currentDir(Path directory) {
        workingDirectory = directory;
        return this;
    }

    public String program() {
        return program;
    }

    public List<String> arguments() {
        return List.copyOf(arguments);
    }

    public CommandOutput output() throws IOException {
        ProcessBuilder builder = builder();
        builder.redirectInput(NULL_DEVICE);
        Process process = builder.start();
        try (ExecutorService pumps = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<byte[]> stdout = pumps.submit(() -> readAll(process.getInputStream()));
            Future<byte[]> stderr = pumps.submit(() -> readAll(process.getErrorStream()));
            int exitCode = process.waitFor();
            return new CommandOutput(exitCode, stdout.get(), stderr.get());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new AnyhowError("interrupted while waiting for " + program, exception);
        } catch (ExecutionException exception) {
            throw new UncheckedIOException(new IOException(exception.getCause()));
        }
    }

    public int status() throws IOException {
        ProcessBuilder builder = builder();
        builder.inheritIO();
        Process process = builder.start();
        try {
            return process.waitFor();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new AnyhowError("interrupted while waiting for " + program, exception);
        }
    }

    public ProcessBuilder builder() {
        ProcessBuilder builder = new ProcessBuilder();
        Map<String, String> childEnvironment = builder.environment();
        if (exactEnvironment == null) {
            ProcessEnvironment.applyTo(childEnvironment);
        } else {
            childEnvironment.clear();
            childEnvironment.putAll(exactEnvironment);
        }
        childEnvironment.putAll(environment);
        List<String> command = new ArrayList<>();
        command.add(ExecutableLookup.resolve(program, childEnvironment.get("PATH"), workingDirectory));
        command.addAll(arguments);
        builder.command(command);
        if (workingDirectory != null) {
            builder.directory(workingDirectory.toFile());
        }
        return builder;
    }

    private static byte[] readAll(InputStream stream) throws IOException {
        try (stream) {
            return stream.readAllBytes();
        }
    }
}
