package com.cherrytest.common.container;

import java.util.List;
import org.jspecify.annotations.Nullable;

/** A workspace-relative path that the job may write, given as a path or as {@code {path, seed: copy}}. */
public record WritableConfig(String path, @Nullable SeedMode seed) {
    public static final List<String> FIELDS = List.of("path", "seed");
}
