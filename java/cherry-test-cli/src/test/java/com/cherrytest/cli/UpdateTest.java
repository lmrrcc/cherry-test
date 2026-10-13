package com.cherrytest.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class UpdateTest {
    @Test
    void cargo_install_root_from_bin_layout() {
        assertThat(Update.installRootFromJar(Path.of("/home/user/.local/lib/cherry-test-java.jar")))
                .isEqualTo(Path.of("/home/user/.local"));
    }

    @Test
    void cargo_install_root_from_flat_layout() {
        assertThat(Update.installRootFromJar(Path.of("/opt/cherry-test-java.jar")))
                .isEqualTo(Path.of("/opt"));
    }

    @Test
    void install_targets_include_standalone_and_cargo_commands() {
        assertThat(Update.LAUNCHER_NAME).isEqualTo("cherry-test-java");
        assertThat(Update.JAR_NAME).isEqualTo("cherry-test-java.jar");
        assertThat(Update.launcherScript(Path.of("/opt/lib/cherry-test-java.jar")))
                .isEqualTo(
                        "#!/bin/sh\nexec java --enable-native-access=ALL-UNNAMED -jar \"/opt/lib/cherry-test-java.jar\" \"$@\"\n");
    }
}
