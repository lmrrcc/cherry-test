package mini.discord;

import static mini.discord.Json.*;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import mini.discord.DiscordState.Application;
import mini.discord.DiscordState.Guild;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

final class Gateway implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(Gateway.class.getName());
    private final MiniDiscordServer owner;
    private final ServerSocket listener;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<Connection> connections = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private long published;
    private int publishedIndex;

    Gateway(MiniDiscordServer owner, String address, int port) throws IOException {
        this.owner = owner;
        listener = new ServerSocket();
        listener.bind(new InetSocketAddress(InetAddress.getByName(address), port), 32);
        workers.execute(this::accept);
    }

    int port() {
        return listener.getLocalPort();
    }

    private void accept() {
        while (!closed.get()) {
            try {
                Socket socket = listener.accept();
                if (connections.size() >= 64) {
                    socket.close();
                    continue;
                }
                Connection connection = new Connection(socket);
                connections.add(connection);
                workers.execute(connection::run);
            } catch (IOException e) {
                if (!closed.get())
                    LOG.log(
                            System.Logger.Level.WARNING,
                            "Gateway accept failure: {0}",
                            e.getClass().getName());
            }
        }
    }

    void publish() {
        if (publishedIndex > owner.state.events.size()) publishedIndex = 0;
        while (publishedIndex < owner.state.events.size()) {
            ObjectNode event = owner.state.events.get(publishedIndex++);
            long sequence = event.path("s").asLong();
            if (sequence <= published) continue;
            for (Connection connection : connections) connection.event(event);
            published = sequence;
        }
    }

    void reset() {
        published = 0;
        publishedIndex = 0;
        connections.forEach(c -> c.closeCode = 4009);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                listener.close();
            } catch (IOException e) {
                LOG.log(
                        System.Logger.Level.DEBUG,
                        "Gateway listener close failure: {0}",
                        e.getClass().getName());
            }
            connections.forEach(Connection::closeSocket);
            workers.shutdownNow();
        }
    }

    private final class Connection {
        final Socket socket;
        final ArrayBlockingQueue<ObjectNode> queue = new ArrayBlockingQueue<>(8192);
        Application app;
        int intents;
        long sequence;
        long heartbeat = System.nanoTime();
        long identifiedDeadline = System.nanoTime() + 5_000_000_000L;
        volatile int closeCode;

        Connection(Socket socket) {
            this.socket = socket;
        }

        void event(ObjectNode event) {
            if (app == null) return;
            String type = event.path("t").asString();
            JsonNode data = event.path("d");
            boolean message =
                    type.equals("MESSAGE_CREATE") || type.equals("MESSAGE_UPDATE") || type.equals("MESSAGE_DELETE");
            if (message) {
                var channel = owner.state.channels.get(data.path("channel_id").asString());
                if (channel == null || (intents & 512) == 0) return;
                if (!PermissionEngine.has(
                        PermissionEngine.channel(owner.state, channel, app.bot), PermissionEngine.VIEW)) return;
                var metadata = ((tools.jackson.databind.node.ObjectNode) data).deepCopy();
                metadata.put("guild_id", channel.path("guild_id").asString());
                metadata.put("content", "");
                metadata.set("embeds", arr(List.of()));
                metadata.set("attachments", arr(List.of()));
                data = metadata;
            }
            String gid = type.equals("GUILD_CREATE") || type.equals("GUILD_UPDATE")
                    ? data.path("id").asString()
                    : data.path("guild_id").asString();
            Guild guild = owner.state.guilds.get(gid);
            if (guild == null || !guild.members.containsKey(app.bot)) return;
            boolean member = type.startsWith("GUILD_MEMBER_");
            boolean standard = type.startsWith("GUILD_ROLE_")
                    || type.startsWith("CHANNEL_")
                    || type.equals("GUILD_CREATE")
                    || type.equals("GUILD_UPDATE");
            if (member && ((intents & 2) == 0 || !app.membersIntent)) return;
            if (!member && !message && (!standard || (intents & 1) == 0)) return;
            if (type.startsWith("CHANNEL_")
                    && !PermissionEngine.has(
                            PermissionEngine.channel(owner.state, data, app.bot), PermissionEngine.VIEW)) return;
            enqueue(type, data);
        }

        void enqueue(String type, JsonNode data) {
            if (!queue.offer(obj("op", 0, "t", type, "s", ++sequence, "d", data.deepCopy()))) closeCode = 4008;
        }

        void run() {
            try (socket) {
                socket.setSoTimeout(5000);
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();
                handshake(in, out);
                socket.setSoTimeout(100);
                send(out, obj("op", 10, "d", obj("heartbeat_interval", 10000)));
                while (!closed.get() && !socket.isClosed()) {
                    if (closeCode != 0) {
                        closeFrame(out, closeCode);
                        break;
                    }
                    synchronized (owner.lock) {
                        if (app != null && (!app.enabled || app.revoked)) {
                            closeCode = 4004;
                            continue;
                        }
                        if (app != null && (intents & 2) != 0 && !app.membersIntent) {
                            closeCode = 4014;
                            continue;
                        }
                    }
                    ObjectNode pending;
                    while ((pending = queue.poll()) != null) send(out, pending);
                    if ((app == null && System.nanoTime() > identifiedDeadline)
                            || System.nanoTime() - heartbeat > 25_000_000_000L) {
                        closeFrame(out, 4009);
                        break;
                    }
                    int first;
                    try {
                        first = in.read();
                    } catch (SocketTimeoutException e) {
                        continue;
                    }
                    if (first < 0) break;
                    socket.setSoTimeout(2000);
                    Frame frame = readFrame(first, in);
                    socket.setSoTimeout(100);
                    if (frame.opcode == 8) {
                        closeFrame(out, 1000);
                        break;
                    }
                    if (frame.opcode == 9) {
                        frame(out, 10, frame.payload);
                        continue;
                    }
                    if (frame.opcode != 1) {
                        closeFrame(out, 4002);
                        break;
                    }
                    JsonNode payload = MAPPER.readTree(frame.payload);
                    int opcode = payload.path("op").asInt(-1);
                    if (opcode == 1) {
                        heartbeat = System.nanoTime();
                        send(out, obj("op", 11, "d", null));
                        continue;
                    }
                    if (opcode == 6) {
                        closeFrame(out, 4007);
                        break;
                    }
                    if (opcode != 2) {
                        closeFrame(out, 4001);
                        break;
                    }
                    if (app != null) {
                        closeFrame(out, 4005);
                        break;
                    }
                    synchronized (owner.lock) {
                        JsonNode identify = payload.path("d");
                        try {
                            app = owner.state.bot("Bot " + required(identify, "token"));
                        } catch (ApiError e) {
                            closeCode = 4004;
                            continue;
                        }
                        if (!identify.has("intents")
                                || !identify.path("intents").canConvertToInt()) {
                            closeCode = 4013;
                            continue;
                        }
                        intents = identify.path("intents").asInt();
                        if (intents < 0 || (intents & ~515) != 0) {
                            closeCode = 4013;
                            continue;
                        }
                        if ((intents & 2) != 0 && !app.membersIntent) {
                            closeCode = 4014;
                            continue;
                        }
                        if (!identify.path("properties").isObject()) {
                            closeCode = 4002;
                            continue;
                        }
                        if (identify.has("shard") && !identify.path("shard").equals(arr(List.of(0, 1)))) {
                            closeCode = 4010;
                            continue;
                        }
                        if (identify.path("compress").asBoolean()) {
                            closeCode = 4002;
                            continue;
                        }
                        List<Guild> guilds = owner.state.guilds.values().stream()
                                .filter(g -> g.members.containsKey(app.bot))
                                .toList();
                        enqueue(
                                "READY",
                                obj(
                                        "v",
                                        10,
                                        "user",
                                        owner.state.publicUser(app.bot),
                                        "guilds",
                                        guilds.stream()
                                                .map(g -> obj("id", g.id(), "unavailable", true))
                                                .toList(),
                                        "session_id",
                                        UUID.randomUUID().toString(),
                                        "resume_gateway_url",
                                        owner.gatewayUri().toString(),
                                        "application",
                                        app.publicJson(),
                                        "shard",
                                        List.of(0, 1)));
                        if ((intents & 1) != 0)
                            for (Guild guild : guilds) {
                                ObjectNode data = guild.json();
                                data.put("joined_at", guild.members.get(app.bot).joined);
                                data.put("large", guild.members.size() > 250);
                                data.put("unavailable", false);
                                data.put("member_count", guild.members.size());
                                data.set(
                                        "members",
                                        arr(guild.members.values().stream()
                                                .filter(m -> (intents & 2) != 0 || m.userId.equals(app.bot))
                                                .map(m -> m.json(owner.state))
                                                .toList()));
                                data.set(
                                        "channels",
                                        arr(owner.state.channels.values().stream()
                                                .filter(c -> c.path("guild_id")
                                                                .asString()
                                                                .equals(guild.id())
                                                        && !ChannelApi.isThread(c))
                                                .map(ChannelApi::publicJson)
                                                .toList()));
                                data.set("threads", arr(List.of()));
                                data.set("presences", arr(List.of()));
                                data.set("voice_states", arr(List.of()));
                                data.set("guild_scheduled_events", arr(guild.scheduled.values()));
                                enqueue("GUILD_CREATE", data);
                            }
                    }
                }
            } catch (IOException | RuntimeException e) {
                if (!closed.get())
                    LOG.log(
                            System.Logger.Level.DEBUG,
                            "Gateway connection ended: {0}",
                            e.getClass().getSimpleName());
            } finally {
                connections.remove(this);
            }
        }

        void closeSocket() {
            try {
                socket.close();
            } catch (IOException e) {
                LOG.log(
                        System.Logger.Level.DEBUG,
                        "Gateway socket close failure: {0}",
                        e.getClass().getName());
            }
        }
    }

    private static void handshake(InputStream in, OutputStream out) throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        int rolling = 0;
        while (header.size() < 16384) {
            int b = in.read();
            if (b < 0) throw new EOFException();
            header.write(b);
            rolling = (rolling << 8) | b;
            if (rolling == 0x0d0a0d0a) break;
        }
        String[] lines = header.toString(StandardCharsets.US_ASCII).split("\r\n", -1);
        if (lines.length == 0 || !lines[0].startsWith("GET /")) throw new IOException("Invalid websocket request");
        String[] request = lines[0].split(" ", -1);
        java.net.URI uri = java.net.URI.create(request[1]);
        JsonNode query = form(uri.getRawQuery());
        if (!str(query, "v", "10").equals("10")
                || !str(query, "encoding", "json").equals("json")
                || query.has("compress")) throw new IOException("Unsupported Gateway encoding or version");
        Map<String, String> values = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            String[] p = lines[i].split(":", 2);
            if (p.length == 2) values.put(p[0].toLowerCase(java.util.Locale.ROOT), p[1].trim());
        }
        String key = values.getOrDefault("sec-websocket-key", "");
        if (!values.getOrDefault("upgrade", "").equalsIgnoreCase("websocket")
                || !values.getOrDefault("sec-websocket-version", "").equals("13")
                || values.containsKey("origin")
                || Base64.getDecoder().decode(key).length != 16) throw new IOException("Invalid websocket upgrade");
        String accept;
        try {
            accept = Base64.getEncoder()
                    .encodeToString(MessageDigest.getInstance("SHA-1")
                            .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
                                    .getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("Websocket handshake unavailable", e);
        }
        out.write(
                ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "
                                + accept + "\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private static final class Frame {
        final int opcode;
        final byte[] payload;

        Frame(int opcode, byte[] payload) {
            this.opcode = opcode;
            this.payload = payload;
        }
    }

    private static Frame readFrame(int first, InputStream in) throws IOException {
        if ((first & 0x80) == 0 || (first & 0x70) != 0)
            throw new IOException("Fragmentation and compression unsupported");
        int second = in.read();
        if (second < 0 || (second & 0x80) == 0) throw new IOException("Client frames must be masked");
        long length = second & 0x7f;
        if (length == 126) length = ByteBuffer.wrap(exact(in, 2)).getShort() & 0xffff;
        if (length == 127) length = ByteBuffer.wrap(exact(in, 8)).getLong();
        if (length < 0 || length > 65536) throw new IOException("Gateway payload too large");
        byte[] mask = exact(in, 4), payload = exact(in, (int) length);
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (payload[i] ^ mask[i % 4]);
        return new Frame(first & 15, payload);
    }

    private static byte[] exact(InputStream in, int count) throws IOException {
        byte[] data = in.readNBytes(count);
        if (data.length != count) throw new EOFException();
        return data;
    }

    private static void send(OutputStream out, JsonNode data) throws IOException {
        frame(out, 1, data.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void closeFrame(OutputStream out, int code) throws IOException {
        frame(out, 8, ByteBuffer.allocate(2).putShort((short) code).array());
    }

    private static void frame(OutputStream out, int opcode, byte[] payload) throws IOException {
        out.write(0x80 | opcode);
        if (payload.length < 126) out.write(payload.length);
        else if (payload.length <= 65535) {
            out.write(126);
            out.write(ByteBuffer.allocate(2).putShort((short) payload.length).array());
        } else {
            out.write(127);
            out.write(ByteBuffer.allocate(8).putLong(payload.length).array());
        }
        out.write(payload);
        out.flush();
    }
}
