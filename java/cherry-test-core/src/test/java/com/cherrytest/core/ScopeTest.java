package com.cherrytest.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScopeTest {
    @TempDir
    Path root;

    private TestItConfig config;

    @BeforeEach
    void sample_config() throws IOException {
        Files.writeString(
                root.resolve("cherry-test.yaml"),
                "defaults:\n  scope: global\nprojects:\n  mini: {}\nscopes:\n  mini_wallet:\n    projects: [mini]\n",
                StandardCharsets.UTF_8);
        config = TestItConfig.load(root);
    }

    @Test
    void global_synonyms_resolve_all_projects() {
        for (String name : new String[] {"workspace", "all", "global"}) {
            assertThat(Scope.resolveProjects(config, name)).hasSize(1);
        }
    }

    @Test
    void project_scope_resolves_single_project() {
        assertThat(Scope.resolveProjects(config, "mini")).containsExactly("mini");
    }
}
