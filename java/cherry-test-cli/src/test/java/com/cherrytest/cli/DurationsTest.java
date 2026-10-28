package com.cherrytest.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DurationsTest {
    @TempDir
    Path dir;

    private static Plan.JobKey key(String packageName, String phase) {
        return new Plan.JobKey("shop", packageName, phase);
    }

    @Test
    void path_sits_beside_the_runs_directory() {
        assertThat(Durations.path(Path.of("/ws"), Path.of("target/cherry-test/runs")))
                .isEqualTo(Path.of("/ws/target/cherry-test/durations.json"));
        assertThat(Durations.path(Path.of("/ws"), Path.of("/ci/runs"))).isEqualTo(Path.of("/ci/durations.json"));
    }

    @Test
    void record_keeps_a_moving_average_over_five_runs() {
        Durations durations = new Durations();
        Plan.JobKey api = key("api", "unit");
        durations.record(api, Duration.ofSeconds(1));
        assertThat(durations.get(api)).isEqualTo(new Durations.Entry(1000, 1));
        durations.record(api, Duration.ofMillis(2001));
        assertThat(Objects.requireNonNull(durations.get(api)).millis()).isEqualTo(1501);
        for (int run = 0; run < 10; run++) {
            durations.record(api, Duration.ofSeconds(6));
        }
        Durations.Entry entry = Objects.requireNonNull(durations.get(api));
        assertThat(entry.runs()).isEqualTo(12);
        assertThat(entry.millis()).isGreaterThan(5000).isLessThanOrEqualTo(6000);
        durations.record(api, Duration.ofSeconds(1));
        assertThat(Objects.requireNonNull(durations.get(api)).millis()).isEqualTo((entry.millis() * 4 + 1000 + 2) / 5);
    }

    @Test
    void writes_and_reads_back_sorted_pretty_json() throws IOException {
        Path path = dir.resolve("target/cherry-test/durations.json");
        Durations.update(
                path,
                List.of(
                        Map.entry(key("web", "unit"), Duration.ofMillis(300)),
                        Map.entry(key("api", "integration"), Duration.ofMillis(4200))));
        assertThat(Files.readString(path, StandardCharsets.UTF_8)).isEqualTo("""
                        {
                          "version": 1,
                          "jobs": {
                            "shop/api/integration": {
                              "millis": 4200,
                              "runs": 1
                            },
                            "shop/web/unit": {
                              "millis": 300,
                              "runs": 1
                            }
                          }
                        }""");
        Durations loaded = Durations.load(path);
        assertThat(Objects.requireNonNull(loaded.get(key("web", "unit"))).millis())
                .isEqualTo(300);
        assertThat(loaded.packageTotal("shop", "api")).isEqualTo(4200L);
        assertThat(loaded.packageTotal("shop", "missing")).isNull();
        Files.writeString(path, "not json", StandardCharsets.UTF_8);
        assertThat(Durations.load(path).isEmpty()).isTrue();
        assertThat(Durations.load(dir.resolve("absent.json")).isEmpty()).isTrue();
    }

    @Test
    void unknown_jobs_are_estimated_at_the_75th_percentile_of_known_ones() throws IOException {
        assertThat(Durations.percentile75(List.of())).isZero();
        assertThat(Durations.percentile75(List.of(7L))).isEqualTo(7);
        assertThat(Durations.percentile75(List.of(40L, 10L, 30L, 20L))).isEqualTo(30);
        assertThat(Durations.percentile75(List.of(1L, 2L, 3L, 4L, 5L))).isEqualTo(4);
        Runner.RunOptions options = PlanTest.options(dir, PlanTest.config(dir, PlanTest.FIXTURE), List.of());
        Plan plan = Plan.plan(PlanTest.projects(), options, PlanTest.emptyPreflight());
        assertThat(new Durations().estimate(plan).jobs().map(Plan.Job::estimateMs))
                .containsOnly(0L);
        Durations durations = new Durations();
        durations.record(key("api", "unit"), Duration.ofMillis(100));
        durations.record(key("web", "unit"), Duration.ofMillis(900));
        assertThat(durations.estimate(plan).jobs().map(Plan.Job::estimateMs)).containsExactly(100L, 900L, 900L);
    }
}
