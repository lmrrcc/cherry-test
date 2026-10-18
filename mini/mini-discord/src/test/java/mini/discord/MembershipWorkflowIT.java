package mini.discord;

import static mini.discord.Json.*;
import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import tools.jackson.databind.JsonNode;

class MembershipWorkflowIT {
    @RegisterExtension
    final HttpFixture f = new HttpFixture();

    @Test
    void mini_discord_project_oauth_suite_authorization_code_test() throws Exception {
        String uid = f.newUser(), code = f.consent(uid, "identify guilds.join");
        JsonNode token = f.request("POST", "/api/v10/oauth2/token", f.exchangeBody(code), "", true, 200)
                .body();
        JsonNode me = f.request(
                        "GET",
                        "/api/v10/users/@me",
                        null,
                        "Bearer " + token.path("access_token").asString(),
                        false,
                        200)
                .body();
        assertThat(me.path("id").asString()).isEqualTo(uid);
        assertThat(me.has("email")).isFalse();
        f.request("POST", "/api/v10/oauth2/token", f.exchangeBody(code), "", true, 400);
    }

    @Test
    void mini_discord_project_membership_suite_approved_join_test() throws Exception {
        String uid = f.newUser();
        f.join(uid);
        f.grant(uid, f.role("MEMBER"));
        f.readable(uid, f.channel("java-jvm"), true);
        f.readable(uid, f.channel("staff"), false);
        assertThat(f.eventCount("GUILD_MEMBER_ADD")).isEqualTo(1);
        assertThat(f.eventCount("GUILD_MEMBER_UPDATE")).isEqualTo(1);
    }

    @Test
    void mini_discord_project_oauth_suite_missing_scope_test() throws Exception {
        String uid = f.newUser(),
                token = f.token(uid, "identify").path("access_token").asString();
        JsonNode error = f.api("PUT", "/guilds/" + f.guild + "/members/" + uid, obj("access_token", token), 403)
                .body();
        assertThat(error.path("code").asInt()).isEqualTo(50026);
        f.api("GET", "/guilds/" + f.guild + "/members/" + uid, null, 404);
    }

    @Test
    void mini_discord_project_membership_suite_bot_permissions_test() throws Exception {
        JsonNode app = f.control(
                        "POST",
                        "bots",
                        obj("name", "Restricted Bot", "redirect_uris", List.of("http://127.0.0.1/callback")),
                        201)
                .body();
        f.control(
                "POST",
                "installations",
                obj(
                        "application_id",
                        app.path("id"),
                        "guild_id",
                        f.guild,
                        "requested_permissions",
                        "0",
                        "authorized_permissions",
                        "0",
                        "position",
                        1),
                201);
        String uid = f.user("student-1");
        f.request(
                "DELETE",
                "/api/v10/guilds/" + f.guild + "/members/" + uid,
                null,
                "Bot " + app.path("bot_token").asString(),
                false,
                403);
        f.api("GET", "/guilds/" + f.guild + "/members/" + uid, null, 200);
    }

    @Test
    void mini_discord_project_roles_suite_hierarchy_test() throws Exception {
        JsonNode app = f.control(
                        "POST",
                        "bots",
                        obj("name", "Low Bot", "redirect_uris", List.of("http://127.0.0.1/callback")),
                        201)
                .body();
        String permissions = PermissionEngine.ROLES.toString();
        f.control(
                "POST",
                "installations",
                obj(
                        "application_id",
                        app.path("id"),
                        "guild_id",
                        f.guild,
                        "requested_permissions",
                        permissions,
                        "authorized_permissions",
                        permissions,
                        "position",
                        1),
                201);
        f.request(
                "PATCH",
                "/api/v10/guilds/" + f.guild + "/roles/" + f.role("ADMIN"),
                obj("name", "hijacked"),
                "Bot " + app.path("bot_token").asString(),
                false,
                403);
        JsonNode roles =
                f.api("GET", "/guilds/" + f.guild + "/roles", null, 200).body();
        assertThat(roles.toString()).contains("ADMIN").doesNotContain("hijacked");
        f.api("PATCH", "/guilds/" + f.guild + "/roles/" + f.role("BOT"), obj("name", "modified"), 403);
    }

    @Test
    void mini_discord_project_membership_suite_duplicate_join_test() throws Exception {
        String uid = f.newUser(), token = f.join(uid);
        f.api("PUT", "/guilds/" + f.guild + "/members/" + uid, obj("access_token", token), 204);
        assertThat(f.eventCount("GUILD_MEMBER_ADD")).isEqualTo(1);
    }

