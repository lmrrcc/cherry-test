package mini.discord;

import static mini.discord.Json.*;
import static org.assertj.core.api.Assertions.*;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import tools.jackson.databind.JsonNode;

class SecurityReliabilityTest {
    @RegisterExtension
    final HttpFixture f = new HttpFixture();

    @Test
    void process_policy_blocks_outbound_before_connect() throws Exception {
        assertThat(Boolean.getBoolean("mini.discord.isolated"))
                .as("Run scripts/offline-tests.sh or the network-isolated Docker test image")
                .isTrue();
        try (Socket socket = new Socket()) {
            assertThatThrownBy(() -> socket.connect(new InetSocketAddress("192.0.2.1", 443), 200))
                    .isInstanceOf(java.net.SocketException.class)
                    .hasMessageMatching(
                            "(?i).*(Operation not permitted|Permission denied|Network (?:is )?unreachable|No route to host).*");
            assertThat(socket.isConnected()).isFalse();
        }
    }

    @Test
    void control_credentials_and_production_modes_are_rejected() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(f.server.baseUri().resolve("/_test/state"))
                .GET()
                .build();
        assertThat(f.client
                        .send(request, HttpResponse.BodyHandlers.discarding())
                        .statusCode())
                .isEqualTo(403);
        request = HttpRequest.newBuilder(f.server.baseUri().resolve("/_test/state"))
                .header("X-Mini-Discord-Secret", f.controlSecret)
                .header("Origin", "http://example.test")
                .GET()
                .build();
        assertThat(f.client
                        .send(request, HttpResponse.BodyHandlers.discarding())
                        .statusCode())
                .isEqualTo(403);
        assertThatThrownBy(() -> new MiniDiscordServer.Settings(
                        "127.0.0.1", 0, 0, MiniDiscordServer.Mode.PRODUCTION, false, false, ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MiniDiscordServer.Settings(
                        "127.0.0.1", 0, 0, MiniDiscordServer.Mode.REAL_DISCORD_TEST, false, false, ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MiniDiscordServer.Settings(
                        "0.0.0.0", 0, 0, MiniDiscordServer.Mode.LOCAL_MINI_DISCORD, false, true, f.controlSecret))
                .isInstanceOf(IllegalArgumentException.class);
        try (MiniDiscordServer disabled = new MiniDiscordServer(
                new MiniDiscordServer.Settings(
                        "127.0.0.1", 0, 0, MiniDiscordServer.Mode.LOCAL_MINI_DISCORD, false, false, ""),
                TestClock.deterministic())) {
            assertThat(f.client
                            .send(
                                    HttpRequest.newBuilder(disabled.baseUri().resolve("/_test/state"))
                                            .build(),
                                    HttpResponse.BodyHandlers.discarding())
                            .statusCode())
                    .isEqualTo(404);
        }
    }

    @Test
    void secrets_are_redacted_and_snapshot_logs_are_independent() throws Exception {
        String uid = f.newUser();
        JsonNode tokens = f.token(uid, "identify guilds.join email");
        f.api("PUT", "/guilds/" + f.guild + "/members/" + uid, obj("access_token", tokens.path("access_token")), 201);
        f.grant(uid, f.role("MEMBER"));
        f.api("GET", "/channels/" + f.channel("java-jvm"), null, 200);
        JsonNode records = f.control("GET", "requests", null, 200).body();
        assertThat(records.get(records.size() - 1).path("guildId").asString()).isEqualTo(f.guild);
        records.forEach(
                record -> assertThat(record.path("correlationId").asString()).isNotEmpty());
        String evidence = f.control("GET", "requests", null, 200).body().toString();
        assertThat(evidence)
                .doesNotContain(
                        f.bot,
                        f.secret,
                        f.controlSecret,
                        tokens.path("access_token").asString(),
                        tokens.path("refresh_token").asString(),
                        "synthetic@example.test");
        assertThat(evidence).contains("[REDACTED]", "actorApplicationId", "discordErrorCode", "durationMs");
        long events = f.eventCount("GUILD_MEMBER_ADD");
        f.control("DELETE", "requests", null, 204);
        assertThat(f.eventCount("GUILD_MEMBER_ADD")).isEqualTo(events);
        assertThat(f.api("GET", "/guilds/" + f.guild + "/audit-logs", null, 200)
                        .body()
                        .path("audit_log_entries")
                        .size())
                .isPositive();
    }

    @Test
    void reset_and_invalid_seed_preserve_isolation() throws Exception {
        String token = f.bot, guild = f.guild;
        f.control("POST", "seed", obj("fixture", "standard", "members", 10001), 400);
        f.api("GET", "/guilds/" + guild, null, 200);
        f.seed("standard");
        assertThat(f.guild).isEqualTo(guild);
        assertThat(f.bot).isNotEqualTo(token);
        f.request("GET", "/api/v10/guilds/" + guild, null, "Bot " + token, false, 401);
        try (MiniDiscordServer other = MiniDiscordServer.local()) {
            JsonNode seeded = other.seed(Fixtures.load("empty"));
            assertThat(seeded.path("guild_id").asString()).isEqualTo(guild);
            assertThat(other.baseUri()).isNotEqualTo(f.server.baseUri());
            f.api("GET", "/guilds/" + f.guild + "/members/" + f.user("student-1"), null, 200);
        }
    }

    @Test
    void faults_after_count_always_global_and_disconnect() throws Exception {
        f.control(
                "POST",
                "faults",
                obj("route", "GET /guilds/{guildId}", "fault", "HTTP_500", "after", 1, "always", true),
                201);
        f.api("GET", "/guilds/" + f.guild, null, 200);
        f.api("GET", "/guilds/" + f.guild, null, 500);
        f.api("GET", "/guilds/" + f.guild, null, 500);
        f.control("DELETE", "faults", null, 204);
        f.control("POST", "rate-limits", obj("route", "*", "global", true, "limit", 1, "windowSeconds", 5), 201);
        f.api("GET", "/guilds/" + f.guild, null, 200);
        HttpFixture.Response rate = f.api("GET", "/guilds/" + f.guild + "/roles", null, 429);
        assertThat(rate.body().path("global").asBoolean()).isTrue();
        assertThat(rate.raw().headers().firstValue("X-RateLimit-Global")).contains("true");
        f.control("DELETE", "faults", null, 204);
        f.control(
                "POST",
                "faults",
                obj("route", "POST /guilds/{guildId}/roles", "fault", "NETWORK_DISCONNECT", "always", true),
                201);
        assertThatThrownBy(() -> f.api("POST", "/guilds/" + f.guild + "/roles", obj("name", "must-not-exist"), 200))
                .isInstanceOf(java.io.IOException.class);
        f.control("DELETE", "faults", null, 204);
        assertThat(f.api("GET", "/guilds/" + f.guild + "/roles", null, 200)
                        .body()
                        .toString())
                .doesNotContain("must-not-exist");
    }

    @Test
    void timeout_does_not_mutate_or_block_health() throws Exception {
        f.control(
                "POST",
                "faults",
                obj("route", "POST /guilds/{guildId}/roles", "fault", "TIMEOUT", "delayMs", 500),
                201);
        HttpRequest request = HttpRequest.newBuilder(
                        f.server.baseUri().resolve("/api/v10/guilds/" + f.guild + "/roles"))
                .timeout(Duration.ofMillis(30))
                .header("Authorization", "Bot " + f.bot)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"timed-out\"}"))
                .build();
        assertThatThrownBy(() -> f.client.send(request, HttpResponse.BodyHandlers.ofString()))
                .isInstanceOf(java.net.http.HttpTimeoutException.class);
        assertThat(f.client
                        .send(
                                HttpRequest.newBuilder(f.server.baseUri().resolve("/health"))
                                        .timeout(Duration.ofSeconds(2))
                                        .build(),
                                HttpResponse.BodyHandlers.discarding())
                        .statusCode())
                .isEqualTo(200);
        assertThat(f.api("GET", "/guilds/" + f.guild + "/roles", null, 200)
                        .body()
                        .toString())
                .doesNotContain("timed-out");
    }

    @Test
    void fixture_files_load_and_optional_ten_thousand_members() throws Exception {
        for (String name : List.of(
                "empty", "jug-lugano-discord", "unauthorized-joining", "expired-membership", "multiple-guilds")) {
            JsonNode fixture = f.server.seed(Fixtures.load(name));
            assertThat(fixture.path("guild_id").asString()).isNotEmpty();
            if (name.equals("unauthorized-joining")) {
                JsonNode invite = f.request(
                                "GET",
                                "/api/v10/invites/"
                                        + fixture.path("invite_code").asString(),
                                null,
                                "",
                                false,
                                200)
                        .body();
                assertThat(invite.path("uses").asInt()).isEqualTo(1);
                f.readable(
                        fixture.path("users").path("unapproved").asString(),
                        fixture.path("channels").path("java-jvm").asString(),
                        false);
            }
        }
        if (Boolean.getBoolean("mini.discord.performance")) {
            long start = System.nanoTime();
            f.server.seed(Fixtures.load("performance"));
            synchronized (f.server.lock) {
                assertThat(f.server.state.guilds.values().iterator().next().members)
                        .hasSize(10002);
            }
            java.nio.file.Files.writeString(
                    java.nio.file.Path.of("target/performance-10000.json"),
                    obj("members", 10000, "loadMs", (System.nanoTime() - start) / 1000000.0)
                            .toString());
        }
    }
}
