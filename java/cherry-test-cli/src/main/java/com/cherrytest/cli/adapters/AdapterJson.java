package com.cherrytest.cli.adapters;

import com.cherrytest.common.adapter.AdapterRequest;
import com.cherrytest.common.adapter.AdapterResponse;
import com.cherrytest.common.adapter.Attachment;
import com.cherrytest.common.adapter.ExecutionMode;
import com.cherrytest.common.adapter.TestResult;
import com.cherrytest.common.adapter.TestStatus;
import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.common.rustcompat.RustOrdering;
import com.cherrytest.common.rustcompat.SerdeJsonWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

public final class AdapterJson {
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private AdapterJson() {}

    public static String requestCompact(AdapterRequest request) {
        ObjectNode node = NODES.objectNode();
        node.put("protocol", request.protocol());
        node.put("project", request.project());
        node.put("package", request.packageName());
        node.put("phase", request.phase());
        node.put("root", request.root().toString());
        node.put("manifest", request.manifest().toString());
        node.put("cwd", request.cwd().toString());
        node.put("output", request.output().toString());
        node.put("execution", request.execution().wireName());
        node.put("coverage", request.coverage());
        node.put("strict", request.strict());
        ArrayNode args = node.putArray("args");
        request.args().forEach(args::add);
        ObjectNode env = node.putObject("env");
        request.env().forEach(env::put);
        ObjectNode options = node.putObject("options");
        request.options().forEach((key, value) -> options.set(key, SerdeJsonWriter.withSortedKeys(value)));
        return SerdeJsonWriter.compact(node);
    }

    public static ObjectNode responseValue(AdapterResponse response) {
        ObjectNode node = NODES.objectNode();
        ArrayNode coverage = node.putArray("coverage");
        response.coverage().forEach(path -> coverage.add(path.toString()));
        ArrayNode diagnostics = node.putArray("diagnostics");
        response.diagnostics().forEach(diagnostics::add);
        node.put("protocol", response.protocol());
        node.put("success", response.success());
        ArrayNode tests = node.putArray("tests");
        response.tests().forEach(test -> tests.add(testValue(test)));
        return node;
    }

    private static ObjectNode testValue(TestResult test) {
        ObjectNode node = NODES.objectNode();
        ArrayNode attachments = node.putArray("attachments");
        for (Attachment attachment : test.attachments()) {
            ObjectNode item = NODES.objectNode();
            item.put("media_type", attachment.mediaType());
            item.put("name", attachment.name());
            item.put("path", attachment.path().toString());
            attachments.add(item);
        }
        node.set("duration_ms", NODES.numberNode(new java.math.BigInteger(Long.toUnsignedString(test.durationMs()))));
        if (test.line() == null) {
            node.putNull("line");
        } else {
            node.put("line", test.line());
        }
        if (test.message() == null) {
            node.putNull("message");
        } else {
            node.put("message", test.message());
        }
        node.put("name", test.name());
        if (test.source() == null) {
            node.putNull("source");
        } else {
            node.put("source", test.source().toString());
        }
        node.put("status", test.status().wireName());
        return node;
    }

    public static AdapterResponse parseResponse(byte[] content) {
        JsonNode node = Json.parseBytes(content);
        return responseFrom(node);
    }

    public static AdapterResponse responseFrom(JsonNode node) {
        if (!node.isObject()) {
            throw new AnyhowError("invalid type: " + describe(node) + ", expected struct AdapterResponse");
        }
        long protocol = requiredU64(node, "protocol", 0xFFFFFFFFL);
        boolean success = requiredBool(node, "success");
        List<TestResult> tests = new ArrayList<>();
        JsonNode testsNode = node.get("tests");
        if (testsNode != null && !testsNode.isNull()) {
            if (!testsNode.isArray()) {
                throw new AnyhowError("invalid type: " + describe(testsNode) + ", expected a sequence");
            }
            for (JsonNode test : testsNode) {
                tests.add(testFrom(test));
            }
        }
        List<Path> coverage = new ArrayList<>();
        JsonNode coverageNode = node.get("coverage");
        if (coverageNode != null && !coverageNode.isNull()) {
            for (JsonNode path : sequence(coverageNode)) {
                coverage.add(Path.of(stringValue(path)));
            }
        }
        List<String> diagnostics = new ArrayList<>();
        JsonNode diagnosticsNode = node.get("diagnostics");
        if (diagnosticsNode != null && !diagnosticsNode.isNull()) {
            for (JsonNode diagnostic : sequence(diagnosticsNode)) {
                diagnostics.add(stringValue(diagnostic));
            }
        }
        return new AdapterResponse(protocol, success, tests, coverage, diagnostics);
    }

