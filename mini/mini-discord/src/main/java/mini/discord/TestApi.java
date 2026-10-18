package mini.discord;

import static mini.discord.Json.*;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import mini.discord.DiscordState.Application;
import mini.discord.DiscordState.Guild;
import mini.discord.DiscordState.Member;
import tools.jackson.databind.JsonNode;

final class TestApi {
    private final MiniDiscordServer server;
    private final DiscordState state;
    private final OAuth oauth;
    private final Administration administration;

    TestApi(MiniDiscordServer server, DiscordState state, OAuth oauth, Administration administration) {
        this.server = server;
        this.state = state;
        this.oauth = oauth;
        this.administration = administration;
    }

    static Application createApplication(DiscordState state, JsonNode input) {
        fields(input, "name", "redirect_uris", "authorized_scopes", "members_intent");
        String name = name(input, "name", 100);
        Set<String> redirects = new LinkedHashSet<>(strings(input.path("redirect_uris")));
        if (redirects.isEmpty()) throw ApiError.invalid("redirect_uris");
        redirects.forEach(OAuth::localRedirect);
        Set<String> scopes = input.has("authorized_scopes")
                ? new LinkedHashSet<>(strings(input.path("authorized_scopes")))
                : Set.of("identify", "guilds.join", "email");
        if (!Set.of("identify", "guilds.join", "email").containsAll(scopes))
            throw ApiError.invalid("authorized_scopes");
        boolean intent = bool(input, "members_intent", false);
        String bot = state.createUser(obj("username", name.length() > 32 ? name.substring(0, 32) : name, "bot", true))
                .path("id")
                .asString();
        Application app = new Application(state.id(), bot, name, redirects, scopes, intent);
        state.applications.put(app.id, app);
        return app;
    }

