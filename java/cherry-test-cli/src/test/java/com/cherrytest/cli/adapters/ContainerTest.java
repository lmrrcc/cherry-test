package com.cherrytest.cli.adapters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cherrytest.common.adapter.AdapterRequest;
import com.cherrytest.common.adapter.ExecutionMode;
import com.cherrytest.common.container.BindMount;
import com.cherrytest.common.container.CacheVolume;
import com.cherrytest.common.container.ContainerConfig;
import com.cherrytest.common.container.ContainerSpec;
import com.cherrytest.common.container.NetworkMode;
import com.cherrytest.common.container.PullPolicy;
import com.cherrytest.common.container.WorkspaceAccess;
import com.cherrytest.common.container.WritableMount;
import com.cherrytest.common.process.CommandOutput;
import com.cherrytest.common.process.SystemCommand;
import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.common.rustcompat.RustOrdering;
import com.cherrytest.common.rustcompat.SerdeJsonWriter;
import com.cherrytest.core.TestItConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/** Twin of the tests in {@code test/cherry-test/src/adapters/container.rs}. */
class ContainerTest {
    @TempDir
    Path temp;

    @TempDir
    Path configDir;

    private Path root;

    @BeforeEach
    void workspace() throws IOException {
        root = temp.toRealPath().resolve("repo");
        Files.createDirectories(root.resolve(".git"));
    }

    private AdapterRequest request(ExecutionMode execution) {
        return new AdapterRequest(
                1,
                "shop",
                "prices",
                "unit",
                root,
                root.resolve("prices/pom.xml"),
                root.resolve("prices"),
                root.resolve("target/cherry-test/runs/r1/reports/adapters/shop/prices/unit"),
                execution,
                true,
                false,
                List.of(),
                new TreeMap<>(),
                new TreeMap<>());
    }

