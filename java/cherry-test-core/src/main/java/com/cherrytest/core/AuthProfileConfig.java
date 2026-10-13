package com.cherrytest.core;

import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

public record AuthProfileConfig(
        String profile,
        String issuer,
        String clientId,
        String clientSecret,
        String audience,
        String email,
        long accessTtlSecs,
        long refreshTtlSecs,
        @Nullable String tokenUrl,
        Path cachePath,
        @Nullable String oktaApiProject,
        @Nullable String oktaApiService) {}
