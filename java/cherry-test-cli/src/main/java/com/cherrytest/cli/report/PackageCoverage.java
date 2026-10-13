package com.cherrytest.cli.report;

import com.cherrytest.cli.coverage.CoverageSummary;
import org.jspecify.annotations.Nullable;

public record PackageCoverage(
        @Nullable CoverageSummary unit, @Nullable CoverageSummary integration, CoverageSummary merged) {
    public static final PackageCoverage EMPTY = new PackageCoverage(null, null, CoverageSummary.ZERO);
}
