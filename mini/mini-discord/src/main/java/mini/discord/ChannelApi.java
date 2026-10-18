package mini.discord;

import static mini.discord.Json.*;
import static mini.discord.PermissionEngine.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import mini.discord.DiscordState.Application;
import mini.discord.DiscordState.Guild;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

final class ChannelApi {
    private final DiscordState state;
    private final Administration administration;
    private final Messages messages;

    ChannelApi(DiscordState state, Administration administration) {
        this.state = state;
        this.administration = administration;
        messages = new Messages(state);
    }

    Reply guildChannels(String method, String[] path, JsonNode input, Guild guild, Application app, String reason) {
        if (path.length != 3) throw ApiError.unsupported();
        if (method.equals("GET"))
            return Reply.ok(arr(state.channels.values().stream()
                    .filter(c -> c.path("guild_id").asString().equals(guild.id()) && !isThread(c))
                    .map(ChannelApi::publicJson)
                    .toList()));
        require(guild, app.bot, CHANNELS);
        if (method.equals("POST")) {
            ObjectNode channel = prepareChannel(input, guild, app, null);
            channel.put("id", state.id());
            state.channels.put(channel.path("id").asString(), channel);
            if (channel.hasNonNull("parent_id") && !input.has("permission_overwrites"))
                state.syncedChannels.add(channel.path("id").asString());
            state.emit("CHANNEL_CREATE", publicJson(channel));
            state.audit(guild, app, 10, channel.path("id").asString(), reason, GuildApi.changes(obj(), channel));
            return Reply.of(201, publicJson(channel));
        }
        if (method.equals("PATCH")) {
            if (!input.isArray()) throw ApiError.invalid("positions");
            List<ObjectNode> updates = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (JsonNode item : input) {
                fields(item, "id", "position", "parent_id", "lock_permissions");
                String id = required(item, "id");
                ObjectNode original = state.channel(id);
                if (!ids.add(id) || !original.path("guild_id").asString().equals(guild.id()) || isThread(original))
                    throw ApiError.invalid("id");
                integer(item, "position", 0, 0, 499);
                validateParent(item, guild, original.path("type").asInt());
                ObjectNode next = original.deepCopy();
                if (item.has("position"))
                    next.put("position", item.path("position").asInt());
                if (item.has("parent_id")) next.set("parent_id", item.path("parent_id"));
                if (bool(item, "lock_permissions", false)) {
                    if (!next.hasNonNull("parent_id")) throw ApiError.invalid("parent_id");
                    requireChannel(state, next, app.bot, ROLES);
                    next.set(
                            "permission_overwrites",
                            state.channel(next.path("parent_id").asString())
                                    .path("permission_overwrites")
                                    .deepCopy());
                }
                updates.add(next);
            }
            for (int i = 0; i < updates.size(); i++) {
                ObjectNode next = updates.get(i);
                String id = next.path("id").asString();
                state.channels.put(id, next);
                if (input.get(i).path("lock_permissions").asBoolean()) state.syncedChannels.add(id);
                else if (input.get(i).has("parent_id")) state.syncedChannels.remove(id);
                state.emit("CHANNEL_UPDATE", publicJson(next));
                state.audit(guild, app, 11, id, reason, arr(List.of()));
            }
            return Reply.empty();
        }
        throw ApiError.unsupported();
    }

    Reply route(String method, String[] path, JsonNode input, JsonNode query, String auth, String reason) {
        state.bot(auth);
        ObjectNode channel = state.channel(path[1]);
        Guild guild = state.guild(channel.path("guild_id").asString());
        Application app = state.installed(auth, guild);
        requireChannel(state, channel, app.bot, VIEW);
        if (path.length >= 3) {
            if (path[2].equals("permissions") && path.length == 4)
                return overwrite(method, path[3], input, channel, guild, app, reason);
            if (path[2].equals("invites") && path.length == 3)
                return administration.channelInvites(method, input, channel, guild, app, reason);
            if (Set.of("messages", "threads", "thread-members").contains(path[2]))
                return messages.route(method, path, input, query, channel, guild, app, reason);
            throw ApiError.unsupported();
        }
        if (method.equals("GET")) return Reply.ok(publicJson(channel));
        if (isThread(channel)) return messages.thread(method, input, channel, guild, app, reason);
        requireChannel(state, channel, app.bot, CHANNELS);
        String id = channel.path("id").asString();
        if (method.equals("PATCH")) {
            ObjectNode next = prepareChannel(input, guild, app, channel);
            state.channels.put(id, next);
            if (input.has("permission_overwrites")) {
                state.syncedChannels.remove(id);
                propagate(next);
            }
            if (input.has("parent_id")) state.syncedChannels.remove(id);
            state.emit("CHANNEL_UPDATE", publicJson(next));
            state.audit(guild, app, 11, id, reason, GuildApi.changes(channel, next));
            return Reply.ok(publicJson(next));
        }
        if (method.equals("DELETE")) {
            removeChannel(channel);
            state.audit(guild, app, 12, id, reason, arr(List.of()));
            return Reply.ok(publicJson(channel));
        }
        throw ApiError.unsupported();
    }

