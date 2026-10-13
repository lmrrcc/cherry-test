package com.cherrytest.cli.report;

import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

public final class IntegrationRow {
    final String project;
    final String suite;
    final String test;
    final String fullName;
    final String scope;
    final String target;
    long elapsedMs;
    String statusLabel;
    final String http;

    @Nullable
    String reason;

    String method;
    String url;
    final List<String> tags;
    final @Nullable Location location;

    record Location(Path source, long line) {}

    IntegrationRow(
            String project,
            String suite,
            String test,
            String fullName,
            String scope,
            String target,
            long elapsedMs,
            String statusLabel,
            String http,
            @Nullable String reason,
            String method,
            String url,
            List<String> tags,
            @Nullable Location location) {
        this.project = project;
        this.suite = suite;
        this.test = test;
        this.fullName = fullName;
        this.scope = scope;
        this.target = target;
        this.elapsedMs = elapsedMs;
        this.statusLabel = statusLabel;
        this.http = http;
        this.reason = reason;
        this.method = method;
        this.url = url;
        this.tags = List.copyOf(tags);
        this.location = location;
    }

    public String project() {
        return project;
    }

    public String suite() {
        return suite;
    }

    public String test() {
        return test;
    }

    public String fullName() {
        return fullName;
    }

    public String scope() {
        return scope;
    }

    public String target() {
        return target;
    }

    public long elapsedMs() {
        return elapsedMs;
    }

    public String statusLabel() {
        return statusLabel;
    }

    public String http() {
        return http;
    }

    public @Nullable String reason() {
        return reason;
    }

    public String method() {
        return method;
    }

    public String url() {
        return url;
    }

    public List<String> tags() {
        return tags;
    }
}
