package com.cherrytest.core;

/** {@code defaults.isolate}: rerun each failed test alone to tell real failures from interference and flakiness. */
public record IsolateConfig(boolean onFailure, long runs, long maxTests) {
    public static final long DEFAULT_RUNS = 3;
    public static final long DEFAULT_MAX_TESTS = 10;

    public static IsolateConfig standard() {
        return new IsolateConfig(false, DEFAULT_RUNS, DEFAULT_MAX_TESTS);
    }
}
