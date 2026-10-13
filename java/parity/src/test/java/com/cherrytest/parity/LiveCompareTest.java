package com.cherrytest.parity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class LiveCompareTest {
    private static final String RUN_ID = "20261013-011836-c19c38";

    @Test
    void drops_docker_build_and_compose_status_lines() {
        assertThat(List.of(
                        "#16 exporting layers done",
                        "#16 CACHED",
                        " Container cherry-test-20261013-011836-c19c38-shop-db-1 Healthy ",
                        " Network cherry-test-20261013-011836-c19c38_default Created ",
                        ""))
                .allSatisfy(line ->
                        assertThat(LiveCompare.terminalLine(line, RUN_ID)).isEmpty());
    }

    @Test
    void replaces_durations_but_keeps_counts_and_coverage() {
        assertThat(LiveCompare.terminalLine("  52 tests · 51 ok · 1 failed · 0 skipped · 15968ms", RUN_ID))
                .contains("52 tests · 51 ok · 1 failed · 0 skipped · Nms");
        assertThat(LiveCompare.terminalLine(
                        "    shop-ts-web-react: Shop shows a network failure     none     shop.shop-ts-web-react"
                                + "             10       ok       ok",
                        RUN_ID))
                .contains("shop-ts-web-react: Shop shows a network failure none shop.shop-ts-web-react N ok ok");
        assertThat(LiveCompare.terminalLine("shop  shop-ts-web-r…  189/189·189c·0u·100.0%  17/17·100.0%", RUN_ID))
                .contains("shop shop-ts-web-r… 189/189·189c·0u·100.0% 17/17·100.0%");
    }

    @Test
    void replaces_the_run_id_and_local_ports() {
        assertThat(LiveCompare.normalized(
                        "GET http://127.0.0.1:65025/v1/carts in target/cherry-test/runs/" + RUN_ID, RUN_ID))
                .isEqualTo("GET http://127.0.0.1:PORT/v1/carts in target/cherry-test/runs/RUN-ID");
    }

    @Test
    void keeps_whether_a_line_was_covered_but_not_how_often() {
        assertThat(LiveCompare.lcovLine("DA:12,7")).isEqualTo("DA:12,hit");
        assertThat(LiveCompare.lcovLine("DA:13,0")).isEqualTo("DA:13,0");
        assertThat(LiveCompare.lcovLine("FNDA:3,_RNvCs_main")).isEqualTo("FNDA:hit,_RNvCs_main");
        assertThat(LiveCompare.lcovLine("BRDA:4,0,1,2")).isEqualTo("BRDA:4,0,1,taken");
        assertThat(LiveCompare.lcovLine("BRDA:4,0,2,-")).isEqualTo("BRDA:4,0,2,-");
        assertThat(LiveCompare.lcovLine("LH:189")).isEqualTo("LH:189");
    }
}
