package mini.discord;

import static mini.discord.Json.*;
import static mini.discord.PermissionEngine.*;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import mini.discord.DiscordState.Application;
import mini.discord.DiscordState.Guild;
import mini.discord.DiscordState.Invite;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

final class Administration {
    private final DiscordState state;

    Administration(DiscordState state) {
        this.state = state;
    }

    boolean activeInvite(Invite invite) {
        return !invite.revoked
                && (invite.expires == 0 || state.clock.instant().getEpochSecond() < invite.expires)
                && (invite.data.path("max_uses").asInt() == 0
                        || invite.uses < invite.data.path("max_uses").asInt());
    }

    ObjectNode inviteJson(Invite invite) {
        ObjectNode out = invite.data.deepCopy();
        out.put("uses", invite.uses);
        return out;
    }

    Invite invite(String code) {
        Invite invite = state.invites.get(code);
        if (invite == null || !activeInvite(invite)) throw new ApiError(404, 10006, "Unknown Invite");
        return invite;
    }

    Reply inviteRoute(String method, String[] path, String authorization, String reason) {
        if (path.length != 2) throw ApiError.unsupported();
        Invite invite = invite(path[1]);
        if (method.equals("GET")) return Reply.ok(inviteJson(invite));
        if (method.equals("DELETE")) {
            Guild guild = state.guild(invite.data.path("guild").path("id").asString());
            Application app = state.installed(authorization, guild);
            JsonNode channel =
                    state.channel(invite.data.path("channel").path("id").asString());
            if (!has(base(guild, app.bot), GUILD) && !has(PermissionEngine.channel(state, channel, app.bot), CHANNELS))
                throw ApiError.permissions();
            invite.revoked = true;
            state.audit(guild, app, 42, path[1], reason, arr(List.of()));
            return Reply.ok(inviteJson(invite));
        }
        throw ApiError.unsupported();
    }

    Reply channelInvites(
            String method, JsonNode input, ObjectNode channel, Guild guild, Application app, String reason) {
        if (method.equals("GET")) {
            requireChannel(state, channel, app.bot, CHANNELS);
            return Reply.ok(arr(state.invites.values().stream()
                    .filter(this::activeInvite)
                    .filter(i -> i.data.path("channel")
                            .path("id")
                            .asString()
                            .equals(channel.path("id").asString()))
                    .map(this::inviteJson)
                    .toList()));
        }
        if (!method.equals("POST")) throw ApiError.unsupported();
        requireChannel(state, channel, app.bot, INVITE);
        if (channel.path("type").asInt() == 4 || ChannelApi.isThread(channel)) throw ApiError.invalid("channel type");
        fields(input, "max_age", "max_uses", "temporary", "unique");
        int age = integer(input, "max_age", 86400, 0, 604800), uses = integer(input, "max_uses", 0, 0, 100);
        boolean temporary = bool(input, "temporary", false), unique = bool(input, "unique", false);
        if (!unique) {
            Invite matching = state.invites.values().stream()
                    .filter(this::activeInvite)
                    .filter(i -> i.data.path("channel").path("id").equals(channel.path("id"))
                            && i.data.path("max_age").asInt() == age
                            && i.data.path("max_uses").asInt() == uses
                            && i.data.path("temporary").asBoolean() == temporary)
                    .findFirst()
                    .orElse(null);
            if (matching != null) return Reply.ok(inviteJson(matching));
        }
        String code = "mini" + state.id();
        long expires = age == 0 ? 0 : state.clock.instant().getEpochSecond() + age;
        Invite invite = new Invite(
                obj(
                        "type",
                        0,
                        "code",
                        code,
                        "guild",
                        obj("id", guild.id(), "name", guild.data.path("name"), "icon", null),
                        "channel",
                        obj("id", channel.path("id"), "name", channel.path("name"), "type", channel.path("type")),
                        "inviter",
                        state.publicUser(app.bot),
                        "max_age",
                        age,
                        "max_uses",
                        uses,
                        "temporary",
                        temporary,
                        "created_at",
                        state.clock.instant().toString(),
                        "expires_at",
                        expires == 0 ? null : Instant.ofEpochSecond(expires).toString()),
                expires);
        state.invites.put(code, invite);
        state.audit(guild, app, 40, code, reason, arr(List.of()));
        return Reply.ok(inviteJson(invite));
    }

