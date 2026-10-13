package com.cherrytest.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class CommandLineTest {
    private static RunCli run(String... arguments) {
        CommandLine.Parsed parsed = CommandLine.parse(List.of(arguments));
        assertThat(parsed).isInstanceOf(CommandLine.Run.class);
        return ((CommandLine.Run) parsed).cli();
    }

    @Test
    void cli_parses_filter() {
        assertThat(run("mini").filter()).isEqualTo("mini");
        assertThat(run("mini").filter()).isEqualTo("mini");
    }

    @Test
    void cli_name_is_cherry_test() {
        assertThat(CommandLine.helpText()).contains("Usage: cherry-test [OPTIONS]");
    }

    @Test
    void cli_parses_root_and_init_only() {
        RunCli cli = run("--root", "/tmp/repo", "--init-only");
        assertThat(cli.root()).isEqualTo(Path.of("/tmp/repo"));
        assertThat(cli.initOnly()).isTrue();
    }

    @Test
    void cli_hides_banner_when_asked() {
        assertThat(CherryTest.showBanner(run("mini"))).isTrue();
        RunCli hidden = run("--no-banner");
        assertThat(hidden.noBanner()).isTrue();
        assertThat(CherryTest.showBanner(hidden)).isFalse();
        assertThat(CherryTest.showBanner(run("--quiet"))).isFalse();
    }

    @Test
    void banner_fits_a_terminal_line() throws IOException {
        List<String> lines = Banner.text().lines().toList();
        assertThat(lines).hasSize(16);
        assertThat(lines.subList(0, 14)).allMatch(line -> line.codePointCount(0, line.length()) == 78);
        assertThat(lines.get(0)).contains("╱").contains("█").doesNotContain("████████╗");
        int cherry = lines.get(0).codePoints().boxed().toList().indexOf((int) '█');
        int testWord = lines.get(7).codePoints().boxed().toList().indexOf((int) '█');
        assertThat(testWord).isGreaterThan(cherry);
        assertThat(lines.get(7)).contains("████████╗");
        assertThat(Banner.text()).contains("Multiple mono-repositories. Single test workflow.");
        assertThat(Files.readString(Path.of("../../README.md"), StandardCharsets.UTF_8))
                .contains(Banner.text());
        assertThat(Banner.render(false)).doesNotContain("\u001b");
        assertThat(Banner.render(true))
                .contains("\u001b[38;2;226;59;50m")
                .contains("\u001b[38;2;217;180;143m")
                .contains("Single test workflow.");
    }

    @Test
    void cli_accepts_mutation_only_when_named() {
        assertThat(run("--kinds", "unit,mutation").kinds()).containsExactly("unit", "mutation");
        assertThatThrownBy(() -> CommandLine.parse(List.of("--kinds", "mutant")))
                .isInstanceOf(CommandLine.UsageError.class);
        assertThatThrownBy(() -> CommandLine.parse(List.of("--kinds", "crap")))
                .isInstanceOf(CommandLine.UsageError.class);
        assertThat(run().noCrap()).isFalse();
        assertThat(run("--no-crap").noCrap()).isTrue();
    }

    @Test
    void cli_parses_update_subcommand() {
        CommandLine.Parsed parsed = CommandLine.parse(List.of("update", "--quiet"));
        assertThat(parsed)
                .isInstanceOfSatisfying(
                        CommandLine.Update.class,
                        update -> assertThat(update.quiet()).isTrue());
    }
}
