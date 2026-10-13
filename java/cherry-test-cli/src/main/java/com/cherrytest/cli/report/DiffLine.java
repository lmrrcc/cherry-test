package com.cherrytest.cli.report;

import org.jspecify.annotations.Nullable;

public record DiffLine(@Nullable Long oldNo, @Nullable Long newNo, DiffTag tag, String text, LineCov coverage) {
    public DiffLine withCoverage(LineCov replacement) {
        return new DiffLine(oldNo, newNo, tag, text, replacement);
    }
}