    @Test
    void mini_discord_project_membership_suite_screening_test() throws Exception {
        f.fixture = f.control("POST", "seed", obj("fixture", "standard", "screening", true), 200)
                .body();
        f.guild = f.fixture.path("guild_id").asString();
        f.bot = f.fixture.path("application").path("bot_token").asString();
        f.appId = f.fixture.path("application").path("client_id").asString();
        f.secret = f.fixture.path("application").path("client_secret").asString();
        String uid = f.newUser();
        f.join(uid);
        assertThat(f.api("GET", "/guilds/" + f.guild + "/members/" + uid, null, 200)
                        .body()
                        .path("pending")
                        .asBoolean())
                .isTrue();
        f.control("POST", "members/" + uid + "/complete-screening", obj("guild_id", f.guild), 200);
        assertThat(f.api("GET", "/guilds/" + f.guild + "/members/" + uid, null, 200)
                        .body()
                        .path("pending")
                        .asBoolean())
                .isFalse();
        f.readable(uid, f.channel("java-jvm"), false);
        assertThat(f.eventCount("GUILD_MEMBER_UPDATE")).isEqualTo(1);
    }

    @Test
    void mini_discord_project_permissions_suite_unauthorized_invite_test() throws Exception {
        String uid = f.newUser();
        JsonNode invite = f.api("POST", "/channels/" + f.channel("benvenuto") + "/invites", obj("max_uses", 1), 200)
                .body();
        f.control("POST", "invites/" + invite.path("code").asString() + "/accept", obj("user_id", uid), 200);
        f.readable(uid, f.channel("benvenuto"), true);
        f.readable(uid, f.channel("java-jvm"), false);
        f.readable(uid, f.channel("staff"), false);
        assertThat(f.api("GET", "/guilds/" + f.guild + "/members/" + uid, null, 200)
                        .body()
                        .path("roles")
                        .isEmpty())
                .isTrue();
    }

    @Test
    void mini_discord_project_membership_suite_expiration_restore_test() throws Exception {
        String uid = f.user("professional-1");
        f.readable(uid, f.channel("java-jvm"), true);
        f.revoke(uid, f.role("MEMBER"));
        f.api("GET", "/guilds/" + f.guild + "/members/" + uid, null, 200);
        f.readable(uid, f.channel("java-jvm"), false);
        f.grant(uid, f.role("MEMBER"));
        f.readable(uid, f.channel("java-jvm"), true);
    }

    @Test
    void mini_discord_project_membership_suite_kick_rejoin_test() throws Exception {
        String uid = f.user("student-1");
        f.api("DELETE", "/guilds/" + f.guild + "/members/" + uid, null, 204);
        f.api("GET", "/guilds/" + f.guild + "/members/" + uid, null, 404);
        f.join(uid);
        assertThat(f.api("GET", "/guilds/" + f.guild + "/members/" + uid, null, 200)
                        .body()
                        .path("roles")
                        .isEmpty())
                .isTrue();
        f.readable(uid, f.channel("java-jvm"), false);
    }

    @Test
    void mini_discord_project_moderation_suite_ban_unban_test() throws Exception {
        String uid = f.user("student-1");
        f.api("PUT", "/guilds/" + f.guild + "/bans/" + uid, obj(), 204);
        f.api("GET", "/guilds/" + f.guild + "/bans/" + uid, null, 200);
        String token = f.token(uid, "identify guilds.join").path("access_token").asString();
        f.api("PUT", "/guilds/" + f.guild + "/members/" + uid, obj("access_token", token), 403);
        f.api("DELETE", "/guilds/" + f.guild + "/bans/" + uid, null, 204);
        f.api("PUT", "/guilds/" + f.guild + "/members/" + uid, obj("access_token", token), 201);
        assertThat(f.api("GET", "/guilds/" + f.guild + "/members/" + uid, null, 200)
                        .body()
                        .path("roles")
                        .isEmpty())
                .isTrue();
    }

