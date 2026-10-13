package com.cherrytest.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TestIdTest {
    @Test
    void parses_convention() {
        TestId id = TestId.parse("mini_project_wallet_suite_get_address_value_usdt_test");
        assertThat(id)
                .isEqualTo(new TestId(
                        "mini",
                        "wallet",
                        "get_address_value_usdt",
                        "mini_project_wallet_suite_get_address_value_usdt_test"));
    }
}
