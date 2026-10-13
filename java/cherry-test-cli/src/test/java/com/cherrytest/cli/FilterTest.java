package com.cherrytest.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cherrytest.core.PackageRuntime;
import java.nio.file.Path;
import java.util.List;
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
}
