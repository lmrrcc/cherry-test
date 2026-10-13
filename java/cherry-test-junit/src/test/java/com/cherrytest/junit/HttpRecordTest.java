package com.cherrytest.junit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.core.TestId;
import org.junit.jupiter.api.Test;

class HttpRecordTest {
    private static HttpRecord record(int status, String body) {
        return new HttpRecord(
                TestId.parse("demo_project_health_suite_responds_test"),
                "none",
                "demo",
                "api",
                3,
                "GET",
                "http://127.0.0.1:1/health",
                status,
                null,
                null,
                body,
                true,
                null);
    }

    @Test
    void assert_status_names_expected_and_actual_status_like_assert_eq() {
        assertThatThrownBy(() -> record(500, "").assertStatus(200))
                .isInstanceOf(AssertionError.class)
                .hasMessage(
                        "assertion `left == right` failed: expected HTTP 200, got 500 for http://127.0.0.1:1/health\n"
                                + "  left: 500\n right: 200");
    }

    @Test
    void response_body_json_parses_the_body() {
        assertThat(record(200, "{\"ok\":true}").responseBodyJson().get("ok").booleanValue())
                .isTrue();
    }

    @Test
    void response_body_json_reports_unparseable_bodies_with_context() {
        assertThatThrownBy(() -> record(200, "nope").responseBodyJson())
                .isInstanceOf(AnyhowError.class)
                .hasMessage("parse response JSON");
    }
}
