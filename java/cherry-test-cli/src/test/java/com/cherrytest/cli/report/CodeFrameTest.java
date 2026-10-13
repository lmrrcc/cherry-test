package com.cherrytest.cli.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class CodeFrameTest {
    private static final String PLAYWRIGHT = """
            Error: page.goto: Page crashed

               6 |   test(`shows the value`, async ({ page }) => {
            >  7 |     await page.goto(url);
                 |                ^
               8 |   });
                at /opt/mini/polyglot/browser/functional.spec.ts:7:16""";

    private static CodeFrame.FramePart frame(List<CodeFrame.ReasonPart> parts) {
        return parts.stream()
                .filter(CodeFrame.FramePart.class::isInstance)
                .map(CodeFrame.FramePart.class::cast)
                .findFirst()
                .orElseThrow();
    }

    @Test
    void keeps_the_message_before_the_frame_as_text() {
        assertThat(CodeFrame.splitReason(PLAYWRIGHT).getFirst())
                .isEqualTo(new CodeFrame.TextPart("Error: page.goto: Page crashed"));
    }

    @Test
    void reads_line_numbers_and_code_with_its_indentation() {
        CodeFrame.FrameLine first =
                frame(CodeFrame.splitReason(PLAYWRIGHT)).lines().getFirst();
        assertThat(first.number()).isEqualTo(6L);
        assertThat(first.text()).isEqualTo("  test(`shows the value`, async ({ page }) => {");
    }

    @Test
    void marks_the_failing_line() {
        List<CodeFrame.FrameLine> lines =
                frame(CodeFrame.splitReason(PLAYWRIGHT)).lines();
        assertThat(lines.get(1).marked()).isTrue();
        assertThat(lines.get(0).marked()).isFalse();
    }

    @Test
    void keeps_the_column_pointer_without_a_number() {
        CodeFrame.FrameLine pointer =
                frame(CodeFrame.splitReason(PLAYWRIGHT)).lines().get(2);
        assertThat(pointer.number()).isNull();
        assertThat(pointer.text().strip()).isEqualTo("^");
    }

    @Test
    void takes_the_source_path_from_the_stack_line() {
        assertThat(frame(CodeFrame.splitReason(PLAYWRIGHT)).source())
                .isEqualTo("/opt/mini/polyglot/browser/functional.spec.ts");
    }

    @Test
    void reads_the_source_inside_parentheses() {
        String reason = "boom\n> 3 | throw err\n    at run (src/app.test.ts:3:9)";
        assertThat(frame(CodeFrame.splitReason(reason)).source()).isEqualTo("src/app.test.ts");
    }

    @Test
    void keeps_the_stack_line_as_text_after_the_frame() {
        assertThat(CodeFrame.splitReason(PLAYWRIGHT).getLast())
                .isEqualTo(new CodeFrame.TextPart("    at /opt/mini/polyglot/browser/functional.spec.ts:7:16"));
    }

    @Test
    void leaves_a_reason_without_a_frame_as_one_text_part() {
        String reason = "assertion `left == right` failed\n  left: 1800\n right: 1500";
        assertThat(CodeFrame.splitReason(reason)).containsExactly(new CodeFrame.TextPart(reason));
    }

    @Test
    void does_not_treat_a_table_cell_as_a_frame() {
        String reason = "expected | actual";
        assertThat(CodeFrame.splitReason(reason)).containsExactly(new CodeFrame.TextPart(reason));
    }

    @Test
    void splits_two_frames_with_text_between_them() {
        String reason = "first\n> 1 | a()\n    at x.ts:1:1\nsecond\n> 2 | b()\n    at x.ts:2:1";
        assertThat(CodeFrame.splitReason(reason).stream().filter(CodeFrame.FramePart.class::isInstance))
                .hasSize(2);
    }
}
