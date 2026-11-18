package com.cherrytest.junit;

import com.cherrytest.common.process.ProcessEnvironment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.extension.ExtensionContext;
import tools.jackson.databind.node.JsonNodeFactory;

final class NativeTestProgress {
    private static final ExtensionContext.Namespace STORE = ExtensionContext.Namespace.create(NativeTestProgress.class);

    private record Invocation(String id, long started) {}

    private NativeTestProgress() {}

    static void started(ExtensionContext context) {
        Invocation invocation = new Invocation(UUID.randomUUID().toString(), System.nanoTime());
        context.getStore(STORE).put(Invocation.class, invocation);
        emit(context, invocation, "started", "pending", null);
    }

    static void completed(ExtensionContext context, String status, @Nullable Throwable failure) {
        Invocation invocation = context.getStore(STORE).get(Invocation.class, Invocation.class);
        if (invocation == null) invocation = new Invocation(UUID.randomUUID().toString(), System.nanoTime());
        emit(context, invocation, "completed", status, failure);
    }

    private static void emit(
            ExtensionContext context, Invocation invocation, String event, String status, @Nullable Throwable failure) {
        String configured = ProcessEnvironment.get("CHERRY_TEST_IDE_TEST_EVENTS");
        if (configured == null || configured.isBlank()) return;
        var method = context.getTestMethod();
        var type = context.getTestClass();
        if (method.isEmpty() || type.isEmpty()) return;
        var node = JsonNodeFactory.instance.objectNode();
        node.put("schemaVersion", 1);
        node.put("event", "test." + event);
        node.put("invocationId", invocation.id());
        String declaration = type.get().getName() + "::" + method.get().getName();
        String signature = declaration + "("
                + String.join(
                        ",",
                        java.util.Arrays.stream(method.get().getParameterTypes())
                                .map(Class::getSimpleName)
                                .toList())
                + ")";
        node.put("name", signature);
        node.put("declaredName", declaration);
        var aliases = node.putArray("reportedNameAliases");
        aliases.add(declaration);
        aliases.add(signature);
        node.put("status", status);
        node.put("processId", ProcessHandle.current().pid());
        node.put("timestamp", java.time.Instant.now().toString());
        node.put("durationMs", event.equals("started") ? 0 : (System.nanoTime() - invocation.started()) / 1_000_000L);
        if (failure != null) node.put("message", failure.toString());
        Path directory = Path.of(configured);
        try {
            Files.createDirectories(directory);
            Path temporary = Files.createTempFile(directory, ".event-", ".tmp");
            Files.writeString(temporary, node.toString());
            Files.move(
                    temporary,
                    directory.resolve(invocation.id() + "-" + event + ".json"),
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ignored) {
            System.err.println("Cherry Test native progress unavailable; final test report remains authoritative");
        }
    }
}
