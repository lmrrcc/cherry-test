package com.cherrytest.cli.report;

import java.util.SortedSet;
import java.util.TreeSet;

public record AddedLines(String path, SortedSet<Long> lines) {
    public AddedLines {
        lines = new TreeSet<>(lines);
    }
}
