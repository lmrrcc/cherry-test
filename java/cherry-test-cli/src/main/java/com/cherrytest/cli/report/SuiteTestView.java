package com.cherrytest.cli.report;

import java.util.List;

public record SuiteTestView(String suite, TestTimingStats stats, List<TestCaseView> cases) {
    public SuiteTestView {
        cases = List.copyOf(cases);
    }
}
