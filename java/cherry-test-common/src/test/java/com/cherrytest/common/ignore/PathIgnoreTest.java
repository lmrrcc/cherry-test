package com.cherrytest.common.ignore;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PathIgnoreTest {
    @Test
    void cargo_toml_pattern_matches_nested_path() {
        assertThat(PathIgnore.isPathIgnored("mini/mini-rs-lib-mcap/Cargo.toml", List.of("**/Cargo.toml")))
                .isTrue();
        assertThat(PathIgnore.isPathIgnored("mini/mini-rs-lib-mcap/src/lib.rs", List.of("**/Cargo.toml")))
                .isFalse();
    }
}
