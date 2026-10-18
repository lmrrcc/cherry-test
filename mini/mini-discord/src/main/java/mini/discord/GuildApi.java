package mini.discord;

import static mini.discord.Json.*;
import static mini.discord.PermissionEngine.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import mini.discord.DiscordState.Application;
import mini.discord.DiscordState.Guild;
import mini.discord.DiscordState.Member;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

final class GuildApi {
    private final DiscordState state;
    private final OAuth oauth;
    private final ChannelApi channels;
    private final Administration administration;

    GuildApi(DiscordState state, OAuth oauth, ChannelApi channels, Administration administration) {
        this.state = state;
        this.oauth = oauth;
        this.channels = channels;
        this.administration = administration;
    }

    Reply route(String method, String[] path, JsonNode input, JsonNode query, String auth, String reason) {
        state.bot(auth);
        Guild guild = state.guild(path[1]);
        Application app = state.installed(auth, guild);
        if (path.length == 2) {
            if (method.equals("GET")) return Reply.ok(guild.json());
            if (method.equals("PATCH")) {
                require(guild, app.bot, GUILD);
                fields(
                        input,
                        "name",
                        "description",
                        "verification_level",
                        "default_message_notifications",
                        "explicit_content_filter",
                        "preferred_locale",
                        "system_channel_id",
                        "rules_channel_id",
                        "public_updates_channel_id");
                if (input.has("name")) name(input, "name", 100);
                if (input.has("description")
                        && !input.path("description").isNull()
                        && input.path("description").asString().length() > 1000) throw ApiError.invalid("description");
                integer(input, "verification_level", 0, 0, 4);
                integer(input, "default_message_notifications", 0, 0, 1);
                integer(input, "explicit_content_filter", 0, 0, 2);
                for (String key : List.of("system_channel_id", "rules_channel_id", "public_updates_channel_id")) {
                    if (input.hasNonNull(key)
                            && !state.channel(required(input, key))
                                    .path("guild_id")
                                    .asString()
                                    .equals(guild.id())) throw ApiError.invalid(key);
                }
                ObjectNode before = guild.data.deepCopy();
                input.properties().forEach(e -> guild.data.set(e.getKey(), e.getValue()));
                state.audit(guild, app, 1, guild.id(), reason, changes(before, guild.data));
                state.emit("GUILD_UPDATE", guild.json());
                return Reply.ok(guild.json());
            }
        }
        if (path.length >= 3) {
            return switch (path[2]) {
                case "members" -> members(method, path, input, query, guild, app, reason);
                case "roles" -> roles(method, path, input, guild, app, reason);
                case "channels" -> channels.guildChannels(method, path, input, guild, app, reason);
                case "bans" -> bans(method, path, input, query, guild, app, reason);
                case "invites" -> {
                    if (!method.equals("GET") || path.length != 3) throw ApiError.unsupported();
                    require(guild, app.bot, GUILD);
                    yield Reply.ok(arr(state.invites.values().stream()
                            .filter(i ->
                                    i.data.path("guild").path("id").asString().equals(guild.id()))
                            .filter(administration::activeInvite)
                            .map(administration::inviteJson)
                            .toList()));
                }
                case "scheduled-events", "auto-moderation", "audit-logs" ->
                    administration.guildRoute(method, path, input, query, guild, app, reason);
                default -> throw ApiError.unsupported();
            };
        }
        throw ApiError.unsupported();
    }

