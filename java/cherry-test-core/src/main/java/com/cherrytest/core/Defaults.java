package com.cherrytest.core;

import com.cherrytest.common.adapter.ExecutionMode;
import com.cherrytest.common.container.ContainerConfig;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

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
        Path coverageIgnore,
        @Nullable ParallelConfig parallel,
        @Nullable ContainerConfig container,
        @Nullable IsolateConfig isolate) {
    public Defaults(
            ExecutionMode execution,
            boolean strict,
            boolean coverage,
            boolean htmlReport,
            boolean openReport,
            Path runsBase,
            Path reportDir,
            String scope,
            String diffBase,
            Path coverageIgnore,
            @Nullable ParallelConfig parallel,
            @Nullable ContainerConfig container) {
        this(
                execution,
                strict,
                coverage,
                htmlReport,
                openReport,
                runsBase,
                reportDir,
                scope,
                diffBase,
                coverageIgnore,
                parallel,
                container,
                null);
    }

    public Defaults(
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
        this(
                execution,
                strict,
                coverage,
                htmlReport,
                openReport,
                runsBase,
                reportDir,
                scope,
                diffBase,
                coverageIgnore,
                null,
                null);
    }

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
                Path.of(".coverageignore"),
                null,
                null);
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
                coverageIgnore,
                parallel,
                container,
                isolate);
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
                coverageIgnore,
                parallel,
                container,
                isolate);
    }

    public Defaults withContainer(@Nullable ContainerConfig replacement) {
        return new Defaults(
                execution,
                strict,
                coverage,
                htmlReport,
                openReport,
                runsBase,
                reportDir,
                scope,
                diffBase,
                coverageIgnore,
                parallel,
                replacement,
                isolate);
    }
}
