package com.cherrytest.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cherrytest.common.container.ContainerConfig;
import com.cherrytest.common.container.ContainerEnvConfig;
import com.cherrytest.common.container.Durations;
import com.cherrytest.common.container.NetworkMode;
import com.cherrytest.common.container.SeedMode;
import com.cherrytest.common.container.WritableConfig;
import com.cherrytest.common.rustcompat.AnyhowError;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/** Twin of the tests in {@code test/cherry-test-common/src/container.rs}; YAML parsing lives in core here. */
class ContainerConfigTest {
    private static ContainerConfig block(String yaml) {
        Map<String, Object> raw = TestItConfig.parseRaw(Path.of("cherry-test.yaml"), "container:\n" + yaml.indent(2));
        return Objects.requireNonNull(ContainerYaml.parse(raw.get("container"), "container"));
    }

    @Test
    void package_overrides_defaults_field_by_field() {
        ContainerConfig defaults = block(
                "image: alpine:3.20\nnetwork: bridge\ntimeout: 30m\ntmpfs: [/tmp]\nenv:\n  pass: [HOME_TOKEN]\n  set: {A: one}\n");
        ContainerConfig pkg = block("network: none\ntmpfs: [/run]\nenv:\n  set: {B: two}\n");
        ContainerConfig merged = Objects.requireNonNull(ContainerConfig.merged(defaults, pkg));
        assertThat(merged.image()).isEqualTo("alpine:3.20");
        assertThat(merged.network()).isEqualTo(NetworkMode.NONE);
        assertThat(merged.timeout()).isEqualTo(1800L);
        assertThat(merged.tmpfs()).containsExactly("/run");
        ContainerEnvConfig env = Objects.requireNonNull(merged.env());
        assertThat(env.pass()).containsExactly("HOME_TOKEN");
        assertThat(env.set()).containsExactlyEntriesOf(Map.of("B", "two"));
        assertThat(ContainerConfig.merged(null, pkg)).isEqualTo(pkg);
        assertThat(ContainerConfig.merged(null, null)).isNull();
    }

    @Test
    void parses_durations() {
        assertThat(Durations.parse("90")).isEqualTo(90);
        assertThat(Durations.parse("1800s")).isEqualTo(1800);
        assertThat(Durations.parse("30m")).isEqualTo(1800);
        assertThat(Durations.parse("2h")).isEqualTo(7200);
        assertThat(block("timeout: 45").timeout()).isEqualTo(45L);
    }

    @Test
    void rejects_invalid_durations() {
        for (String text :
                List.of("", "0", "0s", "m", "1.5h", "-5s", "10d", "99999999999999999999h", "9223372036854775808")) {
            assertThatThrownBy(() -> Durations.parse(text))
                    .isInstanceOf(AnyhowError.class)
                    .hasMessage("invalid duration `" + text
                            + "`; use a positive number of seconds or a value such as 1800s, 30m or 2h");
        }
    }

    @Test
    void parses_writable_entries_and_quantities() {
        ContainerConfig config =
                block("writable: [target, {path: build, seed: copy}]\ncpus: 1.5\nmemory: 2g\nshm_size: 268435456\n");
        assertThat(config.writable())
                .containsExactly(new WritableConfig("target", null), new WritableConfig("build", SeedMode.COPY));
        assertThat(config.cpus()).isEqualTo("1.5");
        assertThat(config.memory()).isEqualTo("2g");
        assertThat(config.shmSize()).isEqualTo("268435456");
    }
}
