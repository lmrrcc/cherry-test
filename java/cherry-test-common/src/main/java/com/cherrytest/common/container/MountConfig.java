package com.cherrytest.common.container;

import java.util.List;
import org.jspecify.annotations.Nullable;

/** An extra bind mount. {@code source} is relative to the workspace root and may point outside it. */
public record MountConfig(String source, @Nullable String target, boolean readOnly) {
    public static final List<String> FIELDS = List.of("source", "target", "read_only");
}
