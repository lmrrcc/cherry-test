package com.cherrytest.core;

import com.cherrytest.common.adapter.ExecutionMode;
import java.nio.file.Path;

public record Defaults(
        ExecutionMode execution,
        boolean strict,
        boolean coverage,
        boolean htmlReport,
        boolean openReport,
        Path runsBase,
        Path reportDir,
        String scope,
        String diffBase,
        Path coverageIgnore) {
    public static Defaults standard() {
        return new Defaults(
                ExecutionMode.LOCAL,
                false,
                true,
                true,
                true,
                Path.of("target/cherry-test/runs"),
                Path.of("target/cherry-test/reports"),
                "global",
                "main",
                Path.of(".coverageignore"));
    }

    public Defaults withExecution(ExecutionMode replacement) {
        return new Defaults(
                replacement,
                strict,
                coverage,
                htmlReport,
                openReport,
                runsBase,
                reportDir,
                scope,
                diffBase,
                coverageIgnore);
    }

    public Defaults withStrict(boolean replacement) {
        return new Defaults(
                execution,
                replacement,
                coverage,
                htmlReport,
                openReport,
                runsBase,
                reportDir,
                scope,
                diffBase,
                coverageIgnore);
    }
}
