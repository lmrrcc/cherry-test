package com.cherrytest.common.adapter;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The classification of one failed test and the reruns behind it; {@code logs} are the output directories of the
 * reruns, relative to the run's {@code reports/}.
 */
public record Isolation(
        IsolationClass isolationClass,
        long runs,
        long passed,
        List<String> logs,
        @Nullable String reason) {
    public Isolation {
        logs = List.copyOf(logs);
    }
}