    void removeChannel(ObjectNode channel) {
        String id = channel.path("id").asString();
        for (ObjectNode child : new ArrayList<>(state.channels.values())) {
            if (child.path("parent_id").asString().equals(id)) {
                if (isThread(child)) removeChannel(child);
                else {
                    child.putNull("parent_id");
                    state.syncedChannels.remove(child.path("id").asString());
                    state.emit("CHANNEL_UPDATE", publicJson(child));
                }
            }
        }
        state.channels.remove(id);
        state.messages.remove(id);
        state.syncedChannels.remove(id);
        state.invites.values().stream()
                .filter(i -> i.data.path("channel").path("id").asString().equals(id))
                .forEach(i -> i.revoked = true);
        state.emit(isThread(channel) ? "THREAD_DELETE" : "CHANNEL_DELETE", publicJson(channel));
    }

    ObjectNode prepareChannel(JsonNode input, Guild guild, Application app, ObjectNode old) {
        fields(
                input,
                "name",
                "type",
                "topic",
                "position",
                "parent_id",
                "permission_overwrites",
                "nsfw",
                "rate_limit_per_user",
                "bitrate",
                "user_limit",
                "available_tags",
                "default_auto_archive_duration");
        int type = integer(input, "type", old == null ? 0 : old.path("type").asInt(), 0, 15);
        if (!Set.of(0, 2, 4, 5, 13, 15).contains(type)) throw ApiError.invalid("type");
        if (old != null
                && type != old.path("type").asInt()
                && !Set.of(0, 5).containsAll(List.of(type, old.path("type").asInt()))) throw ApiError.invalid("type");
        if ((type == 5 || type == 13 || type == 15)
                && !strings(guild.data.path("features")).contains("COMMUNITY"))
            throw ApiError.invalid("community required");
        if (old == null || input.has("name")) name(input, "name", 100);
        integer(input, "position", 0, 0, 499);
        integer(input, "rate_limit_per_user", 0, 0, 21600);
        integer(input, "bitrate", 64000, 8000, 96000);
        integer(input, "user_limit", 0, 0, 99);
        bool(input, "nsfw", false);
        if (input.has("topic")
                && !input.path("topic").isNull()
                && (!input.path("topic").isString()
                        || input.path("topic").asString().length() > (type == 15 ? 4096 : 1024)))
            throw ApiError.invalid("topic");
        validateParent(input, guild, type);
        if (input.has("permission_overwrites")) {
            require(guild, app.bot, ROLES);
            validateOverwrites(input.path("permission_overwrites"), guild, app);
        }
        if (input.has("available_tags")) {
            if (type != 15
                    || !input.path("available_tags").isArray()
                    || input.path("available_tags").size() > 20) throw ApiError.invalid("available_tags");
            for (JsonNode tag : input.path("available_tags")) {
                fields(tag, "id", "name", "moderated", "emoji_id", "emoji_name");
                name(tag, "name", 20);
                bool(tag, "moderated", false);
            }
        }
        ObjectNode channel = old == null
                ? obj(
                        "guild_id",
                        guild.id(),
                        "type",
                        type,
                        "position",
                        (int) state.channels.values().stream()
                                .filter(c -> c.path("guild_id").asString().equals(guild.id()))
                                .count(),
                        "name",
                        input.path("name"),
                        "parent_id",
                        null,
                        "permission_overwrites",
                        List.of(),
                        "nsfw",
                        false,
                        "topic",
                        null,
                        "rate_limit_per_user",
                        0)
                : old.deepCopy();
        input.properties().forEach(e -> channel.set(e.getKey(), e.getValue().deepCopy()));
        if (old == null && channel.hasNonNull("parent_id") && !input.has("permission_overwrites"))
            channel.set(
                    "permission_overwrites",
                    state.channel(channel.path("parent_id").asString())
                            .path("permission_overwrites")
                            .deepCopy());
        if (type == 2 || type == 13) {
            if (!channel.has("bitrate")) channel.put("bitrate", 64000);
            if (!channel.has("user_limit")) channel.put("user_limit", 0);
            channel.putNull("rtc_region");
        }
        if (type == 15) {
            if (!channel.has("available_tags")) channel.set("available_tags", arr(List.of()));
            for (JsonNode tag : channel.path("available_tags"))
                if (!tag.has("id")) ((ObjectNode) tag).put("id", state.id());
        }
        return channel;
    }