    @Test
    void mini_discord_project_membership_suite_reconciliation_state_test() throws Exception {
        JsonNode members = f.api("GET", "/guilds/" + f.guild + "/members?limit=1000", null, 200)
                .body();
        Set<String> ids = new HashSet<>();
        members.forEach(m -> ids.add(m.path("user").path("id").asString()));
        assertThat(ids)
                .contains(f.user("unapproved"), f.user("pending"), f.user("staff"))
                .doesNotContain(f.user("kicked"), f.user("banned"));
        assertThat(f.api("GET", "/guilds/" + f.guild + "/members/" + f.user("pending"), null, 200)
                        .body()
                        .path("pending")
                        .asBoolean())
                .isTrue();
        assertThat(f.api("GET", "/guilds/" + f.guild + "/members/" + f.user("expired"), null, 200)
                        .body()
                        .path("roles")
                        .isEmpty())
                .isTrue();
        f.api("GET", "/guilds/" + f.guild + "/bans/" + f.user("banned"), null, 200);
        f.grant(f.user("unapproved"), f.role("ADMIN"));
        assertThat(strings(f.api("GET", "/guilds/" + f.guild + "/members/" + f.user("unapproved"), null, 200)
                        .body()
                        .path("roles")))
                .contains(f.role("ADMIN"));
    }

    @Test
    void mini_discord_project_membership_suite_bulk_thousand_test() throws Exception {
        long start = System.nanoTime();
        f.seed("large");
        long seeded = System.nanoTime();
        List<String> ids = new ArrayList<>();
        String after = "0";
        while (true) {
            JsonNode page = f.api("GET", "/guilds/" + f.guild + "/members?limit=137&after=" + after, null, 200)
                    .body();
            if (page.isEmpty()) break;
            for (JsonNode member : page) {
                String id = member.path("user").path("id").asString();
                if (!id.equals(f.user("owner")) && !id.equals(f.user("bot"))) ids.add(id);
                after = id;
            }
        }
        assertThat(ids).hasSize(1000).doesNotHaveDuplicates();
        long enumerated = System.nanoTime();
        f.control(
                "POST",
                "faults",
                obj(
                        "route",
                        "PUT /guilds/{guildId}/members/{userId}/roles/{roleId}",
                        "fault",
                        "HTTP_503",
                        "failNext",
                        3),
                201);
        for (int i = 0; i < 3; i++)
            f.api("PUT", "/guilds/" + f.guild + "/members/" + ids.get(i) + "/roles/" + f.role("STUDENT"), null, 503);
        long mutateStart = System.nanoTime();
        try (var workers = Executors.newFixedThreadPool(12)) {
            List<Callable<Void>> operations = new ArrayList<>();
            for (String uid : ids)
                operations.add(() -> {
                    f.grant(uid, f.role("STUDENT"));
                    f.revoke(uid, f.role("MEMBER"));
                    f.api("DELETE", "/guilds/" + f.guild + "/members/" + uid, null, 204);
                    return null;
                });
            for (var result : workers.invokeAll(operations)) result.get();
        }
        long done = System.nanoTime();
        assertThat(f.api("GET", "/guilds/" + f.guild + "/members?limit=1000", null, 200)
                        .body()
                        .size())
                .isEqualTo(2);
        assertThat(f.eventCount("GUILD_MEMBER_REMOVE")).isEqualTo(1000);
        assertThat(f.eventCount("GUILD_MEMBER_UPDATE")).isEqualTo(2000);
        for (int i = 0; i < 10; i++) {
            String uid = ids.get(i);
            String access = f.join(uid);
            f.api("PUT", "/guilds/" + f.guild + "/members/" + uid, obj("access_token", access), 204);
        }
        Path metrics = Path.of(System.getProperty("testit.buildDirectory", "target"), "performance.json");
        Files.createDirectories(metrics.getParent());
        Files.writeString(
                metrics,
                obj(
                                "users",
                                1000,
                                "fixtureLoadMs",
                                (seeded - start) / 1000000.0,
                                "enumerationMs",
                                (enumerated - seeded) / 1000000.0,
                                "bulkOperations",
                                3000,
                                "bulkDurationMs",
                                (done - mutateStart) / 1000000.0,
                                "meanOperationMs",
                                (done - mutateStart) / 3000000000.0,
                                "heapUsedBytes",
                                Runtime.getRuntime().totalMemory()
                                        - Runtime.getRuntime().freeMemory())
                        .toString());
    }

