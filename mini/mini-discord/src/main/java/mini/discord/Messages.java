package mini.discord;

import static mini.discord.Json.*;
import static mini.discord.PermissionEngine.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import mini.discord.DiscordState.Application;
import mini.discord.DiscordState.Guild;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

final class Messages {
    private final DiscordState state;

    Messages(DiscordState state) {
        this.state = state;
    }

    Reply route(
            String method,
            String[] path,
            JsonNode input,
            JsonNode query,
            ObjectNode channel,
            Guild guild,
            Application app,
            String reason) {
        if (path[2].equals("threads") && path.length == 3 && method.equals("POST"))
            return createThread(input, channel, guild, app, null);
        if (path[2].equals("messages") && path.length == 5 && path[4].equals("threads") && method.equals("POST")) {
            ObjectNode message = message(channel.path("id").asString(), path[3]);
            return createThread(input, channel, guild, app, message);
        }
        if (path[2].equals("thread-members") && ChannelApi.isThread(channel))
            return threadMembers(method, path, channel, guild, app);
        if (!path[2].equals("messages")) throw ApiError.unsupported();
        int type = channel.path("type").asInt();
        if (!Set.of(0, 2, 5, 10, 11, 12, 13).contains(type)) throw ApiError.invalid("channel type");
        String cid = channel.path("id").asString();
        if (path.length == 3 && method.equals("GET")) {
            requireChannel(state, channel, app.bot, HISTORY);
            int limit = integer(query, "limit", 50, 1, 100);
            int cursors = (query.has("before") ? 1 : 0) + (query.has("after") ? 1 : 0) + (query.has("around") ? 1 : 0);
            if (cursors > 1 || query.has("around")) throw ApiError.invalid("pagination: use before or after");
            String before = snowflake(str(query, "before", "18446744073709551615")),
                    after = snowflake(str(query, "after", "0"));
            return Reply.ok(arr(state.messages.getOrDefault(cid, List.of()).stream()
                    .filter(m -> DiscordState.IDS.compare(m.path("id").asString(), before) < 0
                            && DiscordState.IDS.compare(m.path("id").asString(), after) > 0)
                    .sorted((a, b) -> DiscordState.IDS.compare(
                            b.path("id").asString(), a.path("id").asString()))
                    .limit(limit)
                    .toList()));
        }
        if (path.length == 3 && method.equals("POST")) {
            requireChannel(state, channel, app.bot, ChannelApi.isThread(channel) ? THREAD_SEND : SEND);
            if (channel.path("thread_metadata").path("archived").asBoolean())
                throw new ApiError(403, 50083, "Thread is archived");
            validateMessage(input, false);
            ObjectNode message = newMessage(input, cid, app);
            state.messages.computeIfAbsent(cid, key -> new ArrayList<>()).add(message);
            state.emit("MESSAGE_CREATE", message);
            return Reply.ok(message);
        }
        if (path.length != 4) throw ApiError.unsupported();
        ObjectNode message = message(cid, path[3]);
        if (method.equals("GET")) {
            requireChannel(state, channel, app.bot, HISTORY);
            return Reply.ok(message);
        }
        if (method.equals("PATCH")) {
            if (!message.path("author").path("id").asString().equals(app.bot))
                throw new ApiError(403, 50005, "Cannot edit a message authored by another user");
            validateMessage(input, true);
            ObjectNode next = message.deepCopy();
            input.properties().forEach(e -> next.set(e.getKey(), e.getValue()));
            if (next.path("content").asString().isEmpty() && next.path("embeds").isEmpty())
                throw new ApiError(400, 50006, "Cannot send an empty message");
            next.put("edited_timestamp", state.clock.instant().toString());
            List<ObjectNode> history = state.messages.get(cid);
            history.set(history.indexOf(message), next);
            state.emit("MESSAGE_UPDATE", next);
            return Reply.ok(next);
        }
        if (method.equals("DELETE")) {
            if (!message.path("author").path("id").asString().equals(app.bot))
                requireChannel(state, channel, app.bot, MESSAGES);
            state.messages.get(cid).remove(message);
            state.emit("MESSAGE_DELETE", obj("id", path[3], "channel_id", cid, "guild_id", guild.id()));
            if (!message.path("author").path("id").asString().equals(app.bot))
                state.audit(guild, app, 72, message.path("author").path("id").asString(), reason, arr(List.of()));
            return Reply.empty();
        }
        throw ApiError.unsupported();
    }

    private ObjectNode message(String channel, String id) {
        return state.messages.getOrDefault(channel, List.of()).stream()
                .filter(m -> m.path("id").asString().equals(id))
                .findFirst()
                .orElseThrow(() -> new ApiError(404, 10008, "Unknown Message"));
    }