    ObjectNode accept(String code, String user) {
        Invite invite = invite(code);
        Guild guild = state.guild(invite.data.path("guild").path("id").asString());
        boolean existing = guild.members.containsKey(user);
        DiscordState.Member member = state.join(guild, user);
        if (!existing) {
            invite.uses++;
            member.temporary = invite.data.path("temporary").asBoolean();
        }
        return member.json(state);
    }

    Reply guildRoute(
            String method, String[] path, JsonNode input, JsonNode query, Guild guild, Application app, String reason) {
        return switch (path[2]) {
            case "audit-logs" -> audit(method, path, query, guild, app);
            case "scheduled-events" -> scheduled(method, path, input, query, guild, app, reason);
            case "auto-moderation" -> automod(method, path, input, guild, app, reason);
            default -> throw ApiError.unsupported();
        };
    }

    private Reply audit(String method, String[] path, JsonNode query, Guild guild, Application app) {
        if (!method.equals("GET") || path.length != 3) throw ApiError.unsupported();
        require(guild, app.bot, AUDIT);
        int limit = integer(query, "limit", 50, 1, 100), type = integer(query, "action_type", -1, 1, 999);
        String before = snowflake(str(query, "before", "18446744073709551615")),
                after = snowflake(str(query, "after", "0"));
        List<ObjectNode> entries = guild.audit.stream()
                .filter(e -> type < 0 || e.path("action_type").asInt() == type)
                .filter(e -> !query.has("user_id")
                        || e.path("user_id")
                                .asString()
                                .equals(query.path("user_id").asString()))
                .filter(e -> DiscordState.IDS.compare(e.path("id").asString(), before) < 0
                        && DiscordState.IDS.compare(e.path("id").asString(), after) > 0)
                .sorted((a, b) -> query.has("after")
                        ? DiscordState.IDS.compare(
                                a.path("id").asString(), b.path("id").asString())
                        : DiscordState.IDS.compare(
                                b.path("id").asString(), a.path("id").asString()))
                .limit(limit)
                .toList();
        return Reply.ok(obj(
                "audit_log_entries",
                entries,
                "users",
                entries.stream()
                        .map(e -> state.publicUser(e.path("user_id").asString()))
                        .distinct()
                        .toList(),
                "integrations",
                List.of(),
                "webhooks",
                List.of(),
                "guild_scheduled_events",
                guild.scheduled.values(),
                "auto_moderation_rules",
                guild.automod.values(),
                "threads",
                List.of()));
    }

    private boolean visible(Application app, JsonNode event) {
        return !event.hasNonNull("channel_id")
                || has(
                        PermissionEngine.channel(
                                state, state.channel(event.path("channel_id").asString()), app.bot),
                        VIEW);
    }