    @Test
    void mini_discord_project_guild_suite_administration_test() throws Exception {
        f.api("GET", "/guilds/" + f.guild, null, 200);
        f.api("PATCH", "/guilds/" + f.guild, obj("description", "Synthetic local community"), 200);
        String role = f.api(
                        "POST", "/guilds/" + f.guild + "/roles", obj("name", "Workshop", "permissions", "1024"), 200)
                .body()
                .path("id")
                .asString();
        f.api("PATCH", "/guilds/" + f.guild + "/roles", arr(List.of(obj("id", role, "position", 2))), 200);
        f.api("PATCH", "/guilds/" + f.guild + "/roles/" + role, obj("name", "Workshop members", "color", 255), 200);
        String category = f.api("POST", "/guilds/" + f.guild + "/channels", obj("name", "Workshops", "type", 4), 201)
                .body()
                .path("id")
                .asString();
        String channel = f.api(
                        "POST", "/guilds/" + f.guild + "/channels", obj("name", "workshop", "parent_id", category), 201)
                .body()
                .path("id")
                .asString();
        f.api(
                "PUT",
                "/channels/" + channel + "/permissions/" + role,
                obj("type", 0, "allow", "1024", "deny", "0"),
                204);
        f.api("PATCH", "/channels/" + channel, obj("topic", "Local workshop"), 200);
        f.api(
                "PATCH",
                "/guilds/" + f.guild + "/channels",
                arr(List.of(obj("id", channel, "position", 1, "parent_id", category, "lock_permissions", true))),
                204);
        f.api("DELETE", "/channels/" + channel, null, 200);
        f.api("DELETE", "/guilds/" + f.guild + "/roles/" + role, null, 204);
        assertThat(f.eventCount("CHANNEL_CREATE")).isEqualTo(2);
        assertThat(f.eventCount("GUILD_ROLE_CREATE")).isEqualTo(1);
        assertThat(f.api("GET", "/guilds/" + f.guild + "/audit-logs", null, 200)
                        .body()
                        .path("audit_log_entries")
                        .size())
                .isGreaterThanOrEqualTo(8);
    }

    @Test
    void mini_discord_project_events_suite_scheduled_meetup_test() throws Exception {
        JsonNode event = f.api(
                        "POST",
                        "/guilds/" + f.guild + "/scheduled-events",
                        obj(
                                "name",
                                "Contract meetup",
                                "privacy_level",
                                2,
                                "entity_type",
                                3,
                                "entity_metadata",
                                obj("location", "Offline room"),
                                "scheduled_start_time",
                                "2030-02-01T10:00:00Z",
                                "scheduled_end_time",
                                "2030-02-01T12:00:00Z"),
                        200)
                .body();
        String path =
                "/guilds/" + f.guild + "/scheduled-events/" + event.path("id").asString();
        f.api("GET", path, null, 200);
        f.api("PATCH", path, obj("name", "Updated meetup"), 200);
        f.control(
                "POST",
                "scheduled-events/" + event.path("id").asString() + "/interest",
                obj("guild_id", f.guild, "user_id", f.user("student-1")),
                204);
        assertThat(f.api("GET", path + "?with_user_count=true", null, 200)
                        .body()
                        .path("user_count")
                        .asInt())
                .isEqualTo(1);
        assertThat(f.api("GET", path + "/users?with_member=true", null, 200)
                        .body()
                        .size())
                .isEqualTo(1);
        f.api("PATCH", path, obj("status", 2), 200);
        f.api("PATCH", path, obj("status", 3), 200);
        f.api("PATCH", path, obj("status", 1), 400);
        f.api("DELETE", path, null, 204);
        f.api("GET", path, null, 404);
        assertThat(f.eventCount("GUILD_SCHEDULED_EVENT_UPDATE")).isEqualTo(3);
    }

    @Test
    void mini_discord_project_gateway_suite_membership_events_test() throws Exception {
        GatewayTest.membership(f);
    }

    @Test
    void mini_discord_project_oauth_suite_expiration_refresh_test() throws Exception {
        String uid = f.newUser();
        JsonNode token = f.token(uid, "identify guilds.join");
        f.control("POST", "clock", obj("advance_seconds", 3601), 200);
        f.request(
                "GET",
                "/api/v10/users/@me",
                null,
                "Bearer " + token.path("access_token").asString(),
                false,
                401);
        JsonNode refreshed = f.request(
                        "POST",
                        "/api/v10/oauth2/token",
                        obj(
                                "client_id",
                                f.appId,
                                "client_secret",
                                f.secret,
                                "grant_type",
                                "refresh_token",
                                "refresh_token",
                                token.path("refresh_token")),
                        "",
                        true,
                        200)
                .body();
        assertThat(f.request(
                                "GET",
                                "/api/v10/users/@me",
                                null,
                                "Bearer " + refreshed.path("access_token").asString(),
                                false,
                                200)
                        .body()
                        .path("id")
                        .asString())
                .isEqualTo(uid);
    }

