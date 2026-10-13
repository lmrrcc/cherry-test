package com.cherrytest.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.cherrytest.core.Defaults;
import com.cherrytest.core.PackageConfig;
import com.cherrytest.core.PackageRuntime;
import com.cherrytest.core.ProjectConfig;
import com.cherrytest.core.TestItConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScaffoldTest {
    @TempDir
    Path root;

    private void write(String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(Objects.requireNonNull(file.getParent()));
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static ProjectConfig project(TestItConfig config, String id) {
        return Objects.requireNonNull(config.projects().get(id));
    }

    @Test
    void scaffolds_mini_like_layout() throws IOException {
        Files.createDirectories(root.resolve("mini/mini-rs-api/tests"));
        Files.createDirectories(root.resolve("mini/mini-ts-lib-price"));
        Files.createDirectories(root.resolve("mini/mini-rs-lib-mcap"));
        write("Cargo.toml", "[workspace]\nmembers = [\"mini/mini-rs-api\", \"mini/mini-rs-lib-mcap\"]\n");
        write("mini/mini-rs-api/Cargo.toml", "[package]\nname = \"mini-rs-api\"\n");
        write("mini/mini-rs-api/tests/integration.rs", """
                #[cherry_test(scope = "mini_wallet")]
                async fn mini_project_wallet_suite_ping_test(env: TestEnv) {
                    env.project("mini").service("api")?;
                    env.project("mini").service("mcap")?;
                }
                """);
        write(
                "mini/mini-ts-lib-price/package.json",
                "{\"name\":\"mini-ts-lib-price\",\"devDependencies\":{\"vitest\":\"2.1.9\"}}");
        write("mini/mini-rs-lib-mcap/Cargo.toml", "[package]\nname = \"mini-rs-lib-mcap\"\n");
        write("mini/docker-compose.test.yml", """
                services:
                  mini-db:
                    image: postgres:16-alpine
                  mini-rs-lib-mcap:
                    build:
                      context: ..
                    ports:
                      - "3001"
                    healthcheck:
                      test: ["CMD", "wget", "-q", "-O", "-", "http://127.0.0.1:3001/health"]
                  mini-rs-api:
                    build:
                      context: ..
                    ports:
                      - "3000"
                    healthcheck:
                      test: ["CMD", "wget", "-q", "-O", "-", "http://127.0.0.1:3000/health"]
                """);
        write("pnpm-workspace.yaml", "packages:\n  - mini/*\n");
        TestItConfig config = Scaffold.scaffoldConfig(root);
        assertThat(config.projects()).containsKey("mini").doesNotContainKey("mcap");
        ProjectConfig mini = project(config, "mini");
        assertThat(mini.packages()).containsKeys("mini-rs-api", "mini-rs-lib-mcap", "mini-ts-lib-price");
        assertThat(mini.path()).isEqualTo("mini");
        assertThat(mini.compose()).isEqualTo("mini/docker-compose.test.yml");
        assertThat(mini.services()).containsKeys("api", "db");
        assertThat(config.scopes()).containsKey("mini_wallet");
        assertThat(Objects.requireNonNull(config.scopes().get("mini_wallet")).projects())
                .containsExactly("mini");
    }

    @Test
    void scaffolds_jvm_and_native_packages_without_a_cargo_workspace() throws IOException {
        write(
                "java/pom.xml",
                "<project><parent><artifactId>parent</artifactId></parent><artifactId>sample-java</artifactId></project>");
        write("kotlin/build.gradle.kts", "plugins { kotlin(\"jvm\") }");
        write("native/CMakeLists.txt", "project(sample-native LANGUAGES C CXX)");
        write("kotlin/settings.gradle.kts", "rootProject.name = \"sample-kotlin\"");
        ProjectConfig sample = project(Scaffold.scaffoldConfig(root), "sample");
        for (Map.Entry<String, String> expected : Map.of(
                        "sample-java", "maven", "sample-kotlin", "gradle", "sample-native", "cmake")
                .entrySet()) {
            PackageConfig config = Objects.requireNonNull(sample.packages().get(expected.getKey()));
            assertThat(config.adapter()).isEqualTo(expected.getValue());
            assertThat(config.kinds()).containsExactly("unit");
        }
    }

    @Test
    void scaffolds_node_only_repo() throws IOException {
        write(
                "app/my-lib/package.json",
                "{\"name\":\"app-ts-lib-core\",\"scripts\":{\"test\":\"vitest run\"},\"devDependencies\":{\"vitest\":\"2.1.9\"}}");
        TestItConfig config = Scaffold.scaffoldConfig(root);
        assertThat(config.projects()).hasSize(1).containsKey("app");
    }

    @Test
    void scaffolds_flat_mini_at_root() throws IOException {
        Files.createDirectories(root.resolve("mini-rs-api/tests"));
        Files.createDirectories(root.resolve("mini-ts-lib-price"));
        Files.createDirectories(root.resolve("mini-rs-lib-mcap"));
        write("mini-rs-api/Cargo.toml", "[package]\nname = \"mini-rs-api\"\n");
        write("mini-rs-lib-mcap/Cargo.toml", "[package]\nname = \"mini-rs-lib-mcap\"\n");
        write(
                "mini-ts-lib-price/package.json",
                "{\"name\":\"mini-ts-lib-price\",\"devDependencies\":{\"vitest\":\"2.1.9\"}}");
        write("docker-compose.test.yml", """
                services:
                  mini-rs-api:
                    build:
                      context: .
                    ports:
                      - "3000"
                    healthcheck:
                      test: ["CMD", "wget", "-q", "-O", "-", "http://127.0.0.1:3000/health"]
                """);
        write("pnpm-workspace.yaml", "packages:\n  - '*'\n");
        TestItConfig config = Scaffold.scaffoldConfig(root);
        assertThat(config.projects()).containsKey("mini");
        ProjectConfig mini = project(config, "mini");
        assertThat(mini.path()).isEqualTo(".");
        assertThat(mini.compose()).isEqualTo("docker-compose.test.yml");
        assertThat(mini.packages()).containsKey("mini-rs-api");
    }

    @Test
    void config_needs_scaffold_when_project_path_missing() throws IOException {
        write("cherry-test.yaml", "projects: {}\n");
        assertThat(Scaffold.configNeedsScaffold(TestItConfig.load(root), root)).isTrue();
        PackageConfig api = new PackageConfig(
                null,
                null,
                new TreeMap<>(),
                null,
                null,
                PackageRuntime.RUST,
                List.of(),
                Path.of("mini-rs-api/Cargo.toml"),
                null,
                null,
                null,
                null);
        ProjectConfig mini =
                ProjectConfig.empty().withPath("mini").withPackages(new TreeMap<>(Map.of("mini-rs-api", api)));
        TestItConfig config = new TestItConfig(
                new TreeMap<>(),
                root,
                root.resolve("cherry-test.yaml"),
                Defaults.standard(),
                new TreeMap<>(),
                new TreeMap<>(Map.of("mini", mini)),
                new TreeMap<>());
        assertThat(Scaffold.configNeedsScaffold(config, root)).isTrue();
        write("mini-rs-api/Cargo.toml", "[package]\nname = \"mini-rs-api\"\n");
        TestItConfig fixed = new TestItConfig(
                new TreeMap<>(),
                root,
                root.resolve("cherry-test.yaml"),
                Defaults.standard(),
                new TreeMap<>(),
                new TreeMap<>(Map.of("mini", mini.withPath("."))),
                new TreeMap<>());
        assertThat(Scaffold.configNeedsScaffold(fixed, root)).isFalse();
    }
}
