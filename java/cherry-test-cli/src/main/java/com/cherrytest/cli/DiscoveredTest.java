package com.cherrytest.cli;

import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

public record DiscoveredTest(
        String packageName,
        String function,
        @Nullable String attrScope,
        @Nullable String attrAuth,
        String effectiveScope,
        String project,
        String suite,
        String test,
        Path sourcePath,
        long fnLine) {}
