package mini.discord;

import static mini.discord.Json.*;

import java.math.BigInteger;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

final class DiscordState {
    static final Comparator<String> IDS = Comparator.comparing(BigInteger::new);
    final Clock clock;
    final Map<String, ObjectNode> users;
    final Map<String, Application> applications;
    final Map<String, Guild> guilds;
    final Map<String, ObjectNode> channels;
    final Map<String, Invite> invites;
    final Map<String, List<ObjectNode>> messages;
    final Set<String> syncedChannels;
    final List<ObjectNode> events;
    long sequence;
    long eventSequence;
    long generation;

    DiscordState(Clock clock) {
        this(clock, StateStore.memory());
    }

    DiscordState(Clock clock, StateStore store) {
        this.clock = clock;
        users = store.users();
        applications = store.applications();
        guilds = store.guilds();
        channels = store.channels();
        invites = store.invites();
        messages = store.messages();
        syncedChannels = store.syncedChannels();
        events = store.events();
    }

    static final class Application {
        final String id;
        final String bot;
        final String name;
        final String secret = credential("secret");
        final String token = credential("bot");
        final Set<String> redirects;
        final Set<String> scopes;
        boolean enabled = true;
        boolean revoked;
        boolean membersIntent;

        Application(
                String id, String bot, String name, Set<String> redirects, Set<String> scopes, boolean membersIntent) {
            this.id = id;
            this.bot = bot;
            this.name = name;
            this.redirects = redirects;
            this.scopes = scopes;
            this.membersIntent = membersIntent;
        }

        ObjectNode publicJson() {
            return obj("id", id, "name", name, "bot_user_id", bot, "flags", membersIntent ? 16384 : 0);
        }

        ObjectNode credentials() {
            ObjectNode out = publicJson();
            out.put("client_id", id);
            out.put("client_secret", secret);
            out.put("bot_token", token);
            return out;
        }
    }

    static final class Member {
        final String userId;
        final Set<String> roles = new LinkedHashSet<>();
        final String joined;
        String nick;
        boolean pending;
        boolean mute;
        boolean deaf;
        boolean temporary;
        String timeout;

        Member(String userId, String joined, boolean pending) {
            this.userId = userId;
            this.joined = joined;
            this.pending = pending;
        }

        ObjectNode json(DiscordState state) {
            return obj(
                    "user",
                    state.publicUser(userId),
                    "nick",
                    nick,
                    "avatar",
                    null,
                    "roles",
                    roles,
                    "joined_at",
                    joined,
                    "deaf",
                    deaf,
                    "mute",
                    mute,
                    "flags",
                    0,
                    "pending",
                    pending,
                    "communication_disabled_until",
                    timeout);
        }
    }

    static final class Guild {
        final ObjectNode data;
        final Map<String, ObjectNode> roles = new LinkedHashMap<>();
        final Map<String, Member> members = new LinkedHashMap<>();
        final Map<String, ObjectNode> bans = new LinkedHashMap<>();
        final Map<String, ObjectNode> scheduled = new LinkedHashMap<>();
        final Map<String, Set<String>> interested = new LinkedHashMap<>();
        final Map<String, ObjectNode> automod = new LinkedHashMap<>();
        final List<ObjectNode> audit = new ArrayList<>();
        boolean screening;

        Guild(ObjectNode data, boolean screening) {
            this.data = data;
            this.screening = screening;
        }

        String id() {
            return data.path("id").asString();
        }

        String owner() {
            return data.path("owner_id").asString();
        }

        ObjectNode json() {
            ObjectNode out = data.deepCopy();
            out.set("roles", arr(roles.values()));
            return out;
        }
    }

    static final class Invite {
        final ObjectNode data;
        final long expires;
        int uses;
        boolean revoked;

        Invite(ObjectNode data, long expires) {
            this.data = data;
            this.expires = expires;
        }
    }

    String id() {
        return Long.toString(100000000000000000L + ++sequence);
    }

    static String credential(String kind) {
        return "mini_discord_" + kind + "_" + UUID.randomUUID();
    }

    ObjectNode user(String id) {
        ObjectNode value = users.get(id);
        if (value == null || value.path("unavailable").asBoolean(false)) throw new ApiError(404, 10013, "Unknown User");
        return value;
    }

    ObjectNode publicUser(String id) {
        ObjectNode value = users.get(id);
        if (value == null) throw new ApiError(404, 10013, "Unknown User");
        ObjectNode out = value.deepCopy();
        for (String key : List.of("email", "verified", "locale", "unavailable")) out.remove(key);
        return out;
    }

    Guild guild(String id) {
        Guild value = guilds.get(id);
        if (value == null) throw new ApiError(404, 10004, "Unknown Guild");
        return value;
    }

    Member member(Guild guild, String id) {
        Member value = guild.members.get(id);
        if (value == null) throw new ApiError(404, 10007, "Unknown Member");
        return value;
    }

    ObjectNode role(Guild guild, String id) {
        ObjectNode value = guild.roles.get(id);
        if (value == null) throw new ApiError(404, 10011, "Unknown Role");
        return value;
    }

    ObjectNode channel(String id) {
        ObjectNode value = channels.get(id);
        if (value == null) throw new ApiError(404, 10003, "Unknown Channel");
        return value;
    }

