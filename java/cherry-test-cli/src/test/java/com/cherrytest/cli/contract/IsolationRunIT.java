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
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class IsolationRunIT {
    private static final String ADAPTER = """
            use std::io::Read;
            use std::path::Path;

            fn field(request: &str, name: &str) -> String {
                let marker = format!("\\"{name}\\":\\"");
                match request.find(&marker) {
                    Some(at) => request[at + marker.len()..].split('"').next().unwrap_or("").to_string(),
                    None => String::new(),
                }
            }

            fn selected(request: &str) -> Option<Vec<String>> {
                let at = request.find("\\"select\\":[")? + "\\"select\\":[".len();
                let list = request[at..].split(']').next().unwrap_or("");
                Some(list.split(',').map(|name| name.trim().trim_matches('"').to_string()).collect())
            }

            fn main() {
                let mut request = String::new();
                std::io::stdin().read_to_string(&mut request).unwrap();
                let package = field(&request, "package");
                let output = field(&request, "output");
                let selected = selected(&request);
                let wants = |name: &str| selected.as_ref().map_or(true, |names| names.iter().any(|n| n == name));
                std::fs::create_dir_all(&output).unwrap();
                let marker = Path::new(&output).join("shared-marker");
                let _ = std::fs::remove_file(&marker);
                let mut tests: Vec<(&str, bool)> = Vec::new();
                if package == "order" {
                    if wants("a_writes_marker") {
                        std::fs::write(&marker, "a ran first").unwrap();
                        tests.push(("a_writes_marker", true));
                    }
                    if wants("b_needs_a_clean_slate") {
                        tests.push(("b_needs_a_clean_slate", !marker.exists()));
                    }
                    if wants("c_always_fails") {
                        tests.push(("c_always_fails", false));
                    }
                    if wants("d_alternates") {
                        let counter = Path::new(&std::env::var("CHERRY_TEST_RUN_DIR").unwrap()).join("d-counter");
                        let count: u32 = std::fs::read_to_string(&counter).ok().and_then(|text| text.trim().parse().ok()).unwrap_or(0) + 1;
                        std::fs::write(&counter, count.to_string()).unwrap();
                        tests.push(("d_alternates", count % 2 == 0));
                    }
                } else {
                    tests.push(("e_fails_without_isolate", false));
                }
                let results: Vec<String> = tests
                    .iter()
                    .map(|(name, passed)| format!("{{\\"name\\":\\"{name}\\",\\"status\\":\\"{}\\",\\"duration_ms\\":1}}", if *passed { "passed" } else { "failed" }))
                    .collect();
                println!(
                    "{{\\"protocol\\":1,\\"success\\":{},\\"tests\\":[{}]}}",
                    tests.iter().all(|(_, passed)| *passed),
                    results.join(",")
                );
            }
""";

    private record Run(int exit, String stdout, Path directory) {}

    private static void workspace(Path root) throws IOException, InterruptedException {
        Path source = root.resolve("adapter.rs");
        Files.writeString(source, ADAPTER, StandardCharsets.UTF_8);
        Path binary = root.resolve("adapter-bin");
        Process compiled = new ProcessBuilder("rustc", source.toString(), "-o", binary.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(compiled.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(compiled.waitFor()).as(output).isZero();
        StringBuilder yaml = new StringBuilder("""
                defaults:
                  coverage: false
                  open_report: false
                adapters:
                  fixture:
                    command: "%1$s"
                    args: []
                    timeout_secs: 60
                    isolate: true
                  plain:
                    command: "%1$s"
                    args: []
                    timeout_secs: 60
                projects:
                  a:
                    path: a
                    packages:
                """.formatted(binary));
        for (List<String> member : List.of(List.of("order", "fixture"), List.of("plain", "plain"))) {
            String name = member.get(0);
            Path directory = Files.createDirectories(root.resolve("a").resolve(name));
            Files.writeString(directory.resolve("package.json"), "{}", StandardCharsets.UTF_8);
            yaml.append("""
                          %1$s:
                            adapter: %2$s
                            manifest: a/%1$s/package.json
                            kinds: [unit]
                    """.formatted(name, member.get(1)));
        }
        Files.writeString(root.resolve("cherry-test.yaml"), yaml, StandardCharsets.UTF_8);
    }

    private static Run run(Path root, @Nullable String flag) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(
                "java",
                "--enable-native-access=ALL-UNNAMED",
                "-jar",
                Objects.requireNonNull(System.getProperty("cherrytest.cliJar")),
                "--root",
                root.toString(),
                "--no-open",
                "--no-banner"));
        if (flag != null) {
            command.add(flag);
        }
        Process process = new ProcessBuilder(command)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        String runId = stdout.lines()
                .filter(line -> line.startsWith("cherry-test run: "))
                .map(line -> line.substring("cherry-test run: ".length()).split(" ", -1)[0])
                .findFirst()
                .orElseThrow();
        return new Run(exit, stdout, root.resolve("target/cherry-test/runs").resolve(runId));
    }

    private static JsonNode json(Path path) throws IOException {
        return JsonMapper.builder().build().readTree(Files.readString(path, StandardCharsets.UTF_8));
    }

    private static List<Map.Entry<String, String>> statuses(Run run, String packageName) throws IOException {
        JsonNode document = json(run.directory().resolve("reports/adapters/a/" + packageName + "/unit/result.json"));
        List<Map.Entry<String, String>> statuses = new ArrayList<>();
        for (JsonNode test : document.get("result").get("tests")) {
            statuses.add(
                    Map.entry(test.get("name").stringValue(), test.get("status").stringValue()));
        }
        return statuses;
    }

    @Test
    void isolation_classifies_interference_real_and_flaky_failures(@TempDir Path temp)
            throws IOException, InterruptedException {
        Path root = temp.toRealPath();
        workspace(root);

        Run plain = run(root, null);
        assertThat(plain.exit()).isEqualTo(1);
        assertThat(plain.stdout()).doesNotContain("==> isolate");
        assertThat(plain.directory().resolve("reports/isolation.json")).doesNotExist();
        assertThat(plain.directory().resolve("reports/isolation")).doesNotExist();

        Run isolated = run(root, "--isolate");
        assertThat(isolated.exit()).as(isolated.stdout()).isEqualTo(plain.exit());
        for (String packageName : List.of("order", "plain")) {
            assertThat(statuses(isolated, packageName)).isEqualTo(statuses(plain, packageName));
        }
        assertThat(statuses(isolated, "order"))
                .containsExactly(
                        Map.entry("a_writes_marker", "passed"),
                        Map.entry("b_needs_a_clean_slate", "failed"),
                        Map.entry("c_always_fails", "failed"),
                        Map.entry("d_alternates", "failed"));
        List<String> lines = isolated.stdout().lines().toList();
        for (String line : List.of(
                "==> isolate 4 failed tests: each alone, 3 runs",
                "  INTERFERENCE  a/order/unit  b_needs_a_clean_slate  3/3 passed alone",
                "  REAL          a/order/unit  c_always_fails  0/3 passed alone",
                "  FLAKY         a/order/unit  d_alternates  2/3 passed alone",
                "  INCONCLUSIVE  a/plain/unit  e_fails_without_isolate  adapter `plain` does not declare `isolate: true`")) {
            assertThat(lines).as(isolated.stdout()).contains(line);
        }

        JsonNode report = json(isolated.directory().resolve("reports/isolation.json"));
        assertThat(report.get("failed").longValue()).isEqualTo(4);
        List<String> classes = new ArrayList<>();
        for (JsonNode test : report.get("tests")) {
            classes.add(test.get("test").stringValue() + " " + test.get("class").stringValue() + " "
                    + test.get("passed").longValue());
        }
        assertThat(classes)
                .containsExactly(
                        "b_needs_a_clean_slate INTERFERENCE 3",
                        "c_always_fails REAL 0",
                        "d_alternates FLAKY 2",
                        "e_fails_without_isolate INCONCLUSIVE 0");
        Path firstRerun = isolated.directory().resolve("reports/isolation/a/order/unit/1/1");
        String request = Files.readString(firstRerun.resolve("request.json"), StandardCharsets.UTF_8);
        assertThat(request).contains("\"select\":[\"b_needs_a_clean_slate\"]");
        assertThat(request).contains("\"coverage\":false");
        assertThat(firstRerun.resolve("result.json")).isRegularFile();

        String result = Files.readString(
                isolated.directory().resolve("reports/adapters/a/order/unit/result.json"), StandardCharsets.UTF_8);
        assertThat(result).contains("\"class\": \"INTERFERENCE\"");
        assertThat(result.split("\"isolation\"", -1)).hasSize(4);

        String index = Files.readString(isolated.directory().resolve("reports/index.html"), StandardCharsets.UTF_8);
        assertThat(index)
                .contains("<a href=\"phases.html#isolation\">Failed tests rerun alone: 1 REAL, 1 INTERFERENCE, 1 FLAKY,"
                        + " 1 INCONCLUSIVE</a>");
        String phases = Files.readString(isolated.directory().resolve("reports/phases.html"), StandardCharsets.UTF_8);
        assertThat(phases).contains("<section id=\"isolation\">");
        assertThat(phases).contains("<strong class=\"isolation neutral\">INTERFERENCE</strong>");
        long rows;
        try (Stream<Path> walk = Files.walk(isolated.directory().resolve("reports/adapters"))) {
            rows = walk.filter(path -> path.getFileName().toString().equals("result.json"))
                    .count();
        }
        assertThat(rows).isEqualTo(2);
    }
}
