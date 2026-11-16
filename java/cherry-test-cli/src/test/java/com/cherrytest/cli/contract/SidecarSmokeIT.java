package com.cherrytest.cli.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

class SidecarSmokeIT {
    private static final String CLIENT = """
            pg_isready -h "$CHERRY_TEST_SMOKE_DB_HOST" -p "$CHERRY_TEST_SMOKE_DB_PORT" \
            && PGPASSWORD=smoke psql -h 127.0.0.1 -U postgres -v ON_ERROR_STOP=1 -qc 'create table only_mine (id int)' \
            && test "$(PGPASSWORD=smoke psql -h 127.0.0.1 -U postgres -tAc \
            'select count(*) from pg_tables where tablename = $$only_mine$$')" = 1""";

    private static void workspace(Path root) throws IOException {
        Files.createDirectories(root.resolve(".git"));
        StringBuilder yaml = new StringBuilder("""
                defaults:
                  coverage: false
                  open_report: false
                  execution: container
                  container:
                    image: postgres:16-alpine
                    timeout: 180s
                projects:
                  smoke:
                    path: .
                    services:
                      db:
                        image: postgres:16-alpine
                        port: 5432
                        kind: postgres
                        env: {POSTGRES_PASSWORD: smoke}
                        tmpfs: [/var/lib/postgresql/data]
                        health:
                          exec: [pg_isready, -h, 127.0.0.1, -p, "5432"]
                          timeout: 120s
                    packages:
                """);
        String quoted = "\"" + CLIENT.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        for (String name : List.of("one", "two")) {
            Files.createDirectories(root.resolve(name));
            Files.writeString(root.resolve(name).resolve("package.json"), "{}", StandardCharsets.UTF_8);
            yaml.append("""
                          %s:
                            manifest: %s/package.json
                            kinds: [integration]
                            integration:
                              adapter: command
                              command: sh
                              scope: smoke
                              args: [-c, %s]
                    """.formatted(name, name, quoted));
        }
        Files.writeString(root.resolve("cherry-test.yaml"), yaml, StandardCharsets.UTF_8);
    }

    private static String labelled(String runId) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("docker", "ps", "-aq", "--filter", "label=cherry-test.run=" + runId)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).isZero();
        return output.strip();
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "CHERRY_TEST_DOCKER_SMOKE", matches = "1")
    void smoke_parallel_jobs_get_their_own_postgres_on_the_same_port(@TempDir Path temp)
            throws IOException, InterruptedException {
        Path root = temp.toRealPath();
        workspace(root);
        Process process = new ProcessBuilder(
                        "java",
                        "--enable-native-access=ALL-UNNAMED",
                        "-jar",
                        Objects.requireNonNull(System.getProperty("cherrytest.cliJar")),
                        "--root",
                        root.toString(),
                        "--no-open",
                        "--no-banner",
                        "--jobs",
                        "2")
                .redirectErrorStream(true)
                .start();
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        String runId = stdout.lines()
                .filter(line -> line.startsWith("cherry-test run: "))
                .map(line -> line.substring("cherry-test run: ".length()).split(" ", -1)[0])
                .findFirst()
                .orElseThrow();
        Path adapters = root.resolve("target/cherry-test/runs").resolve(runId).resolve("reports/adapters/smoke");
        for (String name : List.of("one", "two")) {
            Path job = adapters.resolve(name).resolve("integration");
            String result = Files.readString(job.resolve("result.json"), StandardCharsets.UTF_8);
            assertThat(result).as(name + ": " + stdout).contains("\"status\": \"passed\"");
            String request = Files.readString(job.resolve("request.json"), StandardCharsets.UTF_8);
            assertThat(request).contains("\"CHERRY_TEST_SCOPE_PROVISIONED\":\"smoke\"");
            assertThat(request).contains("\"CHERRY_TEST_SMOKE_DB_PORT\":\"5432\"");
            assertThat(request).contains("\"holder\":\"ct-" + runId + "-smoke-" + name + "-integration-net\"");
            assertThat(job.resolve("sidecar-smoke-db.log")).isRegularFile();
        }
        assertThat(exit).as(stdout).isZero();
        assertThat(labelled(runId)).isEmpty();
    }
}
