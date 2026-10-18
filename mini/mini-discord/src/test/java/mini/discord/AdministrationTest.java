package mini.discord;

import static mini.discord.Json.*;
import static org.assertj.core.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import tools.jackson.databind.JsonNode;

class AdministrationTest {
    @RegisterExtension
    final HttpFixture f = new HttpFixture();

    @Test
    void scheduled_voice_requires_connect_and_stage_management() throws Exception {
        JsonNode app = f.control(
                        "POST",
                        "bots",
                        obj("name", "Event planner", "redirect_uris", List.of("http://127.0.0.1/callback")),
                        201)
                .body();
        String permissions = PermissionEngine.CREATE_EVENTS.toString();
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
        String voice = f.api("POST", "/guilds/" + f.guild + "/channels", obj("name", "Public voice", "type", 2), 201)
                .body()
                .path("id")
                .asString();
        JsonNode event = obj(
                "name",
                "Voice meetup",
                "privacy_level",
                2,
                "entity_type",
                2,
                "channel_id",
                voice,
                "scheduled_start_time",
                "2030-02-01T10:00:00Z");
        f.request(
                "POST",
                "/api/v10/guilds/" + f.guild + "/scheduled-events",
                event,
                "Bot " + app.path("bot_token").asString(),
                false,
                403);
        f.grant(app.path("bot_user_id").asString(), f.role("MEMBER"));
        f.request(
                "POST",
                "/api/v10/guilds/" + f.guild + "/scheduled-events",
                event,
                "Bot " + app.path("bot_token").asString(),
                false,
                200);
        String stage = f.api("POST", "/guilds/" + f.guild + "/channels", obj("name", "Stage", "type", 13), 201)
                .body()
                .path("id")
                .asString();
        f.request(
                "POST",
                "/api/v10/guilds/" + f.guild + "/scheduled-events",
                obj(
                        "name",
                        "Stage meetup",
                        "privacy_level",
                        2,
                        "entity_type",
                        1,
                        "channel_id",
                        stage,
                        "scheduled_start_time",
                        "2030-02-01T10:00:00Z"),
                "Bot " + app.path("bot_token").asString(),
                false,
                403);
    }

    @Test
    void invite_expiry_revoke_limit_ban_and_temporary() throws Exception {
        String cid = f.channel("benvenuto"), uid = f.newUser();
        String expired = f.api("POST", "/channels/" + cid + "/invites", obj("max_age", 1, "unique", true), 200)
                .body()
                .path("code")
                .asString();
        f.control("POST", "clock", obj("advance_seconds", 2), 200);
        f.control("POST", "invites/" + expired + "/accept", obj("user_id", uid), 404);
        String revoked = f.api("POST", "/channels/" + cid + "/invites", obj("unique", true), 200)
                .body()
                .path("code")
                .asString();
        f.api("DELETE", "/invites/" + revoked, null, 200);
        f.request("GET", "/api/v10/invites/" + revoked, null, "", false, 404);
        String limited = f.api(
                        "POST",
                        "/channels/" + cid + "/invites",
                        obj("max_uses", 1, "temporary", true, "unique", true),
                        200)
                .body()
                .path("code")
                .asString();
        f.control("POST", "invites/" + limited + "/accept", obj("user_id", f.user("banned")), 403);
        f.request("GET", "/api/v10/invites/" + limited, null, "", false, 200);
        f.control("POST", "invites/" + limited + "/accept", obj("user_id", uid), 200);
        f.control("POST", "invites/" + limited + "/accept", obj("user_id", f.newUser()), 404);
        f.control("POST", "members/" + uid + "/disconnect", obj("guild_id", f.guild), 204);
        f.api("GET", "/guilds/" + f.guild + "/members/" + uid, null, 404);
        f.request("POST", "/api/v10/channels/" + cid + "/invites", obj(), "", false, 401);
        f.request("GET", "/api/v10/invites/unknown", null, "", false, 404);
    }

