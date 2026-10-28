package com.cherrytest.cli;

import com.cherrytest.cli.report.ProjectRunResult;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public record RunOutcome(
        List<ProjectRunResult> results,
        List<Path> lcovFiles,
        List<Path> unitLcovFiles,
        List<Path> integrationLcovFiles,
        boolean failed,
        List<Map.Entry<Plan.JobKey, Duration>> timings) {
    public RunOutcome {
        results = List.copyOf(results);
        lcovFiles = List.copyOf(lcovFiles);
        unitLcovFiles = List.copyOf(unitLcovFiles);
        integrationLcovFiles = List.copyOf(integrationLcovFiles);
        timings = List.copyOf(timings);
    }
}
