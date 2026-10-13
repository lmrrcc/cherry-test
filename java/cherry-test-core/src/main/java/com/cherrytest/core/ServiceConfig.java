package com.cherrytest.core;

import org.jspecify.annotations.Nullable;

public record ServiceConfig(
        String service,
        int port,
        @Nullable String health,
        @Nullable String kind) {}
