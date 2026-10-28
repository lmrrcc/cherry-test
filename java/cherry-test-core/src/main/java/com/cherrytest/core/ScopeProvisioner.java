package com.cherrytest.core;

import com.cherrytest.common.rustcompat.AnyhowError;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

sealed interface ScopeProvisioner permits ComposeCliProvisioner, TestcontainersProvisioner {
    String VARIABLE = "CHERRY_TEST_PROVISIONER";

    void up(
            Path root,
            TestItConfig config,
            String scope,
            List<String> projectNames,
            List<Path> composeFiles,
            List<String> services,
            Map<String, String> environment);

    String mappedAddress(
            Path root,
            List<Path> composeFiles,
            String project,
            String service,
            int port,
            Map<String, String> environment);

    void down(Path root, List<Path> composeFiles, Map<String, String> environment);

    static ScopeProvisioner selected(Map<String, String> environment) {
        String value = environment.get(VARIABLE);
        if (value == null || value.isEmpty() || value.equals("compose")) {
            return ComposeCliProvisioner.INSTANCE;
        }
        if (value.equals("testcontainers")) {
            return TestcontainersProvisioner.INSTANCE;
        }
        throw new AnyhowError("unknown " + VARIABLE + " `" + value + "`; expected `compose` or `testcontainers`");
    }
}
