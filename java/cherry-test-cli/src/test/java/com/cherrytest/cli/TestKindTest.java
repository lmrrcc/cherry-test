package com.cherrytest.cli;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TestKindTest {
    @Test
    void test_kind_parse() {
        assertThat(TestKind.parse("unit")).isEqualTo(TestKind.UNIT);
        assertThat(TestKind.parse("UI")).isEqualTo(TestKind.UI);
        assertThat(TestKind.parse("mutation")).isEqualTo(TestKind.MUTATION);
        assertThat(TestKind.MUTATION.asStr()).isEqualTo("mutation");
        assertThat(TestKind.parse("unknown")).isNull();
    }
}
