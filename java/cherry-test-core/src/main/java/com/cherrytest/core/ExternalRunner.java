package com.cherrytest.core;

import java.nio.file.Path;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

public record ExternalRunner(
        @Nullable String adapter,
        @Nullable String scope,
        SortedMap<String, JsonNode> options,
        String command,
        List<String> args,
        @Nullable Path cwd) {
    public ExternalRunner {
        options = new TreeMap<>(options);
        args = List.copyOf(args);
    }
}
