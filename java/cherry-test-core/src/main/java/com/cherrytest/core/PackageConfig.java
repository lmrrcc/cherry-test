package com.cherrytest.core;

import com.cherrytest.common.adapter.ExecutionMode;
import com.cherrytest.common.container.ContainerConfig;
import java.nio.file.Path;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

public record PackageConfig(
        @Nullable String adapter,
        @Nullable ExecutionMode execution,
        SortedMap<String, JsonNode> options,
        @Nullable ExternalRunner integration,
        @Nullable ExternalRunner visual,
        PackageRuntime runtime,
        List<String> kinds,
        @Nullable Path manifest,
        @Nullable ExternalRunner unit,
        @Nullable ExternalRunner functional,
        @Nullable ExternalRunner ui,
        @Nullable ExternalRunner mutation,
        @Nullable ContainerConfig container) {
    public PackageConfig {
        options = new TreeMap<>(options);
        kinds = List.copyOf(kinds);
    }

    public PackageConfig(
            @Nullable String adapter,
            @Nullable ExecutionMode execution,
            SortedMap<String, JsonNode> options,
            @Nullable ExternalRunner integration,
            @Nullable ExternalRunner visual,
            PackageRuntime runtime,
            List<String> kinds,
            @Nullable Path manifest,
            @Nullable ExternalRunner unit,
            @Nullable ExternalRunner functional,
            @Nullable ExternalRunner ui,
            @Nullable ExternalRunner mutation) {
        this(
                adapter,
                execution,
                options,
                integration,
                visual,
                runtime,
                kinds,
                manifest,
                unit,
                functional,
                ui,
                mutation,
                null);
    }

    public PackageConfig withoutExecution() {
        return new PackageConfig(
                adapter,
                null,
                options,
                integration,
                visual,
                runtime,
                kinds,
                manifest,
                unit,
                functional,
                ui,
                mutation,
                container);
    }
}
