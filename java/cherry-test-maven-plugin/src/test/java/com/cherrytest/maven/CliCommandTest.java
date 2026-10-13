package com.cherrytest.maven;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class CliCommandTest {
    @Test
    void forks_the_cli_main_class_with_the_plugin_classpath_and_native_access() {
        assertThat(CliCommand.of(
                        Path.of("/jdk/bin/java"),
                        List.of(Path.of("/m2/cherry-test-cli.jar"), Path.of("/m2/jackson.jar")),
                        "mini --no-open"))
                .containsExactly(
                        "/jdk/bin/java",
                        "--enable-native-access=ALL-UNNAMED",
                        "-cp",
                        "/m2/cherry-test-cli.jar" + File.pathSeparator + "/m2/jackson.jar",
                        "com.cherrytest.cli.Main",
                        "mini",
                        "--no-open");
    }

    @Test
    void splits_arguments_on_whitespace_like_a_shell_without_quotes() {
        assertThat(CliCommand.arguments("  --root  app/shop\t--no-open "))
                .containsExactly("--root", "app/shop", "--no-open");
        assertThat(CliCommand.arguments("")).isEmpty();
    }
}
