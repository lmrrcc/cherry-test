package com.cherrytest.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class SchedulerTest {
    private static SortedMap<String, String> vars(String... pairs) {
        SortedMap<String, String> variables = new TreeMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            variables.put(pairs[index], pairs[index + 1]);
        }
        return variables;
    }

    @Test
    void job_env_layers_phase_project_file_services_and_auth() {
        SortedMap<String, String> base = vars("PATH", "/bin", "CHERRY_TEST_RUN_ID", "run");
        Scheduler.EnvLayers layers = new Scheduler.EnvLayers(
                base,
                List.of(
                        Map.entry("mini", vars("CHERRY_TEST_MINI_DB_NAME", "mini")),
                        Map.entry("shop", vars("CHERRY_TEST_SHOP_DB_NAME", "shop"))));
        Scheduler.RunState state = new Scheduler.RunState(PlanTest.emptyPreflight());
        state.serviceEnv.put("CHERRY_TEST_MINI_API_URL", "http://127.0.0.1:4100");
        state.authEnv.put("CHERRY_TEST_AUTH_OKTA_ACCESS_TOKEN", "token");
        SortedMap<String, String> environment = layers.jobEnv(state, "integration", "mini");
        assertThat(environment)
                .containsEntry("CHERRY_TEST_PHASE", "integration")
                .containsEntry("CHERRY_TEST_MINI_DB_NAME", "mini")
                .doesNotContainKey("CHERRY_TEST_SHOP_DB_NAME")
                .containsEntry("CHERRY_TEST_MINI_API_URL", "http://127.0.0.1:4100")
                .containsEntry("CHERRY_TEST_AUTH_OKTA_ACCESS_TOKEN", "token")
                .containsEntry("PATH", "/bin");
        SortedMap<String, String> phase = layers.phaseEnv(state, "integration");
        assertThat(phase)
                .containsEntry("CHERRY_TEST_SHOP_DB_NAME", "shop")
                .containsEntry("CHERRY_TEST_MINI_DB_NAME", "mini");
        assertThat(base).hasSize(2);
    }
}
