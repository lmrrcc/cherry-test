package com.cherrytest.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cherrytest.common.rustcompat.AnyhowError;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
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
    void provisioned_scope_reads_endpoints_without_docker() throws IOException {
        Files.writeString(root.resolve("cherry-test.yaml"), """
                projects:
                  shop:
                    services:
                      db:
                        image: postgres:16-alpine
                        port: 5432
                        kind: postgres
                scopes:
                  checkout:
                    projects: [shop]
                  audit:
                    projects: [shop]
                """, StandardCharsets.UTF_8);
        Map<String, String> environment = new TreeMap<>();
        environment.put("CHERRY_TEST_WORKSPACE_ROOT", root.toString());
        Path bin = Files.createDirectories(root.resolve("bin"));
        Files.writeString(bin.resolve("docker"), "#!/bin/sh\nexit 1\n", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(bin.resolve("docker"), PosixFilePermissions.fromString("rwxr-xr-x"));
        environment.put("PATH", bin.toString());
        environment.put(Sidecars.SCOPE_PROVISIONED_ENV, "checkout");
        environment.put("CHERRY_TEST_SHOP_DB_HOST", "127.0.0.1");
        environment.put("CHERRY_TEST_SHOP_DB_PORT", "5432");
        Workspace.ScopeState state = new Workspace.ScopeState();
        Workspace.ScopeBoot boot = Workspace.bootScope("checkout", state, environment);
        ServiceEndpoint db = boot.registry().get("shop\u0000db");
        assertThat(db).isEqualTo(new ServiceEndpoint("shop", "db", "postgres", null, "127.0.0.1", 5432));
        assertThat(boot.exports()).isEmpty();
        assertThat(state.isBooted("checkout")).isFalse();
        assertThatThrownBy(() -> Workspace.bootScope("audit", state, environment))
                .isInstanceOf(AnyhowError.class)
                .hasMessageStartingWith("docker is not available");
        Workspace.teardownScopes(state, environment);
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
