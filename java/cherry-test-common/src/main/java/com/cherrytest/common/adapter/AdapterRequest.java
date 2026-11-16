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
        @Nullable ContainerSpec container,
        @Nullable List<String> select) {
    public AdapterRequest {
        args = List.copyOf(args);
        env = new TreeMap<>(env);
        options = new TreeMap<>(options);
        select = select == null ? null : List.copyOf(select);
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
            SortedMap<String, JsonNode> options,
            @Nullable ContainerSpec container) {
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
                container,
                null);
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
                replacement,
                select);
    }

    /**
     * The request of a rerun that runs one test alone: its own output, no coverage, not strict, and the arguments or
     * the selection that pick the test.
     */
    public AdapterRequest forRerun(Path rerunOutput, List<String> rerunArgs, @Nullable List<String> rerunSelect) {
        return new AdapterRequest(
                protocol,
                project,
                packageName,
                phase,
                root,
                manifest,
                cwd,
                rerunOutput,
                execution,
                false,
                false,
                rerunArgs,
                env,
                options,
                container,
                rerunSelect);
    }
}
