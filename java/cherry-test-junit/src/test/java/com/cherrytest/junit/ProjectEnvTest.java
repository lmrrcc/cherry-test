package com.cherrytest.junit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.core.ServiceEndpoint;
import com.cherrytest.core.TestId;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProjectEnvTest {
    private static final TestEnv ENV = new TestEnv(
            TestId.parse("demo_project_db_suite_connects_test"),
            "none",
            null,
            Map.of("demo\u0000db", new ServiceEndpoint("demo", "db", "postgres", null, "127.0.0.1", 5432)));

    @Test
    void rejects_an_unknown_service_and_lists_the_known_ones() {
        assertThatThrownBy(() -> ENV.project("demo").service("api"))
                .isInstanceOf(AnyhowError.class)
                .hasMessage("unknown service `demo.api`; known: demo.db");
    }

    @Test
    void rejects_http_access_to_a_non_http_service() {
        assertThatThrownBy(() -> ENV.project("demo").service("db"))
                .isInstanceOf(AnyhowError.class)
                .hasMessage("service `demo`.`db` is kind `postgres`, not http");
    }
}
