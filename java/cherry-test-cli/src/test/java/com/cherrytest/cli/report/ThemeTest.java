package com.cherrytest.cli.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.cherrytest.common.process.ProcessEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ThemeTest {
    @AfterEach
    void forget_no_color() {
        ProcessEnvironment.remove("NO_COLOR");
    }

    @Test
    void coverage_accent_uses_threshold() {
        assertThat(Theme.coverageAccent(49.9)).isEqualTo(Accent.LOW);
        assertThat(Theme.coverageAccent(50.0)).isEqualTo(Accent.OK);
        assertThat(Theme.coverageAccent(Double.NaN)).isEqualTo(Accent.NEUTRAL);
    }

    @Test
    void status_accent_maps_labels() {
        assertThat(Theme.statusAccent("ok")).isEqualTo(Accent.OK);
        assertThat(Theme.statusAccent("skipped")).isEqualTo(Accent.SKIP);
        assertThat(Theme.statusAccent("FAIL")).isEqualTo(Accent.FAIL);
    }

    @Test
    void paint_respects_no_color_env() {
        ProcessEnvironment.set("NO_COLOR", "1");
        assertThat(Theme.paint("ok", Accent.OK)).isEqualTo("ok");
    }

    @Test
    void colorize_status_line_does_not_match_it_inside_unit() {
        ProcessEnvironment.set("NO_COLOR", "1");
        Snapshots snapshot = new Snapshots();
        snapshot.packagesPassed = 2;
        snapshot.packagesTotal = 2;
        snapshot.totals = new CoverageTotals(
                Snapshots.summary(406, 1000, 0, 0), Snapshots.summary(0, 10, 0, 0), Snapshots.summary(268, 1000, 0, 0));
        snapshot.diffCoverage = Snapshots.merged(Snapshots.summary(193, 1000, 0, 0));
        String plain = ReportModel.formatStatusLine(snapshot.build(), 200);
        assertThat(plain).contains("unit:40.6%").contains(" it:0.0%");
    }

    @Test
    void colorize_status_line_keeps_plain_length_when_no_color() {
        ProcessEnvironment.set("NO_COLOR", "1");
        Snapshots snapshot = new Snapshots();
        snapshot.overallOk = false;
        snapshot.packagesTotal = 1;
        snapshot.packagesFailed = 1;
        snapshot.integrationSkipped = 1;
        snapshot.integrationFailed = 1;
        ReportSnapshot built = snapshot.build();
        String plain = ReportModel.formatStatusLine(built, 260);
        assertThat(Theme.colorizeStatusLine(plain, built)).isEqualTo(plain);
        assertThat(plain.length()).isLessThanOrEqualTo(260);
    }
}
