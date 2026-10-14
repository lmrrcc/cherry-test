package com.cherrytest.cli.adapters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cherrytest.common.adapter.AdapterRequest;
import com.cherrytest.common.adapter.AdapterResponse;
import com.cherrytest.common.adapter.Attachment;
import com.cherrytest.common.adapter.ExecutionMode;
import com.cherrytest.common.adapter.TestResult;
import com.cherrytest.common.adapter.TestStatus;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AdaptersTest {
    private static AdapterRequest request(Path root, Path cwd) {
        return new AdapterRequest(
                1,
                "demo",
                "prices",
                "unit",
                root,
                root.resolve("pom.xml"),
                cwd,
                root.resolve("output"),
                ExecutionMode.LOCAL,
                true,
                true,
                List.of(),
                new TreeMap<>(),
                new TreeMap<>());
    }

    @Nested
    class test_source {
        @TempDir
        Path temp;

        private AdapterRequest layout() throws IOException {
            Path file = temp.resolve("shop-rs-api/src/tests.rs");
            Files.createDirectories(Objects.requireNonNull(file.getParent()));
            Files.writeString(file, "fn t() {}\n", StandardCharsets.UTF_8);
            return request(temp, temp.resolve("shop-rs-api"));
        }

        @Test
        void resolves_a_path_relative_to_the_workspace() throws IOException {
            assertThat(Adapters.resolveTestSource(layout(), Path.of("shop-rs-api/src/tests.rs")))
                    .isEqualTo(temp.resolve("shop-rs-api/src/tests.rs"));
        }

        @Test
        void resolves_a_path_relative_to_the_package() throws IOException {
            assertThat(Adapters.resolveTestSource(layout(), Path.of("src/tests.rs")))
                    .isEqualTo(temp.resolve("shop-rs-api/src/tests.rs"));
        }

        @Test
        void drops_a_path_that_does_not_exist() throws IOException {
            assertThat(Adapters.resolveTestSource(layout(), Path.of("src/missing.rs")))
                    .isNull();
        }
    }

    @Test
    void rejects_non_measurements_and_truncated_coverage() {
        assertThatThrownBy(() -> Adapters.validateLcov("not a report")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> Adapters.validateLcov("SF:file.c\nDA:1,0\n")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> Adapters.validateLcov("SF:file.c\nDA:1,invalid\nend_of_record\n"))
                .isInstanceOf(RuntimeException.class);
        assertThatCode(() -> Adapters.validateLcov("SF:file.c\nDA:1,0\nend_of_record\n"))
                .doesNotThrowAnyException();
    }

    @Test
    void strict_mode_rejects_empty_tests_and_missing_coverage(@TempDir Path temp) {
        AdapterResponse response = AdapterResponse.empty();
        Adapters.validate(request(temp, temp), response);
        assertThat(response.success()).isFalse();
        assertThat(response.diagnostics()).hasSize(2);
    }

    @Test
    void failure_wins_over_claimed_success_and_duplicate_names_rejected(@TempDir Path temp) {
        AdapterRequest request = request(temp, temp);
        TestResult failed = Formats.testCase("broken", TestStatus.FAILED, null, 0);
        AdapterResponse response = AdapterResponse.empty();
        response.tests().add(failed);
        Adapters.validate(request, response);
        assertThat(response.success()).isFalse();
        response.tests().add(failed);
        assertThatThrownBy(() -> Adapters.validate(request, response)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void attachment_cannot_escape_output(@TempDir Path temp) throws IOException {
        AdapterRequest request = request(temp, temp);
        Files.createDirectory(request.output());
        Path outside = temp.resolve("secret.txt");
        Files.writeString(outside, "private", StandardCharsets.UTF_8);
        TestResult screen = Formats.testCase("screen", TestStatus.PASSED, null, 0);
        TestResult withAttachment = new TestResult(
                screen.name(),
                screen.status(),
                screen.durationMs(),
                screen.message(),
                screen.source(),
                screen.line(),
                List.of(new Attachment("image", outside, "image/png")));
        AdapterResponse response = AdapterResponse.empty();
        response.tests().add(withAttachment);
        assertThatThrownBy(() -> Adapters.validate(request, response)).isInstanceOf(RuntimeException.class);
    }

    @Nested
    class cargo_target_dir {
        private AdapterRequest request(ExecutionMode execution, String packageName, String phase) {
            Path root = Path.of("/work/mini");
            return new AdapterRequest(
                    1,
                    "mini",
                    packageName,
                    phase,
                    root,
                    root.resolve("mini-rs-api/Cargo.toml"),
                    root,
                    root.resolve("target/cherry-test/runs/20261014-000000-abcdef/reports/adapters/mini/" + packageName
                            + "/" + phase),
                    execution,
                    true,
                    false,
                    List.of(),
                    new TreeMap<>(),
                    new TreeMap<>());
        }

        @Test
        void is_shared_by_runs_packages_and_phases() {
            assertThat(Builtins.cargoTargetDir(request(ExecutionMode.LOCAL, "mini-rs-api", "unit")))
                    .isEqualTo(
                            Builtins.cargoTargetDir(request(ExecutionMode.LOCAL, "mini-rs-api-okta", "integration")));
        }

        @Test
        void keeps_host_and_container_builds_apart() {
            assertThat(Builtins.cargoTargetDir(request(ExecutionMode.LOCAL, "mini-rs-api", "unit")))
                    .isEqualTo(Path.of("/work/mini/target/cherry-test/cargo/local"));
            assertThat(Builtins.cargoTargetDir(request(ExecutionMode.CONTAINER, "mini-rs-api", "unit")))
                    .isEqualTo(Path.of("/work/mini/target/cherry-test/cargo/container"));
        }
    }
}
