package com.cherrytest.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cherrytest.common.container.ContainerSpec;
import com.cherrytest.common.container.PullPolicy;
import com.cherrytest.common.container.SidecarService;
import com.cherrytest.common.container.SidecarSpec;
import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.common.rustcompat.RustOrdering;
import com.cherrytest.core.PackageRuntime;
import com.cherrytest.core.TestItConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
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
                Map.of(),
                Scheduler.Parallelism.SEQUENTIAL);
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

    @Test
    void plans_sidecars_for_container_jobs_with_image_services() throws IOException {
        String local = """
                projects:
                  shop:
                    services:
                      db:
                        image: postgres:16-alpine
                        port: 5432
                        kind: postgres
                        env: {POSTGRES_PASSWORD: test}
                        health: {exec: [pg_isready]}
                      api:
                        service: shop-api
                        port: 3000
                    packages:
                      api:
                        kinds: [unit, integration]
                        integration:
                          scope: checkout
                      web:
                        runtime: node
                        kinds: [unit]
                scopes:
                  checkout:
                    projects: [shop]
                """;
        Plan localPlan = Plan.plan(projects(), options(root, config(root, local), List.of()), emptyPreflight());
        Plan.Job localJob = localPlan.phases().get(1).jobs().getFirst();
        assertThat(localJob.provisioned()).containsExactly("checkout");
        assertThat(localJob.request().container()).isNull();
        String container =
                "defaults:\n  execution: container\n  container:\n    image: eclipse-temurin:25-jdk\n" + local;
        Runner.RunOptions options = options(root, config(root, container), List.of());
        Plan planned = Plan.plan(projects(), options, emptyPreflight());
        Plan.Job unit = planned.phases().getFirst().jobs().getFirst();
        assertThat(unit.provisioned()).isEmpty();
        assertThat(Objects.requireNonNull(unit.request().container()).sidecars())
                .isNull();
        Plan.Job job = planned.phases().get(1).jobs().getFirst();
        ContainerSpec spec = Objects.requireNonNull(job.request().container());
        SidecarSpec sidecars = Objects.requireNonNull(spec.sidecars());
        assertThat(sidecars.holder()).isEqualTo(spec.name() + "-net");
        assertThat(sidecars.services()).hasSize(1);
        SidecarService db = sidecars.services().getFirst();
        assertThat(db.name()).isEqualTo(spec.name() + "-shop-db");
        assertThat(db.pull()).isEqualTo(PullPolicy.NEVER);
        assertThat(db.health().exec()).containsExactly("pg_isready");
        assertThat(db.health().timeoutSecs()).isEqualTo(60);
        Map<String, String> environment = new TreeMap<>(RustOrdering.STRING_ORDER);
        Runner.extendWithProvisioned(environment, job);
        assertThat(environment)
                .containsExactly(
                        Map.entry("CHERRY_TEST_SCOPE_PROVISIONED", "checkout"),
                        Map.entry("CHERRY_TEST_SHOP_DB_HOST", "127.0.0.1"),
                        Map.entry("CHERRY_TEST_SHOP_DB_PORT", "5432"));
        assertThat(RunEnv.requestEnv(environment)).hasSize(3);
    }

    @Test
    void container_limits_set_job_resources_and_resolve_errors_fail_setup() throws IOException {
        String yaml = """
                defaults:
                  execution: container
                  container:
                    image: rust:1
                    cpus: "2.5"
                    memory: 512m
                projects:
                  shop:
                    packages:
                      api:
                        kinds: [unit, integration]
                        integration:
                          scope: checkout
                        container:
                          user: "bad user"
                      web:
                        runtime: node
                        kinds: [unit]
                        resources:
                          cpus: 1.5
                """;
        Runner.RunOptions options = options(root, config(root, yaml), List.of());
        Plan plan = Plan.plan(projects(), options, emptyPreflight());
        Plan.Job api = plan.phases().getFirst().jobs().getFirst();
        assertThat(api.request().container()).isNull();
        assertThat(api.containerError()).contains("container.user");
        Plan.Job web = plan.phases().getFirst().jobs().get(1);
        assertThat(web.containerError()).isNull();
        assertThat(web.request().container()).isNotNull();
        assertThat(web.resources()).isEqualTo(new Plan.Resources(1500, 512L << 20));
        assertThat(Plan.jobResources(null, web.request().container())).isEqualTo(new Plan.Resources(2500, 512L << 20));
        assertThat(Plan.jobResources(null, null)).isEqualTo(Plan.Resources.PACKAGE_DEFAULT);
    }
}
