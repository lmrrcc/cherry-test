package com.cherrytest.cli.report;

public record TestTimingStats(
        long run, long ok, long fail, long skip, long totalMs, long avgMs, long minMs, long maxMs) {}