    private Reply scheduled(
            String method, String[] path, JsonNode input, JsonNode query, Guild guild, Application app, String reason) {
        if (path.length == 3 && method.equals("GET"))
            return Reply.ok(arr(guild.scheduled.values().stream()
                    .filter(e -> visible(app, e))
                    .map(e -> eventJson(guild, e, query))
                    .toList()));
        if (path.length == 3 && method.equals("POST")) {
            if (guild.scheduled.values().stream()
                            .filter(e -> e.path("status").asInt() < 3)
                            .count()
                    >= 100) throw ApiError.invalid("maximum scheduled events");
            ObjectNode event = validateEvent(input, null, guild, app);
            event.put("id", state.id());
            event.put("guild_id", guild.id());
            event.put("creator_id", app.bot);
            event.set("creator", state.publicUser(app.bot));
            event.putNull("entity_id");
            event.putNull("image");
            guild.scheduled.put(event.path("id").asString(), event);
            state.emit("GUILD_SCHEDULED_EVENT_CREATE", event);
            state.audit(guild, app, 100, event.path("id").asString(), reason, GuildApi.changes(obj(), event));
            return Reply.ok(event);
        }
        if (path.length < 4) throw ApiError.unsupported();
        ObjectNode old = guild.scheduled.get(path[3]);
        if (old == null) throw new ApiError(404, 10070, "Unknown Guild Scheduled Event");
        if (!visible(app, old)) throw ApiError.permissions();
        if (path.length == 5 && path[4].equals("users") && method.equals("GET")) {
            int limit = integer(query, "limit", 100, 1, 100);
            String before = snowflake(str(query, "before", "18446744073709551615")),
                    after = snowflake(str(query, "after", "0"));
            return Reply.ok(arr(guild.interested.getOrDefault(path[3], Set.of()).stream()
                    .sorted(DiscordState.IDS)
                    .filter(id -> DiscordState.IDS.compare(id, before) < 0 && DiscordState.IDS.compare(id, after) > 0)
                    .limit(limit)
                    .map(id -> {
                        ObjectNode out = obj("guild_scheduled_event_id", path[3], "user", state.publicUser(id));
                        if (str(query, "with_member", "false").equals("true") && guild.members.containsKey(id))
                            out.set("member", guild.members.get(id).json(state));
                        return out;
                    })
                    .toList()));
        }
        if (path.length != 4) throw ApiError.unsupported();
        if (method.equals("GET")) return Reply.ok(eventJson(guild, old, query));
        eventPermissions(old, guild, app, false);
        if (method.equals("PATCH")) {
            ObjectNode next = validateEvent(input, old, guild, app);
            guild.scheduled.put(path[3], next);
            state.emit("GUILD_SCHEDULED_EVENT_UPDATE", next);
            state.audit(guild, app, 101, path[3], reason, GuildApi.changes(old, next));
            return Reply.ok(next);
        }
        if (method.equals("DELETE")) {
            guild.scheduled.remove(path[3]);
            guild.interested.remove(path[3]);
            state.emit("GUILD_SCHEDULED_EVENT_DELETE", old);
            state.audit(guild, app, 102, path[3], reason, arr(List.of()));
            return Reply.empty();
        }
        throw ApiError.unsupported();
    }

    private static ObjectNode eventJson(Guild guild, ObjectNode event, JsonNode query) {
        ObjectNode out = event.deepCopy();
        if (str(query, "with_user_count", "false").equals("true"))
            out.put(
                    "user_count",
                    guild.interested
                            .getOrDefault(event.path("id").asString(), Set.of())
                            .size());
        return out;
    }

    private ObjectNode validateEvent(JsonNode input, ObjectNode old, Guild guild, Application app) {
        fields(
                input,
                "name",
                "description",
                "channel_id",
                "entity_metadata",
                "privacy_level",
                "scheduled_start_time",
                "scheduled_end_time",
                "entity_type",
                "status");
        ObjectNode next = old == null
                ? obj(
                        "status",
                        1,
                        "description",
                        null,
                        "channel_id",
                        null,
                        "entity_metadata",
                        null,
                        "scheduled_end_time",
                        null)
                : old.deepCopy();
        input.properties().forEach(e -> next.set(e.getKey(), e.getValue()));
        name(next, "name", 100);
        if (!next.has("privacy_level") || integer(next, "privacy_level", 2, 2, 2) != 2)
            throw ApiError.invalid("privacy_level");
        int type = integer(next, "entity_type", -1, 1, 3);
        if (type == -1) throw ApiError.invalid("entity_type");
        Instant start = Instant.parse(required(next, "scheduled_start_time"));
        if ((old == null || input.has("scheduled_start_time")) && !start.isAfter(state.clock.instant()))
            throw ApiError.invalid("scheduled_start_time");
        if (next.hasNonNull("scheduled_end_time")
                && !Instant.parse(required(next, "scheduled_end_time")).isAfter(start))
            throw ApiError.invalid("scheduled_end_time");
        if (type == 3) {
            if (next.hasNonNull("channel_id") || !next.hasNonNull("scheduled_end_time"))
                throw ApiError.invalid("external event");
            name(next.path("entity_metadata"), "location", 100);
        } else {
            ObjectNode channel = state.channel(required(next, "channel_id"));
            if (!channel.path("guild_id").asString().equals(guild.id())
                    || channel.path("type").asInt() != (type == 1 ? 13 : 2)) throw ApiError.invalid("channel_id");
            if (next.hasNonNull("entity_metadata")) throw ApiError.invalid("entity_metadata");
        }
        int status = integer(next, "status", 1, 1, 4);
        if (old == null && status != 1) throw ApiError.invalid("status");
        if (old != null) {
            int previous = old.path("status").asInt();
            if (previous >= 3
                    || (status != previous
                            && !(previous == 1 && (status == 2 || status == 4))
                            && !(previous == 2 && status == 3))) throw ApiError.invalid("status transition");
            if (previous == 2
                    && input.has("entity_type")
                    && old.path("entity_type").asInt() != type) throw ApiError.invalid("entity_type");
        }
        if (next.hasNonNull("description")
                && next.path("description").asString().length() > 1000) throw ApiError.invalid("description");
        eventPermissions(next, guild, app, old == null);
        return next;
    }

