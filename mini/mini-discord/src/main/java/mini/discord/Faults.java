package mini.discord;

import static mini.discord.Json.*;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

final class Faults {
    private final Clock clock;
    private final List<ObjectNode> rules = new ArrayList<>();
    private final List<ObjectNode> limits = new ArrayList<>();
    private final Map<String, Window> windows = new HashMap<>();
    private long sequence;

    private static final class Window {
        long reset;
        int used;

        Window(long reset) {
            this.reset = reset;
        }
    }

    record Result(Reply fault, Map<String, String> headers) {}

    Faults(Clock clock) {
        this.clock = clock;
    }

    ObjectNode add(JsonNode input) {
        fields(
                input,
                "route",
                "failNext",
                "always",
                "after",
                "status",
                "fault",
                "retryAfterSeconds",
                "delayMs",
                "global");
        String route = required(input, "route");
        if (route.length() > 256) throw ApiError.invalid("route");
        int count = integer(input, "failNext", 1, 1, 1000000), after = integer(input, "after", 0, 0, 1000000);
        int status = integer(input, "status", 0, 400, 599);
        String fault = str(
                input,
                "fault",
                status == 429 ? "RATE_LIMITED" : status == 503 ? "HTTP_503" : status == 500 ? "HTTP_500" : "");
        if (!List.of(
                        "INVALID_TOKEN",
                        "EXPIRED_TOKEN",
                        "MISSING_SCOPE",
                        "MISSING_PERMISSIONS",
                        "UNKNOWN_GUILD",
                        "UNKNOWN_USER",
                        "UNKNOWN_MEMBER",
                        "UNKNOWN_ROLE",
                        "ROLE_HIERARCHY_VIOLATION",
                        "ALREADY_MEMBER",
                        "BANNED_USER",
                        "OAUTH_DENIED",
                        "OAUTH_CODE_EXPIRED",
                        "RATE_LIMITED",
                        "HTTP_500",
                        "HTTP_503",
                        "TIMEOUT",
                        "NETWORK_DISCONNECT",
                        "DELAY")
                .contains(fault)) throw ApiError.invalid("fault");
        ObjectNode rule = obj(
                "id",
                ++sequence,
                "route",
                route,
                "remaining",
                count,
                "always",
                bool(input, "always", false),
                "after",
                after,
                "seen",
                0,
                "fault",
                fault,
                "retry",
                integer(input, "retryAfterSeconds", 1, 1, 86400),
                "delay",
                integer(input, "delayMs", fault.equals("TIMEOUT") ? 1000 : 0, 0, 30000),
                "global",
                bool(input, "global", false));
        rules.add(rule);
        return rule.deepCopy();
    }

    ObjectNode limit(JsonNode input) {
        fields(input, "route", "bucket", "limit", "windowSeconds", "global", "shared");
        ObjectNode rule = obj(
                "route",
                required(input, "route"),
                "bucket",
                str(input, "bucket", "bucket-" + ++sequence),
                "limit",
                integer(input, "limit", 1, 1, 1000000),
                "window",
                integer(input, "windowSeconds", 1, 1, 86400),
                "global",
                bool(input, "global", false),
                "shared",
                bool(input, "shared", false));
        limits.add(rule);
        return rule.deepCopy();
    }

    static boolean matches(String pattern, String method, String path) {
        if (pattern.equals("*")) return true;
        String target = method + " " + path;
        if (target.startsWith(method + " /api/v10")) target = method + " " + path.substring(8);
        String regex = java.util.regex.Pattern.quote(pattern)
                .replaceAll("\\{[^}]+}", "\\\\E[^/]+\\\\Q")
                .replace("*", "\\E.*\\Q");
        return target.matches(regex);
    }

