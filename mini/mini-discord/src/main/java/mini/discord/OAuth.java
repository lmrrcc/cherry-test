package mini.discord;

import static mini.discord.Json.*;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mini.discord.DiscordState.Application;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

final class OAuth {
    record Request(String id, String app, String redirect, Set<String> scopes, String state, Instant expires) {}

    record Code(String app, String user, String redirect, Set<String> scopes, Instant expires) {}

    static final class Grant {
        final String app;
        final String user;
        final Set<String> scopes;
        final Instant expires;
        final Instant refreshExpires;
        final String access = DiscordState.credential("access");
        final String refresh = DiscordState.credential("refresh");
        boolean revoked;
        boolean refreshed;

        Grant(String app, String user, Set<String> scopes, Instant now) {
            this.app = app;
            this.user = user;
            this.scopes = Set.copyOf(scopes);
            expires = now.plusSeconds(3600);
            refreshExpires = now.plusSeconds(2592000);
        }

        ObjectNode json() {
            return obj(
                    "access_token",
                    access,
                    "token_type",
                    "Bearer",
                    "expires_in",
                    3600,
                    "refresh_token",
                    refresh,
                    "scope",
                    String.join(" ", scopes.stream().sorted().toList()));
        }
    }

    private final DiscordState state;
    final Map<String, Request> requests = new LinkedHashMap<>();
    final Map<String, Code> codes = new LinkedHashMap<>();
    final Map<String, Grant> grants = new LinkedHashMap<>();

    OAuth(DiscordState state) {
        this.state = state;
    }

    static void localRedirect(String redirect) {
        try {
            URI uri = URI.create(redirect);
            if (!List.of("http", "https").contains(uri.getScheme())
                    || uri.getHost() == null
                    || !List.of("127.0.0.1", "localhost", "[::1]").contains(uri.getHost())
                    || uri.getUserInfo() != null
                    || uri.getFragment() != null) throw ApiError.invalid("redirect_uri must be loopback");
        } catch (IllegalArgumentException e) {
            throw ApiError.invalid("redirect_uri");
        }
    }

    Application application(String id) {
        Application app = state.applications.get(id);
        if (app == null || !app.enabled) throw new ApiError("invalid_client");
        return app;
    }

    Request authorize(JsonNode input) {
        Application app = application(required(input, "client_id"));
        String redirect = required(input, "redirect_uri");
        if (!app.redirects.contains(redirect)) throw new ApiError("invalid_request");
        if (!str(input, "response_type", "").equals("code")) throw new ApiError("unsupported_response_type");
        if (input.has("code_challenge") || input.has("code_challenge_method")) throw new ApiError("invalid_request");
        Set<String> scopes = scopes(str(input, "scope", ""));
        if (scopes.isEmpty() || !app.scopes.containsAll(scopes)) throw new ApiError("invalid_scope");
        Request request = new Request(
                DiscordState.credential("request"),
                app.id,
                redirect,
                scopes,
                str(input, "state", ""),
                state.clock.instant().plusSeconds(300));
        requests.put(request.id, request);
        return request;
    }

    ObjectNode consent(JsonNode input, boolean denied) {
        Request request = requests.get(required(input, "request_id"));
        if (request == null || !state.clock.instant().isBefore(request.expires)) throw new ApiError("invalid_request");
        String redirect;
        if (denied) {
            redirect = callback(request, "error", "access_denied");
        } else {
            String user = required(input, "user_id");
            if (state.user(user).path("bot").asBoolean()) throw ApiError.invalid("user_id");
            Set<String> selected =
                    input.has("scopes") ? new LinkedHashSet<>(strings(input.path("scopes"))) : request.scopes;
            if (!request.scopes.containsAll(selected) || selected.isEmpty()) throw new ApiError("invalid_scope");
            String code = DiscordState.credential("code");
            codes.put(
                    code,
                    new Code(
                            request.app,
                            user,
                            request.redirect,
                            selected,
                            state.clock.instant().plusSeconds(300)));
            redirect = callback(request, "code", code);
        }
        requests.remove(request.id);
        return obj("redirect_uri", redirect);
    }