    private static TestResult testFrom(JsonNode node) {
        if (!node.isObject()) {
            throw new AnyhowError("invalid type: " + describe(node) + ", expected struct TestResult");
        }
        JsonNode nameNode = node.get("name");
        if (nameNode == null) {
            throw new AnyhowError("missing field `name`");
        }
        JsonNode statusNode = node.get("status");
        if (statusNode == null) {
            throw new AnyhowError("missing field `status`");
        }
        long duration = optionalU64(node, "duration_ms", -1L, 0L);
        String message = optionalString(node, "message");
        String source = optionalString(node, "source");
        JsonNode lineNode = node.get("line");
        Long line = lineNode == null || lineNode.isNull() ? null : u64(lineNode, 0xFFFFFFFFL);
        List<Attachment> attachments = new ArrayList<>();
        JsonNode attachmentsNode = node.get("attachments");
        if (attachmentsNode != null && !attachmentsNode.isNull()) {
            for (JsonNode attachment : sequence(attachmentsNode)) {
                attachments.add(new Attachment(
                        stringValue(required(attachment, "name")),
                        Path.of(stringValue(required(attachment, "path"))),
                        stringValue(required(attachment, "media_type"))));
            }
        }
        return new TestResult(
                stringValue(nameNode),
                TestStatus.fromWire(stringValue(statusNode)),
                duration,
                message,
                source == null ? null : Path.of(source),
                line,
                attachments);
    }

    public static AdapterRequest parseRequest(byte[] content) {
        JsonNode node = Json.parseBytes(content);
        if (!node.isObject()) {
            throw new AnyhowError("invalid type: " + describe(node) + ", expected struct AdapterRequest");
        }
        SortedMap<String, String> env = new TreeMap<>(RustOrdering.STRING_ORDER);
        JsonNode envNode = node.get("env");
        if (envNode != null && !envNode.isNull()) {
            for (Map.Entry<String, JsonNode> entry : envNode.properties()) {
                env.put(entry.getKey(), stringValue(entry.getValue()));
            }
        }
        SortedMap<String, JsonNode> options = new TreeMap<>(RustOrdering.STRING_ORDER);
        JsonNode optionsNode = node.get("options");
        if (optionsNode != null && !optionsNode.isNull()) {
            for (Map.Entry<String, JsonNode> entry : optionsNode.properties()) {
                options.put(entry.getKey(), entry.getValue());
            }
        }
        List<String> args = new ArrayList<>();
        JsonNode argsNode = node.get("args");
        if (argsNode != null && !argsNode.isNull()) {
            for (JsonNode argument : sequence(argsNode)) {
                args.add(stringValue(argument));
            }
        }
        return new AdapterRequest(
                requiredU64(node, "protocol", 0xFFFFFFFFL),
                stringValue(required(node, "project")),
                stringValue(required(node, "package")),
                stringValue(required(node, "phase")),
                Path.of(stringValue(required(node, "root"))),
                Path.of(stringValue(required(node, "manifest"))),
                Path.of(stringValue(required(node, "cwd"))),
                Path.of(stringValue(required(node, "output"))),
                ExecutionMode.fromWire(stringValue(required(node, "execution"))),
                requiredBool(node, "coverage"),
                requiredBool(node, "strict"),
                args,
                env,
                options);
    }

    private static JsonNode required(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null) {
            throw new AnyhowError("missing field `" + field + "`");
        }
        return value;
    }

    private static long requiredU64(JsonNode node, String field, long maximum) {
        return u64(required(node, field), maximum);
    }

    private static long optionalU64(JsonNode node, String field, long maximum, long fallback) {
        JsonNode value = node.get(field);
        if (value == null) {
            return fallback;
        }
        return u64(value, maximum);
    }

    private static long u64(JsonNode value, long maximum) {
        if (!value.isIntegralNumber()) {
            throw new AnyhowError("invalid type: " + describe(value) + ", expected u" + (maximum == -1L ? "64" : "32"));
        }
        java.math.BigInteger integer = value.bigIntegerValue();
        java.math.BigInteger limit = maximum == -1L
                ? new java.math.BigInteger("18446744073709551615")
                : java.math.BigInteger.valueOf(maximum);
        if (integer.signum() < 0 || integer.compareTo(limit) > 0) {
            throw new AnyhowError(
                    "invalid value: integer `" + integer + "`, expected u" + (maximum == -1L ? "64" : "32"));
        }
        return integer.longValue();
    }

    private static boolean requiredBool(JsonNode node, String field) {
        JsonNode value = required(node, field);
        if (!value.isBoolean()) {
            throw new AnyhowError("invalid type: " + describe(value) + ", expected a boolean");
        }
        return value.booleanValue();
    }

    private static @Nullable String optionalString(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return stringValue(value);
    }

    private static String stringValue(JsonNode value) {
        if (!value.isString()) {
            throw new AnyhowError("invalid type: " + describe(value) + ", expected a string");
        }
        return value.stringValue();
    }

    private static List<JsonNode> sequence(JsonNode value) {
        if (!value.isArray()) {
            throw new AnyhowError("invalid type: " + describe(value) + ", expected a sequence");
        }
        List<JsonNode> items = new ArrayList<>();
        value.forEach(items::add);
        return items;
    }

    private static String describe(JsonNode value) {
        if (value.isNull()) {
            return "null";
        }
        if (value.isBoolean()) {
            return "boolean `" + value.booleanValue() + "`";
        }
        if (value.isIntegralNumber()) {
            return "integer `" + value.bigIntegerValue() + "`";
        }
        if (value.isNumber()) {
            return "floating point `" + SerdeJsonWriter.compact(value) + "`";
        }
        if (value.isString()) {
            return "string " + com.cherrytest.common.rustcompat.RustDebug.string(value.stringValue());
        }
        if (value.isArray()) {
            return "sequence";
        }
        return "map";
    }
}
