package mini.discord;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        Map<String, String> env = System.getenv();
        int port = Integer.parseInt(env.getOrDefault("MINI_DISCORD_PORT", "0"));
        if (args.length == 1 && args[0].equals("health")) {
            try (HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .build()) {
                int status = client.send(
                                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health"))
                                        .timeout(Duration.ofSeconds(2))
                                        .build(),
                                HttpResponse.BodyHandlers.discarding())
                        .statusCode();
                if (status != 200) throw new IllegalStateException("Mini-Discord health check failed");
            }
            return;
        }
        if (args.length != 0) throw new IllegalArgumentException("Usage: java -jar mini-discord.jar [health]");
        MiniDiscordServer.Settings settings = new MiniDiscordServer.Settings(
                env.getOrDefault("MINI_DISCORD_BIND", "127.0.0.1"),
                port,
                Integer.parseInt(env.getOrDefault("MINI_DISCORD_GATEWAY_PORT", "0")),
                MiniDiscordServer.Mode.valueOf(env.getOrDefault("MINI_DISCORD_MODE", "LOCAL_MINI_DISCORD")),
                Boolean.parseBoolean(env.getOrDefault("MINI_DISCORD_CONTAINER", "false")),
                Boolean.parseBoolean(env.getOrDefault("MINI_DISCORD_TEST_API", "false")),
                env.getOrDefault("MINI_DISCORD_TEST_SECRET", ""));
        try (MiniDiscordServer server = new MiniDiscordServer(
                settings,
                new TestClock(Instant.parse(env.getOrDefault("MINI_DISCORD_CLOCK", "2030-01-01T00:00:00Z"))))) {
            String fixture = env.getOrDefault("MINI_DISCORD_FIXTURE", "");
            if (!fixture.isEmpty()) server.seed(Fixtures.parse(Files.readString(Path.of(fixture))));
            String endpoint = env.getOrDefault("MINI_DISCORD_ENDPOINT_FILE", "");
            if (!endpoint.isEmpty()) {
                Path destination = Path.of(endpoint).toAbsolutePath();
                Path temporary = Files.createTempFile(destination.getParent(), ".mini-discord-endpoints-", ".json");
                try {
                    Files.writeString(
                            temporary, Json.MAPPER.writeValueAsString(server.environment("mini_discord", "discord")));
                    Files.move(
                            temporary,
                            destination,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    Files.deleteIfExists(temporary);
                }
            }
            CountDownLatch done = new CountDownLatch(1);
            Runtime.getRuntime().addShutdownHook(new Thread(done::countDown, "mini-discord-shutdown"));
            done.await();
        }
    }
}
