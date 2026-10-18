package mini.discord;

import static mini.discord.Json.*;
import static org.assertj.core.api.Assertions.*;

import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

final class HttpFixture implements BeforeEachCallback, AfterEachCallback, InvocationInterceptor {
    MiniDiscordServer server;
    HttpClient client;
    JsonNode fixture;
    String guild;
    String bot;
    String appId;
    String secret;
    String base;
    String controlSecret;
    final boolean external;

    HttpFixture() {
        this(false);
    }

    HttpFixture(boolean external) {
        this.external = external;
    }

    record Response(int status, JsonNode body, HttpResponse<String> raw) {}

    @Override
    public void interceptTestMethod(
            Invocation<Void> invocation, ReflectiveInvocationContext<Method> method, ExtensionContext context)
            throws Throwable {
        try {
            invocation.proceed();
        } catch (Throwable failure) {
            String message = Evidence.scrub(failure.getClass().getSimpleName() + ": " + failure.getMessage());
            if (controlSecret != null && !controlSecret.isEmpty())
                message = message.replace(controlSecret, "[REDACTED]");
            if (server != null) {
                java.util.List<ObjectNode> requests = server.requests();
                for (ObjectNode request : requests.subList(Math.max(0, requests.size() - 3), requests.size())) {
                    String detail = request.toString();
                    message += "\nHTTP evidence: " + detail.substring(0, Math.min(detail.length(), 8000));
                }
            }
            AssertionError sanitized = new AssertionError(message);
            sanitized.setStackTrace(failure.getStackTrace());
            throw sanitized;
        }
    }

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        if (external) {
            base = System.getenv()
                    .getOrDefault(
                            "MINI_DISCORD_CONTAINER_URL",
                            System.getenv().getOrDefault("CHERRY_TEST_MINI_DISCORD_DISCORD_URL", ""));
            controlSecret = System.getenv().getOrDefault("MINI_DISCORD_TEST_SECRET", "");
            if (base.isBlank() || controlSecret.length() < 32)
                throw new IllegalStateException("External Mini-Discord URL and test control secret required");
        } else {
            server = MiniDiscordServer.local();
            base = server.baseUri().toString();
            controlSecret = server.testSecret();
        }
        client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        seed("standard");
    }

    void seed(String name) throws Exception {
        fixture = control("POST", "seed", obj("fixture", name), 200).body;
        guild = fixture.path("guild_id").asString();
        bot = fixture.path("application").path("bot_token").asString();
        appId = fixture.path("application").path("client_id").asString();
        secret = fixture.path("application").path("client_secret").asString();
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        if (client == null) return;
        try {
            Throwable failure = context.getExecutionException().orElse(null);
            String name = context.getRequiredTestMethod().getName();
            if (!name.contains("_project_")) name = "mini_discord_project_contract_suite_" + name + "_test";
            Path directory = Path.of(System.getProperty(
                    "mini.discord.reportDir",
                    System.getenv().getOrDefault("CHERRY_TEST_REPORT_DIR", "target/cherry-test/reports")));
            if (!context.getRequiredTestMethod().getName().contains("_project_"))
                directory = directory.resolve("unit-http");
            String reason = failure == null ? "" : String.valueOf(failure.getMessage());
            if (server != null) server.writeReport(directory, name, failure == null, reason);
            else {
                Evidence evidence = new Evidence();
                control("GET", "requests", null, 200).body.forEach(r -> evidence.add((ObjectNode) r));
                java.util.List<ObjectNode> events = new java.util.ArrayList<>();
                control("GET", "events", null, 200).body.forEach(e -> events.add((ObjectNode) e));
                evidence.report(directory, name, failure == null, reason, base, events);
            }
        } finally {
            if (client != null) client.close();
            if (server != null) server.close();
        }
    }

    Response control(String method, String path, JsonNode body, int expected) throws Exception {
        return request(method, "/_test/" + path, body, "", false, expected);
    }

    Response api(String method, String path, JsonNode body, int expected) throws Exception {
        return request(method, "/api/v10" + path, body, "Bot " + bot, false, expected);
    }

    Response request(String method, String path, JsonNode body, String authorization, boolean form, int expected)
            throws Exception {
        if (!path.startsWith("/")
                || path.startsWith("//")
                || !URI.create(base).getHost().matches("127\\.0\\.0\\.1|localhost|mini-discord"))
            throw new IllegalArgumentException("Local simulator endpoint required");
        String content = body == null ? "" : form ? formBody(body) : body.toString();
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", form ? "application/x-www-form-urlencoded" : "application/json");
        if (!authorization.isEmpty()) request.header("Authorization", authorization);
        if (path.startsWith("/_test/")) request.header("X-Mini-Discord-Secret", controlSecret);
        HttpResponse<String> response = client.send(
                request.method(
                                method,
                                content.isEmpty()
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(content))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        JsonNode json = response.body().isBlank() ? obj() : MAPPER.readTree(response.body());
        assertThat(response.statusCode())
                .as("%s %s: %s", method, path, Evidence.sanitize(json))
                .isEqualTo(expected);
        return new Response(response.statusCode(), json, response);
    }

    static String formBody(JsonNode node) {
        return node.properties().stream()
                .map(e -> OAuth.encode(e.getKey()) + "="
                        + OAuth.encode(e.getValue().asString()))
                .collect(java.util.stream.Collectors.joining("&"));
    }

    String role(String name) {
        return fixture.path("roles").path(name).asString();
    }

    String channel(String name) {
        return fixture.path("channels").path(name).asString();
    }

    String user(String name) {
        return fixture.path("users").path(name).asString();
    }

    String newUser() throws Exception {
        return control(
                        "POST",
                        "users",
                        obj("username", "new-synthetic-user", "email", "synthetic@example.test", "verified", true),
                        201)
                .body
                .path("id")
                .asString();
    }

    String authRequest(String scopes, String state) throws Exception {
        ObjectNode query = obj(
                "client_id",
                appId,
                "redirect_uri",
                "http://127.0.0.1/callback",
                "response_type",
                "code",
                "scope",
                scopes,
                "state",
                state);
        Response response = request("GET", "/oauth2/authorize?" + formBody(query), null, "", false, 302);
        return response.raw.headers().firstValue("Location").orElseThrow().substring("/_test/consent/".length());
    }

    String consent(String uid, String scopes) throws Exception {
        String request = authRequest(scopes, "test-csrf-state");
        JsonNode response = control("POST", "oauth/authorize", obj("request_id", request, "user_id", uid), 200).body;
        JsonNode callback =
                Json.form(URI.create(response.path("redirect_uri").asString()).getRawQuery());
        assertThat(callback.path("state").asString()).isEqualTo("test-csrf-state");
        return callback.path("code").asString();
    }

    ObjectNode exchangeBody(String code) {
        return obj(
                "client_id",
                appId,
                "client_secret",
                secret,
                "grant_type",
                "authorization_code",
                "code",
                code,
                "redirect_uri",
                "http://127.0.0.1/callback");
    }

    JsonNode token(String uid, String scopes) throws Exception {
        return request("POST", "/api/v10/oauth2/token", exchangeBody(consent(uid, scopes)), "", true, 200).body;
    }

    String join(String uid) throws Exception {
        String access = token(uid, "identify guilds.join").path("access_token").asString();
        api("PUT", "/guilds/" + guild + "/members/" + uid, obj("access_token", access), 201);
        return access;
    }

    void grant(String uid, String role) throws Exception {
        api("PUT", "/guilds/" + guild + "/members/" + uid + "/roles/" + role, null, 204);
    }

    void revoke(String uid, String role) throws Exception {
        api("DELETE", "/guilds/" + guild + "/members/" + uid + "/roles/" + role, null, 204);
    }

    void readable(String uid, String channel, boolean expected) throws Exception {
        control("POST", "read-channel", obj("user_id", uid, "channel_id", channel), expected ? 200 : 403);
    }

    JsonNode events() throws Exception {
        return control("GET", "events", null, 200).body;
    }

    long eventCount(String type) throws Exception {
        return java.util.stream.StreamSupport.stream(events().spliterator(), false)
                .filter(e -> e.path("t").asString().equals(type))
                .count();
    }
}
