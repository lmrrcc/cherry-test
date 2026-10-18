package mini.discord;

import static mini.discord.Json.*;
import static mini.discord.PermissionEngine.*;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mini.discord.DiscordState.Application;
import mini.discord.DiscordState.Guild;
import mini.discord.DiscordState.Member;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

public final class Fixtures {
    private Fixtures() {}

    public static JsonNode load(String name) throws IOException {
        if (!name.matches("[a-z0-9-]+")) throw new IllegalArgumentException("Invalid fixture name");
        try (InputStream stream = Fixtures.class.getResourceAsStream("/fixtures/" + name + ".yaml")) {
            if (stream == null) throw new IOException("Unknown fixture: " + name);
            return parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    public static JsonNode parse(String yaml) {
        return MAPPER.valueToTree(new Load(LoadSettings.builder()
                        .setCodePointLimit(1048576)
                        .setMaxAliasesForCollections(0)
                        .build())
                .loadFromString(yaml));
    }

    static ObjectNode seed(DiscordState state, JsonNode recipe) {
        fields(recipe, "fixture", "members", "screening", "guilds", "roles", "channels", "events");
        String fixture = str(recipe, "fixture", "standard");
        if (!Set.of("empty", "standard", "unauthorized", "expired", "large", "performance", "multiple")
                .contains(fixture)) throw ApiError.invalid("fixture");
        int count = integer(
                recipe,
                "members",
                fixture.equals("large") ? 1000 : fixture.equals("performance") ? 10000 : 0,
                0,
                10000);
        int guildCount = integer(recipe, "guilds", fixture.equals("multiple") ? 2 : 1, 1, 10);
        boolean screening = bool(recipe, "screening", false);
        ObjectNode owner = state.createUser(obj("username", "synthetic-owner"));
        Application app = TestApi.createApplication(
                state,
                obj(
                        "name",
                        "Local Community Automation",
                        "redirect_uris",
                        List.of("http://127.0.0.1/callback"),
                        "members_intent",
                        true));
        Map<String, String> roleIds = new LinkedHashMap<>(),
                channelIds = new LinkedHashMap<>(),
                userIds = new LinkedHashMap<>();
        userIds.put("owner", owner.path("id").asString());
        userIds.put("bot", app.bot);
        List<String> guildIds = new ArrayList<>();
        Guild first = null;
        for (int n = 0; n < guildCount; n++) {
            Guild guild = state.createGuild(obj(
                    "name",
                    n == 0 ? "JUG Lugano Local" : "JUG Lugano Local B",
                    "owner_id",
                    owner.path("id"),
                    "screening",
                    screening));
            guildIds.add(guild.id());
            if (n != 0) continue;
            first = guild;
            int position = 1;
            List<String> names = recipe.has("roles")
                    ? strings(recipe.path("roles"))
                    : List.of("MEMBER", "STUDENT", "PROFESSIONAL", "SPEAKER", "MENTOR", "MODERATOR", "STAFF", "ADMIN");
            if (!names.containsAll(
                    List.of("MEMBER", "STUDENT", "PROFESSIONAL", "SPEAKER", "MENTOR", "MODERATOR", "STAFF", "ADMIN")))
                throw ApiError.invalid("fixture roles");
            for (String name : names) {
                BigInteger permissions = switch (name) {
                    case "MEMBER" ->
                        VIEW.or(SEND)
                                .or(HISTORY)
                                .or(CONNECT)
                                .or(SPEAK)
                                .or(PUBLIC_THREADS)
                                .or(THREAD_SEND);
                    case "MODERATOR" -> KICK.or(BAN).or(MODERATE).or(MESSAGES).or(AUDIT);
                    case "STAFF" -> VIEW.or(SEND).or(HISTORY).or(CREATE_EVENTS);
                    case "ADMIN" ->
                        GUILD.or(CHANNELS)
                                .or(ROLES)
                                .or(EVENTS)
                                .or(CREATE_EVENTS)
                                .or(AUDIT);
                    default -> BigInteger.ZERO;
                };
                String id = state.id();
                guild.roles.put(id, DiscordState.roleJson(id, name, position++, permissions, false));
                roleIds.put(name, id);
            }
            ObjectNode installation = install(
                    state,
                    guild,
                    app,
                    obj(
                            "requested_permissions",
                            BOT_DEFAULT.toString(),
                            "authorized_permissions",
                            BOT_DEFAULT.toString(),
                            "position",
                            position));
            String botRole = installation.path("role_id").asString();
            roleIds.put("BOT", botRole);
            roleIds.put("@everyone", guild.id());
            List<JsonNode> channelRecipes = new ArrayList<>();
            if (recipe.has("channels")) recipe.path("channels").forEach(channelRecipes::add);
            else {
                for (String name : List.of(
                        "benvenuto",
                        "regolamento",
                        "annunci",
                        "presentazioni",
                        "java-jvm",
                        "java-ee",
                        "spring",
                        "java-ai",
                        "eventi-talk",
                        "studenti-mentoring",
                        "staff",
                        "opportunita-lavoro",
                        "off-topic"))
                    channelRecipes.add(obj(
                            "name",
                            name,
                            "type",
                            name.equals("annunci") ? 5 : 0,
                            "access",
                            name.equals("staff")
                                    ? "STAFF"
                                    : List.of("benvenuto", "regolamento").contains(name) ? "public" : "MEMBER"));
                channelRecipes.add(obj(
                        "name",
                        "Java Questions",
                        "type",
                        15,
                        "access",
                        "MEMBER",
                        "tags",
                        List.of("Spring", "Jakarta EE", "Hibernate", "JVM", "AI", "Testing")));
                channelRecipes.add(obj("name", "Meetup Online", "type", 2, "access", "MEMBER"));
                channelRecipes.add(obj("name", "Mentoring", "type", 2, "access", "MEMBER"));
            }
            ChannelApi channelApi = new ChannelApi(state, new Administration(state));
            for (JsonNode configured : channelRecipes) {
                String name = required(configured, "name");
                if (fixture.equals("empty")
                        && !List.of("benvenuto", "regolamento").contains(name)) continue;
                String access = str(configured, "access", "MEMBER");
                List<JsonNode> overwrites = new ArrayList<>();
                if (!access.equals("public")) {
                    String accessRole = roleIds.get(access);
                    if (accessRole == null) throw ApiError.invalid("access role");
                    overwrites.add(obj("id", guild.id(), "type", 0, "deny", VIEW.toString(), "allow", "0"));
                    overwrites.add(obj(
                            "id",
                            accessRole,
                            "type",
                            0,
                            "allow",
                            VIEW.or(SEND).or(HISTORY).toString(),
                            "deny",
                            "0"));
                    overwrites.add(obj("id", botRole, "type", 0, "allow", VIEW.toString(), "deny", "0"));
                }
                ObjectNode input = obj(
                        "name",
                        name,
                        "type",
                        integer(configured, "type", 0, 0, 15),
                        "permission_overwrites",
                        overwrites);
                if (configured.has("tags"))
                    input.set(
                            "available_tags",
                            arr(strings(configured.path("tags")).stream()
                                    .map(tag -> obj("name", tag, "moderated", false))
                                    .toList()));
                ObjectNode channel = channelApi.prepareChannel(input, guild, app, null);
                String id = state.id();
                channel.put("id", id);
                state.channels.put(id, channel);
                channelIds.put(name, id);
            }
            if (!fixture.equals("empty") && count == 0) {
                List<String> labels = new ArrayList<>(List.of(
                        "staff",
                        "moderator",
                        "mentor-1",
                        "mentor-2",
                        "speaker",
                        "pending",
                        "unapproved",
                        "banned",
                        "kicked",
                        "expired"));
                for (int i = 1; i <= 5; i++) {
                    labels.add("student-" + i);
                    labels.add("professional-" + i);
                }
                for (String label : labels) {
                    ObjectNode user = state.createUser(
                            obj("username", label, "email", label + "@example.test", "verified", true));
                    String uid = user.path("id").asString();
                    userIds.put(label, uid);
                    if (label.equals("banned")) {
                        guild.bans.put(uid, obj("reason", "Synthetic fixture ban", "user", state.publicUser(uid)));
                        continue;
                    }
                    if (label.equals("kicked")) continue;
                    Member member = state.join(guild, uid);
                    member.pending = label.equals("pending");
                    if (!Set.of("pending", "unapproved", "expired").contains(label))
                        member.roles.add(roleIds.get("MEMBER"));
                    String extra = label.startsWith("student")
                            ? "STUDENT"
                            : label.startsWith("professional")
                                    ? "PROFESSIONAL"
                                    : label.startsWith("mentor")
                                            ? "MENTOR"
                                            : label.equals("staff")
                                                    ? "STAFF"
                                                    : label.equals("moderator")
                                                            ? "MODERATOR"
                                                            : label.equals("speaker") ? "SPEAKER" : "";
                    if (!extra.isEmpty()) member.roles.add(roleIds.get(extra));
                }
            }
            for (int i = 0; i < count; i++) {
                String uid = state.createUser(obj("username", "synthetic-user-" + i))
                        .path("id")
                        .asString();
                state.join(guild, uid).roles.add(roleIds.get("MEMBER"));
            }
            if (!fixture.equals("empty")) {
                List<String> events = recipe.has("events")
                        ? strings(recipe.path("events"))
                        : List.of(
                                "Java Meetup",
                                "Spring Boot Workshop",
                                "Jakarta EE Technical Session",
                                "AI with Java",
                                "Student Mentoring");
                for (String name : events) {
                    String id = state.id();
                    guild.scheduled.put(
                            id,
                            obj(
                                    "id",
                                    id,
                                    "guild_id",
                                    guild.id(),
                                    "name",
                                    name,
                                    "description",
                                    null,
                                    "channel_id",
                                    null,
                                    "creator_id",
                                    app.bot,
                                    "creator",
                                    state.publicUser(app.bot),
                                    "entity_type",
                                    3,
                                    "privacy_level",
                                    2,
                                    "status",
                                    1,
                                    "entity_id",
                                    null,
                                    "image",
                                    null,
                                    "entity_metadata",
                                    obj("location", "Local synthetic meetup"),
                                    "scheduled_start_time",
                                    state.clock.instant().plusSeconds(86400).toString(),
                                    "scheduled_end_time",
                                    state.clock.instant().plusSeconds(90000).toString()));
                }
            }
        }
        if (first == null) throw new IllegalStateException("Fixture has no guild");
        String inviteCode = null;
        if (fixture.equals("unauthorized")) {
            String user = userIds.get("unapproved");
            if (user == null) throw ApiError.invalid("unauthorized fixture requires standard users");
            state.leave(first, user);
            Administration administration = new Administration(state);
            inviteCode = administration
                    .channelInvites(
                            "POST",
                            obj("max_age", 0, "unique", true),
                            state.channel(channelIds.get("benvenuto")),
                            first,
                            app,
                            "Synthetic fixture invite")
                    .body()
                    .path("code")
                    .asString();
            administration.accept(inviteCode, user);
        }
        state.events.clear();
        state.eventSequence = 0;
        return obj(
                "guild_id",
                first.id(),
                "guild_ids",
                guildIds,
                "roles",
                roleIds,
                "channels",
                channelIds,
                "users",
                userIds,
                "invite_code",
                inviteCode,
                "application",
                app.credentials());
    }

    static ObjectNode install(DiscordState state, Guild guild, Application app, JsonNode input) {
        if (guild.members.containsKey(app.bot)) throw ApiError.invalid("bot already installed");
        BigInteger requested = bits(input, "requested_permissions"), authorized = bits(input, "authorized_permissions");
        if (!has(requested, authorized)) throw ApiError.invalid("authorized_permissions exceed requested_permissions");
        int position = integer(input, "position", guild.roles.size(), 1, guild.roles.size());
        List<String> additional = input.has("roles") ? strings(input.path("roles")) : List.of();
        for (String id : additional) state.role(guild, id);
        String rid = state.id();
        guild.roles.values().stream()
                .filter(r -> r.path("position").asInt() >= position)
                .forEach(r -> r.put("position", r.path("position").asInt() + 1));
        ObjectNode role = DiscordState.roleJson(rid, app.name, position, authorized, true);
        role.set("tags", obj("bot_id", app.bot));
        guild.roles.put(rid, role);
        Member member = new Member(app.bot, state.clock.instant().toString(), false);
        member.roles.add(rid);
        member.roles.addAll(additional);
        guild.members.put(app.bot, member);
        state.memberEvent("GUILD_MEMBER_ADD", guild, member);
        state.emit("GUILD_ROLE_CREATE", obj("guild_id", guild.id(), "role", role));
        return obj(
                "role_id",
                rid,
                "bot_id",
                app.bot,
                "guild_id",
                guild.id(),
                "requested_permissions",
                requested.toString(),
                "authorized_permissions",
                authorized.toString());
    }
}
