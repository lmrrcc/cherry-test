package com.cherrytest.common.adapter;

import java.util.List;

public record AdapterConfig(String command, List<String> args, long timeoutSecs) {
    public static final long DEFAULT_TIMEOUT_SECS = 1800;

    public AdapterConfig {
        args = List.copyOf(args);
    }
}