    Result check(String method, String path, String principal) {
        for (ObjectNode rule : rules) {
            if (!matches(rule.path("route").asString(), method, path)) continue;
            int seen = rule.path("seen").asInt();
            rule.put("seen", seen + 1);
            if (seen < rule.path("after").asInt()
                    || (!rule.path("always").asBoolean()
                            && rule.path("remaining").asInt() <= 0)) continue;
            rule.put("remaining", rule.path("remaining").asInt() - 1);
            String fault = rule.path("fault").asString();
            if (fault.equals("ALREADY_MEMBER"))
                return new Result(null, Map.of("X-Mini-Discord-Fault", "ALREADY_MEMBER"));
            if (fault.equals("DELAY"))
                return new Result(
                        null, Map.of("X-Mini-Discord-Delay", rule.path("delay").asString()));
            if (fault.equals("NETWORK_DISCONNECT") || fault.equals("TIMEOUT"))
                return new Result(
                        new Reply(503, obj(), Map.of(), rule.path("delay").asInt(), true), Map.of());
            if (fault.equals("RATE_LIMITED"))
                return new Result(
                        rate(
                                rule.path("retry").asInt(),
                                rule.path("global").asBoolean(),
                                "fault-" + rule.path("id").asString(),
                                "user"),
                        Map.of());
            ApiError error = switch (fault) {
                case "INVALID_TOKEN", "EXPIRED_TOKEN" -> ApiError.unauthorized();
                case "MISSING_SCOPE" -> new ApiError(403, 50026, "Missing required OAuth2 scope");
                case "MISSING_PERMISSIONS", "ROLE_HIERARCHY_VIOLATION" -> ApiError.permissions();
                case "UNKNOWN_GUILD" -> new ApiError(404, 10004, "Unknown Guild");
                case "UNKNOWN_USER" -> new ApiError(404, 10013, "Unknown User");
                case "UNKNOWN_MEMBER" -> new ApiError(404, 10007, "Unknown Member");
                case "UNKNOWN_ROLE" -> new ApiError(404, 10011, "Unknown Role");
                case "BANNED_USER" -> new ApiError(403, 40007, "The user is banned from this guild");
                case "OAUTH_DENIED" -> new ApiError("access_denied");
                case "OAUTH_CODE_EXPIRED" -> new ApiError("invalid_grant");
                case "HTTP_500" -> new ApiError(500, 0, "Internal Server Error");
                case "HTTP_503" -> new ApiError(503, 0, "Service Unavailable");
                default -> throw new IllegalStateException("Unrecognized configured fault");
            };
            return new Result(
                    new Reply(
                            error.status,
                            error.body(),
                            Map.of(),
                            rule.path("delay").asInt(),
                            false),
                    Map.of());
        }
        Map<String, String> headers = new LinkedHashMap<>();
        List<Window> accepted = new ArrayList<>();
        long now = clock.instant().getEpochSecond();
        for (ObjectNode limit : limits) {
            if (!limit.path("global").asBoolean()
                    && !matches(limit.path("route").asString(), method, path)) continue;
            String bucket = limit.path("bucket").asString();
            String key = bucket + ":" + (limit.path("shared").asBoolean() ? "shared" : principal) + ":"
                    + (limit.path("global").asBoolean() ? "global" : major(path));
            Window window = windows.computeIfAbsent(
                    key, k -> new Window(now + limit.path("window").asInt()));
            if (now >= window.reset) {
                window.reset = now + limit.path("window").asInt();
                window.used = 0;
            }
            int max = limit.path("limit").asInt();
            headers.putAll(Map.of(
                    "X-RateLimit-Limit",
                    Integer.toString(max),
                    "X-RateLimit-Remaining",
                    Integer.toString(Math.max(0, max - window.used - 1)),
                    "X-RateLimit-Reset",
                    Long.toString(window.reset),
                    "X-RateLimit-Reset-After",
                    Long.toString(window.reset - now),
                    "X-RateLimit-Bucket",
                    bucket));
            if (window.used >= max) {
                Reply limited = rate(
                        (int) (window.reset - now),
                        limit.path("global").asBoolean(),
                        bucket,
                        limit.path("shared").asBoolean() ? "shared" : "user");
                headers.putAll(limited.headers());
                headers.put("X-RateLimit-Remaining", "0");
                return new Result(new Reply(limited.status(), limited.body(), headers, 0, false), Map.of());
            }
            accepted.add(window);
        }
        accepted.forEach(w -> w.used++);
        return new Result(null, headers);
    }

    private static String major(String path) {
        String[] parts = path.replaceFirst("^/api/v10", "").split("/", -1);
        return parts.length > 2 && List.of("guilds", "channels").contains(parts[1])
                ? parts[1] + ":" + parts[2]
                : "none";
    }

    private Reply rate(int retry, boolean global, String bucket, String scope) {
        Map<String, String> headers = new LinkedHashMap<>(Map.of(
                "Retry-After",
                Integer.toString(retry),
                "X-RateLimit-Bucket",
                bucket,
                "X-RateLimit-Remaining",
                "0",
                "X-RateLimit-Reset-After",
                Integer.toString(retry),
                "X-RateLimit-Reset",
                Long.toString(clock.instant().getEpochSecond() + retry),
                "X-RateLimit-Scope",
                global ? "global" : scope));
        if (global) headers.put("X-RateLimit-Global", "true");
        return new Reply(
                429,
                obj("message", "You are being rate limited.", "retry_after", retry, "global", global),
                headers,
                0,
                false);
    }

    void clear() {
        rules.clear();
        limits.clear();
        windows.clear();
    }
}
