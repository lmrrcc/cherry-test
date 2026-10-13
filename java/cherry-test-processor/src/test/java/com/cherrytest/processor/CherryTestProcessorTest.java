package com.cherrytest.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringWriter;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CherryTestProcessorTest {
    private static final String ANNOTATION = """
            package com.cherrytest.junit;
            public @interface CherryTest { String scope() default ""; String auth() default ""; }
            """;
    private static final String TEST_ENV = """
            package com.cherrytest.junit;
            public final class TestEnv {}
            """;

    private static JavaFileObject source(String name, String content) {
        return new SimpleJavaFileObject(
                URI.create("string:///" + name.replace('.', '/') + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return content;
            }
        };
    }

    private static List<String> errors(String method) {
        String test = "package demo;\nimport com.cherrytest.junit.*;\nclass WalletIT {\n" + method + "\n}\n";
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        JavaCompiler.CompilationTask task = compiler.getTask(
                new StringWriter(),
                null,
                diagnostics,
                List.of("-proc:only"),
                null,
                List.of(
                        source("com.cherrytest.junit.CherryTest", ANNOTATION),
                        source("com.cherrytest.junit.TestEnv", TEST_ENV),
                        source("demo.WalletIT", test)));
        task.setProcessors(List.of(new CherryTestProcessor()));
        task.call();
        return diagnostics.getDiagnostics().stream()
                .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                .map(diagnostic -> diagnostic.getMessage(Locale.ROOT))
                .toList();
    }

    @Nested
    class attributes {
        @Test
        void parses_scope_only_attr() {
            assertThat(
                            errors(
                                    "@CherryTest(scope = \"mini_wallet\") void mini_project_wallet_suite_get_balance_test(TestEnv env) {}"))
                    .isEmpty();
        }

        @Test
        void parses_auth_only_attr() {
            assertThat(
                            errors(
                                    "@CherryTest(auth = \"okta\") void mini_project_wallet_suite_get_balance_test(TestEnv env) {}"))
                    .isEmpty();
        }

        @Test
        void parses_combined_scope_and_auth_attr() {
            assertThat(
                            errors(
                                    "@CherryTest(scope = \"mini_auth\", auth = \"okta\") void mini_project_auth_suite_me_test(TestEnv env) {}"))
                    .isEmpty();
        }
    }

    @Nested
    class naming {
        @Test
        void rejects_a_name_without_the_test_suffix() {
            assertThat(errors("@CherryTest void mini_project_wallet_suite_balance(TestEnv env) {}"))
                    .containsExactly("cherry-test name `mini_project_wallet_suite_balance` must end with `_test`; "
                            + "expected {project}_project_{suite}_suite_{test}_test");
        }

        @Test
        void rejects_a_name_without_a_suite() {
            assertThat(errors("@CherryTest void mini_project_balance_test(TestEnv env) {}"))
                    .containsExactly("cherry-test name `mini_project_balance_test` must match "
                            + "{project}_project_{suite}_suite_{test}_test");
        }

        @Test
        void rejects_an_empty_project() {
            assertThat(errors("@CherryTest void _project_wallet_suite_balance_test(TestEnv env) {}"))
                    .containsExactly("cherry-test name `_project_wallet_suite_balance_test` must match "
                            + "{project}_project_{suite}_suite_{test}_test");
        }
    }

    @Nested
    class signature {
        @Test
        void rejects_a_method_without_a_test_env_parameter() {
            assertThat(errors("@CherryTest void mini_project_wallet_suite_balance_test() {}"))
                    .containsExactly("cherry-test integration tests must take a single `env: TestEnv` parameter");
        }

        @Test
        void rejects_a_method_with_a_return_type() {
            assertThat(errors("@CherryTest int mini_project_wallet_suite_balance_test(TestEnv env) { return 0; }"))
                    .containsExactly("cherry-test integration tests must not declare a return type");
        }

        @Test
        void rejects_a_static_method() {
            assertThat(errors("@CherryTest static void mini_project_wallet_suite_balance_test(TestEnv env) {}"))
                    .containsExactly("cherry-test integration tests must be non-private instance methods");
        }
    }
}
