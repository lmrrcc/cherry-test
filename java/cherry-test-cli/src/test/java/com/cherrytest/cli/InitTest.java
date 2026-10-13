package com.cherrytest.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cherrytest.core.TestItConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InitTest {
    private static final String CONFIG =
            "\nprojects:\n  mini:\n    path: mini\n    packages:\n      mini-rs-api:\n        kinds: [unit]\n";

    @TempDir
    Path root;

    @Test
    void resolve_projects_from_yaml_fixture() throws IOException {
        Files.createDirectories(root.resolve("mini/mini-rs-api"));
        Files.writeString(root.resolve("Cargo.toml"), "[workspace]\nmembers = []\n", StandardCharsets.UTF_8);
        Files.writeString(
                root.resolve("mini/mini-rs-api/Cargo.toml"),
                "[package]\nname = \"mini-rs-api\"\n",
                StandardCharsets.UTF_8);
        Files.writeString(root.resolve("cherry-test.yaml"), CONFIG, StandardCharsets.UTF_8);
        List<ProjectGroup> projects =
                Init.resolveProjectsFromConfig(root, TestItConfig.load(root), new TreeSet<>(List.of("mini-rs-api")));
        assertThat(projects).hasSize(1);
        assertThat(projects.getFirst().members().getFirst().name()).isEqualTo("mini-rs-api");
    }

    @Test
    void missing_manifest_errors() throws IOException {
        Files.createDirectories(root.resolve("mini/mini-rs-api"));
        Files.writeString(root.resolve("cherry-test.yaml"), CONFIG, StandardCharsets.UTF_8);
        TestItConfig config = TestItConfig.load(root);
        assertThatThrownBy(() -> Init.resolveProjectsFromConfig(root, config, new TreeSet<>(List.of("mini-rs-api"))))
                .hasMessageContaining("manifest");
    }
}