    private static String callback(Request request, String key, String value) {
        return request.redirect + (request.redirect.contains("?") ? "&" : "?") + key + "=" + encode(value)
                + (request.state.isEmpty() ? "" : "&state=" + encode(request.state));
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    static Set<String> scopes(String raw) {
        return raw.isBlank()
                ? Set.of()
                : new LinkedHashSet<>(Arrays.asList(raw.trim().split("\\s+", -1)));
    }

    Application client(JsonNode input, String authorization) {
        String client = str(input, "client_id", ""), secret = str(input, "client_secret", "");
        if (authorization.startsWith("Basic ")) {
            try {
                String[] parts = new String(
                                Base64.getDecoder().decode(authorization.substring(6)), StandardCharsets.UTF_8)
                        .split(":", 2);
                if (parts.length != 2) throw new ApiError("invalid_client");
                if (!client.isEmpty() || !secret.isEmpty()) throw new ApiError("invalid_client");
                client = parts[0];
                secret = parts[1];
            } catch (IllegalArgumentException e) {
                throw new ApiError("invalid_client");
            }
        } else if (!authorization.isEmpty()) throw new ApiError("invalid_client");
        Application app = application(client);
        if (!java.security.MessageDigest.isEqual(
                app.secret.getBytes(StandardCharsets.UTF_8), secret.getBytes(StandardCharsets.UTF_8)))
            throw new ApiError("invalid_client");
        return app;
    }

    ObjectNode token(JsonNode input, String authorization) {
        Application app = client(input, authorization);
        String grantType = str(input, "grant_type", "");
        Grant grant;
        if (grantType.equals("authorization_code")) {
            String value = str(input, "code", "");
            Code code = codes.get(value);
            if (code == null
                    || !code.app.equals(app.id)
                    || !state.clock.instant().isBefore(code.expires)
                    || !code.redirect.equals(str(input, "redirect_uri", ""))) throw new ApiError("invalid_grant");
            state.user(code.user);
            grant = new Grant(app.id, code.user, code.scopes, state.clock.instant());
            codes.remove(value);
        } else if (grantType.equals("refresh_token")) {
            String value = str(input, "refresh_token", "");
            Grant old = grants.values().stream()
                    .filter(g -> g.refresh.equals(value))
                    .findFirst()
                    .orElseThrow(() -> new ApiError("invalid_grant"));
            if (old.revoked
                    || old.refreshed
                    || !old.app.equals(app.id)
                    || !state.clock.instant().isBefore(old.refreshExpires)) throw new ApiError("invalid_grant");
            state.user(old.user);
            Set<String> selected =
                    input.has("scope") ? scopes(input.path("scope").asString()) : old.scopes;
            if (!old.scopes.containsAll(selected)) throw new ApiError("invalid_scope");
            grant = new Grant(app.id, old.user, selected, state.clock.instant());
            old.refreshed = true;
        } else throw new ApiError("unsupported_grant_type");
        grants.put(grant.access, grant);
        return grant.json();
    }

    Grant access(String value, String scope) {
        Grant grant = grants.get(value);
        if (grant == null
                || grant.revoked
                || !state.clock.instant().isBefore(grant.expires)
                || !application(grant.app).enabled) throw ApiError.unauthorized();
        state.user(grant.user);
        if (!grant.scopes.contains(scope)) throw new ApiError(403, 50026, "Missing required OAuth2 scope");
        return grant;
    }

    ObjectNode me(String authorization) {
        if (authorization.startsWith("Bot ")) return state.publicUser(state.bot(authorization).bot);
        if (!authorization.startsWith("Bearer ")) throw ApiError.unauthorized();
        Grant grant = access(authorization.substring(7), "identify");
        ObjectNode user = state.user(grant.user).deepCopy();
        user.remove("unavailable");
        if (!grant.scopes.contains("email")) {
            user.remove("email");
            user.remove("verified");
        }
        return user;
    }

    void revoke(JsonNode input, String authorization) {
        Application app = client(input, authorization);
        String token = required(input, "token");
        grants.values().stream()
                .filter(g -> g.app.equals(app.id) && (g.access.equals(token) || g.refresh.equals(token)))
                .findFirst()
                .ifPresent(g -> revokeConsent(g.app, g.user));
    }

    void revokeConsent(String app, String user) {
        grants.values().stream()
                .filter(g -> g.app.equals(app) && g.user.equals(user))
                .forEach(g -> g.revoked = true);
        codes.entrySet()
                .removeIf(e -> e.getValue().app.equals(app) && e.getValue().user.equals(user));
    }
}
