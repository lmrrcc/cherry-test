package com.cherrytest.cli;

import com.cherrytest.core.Jobs;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

public record RunCli(
        @Nullable String filter,
        @Nullable Path root,
        boolean initOnly,
        boolean forceInit,
        boolean noCoverage,
        boolean noCrap,
        @Nullable String execution,
        boolean strict,
        List<String> kinds,
        @Nullable Jobs jobs,
        @Nullable Shard shard,
        boolean noHtml,
        boolean noOpen,
        boolean noBanner,
        boolean isolate,
        boolean noIsolate,
        boolean keepGoing,
        boolean quiet,
        boolean verbose,
        List<String> cargoTestArgs) {
    public RunCli {
        kinds = List.copyOf(kinds);
        cargoTestArgs = List.copyOf(cargoTestArgs);
    }
}
