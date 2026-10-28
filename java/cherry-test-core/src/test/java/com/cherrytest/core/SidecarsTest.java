package com.cherrytest.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cherrytest.common.container.BindMount;
import com.cherrytest.common.container.HealthCheck;
import com.cherrytest.common.container.NetworkMode;
import com.cherrytest.common.container.PullPolicy;
import com.cherrytest.common.container.SidecarService;
import com.cherrytest.common.container.SidecarSpec;
import com.cherrytest.common.rustcompat.AnyhowError;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SidecarsTest {
    @TempDir
    Path dir;

    private static SidecarService postgres() {
        return postgres(List.of("pg_isready", "-h", "127.0.0.1"), "postgres", 5432);
    }

    private static SidecarService postgres(List<String> exec, String kind, int port) {
        return new SidecarService(
                "shop",
                "db",
                "ct-r1-shop-api-integration-shop-db",
                "postgres:16-alpine",
                PullPolicy.NEVER,
                port,
                kind,
                new TreeMap<>(Map.of("POSTGRES_PASSWORD", "test")),
                List.of("/var/lib/postgresql/data"),
                List.of(new BindMount(Path.of("/repo/fixtures/sql"), "/docker-entrypoint-initdb.d", true)),
                new HealthCheck(exec, 60));
    }

    /**
     * A {@code docker} stand-in on {@code PATH} that appends each call to {@code docker.log} and answers like a
     * service whose health check fails and which then stops.
     */
    private Map<String, String> fakeDocker() throws IOException {
        Path script = dir.resolve("docker");
        Files.writeString(script, """
                #!/bin/sh
                echo "$*" >> "$FAKE_DOCKER_LOG"
                case "$1" in
                  run) echo started ;;
                  exec) exit 1 ;;
                  inspect) echo exited ;;
                  logs) echo "FATAL: role does not exist" ;;
                esac
                exit 0
                """, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        Map<String, String> environment = new TreeMap<>();
        environment.put("PATH", dir + ":/bin:/usr/bin");
        environment.put("FAKE_DOCKER_LOG", dir.resolve("docker.log").toString());
        return environment;
    }

    private static SidecarSpec jobNetwork() {
        return new SidecarSpec("ct-r1-shop-api-integration-net", List.of(postgres()));
    }

    private static Sidecars.Labels labels() {
        return new Sidecars.Labels("r1", "shop/api/integration", 7);
    }

    private List<String> calls() throws IOException {
        return Files.readAllLines(dir.resolve("docker.log"), StandardCharsets.UTF_8);
    }

    @Test
    void a_service_that_stops_fails_the_job_network_and_is_removed() throws IOException {
        Map<String, String> environment = fakeDocker();
        Path logs = dir.resolve("output");
        assertThatThrownBy(() -> Sidecars.startJobNetwork(
                        environment,
                        jobNetwork(),
                        "eclipse-temurin:25-jdk",
                        PullPolicy.NEVER,
                        NetworkMode.NONE,
                        labels(),
                        logs,
                        () -> false))
                .isInstanceOf(AnyhowError.class)
                .satisfies(error -> assertThat(AnyhowError.alternate(error))
                        .isEqualTo("image service `shop/db` (postgres:16-alpine) stopped before it became healthy; see "
                                + logs.resolve("sidecar-shop-db.log")));
        assertThat(Files.readString(logs.resolve("sidecar-shop-db.log"), StandardCharsets.UTF_8))
                .isEqualTo("FATAL: role does not exist\n");
        List<String> calls = calls();
        assertThat(calls).hasSize(6);
        assertThat(calls.get(0)).startsWith("run -d --name ct-r1-shop-api-integration-net ");
        assertThat(calls.get(1)).startsWith("run -d --name ct-r1-shop-api-integration-shop-db ");
        assertThat(calls.get(2)).isEqualTo("exec ct-r1-shop-api-integration-shop-db pg_isready -h 127.0.0.1");
        assertThat(calls.get(3)).isEqualTo("inspect --format {{.State.Status}} ct-r1-shop-api-integration-shop-db");
        assertThat(calls.get(4)).isEqualTo("logs ct-r1-shop-api-integration-shop-db");
        assertThat(calls.get(5))
                .isEqualTo("rm -f -v ct-r1-shop-api-integration-net ct-r1-shop-api-integration-shop-db");
    }

    @Test
    void stopping_the_job_network_saves_logs_and_removes_every_container() throws IOException {
        Map<String, String> environment = fakeDocker();
        Path logs = dir.resolve("output");
        Sidecars.stopJobNetwork(environment, jobNetwork(), logs);
        assertThat(logs.resolve("sidecar-shop-db.log")).isRegularFile();
        assertThat(calls())
                .containsExactly(
                        "logs ct-r1-shop-api-integration-shop-db",
                        "rm -f -v ct-r1-shop-api-integration-net ct-r1-shop-api-integration-shop-db");
        assertThatThrownBy(() -> Sidecars.startJobNetwork(
                        environment,
                        jobNetwork(),
                        "eclipse-temurin:25-jdk",
                        PullPolicy.NEVER,
                        NetworkMode.NONE,
                        labels(),
                        logs,
                        () -> true))
                .satisfies(error -> assertThat(AnyhowError.alternate(error))
                        .isEqualTo("cancelled while waiting for image service `shop/db` (postgres:16-alpine)"));
    }

    @Test
    void listening_reads_proc_net_tcp() {
        String tcp =
                "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode\n"
                        + "   0: 0100007F:1538 00000000:0000 0A 00000000:00000000 00:00000000 00000000    70        0 1 1"
                        + " 0000000000000000 100 0 0 10 0\n"
                        + "   1: 0100007F:9C40 0100007F:1538 01 00000000:00000000 00:00000000 00000000     0        0 2 1"
                        + " 0000000000000000 20 4 30 10 -1\n";
        assertThat(Sidecars.listening(tcp, 5432)).isTrue();
        assertThat(Sidecars.listening(tcp, 40000)).isFalse();
        assertThat(Sidecars.listening(tcp, 80)).isFalse();
        String tcp6 = "  sl  local_address                         remote_address                        st\n"
                + "   0: 00000000000000000000000000000000:0050 00000000000000000000000000000000:0000 0A"
                + " 00000000:00000000\n";
        assertThat(Sidecars.listening(tcp6, 80)).isTrue();
        assertThat(Sidecars.listening("", 80)).isFalse();
    }

    @Test
    void provisioned_scopes_come_from_the_runner_variable() {
        Map<String, String> environment = new TreeMap<>();
        assertThat(Sidecars.provisioned(environment, "db")).isFalse();
        environment.put(Sidecars.SCOPE_PROVISIONED_ENV, "checkout,db");
        assertThat(Sidecars.provisioned(environment, "db")).isTrue();
        assertThat(Sidecars.provisioned(environment, "checkout")).isTrue();
        assertThat(Sidecars.provisioned(environment, "d")).isFalse();
    }

    @Test
    void sidecar_env_points_at_loopback() {
        assertThat(Sidecars.sidecarEnv(postgres()))
                .containsExactly(
                        Map.entry("CHERRY_TEST_SHOP_DB_HOST", "127.0.0.1"),
                        Map.entry("CHERRY_TEST_SHOP_DB_PORT", "5432"));
        assertThat(Sidecars.sidecarEnv(postgres(List.of(), "http", 8080)).get(2))
                .isEqualTo(Map.entry("CHERRY_TEST_SHOP_DB_URL", "http://127.0.0.1:8080"));
        assertThat(Sidecars.envPrefix("shop", "db")).isEqualTo("CHERRY_TEST_SHOP_DB_");
    }

    @Test
    void probes_exec_in_the_service_or_read_sockets_from_the_holder() {
        assertThat(Sidecars.probeArgv(postgres(), "holder"))
                .containsExactly("exec", "ct-r1-shop-api-integration-shop-db", "pg_isready", "-h", "127.0.0.1");
        assertThat(Sidecars.probeArgv(postgres(List.of(), "postgres", 5432), "holder"))
                .containsExactly("exec", "holder", "cat", "/proc/net/tcp", "/proc/net/tcp6");
        assertThat(Sidecars.removeArgv(List.of("a", "b"))).containsExactly("rm", "-f", "-v", "a", "b");
        assertThat(Sidecars.logPath(Path.of("/out"), postgres())).isEqualTo(Path.of("/out/sidecar-shop-db.log"));
    }
}
