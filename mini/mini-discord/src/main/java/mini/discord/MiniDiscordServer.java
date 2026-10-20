package mini.discord;

import static mini.discord.Json.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

public final class MiniDiscordServer implements AutoCloseable {
    public enum Mode {
        LOCAL_MINI_DISCORD,
        REAL_DISCORD_TEST,
        PRODUCTION
    }

    public record Settings(
            String address, int port, int gatewayPort, Mode mode, boolean container, boolean testApi, String secret) {
        public Settings {
            if (mode != Mode.LOCAL_MINI_DISCORD)
                throw new IllegalArgumentException("Only LOCAL_MINI_DISCORD is permitted");
            if (port < 0 || port > 65535 || gatewayPort < 0 || gatewayPort > 65535)
                throw new IllegalArgumentException("Invalid port");
            if (!List.of("127.0.0.1", "::1", "0.0.0.0").contains(address) || (address.equals("0.0.0.0") && !container))
                throw new IllegalArgumentException("Loopback or isolated container binding required");
            if (testApi && (secret == null || secret.length() < 32))
                throw new IllegalArgumentException("Dedicated test control secret of at least 32 characters required");
        }

        public static Settings local() {
            return new Settings(
                    "127.0.0.1",
                    0,
                    0,
                    Mode.LOCAL_MINI_DISCORD,
                    false,
                    true,
                    UUID.randomUUID().toString());
        }
    }

    private static final System.Logger LOG = System.getLogger(MiniDiscordServer.class.getName());
    final Object lock = new Object();
    final DiscordState state;
    final Faults faults;
    final Evidence evidence = new Evidence();
    private final OAuth oauth;
    private final GuildApi guildApi;
    private final ChannelApi channelApi;
    private final Administration administration;
    private final TestApi testApi;
    private final Settings settings;
    private final HttpServer http;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore capacity = new Semaphore(64);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Gateway gateway;

    public MiniDiscordServer(Settings settings, Clock clock) throws IOException {
        for (String key : List.of("APP_ENV", "ENVIRONMENT", "SPRING_PROFILES_ACTIVE", "NODE_ENV")) {
            if (System.getenv().getOrDefault(key, "").toLowerCase(Locale.ROOT).matches(".*\\bprod(?:uction)?\\b.*"))
                throw new IllegalArgumentException("Production startup forbidden");
        }
        this.settings = settings;
        state = new DiscordState(clock);
        faults = new Faults(clock);
        oauth = new OAuth(state);
        administration = new Administration(state);
        channelApi = new ChannelApi(state, administration);
        guildApi = new GuildApi(state, oauth, channelApi, administration);
        testApi = new TestApi(this, state, oauth, administration);
        http = HttpServer.create(new InetSocketAddress(InetAddress.getByName(settings.address), settings.port), 64);
        try {
            gateway = new Gateway(this, settings.address, settings.gatewayPort);
        } catch (IOException e) {
            http.stop(0);
            executor.close();
            throw e;
        }
        http.setExecutor(executor);
        http.createContext("/", this::handle);
        http.start();
    }

    public static MiniDiscordServer local() throws IOException {
        return new MiniDiscordServer(Settings.local(), TestClock.deterministic());
    }

    public URI baseUri() {
        return URI.create("http://" + (settings.address.equals("::1") ? "[::1]" : "127.0.0.1") + ":"
                + http.getAddress().getPort());
    }

    public URI gatewayUri() {
        return URI.create("ws://" + (settings.address.equals("::1") ? "[::1]" : "127.0.0.1") + ":" + gateway.port());
    }

    public String testSecret() {
        return settings.secret;
    }

    public Map<String, String> environment(String project, String service) {
        if (!project.matches("[A-Za-z][A-Za-z0-9_]*") || !service.matches("[A-Za-z][A-Za-z0-9_]*"))
            throw new IllegalArgumentException("Invalid service identity");
        String prefix = "CHERRY_TEST_" + project.toUpperCase(Locale.ROOT) + "_" + service.toUpperCase(Locale.ROOT);
        return Map.of(
                prefix + "_URL",
                baseUri().toString(),
                prefix + "_HOST",
                baseUri().getHost(),
                prefix + "_PORT",
                Integer.toString(http.getAddress().getPort()),
                "DISCORD_API_BASE_URL",
                baseUri() + "/api/v10",
                "DISCORD_OAUTH_BASE_URL",
                baseUri() + "/oauth2",
                "DISCORD_GATEWAY_URL",
                gatewayUri().toString(),
                "DISCORD_TEST_CONTROL_URL",
                baseUri() + "/_test/");
    }

