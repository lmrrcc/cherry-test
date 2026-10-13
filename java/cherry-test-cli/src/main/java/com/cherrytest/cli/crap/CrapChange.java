package com.cherrytest.cli.crap;

import java.util.List;

public record CrapChange(List<CrapFunction> functions, CrapTotals totals) {
    public CrapChange {
        functions = List.copyOf(functions);
    }
}