    private ContainerConfig block(String yaml) {
        try {
            Files.writeString(
                    configDir.resolve("cherry-test.yaml"),
                    "defaults:\n  container:\n" + yaml.indent(4),
                    StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
        return Objects.requireNonNull(TestItConfig.load(configDir).defaults().container());
    }

    private @Nullable ContainerSpec resolved(String yaml) {
        return Container.resolve(
                block(yaml), null, request(ExecutionMode.CONTAINER), "r1", root.resolve("target/cherry-test/runs/r1"));
    }

    private ContainerSpec spec(String yaml) {
        return Objects.requireNonNull(resolved(yaml));
    }

    private String error(String yaml) {
        try {
            resolved(yaml);
        } catch (AnyhowError error) {
            return AnyhowError.alternate(error);
        }
        throw new AssertionError("expected an error for " + yaml);
    }

    @Test
    void local_execution_or_absent_block_resolves_nothing() {
        ContainerConfig config = block("image: alpine:3.20");
        assertThat(Container.resolve(config, null, request(ExecutionMode.LOCAL), "r1", root))
                .isNull();
        assertThat(Container.resolve(null, null, request(ExecutionMode.CONTAINER), "r1", root))
                .isNull();
    }

    @Test
    void resolves_defaults_for_a_present_block() {
        ContainerSpec spec = spec("image: alpine:3.20");
        assertThat(spec.name()).isEqualTo("ct-r1-shop-prices-unit");
        assertThat(spec.run()).isEqualTo("r1");
        assertThat(spec.job()).isEqualTo("shop/prices/unit");
        assertThat(spec.pull()).isEqualTo(PullPolicy.NEVER);
        assertThat(spec.network()).isEqualTo(NetworkMode.NONE);
        assertThat(spec.capDrop()).containsExactly("ALL");
        assertThat(spec.noNewPrivileges()).isTrue();
        assertThat(spec.readOnlyRoot()).isFalse();
        assertThat(spec.timeoutSecs()).isEqualTo(1800);
        assertThat(spec.workspace()).isEqualTo(WorkspaceAccess.RO);
        assertThat(spec.mountRoot()).isEqualTo(root);
        assertThat(spec.outputs())
                .containsExactly(root.resolve("target/cherry-test/runs/r1/reports/adapters/shop/prices/unit"));
        assertThat(spec.caches()).isEmpty();
        ContainerSpec maven = spec("image: maven:3.9.11-eclipse-temurin-25");
        assertThat(maven.caches()).containsExactly(new CacheVolume("cherry-test-polyglot-maven", "/root/.m2"));
    }

    @Test
    void image_falls_back_to_options_and_is_required() {
        AdapterRequest base = request(ExecutionMode.CONTAINER);
        TreeMap<String, JsonNode> options = new TreeMap<>(RustOrdering.STRING_ORDER);
        options.put("image", JsonNodeFactory.instance.stringNode("node:22-bookworm"));
        AdapterRequest withOption = new AdapterRequest(
                base.protocol(),
                base.project(),
                base.packageName(),
                base.phase(),
                base.root(),
                base.manifest(),
                base.cwd(),
                base.output(),
                base.execution(),
                base.coverage(),
                base.strict(),
                base.args(),
                base.env(),
                options);
        ContainerSpec spec =
                Objects.requireNonNull(Container.resolve(block("network: none"), null, withOption, "r1", root));
        assertThat(spec.image()).isEqualTo("node:22-bookworm");
        assertThat(error("network: none")).isEqualTo("container execution requires container.image or options.image");
    }

    @Test
    void writable_paths_are_per_job() {
        ContainerSpec spec = spec("image: alpine:3.20\nwritable: [target, {path: ./prices/build, seed: copy}]");
        Path jobs = root.resolve("target/cherry-test/runs/r1/jobs/shop/prices/unit/w");
        assertThat(spec.writable())
                .containsExactly(
                        new WritableMount(root.resolve("target"), jobs.resolve("target"), "/workspace/target", false),
                        new WritableMount(
                                root.resolve("prices/build"),
                                jobs.resolve("prices/build"),
                                "/workspace/prices/build",
                                true));
    }

    @Test
    void rejects_writable_paths_outside_the_workspace() {
        for (String path : List.of("../elsewhere", "/etc", "", "a/../../b", "c:d")) {
            assertThat(error("image: alpine:3.20\nwritable: [\"" + path + "\"]"))
                    .isEqualTo("container.writable: `" + path + "`: must be a path inside the workspace");
        }
    }

    @Test
    void default_mount_target_keeps_relative_position() {
        ContainerSpec spec = spec(
                "image: alpine:3.20\nmounts:\n  - {source: ../sibling}\n  - {source: docs, read_only: false}\n  - {source: ../data, target: /data/./set}");
        Path parent = Objects.requireNonNull(root.getParent());
        assertThat(spec.mounts())
                .containsExactly(
                        new BindMount(parent.resolve("sibling"), "/sibling", true),
                        new BindMount(root.resolve("docs"), "/workspace/docs", false),
                        new BindMount(parent.resolve("data"), "/data/set", true));
        assertThat(error("image: alpine:3.20\nmounts: [{source: ../../far}]"))
                .isEqualTo(
                        "container.mounts: source `../../far`: is more than one level above the workspace; set `target`");
    }

    @Test
    void rejects_unsafe_container_values() {
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("image: --privileged", "container.image: invalid image `--privileged`");
        cases.put("user: root;id", "container.user: `root;id` is not `host` or a user[:group] name or id");
        cases.put("cap_drop: [--all]", "container.cap_drop: `--all` is not a capability name");
        cases.put("memory: --privileged", "container.memory: `--privileged` is not a number or size");
        cases.put("tmpfs: [tmp]", "container.tmpfs: `tmp` is not an absolute container path");
        cases.put("caches: [{volume: -v, target: /c}]", "container.caches: `-v` is not a volume name");
        cases.put(
                "caches: [{volume: m2, target: relative}]",
                "container.caches: target `relative`: must be an absolute container path");
        cases.put("env: {pass: [A-B]}", "container.env: `A-B` is not a variable name");
        cases.forEach((yaml, expected) -> {
            String image = yaml.startsWith("image") ? "" : "image: alpine:3.20\n";
            assertThat(error(image + yaml)).isEqualTo(expected);
        });
    }

    @Test
    void sanitizes_container_names() {
        assertThat(Container.containerName("20261028-101500-abc123", "shop", "prices", "unit"))
                .isEqualTo("ct-20261028-101500-abc123-shop-prices-unit");
        assertThat(Container.containerName("r1", "web", "@scope/ui kit", "visual"))
                .isEqualTo("ct-r1-web--scope-ui-kit-visual");
        assertThat(Container.sanitizeName("ct-r1-café-é")).isEqualTo("ct-r1-caf---");
        assertThat(Container.sanitizeName("-leading")).isEqualTo("ct-leading");
        assertThat(Container.sanitizeName("a.b_c-d")).isEqualTo("a.b_c-d");
    }

    @Test
    void selects_containers_of_dead_runs() {
        String listing =
                "aaa\tr-old\t100\nbbb\tr-now\t100\nccc\tr-live\t200\nddd\tr-legacy\t\neee\tr-old\tnot-a-pid\n\n";
        assertThat(Container.staleContainers(listing, "r-now", pid -> pid == 200))
                .containsExactly("aaa");
    }

    @Test
    void prepare_seeds_writable_paths_once() throws IOException {
        Files.createDirectories(root.resolve("build/nested"));
        Files.writeString(root.resolve("build/nested/seed.txt"), "seed");
        ContainerSpec spec = spec("image: alpine:3.20\nwritable: [{path: build, seed: copy}, cache]");
        Container.prepare(spec);
        Path build = spec.writable().get(0).source();
        assertThat(Files.readString(build.resolve("nested/seed.txt"))).isEqualTo("seed");
        assertThat(root.resolve("cache")).isDirectory();
        assertThat(spec.writable().get(1).source()).isDirectory();
        Files.writeString(build.resolve("nested/seed.txt"), "changed by the job");
        Container.prepare(spec);
        assertThat(Files.readString(build.resolve("nested/seed.txt"))).isEqualTo("changed by the job");
        assertThat(Files.readString(root.resolve("build/nested/seed.txt"))).isEqualTo("seed");
    }

    private static String runIds(String runId) throws IOException {
        CommandOutput output = SystemCommand.of("docker")
                .args("ps", "-aq", "--filter", "label=" + Container.RUN_LABEL + "=" + runId)
                .output();
        assertThat(output.success()).isTrue();
        return output.stdoutLossy().strip();
    }

    private static String sh(AdapterRequest request, ContainerSpec spec, String script, String label)
            throws IOException {
        boolean ok = Container.run(request, spec, "sh", List.of("-c", script), label);
        assertThat(ok)
                .as(script + ": " + Files.readString(request.output().resolve(label + ".stderr")))
                .isTrue();
        return Files.readString(request.output().resolve(label + ".stdout"));
    }

    private AdapterRequest job(String phase, String yaml, String runId, Path runRoot) {
        AdapterRequest base = request(ExecutionMode.CONTAINER);
        AdapterRequest request = new AdapterRequest(
                base.protocol(),
                base.project(),
                base.packageName(),
                phase,
                base.root(),
                base.manifest(),
                root,
                runRoot.resolve("reports/adapters/shop/prices").resolve(phase),
                base.execution(),
                base.coverage(),
                base.strict(),
                base.args(),
                base.env(),
                base.options());
        return request.withContainer(Container.resolve(block(yaml), null, request, runId, runRoot));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "CHERRY_TEST_DOCKER_SMOKE", matches = "1")
    void smoke_runs_hardened_containers() throws IOException {
        String runId = "smoke-" + ProcessHandle.current().pid();
        Path runRoot = root.resolve("target/cherry-test/runs").resolve(runId);
        String yaml = "image: alpine:3.20\nwritable: [scratch]\ntimeout: 3s";
        AdapterRequest unit = job("unit", yaml, runId, runRoot);
        ContainerSpec spec = Objects.requireNonNull(unit.container());

        String network = sh(
                unit,
                spec,
                "echo routes=$(ip route | wc -l); if wget -q -T 3 -O /dev/null http://1.1.1.1; then echo online; else echo offline; fi",
                "network");
        assertThat(network).isEqualTo("routes=0\noffline\n");

        String denied = sh(unit, spec, "touch /workspace/probe 2>&1; echo status=$?", "workspace");
        assertThat(denied).contains("Read-only file system").endsWith("status=1\n");
        assertThat(root.resolve("probe")).doesNotExist();

        String output = unit.output().toString().replace(root.toString(), "/workspace");
        String written = sh(
                unit,
                spec,
                "echo unit > /workspace/scratch/job.txt && echo report > " + output
                        + "/report.txt && cat /workspace/scratch/job.txt",
                "writable");
        assertThat(written).isEqualTo("unit\n");
        assertThat(Files.readString(spec.writable().get(0).source().resolve("job.txt")))
                .isEqualTo("unit\n");
        assertThat(unit.output().resolve("report.txt")).isRegularFile();
        assertThat(root.resolve("scratch/job.txt")).doesNotExist();

        AdapterRequest integration = job("integration", yaml, runId, runRoot);
        ContainerSpec other = Objects.requireNonNull(integration.container());
        assertThat(other.writable().get(0).source())
                .isNotEqualTo(spec.writable().get(0).source());
        assertThat(sh(integration, other, "ls -A /workspace/scratch | wc -l", "fresh")
                        .strip())
                .isEqualTo("0");
        assertThat(runIds(runId)).isEmpty();

        long started = System.nanoTime();
        assertThatThrownBy(() -> Container.run(unit, spec, "sleep", List.of("60"), "timeout"))
                .isInstanceOfSatisfying(
                        AnyhowError.class,
                        error -> assertThat(AnyhowError.alternate(error)).contains("exceeded 3s"));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(25));
        assertThat(runIds(runId)).isEmpty();
        assertThat(Container.removeRun(runId)).isZero();
    }

    @Test
    void argv_matches_golden_cases() throws IOException {
        JsonNode cases =
                Json.parseBytes(Files.readAllBytes(Path.of("../../test/cherry-test/tests/golden/container-argv.json")));
        assertThat(cases.size()).isGreaterThanOrEqualTo(4);
        for (JsonNode golden : cases) {
            String name = golden.get("name").stringValue();
            AdapterRequest request = AdapterJson.parseRequest(
                    SerdeJsonWriter.compact(golden.get("request")).getBytes(StandardCharsets.UTF_8));
            assertThat(AdapterJson.requestCompact(request))
                    .as(name)
                    .isEqualTo(golden.get("request_json").stringValue());
            JsonNode call = golden.get("call");
            List<String> args = new ArrayList<>();
            call.get("args").forEach(argument -> args.add(argument.stringValue()));
            List<String> authNames = new ArrayList<>();
            call.get("auth_names").forEach(argument -> authNames.add(argument.stringValue()));
            JsonNode user = call.get("user");
            Container.ContainerCall containerCall = new Container.ContainerCall(
                    call.get("program").stringValue(),
                    args,
                    call.get("owner_pid").longValue(),
                    user == null || user.isNull() ? null : user.stringValue(),
                    authNames);
            List<String> argv = new ArrayList<>();
            golden.get("argv").forEach(argument -> argv.add(argument.stringValue()));
            assertThat(Container.containerArgv(Objects.requireNonNull(request.container()), request, containerCall))
                    .as(name)
                    .isEqualTo(argv);
        }
    }
}
