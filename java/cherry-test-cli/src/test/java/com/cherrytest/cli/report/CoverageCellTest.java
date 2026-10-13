package com.cherrytest.cli.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.cherrytest.cli.coverage.CoverageSummary;
import org.junit.jupiter.api.Test;

class CoverageCellTest {
    private static CoverageSummary sample(long linesHit, long linesFound, long functionsHit, long functionsFound) {
        return new CoverageSummary(linesHit, linesFound, functionsHit, functionsFound);
    }

    @Test
    void format_lines_cell_shows_counts_and_pct() {
        CoverageSummary summary = sample(12, 45, 0, 0);
        assertThat(CoverageCell.formatLinesCell(summary, false)).isEqualTo("12/45 · 12 cov · 33 unc · 26.7%");
        assertThat(CoverageCell.formatLinesCell(summary, true)).isEqualTo("12/45·12c·33u·26.7%");
    }

    @Test
    void format_lines_cell_dash_when_empty() {
        assertThat(CoverageCell.formatLinesCell(null, false)).isEqualTo("-");
        assertThat(CoverageCell.formatLinesCell(sample(0, 0, 0, 0), false)).isEqualTo("-");
    }

    @Test
    void format_fn_cell_shows_hit_found_and_pct() {
        assertThat(CoverageCell.formatFnCell(sample(0, 0, 2, 8), false)).isEqualTo("2/8 · 25.0%");
    }

    @Test
    void lines_cell_html_contains_ratio_and_pct() {
        assertThat(CoverageCell.linesCellHtml(sample(12, 45, 0, 0)))
                .contains("12/45")
                .contains("33 unc")
                .contains("26.7%");
    }
}