    public ObjectNode seed(JsonNode recipe) {
        synchronized (lock) {
            return reset(recipe);
        }
    }

    ObjectNode reset(JsonNode recipe) {
        DiscordState candidate = new DiscordState(state.clock);
        ObjectNode result = recipe == null ? obj() : Fixtures.seed(candidate, recipe);
        state.users.clear();
        state.users.putAll(candidate.users);
        state.applications.clear();
        state.applications.putAll(candidate.applications);
        state.guilds.clear();
        state.guilds.putAll(candidate.guilds);
        state.channels.clear();
        state.channels.putAll(candidate.channels);
        state.invites.clear();
        state.invites.putAll(candidate.invites);
        state.messages.clear();
        state.messages.putAll(candidate.messages);
        state.syncedChannels.clear();
        state.syncedChannels.addAll(candidate.syncedChannels);
        state.events.clear();
        state.events.addAll(candidate.events);
        state.sequence = candidate.sequence;
        state.eventSequence = candidate.eventSequence;
        state.generation++;
        oauth.requests.clear();
        oauth.codes.clear();
        oauth.grants.clear();
        faults.clear();
        evidence.clear();
        gateway.reset();
        return result;
    }

    public List<ObjectNode> requests() {
        return evidence.requests();
    }

    public List<ObjectNode> events() {
        synchronized (lock) {
            return state.events.stream().map(ObjectNode::deepCopy).toList();
        }
    }

    public void writeReport(Path directory, String fullName, boolean passed, String reason) throws IOException {
        evidence.report(
                directory,
                fullName,
                passed,
                reason.replace(settings.secret, "[REDACTED]"),
                baseUri().toString(),
                events());
    }

