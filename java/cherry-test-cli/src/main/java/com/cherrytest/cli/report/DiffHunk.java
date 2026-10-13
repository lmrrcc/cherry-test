package com.cherrytest.cli.report;

import java.util.List;

public record DiffHunk(String header, List<DiffLine> lines) {
    public DiffHunk {
        lines = List.copyOf(lines);
    }
}
