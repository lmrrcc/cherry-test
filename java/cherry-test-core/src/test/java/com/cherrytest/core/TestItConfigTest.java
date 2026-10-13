package com.cherrytest.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.cherrytest.common.adapter.ExecutionMode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestItConfigTest {
    @TempDir
    Path root;

    private TestItConfig load(String yaml) throws IOException {
        Files.writeString(root.resolve("cherry-test.yaml"), yaml, StandardCharsets.UTF_8);
        return TestItConfig.load(root);
    }

    @Test
    void parses_yaml_defaults_and_projects() {
        String yaml = """
                defaults:
                  scope: global
                  diff_base: main
                projects:
                  mini:
                    path: mini
                    diff_base: main
                    packages:
                      mini-rs-api:
                        kinds: [unit, integration]
                """;
        Map<String, Object> raw = TestItConfig.parseRaw(Path.of("cherry-test.yaml"), yaml);
        assertThat(TestItConfig.parseDefaults(raw).diffBase()).isEqualTo("main");
        assertThat(TestItConfig.parseProjects(raw)).containsKey("mini");
    }

    @Test
    void parses_a_mutation_runner() {
        String yaml = """
                packages:
                  mini-rs-lib-mcap:
                    kinds: [mutation]
                    mutation:
                      adapter: command
                      command: cargo
                      args: [mutants, --package, mini-rs-lib-mcap]
                """;
        Map<String, Object> raw = TestItConfig.parseRaw(Path.of("cherry-test.yaml"), yaml);
        PackageConfig config = Objects.requireNonNull(
                TestItConfig.parsePackages(raw.get("packages"), "packages.").get("mini-rs-lib-mcap"));
        ExternalRunner runner = Objects.requireNonNull(config.mutation());
        assertThat(config.kinds()).containsExactly("mutation");
        assertThat(runner.adapter()).isEqualTo("command");
        assertThat(runner.command()).isEqualTo("cargo");
        assertThat(runner.args()).containsExactly("mutants", "--package", "mini-rs-lib-mcap");
    }

    @Test
    void diff_base_falls_back_to_defaults() throws IOException {
        TestItConfig config = load("defaults:\n  diff_base: main\nprojects:\n  mini:\n    diff_base: develop\n");
        assertThat(config.diffBaseForProject("mini")).isEqualTo("develop");
    }

    @Nested
    class with_execution {
        private TestItConfig config_with_container_package() throws IOException {
            return load("projects:\n  prices:\n    packages:\n      prices-c:\n        execution: container\n");
        }

        @Test
        void sets_the_default_execution() {
            TestItConfig config = TestItConfig.empty(root).withExecution(ExecutionMode.CONTAINER);
            assertThat(config.defaults().execution()).isEqualTo(ExecutionMode.CONTAINER);
        }

        @Test
        void overrides_package_execution() throws IOException {
            TestItConfig config = config_with_container_package().withExecution(ExecutionMode.LOCAL);
            assertThat(config.packageConfig("prices", "prices-c").execution()).isNull();
        }
    }
}
