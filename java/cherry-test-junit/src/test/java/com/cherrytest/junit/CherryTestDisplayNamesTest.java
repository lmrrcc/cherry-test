package com.cherrytest.junit;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class CherryTestDisplayNamesTest {
    private final CherryTestDisplayNames names = new CherryTestDisplayNames();

    @Test
    void names_cherry_tests_like_the_rust_test_function() throws NoSuchMethodException {
        Method method =
                HealthFixture.class.getDeclaredMethod("demo_project_health_suite_responds_ok_test", TestEnv.class);
        assertThat(names.generateDisplayNameForMethod(List.of(), HealthFixture.class, method))
                .isEqualTo("demo_project_health_suite_responds_ok_test");
    }

    @Test
    void keeps_the_standard_name_of_other_test_methods() throws NoSuchMethodException {
        Method method = IntegrationReportTest.class.getDeclaredMethod(
                "appends_records_to_the_package_file_under_the_report_dir", Path.class);
        assertThat(names.generateDisplayNameForMethod(List.of(), IntegrationReportTest.class, method))
                .isEqualTo("appends_records_to_the_package_file_under_the_report_dir(Path)");
    }
}
