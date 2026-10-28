package com.cherrytest.core;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * {@code health} of an image service: run {@code exec} in the service container until it succeeds, or wait for a
 * socket on the port. {@code timeout} is in seconds.
 */
public record HealthProbe(
        @Nullable List<String> exec,
        @Nullable Boolean tcp,
        @Nullable Long timeout) {
    public static final List<String> FIELDS = List.of("exec", "tcp", "timeout");

    public HealthProbe {
        exec = exec == null ? null : List.copyOf(exec);
    }
}
