package mini.discord;

import static mini.discord.Json.*;
import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class OAuthTest {
    @RegisterExtension
    final HttpFixture f = new HttpFixture();

    @Test
    void denial_and_state_are_preserved() throws Exception {
        String request = f.authRequest("identify guilds.join", "opaque+state&123");
        JsonNode response =
                f.control("POST", "oauth/deny", obj("request_id", request), 200).body();
        JsonNode callback =
                form(URI.create(response.path("redirect_uri").asString()).getRawQuery());
        assertThat(callback.path("error").asString()).isEqualTo("access_denied");
        assertThat(callback.path("state").asString()).isEqualTo("opaque+state&123");
        assertThat(callback.has("code")).isFalse();
    }

    @Test
    void csrf_state_is_consumer_responsibility() throws Exception {
        String request = f.authRequest("identify", "returned-state");
        String uid = f.newUser();
        JsonNode response = f.control("POST", "oauth/authorize", obj("request_id", request, "user_id", uid), 200)
                .body();
        JsonNode callback =
                form(URI.create(response.path("redirect_uri").asString()).getRawQuery());
        assertThat(callback.path("state").asString()).isNotEqualTo("expected-different-state");
        assertThat(callback.path("code").asString()).startsWith("mini_discord_code_");
    }

    @Test
    void selected_scopes_and_email() throws Exception {
        String uid = f.newUser();
        JsonNode token = f.token(uid, "identify email");
        assertThat(f.request(
                                "GET",
                                "/api/v10/users/@me",
                                null,
                                "Bearer " + token.path("access_token").asString(),
                                false,
                                200)
                        .body()
                        .path("email")
                        .asString())
                .isEqualTo("synthetic@example.test");
        String request = f.authRequest("identify guilds.join email", "state");
        JsonNode accepted = f.control(
                        "POST",
                        "oauth/authorize",
                        obj("request_id", request, "user_id", uid, "scopes", List.of("identify")),
                        200)
                .body();
        String code = form(URI.create(accepted.path("redirect_uri").asString()).getRawQuery())
                .path("code")
                .asString();
        JsonNode limited = f.request("POST", "/api/v10/oauth2/token", f.exchangeBody(code), "", true, 200)
                .body();
        assertThat(limited.path("scope").asString()).isEqualTo("identify");
        f.api("PUT", "/guilds/" + f.guild + "/members/" + uid, obj("access_token", limited.path("access_token")), 403);
    }

    @Test
    void invalid_redirect_credentials_scope_and_content_type() throws Exception {
        String query = HttpFixture.formBody(obj(
                "client_id",
                f.appId,
                "redirect_uri",
                "http://127.0.0.1/unregistered",
                "response_type",
                "code",
                "scope",
                "identify"));
        f.request("GET", "/oauth2/authorize?" + query, null, "", false, 400);
        f.request(
                "GET",
                "/oauth2/authorize?" + query.replace("unregistered", "callback").replace("identify", "unknown"),
                null,
                "",
                false,
                400);
        String code = f.consent(f.newUser(), "identify");
        ObjectNode body = f.exchangeBody(code);
        body.put("client_secret", "mini_discord_secret_invalid");
        f.request("POST", "/api/v10/oauth2/token", body, "", true, 401);
        f.request("POST", "/api/v10/oauth2/token", f.exchangeBody(code), "", false, 400);
        f.request("POST", "/api/v10/oauth2/token", f.exchangeBody(code), "", true, 200);
    }

    @Test
    void codes_expire_without_consuming_other_users() throws Exception {
        String uid = f.newUser(), code = f.consent(uid, "identify");
        f.control("POST", "clock", obj("advance_seconds", 301), 200);
        f.request("POST", "/api/v10/oauth2/token", f.exchangeBody(code), "", true, 400);
        JsonNode token = f.token(uid, "identify");
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
    }

    @Test
    void token_types_and_application_user_binding() throws Exception {
        String a = f.newUser(), b = f.newUser();
        String token = f.token(a, "identify guilds.join").path("access_token").asString();
        f.api("PUT", "/guilds/" + f.guild + "/members/" + b, obj("access_token", token), 403);
        f.request("GET", "/api/v10/guilds/" + f.guild, null, "Bearer " + token, false, 401);
        f.request("GET", "/api/v10/users/@me", null, "Bearer " + f.bot, false, 401);
        f.request("GET", "/api/v10/users/@me", null, "Bot " + token, false, 401);
        JsonNode other = f.control(
                        "POST",
                        "bots",
                        obj("name", "Other app", "redirect_uris", List.of("http://127.0.0.1/callback")),
                        201)
                .body();
        f.control(
                "POST",
                "installations",
                obj(
                        "application_id",
                        other.path("id"),
                        "guild_id",
                        f.guild,
                        "requested_permissions",
                        "1",
                        "authorized_permissions",
                        "1"),
                201);
        f.request(
                "PUT",
                "/api/v10/guilds/" + f.guild + "/members/" + a,
                obj("access_token", token),
                "Bot " + other.path("bot_token").asString(),
                false,
                403);
        f.api("GET", "/guilds/" + f.guild + "/members/" + a, null, 404);
    }

    @Test
    void revoked_bot_and_user_consent() throws Exception {
        String uid = f.newUser();
        JsonNode token = f.token(uid, "identify");
        f.control("POST", "oauth/revoke", obj("application_id", f.appId, "user_id", uid), 204);
        f.request(
                "GET",
                "/api/v10/users/@me",
                null,
                "Bearer " + token.path("access_token").asString(),
                false,
                401);
        f.control("PATCH", "applications/" + f.appId, obj("revoked", true), 200);
        f.api("GET", "/guilds/" + f.guild, null, 401);
    }
}
