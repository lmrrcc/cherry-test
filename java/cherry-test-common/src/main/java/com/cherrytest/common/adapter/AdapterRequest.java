package com.cherrytest.common.adapter;

import com.cherrytest.common.container.ContainerSpec;
import java.nio.file.Path;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

public record AdapterRequest(
        long protocol,
        String project,
        String packageName,
        String phase,
        Path root,
        Path manifest,
        Path cwd,
        Path output,
        ExecutionMode execution,
        boolean coverage,
        boolean strict,
        List<String> args,
        SortedMap<String, String> env,
        SortedMap<String, JsonNode> options,
        @Nullable ContainerSpec container) {
    public AdapterRequest {
        args = List.copyOf(args);
        env = new TreeMap<>(env);
        options = new TreeMap<>(options);
    }

    public AdapterRequest(
            long protocol,
            String project,
            String packageName,
            String phase,
            Path root,
            Path manifest,
            Path cwd,
            Path output,
            ExecutionMode execution,
            boolean coverage,
            boolean strict,
            List<String> args,
            SortedMap<String, String> env,
            SortedMap<String, JsonNode> options) {
        this(
                protocol,
                project,
                packageName,
                phase,
                root,
                manifest,
                cwd,
                output,
                execution,
                coverage,
                strict,
                args,
                env,
                options,
                null);
    }

    /** The same request carrying resolved container settings. */
    public AdapterRequest withContainer(@Nullable ContainerSpec replacement) {
        return new AdapterRequest(
                protocol,
                project,
                packageName,
                phase,
                root,
                manifest,
                cwd,
                output,
                execution,
                coverage,
                strict,
                args,
                env,
                options,
                replacement);
    }
}