    private Reply members(
            String method, String[] path, JsonNode input, JsonNode query, Guild guild, Application app, String reason) {
        if (path.length == 3 && method.equals("GET")) {
            if (!app.membersIntent) throw new ApiError(403, 50001, "Missing Access");
            int limit = integer(query, "limit", 1, 1, 1000);
            String after = snowflake(str(query, "after", "0"));
            return Reply.ok(arr(guild.members.keySet().stream()
                    .sorted(DiscordState.IDS)
                    .filter(id -> DiscordState.IDS.compare(id, after) > 0)
                    .limit(limit)
                    .map(id -> guild.members.get(id).json(state))
                    .toList()));
        }
        if (path.length < 4) throw ApiError.unsupported();
        String uid = snowflake(path[3]);
        if (path.length == 6 && path[4].equals("roles") && (method.equals("PUT") || method.equals("DELETE"))) {
            Member member = state.member(guild, uid);
            ObjectNode role = state.role(guild, path[5]);
            PermissionEngine.role(guild, app.bot, role);
            if (uid.equals(guild.owner())) throw ApiError.permissions();
            ObjectNode before = member.json(state);
            boolean changed = method.equals("PUT") ? member.roles.add(path[5]) : member.roles.remove(path[5]);
            if (changed) {
                state.memberEvent("GUILD_MEMBER_UPDATE", guild, member);
                state.audit(guild, app, 25, uid, reason, changes(before, member.json(state)));
            }
            return Reply.empty();
        }
        if (path.length != 4) throw ApiError.unsupported();
        if (method.equals("GET")) return Reply.ok(state.member(guild, uid).json(state));
        if (method.equals("PUT")) {
            fields(input, "access_token", "nick", "roles", "mute", "deaf");
            require(guild, app.bot, INVITE);
            OAuth.Grant grant = oauth.access(required(input, "access_token"), "guilds.join");
            if (!grant.app.equals(app.id) || !grant.user.equals(uid))
                throw new ApiError(403, 50025, "Invalid OAuth2 access token");
            state.user(uid);
            if (guild.bans.containsKey(uid)) throw new ApiError(403, 40007, "The user is banned from this guild");
            validateMember(input, guild, app, uid);
            if (guild.members.containsKey(uid)) return Reply.empty();
            Member member = new Member(uid, state.clock.instant().toString(), guild.screening);
            applyMember(input, member);
            if (!member.roles.isEmpty()) member.pending = false;
            guild.members.put(uid, member);
            state.memberEvent("GUILD_MEMBER_ADD", guild, member);
            return Reply.of(201, member.json(state));
        }
        Member member = state.member(guild, uid);
        if (method.equals("PATCH")) {
            fields(input, "nick", "roles", "mute", "deaf", "communication_disabled_until");
            validateMember(input, guild, app, uid);
            ObjectNode before = member.json(state);
            applyMember(input, member);
            state.memberEvent("GUILD_MEMBER_UPDATE", guild, member);
            state.audit(guild, app, input.has("roles") ? 25 : 24, uid, reason, changes(before, member.json(state)));
            return Reply.ok(member.json(state));
        }
        if (method.equals("DELETE")) {
            require(guild, app.bot, KICK);
            target(guild, app.bot, uid);
            state.leave(guild, uid);
            state.audit(guild, app, 20, uid, reason, arr(List.of()));
            return Reply.empty();
        }
        throw ApiError.unsupported();
    }

    private void validateMember(JsonNode input, Guild guild, Application app, String uid) {
        if (input.has("roles")) {
            require(guild, app.bot, ROLES);
            if (uid.equals(guild.owner())) throw ApiError.permissions();
            List<String> ids = strings(input.path("roles"));
            Set<String> changed = new HashSet<>(ids);
            Member old = guild.members.get(uid);
            if (old != null) changed.addAll(old.roles);
            for (String id : changed) {
                if (old == null || old.roles.contains(id) != ids.contains(id))
                    PermissionEngine.role(guild, app.bot, state.role(guild, id));
                else state.role(guild, id);
            }
        }
        if (input.has("nick")) {
            require(guild, app.bot, NICK);
            target(guild, app.bot, uid);
            if (!input.path("nick").isNull()
                    && (!input.path("nick").isString()
                            || input.path("nick").asString().length() > 32)) throw ApiError.invalid("nick");
        }
        if (input.has("mute")) {
            require(guild, app.bot, MUTE);
            bool(input, "mute", false);
        }
        if (input.has("deaf")) {
            require(guild, app.bot, DEAFEN);
            bool(input, "deaf", false);
        }
        if (input.has("communication_disabled_until")) {
            require(guild, app.bot, MODERATE);
            target(guild, app.bot, uid);
            if (has(base(guild, uid), ADMINISTRATOR)) throw ApiError.permissions();
            if (!input.path("communication_disabled_until").isNull()) {
                Instant until = Instant.parse(required(input, "communication_disabled_until"));
                if (until.isAfter(state.clock.instant().plusSeconds(28L * 86400)))
                    throw ApiError.invalid("communication_disabled_until");
            }
        }
    }

