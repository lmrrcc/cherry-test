package com.cherrytest.cli.report;

import com.cherrytest.cli.coverage.CoverageSummary;
import org.jspecify.annotations.Nullable;

public record CoverageTotals(
        @Nullable CoverageSummary unit, @Nullable CoverageSummary integration, CoverageSummary merged) {
    public static final CoverageTotals EMPTY = new CoverageTotals(null, null, CoverageSummary.ZERO);
}