    private static void validateMessage(JsonNode input, boolean patch) {
        fields(input, "content", "embeds", "allowed_mentions", "tts");
        if (input.has("content")
                && (!input.path("content").isString()
                        || input.path("content").asString().length() > 2000)) throw ApiError.invalid("content");
        if (input.has("embeds")) {
            if (!input.path("embeds").isArray() || input.path("embeds").size() > 10) throw ApiError.invalid("embeds");
            int total = 0;
            for (JsonNode embed : input.path("embeds")) {
                fields(
                        embed,
                        "title",
                        "description",
                        "url",
                        "timestamp",
                        "color",
                        "footer",
                        "image",
                        "thumbnail",
                        "author",
                        "fields");
                if (str(embed, "title", "").length() > 256
                        || str(embed, "description", "").length() > 4096) throw ApiError.invalid("embed");
                total += str(embed, "title", "").length()
                        + str(embed, "description", "").length();
                if (embed.has("fields")) {
                    if (!embed.path("fields").isArray() || embed.path("fields").size() > 25)
                        throw ApiError.invalid("fields");
                    for (JsonNode field : embed.path("fields")) {
                        name(field, "name", 256);
                        name(field, "value", 1024);
                        total += field.path("name").asString().length()
                                + field.path("value").asString().length();
                    }
                }
            }
            if (total > 6000) throw ApiError.invalid("embeds");
        }
        bool(input, "tts", false);
        if (!patch
                && str(input, "content", "").isEmpty()
                && input.path("embeds").isEmpty()) throw new ApiError(400, 50006, "Cannot send an empty message");
    }

    private ObjectNode newMessage(JsonNode input, String cid, Application app) {
        return obj(
                "id",
                state.id(),
                "channel_id",
                cid,
                "author",
                state.publicUser(app.bot),
                "content",
                str(input, "content", ""),
                "timestamp",
                state.clock.instant().toString(),
                "edited_timestamp",
                null,
                "tts",
                bool(input, "tts", false),
                "mention_everyone",
                false,
                "mentions",
                List.of(),
                "mention_roles",
                List.of(),
                "attachments",
                List.of(),
                "embeds",
                input.has("embeds") ? input.path("embeds") : List.of(),
                "pinned",
                false,
                "type",
                0,
                "flags",
                0);
    }

    private Reply createThread(JsonNode input, ObjectNode parent, Guild guild, Application app, ObjectNode message) {
        fields(
                input,
                "name",
                "auto_archive_duration",
                "type",
                "invitable",
                "rate_limit_per_user",
                "message",
                "applied_tags");
        name(input, "name", 100);
        int parentType = parent.path("type").asInt();
        boolean forum = parentType == 15;
        if (!Set.of(0, 5, 15).contains(parentType)) throw ApiError.invalid("parent channel type");
        int type = forum || message != null ? (parentType == 5 ? 10 : 11) : integer(input, "type", 12, 11, 12);
        if (input.has("type") && input.path("type").asInt() != type) throw ApiError.invalid("thread type");
        requireChannel(state, parent, app.bot, forum ? SEND : type == 12 ? PRIVATE_THREADS : PUBLIC_THREADS);
        int archive = integer(input, "auto_archive_duration", 1440, 60, 10080);
        if (!Set.of(60, 1440, 4320, 10080).contains(archive)) throw ApiError.invalid("auto_archive_duration");
        integer(input, "rate_limit_per_user", 0, 0, 21600);
        bool(input, "invitable", true);
        if (forum) validateMessage(input.path("message"), false);
        else if (input.has("message") || input.has("applied_tags")) throw ApiError.invalid("forum-only fields");
        if (input.has("applied_tags")) {
            List<String> tags = strings(input.path("applied_tags"));
            if (tags.size() > 5) throw ApiError.invalid("applied_tags");
            for (String tag : tags) {
                boolean found = false;
                for (JsonNode available : parent.path("available_tags"))
                    if (available.path("id").asString().equals(tag)) found = true;
                if (!found) throw ApiError.invalid("applied_tags");
            }
        }
        String id = message == null ? state.id() : message.path("id").asString();
        if (state.channels.containsKey(id))
            throw new ApiError(400, 160004, "A thread has already been created for this message");
        ObjectNode thread = obj(
                "id",
                id,
                "guild_id",
                guild.id(),
                "parent_id",
                parent.path("id"),
                "owner_id",
                app.bot,
                "name",
                input.path("name"),
                "type",
                type,
                "message_count",
                0,
                "member_count",
                1,
                "rate_limit_per_user",
                integer(input, "rate_limit_per_user", 0, 0, 21600),
                "member_ids",
                List.of(app.bot),
                "thread_metadata",
                obj(
                        "archived",
                        false,
                        "locked",
                        false,
                        "auto_archive_duration",
                        archive,
                        "archive_timestamp",
                        state.clock.instant().toString(),
                        "invitable",
                        bool(input, "invitable", true)),
                "applied_tags",
                input.has("applied_tags") ? input.path("applied_tags") : List.of());
        state.channels.put(id, thread);
        ObjectNode result = ChannelApi.publicJson(thread);
        if (forum) {
            ObjectNode first = newMessage(input.path("message"), id, app);
            first.put("id", id);
            state.messages.put(id, new ArrayList<>(List.of(first)));
            thread.put("message_count", 1);
            result.put("message_count", 1);
            result.set("message", first);
        }
        state.emit("THREAD_CREATE", ChannelApi.publicJson(thread));
        state.audit(guild, app, 110, id, "", GuildApi.changes(obj(), thread));
        return Reply.of(201, result);
    }