    private static void applyMember(JsonNode input, Member member) {
        if (input.has("nick"))
            member.nick =
                    input.path("nick").isNull() ? null : input.path("nick").asString();
        if (input.has("roles")) {
            member.roles.clear();
            member.roles.addAll(strings(input.path("roles")));
        }
        if (input.has("mute")) member.mute = bool(input, "mute", false);
        if (input.has("deaf")) member.deaf = bool(input, "deaf", false);
        if (input.has("communication_disabled_until"))
            member.timeout = input.path("communication_disabled_until").isNull()
                    ? null
                    : input.path("communication_disabled_until").asString();
    }

    private Reply roles(String method, String[] path, JsonNode input, Guild guild, Application app, String reason) {
        if (path.length == 3 && method.equals("GET"))
            return Reply.ok(arr(guild.roles.values().stream()
                    .sorted(Comparator.comparingInt(r -> r.path("position").asInt()))
                    .toList()));
        require(guild, app.bot, ROLES);
        if (path.length == 3 && method.equals("PATCH")) {
            if (!input.isArray() || input.isEmpty()) throw ApiError.invalid("positions");
            Set<String> ids = new HashSet<>();
            for (JsonNode item : input) {
                fields(item, "id", "position");
                String id = required(item, "id");
                if (!ids.add(id)) throw ApiError.invalid("duplicate role");
                PermissionEngine.role(guild, app.bot, state.role(guild, id));
                integer(item, "position", 1, 1, highest(guild, app.bot) - 1);
            }
            List<ObjectNode> ordered = new ArrayList<>(guild.roles.values().stream()
                    .sorted(Comparator.comparingInt(r -> r.path("position").asInt()))
                    .toList());
            for (JsonNode item : input) {
                ObjectNode role = state.role(guild, item.path("id").asString());
                ordered.remove(role);
                ordered.add(Math.min(item.path("position").asInt(), ordered.size()), role);
            }
            for (int i = 0; i < ordered.size(); i++) {
                ObjectNode role = ordered.get(i);
                if (role.path("position").asInt() != i) {
                    role.put("position", i);
                    state.emit("GUILD_ROLE_UPDATE", obj("guild_id", guild.id(), "role", role));
                }
            }
            state.audit(guild, app, 31, guild.id(), reason, arr(List.of()));
            return Reply.ok(arr(ordered));
        }
        if (path.length == 3 && method.equals("POST")) {
            validateRole(input, guild, app);
            if (highest(guild, app.bot) < 1) throw ApiError.permissions();
            ObjectNode role = DiscordState.roleJson(
                    state.id(),
                    str(input, "name", "new role"),
                    1,
                    input.has("permissions")
                            ? bits(input, "permissions")
                            : bits(guild.roles.get(guild.id()), "permissions"),
                    false);
            guild.roles.values().stream()
                    .filter(r -> r.path("position").asInt() >= 1)
                    .forEach(r -> r.put("position", r.path("position").asInt() + 1));
            applyRole(input, role);
            guild.roles.put(role.path("id").asString(), role);
            state.emit("GUILD_ROLE_CREATE", obj("guild_id", guild.id(), "role", role));
            state.audit(guild, app, 30, role.path("id").asString(), reason, changes(obj(), role));
            return Reply.ok(role);
        }
        if (path.length != 4) throw ApiError.unsupported();
        ObjectNode role = state.role(guild, path[3]);
        if (path[3].equals(guild.id())) {
            if (!method.equals("PATCH")) throw ApiError.permissions();
        } else PermissionEngine.role(guild, app.bot, role);
        if (method.equals("PATCH")) {
            validateRole(input, guild, app);
            if (path[3].equals(guild.id()) && input.has("name")) throw ApiError.invalid("name");
            ObjectNode before = role.deepCopy();
            applyRole(input, role);
            state.emit("GUILD_ROLE_UPDATE", obj("guild_id", guild.id(), "role", role));
            state.audit(guild, app, 31, path[3], reason, changes(before, role));
            return Reply.ok(role);
        }
        if (method.equals("DELETE")) {
            guild.roles.remove(path[3]);
            guild.members.values().forEach(m -> {
                if (m.roles.remove(path[3])) state.memberEvent("GUILD_MEMBER_UPDATE", guild, m);
            });
            state.channels.values().stream()
                    .filter(c -> c.path("guild_id").asString().equals(guild.id()))
                    .forEach(c -> c.set(
                            "permission_overwrites",
                            arr(java.util.stream.StreamSupport.stream(
                                            c.path("permission_overwrites").spliterator(), false)
                                    .filter(o -> !o.path("id").asString().equals(path[3]))
                                    .toList())));
            state.emit("GUILD_ROLE_DELETE", obj("guild_id", guild.id(), "role_id", path[3]));
            state.audit(guild, app, 32, path[3], reason, arr(List.of()));
            return Reply.empty();
        }
        throw ApiError.unsupported();
    }

