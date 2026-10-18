package mini.discord;

import static mini.discord.Json.*;
import static mini.discord.PermissionEngine.*;
import static org.assertj.core.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import tools.jackson.databind.JsonNode;

class PermissionsTest {
    @RegisterExtension
    final HttpFixture f = new HttpFixture();

    @Test
    void newly_installed_bot_can_create_its_first_lower_role() throws Exception {
        String guild = f.control("POST", "guilds", obj("name", "New synthetic guild", "owner_id", f.user("owner")), 201)
                .body()
                .path("id")
                .asString();
        JsonNode installation = f.control(
                        "POST",
                        "installations",
                        obj(
                                "application_id",
                                f.appId,
                                "guild_id",
                                guild,
                                "requested_permissions",
                                ROLES.toString(),
                                "authorized_permissions",
                                ROLES.toString(),
                                "position",
                                1),
                        201)
                .body();
        JsonNode role = f.api("POST", "/guilds/" + guild + "/roles", obj("name", "First member role"), 200)
                .body();
        assertThat(role.path("position").asInt()).isEqualTo(1);
        assertThat(role.path("permissions").asString())
                .isEqualTo(VIEW.or(HISTORY).toString());
        JsonNode roles = f.api("GET", "/guilds/" + guild + "/roles", null, 200).body();
        for (JsonNode entry : roles)
            if (entry.path("id").asString().equals(installation.path("role_id").asString()))
                assertThat(entry.path("position").asInt()).isEqualTo(2);
    }

    @Test
    void manage_roles_channel_overwrite_requires_administrator() throws Exception {
        String channel = f.channel("java-jvm");
        JsonNode before = f.api("GET", "/channels/" + channel, null, 200).body();
        f.api(
                "PUT",
                "/channels/" + channel + "/permissions/" + f.role("MEMBER"),
                obj("type", 0, "allow", ROLES.toString(), "deny", "0"),
                403);
        assertThat(f.api("GET", "/channels/" + channel, null, 200).body()).isEqualTo(before);
        f.api(
                "POST",
                "/guilds/" + f.guild + "/channels",
                obj(
                        "name",
                        "forbidden-delegation",
                        "permission_overwrites",
                        List.of(obj("id", f.role("MEMBER"), "type", 0, "allow", ROLES.toString(), "deny", "0"))),
                403);
    }

    @Test
    void role_allow_overrides_combined_role_deny_but_member_deny_wins() throws Exception {
        String uid = f.user("student-1"), cid = f.channel("java-jvm");
        f.api(
                "PUT",
                "/channels/" + cid + "/permissions/" + f.role("STUDENT"),
                obj("type", 0, "allow", "0", "deny", VIEW.toString()),
                204);
        f.readable(uid, cid, true);
        f.api(
                "PUT",
                "/channels/" + cid + "/permissions/" + uid,
                obj("type", 1, "allow", "0", "deny", VIEW.toString()),
                204);
        f.readable(uid, cid, false);
        f.api(
                "PUT",
                "/channels/" + cid + "/permissions/" + uid,
                obj("type", 1, "allow", VIEW.toString(), "deny", "0"),
                204);
        f.readable(uid, cid, true);
        f.api("DELETE", "/channels/" + cid + "/permissions/" + uid, null, 204);
        f.readable(uid, cid, true);
    }

    @Test
    void admin_label_does_not_grant_administrator() throws Exception {
        String uid = f.user("unapproved");
        f.grant(uid, f.role("ADMIN"));
        f.readable(uid, f.channel("java-jvm"), false);
        f.readable(uid, f.channel("staff"), false);
        f.readable(f.user("owner"), f.channel("staff"), true);
        f.api(
                "PATCH",
                "/guilds/" + f.guild + "/roles/" + f.role("MEMBER"),
                obj("permissions", ADMINISTRATOR.toString()),
                403);
    }

    @Test
    void administrator_bypass_and_owner_protection() throws Exception {
        JsonNode admin = f.control(
                        "POST",
                        "bots",
                        obj("name", "Explicit administrator", "redirect_uris", List.of("http://127.0.0.1/callback")),
                        201)
                .body();
        f.control(
                "POST",
                "installations",
                obj(
                        "guild_id",
                        f.guild,
                        "application_id",
                        admin.path("id"),
                        "requested_permissions",
                        ADMINISTRATOR.toString(),
                        "authorized_permissions",
                        ADMINISTRATOR.toString()),
                201);
        f.request(
                "GET",
                "/api/v10/channels/" + f.channel("staff"),
                null,
                "Bot " + admin.path("bot_token").asString(),
                false,
                200);
        f.request(
                "DELETE",
                "/api/v10/guilds/" + f.guild + "/members/" + f.user("owner"),
                null,
                "Bot " + admin.path("bot_token").asString(),
                false,
                403);
        f.api("PUT", "/guilds/" + f.guild + "/bans/" + f.user("owner"), obj(), 403);
    }

