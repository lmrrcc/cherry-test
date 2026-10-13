package com.cherrytest.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MainTest {
    @Test
    void self_exec_class_path_resolves_relative_entries_against_the_launch_directory() {
        assertThat(Main.absoluteClassPath("java/cherry-test-cli/target/cherry-test-java.jar", Path.of("/work/repo")))
                .isEqualTo("/work/repo/java/cherry-test-cli/target/cherry-test-java.jar");
    }

    @Test
    void self_exec_class_path_keeps_absolute_entries_and_their_order() {
        assertThat(Main.absoluteClassPath("/opt/lib/a.jar:./b.jar", Path.of("/work")))
                .isEqualTo("/opt/lib/a.jar:/work/b.jar");
    }
}
