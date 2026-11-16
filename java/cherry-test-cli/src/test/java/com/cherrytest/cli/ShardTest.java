package com.cherrytest.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cherrytest.core.PackageRuntime;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ShardTest {
    private static List<ProjectGroup> projects() {
        List<ProjectGroup> projects = new ArrayList<>();
        for (Map.Entry<String, List<String>> project :
                List.of(Map.entry("a", List.of("a1", "a2")), Map.entry("b", List.of("b1", "b2", "b3")))) {
            projects.add(new ProjectGroup(
                    project.getKey(),
                    project.getValue().stream()
                            .map(name -> new WorkspaceMember(
                                    name,
                                    Path.of(project.getKey() + "/" + name + "/Cargo.toml"),
                                    project.getKey(),
                                    PackageRuntime.RUST))
                            .toList()));
        }
        return projects;
    }

    private static List<String> names(List<ProjectGroup> projects) {
        return projects.stream()
                .flatMap(project -> project.members().stream().map(WorkspaceMember::name))
                .toList();
    }

    @Test
    void parses_one_based_shards() {
        assertThat(Shard.parse("2/4")).isEqualTo(new Shard(2, 4));
        assertThat(new Shard(1, 3)).hasToString("1/3");
        for (String invalid : List.of("0/4", "5/4", "1/0", "1", "a/b", "1/2/3", "-1/2")) {
            assertThatThrownBy(() -> Shard.parse(invalid)).as(invalid).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void deals_packages_round_robin_without_durations() {
        assertThat(Shard.assign(Arrays.asList(null, null, null, null, null), 2)).containsExactly(0, 1, 0, 1, 0);
        Durations durations = new Durations();
        assertThat(names(Shard.select(projects(), new Shard(1, 2), durations))).containsExactly("a1", "b1", "b3");
        assertThat(names(Shard.select(projects(), new Shard(2, 2), durations))).containsExactly("a2", "b2");
        assertThat(Shard.select(projects(), new Shard(6, 6), durations)).isEmpty();
    }

    @Test
    void packs_the_longest_packages_first_onto_the_lightest_shard() {
        assertThat(Shard.assign(List.of(10L, 70L, 30L, 30L, 20L), 2)).containsExactly(0, 0, 1, 1, 1);
        assertThat(Shard.assign(Arrays.asList(5L, null, 1L), 3)).containsExactly(0, 1, 2);
        Durations durations = new Durations();
        for (Map.Entry<String, Long> entry :
                List.of(Map.entry("a1", 100L), Map.entry("a2", 900L), Map.entry("b1", 400L), Map.entry("b2", 450L))) {
            String project = entry.getKey().startsWith("a") ? "a" : "b";
            durations.record(new Plan.JobKey(project, entry.getKey(), "unit"), Duration.ofMillis(entry.getValue()));
        }
        durations.record(new Plan.JobKey("a", "a1", "integration"), Duration.ofMillis(500));
        List<String> first = names(Shard.select(projects(), new Shard(1, 2), durations));
        List<String> second = names(Shard.select(projects(), new Shard(2, 2), durations));
        assertThat(first).containsExactly("a2", "b2");
        assertThat(second).containsExactly("a1", "b1", "b3");
        List<String> all = new ArrayList<>(first);
        all.addAll(second);
        assertThat(all).containsExactlyInAnyOrder("a1", "a2", "b1", "b2", "b3");
    }
}
