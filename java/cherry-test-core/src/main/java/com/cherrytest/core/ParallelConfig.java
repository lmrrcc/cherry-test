package com.cherrytest.core;

import org.jspecify.annotations.Nullable;

/** {@code defaults.parallel}: how many jobs run at once and the CPU and memory they may share. */
public record ParallelConfig(
        @Nullable Jobs jobs,
        @Nullable Long millicpus,
        @Nullable Long memory,
        boolean phaseBarrier) {}
