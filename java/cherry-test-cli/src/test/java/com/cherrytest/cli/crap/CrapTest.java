package com.cherrytest.cli.crap;

import static org.assertj.core.api.Assertions.assertThat;

import com.cherrytest.cli.coverage.FileLineCoverage;
import com.cherrytest.cli.report.AddedLines;
import com.cherrytest.common.rustcompat.RustFloatFormat;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CrapTest {
    private static CrapFunction scored(String path, double score, long complexity, double load) {
        return new CrapFunction(path, path, 1, complexity, 0, 1, 0.0, score, load, List.of());
    }

    private static CrapFunction named(CrapFunction function, String name, long line, List<CrapSourceLine> source) {
        return new CrapFunction(
                function.path(),
                name,
                line,
                function.complexity(),
                function.coveredLines(),
                function.measuredLines(),
                function.coverage(),
                function.score(),
                function.crapLoad(),
                source);
    }

    private static Crap.FunctionSpan function(List<Crap.FunctionSpan> functions, String name) {
        return functions.stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    @Nested
    class severity {
        @Test
        void treats_the_threshold_as_critical() {
            assertThat(Crap.crapSeverity(Crap.CRAP_THRESHOLD)).isEqualTo(CrapSeverity.CRITICAL);
        }

        @Test
        void bands_scores_from_high_to_low() {
            assertThat(Crap.crapSeverity(15.0)).isEqualTo(CrapSeverity.HIGH);
            assertThat(Crap.crapSeverity(5.0)).isEqualTo(CrapSeverity.MODERATE);
            assertThat(Crap.crapSeverity(4.9)).isEqualTo(CrapSeverity.LOW);
        }
    }

    @Nested
    class source_lines {
        private static final String SOURCE =
                "fn a() {}\nfn price(x: u32) -> u32 {\n    if x > 1 { 2 } else { 3 }\n}\nfn b() {}";

        private static Map<Long, Boolean> coverage() {
            return new TreeMap<>(Map.of(2L, true, 3L, false));
        }

        @Test
        void keeps_only_the_function_lines() {
            assertThat(Crap.functionSource(SOURCE, 2, 4, coverage()))
                    .extracting(CrapSourceLine::number)
                    .containsExactly(2L, 3L, 4L);
        }

        @Test
        void marks_each_line_with_its_coverage() {
            assertThat(Crap.functionSource(SOURCE, 2, 4, coverage()))
                    .extracting(CrapSourceLine::measure)
                    .containsExactly(LineMeasure.COVERED, LineMeasure.UNCOVERED, LineMeasure.NOT_MEASURED);
        }

        @Test
        void stops_at_the_end_of_the_file() {
            assertThat(Crap.functionSource(SOURCE, 5, 9, coverage())).hasSize(1);
        }
    }

    @Test
    void formula_matches_the_published_examples() {
        assertThat(Crap.crapScore(10, 0.0)).isEqualTo(110.0);
        assertThat(Crap.crapScore(10, 0.5)).isEqualTo(22.5);
        assertThat(Crap.crapScore(10, 1.0)).isEqualTo(10.0);
    }

    @Test
    void rust_counts_decisions_and_ignores_strings() {
        String source = """

                fn price(x: u64) -> u64 {
                    if x > 10 && x < 20 {
                        1
                    } else if x == 0 {
                        2
                    } else {
                        3
                    }
                }
                fn label() -> &'static str {
                    "if && for"
                }
                fn choose(kind: u8) -> u8 {
                    match kind {
                        1 => 1,
                        2 => 2,
                        _ => 0,
                    }
                }
                """;
        List<Crap.FunctionSpan> functions = Crap.functionsIn(source, Crap.Language.RUST);
        assertThat(function(functions, "price").complexity()).isEqualTo(4);
        assertThat(function(functions, "label").complexity()).isEqualTo(1);
        assertThat(function(functions, "choose").complexity()).isEqualTo(4);
    }

    @Test
    void nested_rust_function_keeps_its_own_decisions() {
        String source = """

                fn outer() {
                    if ready() {
                        fn inner() {
                            if deep() { 1 } else { 2 }
                        }
                    }
                }
                """;
        List<Crap.FunctionSpan> functions = Crap.functionsIn(source, Crap.Language.RUST);
        assertThat(function(functions, "outer").complexity()).isEqualTo(2);
        assertThat(function(functions, "inner").complexity()).isEqualTo(2);
    }

    @Test
    void c_java_script_and_kotlin_count_their_branches() {
        List<Crap.FunctionSpan> c =
                Crap.functionsIn("int price(int x) { if (x > 1 && x < 3) return 1; return 0; }\n", Crap.Language.C);
        assertThat(c.getFirst().name()).isEqualTo("price");
        assertThat(c.getFirst().complexity()).isEqualTo(3);
        List<Crap.FunctionSpan> java = Crap.functionsIn(
                "class Prices { long convert(long x) { if (x > 0) return x; return 0; } }\n", Crap.Language.JAVA);
        assertThat(function(java, "convert").complexity()).isEqualTo(2);
        String scriptSource = """
                function price(x) { return x > 1 && x < 3 ? 1 : 0; }
                const arrow = (x) => { if (x) return 1; return 0; };
                """;
        List<Crap.FunctionSpan> script = Crap.functionsIn(scriptSource, Crap.Language.SCRIPT);
        assertThat(function(script, "price").complexity()).isEqualTo(3);
        assertThat(function(script, "arrow").complexity()).isEqualTo(2);
        List<Crap.FunctionSpan> kotlin = Crap.functionsIn(
                "fun price(x: Int): Int { if (x > 0 && x < 3) return 1; return 0 }\n", Crap.Language.KOTLIN);
        assertThat(kotlin.getFirst().complexity()).isEqualTo(3);
    }

    @Test
    void vue_script_uses_the_source_line() {
        String source = """
                <template><p>if</p></template>
                <script setup lang="ts">
                function price(x: number) { if (x > 0) return 1; return 0; }
                </script>
                """;
        List<Crap.FunctionSpan> functions = Crap.functionsIn(Crap.vueScript(source), Crap.Language.SCRIPT);
        assertThat(functions.getFirst().name()).isEqualTo("price");
        assertThat(functions.getFirst().start()).isEqualTo(3);
        assertThat(functions.getFirst().complexity()).isEqualTo(2);
    }

    @Test
    void score_uses_measured_lines_inside_the_function(@TempDir Path directory) throws IOException {
        Files.writeString(
                directory.resolve("price.rs"),
                "fn price(x: u64) -> u64 {\n    if x > 0 { 1 } else { 0 }\n}\n",
                StandardCharsets.UTF_8);
        List<CrapFunction> scored =
                Crap.scoreFiles(directory, List.of(new FileLineCoverage("price.rs", new TreeMap<>(Map.of(2L, true)))));
        assertThat(scored).hasSize(1);
        CrapFunction price = scored.getFirst();
        assertThat(price.complexity()).isEqualTo(2);
        assertThat(price.measuredLines()).isEqualTo(1);
        assertThat(price.coveredLines()).isEqualTo(1);
        assertThat(price.coverage()).isEqualTo(1.0);
        assertThat(price.score()).isEqualTo(2.0);
        assertThat(price.crapLoad()).isEqualTo(0.0);
    }

    @Test
    void crap_load_is_zero_under_thirty_and_set_at_the_threshold() {
        assertThat(Crap.crapLoad(10, 1.0)).isEqualTo(0.0);
        assertThat(Crap.crapLoad(10, 0.5)).isEqualTo(0.0);
        assertThat(Crap.crapScore(4, 0.0)).isEqualTo(20.0);
        assertThat(Crap.crapLoad(4, 0.0)).isEqualTo(0.0);
        assertThat(Crap.crapScore(5, 0.0)).isEqualTo(30.0);
        assertThat(Crap.crapLoad(5, 0.0)).isEqualTo(5.0 + 5.0 / 30.0);
        assertThat(Crap.crapScore(10, 0.0)).isEqualTo(110.0);
        assertThat(Crap.crapLoad(10, 0.0)).isEqualTo(10.0 + 10.0 / 30.0);
        assertThat(RustFloatFormat.fixed(Crap.crapLoad(10, 0.0), 1)).isEqualTo("10.3");
    }

    @Test
    void median_averages_the_middle_pair_and_counts_thirty_as_crappy() {
        CrapTotals odd = Crap.crapTotals(List.of(
                scored("c", 110.0, 10, Crap.crapLoad(10, 0.0)),
                scored("a", 5.0, 1, 0.0),
                scored("b", 30.0, 5, Crap.crapLoad(5, 0.0))));
        assertThat(odd.medianCrap()).isEqualTo(30.0);
        assertThat(odd.worstCrap()).isEqualTo(110.0);
        assertThat(odd.crappyFunctions()).isEqualTo(2);
        CrapTotals even = Crap.crapTotals(List.of(
                scored("d", 110.0, 10, 1.0),
                scored("a", 1.0, 1, 0.0),
                scored("c", 20.0, 4, 0.0),
                scored("b", 10.0, 2, 0.0)));
        assertThat(even.medianCrap()).isEqualTo(15.0);
        assertThat(even.meanCrap()).isEqualTo(35.25);
        assertThat(even.crappyFunctions()).isEqualTo(1);
        assertThat(even.crappyPercent()).isEqualTo(25.0);
        assertThat(even.complexity()).isEqualTo(17);
        assertThat(Crap.crapTotals(List.of())).isEqualTo(CrapTotals.ZERO);
    }

    @Test
    void totals_include_functions_past_the_displayed_thirty() {
        List<CrapFunction> functions = new ArrayList<>();
        for (int index = 0; index < 30; index++) {
            functions.add(scored("low" + index, 1.0, 1, 0.0));
        }
        functions.add(scored("high", 110.0, 10, Crap.crapLoad(10, 0.0)));
        CrapTotals totals = Crap.crapTotals(functions);
        List<CrapFunction> ranked = new ArrayList<>(functions);
        ranked.sort(Comparator.comparingDouble(CrapFunction::score).reversed());
        double shownScore =
                ranked.stream().limit(30).mapToDouble(CrapFunction::score).sum();
        long shownComplexity =
                ranked.stream().limit(30).mapToLong(CrapFunction::complexity).sum();
        assertThat(totals.functions()).isEqualTo(31);
        assertThat(totals.totalCrap()).isEqualTo(140.0);
        assertThat(shownScore).isEqualTo(139.0);
        assertThat(totals.complexity()).isEqualTo(40);
        assertThat(shownComplexity).isEqualTo(39);
        assertThat(totals.worstCrap()).isEqualTo(110.0);
        assertThat(totals.medianCrap()).isEqualTo(1.0);
        assertThat(totals.crappyFunctions()).isEqualTo(1);
        assertThat(totals.crappyPercent()).isEqualTo(100.0 / 31.0);
        assertThat(totals.crapLoad()).isEqualTo(10.0 + 10.0 / 30.0);
    }

    @Test
    void project_rollups_use_the_longest_path_and_skip_ties() {
        Path workspace = Path.of("/ws");
        List<CrapFunction> functions = List.of(
                scored("app/shop/src/a.rs", 110.0, 10, Crap.crapLoad(10, 0.0)),
                scored("app/shop/src/b.rs", 10.0, 2, 0.0),
                scored("app/mini/src/c.rs", 30.0, 5, Crap.crapLoad(5, 0.0)),
                scored("notes.rs", 4.0, 1, 0.0),
                scored("app/shop-extra/src/d.rs", 8.0, 1, 0.0));
        List<CrapProjectTotals> rollups = Crap.projectRollups(
                workspace,
                functions,
                List.of(
                        new CrapProjectRoot("shop", Path.of("/ws/app/shop")),
                        new CrapProjectRoot("mini", Path.of("/ws/app/mini"))));
        assertThat(rollups).hasSize(3);
        assertThat(rollups.get(0).project()).isEqualTo("mini");
        assertThat(rollups.get(0).totals().functions()).isEqualTo(1);
        assertThat(rollups.get(0).totals().crappyFunctions()).isEqualTo(1);
        assertThat(rollups.get(1).project()).isEqualTo("shop");
        assertThat(rollups.get(1).totals().functions()).isEqualTo(2);
        assertThat(rollups.get(1).totals().complexity()).isEqualTo(12);
        assertThat(rollups.get(1).totals().totalCrap()).isEqualTo(120.0);
        assertThat(rollups.get(1).totals().crapLoad()).isEqualTo(10.0 + 10.0 / 30.0);
        assertThat(rollups.get(2).project()).isEqualTo("outside projects");
        assertThat(rollups.get(2).totals().functions()).isEqualTo(2);
        assertThat(rollups.get(2).totals().totalCrap()).isEqualTo(12.0);
        List<CrapProjectTotals> nested = Crap.projectRollups(
                workspace,
                functions,
                List.of(
                        new CrapProjectRoot("root", workspace.resolve(".")),
                        new CrapProjectRoot("shop", Path.of("/ws/app/shop"))));
        assertThat(nested).hasSize(2);
        assertThat(nested.get(0).project()).isEqualTo("root");
        assertThat(nested.get(0).totals().functions()).isEqualTo(3);
        assertThat(nested.get(1).project()).isEqualTo("shop");
        assertThat(nested.get(1).totals().functions()).isEqualTo(2);
        List<CrapProjectTotals> tied = Crap.projectRollups(
                workspace,
                functions,
                List.of(new CrapProjectRoot("mini", Path.of("/ws")), new CrapProjectRoot("prices", Path.of("/ws"))));
        assertThat(tied).isEmpty();
        assertThat(Crap.projectRollups(workspace, functions, List.of())).isEmpty();
    }

    @Test
    void change_slice_keeps_functions_whose_file_is_in_the_diff() {
        List<CrapFunction> functions = List.of(
                scored("shop-rs-api/src/domain/model.rs", 4.0, 2, 0.0),
                scored("shop-ts-lib-cart/src/cart.ts", 2.0, 1, 0.0));
        assertThat(Crap.changeSlice(functions, List.of())).isNull();
        CrapChange changed =
                Objects.requireNonNull(Crap.changeSlice(functions, List.of("shop-rs-api/src/domain/model.rs")));
        assertThat(changed.functions()).hasSize(1);
        assertThat(changed.functions().getFirst().path()).isEqualTo("shop-rs-api/src/domain/model.rs");
        assertThat(changed.totals().functions()).isEqualTo(1);
        assertThat(changed.totals().worstCrap()).isEqualTo(4.0);
    }

    @Test
    void change_on_added_lines_keeps_functions_that_overlap_the_diff() {
        CrapFunction parse = named(
                scored("shop-rs-api/src/domain/model.rs", 4.0, 3, 0.0),
                "parse",
                110,
                List.of(new CrapSourceLine(112, "_ => Err(DomainError::InvalidDiscount),", LineMeasure.UNCOVERED)));
        CrapFunction checkout = named(
                scored("shop-rs-api/src/domain/model.rs", 2.0, 2, 0.0),
                "checkout",
                220,
                List.of(new CrapSourceLine(221, "self.ensure_open()?;", LineMeasure.COVERED)));
        CrapChange changed = Objects.requireNonNull(Crap.changeOnAddedLines(
                List.of(parse, checkout),
                List.of(new AddedLines("shop-rs-api/src/domain/model.rs", new TreeSet<>(List.of(112L))))));
        assertThat(changed.functions()).hasSize(1);
        assertThat(changed.functions().getFirst().name()).isEqualTo("parse");
        assertThat(Crap.changeOnAddedLines(List.of(), List.of())).isNull();
    }
}
