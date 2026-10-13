package com.cherrytest.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.cherrytest.core.TestItConfig;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class PreflightTest {
    @Test
    void parses_scope_from_attribute() {
        assertThat(Preflight.parseStringAttr("#[cherry_test(scope = \"mini_wallet\")]", "scope"))
                .isEqualTo("mini_wallet");
    }

    @Test
    void parses_auth_from_attribute() {
        assertThat(Preflight.parseStringAttr("#[cherry_test(scope = \"mini_auth\", auth = \"okta\")]", "auth"))
                .isEqualTo("okta");
    }

    @Test
    void discovers_test_from_source() {
        String source =
                "\n#[cherry_test(scope = \"none\")]\nasync fn mini_project_wallet_suite_offline_test(env: TestEnv) {}\n";
        List<DiscoveredTest> tests = Preflight.parseTestSources(
                "mini-rs-api",
                Path.of("mini/mini-rs-api/tests/integration.rs"),
                source,
                TestItConfig.empty(Path.of(".")));
        assertThat(tests).hasSize(1);
        assertThat(tests.getFirst().effectiveScope()).isEqualTo("none");
        assertThat(tests.getFirst().fnLine()).isEqualTo(3);
    }
}
