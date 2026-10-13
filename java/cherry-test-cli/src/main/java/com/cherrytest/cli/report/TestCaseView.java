package com.cherrytest.cli.report;

import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

public record TestCaseView(
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
        List<SnippetLine> snippet,
        @Nullable Path snippetPath) {
    public TestCaseView {
        tags = List.copyOf(tags);
        snippet = List.copyOf(snippet);
    }
}
