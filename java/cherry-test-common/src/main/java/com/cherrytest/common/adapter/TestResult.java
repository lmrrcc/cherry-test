package com.cherrytest.common.adapter;

import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

public record TestResult(
        String name,
        TestStatus status,
        long durationMs,
        @Nullable String message,
        @Nullable Path source,
        @Nullable Long line,
        List<Attachment> attachments,
        @Nullable Isolation isolation) {
    public TestResult {
        attachments = List.copyOf(attachments);
    }

    public TestResult(
            String name,
            TestStatus status,
            long durationMs,
            @Nullable String message,
            @Nullable Path source,
            @Nullable Long line,
            List<Attachment> attachments) {
        this(name, status, durationMs, message, source, line, attachments, null);
    }

    public TestResult withSource(@Nullable Path replacement) {
        return new TestResult(name, status, durationMs, message, replacement, line, attachments, isolation);
    }

    public TestResult withAttachments(List<Attachment> replacement) {
        return new TestResult(name, status, durationMs, message, source, line, replacement, isolation);
    }

    /** The same result with what rerunning it alone showed. */
    public TestResult withIsolation(@Nullable Isolation replacement) {
        return new TestResult(name, status, durationMs, message, source, line, attachments, replacement);
    }
}
