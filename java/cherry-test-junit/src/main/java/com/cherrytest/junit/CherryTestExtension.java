package com.cherrytest.junit;

import com.cherrytest.common.process.EnvFile;
import com.cherrytest.common.process.ProcessEnvironment;
import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.core.Auth;
import com.cherrytest.core.Scope;
import com.cherrytest.core.ServiceEndpoint;
import com.cherrytest.core.TestId;
import com.cherrytest.core.TestItConfig;
import com.cherrytest.core.Workspace;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;

public final class CherryTestExtension implements ParameterResolver, InvocationInterceptor {
    static final String PACKAGE_PROPERTY = "cherrytest.package";
    static final String PACKAGE_VARIABLE = "CHERRY_TEST_PACKAGE";
    static final String PROVISIONER_VARIABLE = "CHERRY_TEST_PROVISIONER";
    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(CherryTestExtension.class);

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        return parameterContext.getParameter().getType().equals(TestEnv.class);
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        Method method = extensionContext.getRequiredTestMethod();
        CherryTest annotation = method.getAnnotation(CherryTest.class);
        if (annotation == null) {
            throw new AnyhowError("TestEnv is only available to @CherryTest methods");
        }
        if (standalone()) {
            extensionContext
                    .getRoot()
                    .getStore(NAMESPACE)
                    .computeIfAbsent(ScopeTeardown.class, key -> new ScopeTeardown(), ScopeTeardown.class);
        }
        TestEnv env = prepare(method.getName(), blankToNull(annotation.scope()), blankToNull(annotation.auth()));
        extensionContext.getStore(NAMESPACE).put(TestEnv.class, env);
        return env;
    }

    @Override
    public void interceptTestMethod(
            Invocation<@Nullable Void> invocation,
            ReflectiveInvocationContext<Method> invocationContext,
            ExtensionContext extensionContext)
            throws Throwable {
        TestEnv env = extensionContext.getStore(NAMESPACE).get(TestEnv.class, TestEnv.class);
        if (env == null || invocationContext.getArguments().size() != 1) {
            invocation.skip();
            throw new AnyhowError("cherry-test integration tests must take a single `env: TestEnv` parameter");
        }
        Throwable failure = null;
        try {
            invocation.proceed();
        } catch (Throwable thrown) {
            failure = thrown;
        }
        String reason = failure == null ? null : failureReason(failure);
        List<HttpRecord> records = env.drainRecords().stream()
                .map(record -> record.withOutcome(reason == null, reason))
                .toList();
        IntegrationReport.appendRecords(packageName(), records);
        if (failure != null) {
            throw failure;
        }
    }

    static TestEnv prepare(String fullName, @Nullable String attributeScope, @Nullable String attributeAuth) {
        if (standalone() && ProcessEnvironment.get(PROVISIONER_VARIABLE) == null) {
            ProcessEnvironment.set(PROVISIONER_VARIABLE, "testcontainers");
        }
        TestId testId = TestId.parse(fullName);
        Path root = Workspace.workspaceRoot();
        if (standalone()) {
            EnvFile.apply(root, testId.project());
        }
        TestItConfig config = TestItConfig.load(root);
        config.project(testId.project());
        String scope = Scope.effectiveScope(config, attributeScope);
        Map<String, ServiceEndpoint> registry =
                Scope.isNone(scope) ? Workspace.bootScope("none") : Workspace.bootScope(scope);
        if (attributeAuth != null) {
            Auth.ensureProfileToken(root, attributeAuth, config.authProfile(attributeAuth));
        }
        return new TestEnv(testId, scope, attributeAuth, registry);
    }

    static boolean standalone() {
        return ProcessEnvironment.get("CHERRY_TEST_RUN_ID") == null
                && ProcessEnvironment.get("CHERRY_TEST_SKIP_DOCKER") == null;
    }

    static final class ScopeTeardown implements AutoCloseable {
        @Override
        public void close() {
            Workspace.teardownScopes();
        }
    }

    static String failureReason(Throwable failure) {
        if (failure instanceof AnyhowError) {
            return AnyhowError.alternate(failure);
        }
        String message = failure.getMessage();
        return message == null ? failure.getClass().getName() : message;
    }

    static String packageName() {
        String property = System.getProperty(PACKAGE_PROPERTY);
        if (property != null && !property.isEmpty()) {
            return property;
        }
        String variable = ProcessEnvironment.get(PACKAGE_VARIABLE);
        if (variable != null && !variable.isEmpty()) {
            return variable;
        }
        throw new AnyhowError("cherry-test package name unknown: set the " + PACKAGE_PROPERTY
                + " system property (failsafe systemPropertyVariables) or " + PACKAGE_VARIABLE);
    }

    private static @Nullable String blankToNull(String value) {
        return value.isEmpty() ? null : value;
    }
}
