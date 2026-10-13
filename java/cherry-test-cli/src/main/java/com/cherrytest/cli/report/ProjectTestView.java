package com.cherrytest.cli.report;

import java.util.List;

public record ProjectTestView(String project, TestTimingStats stats, List<SuiteTestView> suites) {
    public ProjectTestView {
        suites = List.copyOf(suites);
    }
}
