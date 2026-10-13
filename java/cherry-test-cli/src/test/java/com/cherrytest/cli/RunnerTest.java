package com.cherrytest.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.cherrytest.cli.adapters.Formats;
import com.cherrytest.common.adapter.AdapterResponse;
import com.cherrytest.common.adapter.ExecutionMode;
import com.cherrytest.common.adapter.TestStatus;
import com.cherrytest.core.ExternalRunner;
import com.cherrytest.core.PackageConfig;
import com.cherrytest.core.PackageRuntime;
import java.util.List;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class RunnerTest {
    private static PackageConfig packageConfig(
            @Nullable String adapter, @Nullable ExecutionMode execution, PackageRuntime runtime) {
        return new PackageConfig(
                adapter, execution, new TreeMap<>(), null, null, runtime, List.of(), null, null, null, null, null);
    }

    @Nested
    class failure_summary {
        @Test
        void reports_adapter_diagnostics() {
            AdapterResponse response = AdapterResponse.empty();
            response.success(false);
            response.diagnostics().add("CMake configuration failed");
            assertThat(Runner.failureSummary(response)).isEqualTo("CMake configuration failed");
        }

        @Test
        void names_failed_tests_when_there_are_no_diagnostics() {
            AdapterResponse response = AdapterResponse.empty();
            response.success(false);
            response.tests().add(Formats.testCase("catalog::reports_a_price_mismatch", TestStatus.FAILED, null, 0));
            assertThat(Runner.failureSummary(response)).isEqualTo("1 test failed: catalog::reports_a_price_mismatch");
        }

        @Test
        void says_the_runner_failed_when_nothing_explains_it() {
            AdapterResponse response = AdapterResponse.empty();
            response.success(false);
            assertThat(Runner.failureSummary(response))
                    .isEqualTo("test runner exited with an error; see its logs in the run directory");
        }
    }

    @Test
    void mutation_stays_out_of_a_default_run() {
        assertThat(Runner.phasesFor(List.of())).doesNotContain("mutation");
        assertThat(Runner.phasesFor(List.of("unit"))).doesNotContain("mutation");
        assertThat(Runner.phasesFor(List.of("mutation"))).contains("mutation");
        assertThat(Runner.phasesFor(List.of("unit", "mutation"))).contains("mutation");
    }

    @Test
    void mutation_runs_a_local_command_and_skips_coverage() {
        PackageConfig maven = packageConfig("maven", ExecutionMode.CONTAINER, PackageRuntime.RUST);
        assertThat(Runner.phaseAdapter("mutation", null, maven)).isEqualTo("command");
        assertThat(Runner.phaseAdapter("unit", null, maven)).isEqualTo("maven");
        ExternalRunner runner = new ExternalRunner("command", null, new TreeMap<>(), "mvn", List.of(), null);
        assertThat(Runner.phaseAdapter("mutation", runner, maven)).isEqualTo("command");
        PackageConfig node = packageConfig(null, null, PackageRuntime.NODE);
        assertThat(Runner.phaseAdapter("unit", null, node)).isEqualTo("vitest");
        assertThat(Runner.phaseAdapter("mutation", null, node)).isEqualTo("command");
        assertThat(Runner.phaseExecution("mutation", maven.execution(), ExecutionMode.CONTAINER))
                .isEqualTo(ExecutionMode.LOCAL);
        assertThat(Runner.phaseExecution("unit", maven.execution(), ExecutionMode.LOCAL))
                .isEqualTo(ExecutionMode.CONTAINER);
        assertThat(Runner.phaseCollectsCoverage("mutation", true)).isFalse();
        assertThat(Runner.phaseCollectsCoverage("unit", true)).isTrue();
    }

    @Test
    void strict_preflight_skips_fail_even_when_other_tests_pass() {
        AdapterResponse response = AdapterResponse.empty();
        response.success(true);
        response.tests().add(Formats.testCase("unscoped", TestStatus.PASSED, null, 0));
        PreflightPlan preflight = new PreflightPlan(
                false,
                List.of(),
                List.of(new SkippedTest(
                        "api", "demo_project_http_suite_health_test", "demo", "demo", "http", "health", "no docker")),
                List.of(),
                List.of());
        Runner.recordSkips(response, preflight, "api", true);
        assertThat(response.success()).isFalse();
        assertThat(response.tests()).hasSize(2);
        assertThat(response.tests().get(1).status()).isEqualTo(TestStatus.SKIPPED);
        assertThat(response.diagnostics().getFirst()).contains("no docker");
    }
}
