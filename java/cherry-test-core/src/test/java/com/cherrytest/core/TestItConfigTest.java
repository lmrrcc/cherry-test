package com.cherrytest.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cherrytest.common.adapter.ExecutionMode;
import com.cherrytest.common.container.ContainerConfig;
import com.cherrytest.common.container.MountConfig;
import com.cherrytest.common.container.NetworkMode;
import com.cherrytest.common.container.PullPolicy;
import com.cherrytest.common.container.SeedMode;
import com.cherrytest.common.container.WorkspaceAccess;
import com.cherrytest.common.container.WritableConfig;
import com.cherrytest.common.rustcompat.AnyhowError;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
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
    class container {
        private String error(String yaml) {
            Map<String, Object> raw = TestItConfig.parseRaw(Path.of("cherry-test.yaml"), yaml);
            try {
                TestItConfig.parseDefaults(raw);
                TestItConfig.parseProjects(raw);
            } catch (AnyhowError error) {
                return AnyhowError.alternate(error);
            }
            throw new AssertionError("expected a parse error for " + yaml);
        }

        @Test
        void parses_a_container_block_in_defaults_and_packages() {
            String yaml = """
                    defaults:
                      execution: container
                      container:
                        image: alpine:3.20
                        pull: missing
                        network: bridge
                        user: host
                        cap_drop: [ALL]
                        no_new_privileges: true
                        read_only_root: true
                        cpus: 2
                        memory: 2g
                        pids: 256
                        shm_size: 64m
                        tmpfs: [/tmp]
                        timeout: 30m
                        workspace: ro
                        writable: [target, {path: build, seed: copy}]
                        mounts:
                          - {source: ../sibling}
                          - {source: ../data, target: /data, read_only: false}
                        caches:
                          - {volume: cherry-test-m2, target: /tmp/home/.m2}
                        env:
                          pass: [CI]
                          set: {LANG: C.UTF-8}
                    packages:
                      prices:
                        container:
                          network: none
                          timeout: 90
                    """;
            Map<String, Object> raw = TestItConfig.parseRaw(Path.of("cherry-test.yaml"), yaml);
            ContainerConfig container =
                    Objects.requireNonNull(TestItConfig.parseDefaults(raw).container());
            assertThat(container.image()).isEqualTo("alpine:3.20");
            assertThat(container.pull()).isEqualTo(PullPolicy.MISSING);
            assertThat(container.network()).isEqualTo(NetworkMode.BRIDGE);
            assertThat(container.user()).isEqualTo("host");
            assertThat(container.cpus()).isEqualTo("2");
            assertThat(container.memory()).isEqualTo("2g");
            assertThat(container.pids()).isEqualTo(256L);
            assertThat(container.timeout()).isEqualTo(1800L);
            assertThat(container.workspace()).isEqualTo(WorkspaceAccess.RO);
            assertThat(Objects.requireNonNull(container.writable()).get(1))
                    .isEqualTo(new WritableConfig("build", SeedMode.COPY));
            List<MountConfig> mounts = Objects.requireNonNull(container.mounts());
            assertThat(mounts.get(0).target()).isNull();
            assertThat(mounts.get(0).readOnly()).isTrue();
            assertThat(mounts.get(1).readOnly()).isFalse();
            assertThat(Objects.requireNonNull(container.caches()).get(0).target())
                    .isEqualTo("/tmp/home/.m2");
            assertThat(Objects.requireNonNull(container.env()).pass()).containsExactly("CI");
            PackageConfig prices = Objects.requireNonNull(
                    TestItConfig.parsePackages(raw.get("packages"), "packages.").get("prices"));
            ContainerConfig override = Objects.requireNonNull(prices.container());
            assertThat(override.network()).isEqualTo(NetworkMode.NONE);
            assertThat(override.timeout()).isEqualTo(90L);
            assertThat(override.image()).isNull();
        }

        @Test
        void rejects_unknown_container_keys() {
            assertThat(error("defaults:\n  container:\n    image: alpine\n    privileged: true\n"))
                    .contains("defaults.container: unknown field `privileged`, expected one of `image`, `pull`");
            assertThat(
                            error(
                                    "projects:\n  demo:\n    packages:\n      api:\n        container:\n          netwrok: none\n"))
                    .contains("unknown field `netwrok`");
        }

        @Test
        void rejects_unknown_keys_in_container_entries() {
            Map<String, String> cases = new LinkedHashMap<>();
            cases.put("writable: [{path: target, mode: rw}]", "mode");
            cases.put("mounts: [{source: ../data, readonly: true}]", "readonly");
            cases.put("caches: [{volume: m2, target: /m2, size: 1g}]", "size");
            cases.put("env: {passthrough: [CI]}", "passthrough");
            cases.forEach((yaml, field) -> assertThat(error("defaults:\n  container:\n    " + yaml + "\n"))
                    .contains("unknown field `" + field + "`"));
        }

        @Test
        void rejects_unknown_container_values() {
            Map<String, String> cases = new LinkedHashMap<>();
            cases.put("network: host", "unknown variant `host`, expected `none` or `bridge`");
            cases.put("pull: sometimes", "unknown variant `sometimes`, expected one of `never`, `missing`, `always`");
            cases.put("workspace: readonly", "unknown variant `readonly`, expected `ro` or `rw`");
            cases.put("writable: [{path: target, seed: link}]", "unknown variant `link`, expected `copy`");
            cases.put("timeout: soon", "invalid duration `soon`");
            cases.forEach((yaml, expected) -> assertThat(error("defaults:\n  container:\n    " + yaml + "\n"))
                    .contains(expected));
        }

        @Test
        void documented_container_examples_parse() throws IOException {
            int examples = 0;
            for (String file : List.of("../../README.md", "../../docs/adapters.md")) {
                String doc = Files.readString(Path.of(file), StandardCharsets.UTF_8);
                String[] blocks = doc.split("```yaml\n", -1);
                for (int index = 1; index < blocks.length; index++) {
                    String yaml = blocks[index].substring(0, blocks[index].indexOf("```"));
                    if (!yaml.contains("  container:\n")) {
                        continue;
                    }
                    Map<String, Object> raw = TestItConfig.parseRaw(Path.of("cherry-test.yaml"), yaml);
                    assertThat(TestItConfig.parseDefaults(raw).container()).isNotNull();
                    TestItConfig.parseProjects(raw);
                    examples++;
                }
            }
            assertThat(examples).isEqualTo(2);
        }

        @Test
        void container_settings_apply_only_with_container_execution() throws IOException {
            TestItConfig config = load("projects:\n  prices:\n    packages:\n      api: {}\n");
            assertThat(config.containerSettingsInUse()).isFalse();
            config.defaults(config.defaults().withContainer(ContainerConfig.empty()));
            assertThat(config.containerSettingsInUse()).isFalse();
            assertThat(config.withExecution(ExecutionMode.CONTAINER).containerSettingsInUse())
                    .isTrue();
        }
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
