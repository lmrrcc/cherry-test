package com.cherrytest.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cherrytest.core.PackageRuntime;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class FilterTest {
    private static List<ProjectGroup> sample_projects() {
        return List.of(new ProjectGroup(
                "mini",
                List.of(new WorkspaceMember(
                        "mini-rs-api", Path.of("mini/mini-rs-api/Cargo.toml"), "mini", PackageRuntime.RUST))));
    }

    @Test
    void no_filter_returns_all() {
        assertThat(Filter.applyFilter(sample_projects(), null)).hasSize(1);
    }

    @Test
    void filter_by_project_id() {
        assertThat(Filter.applyFilter(sample_projects(), "mini").getFirst().id())
                .isEqualTo("mini");
    }

    @Test
    void filter_by_package_name() {
        assertThat(Filter.applyFilter(sample_projects(), "mini-rs-api")
                        .getFirst()
                        .members()
                        .getFirst()
                        .name())
                .isEqualTo("mini-rs-api");
    }

    @Test
    void unknown_filter_errors() {
        assertThatThrownBy(() -> Filter.applyFilter(sample_projects(), "nope")).hasMessageContaining("unknown filter");
    }

    private static List<ProjectGroup> two_projects() {
        List<ProjectGroup> projects = new ArrayList<>();
        for (Map.Entry<String, List<String>> project : List.of(
                Map.entry("mini", List.of("mini-rs-api", "mini-ts-web")),
                Map.entry("shop", List.of("shop-rs-api", "shop-ts-web")))) {
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

    @Test
    void filter_by_group_selects_named_and_globbed_packages() {
        Map<String, List<String>> groups = Map.of("rust", List.of("*-rs-*", "mini-ts-web"));
        List<ProjectGroup> out = Filter.applyFilter(two_projects(), "@rust", groups);
        assertThat(out.stream().flatMap(project -> project.members().stream().map(WorkspaceMember::name)))
                .containsExactly("mini-rs-api", "mini-ts-web", "shop-rs-api");
        assertThat(out).hasSize(2);
    }

    @Test
    void unknown_or_empty_groups_error() {
        Map<String, List<String>> groups = new TreeMap<>(Map.of(
                "web", List.of("*-web"),
                "typo", List.of("shop-rs-apii"),
                "none", List.of("*-go-*")));
        assertThatThrownBy(() -> Filter.applyFilter(two_projects(), "@nope", groups))
                .hasMessage("unknown group '@nope'. Valid groups: [@none, @typo, @web]");
        assertThatThrownBy(() -> Filter.applyFilter(two_projects(), "@typo", groups))
                .hasMessage("group '@typo' names unknown package 'shop-rs-apii'");
        assertThatThrownBy(() -> Filter.applyFilter(two_projects(), "@none", groups))
                .hasMessage("group '@none' matches no packages");
    }
}
