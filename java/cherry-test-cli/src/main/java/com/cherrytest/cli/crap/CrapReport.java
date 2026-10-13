package com.cherrytest.cli.crap;

import java.util.List;
import org.jspecify.annotations.Nullable;

public record CrapReport(
        boolean enabled,
        List<CrapFunction> functions,
        CrapTotals totals,
        List<CrapProjectTotals> projects,
        @Nullable CrapChange changed) {
    public static final CrapReport DISABLED = new CrapReport(false, List.of(), CrapTotals.ZERO, List.of(), null);

    public CrapReport {
        functions = List.copyOf(functions);
        projects = List.copyOf(projects);
    }

    public CrapReport withChanged(@Nullable CrapChange replacement) {
        return new CrapReport(enabled, functions, totals, projects, replacement);
    }
}
