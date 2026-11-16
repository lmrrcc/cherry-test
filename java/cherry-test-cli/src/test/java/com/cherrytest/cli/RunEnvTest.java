package com.cherrytest.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunEnvTest {
    @TempDir
    Path root;

    @Test
    void env_file_prefers_the_root_file_and_skips_comments() throws IOException {
        Files.createDirectory(root.resolve("mini"));
        Files.writeString(root.resolve("mini/.env.test"), "CHERRY_TEST_MINI_DB_NAME=nested\n", StandardCharsets.UTF_8);
        assertThat(RunEnv.envFile(root, "mini")).containsEntry("CHERRY_TEST_MINI_DB_NAME", "nested");
        Files.writeString(
                root.resolve(".env.test"),
                "# comment\n\n CHERRY_TEST_MINI_DB_NAME = root \nBROKEN\n",
                StandardCharsets.UTF_8);
        SortedMap<String, String> variables = RunEnv.envFile(root, "mini");
        assertThat(variables).hasSize(1).containsEntry("CHERRY_TEST_MINI_DB_NAME", "root");
        assertThat(RunEnv.envFile(root, "other")).containsKey("CHERRY_TEST_MINI_DB_NAME");
    }

    @Test
    void request_env_keeps_cherry_test_variables_without_credentials() {
        Map<String, String> environment = new TreeMap<>();
        environment.put("PATH", "/bin");
        environment.put("CHERRY_TEST_RUN_ID", "run");
        environment.put("CHERRY_TEST_AUTH_OKTA_ACCESS_TOKEN", "token");
        environment.put("CHERRY_TEST_API_TOKEN", "token");
        environment.put("CHERRY_TEST_CLIENT_SECRET", "secret");
        environment.put("CHERRY_TEST_MINI_DB_PASSWORD", "password");
        assertThat(RunEnv.requestEnv(environment).keySet())
                .containsExactly("CHERRY_TEST_MINI_DB_PASSWORD", "CHERRY_TEST_RUN_ID");
    }
}
