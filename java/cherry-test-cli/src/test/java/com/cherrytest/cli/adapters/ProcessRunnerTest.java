package com.cherrytest.cli.adapters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cherrytest.common.rustcompat.AnyhowError;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProcessRunnerTest {
    @Nested
    class capture {
        @Test
        void names_the_program_that_could_not_start(@TempDir Path logs) {
            ProcessRunner.Spec spec =
                    new ProcessRunner.Spec("cherry-test-missing-runner", List.of(), Map.of(), null, null);
            assertThatThrownBy(() -> ProcessRunner.capture(spec, logs.resolve("tests"), 5, false))
                    .isInstanceOfSatisfying(
                            AnyhowError.class,
                            error -> assertThat(AnyhowError.alternate(error)).contains("`cherry-test-missing-runner`"));
        }
    }
}
