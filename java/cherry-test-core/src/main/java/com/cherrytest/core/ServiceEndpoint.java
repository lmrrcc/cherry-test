package com.cherrytest.core;

import org.jspecify.annotations.Nullable;

public record ServiceEndpoint(
        String project,
        String service,
        String kind,
        @Nullable String url,
        String host,
        int port) {}