    Reply route(String method, String path, JsonNode input) {
        if (path.equals("/_test/reset") && method.equals("POST")) {
            server.reset(null);
            return Reply.ok(obj("reset", true));
        }
        if (path.equals("/_test/seed") && method.equals("POST")) return Reply.ok(server.reset(input));
        if (path.equals("/_test/state") && method.equals("GET")) return Reply.ok(Evidence.sanitize(state.snapshot()));
        if (path.equals("/_test/clock") && method.equals("POST")) {
            if (!(state.clock instanceof TestClock clock)) throw ApiError.invalid("clock is not controllable");
            clock.advance(Duration.ofSeconds(integer(input, "advance_seconds", 0, 0, 315360000)));
            return Reply.ok(obj("now", clock.instant().toString()));
        }
        if (path.equals("/_test/users") && method.equals("POST")) return Reply.of(201, state.createUser(input));
        if (path.equals("/_test/guilds") && method.equals("POST"))
            return Reply.of(201, state.createGuild(input).json());
        if ((path.equals("/_test/bots") || path.equals("/_test/applications")) && method.equals("POST"))
            return Reply.of(201, createApplication(state, input).credentials());
        if (path.equals("/_test/installations") && method.equals("POST"))
            return Reply.of(
                    201,
                    Fixtures.install(
                            state,
                            state.guild(required(input, "guild_id")),
                            oauth.application(required(input, "application_id")),
                            input));
        if (path.startsWith("/_test/applications/") && method.equals("PATCH")) {
            Application app = oauth.application(path.substring("/_test/applications/".length()));
            fields(input, "enabled", "revoked", "members_intent");
            boolean enabled = bool(input, "enabled", app.enabled),
                    revoked = bool(input, "revoked", app.revoked),
                    intent = bool(input, "members_intent", app.membersIntent);
            app.enabled = enabled;
            app.revoked = revoked;
            app.membersIntent = intent;
            return Reply.ok(app.publicJson());
        }
        if (path.equals("/_test/oauth/authorize") && method.equals("POST"))
            return Reply.ok(oauth.consent(input, false));
        if (path.equals("/_test/oauth/deny") && method.equals("POST")) return Reply.ok(oauth.consent(input, true));
        if (path.equals("/_test/oauth/revoke") && method.equals("POST")) {
            oauth.revokeConsent(required(input, "application_id"), required(input, "user_id"));
            return Reply.empty();
        }
        if (path.startsWith("/_test/consent/") && method.equals("GET")) {
            OAuth.Request request = oauth.requests.get(path.substring("/_test/consent/".length()));
            if (request == null) throw ApiError.unsupported();
            return Reply.ok(obj(
                    "request_id",
                    request.id(),
                    "application_id",
                    request.app(),
                    "scopes",
                    request.scopes(),
                    "state",
                    request.state(),
                    "local_simulator",
                    true));
        }
        String[] parts = path.substring(1).split("/", -1);
        if (parts.length == 4 && parts[1].equals("members") && method.equals("POST")) {
            String uid = parts[2];
            Guild guild = state.guild(required(input, "guild_id"));
            if (parts[3].equals("join")) return Reply.ok(state.join(guild, uid).json(state));
            if (parts[3].equals("leave")) {
                if (guild.owner().equals(uid)) throw ApiError.permissions();
                state.leave(guild, uid);
                return Reply.empty();
            }
            Member member = state.member(guild, uid);
            if (parts[3].equals("complete-screening")) {
                member.pending = false;
                state.memberEvent("GUILD_MEMBER_UPDATE", guild, member);
                return Reply.ok(member.json(state));
            }
            if (parts[3].equals("disconnect")) {
                if (member.temporary && member.roles.isEmpty()) state.leave(guild, uid);
                return Reply.empty();
            }
        }
        if (parts.length == 4 && parts[1].equals("invites") && parts[3].equals("accept") && method.equals("POST"))
            return Reply.ok(administration.accept(parts[2], required(input, "user_id")));
        if (path.equals("/_test/permissions") && method.equals("POST")) {
            JsonNode channel = state.channel(required(input, "channel_id"));
            String uid = required(input, "user_id");
            state.user(uid);
            java.math.BigInteger permissions = PermissionEngine.channel(state, channel, uid);
            return Reply.ok(obj(
                    "permissions",
                    permissions.toString(),
                    "can_view",
                    PermissionEngine.has(permissions, PermissionEngine.VIEW),
                    "can_post",
                    PermissionEngine.has(permissions, PermissionEngine.SEND)));
        }
        if (path.equals("/_test/read-channel") && method.equals("POST")) {
            JsonNode channel = state.channel(required(input, "channel_id"));
            String uid = required(input, "user_id");
            state.user(uid);
            PermissionEngine.requireChannel(state, channel, uid, PermissionEngine.VIEW.or(PermissionEngine.HISTORY));
            return Reply.ok(arr(state.messages.getOrDefault(channel.path("id").asString(), List.of())));
        }
        if (parts.length == 4
                && parts[1].equals("scheduled-events")
                && parts[3].equals("interest")
                && (method.equals("POST") || method.equals("DELETE"))) {
            Guild guild = state.guild(required(input, "guild_id"));
            String uid = required(input, "user_id");
            state.member(guild, uid);
            JsonNode event = guild.scheduled.get(parts[2]);
            if (event == null) throw new ApiError(404, 10070, "Unknown Guild Scheduled Event");
            if (event.hasNonNull("channel_id"))
                PermissionEngine.requireChannel(
                        state, state.channel(event.path("channel_id").asString()), uid, PermissionEngine.VIEW);
            Set<String> users = guild.interested.computeIfAbsent(parts[2], key -> new LinkedHashSet<>());
            boolean changed = method.equals("POST") ? users.add(uid) : users.remove(uid);
            if (changed)
                state.emit(
                        method.equals("POST") ? "GUILD_SCHEDULED_EVENT_USER_ADD" : "GUILD_SCHEDULED_EVENT_USER_REMOVE",
                        obj("guild_id", guild.id(), "guild_scheduled_event_id", parts[2], "user_id", uid));
            return Reply.empty();
        }
        if (path.equals("/_test/faults")) {
            if (method.equals("POST")) return Reply.of(201, server.faults.add(input));
            if (method.equals("DELETE")) {
                server.faults.clear();
                return Reply.empty();
            }
        }
        if (path.equals("/_test/rate-limits") && method.equals("POST"))
            return Reply.of(201, server.faults.limit(input));
        if (path.equals("/_test/requests")) {
            if (method.equals("GET")) return Reply.ok(arr(server.requests()));
            if (method.equals("DELETE")) {
                server.evidence.clear();
                return Reply.empty();
            }
        }
        if (path.equals("/_test/events")) {
            if (method.equals("GET")) return Reply.ok(Evidence.sanitize(arr(state.events)));
            if (method.equals("DELETE")) {
                state.events.clear();
                return Reply.empty();
            }
        }
        throw ApiError.unsupported();
    }
}
