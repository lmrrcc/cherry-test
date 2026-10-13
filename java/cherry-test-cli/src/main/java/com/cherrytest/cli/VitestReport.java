package com.cherrytest.cli;

import com.cherrytest.cli.adapters.Json;
import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.common.rustcompat.RustIoError;
import com.cherrytest.common.rustcompat.U64;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

public final class VitestReport {
    private VitestReport() {}

    public static List<JsonNode> readUnitRows(Path reportDir) {
        Path unitDir = reportDir.resolve("unit");
        if (!Files.exists(unitDir)) {
            return List.of();
        }
        List<JsonNode> rows = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(unitDir)) {
            for (Path path : entries) {
                Path fileName = path.getFileName();
                String name = fileName == null ? "" : fileName.toString();
                int dot = name.lastIndexOf('.');
                if (dot <= 0 || !name.substring(dot + 1).equals("json")) {
                    continue;
                }
                String content;
                try {
                    content = Files.readString(path, StandardCharsets.UTF_8);
                } catch (IOException exception) {
                    throw AnyhowError.context("read " + path, new AnyhowError(RustIoError.display(exception)));
                }
                rows.addAll(parseVitestJson(content, name.substring(0, dot)));
            }
        } catch (IOException exception) {
            throw new AnyhowError(RustIoError.display(exception), null);
        }
        return rows;
    }

    public static List<JsonNode> parseVitestJson(String content, String packageName) {
        JsonNode root = Json.parse(content);
        List<JsonNode> rows = new ArrayList<>();
        String project = inferProjectId(packageName);
        JsonNode suites = Json.index(root, "testResults");
        if (!suites.isArray()) {
            return rows;
        }
        for (JsonNode suite : suites) {
            JsonNode assertions = Json.index(suite, "assertionResults");
            if (!assertions.isArray()) {
                continue;
            }
            for (JsonNode assertion : assertions) {
                String title = Json.string(Json.index(assertion, "title"));
                String testSlug = slugify(title == null ? "unnamed" : title);
                String statusText = Json.string(Json.index(assertion, "status"));
                String status = switch (statusText == null ? "failed" : statusText) {
                    case "passed" -> "ok";
                    case "skipped", "pending", "todo" -> "skipped";
                    default -> "FAIL";
                };
                Double duration = Json.f64(Json.index(assertion, "duration"));
                long elapsed = U64.fromF64(roundHalfAwayFromZero(duration == null ? 0.0 : duration));
                String reason = null;
                if (status.equals("FAIL")) {
                    JsonNode messages = Json.index(assertion, "failureMessages");
                    if (messages.isArray()
                            && !messages.isEmpty()
                            && messages.get(0).isString()) {
                        reason = messages.get(0).stringValue();
                    }
                } else if (status.equals("skipped")) {
                    reason = "skipped";
                }
                ObjectNode row = JsonNodeFactory.instance.objectNode();
                row.put("elapsed_ms", elapsed);
                row.put("full_name", project + "_project_unit_suite_" + testSlug + "_test");
                row.put("method", "");
                row.put("project", project);
                if (reason == null) {
                    row.putNull("reason");
                } else {
                    row.put("reason", reason);
                }
                row.put("scope", "none");
                row.put("status", status);
                row.put("suite", "unit");
                row.put("target_project", "");
                row.put("target_service", "");
                row.put("test", testSlug);
                row.put("url", "");
                rows.add(row);
            }
        }
        return rows;
    }

    static double roundHalfAwayFromZero(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return value;
        }
        double magnitude = Math.abs(value);
        double floor = Math.floor(magnitude);
        double rounded = magnitude - floor >= 0.5 ? floor + 1.0 : floor;
        return Math.copySign(rounded, value);
    }

    private static String inferProjectId(String packageName) {
        int dash = packageName.indexOf('-');
        return dash < 0 ? packageName : packageName.substring(0, dash);
    }

    public static String slugify(String input) {
        StringBuilder out = new StringBuilder();
        boolean previousUnderscore = false;
        int index = 0;
        while (index < input.length()) {
            int character = input.codePointAt(index);
            index += Character.charCount(character);
            boolean alphanumeric = (character >= 'a' && character <= 'z')
                    || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9');
            if (alphanumeric) {
                out.append((char) (character >= 'A' && character <= 'Z' ? character + 32 : character));
                previousUnderscore = false;
            } else if (!previousUnderscore) {
                out.append('_');
                previousUnderscore = true;
            }
        }
        int start = 0;
        int end = out.length();
        while (start < end && out.charAt(start) == '_') {
            start++;
        }
        while (end > start && out.charAt(end - 1) == '_') {
            end--;
        }
        String trimmed = out.substring(start, end);
        return trimmed.isEmpty() ? "unnamed" : trimmed;
    }
}
