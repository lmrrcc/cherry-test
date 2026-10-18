package mini.discord;

import static mini.discord.Json.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

final class Evidence {
    private final List<ObjectNode> requests = new ArrayList<>();
    private boolean overflow;

    synchronized void add(ObjectNode request) {
        if (requests.size() >= 100000) {
            overflow = true;
            return;
        }
        requests.add((ObjectNode) sanitize(request));
    }

    synchronized void clear() {
        requests.clear();
        overflow = false;
    }

    synchronized List<ObjectNode> requests() {
        if (overflow) throw new IllegalStateException("Request evidence capacity exceeded");
        return requests.stream().map(ObjectNode::deepCopy).toList();
    }

    synchronized void report(
            Path directory, String fullName, boolean passed, String reason, String base, List<ObjectNode> events)
            throws IOException {
        if (!fullName.matches("[a-z0-9_]+_project_[a-z0-9_]+_suite_[a-z0-9_]+_test"))
            throw new IllegalArgumentException("Invalid Cherry Test identity");
        String[] project = fullName.split("_project_", 2), suite = project[1].split("_suite_", 2);
        String test = suite[1].substring(0, suite[1].length() - 5);
        Path output = directory.resolve("integration");
        Files.createDirectories(output);
        StringBuilder jsonl = new StringBuilder();
        for (ObjectNode request : requests()) {
            jsonl.append(obj(
                            "project",
                            project[0],
                            "suite",
                            suite[0],
                            "test",
                            test,
                            "full_name",
                            fullName,
                            "scope",
                            "mini_discord",
                            "target_project",
                            project[0],
                            "target_service",
                            "discord",
                            "elapsed_ms",
                            request.path("durationMs"),
                            "method",
                            request.path("method"),
                            "url",
                            base + request.path("path").asString(),
                            "status",
                            request.path("httpStatus"),
                            "request_body",
                            obj(
                                            "query",
                                            request.path("query"),
                                            "body",
                                            request.path("body"),
                                            "correlationId",
                                            request.path("correlationId"))
                                    .toString(),
                            "authorization",
                            null,
                            "response_body",
                            request.path("response").toString(),
                            "passed",
                            passed,
                            "reason",
                            reason.isEmpty() ? null : scrub(reason)))
                    .append('\n');
        }
        Files.writeString(output.resolve("mini-discord-" + fullName + ".jsonl"), jsonl);
        Path details = directory.resolve("mini-discord");
        Files.createDirectories(details);
        Files.writeString(
                details.resolve(fullName + ".json"),
                obj("passed", passed, "reason", scrub(reason), "requests", requests(), "events", sanitize(arr(events)))
                        .toString());
    }

    static JsonNode sanitize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode out = obj();
            node.properties().forEach(e -> {
                String key = e.getKey().toLowerCase(Locale.ROOT);
                out.set(
                        e.getKey(),
                        key.contains("token")
                                        || key.contains("secret")
                                        || key.equals("authorization")
                                        || key.equals("email")
                                        || key.equals("username")
                                        || key.equals("global_name")
                                        || key.equals("nick")
                                        || key.equals("content")
                                        || key.equals("embeds")
                                        || key.equals("state")
                                        || (key.equals("code") && e.getValue().isString())
                                ? MAPPER.valueToTree("[REDACTED]")
                                : sanitize(e.getValue()));
            });
            return out;
        }
        if (node.isArray()) {
            List<JsonNode> out = new ArrayList<>();
            node.forEach(value -> out.add(sanitize(value)));
            return arr(out);
        }
        return node.isString() ? MAPPER.valueToTree(scrub(node.asString())) : node.deepCopy();
    }

    static String scrub(String value) {
        return value.replaceAll("mini_discord_(?:bot|secret|access|refresh|code|request)_[A-Za-z0-9-]+", "[REDACTED]")
                .replaceAll("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+", "[REDACTED]");
    }
}
