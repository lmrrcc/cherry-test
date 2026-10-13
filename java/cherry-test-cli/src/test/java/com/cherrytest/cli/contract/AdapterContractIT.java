package com.cherrytest.cli.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AdapterContractIT {
    private record Outcome(int exit, String stderr) {
        boolean success() {
            return exit == 0;
        }
    }

    private static Path compileFixture(Path root, String source) throws IOException, InterruptedException {
        Path sourcePath = root.resolve("suite.rs");
        Files.writeString(sourcePath, source, StandardCharsets.UTF_8);
        Path binary = root.resolve("suite-bin");
        Process compiled = new ProcessBuilder("rustc", sourcePath.toString(), "-o", binary.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(compiled.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(compiled.waitFor()).as(output).isZero();
        return binary;
    }

    private static Outcome execute(Path root, String source, boolean coverage)
            throws IOException, InterruptedException {
        Path binary = compileFixture(root, source);
        Files.writeString(
                root.resolve("cherry-test.yaml"),
                "defaults:\n  strict: true\n  coverage: true\nadapters:\n  fixture:\n    command: \"" + binary
                        + "\"\n    args: []\n    timeout_secs: 1\nprojects:\n  demo:\n    path: .\n    packages:\n"
                        + "      fixture:\n        adapter: fixture\n        manifest: suite.rs\n        kinds: [unit]\n",
                StandardCharsets.UTF_8);
        List<String> command = new ArrayList<>(List.of(
                "java",
                "--enable-native-access=ALL-UNNAMED",
                "-jar",
                Objects.requireNonNull(System.getProperty("cherrytest.cliJar")),
                "--root",
                root.toString(),
                "--no-open",
                "--quiet"));
        if (!coverage) {
            command.add("--no-coverage");
        }
        Process process = new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Outcome(process.waitFor(), stderr);
    }

    private static String jsonProgram(String response, @Nullable Integer exitCode) {
        StringBuilder source =
                new StringBuilder("fn main() {\n    let body = r#\"" + response + "\"#;\n    println!(\"{body}\");\n");
        if (exitCode != null) {
            source.append("    std::process::exit(").append(exitCode).append(");\n");
        }
        return source.append("}\n").toString();
    }

    @Test
    void success_failure_and_malformed_plugin_results_reach_cli_exit_status(@TempDir Path base)
            throws IOException, InterruptedException {
        Map<String, Boolean> cases = Map.of(
                "{\"protocol\":1,\"success\":true,\"tests\":[{\"name\":\"case\",\"status\":\"passed\",\"duration_ms\":0}]}",
                true,
                "{\"protocol\":1,\"success\":true,\"tests\":[{\"name\":\"case\",\"status\":\"failed\",\"duration_ms\":0}]}",
                false,
                "{\"protocol\":1,\"success\":true,\"tests\":[{\"name\":\"case\",\"status\":\"skipped\",\"duration_ms\":0}]}",
                false,
                "{\"protocol\":1,\"success\":true,\"tests\":[]}",
                false,
                "invalid JSON",
                false);
        int index = 0;
        for (Map.Entry<String, Boolean> entry : cases.entrySet()) {
            Path directory = Files.createDirectory(base.resolve("case" + index++));
            Outcome result = execute(directory, jsonProgram(entry.getKey(), null), false);
            assertThat(result.success()).as(result.stderr()).isEqualTo(entry.getValue());
            assertThat(directory.resolve("target/cherry-test/latest/reports/index.html"))
                    .isRegularFile();
        }
    }

    @Test
    void missing_coverage_and_nonzero_adapter_exit_cannot_pass(@TempDir Path base)
            throws IOException, InterruptedException {
        String response =
                "{\"protocol\":1,\"success\":true,\"tests\":[{\"name\":\"case\",\"status\":\"passed\",\"duration_ms\":0}]}";
        Path withCoverage = Files.createDirectory(base.resolve("coverage"));
        assertThat(execute(withCoverage, jsonProgram(response, null), true).success())
                .isFalse();
        Path withExit = Files.createDirectory(base.resolve("exit"));
        assertThat(execute(withExit, jsonProgram(response, 3), false).success()).isFalse();
    }

    @Test
    void timeout_is_recorded_as_a_failed_phase(@TempDir Path directory) throws IOException, InterruptedException {
        Outcome result = execute(
                directory, "fn main() {\n    std::thread::sleep(std::time::Duration::from_secs(10));\n}\n", false);
        assertThat(result.success()).isFalse();
        String report = Files.readString(
                directory.resolve("target/cherry-test/latest/reports/adapters/demo/fixture/unit/result.json"),
                StandardCharsets.UTF_8);
        assertThat(report).contains("exceeded 1s");
    }
}
