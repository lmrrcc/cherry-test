package com.cherrytest.common.adapter;

import java.nio.file.Path;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
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
        SortedMap<String, JsonNode> options) {
    public AdapterRequest {
        args = List.copyOf(args);
        env = new TreeMap<>(env);
        options = new TreeMap<>(options);
    }
}
