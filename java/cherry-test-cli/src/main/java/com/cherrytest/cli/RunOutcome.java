package com.cherrytest.cli;

import com.cherrytest.cli.report.ProjectRunResult;
import java.nio.file.Path;
import java.util.List;

public record RunOutcome(
        List<ProjectRunResult> results,
        List<Path> lcovFiles,
        List<Path> unitLcovFiles,
        List<Path> integrationLcovFiles,
        boolean failed,
        boolean ranIntegration) {
    public RunOutcome {
        results = List.copyOf(results);
        lcovFiles = List.copyOf(lcovFiles);
        unitLcovFiles = List.copyOf(unitLcovFiles);
        integrationLcovFiles = List.copyOf(integrationLcovFiles);
    }
}
