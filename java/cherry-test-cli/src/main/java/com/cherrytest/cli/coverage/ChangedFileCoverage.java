package com.cherrytest.cli.coverage;

import org.jspecify.annotations.Nullable;

public record ChangedFileCoverage(
        String path,
        @Nullable CoverageSummary unit,
        @Nullable CoverageSummary integration,
        CoverageSummary merged) {}