    private void validateParent(JsonNode input, Guild guild, int type) {
        if (input.hasNonNull("parent_id")) {
            ObjectNode parent = state.channel(required(input, "parent_id"));
            if (type == 4
                    || parent.path("type").asInt() != 4
                    || !parent.path("guild_id").asString().equals(guild.id())) throw ApiError.invalid("parent_id");
        }
    }

    private void validateOverwrites(JsonNode overwrites, Guild guild, Application app) {
        if (!overwrites.isArray() || overwrites.size() > 100) throw ApiError.invalid("permission_overwrites");
        Set<String> ids = new HashSet<>();
        for (JsonNode overwrite : overwrites) {
            fields(overwrite, "id", "type", "allow", "deny");
            String id = required(overwrite, "id");
            if (!ids.add(id)) throw ApiError.invalid("duplicate overwrite");
            int type = integer(overwrite, "type", -1, 0, 1);
            if (type == -1) throw ApiError.invalid("type");
            if (type == 0) state.role(guild, id);
            else state.member(guild, id);
            java.math.BigInteger requested = bits(overwrite, "allow").or(bits(overwrite, "deny"));
            grantable(guild, app.bot, requested);
            if (has(requested, ROLES) && !has(base(guild, app.bot), ADMINISTRATOR)) throw ApiError.permissions();
        }
    }

    private Reply overwrite(
            String method, String id, JsonNode input, ObjectNode channel, Guild guild, Application app, String reason) {
        requireChannel(state, channel, app.bot, ROLES);
        List<JsonNode> overwrites = new ArrayList<>();
        channel.path("permission_overwrites").forEach(o -> {
            if (!o.path("id").asString().equals(id)) overwrites.add(o);
        });
        if (method.equals("PUT")) {
            fields(input, "type", "allow", "deny");
            ObjectNode overwrite = obj(
                    "id",
                    id,
                    "type",
                    input.path("type"),
                    "allow",
                    bits(input, "allow").toString(),
                    "deny",
                    bits(input, "deny").toString());
            validateOverwrites(arr(List.of(overwrite)), guild, app);
            overwrites.add(overwrite);
        } else if (!method.equals("DELETE")) throw ApiError.unsupported();
        channel.set("permission_overwrites", arr(overwrites));
        state.syncedChannels.remove(channel.path("id").asString());
        propagate(channel);
        state.emit("CHANNEL_UPDATE", publicJson(channel));
        state.audit(
                guild, app, method.equals("PUT") ? 13 : 15, channel.path("id").asString(), reason, arr(List.of()));
        return Reply.empty();
    }

    private void propagate(ObjectNode parent) {
        state.channels.values().stream()
                .filter(c ->
                        c.path("parent_id").asString().equals(parent.path("id").asString())
                                && state.syncedChannels.contains(c.path("id").asString()))
                .forEach(c -> {
                    c.set(
                            "permission_overwrites",
                            parent.path("permission_overwrites").deepCopy());
                    state.emit("CHANNEL_UPDATE", publicJson(c));
                });
    }

    static boolean isThread(JsonNode channel) {
        int type = channel.path("type").asInt();
        return type >= 10 && type <= 12;
    }

    static ObjectNode publicJson(ObjectNode channel) {
        ObjectNode out = channel.deepCopy();
        out.remove("member_ids");
        return out;
    }
}
