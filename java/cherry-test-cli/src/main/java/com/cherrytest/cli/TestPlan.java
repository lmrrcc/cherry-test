package com.cherrytest.cli;

import com.cherrytest.core.TestItConfig;
import java.util.List;

public record TestPlan(
        TestItConfig config, List<ProjectGroup> projects, List<DiscoveredTest> discovered, PreflightPlan preflight) {
    public TestPlan {
        projects = List.copyOf(projects);
        discovered = List.copyOf(discovered);
    }
}
