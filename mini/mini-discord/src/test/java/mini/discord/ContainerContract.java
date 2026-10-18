package mini.discord;

import static mini.discord.Json.*;
import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class ContainerContract {
    @RegisterExtension
    final HttpFixture f = new HttpFixture(true);

    @Test
    void mini_discord_project_container_suite_complete_lifecycle_test() throws Exception {
        String uid = f.newUser();
        String token = f.join(uid);
        f.grant(uid, f.role("MEMBER"));
        f.readable(uid, f.channel("java-jvm"), true);
        f.revoke(uid, f.role("MEMBER"));
        f.readable(uid, f.channel("java-jvm"), false);
        f.grant(uid, f.role("MEMBER"));
        f.readable(uid, f.channel("java-jvm"), true);
        f.api("DELETE", "/guilds/" + f.guild + "/members/" + uid, null, 204);
        f.api("GET", "/guilds/" + f.guild + "/members/" + uid, null, 404);
        f.api("PUT", "/guilds/" + f.guild + "/members/" + uid, obj("access_token", token), 201);
        f.api("PUT", "/guilds/" + f.guild + "/bans/" + uid, obj(), 204);
        f.api("PUT", "/guilds/" + f.guild + "/members/" + uid, obj("access_token", token), 403);
        assertThat(f.eventCount("GUILD_MEMBER_ADD")).isEqualTo(2);
        assertThat(f.eventCount("GUILD_MEMBER_REMOVE")).isEqualTo(2);
    }
}
