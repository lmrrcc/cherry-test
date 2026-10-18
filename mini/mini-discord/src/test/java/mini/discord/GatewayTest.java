package mini.discord;

import static mini.discord.Json.*;
import static org.assertj.core.api.Assertions.*;

import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import tools.jackson.databind.JsonNode;

class GatewayTest {
    @RegisterExtension
    final HttpFixture f = new HttpFixture();

    static final class Client implements WebSocket.Listener, AutoCloseable {
        final LinkedBlockingQueue<JsonNode> events = new LinkedBlockingQueue<>();
        final CompletableFuture<Integer> close = new CompletableFuture<>();
        final StringBuilder partial = new StringBuilder();
        WebSocket socket;

        @Override
        public void onOpen(WebSocket socket) {
            socket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence text, boolean last) {
            partial.append(text);
            if (last) {
                events.add(MAPPER.readTree(partial.toString()));
                partial.setLength(0);
            }
            socket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int code, String reason) {
            close.complete(code);
            return null;
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            close.completeExceptionally(error);
        }

        JsonNode next(int op, String type) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            List<String> received = new ArrayList<>();
            while (System.nanoTime() < deadline) {
                JsonNode event = events.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                if (event == null) break;
                if (event.path("op").asInt() == op
                        && (type.isEmpty() || event.path("t").asString().equals(type))) return event;
                received.add(event.path("t").asString());
            }
            throw new AssertionError("Missing Gateway event " + type + " received " + received);
        }

        void send(JsonNode message) {
            socket.sendText(message.toString(), true).join();
        }

        @Override
        public void close() {
            if (socket != null) socket.abort();
        }
    }

    static Client connect(HttpFixture f, int intents) throws Exception {
        Client client = new Client();
        client.socket = f.client
                .newWebSocketBuilder()
                .buildAsync(java.net.URI.create(f.server.gatewayUri() + "/?v=10&encoding=json"), client)
                .get(5, TimeUnit.SECONDS);
        assertThat(client.next(10, "").path("d").path("heartbeat_interval").asInt())
                .isEqualTo(10000);
        client.send(obj(
                "op",
                2,
                "d",
                obj(
                        "token",
                        f.bot,
                        "intents",
                        intents,
                        "properties",
                        obj("os", "local", "browser", "cherry-test", "device", "cherry-test"))));
        return client;
    }

    static void membership(HttpFixture f) throws Exception {
        try (Client client = connect(f, 3)) {
            assertThat(client.next(0, "READY").path("d").path("user").path("id").asString())
                    .isEqualTo(f.user("bot"));
            client.next(0, "GUILD_CREATE");
            String uid = f.newUser();
            f.join(uid);
            JsonNode added = client.next(0, "GUILD_MEMBER_ADD");
            assertThat(added.path("d").path("user").path("id").asString()).isEqualTo(uid);
            f.grant(uid, f.role("MEMBER"));
            JsonNode updated = client.next(0, "GUILD_MEMBER_UPDATE");
            assertThat(updated.path("s").asLong()).isGreaterThan(added.path("s").asLong());
            f.api("DELETE", "/guilds/" + f.guild + "/members/" + uid, null, 204);
            assertThat(client.next(0, "GUILD_MEMBER_REMOVE")
                            .path("d")
                            .path("user")
                            .path("id")
                            .asString())
                    .isEqualTo(uid);
            client.send(obj("op", 1, "d", updated.path("s")));
            client.next(11, "");
        }
    }

    @Test
    void required_member_intent() throws Exception {
        membership(f);
    }

    @Test
    void missing_member_intent_does_not_receive_members() throws Exception {
        try (Client client = connect(f, 1)) {
            client.next(0, "READY");
            client.next(0, "GUILD_CREATE");
            String uid = f.newUser();
            f.join(uid);
            f.api("PATCH", "/guilds/" + f.guild, obj("name", "Gateway barrier"), 200);
            JsonNode event = client.events.poll(5, TimeUnit.SECONDS);
            assertThat(event).isNotNull();
            assertThat(event.path("t").asString()).isEqualTo("GUILD_UPDATE");
        }
    }

    @Test
    void unauthorized_privileged_intent_closes_4014() throws Exception {
        f.control("PATCH", "applications/" + f.appId, obj("members_intent", false), 200);
        try (Client client = connect(f, 3)) {
            assertThat(client.close.get(5, TimeUnit.SECONDS)).isEqualTo(4014);
        }
        f.api("GET", "/guilds/" + f.guild + "/members?limit=1000", null, 403);
    }

    @Test
    void invalid_intents_and_reset_close() throws Exception {
        try (Client client = connect(f, 32768)) {
            assertThat(client.close.get(5, TimeUnit.SECONDS)).isEqualTo(4013);
        }
        try (Client client = connect(f, 3)) {
            client.next(0, "READY");
            f.control("POST", "reset", obj(), 200);
            assertThat(client.close.get(5, TimeUnit.SECONDS)).isEqualTo(4009);
        }
    }

    @Test
    void role_channel_and_guild_events() throws Exception {
        try (Client client = connect(f, 1)) {
            client.next(0, "READY");
            client.next(0, "GUILD_CREATE");
            String role = f.api("POST", "/guilds/" + f.guild + "/roles", obj("name", "Gateway role"), 200)
                    .body()
                    .path("id")
                    .asString();
            client.next(0, "GUILD_ROLE_CREATE");
            f.api("PATCH", "/guilds/" + f.guild + "/roles/" + role, obj("name", "Updated"), 200);
            client.next(0, "GUILD_ROLE_UPDATE");
            f.api("DELETE", "/guilds/" + f.guild + "/roles/" + role, null, 204);
            client.next(0, "GUILD_ROLE_DELETE");
            String channel = f.api("POST", "/guilds/" + f.guild + "/channels", obj("name", "gateway-channel"), 201)
                    .body()
                    .path("id")
                    .asString();
            client.next(0, "CHANNEL_CREATE");
            f.api("PATCH", "/channels/" + channel, obj("topic", "updated"), 200);
            client.next(0, "CHANNEL_UPDATE");
            f.api("DELETE", "/channels/" + channel, null, 200);
            client.next(0, "CHANNEL_DELETE");
        }
    }
}
