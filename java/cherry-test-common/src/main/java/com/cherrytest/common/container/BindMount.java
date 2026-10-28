package com.cherrytest.common.container;

import java.nio.file.Path;

/** An extra host directory mounted into the container. */
public record BindMount(Path source, String target, boolean readOnly) {}
