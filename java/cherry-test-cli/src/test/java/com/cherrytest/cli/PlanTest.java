package com.cherrytest.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.core.PackageRuntime;
import com.cherrytest.core.TestItConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlanTest {
    static final String FIXTURE = """
            projects:
              shop:
                packages:
                  api:
                    kinds: [unit, integration]
                    integration:
                      scope: checkout
                  web:
                    runtime: node
                    kinds: [unit]
            """;

    @TempDir
    Path root;

    static List<ProjectGroup> projects() {
        return List.of(new ProjectGroup(
                "shop",
                List.of(
                        new WorkspaceMember("api", Path.of("shop/api/Cargo.toml"), "shop", PackageRuntime.RUST),
                        new WorkspaceMember("web", Path.of("shop/web/Cargo.toml"), "shop", PackageRuntime.RUST))));
    }

    static TestItConfig config(Path root, String yaml) throws IOException {
        Files.writeString(root.resolve("cherry-test.yaml"), yaml, StandardCharsets.UTF_8);
        return TestItConfig.load(root);
    }

    static Runner.RunOptions options(Path root, TestItConfig config, List<String> kinds) {
        RunDir runDir = RunDir.create(root, Path.of("runs"), RunIdGenerator.systemClock());
        return new Runner.RunOptions(
                root,
                runDir,
                config,
                true,
                true,
                Runner.TestListing.AFTER_EACH_PHASE,
                List.of(),
                kinds,
                Main.selfCommand(),
                Map.of());
    }

    static PreflightPlan emptyPreflight() {
        return new PreflightPlan(false, List.of(), List.of(), List.of(), List.of());
    }

    @Test
    void plans_jobs_phase_by_phase_in_configuration_order() throws IOException {
        Runner.RunOptions options = options(root, config(root, FIXTURE), List.of());
        Plan plan = Plan.plan(projects(), options, emptyPreflight());
        assertThat(plan.phases().stream().map(Plan.PhasePlan::phase))
                .containsExactly("unit", "integration", "functional", "ui", "visual");
        assertThat(plan.jobs().map(job -> job.key().id()))
                .containsExactly("shop/api/unit", "shop/web/unit", "shop/api/integration");
        Plan.Job integration = plan.phases().get(1).jobs().getFirst();
        assertThat(integration.adapter()).isEqualTo("cargo");
        assertThat(integration.scopes()).containsExactly("checkout");
        assertThat(integration.request().env()).isEmpty();
        assertThat(integration.request().output().endsWith(Path.of("reports/adapters/shop/api/integration")))
                .isTrue();
        assertThat(plan.phases().getFirst().jobs().get(1).adapter()).isEqualTo("vitest");
        assertThat(plan.phases().getFirst().jobs().get(1).resources()).isEqualTo(Plan.Resources.PACKAGE_DEFAULT);
    }

    @Test
    void plans_only_the_selected_kinds() throws IOException {
        Runner.RunOptions options = options(root, config(root, FIXTURE), List.of("integration"));
        Plan plan = Plan.plan(projects(), options, emptyPreflight());
        assertThat(plan.phases()).hasSize(1);
        assertThat(plan.phases().getFirst().jobs()).hasSize(1);
    }

    @Test
    void rejects_an_unknown_kind_before_anything_runs() throws IOException {
        Runner.RunOptions options =
                options(root, config(root, FIXTURE.replace("kinds: [unit]", "kinds: [unit, smoke]")), List.of());
        assertThatThrownBy(() -> Plan.plan(projects(), options, emptyPreflight()))
                .isInstanceOf(AnyhowError.class)
                .hasMessage("unknown test kind smoke");
    }
}
