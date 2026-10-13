package com.cherrytest.junit;

import static org.assertj.core.api.Assertions.assertThat;

import com.cherrytest.common.process.ProcessEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StandaloneTest {
    @BeforeEach
    @AfterEach
    void without_a_runner() {
        ProcessEnvironment.remove("CHERRY_TEST_RUN_ID");
        ProcessEnvironment.remove("CHERRY_TEST_SKIP_DOCKER");
    }

    @Test
    void provisions_scopes_itself_when_no_runner_is_present() {
        assertThat(CherryTestExtension.standalone()).isTrue();
    }

    @Test
    void reuses_the_compose_project_of_a_runner_in_compose_mode() {
        ProcessEnvironment.set("CHERRY_TEST_RUN_ID", "20261013-011836-c19c38");
        assertThat(CherryTestExtension.standalone()).isFalse();
    }

    @Test
    void uses_the_endpoints_of_a_runner_that_provisioned_the_scopes() {
        ProcessEnvironment.set("CHERRY_TEST_SKIP_DOCKER", "1");
        assertThat(CherryTestExtension.standalone()).isFalse();
    }
}
