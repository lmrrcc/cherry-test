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
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ParallelRunIT {
    private static final String ADAPTER = """
            use std::io::Read;

            fn field(request: &str, name: &str) -> String {
                let marker = format!("\\"{name}\\":\\"");
                match request.find(&marker) {
                    Some(at) => request[at + marker.len()..].split('"').next().unwrap_or("").to_string(),
                    None => String::new(),
                }
            }

            fn main() {
                let mut request = String::new();
                std::io::stdin().read_to_string(&mut request).unwrap();
                let package = field(&request, "package");
                let phase = field(&request, "phase");
                let weight: u64 = package.rsplit('-').next().and_then(|n| n.parse().ok()).unwrap_or(1);
                std::thread::sleep(std::time::Duration::from_millis(weight * 40));
                let evidence = std::path::Path::new(&std::env::var("CHERRY_TEST_REPORT_DIR").unwrap()).join("evidence");
                std::fs::create_dir_all(&evidence).unwrap();
                std::fs::write(evidence.join(format!("{package}-{phase}.txt")), &package).unwrap();
                use std::io::Write;
                let mut shared = std::fs::OpenOptions::new()
                    .create(true)
                    .append(true)
                    .open(evidence.join("shared.jsonl"))
                    .unwrap();
                writeln!(shared, "{{\\"job\\":\\"{package}/{phase}\\"}}").unwrap();
                let failed = package == "b-fail-2" && phase == "unit";
                let status = if failed { "failed" } else { "passed" };
                println!(
                    "{{\\"protocol\\":1,\\"success\\":{},\\"tests\\":[{{\\"name\\":\\"{package}_{phase}\\",\\"status\\":\\"{status}\\",\\"duration_ms\\":1}}]}}",
                    !failed
                );
            }
            """;

    private record Member(String project, String name, String kinds, String extra) {}

    private static final List<Member> PACKAGES = List.of(
            new Member("a", "a-one-3", "[unit, integration]", ""),
            new Member("a", "a-two-1", "[unit]", ""),
            new Member("b", "b-fail-2", "[unit, functional]", ""),
            new Member(
                    "b",
                    "b-four-4",
                    "[unit, integration]",
                    "        exclusive: true\n        resources: {cpus: 2, memory: 2g}\n"),
            new Member("b", "b-five-1", "[unit, functional]", ""));

    private record Run(int exit, Path directory, String runId) {}

    private static void workspace(Path root) throws IOException, InterruptedException {
        Path source = root.resolve("adapter.rs");
        Files.writeString(source, ADAPTER, StandardCharsets.UTF_8);
        Path binary = root.resolve("adapter-bin");
        Process compiled = new ProcessBuilder("rustc", source.toString(), "-o", binary.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(compiled.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(compiled.waitFor()).as(output).isZero();
        StringBuilder yaml = new StringBuilder("defaults:\n  coverage: false\n  open_report: false\nadapters:\n"
                + "  fixture:\n    command: \"" + binary + "\"\n    args: []\n    timeout_secs: 60\nprojects:\n");
        for (String project : List.of("a", "b")) {
            yaml.append("  ")
                    .append(project)
                    .append(":\n    path: ")
                    .append(project)
                    .append("\n    packages:\n");
            for (Member entry : PACKAGES) {
                if (!entry.project().equals(project)) {
                    continue;
                }
                Path directory = root.resolve(project).resolve(entry.name());
                Files.createDirectories(directory);
                Files.writeString(directory.resolve("package.json"), "{}", StandardCharsets.UTF_8);
                yaml.append("      ")
                        .append(entry.name())
                        .append(":\n        adapter: fixture\n        manifest: ")
                        .append(project)
                        .append('/')
                        .append(entry.name())
                        .append("/package.json\n        kinds: ")
                        .append(entry.kinds())
                        .append("\n        functional: {adapter: fixture}\n")
                        .append(entry.extra());
            }
        }
        Files.writeString(root.resolve("cherry-test.yaml"), yaml, StandardCharsets.UTF_8);
    }

    private static Run run(Path root, String jobs) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(
                        "java",
                        "--enable-native-access=ALL-UNNAMED",
                        "-jar",
                        Objects.requireNonNull(System.getProperty("cherrytest.cliJar")),
                        "--root",
                        root.toString(),
                        "--no-open",
                        "--no-banner",
                        "--jobs",
                        jobs)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        String runId = stdout.lines()
                .filter(line -> line.startsWith("cherry-test run: "))
                .map(line -> line.substring("cherry-test run: ".length()).split(" ", -1)[0])
                .findFirst()
                .orElseThrow();
        return new Run(exit, root.resolve("target/cherry-test/runs").resolve(runId), runId);
    }

    private static List<Map.Entry<String, String>> files(Run run) throws IOException {
        List<Map.Entry<String, String>> files = new ArrayList<>();
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(run.directory())) {
            paths = walk.filter(Files::isRegularFile).sorted().toList();
        }
        for (Path path : paths) {
            String text;
            try {
                text = Files.readString(path, StandardCharsets.UTF_8);
            } catch (IOException binary) {
                text = "";
            }
            files.add(Map.entry(run.directory().relativize(path).toString(), text.replace(run.runId(), "RUN")));
        }
        return files;
    }

    @Test
    void jobs_one_and_four_produce_the_same_results(@TempDir Path base) throws IOException, InterruptedException {
        Path root = base.toRealPath();
        workspace(root);
        Run sequential = run(root, "1");
        Run parallel = run(root, "4");
        assertThat(sequential.exit()).isEqualTo(1);
        assertThat(parallel.exit()).isEqualTo(sequential.exit());
        List<Map.Entry<String, String>> sequentialFiles = files(sequential);
        assertThat(sequentialFiles).anyMatch(file -> file.getKey().equals("reports/index.html"));
        assertThat(sequentialFiles.stream()
                        .filter(file -> file.getKey().endsWith("result.json"))
                        .count())
                .isEqualTo(9);
        assertThat(files(parallel)).isEqualTo(sequentialFiles);
        String shared = sequentialFiles.stream()
                .filter(file -> file.getKey().equals("reports/evidence/shared.jsonl"))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow();
        assertThat(shared.lines().map(line -> line.replace("{\"job\":\"", "").replace("\"}", "")))
                .containsExactly(
                        "a-one-3/unit",
                        "a-two-1/unit",
                        "b-fail-2/unit",
                        "b-five-1/unit",
                        "b-four-4/unit",
                        "a-one-3/integration",
                        "b-four-4/integration",
                        "b-fail-2/functional",
                        "b-five-1/functional");
        assertThat(sequentialFiles).anyMatch(file -> file.getKey().equals("reports/evidence/b-four-4-integration.txt"));
        assertThat(sequentialFiles).noneMatch(file -> file.getKey().contains("/report/"));
        String request = sequentialFiles.stream()
                .filter(file -> file.getKey().equals("reports/adapters/a/a-one-3/unit/request.json"))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow();
        assertThat(request).contains("reports/adapters/a/a-one-3/unit/report\"");
        String durations = Files.readString(root.resolve("target/cherry-test/durations.json"), StandardCharsets.UTF_8);
        for (String key : List.of("a/a-one-3/integration", "b/b-four-4/unit", "b/b-fail-2/functional")) {
            assertThat(durations).contains("\"" + key + "\"");
        }
        assertThat(durations).contains("\"runs\": 2");
    }
}