    Reply thread(String method, JsonNode input, ObjectNode thread, Guild guild, Application app, String reason) {
        if (method.equals("DELETE")) {
            requireChannel(state, thread, app.bot, THREADS);
            state.channels.remove(thread.path("id").asString());
            state.messages.remove(thread.path("id").asString());
            state.emit("THREAD_DELETE", ChannelApi.publicJson(thread));
            state.audit(guild, app, 112, thread.path("id").asString(), reason, arr(List.of()));
            return Reply.ok(ChannelApi.publicJson(thread));
        }
        if (!method.equals("PATCH")) throw ApiError.unsupported();
        fields(input, "name", "archived", "locked", "auto_archive_duration", "rate_limit_per_user", "applied_tags");
        if (!thread.path("owner_id").asString().equals(app.bot)
                || input.has("locked")
                || thread.path("thread_metadata").path("locked").asBoolean())
            requireChannel(state, thread, app.bot, THREADS);
        if (input.has("name")) name(input, "name", 100);
        bool(input, "archived", false);
        bool(input, "locked", false);
        int archive = integer(input, "auto_archive_duration", 1440, 60, 10080);
        if (!Set.of(60, 1440, 4320, 10080).contains(archive)) throw ApiError.invalid("auto_archive_duration");
        integer(input, "rate_limit_per_user", 0, 0, 21600);
        if (input.has("applied_tags")) throw ApiError.invalid("applied_tags update unsupported");
        ObjectNode next = thread.deepCopy();
        ObjectNode metadata = (ObjectNode) next.path("thread_metadata");
        input.properties().forEach(e -> {
            if (Set.of("archived", "locked", "auto_archive_duration").contains(e.getKey()))
                metadata.set(e.getKey(), e.getValue());
            else next.set(e.getKey(), e.getValue());
        });
        if (input.has("archived"))
            metadata.put("archive_timestamp", state.clock.instant().toString());
        state.channels.put(thread.path("id").asString(), next);
        state.emit("THREAD_UPDATE", ChannelApi.publicJson(next));
        state.audit(guild, app, 111, thread.path("id").asString(), reason, GuildApi.changes(thread, next));
        return Reply.ok(ChannelApi.publicJson(next));
    }

    private Reply threadMembers(String method, String[] path, ObjectNode thread, Guild guild, Application app) {
        if (path.length == 3 && method.equals("GET"))
            return Reply.ok(arr(strings(thread.path("member_ids")).stream()
                    .map(id -> obj(
                            "id",
                            thread.path("id"),
                            "user_id",
                            id,
                            "join_timestamp",
                            thread.path("thread_metadata").path("archive_timestamp"),
                            "flags",
                            0))
                    .toList()));
        if (path.length != 4) throw ApiError.unsupported();
        String uid = path[3].equals("@me") ? app.bot : path[3];
        state.member(guild, uid);
        List<String> ids = new ArrayList<>(strings(thread.path("member_ids")));
        if (method.equals("PUT")) {
            requireChannel(state, thread, app.bot, THREAD_SEND);
            if (!ids.contains(uid)) ids.add(uid);
        } else if (method.equals("DELETE")) {
            if (!uid.equals(app.bot)) requireChannel(state, thread, app.bot, THREADS);
            ids.remove(uid);
        } else throw ApiError.unsupported();
        thread.set("member_ids", arr(ids));
        thread.put("member_count", ids.size());
        return Reply.empty();
    }
}