    private static void validateRole(JsonNode input, Guild guild, Application app) {
        fields(input, "name", "permissions", "color", "hoist", "mentionable");
        if (input.has("name")) name(input, "name", 100);
        if (input.has("permissions")) grantable(guild, app.bot, bits(input, "permissions"));
        integer(input, "color", 0, 0, 0xffffff);
        bool(input, "hoist", false);
        bool(input, "mentionable", false);
    }

    private static void applyRole(JsonNode input, ObjectNode role) {
        input.properties().forEach(e -> role.set(e.getKey(), e.getValue()));
        role.put("permissions", bits(role, "permissions").toString());
        if (input.has("color"))
            role.set(
                    "colors",
                    obj("primary_color", input.path("color"), "secondary_color", null, "tertiary_color", null));
    }

    private Reply bans(
            String method, String[] path, JsonNode input, JsonNode query, Guild guild, Application app, String reason) {
        if (method.equals("GET")) {
            if (!has(base(guild, app.bot), BAN) && !has(base(guild, app.bot), AUDIT)) throw ApiError.permissions();
            if (path.length == 3) {
                int limit = integer(query, "limit", 1000, 1, 1000);
                String before = snowflake(str(query, "before", "18446744073709551615"));
                String after = snowflake(str(query, "after", "0"));
                return Reply.ok(arr(guild.bans.keySet().stream()
                        .sorted(DiscordState.IDS)
                        .filter(id -> query.has("before")
                                ? DiscordState.IDS.compare(id, before) < 0
                                : DiscordState.IDS.compare(id, after) > 0)
                        .limit(limit)
                        .map(guild.bans::get)
                        .toList()));
            }
            if (path.length == 4) {
                ObjectNode ban = guild.bans.get(path[3]);
                if (ban == null) throw new ApiError(404, 10026, "Unknown Ban");
                return Reply.ok(ban);
            }
        }
        if (path.length != 4) throw ApiError.unsupported();
        require(guild, app.bot, BAN);
        String uid = path[3];
        state.user(uid);
        if (method.equals("PUT")) {
            fields(input, "delete_message_seconds", "delete_message_days");
            int seconds = integer(
                    input, "delete_message_seconds", integer(input, "delete_message_days", 0, 0, 7) * 86400, 0, 604800);
            target(guild, app.bot, uid);
            if (guild.members.containsKey(uid)) state.leave(guild, uid);
            guild.bans.put(uid, obj("reason", reason.isEmpty() ? null : reason, "user", state.publicUser(uid)));
            if (seconds > 0)
                state.messages.forEach((cid, messages) -> {
                    if (state.channel(cid).path("guild_id").asString().equals(guild.id()))
                        messages.removeIf(
                                m -> m.path("author").path("id").asString().equals(uid)
                                        && Instant.parse(m.path("timestamp").asString())
                                                .isAfter(state.clock.instant().minusSeconds(seconds)));
                });
            state.emit("GUILD_BAN_ADD", obj("guild_id", guild.id(), "user", state.publicUser(uid)));
            state.audit(guild, app, 22, uid, reason, arr(List.of()));
            return Reply.empty();
        }
        if (method.equals("DELETE")) {
            if (guild.bans.remove(uid) == null) throw new ApiError(404, 10026, "Unknown Ban");
            state.emit("GUILD_BAN_REMOVE", obj("guild_id", guild.id(), "user", state.publicUser(uid)));
            state.audit(guild, app, 23, uid, reason, arr(List.of()));
            return Reply.empty();
        }
        throw ApiError.unsupported();
    }

    static JsonNode changes(JsonNode before, JsonNode after) {
        List<JsonNode> result = new ArrayList<>();
        after.properties().forEach(entry -> {
            if (!entry.getValue().equals(before.path(entry.getKey())))
                result.add(obj(
                        "key",
                        entry.getKey(),
                        "old_value",
                        before.path(entry.getKey()),
                        "new_value",
                        entry.getValue()));
        });
        return arr(result);
    }
}