    private void eventPermissions(JsonNode event, Guild guild, Application app, boolean creating) {
        int type = event.path("entity_type").asInt();
        java.math.BigInteger permissions = type == 3
                ? base(guild, app.bot)
                : PermissionEngine.channel(
                        state, state.channel(event.path("channel_id").asString()), app.bot);
        boolean own = event.path("creator_id").asString().equals(app.bot);
        if (creating
                ? !has(permissions, CREATE_EVENTS)
                : !has(permissions, EVENTS) && !(own && has(permissions, CREATE_EVENTS))) throw ApiError.permissions();
        if (type == 1 && !has(permissions, CHANNELS.or(MUTE).or(MOVE))) throw ApiError.permissions();
        if (type == 2 && !has(permissions, VIEW.or(CONNECT))) throw ApiError.permissions();
    }

    private Reply automod(String method, String[] path, JsonNode input, Guild guild, Application app, String reason) {
        if (path.length < 4 || !path[3].equals("rules")) throw ApiError.unsupported();
        require(guild, app.bot, GUILD);
        if (path.length == 4 && method.equals("GET")) return Reply.ok(arr(guild.automod.values()));
        ObjectNode old = path.length == 5 ? guild.automod.get(path[4]) : null;
        if (path.length == 5 && old == null) throw ApiError.unsupported();
        if (path.length == 5 && method.equals("GET")) return Reply.ok(old);
        if (path.length == 5 && method.equals("DELETE")) {
            guild.automod.remove(path[4]);
            state.emit("AUTO_MODERATION_RULE_DELETE", old);
            state.audit(guild, app, 142, path[4], reason, arr(List.of()));
            return Reply.empty();
        }
        if (!(path.length == 4 && method.equals("POST")) && !(path.length == 5 && method.equals("PATCH")))
            throw ApiError.unsupported();
        fields(
                input,
                "name",
                "event_type",
                "trigger_type",
                "trigger_metadata",
                "actions",
                "enabled",
                "exempt_roles",
                "exempt_channels");
        ObjectNode next = old == null
                ? obj(
                        "enabled",
                        false,
                        "exempt_roles",
                        List.of(),
                        "exempt_channels",
                        List.of(),
                        "trigger_metadata",
                        obj())
                : old.deepCopy();
        input.properties().forEach(e -> next.set(e.getKey(), e.getValue()));
        name(next, "name", 100);
        int eventType = integer(next, "event_type", -1, 1, 2), trigger = integer(next, "trigger_type", -1, 1, 6);
        if (!Set.of(1, 3, 4, 5, 6).contains(trigger) || eventType != (trigger == 6 ? 2 : 1))
            throw ApiError.invalid("trigger_type/event_type");
        if (old != null && trigger != old.path("trigger_type").asInt())
            throw ApiError.invalid("trigger_type immutable");
        if (old == null
                && guild.automod.values().stream()
                                .filter(rule -> rule.path("trigger_type").asInt() == trigger)
                                .count()
                        >= (trigger == 1 ? 6 : 1)) throw ApiError.invalid("maximum rules for trigger type");
        validateTrigger(next.path("trigger_metadata"), trigger);
        bool(next, "enabled", false);
        if (!next.path("actions").isArray()
                || next.path("actions").isEmpty()
                || next.path("actions").size() > 3) throw ApiError.invalid("actions");
        Set<Integer> actions = new HashSet<>();
        for (JsonNode action : next.path("actions")) {
            fields(action, "type", "metadata");
            int type = integer(action, "type", -1, 1, 4);
            if (type == -1 || !actions.add(type)) throw ApiError.invalid("actions");
            if (type == 2
                    && !state.channel(required(action.path("metadata"), "channel_id"))
                            .path("guild_id")
                            .asString()
                            .equals(guild.id())) throw ApiError.invalid("channel_id");
            if (type == 3) {
                require(guild, app.bot, MODERATE);
                if (!action.path("metadata").has("duration_seconds")) throw ApiError.invalid("duration_seconds");
                integer(action.path("metadata"), "duration_seconds", -1, 1, 2419200);
            }
        }
        for (String id : strings(next.path("exempt_roles"))) state.role(guild, id);
        for (String id : strings(next.path("exempt_channels")))
            if (!state.channel(id).path("guild_id").asString().equals(guild.id()))
                throw ApiError.invalid("exempt_channels");
        if (next.path("exempt_roles").size() > 20
                || next.path("exempt_channels").size() > 50) throw ApiError.invalid("exemptions");
        if (old == null) {
            next.put("id", state.id());
            next.put("guild_id", guild.id());
            next.put("creator_id", app.bot);
        }
        guild.automod.put(next.path("id").asString(), next);
        state.emit(old == null ? "AUTO_MODERATION_RULE_CREATE" : "AUTO_MODERATION_RULE_UPDATE", next);
        state.audit(
                guild,
                app,
                old == null ? 140 : 141,
                next.path("id").asString(),
                reason,
                GuildApi.changes(old == null ? obj() : old, next));
        return Reply.ok(next);
    }

