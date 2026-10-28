package com.cherrytest.cli;

import com.cherrytest.common.process.EnvFile;
import com.cherrytest.common.process.ProcessEnvironment;
import com.cherrytest.common.rustcompat.RustOrdering;
import java.nio.file.Path;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/** Explicit environments for a run: one snapshot of the process environment, extended per job. */
public final class RunEnv {
    private RunEnv() {}

    /** The process environment, captured once before a run; a run never changes the process environment. */
    public static SortedMap<String, String> snapshot() {
        return ProcessEnvironment.explicitSnapshot();
    }

    /** Variables from {@code .env.test} at the configuration root, or else from {@code <project>/.env.test}. */
    public static SortedMap<String, String> envFile(Path workspaceRoot, String projectId) {
        return EnvFile.read(workspaceRoot, projectId);
    }

    /** The {@code CHERRY_TEST_*} variables an adapter request records; credentials stay out of {@code request.json}. */
    public static SortedMap<String, String> requestEnv(Map<String, String> environment) {
        SortedMap<String, String> view = new TreeMap<>(RustOrdering.STRING_ORDER);
        environment.forEach((key, value) -> {
            if (key.startsWith("CHERRY_TEST_")
                    && !key.contains("AUTH")
                    && !key.contains("TOKEN")
                    && !key.contains("SECRET")) {
                view.put(key, value);
            }
        });
        return view;
    }
}