    Application bot(String authorization) {
        if (!authorization.startsWith("Bot mini_discord_bot_")) throw ApiError.unauthorized();
        return applications.values().stream()
                .filter(app -> app.enabled && !app.revoked && authorization.equals("Bot " + app.token))
                .findFirst()
                .orElseThrow(ApiError::unauthorized);
    }

    Application installed(String authorization, Guild guild) {
        Application app = bot(authorization);
        if (!guild.members.containsKey(app.bot)) throw new ApiError(403, 50001, "Missing Access");
        return app;
    }

    ObjectNode createUser(JsonNode input) {
        fields(input, "id", "username", "global_name", "bot", "email", "verified", "locale", "unavailable");
        String username = name(input, "username", 32);
        String uid = input.has("id") ? snowflake(required(input, "id")) : id();
        if (users.containsKey(uid)) throw ApiError.invalid("duplicate id");
        if (input.has("email")
                && !input.path("email").isNull()
                && !required(input, "email").endsWith("@example.test")) throw ApiError.invalid("synthetic email");
        ObjectNode user = obj(
                "id",
                uid,
                "username",
                username,
                "global_name",
                input.path("global_name").isMissingNode() ? null : input.path("global_name"),
                "discriminator",
                "0",
                "avatar",
                null,
                "bot",
                bool(input, "bot", false),
                "system",
                false,
                "flags",
                0,
                "public_flags",
                0);
        for (String field : List.of("email", "verified", "locale", "unavailable"))
            if (input.has(field)) user.set(field, input.path(field));
        users.put(uid, user);
        return user.deepCopy();
    }

    Guild createGuild(JsonNode input) {
        String owner = required(input, "owner_id");
        user(owner);
        String name = name(input, "name", 100);
        String gid = input.has("id") ? snowflake(required(input, "id")) : id();
        if (guilds.containsKey(gid)) throw ApiError.invalid("duplicate id");
        boolean screening = bool(input, "screening", false);
        Guild guild = new Guild(
                obj(
                        "id",
                        gid,
                        "name",
                        name,
                        "owner_id",
                        owner,
                        "description",
                        null,
                        "icon",
                        null,
                        "features",
                        screening
                                ? List.of("COMMUNITY", "NEWS", "MEMBER_VERIFICATION_GATE_ENABLED")
                                : List.of("COMMUNITY", "NEWS"),
                        "verification_level",
                        0,
                        "default_message_notifications",
                        1,
                        "explicit_content_filter",
                        0,
                        "afk_timeout",
                        300,
                        "afk_channel_id",
                        null,
                        "system_channel_id",
                        null,
                        "preferred_locale",
                        "en-US",
                        "premium_tier",
                        0),
                screening);
        guild.roles.put(gid, roleJson(gid, "@everyone", 0, PermissionEngine.VIEW.or(PermissionEngine.HISTORY), false));
        guild.members.put(owner, new Member(owner, clock.instant().toString(), false));
        guilds.put(gid, guild);
        emit("GUILD_CREATE", guild.json());
        return guild;
    }

    static ObjectNode roleJson(String id, String name, int position, BigInteger permissions, boolean managed) {
        return obj(
                "id",
                id,
                "name",
                name,
                "position",
                position,
                "permissions",
                permissions.toString(),
                "color",
                0,
                "colors",
                obj("primary_color", 0, "secondary_color", null, "tertiary_color", null),
                "hoist",
                false,
                "mentionable",
                false,
                "managed",
                managed,
                "flags",
                0);
    }

    Member join(Guild guild, String userId) {
        user(userId);
        if (guild.bans.containsKey(userId)) throw new ApiError(403, 40007, "The user is banned from this guild");
        Member existing = guild.members.get(userId);
        if (existing != null) return existing;
        Member member = new Member(userId, clock.instant().toString(), guild.screening);
        guild.members.put(userId, member);
        memberEvent("GUILD_MEMBER_ADD", guild, member);
        return member;
    }

    void leave(Guild guild, String userId) {
        Member member = member(guild, userId);
        guild.members.remove(userId);
        emit("GUILD_MEMBER_REMOVE", obj("guild_id", guild.id(), "user", publicUser(member.userId)));
    }

    void memberEvent(String type, Guild guild, Member member) {
        ObjectNode data = member.json(this);
        data.put("guild_id", guild.id());
        emit(type, data);
    }

    void emit(String type, JsonNode data) {
        events.add(obj("op", 0, "t", type, "s", ++eventSequence, "d", data.deepCopy()));
    }

    void audit(Guild guild, Application actor, int action, String target, String reason, JsonNode changes) {
        guild.audit.add(obj(
                "id",
                id(),
                "action_type",
                action,
                "user_id",
                actor.bot,
                "target_id",
                target,
                "reason",
                reason.isEmpty() ? null : reason,
                "changes",
                changes));
    }

    ObjectNode snapshot() {
        List<ObjectNode> allGuilds = new ArrayList<>();
        guilds.values().forEach(guild -> {
            ObjectNode out = guild.json();
            out.set(
                    "members",
                    arr(guild.members.values().stream().map(m -> m.json(this)).toList()));
            out.set("bans", arr(guild.bans.values()));
            out.set("scheduled_events", arr(guild.scheduled.values()));
            allGuilds.add(out);
        });
        return obj(
                "users",
                users.values(),
                "guilds",
                allGuilds,
                "channels",
                channels.values(),
                "applications",
                applications.values().stream().map(Application::publicJson).toList(),
                "clock",
                clock.instant().toString(),
                "generation",
                generation);
    }
}