    @Test
    void message_edit_delete_history_and_private_threads() throws Exception {
        String cid = f.channel("java-jvm");
        JsonNode message = f.api(
                        "POST",
                        "/channels/" + cid + "/messages",
                        obj(
                                "content",
                                "Synthetic text",
                                "embeds",
                                List.of(obj("title", "Local embed", "description", "Offline content"))),
                        200)
                .body();
        String path = "/channels/" + cid + "/messages/" + message.path("id").asString();
        f.api("PATCH", path, obj("content", "Edited"), 200);
        assertThat(f.api("GET", path, null, 200).body().path("content").asString())
                .isEqualTo("Edited");
        assertThat(f.api("GET", "/channels/" + cid + "/messages?limit=1", null, 200)
                        .body()
                        .size())
                .isEqualTo(1);
        JsonNode thread =
                f.api("POST", path + "/threads", obj("name", "Discussion"), 201).body();
        f.api("POST", "/channels/" + thread.path("id").asString() + "/messages", obj("content", "Thread reply"), 200);
        f.api("PATCH", "/channels/" + thread.path("id").asString(), obj("archived", true), 200);
        f.api("POST", "/channels/" + thread.path("id").asString() + "/messages", obj("content", "Blocked"), 403);
        f.api("PATCH", "/channels/" + thread.path("id").asString(), obj("archived", false), 200);
        f.api("DELETE", path, null, 204);
        f.api("GET", path, null, 404);
        String privateThread = f.api(
                        "POST", "/channels/" + cid + "/threads", obj("name", "Private discussion", "type", 12), 201)
                .body()
                .path("id")
                .asString();
        f.readable(f.user("student-1"), privateThread, false);
        f.api("PUT", "/channels/" + privateThread + "/thread-members/" + f.user("student-1"), null, 204);
        f.readable(f.user("student-1"), privateThread, true);
    }

    @Test
    void forum_posts_tags_and_visibility() throws Exception {
        String cid = f.channel("Java Questions");
        JsonNode forum = f.api("GET", "/channels/" + cid, null, 200).body();
        String tag = forum.path("available_tags").get(0).path("id").asString();
        JsonNode thread = f.api(
                        "POST",
                        "/channels/" + cid + "/threads",
                        obj(
                                "name",
                                "Java question",
                                "message",
                                obj("content", "Synthetic question"),
                                "applied_tags",
                                List.of(tag)),
                        201)
                .body();
        assertThat(thread.path("message").path("content").asString()).isEqualTo("Synthetic question");
        f.readable(f.user("student-1"), thread.path("id").asString(), true);
        f.readable(f.user("unapproved"), thread.path("id").asString(), false);
        f.api(
                "POST",
                "/channels/" + cid + "/threads",
                obj("name", "Invalid", "message", obj("content", "test"), "applied_tags", List.of("999")),
                400);
    }

    @Test
    void automod_rule_crud_and_audit() throws Exception {
        String path = "/guilds/" + f.guild + "/auto-moderation/rules";
        JsonNode rule = f.api(
                        "POST",
                        path,
                        obj(
                                "name",
                                "Local keyword rule",
                                "event_type",
                                1,
                                "trigger_type",
                                1,
                                "trigger_metadata",
                                obj("keyword_filter", List.of("synthetic-blocked")),
                                "actions",
                                List.of(obj("type", 1)),
                                "enabled",
                                true),
                        200)
                .body();
        String rid = rule.path("id").asString();
        f.api("GET", path + "/" + rid, null, 200);
        f.api("PATCH", path + "/" + rid, obj("name", "Updated", "enabled", false), 200);
        assertThat(f.api("GET", path, null, 200).body().size()).isEqualTo(1);
        f.api("DELETE", path + "/" + rid, null, 204);
        f.api("GET", path + "/" + rid, null, 404);
        assertThat(f.api("GET", "/guilds/" + f.guild + "/audit-logs?action_type=140", null, 200)
                        .body()
                        .path("audit_log_entries")
                        .size())
                .isEqualTo(1);
    }

    @Test
    void unsupported_operations_are_errors() throws Exception {
        f.api("POST", "/guilds", obj("name", "No public guild creation"), 404);
        f.api(
                "POST",
                "/channels/" + f.channel("java-jvm") + "/messages",
                obj("attachments", List.of(obj("id", "1"))),
                400);
        f.api("POST", "/guilds/" + f.guild + "/channels", obj("name", "unsupported", "type", 16), 400);
        f.api("PATCH", "/guilds/" + f.guild, obj("unsupported_setting", true), 400);
    }
}