    @Test
    void category_sync_and_unsynced_channels() throws Exception {
        String category = f.api(
                        "POST", "/guilds/" + f.guild + "/channels", obj("name", "Restricted category", "type", 4), 201)
                .body()
                .path("id")
                .asString();
        String synced = f.api(
                        "POST",
                        "/guilds/" + f.guild + "/channels",
                        obj("name", "inherited", "parent_id", category),
                        201)
                .body()
                .path("id")
                .asString();
        String independent = f.api(
                        "POST",
                        "/guilds/" + f.guild + "/channels",
                        obj("name", "independent", "parent_id", category, "permission_overwrites", List.of()),
                        201)
                .body()
                .path("id")
                .asString();
        f.api(
                "PUT",
                "/channels/" + category + "/permissions/" + f.role("BOT"),
                obj("type", 0, "allow", VIEW.toString(), "deny", "0"),
                204);
        f.api(
                "PUT",
                "/channels/" + category + "/permissions/" + f.guild,
                obj("type", 0, "allow", "0", "deny", VIEW.toString()),
                204);
        f.readable(f.user("student-1"), synced, false);
        f.readable(f.user("student-1"), independent, true);
        f.api("DELETE", "/channels/" + category, null, 200);
        f.readable(f.user("student-1"), synced, false);
    }

    @Test
    void invalid_member_patch_is_atomic() throws Exception {
        String uid = f.user("student-1");
        JsonNode before = f.api("GET", "/guilds/" + f.guild + "/members/" + uid, null, 200)
                .body();
        f.api(
                "PATCH",
                "/guilds/" + f.guild + "/members/" + uid,
                obj("roles", List.of(f.role("STAFF")), "communication_disabled_until", "2031-01-01T00:00:00Z"),
                400);
        assertThat(f.api("GET", "/guilds/" + f.guild + "/members/" + uid, null, 200)
                        .body())
                .isEqualTo(before);
        assertThat(f.eventCount("GUILD_MEMBER_UPDATE")).isZero();
        f.api(
                "PATCH",
                "/guilds/" + f.guild + "/roles",
                arr(List.of(obj("id", f.role("MEMBER"), "position", 2), obj("id", f.role("BOT"), "position", 1))),
                403);
        assertThat(f.eventCount("GUILD_ROLE_UPDATE")).isZero();
    }

    @Test
    void timeout_removal_and_clock_expiry() throws Exception {
        String uid = f.user("student-1");
        f.api(
                "PATCH",
                "/guilds/" + f.guild + "/members/" + uid,
                obj("communication_disabled_until", "2030-01-01T00:01:00Z"),
                200);
        assertThat(f.control("POST", "permissions", obj("user_id", uid, "channel_id", f.channel("java-jvm")), 200)
                        .body()
                        .path("can_post")
                        .asBoolean())
                .isFalse();
        f.readable(uid, f.channel("java-jvm"), true);
        f.control("POST", "clock", obj("advance_seconds", 61), 200);
        assertThat(f.control("POST", "permissions", obj("user_id", uid, "channel_id", f.channel("java-jvm")), 200)
                        .body()
                        .path("can_post")
                        .asBoolean())
                .isTrue();
        f.api("PATCH", "/guilds/" + f.guild + "/members/" + uid, obj("communication_disabled_until", null), 200);
    }

    @Test
    void public_bot_channel_reads_obey_overwrites() throws Exception {
        JsonNode visitor = f.control(
                        "POST",
                        "bots",
                        obj("name", "Visitor bot", "redirect_uris", List.of("http://127.0.0.1/callback")),
                        201)
                .body();
        f.control(
                "POST",
                "installations",
                obj(
                        "guild_id",
                        f.guild,
                        "application_id",
                        visitor.path("id"),
                        "requested_permissions",
                        VIEW.or(HISTORY).toString(),
                        "authorized_permissions",
                        VIEW.or(HISTORY).toString(),
                        "position",
                        1),
                201);
        String auth = "Bot " + visitor.path("bot_token").asString(),
                uid = visitor.path("bot_user_id").asString();
        f.request("GET", "/api/v10/channels/" + f.channel("java-jvm") + "/messages", null, auth, false, 403);
        f.grant(uid, f.role("MEMBER"));
        f.request("GET", "/api/v10/channels/" + f.channel("java-jvm") + "/messages", null, auth, false, 200);
        f.revoke(uid, f.role("MEMBER"));
        f.request("GET", "/api/v10/channels/" + f.channel("java-jvm") + "/messages", null, auth, false, 403);
    }
}
