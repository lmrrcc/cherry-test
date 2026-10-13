package com.cherrytest.junit;

import static org.assertj.core.api.Assertions.assertThat;

import com.cherrytest.core.ServiceEndpoint;
import com.cherrytest.core.TestId;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ServiceRequestTest {
    private static TestEnv env(String url) {
        return new TestEnv(
                TestId.parse("demo_project_health_suite_responds_test"),
                "none",
                null,
                Map.of("demo\u0000api", new ServiceEndpoint("demo", "api", "http", url, "127.0.0.1", 1)));
    }

    @Nested
    class url {
        @Test
        void joins_the_base_url_and_the_path_with_one_slash() {
            ServiceEnv service = env("http://127.0.0.1:1/").project("demo").service("api");
            HttpRecord record = service.get("/health").send();
            assertThat(record.url()).isEqualTo("http://127.0.0.1:1/health");
        }

        @Test
        void keeps_an_absolute_url_unchanged() {
            ServiceEnv service = env("http://127.0.0.1:1").project("demo").service("api");
            HttpRecord record = service.get("http://127.0.0.1:2/other").send();
            assertThat(record.url()).isEqualTo("http://127.0.0.1:2/other");
        }
    }

    @Nested
    class send {
        @Test
        void reports_status_zero_when_the_service_is_unreachable() {
            ServiceEnv service = env("http://127.0.0.1:1").project("demo").service("api");
            HttpRecord record = service.get("/health").send();
            assertThat(record.status()).isZero();
        }

        @Test
        void describes_the_transport_failure_in_the_response_body() {
            ServiceEnv service = env("http://127.0.0.1:1").project("demo").service("api");
            HttpRecord record = service.get("/health").send();
            assertThat(record.responseBody()).isEqualTo("error sending request for url (http://127.0.0.1:1/health)");
        }

        @Test
        void redacts_the_bearer_token_in_the_record() {
            ServiceEnv service = env("http://127.0.0.1:1").project("demo").service("api");
            HttpRecord record = service.get("/health").bearer("secret-token").send();
            assertThat(record.authorization()).isEqualTo("Bearer [redacted]");
        }

        @Test
        void records_a_json_body_compactly() {
            ServiceEnv service = env("http://127.0.0.1:1").project("demo").service("api");
            HttpRecord record =
                    service.post("/orders").jsonBody(Map.of("sku", "MUG")).send();
            assertThat(record.requestBody()).isEqualTo("{\"sku\":\"MUG\"}");
        }
    }
}
