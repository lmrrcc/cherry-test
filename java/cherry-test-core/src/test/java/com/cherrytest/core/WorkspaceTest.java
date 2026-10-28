package com.cherrytest.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceTest {
    @TempDir
    Path root;

    private static ServiceEndpoint endpoint(@Nullable String url) {
        return new ServiceEndpoint("mini", "api", "http", url, "127.0.0.1", 49152);
    }

    @Test
    void service_env_names_host_port_and_url() {
        assertThat(Workspace.serviceEnv(endpoint("http://127.0.0.1:49152")))
                .containsExactly(
                        Map.entry("CHERRY_TEST_MINI_API_HOST", "127.0.0.1"),
                        Map.entry("CHERRY_TEST_MINI_API_PORT", "49152"),
                        Map.entry("CHERRY_TEST_MINI_API_URL", "http://127.0.0.1:49152"));
        assertThat(Workspace.serviceEnv(endpoint(null))).hasSize(2);
    }

    @Test
    void skipped_docker_reuses_supplied_endpoints_without_global_state() throws IOException {
        Files.writeString(
                root.resolve("cherry-test.yaml"),
                "projects:\n  mini:\n    services:\n      api:\n        service: api\n        port: 3000\n",
                StandardCharsets.UTF_8);
        Map<String, String> environment = new TreeMap<>();
        environment.put("CHERRY_TEST_WORKSPACE_ROOT", root.toString());
        environment.put("CHERRY_TEST_SKIP_DOCKER", "1");
        environment.put("CHERRY_TEST_MINI_API_PORT", "4100");
        Workspace.ScopeState state = new Workspace.ScopeState();
        Workspace.ScopeBoot boot = Workspace.bootScope("mini", state, environment);
        assertThat(boot.registry().get("mini\u0000api"))
                .extracting(ServiceEndpoint::port)
                .isEqualTo(4100);
        assertThat(boot.exports()).isEmpty();
        assertThat(state.isBooted("mini")).isFalse();
        Workspace.teardownScopes(state, environment);
        assertThat(state.composeFiles()).isEmpty();
    }
}
