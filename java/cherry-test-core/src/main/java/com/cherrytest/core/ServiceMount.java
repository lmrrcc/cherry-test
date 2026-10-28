package com.cherrytest.core;

import java.util.List;

/**
 * A read-only bind mount into an image service: {@code source} relative to the configuration root, {@code target}
 * absolute.
 */
public record ServiceMount(String source, String target) {
    public static final List<String> FIELDS = List.of("source", "target");
}
