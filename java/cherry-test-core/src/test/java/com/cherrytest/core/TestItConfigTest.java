package com.cherrytest.core;

import static org.assertj.core.api.Assertions.assertThat;

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