    private void handle(HttpExchange exchange) throws IOException {
        if (!capacity.tryAcquire()) {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
            return;
        }
        long started = System.nanoTime();
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        JsonNode input = obj(), query = obj();
        Reply reply;
        long generation;
        synchronized (lock) {
            generation = state.generation;
        }
        String actorApp = "", actorBot = "", actorUser = "", channelGuild = "";
        try {
            try {
                if (exchange.getRequestURI().toString().length() > 16384) throw new ApiError(414, 0, "URI Too Long");
                if (exchange.getRequestHeaders().containsKey("Origin"))
                    throw new ApiError(403, 50001, "Browser origins are not accepted");
                byte[] body = exchange.getRequestBody().readNBytes(1048577);
                if (body.length > 1048576) throw new ApiError(413, 40005, "Request entity too large");
                String contentType =
                        header(exchange, "Content-Type").split(";", 2)[0].trim();
                boolean tokenPath = path.matches("/api(?:/v10)?/oauth2/token(?:/revoke)?");
                if (tokenPath && !contentType.equals("application/x-www-form-urlencoded"))
                    throw new ApiError("invalid_request");
                query = form(exchange.getRequestURI().getRawQuery());
                if (body.length > 0) {
                    if (contentType.equals("application/x-www-form-urlencoded"))
                        input = form(new String(body, StandardCharsets.UTF_8));
                    else if (contentType.equals("application/json")) {
                        input = MAPPER.readTree(body);
                        if (path.endsWith("/messages")
                                && input.has("attachments")
                                && !input.path("attachments").isEmpty())
                            throw ApiError.invalid("attachments require multipart files");
                    } else if (contentType.equals("multipart/form-data")
                            && path.matches("/api/v10/channels/[0-9]+/messages"))
                        input = Multipart.parse(header(exchange, "Content-Type"), body);
                    else throw new ApiError(415, 0, "Unsupported Media Type");
                }
                String authorization = header(exchange, "Authorization");
                synchronized (lock) {
                    if (path.startsWith("/api/v10/channels/")) {
                        JsonNode channel = state.channels.get(
                                path.substring("/api/v10/channels/".length()).split("/", 2)[0]);
                        if (channel != null)
                            channelGuild = channel.path("guild_id").asString();
                    }
                    if (authorization.startsWith("Bearer ")) {
                        OAuth.Grant grant = oauth.grants.get(authorization.substring(7));
                        if (grant != null) {
                            actorApp = grant.app;
                            actorUser = grant.user;
                        }
                    }
                    if (authorization.startsWith("Bot mini_discord_bot_")) {
                        DiscordState.Application actor = state.applications.values().stream()
                                .filter(a -> authorization.equals("Bot " + a.token))
                                .findFirst()
                                .orElse(null);
                        if (actor != null) {
                            actorApp = actor.id;
                            actorBot = actor.bot;
                        }
                    }
                    reply = dispatch(exchange, path, method, input, query, authorization);
                    gateway.publish();
                }
            } catch (ApiError error) {
                reply = Reply.of(error.status, error.body());
            } catch (JacksonException | IllegalArgumentException | java.time.DateTimeException error) {
                reply = Reply.of(400, ApiError.invalid("request").body());
            } catch (RuntimeException error) {
                LOG.log(
                        System.Logger.Level.ERROR,
                        "Mini-Discord request failure: {0}",
                        error.getClass().getName());
                reply = Reply.of(500, obj("code", 0, "message", "Internal Server Error"));
            }
            if (reply.delayMillis() > 0) {
                try {
                    Thread.sleep(reply.delayMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            String[] parts = path.split("/", -1);
            String guildId = channelGuild, targetUser = actorUser;
            for (int i = 0; i + 1 < parts.length; i++) {
                if (parts[i].equals("guilds")) guildId = parts[i + 1];
                if (parts[i].equals("members") || parts[i].equals("bans")) targetUser = parts[i + 1];
            }
            ObjectNode record = obj(
                    "timestamp",
                    state.clock.instant().toString(),
                    "method",
                    method,
                    "path",
                    path,
                    "guildId",
                    guildId,
                    "actorApplicationId",
                    actorApp,
                    "actorBotId",
                    actorBot,
                    "targetUserId",
                    targetUser,
                    "operation",
                    method + " " + path,
                    "result",
                    reply.disconnect() ? "disconnected" : reply.status() < 400 ? "success" : "error",
                    "httpStatus",
                    reply.status(),
                    "discordErrorCode",
                    reply.body().path("code"),
                    "durationMs",
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                    "correlationId",
                    header(exchange, "X-Cherry-Test-Id").isEmpty()
                            ? UUID.randomUUID().toString()
                            : header(exchange, "X-Cherry-Test-Id"),
                    "query",
                    query,
                    "body",
                    input,
                    "response",
                    reply.body());
            synchronized (lock) {
                if (state.generation == generation && !path.equals("/_test/requests") && !path.equals("/health"))
                    evidence.add(record);
            }
            if (reply.disconnect()) return;
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            reply.headers().forEach((key, value) -> {
                if (!key.startsWith("X-Mini-Discord-"))
                    exchange.getResponseHeaders().set(key, value);
            });
            byte[] bytes = reply.body().toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(reply.status(), reply.status() == 204 ? -1 : bytes.length);
            if (reply.status() != 204) exchange.getResponseBody().write(bytes);
        } catch (IOException error) {
            LOG.log(
                    System.Logger.Level.DEBUG,
                    "Mini-Discord client disconnected: {0}",
                    error.getClass().getSimpleName());
        } finally {
            exchange.close();
            capacity.release();
        }
    }

    private Reply dispatch(
            HttpExchange exchange, String path, String method, JsonNode input, JsonNode query, String authorization) {
        if (path.equals("/health") && method.equals("GET"))
            return Reply.ok(obj("status", "ok", "service", "mini-discord", "mode", settings.mode.name()));
        if (path.startsWith("/_test/")) {
            if (!settings.testApi) throw ApiError.unsupported();
            if (!MessageDigest.isEqual(
                    settings.secret.getBytes(StandardCharsets.UTF_8),
                    header(exchange, "X-Mini-Discord-Secret").getBytes(StandardCharsets.UTF_8)))
                throw new ApiError(403, 50001, "Missing Access");
            return testApi.route(method, path, input);
        }
        Faults.Result fault = faults.check(method, path, authorization);
        if (fault.fault() != null) return fault.fault();
        if (fault.headers().containsKey("X-Mini-Discord-Fault")) {
            String[] memberPath = path.replaceFirst("^/api/v10/", "").split("/", -1);
            if (!method.equals("PUT")
                    || memberPath.length != 4
                    || !memberPath[0].equals("guilds")
                    || !memberPath[2].equals("members"))
                throw ApiError.invalid("ALREADY_MEMBER requires a member PUT route");
            DiscordState.Guild guild = state.guild(memberPath[1]);
            state.installed(authorization, guild);
            if (!guild.members.containsKey(memberPath[3]))
                throw ApiError.invalid("ALREADY_MEMBER requires existing membership");
        }
        Reply result = publicRoute(exchange, path, method, input, query, authorization);
        Map<String, String> headers = new LinkedHashMap<>(result.headers());
        headers.putAll(fault.headers());
        return new Reply(
                result.status(),
                result.body(),
                headers,
                Integer.parseInt(headers.getOrDefault("X-Mini-Discord-Delay", "0")),
                false);
    }

    private Reply publicRoute(
            HttpExchange exchange, String path, String method, JsonNode input, JsonNode query, String authorization) {
        if (path.equals("/oauth2/authorize") && method.equals("GET")) {
            OAuth.Request request = oauth.authorize(query);
            return new Reply(
                    302, obj("local_consent", true), Map.of("Location", "/_test/consent/" + request.id()), 0, false);
        }
        String apiPath = path.replaceFirst("^/api/oauth2/", "/api/v10/oauth2/");
        if (apiPath.equals("/api/v10/oauth2/token") && method.equals("POST"))
            return Reply.ok(oauth.token(input, authorization));
        if (apiPath.equals("/api/v10/oauth2/token/revoke") && method.equals("POST")) {
            oauth.revoke(input, authorization);
            return Reply.ok(obj());
        }
        if (apiPath.equals("/api/v10/users/@me") && method.equals("GET")) return Reply.ok(oauth.me(authorization));
        if (apiPath.equals("/api/v10/gateway") && method.equals("GET"))
            return Reply.ok(obj("url", gatewayUrl(exchange)));
        if (apiPath.equals("/api/v10/gateway/bot") && method.equals("GET")) {
            state.bot(authorization);
            return Reply.ok(obj(
                    "url",
                    gatewayUrl(exchange),
                    "shards",
                    1,
                    "session_start_limit",
                    obj("total", 1000, "remaining", 1000, "reset_after", 86400000, "max_concurrency", 1)));
        }
        if (!apiPath.startsWith("/api/v10/")) throw ApiError.unsupported();
        String[] parts = apiPath.substring(9).split("/", -1);
        String reason = header(exchange, "X-Audit-Log-Reason");
        if (!reason.isEmpty()) {
            reason = java.net.URLDecoder.decode(reason, StandardCharsets.UTF_8);
            if (reason.length() > 512) throw ApiError.invalid("audit reason");
        }
        if (parts.length >= 2 && parts[0].equals("guilds"))
            return guildApi.route(method, parts, input, query, authorization, reason);
        if (parts.length >= 2 && parts[0].equals("channels"))
            return channelApi.route(method, parts, input, query, authorization, reason);
        if (parts.length >= 2 && parts[0].equals("invites"))
            return administration.inviteRoute(method, parts, authorization, reason);
        if (parts.length == 2 && parts[0].equals("users") && method.equals("GET")) {
            state.bot(authorization);
            return Reply.ok(state.publicUser(parts[1]));
        }
        throw ApiError.unsupported();
    }

    private String gatewayUrl(HttpExchange exchange) {
        if (!settings.container) return gatewayUri().toString();
        String host = header(exchange, "Host").split(":", 2)[0];
        if (!host.matches(
                "localhost|mini-discord|127(?:\\.[0-9]{1,3}){3}|10(?:\\.[0-9]{1,3}){3}|192\\.168(?:\\.[0-9]{1,3}){2}|172\\.(?:1[6-9]|2[0-9]|3[01])(?:\\.[0-9]{1,3}){2}"))
            throw ApiError.invalid("Local Gateway Host required");
        return "ws://" + host + ":" + gateway.port();
    }

    private static String header(HttpExchange exchange, String key) {
        String value = exchange.getRequestHeaders().getFirst(key);
        return value == null ? "" : value;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            http.stop(0);
            gateway.close();
            executor.shutdownNow();
        }
    }
}
