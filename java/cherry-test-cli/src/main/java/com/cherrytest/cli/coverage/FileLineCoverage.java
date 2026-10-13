package com.cherrytest.cli.coverage;

import java.util.SortedMap;
import java.util.TreeMap;

public record FileLineCoverage(String path, SortedMap<Long, Boolean> lines) {
    public FileLineCoverage {
        lines = new TreeMap<>(lines);
    }
}
