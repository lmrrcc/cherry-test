package com.cherrytest.junit;

import com.cherrytest.common.process.ProcessEnvironment;
import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.common.rustcompat.RustIoError;
import com.cherrytest.common.rustcompat.SerdeJsonWriter;
import com.cherrytest.core.Workspace;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

final class IntegrationReport {
    private IntegrationReport() {}

    static Path resolveReportDir() {
        String reportDir = ProcessEnvironment.get("CHERRY_TEST_REPORT_DIR");
        if (reportDir != null) {
            return Path.of(reportDir);
        }
        Path root = Workspace.workspaceRoot();
        String runDir = ProcessEnvironment.get("CHERRY_TEST_RUN_DIR");
        if (runDir != null) {
            return Path.of(runDir).resolve("reports");
        }
        return root.resolve("target/cherry-test/reports");
    }

    static void appendRecords(String packageName, List<HttpRecord> records) {
        if (records.isEmpty()) {
            return;
        }
        Path integrationDir = resolveReportDir().resolve("integration");
        Path path = integrationDir.resolve(packageName + ".jsonl");
        StringBuilder lines = new StringBuilder();
        for (HttpRecord record : records) {
            lines.append(SerdeJsonWriter.compact(SerdeJsonWriter.withSortedKeys(line(record))))
                    .append('\n');
        }
        try {
            Files.createDirectories(integrationDir);
        } catch (IOException exception) {
            throw new AnyhowError(RustIoError.display(exception));
        }
        try {
            Files.writeString(
                    path,
                    lines,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                    StandardOpenOption.WRITE);
        } catch (IOException exception) {
            throw AnyhowError.context("open " + path, new AnyhowError(RustIoError.display(exception)));
        }
    }

    static ObjectNode line(HttpRecord record) {
        ObjectNode line = JsonNodeFactory.instance.objectNode();
        line.put("project", record.testId().project());
        line.put("suite", record.testId().suite());
        line.put("test", record.testId().test());
        line.put("full_name", record.testId().fullName());
        line.put("scope", record.scope());
        line.put("target_project", record.targetProject());
        line.put("target_service", record.targetService());
        line.put("elapsed_ms", record.elapsedMs());
        line.put("method", record.method());
        line.put("url", record.url());
        line.put("status", record.status());
        line.put("passed", record.passed());
        line.put("reason", record.failureReason());
        line.put("request_body", record.requestBody());
        line.put("authorization", record.authorization());
        line.put("response_body", record.responseBody());
        return line;
    }
}