    private static void validateTrigger(JsonNode metadata, int trigger) {
        fields(
                metadata,
                "keyword_filter",
                "regex_patterns",
                "presets",
                "allow_list",
                "mention_total_limit",
                "mention_raid_protection_enabled");
        for (String field : List.of("keyword_filter", "regex_patterns", "allow_list")) {
            if (!metadata.has(field)) continue;
            if (!Set.of(1, 4, 6).contains(trigger) || (trigger == 4 && !field.equals("allow_list")))
                throw ApiError.invalid(field);
            List<String> values = strings(metadata.path(field));
            int max = field.equals("regex_patterns") ? 10 : field.equals("keyword_filter") || trigger == 4 ? 1000 : 100;
            int length = field.equals("regex_patterns") ? 260 : 60;
            if (values.size() > max || values.stream().anyMatch(value -> value.isBlank() || value.length() > length))
                throw ApiError.invalid(field);
        }
        if (metadata.has("presets")) {
            if (trigger != 4
                    || !metadata.path("presets").isArray()
                    || metadata.path("presets").isEmpty()) throw ApiError.invalid("presets");
            for (JsonNode preset : metadata.path("presets"))
                if (!Set.of(1, 2, 3).contains(preset.asInt())) throw ApiError.invalid("presets");
        }
        if (trigger == 4 && !metadata.has("presets")) throw ApiError.invalid("presets");
        if (metadata.has("mention_total_limit") || metadata.has("mention_raid_protection_enabled")) {
            if (trigger != 5) throw ApiError.invalid("mention metadata");
            integer(metadata, "mention_total_limit", 0, 0, 50);
            bool(metadata, "mention_raid_protection_enabled", false);
        }
    }
}
