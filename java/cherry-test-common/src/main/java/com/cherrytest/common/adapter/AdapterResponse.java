package com.cherrytest.common.adapter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class AdapterResponse {
    public static final long PROTOCOL_VERSION = 1;
    private long protocol;
    private boolean success;
    private final List<TestResult> tests;
    private final List<Path> coverage;
    private final List<String> diagnostics;

    public AdapterResponse(
            long protocol, boolean success, List<TestResult> tests, List<Path> coverage, List<String> diagnostics) {
        this.protocol = protocol;
        this.success = success;
        this.tests = new ArrayList<>(tests);
        this.coverage = new ArrayList<>(coverage);
        this.diagnostics = new ArrayList<>(diagnostics);
    }

    public static AdapterResponse empty() {
        return new AdapterResponse(PROTOCOL_VERSION, true, List.of(), List.of(), List.of());
    }

    public static AdapterResponse failure(String diagnostic) {
        return new AdapterResponse(PROTOCOL_VERSION, false, List.of(), List.of(), List.of(diagnostic));
    }

    public long protocol() {
        return protocol;
    }

    public void protocol(long value) {
        protocol = value;
    }

    public boolean success() {
        return success;
    }

    public void success(boolean value) {
        success = value;
    }

    public List<TestResult> tests() {
        return tests;
    }

    public List<Path> coverage() {
        return coverage;
    }

    public List<String> diagnostics() {
        return diagnostics;
    }

    public AdapterResponse copy() {
        return new AdapterResponse(protocol, success, tests, coverage, diagnostics);
    }
}
