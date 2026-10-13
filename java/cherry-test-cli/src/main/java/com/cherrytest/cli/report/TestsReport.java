package com.cherrytest.cli.report;

import java.util.List;

public record TestsReport(List<ProjectTestView> projects, List<String> allTags) {
    public static final TestsReport EMPTY = new TestsReport(List.of(), List.of());

    public TestsReport {
        projects = List.copyOf(projects);
        allTags = List.copyOf(allTags);
    }
}
