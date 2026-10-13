package com.cherrytest.cli.coverage;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoverageIgnoreTest {
    @Test
    void cargo_toml_pattern_matches_nested_path() {
        CoverageIgnore ignore = CoverageIgnore.fromPatterns(List.of("**/Cargo.toml"));
        assertThat(ignore.isIgnored("mini/mini-rs-lib-mcap/Cargo.toml")).isTrue();
        assertThat(ignore.isIgnored("mini/mini-rs-lib-mcap/src/lib.rs")).isFalse();
    }

    @Test
    void ignores_comments_and_blanks(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve(".coverageignore"), "# comment\n\n**/Cargo.toml\n", StandardCharsets.UTF_8);
        assertThat(CoverageIgnore.loadPatterns(directory.resolve(".coverageignore")))
                .containsExactly("**/Cargo.toml");
    }

    @Test
    void node_modules_pattern_matches_nested_dependency_paths() {
        CoverageIgnore ignore = CoverageIgnore.fromPatterns(List.of("**/node_modules/**"));
        assertThat(ignore.isIgnored("mini/mini-ts-lib-price/node_modules/foo/index.js"))
                .isTrue();
        assertThat(ignore.isIgnored("/Users/dev/cherry-test/mini/mini-ts-lib-price/node_modules/foo/index.js"))
                .isTrue();
        assertThat(ignore.isIgnored("mini/mini-ts-lib-price/src/displayAddressUsdt.ts"))
                .isFalse();
    }

    @Test
    void frontend_config_patterns_match_nested_paths() {
        CoverageIgnore ignore =
                CoverageIgnore.fromPatterns(List.of("**/package.json", "**/tsconfig.json", "**/vite.config.ts"));
        assertThat(ignore.isIgnored("mini/mini-ts-web-react/package.json")).isTrue();
        assertThat(ignore.isIgnored("mini/mini-ts-web-react/tsconfig.json")).isTrue();
        assertThat(ignore.isIgnored("mini/mini-ts-web-vue/vite.config.ts")).isTrue();
        assertThat(ignore.isIgnored("mini/mini-ts-web-react/src/App.tsx")).isFalse();
    }

    @Test
    void filter_paths_removes_ignored() {
        CoverageIgnore ignore = CoverageIgnore.fromPatterns(List.of("**/Cargo.toml"));
        assertThat(ignore.filterPaths(List.of("mini/mini-rs-lib-mcap/Cargo.toml", "mini/mini-rs-lib-mcap/src/lib.rs")))
                .containsExactly("mini/mini-rs-lib-mcap/src/lib.rs");
    }
}
