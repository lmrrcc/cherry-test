package com.cherrytest.cli.adapters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cherrytest.common.adapter.TestResult;
import com.cherrytest.common.adapter.TestStatus;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FormatsTest {
    @Nested
    class rust_tests {
        private static final String FAILED_RUN = """
                running 2 tests
                test catalog::lists ... ok
                test catalog::reports_a_price_mismatch ... FAILED

                failures:

                ---- catalog::reports_a_price_mismatch stdout ----

                thread 'catalog::reports_a_price_mismatch' (4523666) panicked at src/tests.rs:217:9:
                assertion `left == right` failed: listed price did not match the expected 1500
                  left: 1800
                 right: 1500
                note: run with `RUST_BACKTRACE=1` environment variable to display a backtrace


                failures:
                    catalog::reports_a_price_mismatch

                test result: FAILED. 1 passed; 1 failed; 0 ignored; 0 measured; 0 filtered out
                """;

        @Test
        void keeps_the_panic_message_of_a_failed_test() {
            assertThat(Formats.rustTests(FAILED_RUN).get(1).message())
                    .isEqualTo("assertion `left == right` failed: listed price did not match the expected 1500\n"
                            + "  left: 1800\n right: 1500");
        }

        @Test
        void leaves_passed_tests_without_a_message() {
            assertThat(Formats.rustTests(FAILED_RUN).get(0).message()).isNull();
        }

        @Test
        void keeps_a_failure_without_captured_output_unexplained() {
            TestResult row =
                    Formats.rustTests("test catalog::times_out ... FAILED\n").getFirst();
            assertThat(row.status()).isEqualTo(TestStatus.FAILED);
            assertThat(row.message()).isNull();
        }

        @Test
        void keeps_the_file_of_the_failed_assertion() {
            assertThat(Formats.rustTests(FAILED_RUN).get(1).source()).isEqualTo(Path.of("src/tests.rs"));
        }

        @Test
        void keeps_the_line_of_the_failed_assertion() {
            assertThat(Formats.rustTests(FAILED_RUN).get(1).line()).isEqualTo(217L);
        }

        @Test
        void reads_a_location_on_the_line_after_the_thread_name() {
            String output =
                    "test a::b ... FAILED\n---- a::b stdout ----\nthread 'a::b' panicked at\nsrc/lib.rs:9:5:\nboom\n";
            TestResult row = Formats.rustTests(output).getFirst();
            assertThat(row.source()).isEqualTo(Path.of("src/lib.rs"));
            assertThat(row.line()).isEqualTo(9L);
        }
    }

    @Test
    void junit_preserves_failures_skips_and_parameterized_names() {
        String xml = "<testsuite><testcase classname=\"Prices\" name=\"round[1]\" time=\"0.125\"/><testcase "
                + "name=\"upstream\"><failure message=\"expected 502\">received 200</failure></testcase><testcase "
                + "name=\"optional\"><skipped/></testcase></testsuite>";
        List<TestResult> rows = Formats.junit(xml);
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).durationMs()).isEqualTo(125);
        assertThat(rows.get(1).status()).isEqualTo(TestStatus.FAILED);
        assertThat(rows.get(1).message()).contains("received 200");
        assertThat(rows.get(2).status()).isEqualTo(TestStatus.SKIPPED);
    }

    @Test
    void junit_rejects_external_entities_and_wrong_documents() {
        assertThatThrownBy(() -> Formats.junit("<!DOCTYPE a [<!ENTITY secret SYSTEM \"file:///etc/passwd\">]>"
                        + "<testsuite><testcase name=\"x\">&secret;</testcase></testsuite>"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> Formats.junit("<project/>")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> Formats.junit("<testsuite><testcase name='x' time='NaN'/></testsuite>"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void jacoco_resolves_source_and_preserves_uncovered_lines(@TempDir Path temp) throws IOException {
        Files.createDirectory(temp.resolve("prices"));
        Files.writeString(temp.resolve("prices/Rate.java"), "class Rate {}\n", StandardCharsets.UTF_8);
        String xml = "<?xml version=\"1.0\"?><!DOCTYPE report PUBLIC \"-//JACOCO//DTD Report 1.1//EN\" "
                + "\"report.dtd\"><report><package name=\"prices\"><sourcefile name=\"Rate.java\"><line nr=\"1\" "
                + "ci=\"3\" mi=\"0\"/><line nr=\"2\" ci=\"0\" mi=\"4\"/></sourcefile></package></report>";
        assertThat(Formats.jacoco(xml, List.of(temp))).contains("DA:1,1\nDA:2,0");
        assertThatThrownBy(() -> Formats.jacoco(xml, List.of())).isInstanceOf(RuntimeException.class);
    }

    @Test
    void playwright_keeps_failed_screenshot_attachments() {
        String data = "{\"suites\":[{\"specs\":[{\"title\":\"price\",\"file\":\"price.spec.ts\",\"line\":4,"
                + "\"tests\":[{\"projectName\":\"java\",\"status\":\"unexpected\",\"results\":[{\"duration\":12,"
                + "\"error\":{\"message\":\"pixels differ\"},\"attachments\":[{\"name\":\"diff\",\"path\":"
                + "\"/tmp/diff.png\",\"contentType\":\"image/png\"}]}]}]}]}]}";
        TestResult row = Formats.playwright(data).getFirst();
        assertThat(row.status()).isEqualTo(TestStatus.FAILED);
        assertThat(row.attachments().getFirst().name()).isEqualTo("diff");
    }
}
