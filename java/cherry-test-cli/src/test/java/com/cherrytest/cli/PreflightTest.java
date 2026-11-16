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

    @Test
    void discovers_java_cherry_test_methods_with_scope_and_auth() {
        String source = """

                package com.example.wallet;

                class WalletIT {
                    @CherryTest(scope = "mini_wallet")
                    void mini_project_wallet_suite_balance_test(TestEnv env) {}

                    @CherryTest(
                            scope="mini_auth",
                            auth = "okta")
                    @DisplayName("token")
                    public void mini_project_auth_suite_token_test(TestEnv env) {}

                    @Test
                    void plainUnitTest() {}

                    @CherryTest
                    void notFollowingTheNamingContract(TestEnv env) {}
                }
                """;
        List<DiscoveredTest> tests = Preflight.parseJavaTestSources(
                "mini-java-api",
                "com.example.wallet.WalletIT",
                Path.of("mini-java-api/src/test/java/com/example/wallet/WalletIT.java"),
                source,
                TestItConfig.empty(Path.of(".")));
        assertThat(tests).hasSize(2);
        assertThat(tests.getFirst().function())
                .isEqualTo("com.example.wallet.WalletIT::mini_project_wallet_suite_balance_test");
        assertThat(tests.getFirst().effectiveScope()).isEqualTo("mini_wallet");
        assertThat(tests.getFirst().fnLine()).isEqualTo(6);
        assertThat(tests.getFirst().suite()).isEqualTo("wallet");
        assertThat(tests.get(1).attrScope()).isEqualTo("mini_auth");
        assertThat(tests.get(1).attrAuth()).isEqualTo("okta");
        assertThat(tests.get(1).fnLine()).isEqualTo(12);
    }

    @Test
    void java_selection_follows_failsafe_filters() {
        Preflight.JavaSelection filtered =
                Preflight.parseJavaSelection(List.of("-o", "-Dit.test=WalletIT,Stack*IT#jug_*,!StackLegacyIT"));
        assertThat(filtered.selects("com/example/WalletIT", "anything")).isTrue();
        assertThat(filtered.selects("com/example/StackReferenceIT", "jug_project_a_suite_b_test"))
                .isTrue();
        assertThat(filtered.selects("com/example/StackReferenceIT", "other")).isFalse();
        assertThat(filtered.selects("com/example/StackLegacyIT", "jug_project_a_suite_b_test"))
                .isFalse();
        assertThat(filtered.selects("com/example/ShopIT", "anything")).isFalse();
        assertThat(filtered.selectsClass("com/example/StackReferenceIT")).isTrue();
        assertThat(filtered.selectsClass("com/example/ShopIT")).isFalse();
        Preflight.JavaSelection defaults = Preflight.parseJavaSelection(List.of("-o"));
        assertThat(defaults.selects("com/example/WalletIT", "m")).isTrue();
        assertThat(defaults.selects("com/example/ITWallet", "m")).isTrue();
        assertThat(defaults.selects("com/example/WalletTest", "m")).isFalse();
        Preflight.JavaSelection qualified =
                Preflight.parseJavaSelection(List.of("-o", "-Dit.test=com.example.WalletIT"));
        assertThat(qualified.selects("com/example/WalletIT", "m")).isTrue();
        assertThat(qualified.selects("org/other/WalletIT", "m")).isFalse();
    }
}
