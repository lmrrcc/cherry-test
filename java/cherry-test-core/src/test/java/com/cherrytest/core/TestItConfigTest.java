package com.cherrytest.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cherrytest.common.adapter.ExecutionMode;
import com.cherrytest.common.rustcompat.AnyhowError;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
    void parses_parallel_defaults_package_resources_and_groups() throws IOException {
        TestItConfig config = load("""
                defaults:
                  parallel:
                    jobs: auto
                    cpus: 6
                    memory: 12g
                    phase_barrier: false
                projects:
                  shop:
                    packages:
                      api:
                        resources:
                          cpus: 0.5
                          memory: 512m
                        exclusive: true
                groups:
                  backend: [api, "mini-rs-*"]
                """);
        ParallelConfig parallel = Objects.requireNonNull(config.defaults().parallel());
        assertThat(parallel.jobs()).isEqualTo(Jobs.AUTO);
        assertThat(parallel.millicpus()).isEqualTo(6000L);
        assertThat(parallel.memory()).isEqualTo(12L << 30);
        assertThat(parallel.phaseBarrier()).isFalse();
        PackageConfig api = config.packageConfig("shop", "api");
        ResourcesConfig resources = Objects.requireNonNull(api.resources());
        assertThat(resources.millicpus()).isEqualTo(500L);
        assertThat(resources.memory()).isEqualTo(512L << 20);
        assertThat(api.exclusive()).isTrue();
        assertThat(config.groups().get("backend")).containsExactly("api", "mini-rs-*");
        ParallelConfig counted = Objects.requireNonNull(
                load("defaults:\n  parallel:\n    jobs: 4\n").defaults().parallel());
        assertThat(counted.jobs()).isEqualTo(Jobs.count(4));
        assertThat(counted.phaseBarrier()).isTrue();
        assertThat(TestItConfig.empty(root).defaults().parallel()).isNull();
    }

    @Test
    void rejects_unknown_parallel_fields_and_invalid_amounts() {
        for (String yaml : List.of(
                "defaults:\n  parallel:\n    workers: 2\n",
                "projects:\n  shop:\n    packages:\n      api:\n        resources:\n          gpus: 1\n",
                "defaults:\n  parallel:\n    jobs: 0\n",
                "defaults:\n  parallel:\n    jobs: many\n",
                "defaults:\n  parallel:\n    cpus: 0\n",
                "defaults:\n  parallel:\n    memory: 8x\n")) {
            assertThatThrownBy(() -> load(yaml)).as(yaml).isInstanceOf(AnyhowError.class);
        }
    }

    @Test
    void parses_memory_units_as_powers_of_1024() {
        assertThat(Memory.parse("1024")).isEqualTo(1024L);
        assertThat(Memory.parse("1k")).isEqualTo(1024L);
        assertThat(Memory.parse("512MiB")).isEqualTo(512L << 20);
        assertThat(Memory.parse("1.5G")).isEqualTo(3L << 29);
        assertThat(Memory.parse(" 2 gb ")).isEqualTo(2L << 30);
        assertThat(Memory.parse("1t")).isEqualTo(1L << 40);
        for (String invalid : List.of("", "g", ".5g", "5.g", "-1g", "0", "1p", "1..5g")) {
            assertThatThrownBy(() -> Memory.parse(invalid)).as(invalid).isInstanceOf(AnyhowError.class);
        }
        assertThat(Jobs.parse("auto")).isEqualTo(Jobs.AUTO);
        assertThat(Jobs.parse("3")).isEqualTo(Jobs.count(3));
        assertThatThrownBy(() -> Jobs.parse("0")).isInstanceOf(IllegalArgumentException.class);
        assertThat(Jobs.count(3)).hasToString("3");
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
