package mini.discord;

import static mini.discord.Json.*;
import static org.assertj.core.api.Assertions.*;

import java.math.BigInteger;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class StateTest {
    @Test
    void permission_bitfields_preserve_high_bits() {
        BigInteger high = BigInteger.ONE.shiftLeft(52);
        assertThat(bits(obj("permissions", high.toString()), "permissions")).isEqualTo(high);
        assertThatThrownBy(() -> bits(obj("permissions", "18446744073709551616"), "permissions"))
                .isInstanceOf(ApiError.class);
        assertThatThrownBy(() -> bits(obj("permissions", "-1"), "permissions")).isInstanceOf(ApiError.class);
    }

    @Test
    void identity_is_independent_of_membership() {
        DiscordState state = new DiscordState(TestClock.deterministic());
        String owner = state.createUser(obj("username", "owner")).path("id").asString();
        String uid = state.createUser(obj("username", "same-name")).path("id").asString();
        String second =
                state.createUser(obj("username", "same-name")).path("id").asString();
        assertThat(uid).isNotEqualTo(second);
        var guild = state.createGuild(obj("owner_id", owner, "name", "Guild A"));
        var other = state.createGuild(obj("owner_id", owner, "name", "Guild B"));
        state.join(guild, uid);
        assertThat(other.members).doesNotContainKey(uid);
        state.leave(guild, uid);
        assertThat(state.user(uid).path("id").asString()).isEqualTo(uid);
    }

    @Test
    void unavailable_identity_and_clock_boundaries() {
        TestClock clock = TestClock.deterministic();
        DiscordState state = new DiscordState(clock);
        String uid = state.createUser(obj("username", "deleted", "unavailable", true))
                .path("id")
                .asString();
        assertThatThrownBy(() -> state.user(uid)).isInstanceOf(ApiError.class);
        assertThat(state.publicUser(uid).has("unavailable")).isFalse();
        var before = clock.instant();
        clock.advance(Duration.ofSeconds(1));
        assertThat(clock.instant()).isEqualTo(before.plusSeconds(1));
        assertThatThrownBy(() -> clock.advance(Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deterministic_fixture_and_snapshot_are_detached() throws Exception {
        DiscordState first = new DiscordState(TestClock.deterministic()),
                second = new DiscordState(TestClock.deterministic());
        var a = Fixtures.seed(first, Fixtures.load("jug-lugano-discord"));
        var b = Fixtures.seed(second, Fixtures.load("jug-lugano-discord"));
        assertThat(a.path("roles")).isEqualTo(b.path("roles"));
        assertThat(a.path("channels")).isEqualTo(b.path("channels"));
        var snapshot = first.snapshot();
        ((tools.jackson.databind.node.ObjectNode) snapshot.path("guilds").get(0)).put("name", "mutated snapshot");
        ((tools.jackson.databind.node.ObjectNode)
                        snapshot.path("guilds").get(0).path("roles").get(0))
                .put("permissions", "8");
        assertThat(first.guild(a.path("guild_id").asString()).data.path("name").asString())
                .isEqualTo("JUG Lugano Local");
        assertThat(first.guild(a.path("guild_id").asString())
                        .roles
                        .get(a.path("guild_id").asString())
                        .path("permissions")
                        .asString())
                .isNotEqualTo("8");
    }

    @Test
    void fault_templates_and_sanitization() {
        assertThat(Faults.matches("PUT /guilds/{guildId}/members/{userId}", "PUT", "/api/v10/guilds/123/members/456"))
                .isTrue();
        assertThat(Faults.matches(
                        "PUT /guilds/{guildId}/members/{userId}", "DELETE", "/api/v10/guilds/123/members/456"))
                .isFalse();
        assertThat(Evidence.sanitize(obj(
                                "Authorization",
                                "Bot forbidden",
                                "client_secret",
                                "forbidden",
                                "code",
                                "mini_discord_code_secret",
                                "nested",
                                List.of(obj("email", "member@example.test"))))
                        .toString())
                .doesNotContain("forbidden", "mini_discord_code_secret", "member@example.test");
    }
}
