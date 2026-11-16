package com.cherrytest.common.container;

import java.nio.file.Path;

/** A fresh per-job host directory mounted over a workspace path. */
public record WritableMount(Path origin, Path source, String target, boolean seed) {}
