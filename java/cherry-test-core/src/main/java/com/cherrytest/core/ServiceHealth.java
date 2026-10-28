package com.cherrytest.core;

/**
 * A service's {@code health}: an HTTP path such as {@code /health} for a Compose service, or how an image service
 * shows it is ready.
 */
public sealed interface ServiceHealth {
    /** An HTTP path such as {@code /health}. */
    record HttpPath(String path) implements ServiceHealth {}

    /** How an image service shows it is ready. */
    record Probe(HealthProbe probe) implements ServiceHealth {}
}
