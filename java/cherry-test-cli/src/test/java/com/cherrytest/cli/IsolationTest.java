package com.cherrytest.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.cherrytest.cli.adapters.Formats;
import com.cherrytest.common.adapter.AdapterConfig;
import com.cherrytest.common.adapter.AdapterRequest;
import com.cherrytest.common.adapter.ExecutionMode;
import com.cherrytest.common.adapter.IsolationClass;
import com.cherrytest.common.adapter.TestResult;
import com.cherrytest.common.adapter.TestStatus;
import com.cherrytest.core.IsolateConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.LongStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IsolationTest {
    private static AdapterRequest request(Path root, String phase, String... args) {
        return new AdapterRequest(
                1,
                "shop",
                "prices",
                phase,
                root,
                root.resolve("prices/pom.xml"),
                root.resolve("prices"),
                root.resolve("out"),
                ExecutionMode.LOCAL,
                true,
                true,
                List.of(args),
                new TreeMap<>(),
                new TreeMap<>());
    }

    private static TestResult failed(String name) {
        return Formats.testCase(name, TestStatus.FAILED, null, 0);
    }

    private static List<String> args(Isolation.Narrowing narrowing) {
        assertThat(narrowing).isInstanceOf(Isolation.Narrowing.Args.class);
        return ((Isolation.Narrowing.Args) narrowing).args();
    }

    @Test
    void classifies_reruns_as_real_interference_flaky_or_inconclusive() {
        Isolation.Rerun passed = new Isolation.Rerun.Passed();
        Isolation.Rerun failedRun = new Isolation.Rerun.Failed();
        assertThat(Isolation.classify(List.of(failedRun, failedRun, failedRun)))
                .isEqualTo(new Isolation.Classification(IsolationClass.REAL, 0, null));
        assertThat(Isolation.classify(List.of(passed, passed, passed)))
                .isEqualTo(new Isolation.Classification(IsolationClass.INTERFERENCE, 3, null));
        assertThat(Isolation.classify(List.of(passed, failedRun, passed)))
                .isEqualTo(new Isolation.Classification(IsolationClass.FLAKY, 2, null));
        assertThat(Isolation.classify(List.of(passed, new Isolation.Rerun.Infrastructure("boot failed"), failedRun)))
                .isEqualTo(new Isolation.Classification(IsolationClass.INCONCLUSIVE, 1, "boot failed"));
        assertThat(Isolation.classify(List.of()))
                .isEqualTo(new Isolation.Classification(IsolationClass.INCONCLUSIVE, 0, null));
        assertThat(Isolation.classify(List.of(passed)))
                .isEqualTo(new Isolation.Classification(IsolationClass.INTERFERENCE, 1, null));
    }

    @Test
    void narrows_cargo_to_the_exact_test() {
        AdapterRequest request = request(Path.of("/work"), "unit", "--nocapture");
        assertThat(args(Isolation.narrow("cargo", null, request, failed("pricing::tests::rounds"))))
                .containsExactly("--nocapture", "pricing::tests::rounds", "--exact");
    }

    @Test
    void narrows_maven_to_the_method_or_the_ordered_class(@TempDir Path root) throws IOException {
        Path tests = Files.createDirectories(root.resolve("prices/src/test/java/com/shop"));
        Files.writeString(tests.resolve("PriceTest.java"), "class PriceTest {}\n", StandardCharsets.UTF_8);
        Files.writeString(
                tests.resolve("CheckoutIT.java"),
                "@TestMethodOrder(MethodOrderer.OrderAnnotation.class)\nclass CheckoutIT {}\n",
                StandardCharsets.UTF_8);
        Files.writeString(
                tests.resolve("CartTest.java"),
                "@TestInstance(TestInstance.Lifecycle.PER_CLASS)\nclass CartTest {}\n",
                StandardCharsets.UTF_8);
        AdapterRequest unit = request(root, "unit", "-Dtest=Price*", "-q");
        assertThat(args(Isolation.narrow("maven", null, unit, failed("com.shop.PriceTest::rounds_half_even(int)[2]"))))
                .containsExactly(
                        "-q", "-Dtest=com.shop.PriceTest#rounds_half_even", "-Dsurefire.failIfNoSpecifiedTests=false");
        assertThat(args(Isolation.narrow("maven", null, unit, failed("com.shop.CartTest$Totals::adds"))))
                .containsExactly("-q", "-Dtest=com.shop.CartTest$Totals", "-Dsurefire.failIfNoSpecifiedTests=false");
        AdapterRequest integration = request(root, "integration", "-Dit.test=CheckoutIT", "-Dtest=x");
        assertThat(args(Isolation.narrow("maven", null, integration, failed("com.shop.CheckoutIT::pays"))))
                .containsExactly(
                        "-Dtest=x",
                        "-Dit.test=com.shop.CheckoutIT",
                        "-Dsurefire.failIfNoSpecifiedTests=false",
                        "-Dit.failIfNoSpecifiedTests=false");
        assertThat(Isolation.runsAsAClass(root.resolve("prices"), "com.shop.CheckoutIT"))
                .isTrue();
        assertThat(Isolation.runsAsAClass(root.resolve("prices"), "com.shop.Missing"))
                .isFalse();
        assertThat(Isolation.narrow("maven", null, unit, failed("suite setup")))
                .isEqualTo(new Isolation.Narrowing.Unsupported("`suite setup` does not name a test class and method"));
    }

    @Test
    void narrows_vitest_to_the_file_and_anchored_name() {
        Path root = Path.of("/work");
        AdapterRequest base = request(root, "unit", "exec", "vitest", "run", "--coverage");
        AdapterRequest request = new AdapterRequest(
                base.protocol(),
                base.project(),
                base.packageName(),
                base.phase(),
                base.root(),
                base.manifest(),
                root.resolve("web"),
                base.output(),
                base.execution(),
                base.coverage(),
                base.strict(),
                base.args(),
                base.env(),
                base.options());
        TestResult test = failed("cart > adds (2) items").withSource(Path.of("/work/web/src/cart.test.ts"));
        assertThat(args(Isolation.narrow("vitest", null, request, test)))
                .containsExactly(
                        "exec", "vitest", "run", "--coverage", "src/cart.test.ts", "-t", "^cart > adds \\(2\\) items$");
        AdapterRequest bare = request.forRerun(request.output(), List.of(), null);
        assertThat(args(Isolation.narrow("vitest", null, bare, test)).subList(0, 2))
                .containsExactly("run", "src/cart.test.ts");
        assertThat(Isolation.narrow("vitest", null, request, test.withSource(null)))
                .isEqualTo(new Isolation.Narrowing.Unsupported(
                        "Vitest did not report the file of `cart > adds (2) items`"));
        assertThat(Isolation.regexEscape("a.b*c[d]/e")).isEqualTo("a\\.b\\*c\\[d\\]\\/e");
    }

    @Test
    void registered_adapters_opt_in_with_select_or_stay_inconclusive() {
        AdapterRequest request = request(Path.of("/work"), "functional");
        AdapterConfig config = new AdapterConfig("shop-adapter", List.of(), 60, false);
        assertThat(Isolation.narrow("shop", config, request, failed("checkout")))
                .isEqualTo(new Isolation.Narrowing.Unsupported("adapter `shop` does not declare `isolate: true`"));
        AdapterConfig optedIn = new AdapterConfig("shop-adapter", List.of(), 60, true);
        assertThat(Isolation.narrow("shop", optedIn, request, failed("checkout")))
                .isEqualTo(new Isolation.Narrowing.Select(List.of("checkout")));
        for (String adapter : List.of("gradle", "cmake", "playwright", "command")) {
            assertThat(Isolation.narrow(adapter, null, request, failed("x")))
                    .isEqualTo(
                            new Isolation.Narrowing.Unsupported("adapter `" + adapter + "` cannot run a single test"));
        }
    }

    @Test
    void resolves_isolation_from_flags_and_config() {
        IsolateConfig on = new IsolateConfig(true, 5, 2);
        assertThat(Isolation.Settings.resolve(false, false, null)).isNull();
        assertThat(Isolation.Settings.resolve(true, false, null)).isEqualTo(new Isolation.Settings(3, 10));
        assertThat(Isolation.Settings.resolve(false, false, on)).isEqualTo(new Isolation.Settings(5, 2));
        assertThat(Isolation.Settings.resolve(false, true, on)).isNull();
    }

    private static Isolation.IsolatedTest entry(
            String test, IsolationClass value, long passed, @Nullable String reason) {
        return new Isolation.IsolatedTest(
                "shop",
                "prices",
                "unit",
                test,
                value,
                reason != null ? 0 : 3,
                passed,
                reason != null
                        ? List.of()
                        : LongStream.rangeClosed(1, 3)
                                .mapToObj(attempt -> "isolation/shop/prices/unit/1/" + attempt)
                                .toList(),
                reason);
    }

    @Test
    void writes_isolation_json_and_terminal_lines() throws IOException {
        Isolation.Report report = new Isolation.Report(
                3,
                2,
                3,
                List.of(
                        entry("b_needs_order", IsolationClass.INTERFERENCE, 3, null),
                        entry(
                                "login",
                                IsolationClass.INCONCLUSIVE,
                                0,
                                "adapter `playwright` cannot run a single test")));
        assertThat(report.lines())
                .containsExactly(
                        "==> isolate 2 failed tests: each alone, 3 runs",
                        "  INTERFERENCE  shop/prices/unit  b_needs_order  3/3 passed alone",
                        "  INCONCLUSIVE  shop/prices/unit  login  adapter `playwright` cannot run a single test",
                        "  1 more failed test not isolated (max_tests 2)");
        assertThat(report.counts())
                .containsExactly(
                        Map.entry(IsolationClass.INTERFERENCE, 1L), Map.entry(IsolationClass.INCONCLUSIVE, 1L));
        String golden =
                Files.readString(Path.of("../../test/cherry-test/tests/golden/isolation.json"), StandardCharsets.UTF_8);
        assertThat(report.json()).isEqualTo(golden.stripTrailing());
        assertThat(new Isolation.Report(3, 10, 0, List.of()).lines()).containsExactly("==> isolate: no failed tests");
    }
}
