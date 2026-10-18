package mini.discord;

import static mini.discord.Json.*;

import java.math.BigInteger;
import java.time.Instant;
import mini.discord.DiscordState.Guild;
import mini.discord.DiscordState.Member;
import tools.jackson.databind.JsonNode;

final class PermissionEngine {
    static final BigInteger INVITE = bit(0),
            KICK = bit(1),
            BAN = bit(2),
            ADMINISTRATOR = bit(3),
            CHANNELS = bit(4),
            GUILD = bit(5),
            AUDIT = bit(7),
            VIEW = bit(10),
            SEND = bit(11),
            MESSAGES = bit(13),
            HISTORY = bit(16),
            CONNECT = bit(20),
            SPEAK = bit(21),
            MUTE = bit(22),
            DEAFEN = bit(23),
            MOVE = bit(24),
            NICK = bit(27),
            ROLES = bit(28),
            EVENTS = bit(33),
            THREADS = bit(34),
            PUBLIC_THREADS = bit(35),
            PRIVATE_THREADS = bit(36),
            THREAD_SEND = bit(38),
            MODERATE = bit(40),
            CREATE_EVENTS = bit(44);
    static final BigInteger ALL = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
    static final BigInteger BOT_DEFAULT = INVITE.or(KICK)
            .or(BAN)
            .or(CHANNELS)
            .or(GUILD)
            .or(AUDIT)
            .or(VIEW)
            .or(SEND)
            .or(HISTORY)
            .or(ROLES)
            .or(NICK)
            .or(EVENTS)
            .or(CREATE_EVENTS)
            .or(MODERATE)
            .or(MESSAGES)
            .or(PUBLIC_THREADS)
            .or(PRIVATE_THREADS)
            .or(THREADS)
            .or(THREAD_SEND)
            .or(CONNECT)
            .or(SPEAK)
            .or(MUTE)
            .or(DEAFEN)
            .or(MOVE);

    private PermissionEngine() {}

    static BigInteger bit(int bit) {
        return BigInteger.ONE.shiftLeft(bit);
    }

    static boolean has(BigInteger permissions, BigInteger required) {
        return permissions.and(required).equals(required);
    }

    static BigInteger base(Guild guild, String uid) {
        Member member = guild.members.get(uid);
        if (member == null) return BigInteger.ZERO;
        if (guild.owner().equals(uid)) return ALL;
        BigInteger permissions = bits(guild.roles.get(guild.id()), "permissions");
        for (String id : member.roles) {
            JsonNode role = guild.roles.get(id);
            if (role != null) permissions = permissions.or(bits(role, "permissions"));
        }
        return has(permissions, ADMINISTRATOR) ? ALL : permissions;
    }

    static BigInteger channel(DiscordState state, JsonNode channel, String uid) {
        Guild guild = state.guild(channel.path("guild_id").asString());
        Member member = guild.members.get(uid);
        if (member == null) return BigInteger.ZERO;
        BigInteger permissions = base(guild, uid);
        if (has(permissions, ADMINISTRATOR)) return ALL;
        int type = channel.path("type").asInt();
        JsonNode source = type >= 10 && type <= 12
                ? state.channel(channel.path("parent_id").asString())
                : channel;
        BigInteger allow = BigInteger.ZERO, deny = BigInteger.ZERO;
        for (JsonNode overwrite : source.path("permission_overwrites")) {
            String id = overwrite.path("id").asString();
            if (id.equals(guild.id()) && overwrite.path("type").asInt() == 0)
                permissions = apply(permissions, overwrite);
        }
        for (JsonNode overwrite : source.path("permission_overwrites")) {
            String id = overwrite.path("id").asString();
            if (overwrite.path("type").asInt() == 0 && member.roles.contains(id)) {
                allow = allow.or(bits(overwrite, "allow"));
                deny = deny.or(bits(overwrite, "deny"));
            }
        }
        permissions = permissions.andNot(deny).or(allow);
        for (JsonNode overwrite : source.path("permission_overwrites")) {
            if (overwrite.path("type").asInt() == 1
                    && overwrite.path("id").asString().equals(uid)) permissions = apply(permissions, overwrite);
        }
        if (!has(permissions, VIEW)) return BigInteger.ZERO;
        if (!has(permissions, SEND))
            permissions = permissions.andNot(bit(12).or(bit(14)).or(bit(15)).or(bit(17)));
        if (member.pending)
            permissions = permissions.andNot(
                    SEND.or(THREAD_SEND).or(CONNECT).or(SPEAK).or(INVITE));
        if (member.timeout != null && Instant.parse(member.timeout).isAfter(state.clock.instant()))
            permissions = permissions.and(VIEW.or(HISTORY));
        if (type == 12
                && !has(permissions, THREADS)
                && !Json.strings(channel.path("member_ids")).contains(uid)) return BigInteger.ZERO;
        return permissions;
    }

    private static BigInteger apply(BigInteger base, JsonNode overwrite) {
        return base.andNot(bits(overwrite, "deny")).or(bits(overwrite, "allow"));
    }

    static void require(Guild guild, String uid, BigInteger permission) {
        if (!has(base(guild, uid), permission)) throw ApiError.permissions();
    }

    static void requireChannel(DiscordState state, JsonNode channel, String uid, BigInteger permission) {
        if (!has(channel(state, channel, uid), permission)) throw ApiError.permissions();
    }

    static int highest(Guild guild, String uid) {
        if (guild.owner().equals(uid)) return Integer.MAX_VALUE;
        Member member = guild.members.get(uid);
        if (member == null) return -1;
        return member.roles.stream()
                .map(guild.roles::get)
                .filter(java.util.Objects::nonNull)
                .mapToInt(role -> role.path("position").asInt())
                .max()
                .orElse(0);
    }

    static void target(Guild guild, String actor, String target) {
        if (guild.owner().equals(target) || actor.equals(target) || highest(guild, actor) <= highest(guild, target))
            throw ApiError.permissions();
    }

    static void role(Guild guild, String actor, JsonNode role) {
        require(guild, actor, ROLES);
        if (role.path("id").asString().equals(guild.id())
                || role.path("managed").asBoolean()
                || role.path("position").asInt() >= highest(guild, actor)) throw ApiError.permissions();
    }

    static void grantable(Guild guild, String actor, BigInteger requested) {
        if (!has(base(guild, actor), requested)) throw ApiError.permissions();
    }
}