    @Test
    void mini_discord_project_oauth_suite_revocation_test() throws Exception {
        JsonNode token = f.token(f.newUser(), "identify guilds.join");
        f.request(
                "POST",
                "/api/v10/oauth2/token/revoke",
                obj("client_id", f.appId, "client_secret", f.secret, "token", token.path("refresh_token")),
                "",
                true,
                200);
        f.request(
                "GET",
                "/api/v10/users/@me",
                null,
                "Bearer " + token.path("access_token").asString(),
                false,
                401);
        f.request(
                "POST",
                "/api/v10/oauth2/token",
                obj(
                        "client_id",
                        f.appId,
                        "client_secret",
                        f.secret,
                        "grant_type",
                        "refresh_token",
                        "refresh_token",
                        token.path("refresh_token")),
                "",
                true,
                400);
    }

    @Test
    void mini_discord_project_resilience_suite_outage_recovery_test() throws Exception {
        String uid = f.newUser(),
                access =
                        f.token(uid, "identify guilds.join")
                                .path("access_token")
                                .asString();
        f.control(
                "POST",
                "faults",
                obj("route", "PUT /guilds/{guildId}/members/{userId}", "status", 503, "failNext", 1),
                201);
        f.api("PUT", "/guilds/" + f.guild + "/members/" + uid, obj("access_token", access), 503);
        f.api("GET", "/guilds/" + f.guild + "/members/" + uid, null, 404);
        f.api("PUT", "/guilds/" + f.guild + "/members/" + uid, obj("access_token", access), 201);
    }

    @Test
    void mini_discord_project_resilience_suite_rate_limit_retry_test() throws Exception {
        f.control(
                "POST",
                "rate-limits",
                obj("route", "PUT /guilds/{guildId}/members/{userId}/roles/{roleId}", "limit", 1, "windowSeconds", 2),
                201);
        f.grant(f.user("student-1"), f.role("SPEAKER"));
        HttpFixture.Response rate = f.api(
                "PUT",
                "/guilds/" + f.guild + "/members/" + f.user("student-2") + "/roles/" + f.role("SPEAKER"),
                null,
                429);
        assertThat(rate.raw().headers().firstValue("Retry-After")).contains("2");
        assertThat(rate.body().path("global").asBoolean()).isFalse();
        f.control("POST", "clock", obj("advance_seconds", 2), 200);
        f.grant(f.user("student-2"), f.role("SPEAKER"));
    }

    @Test
    void mini_discord_project_security_suite_multiple_guilds_test() throws Exception {
        f.seed("multiple");
        String other = f.fixture.path("guild_ids").get(1).asString();
        f.api("GET", "/guilds/" + other, null, 403);
        f.api("PATCH", "/guilds/" + other, obj("name", "illegal"), 403);
        f.api("PUT", "/guilds/" + other + "/members/" + f.user("student-1") + "/roles/" + f.role("MEMBER"), null, 403);
        assertThat(f.control("GET", "state", null, 200)
                        .body()
                        .path("guilds")
                        .get(1)
                        .path("name")
                        .asString())
                .isEqualTo("JUG Lugano Local B");
    }

    @Test
    void mini_discord_project_membership_suite_complete_jug_contract_test() throws Exception {
        String uid = f.newUser();
        JsonNode token = f.token(uid, "identify guilds.join");
        assertThat(f.request(
                                "GET",
                                "/api/v10/users/@me",
                                null,
                                "Bearer " + token.path("access_token").asString(),
                                false,
                                200)
                        .body()
                        .path("id")
                        .asString())
                .isEqualTo(uid);
        f.api("PUT", "/guilds/" + f.guild + "/members/" + uid, obj("access_token", token.path("access_token")), 201);
        f.control("POST", "members/" + uid + "/complete-screening", obj("guild_id", f.guild), 200);
        f.grant(uid, f.role("MEMBER"));
        f.readable(uid, f.channel("java-jvm"), true);
        f.revoke(uid, f.role("MEMBER"));
        f.readable(uid, f.channel("java-jvm"), false);
        f.grant(uid, f.role("MEMBER"));
        f.readable(uid, f.channel("java-jvm"), true);
        f.api("DELETE", "/guilds/" + f.guild + "/members/" + uid, null, 204);
        f.api("GET", "/guilds/" + f.guild + "/members/" + uid, null, 404);
        f.readable(uid, f.channel("java-jvm"), false);
        assertThat(f.eventCount("GUILD_MEMBER_ADD")).isEqualTo(1);
        assertThat(f.eventCount("GUILD_MEMBER_REMOVE")).isEqualTo(1);
    }
}
