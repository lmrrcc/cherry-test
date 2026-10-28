package com.cherrytest.common.adapter;

import java.util.List;

/** A registered adapter; {@code isolate} means it runs only the tests a request names in {@code select}. */
public record AdapterConfig(String command, List<String> args, long timeoutSecs, boolean isolate) {
    public static final long DEFAULT_TIMEOUT_SECS = 1800;

    public AdapterConfig {
        args = List.copyOf(args);
    }

    public AdapterConfig(String command, List<String> args, long timeoutSecs) {
        this(command, args, timeoutSecs, false);
    }
}
