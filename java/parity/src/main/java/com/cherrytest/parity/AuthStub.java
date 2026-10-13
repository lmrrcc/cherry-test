package com.cherrytest.parity;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

public final class AuthStub {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final PrintStream OUT = System.out;
    private static final long EXPIRES_IN = 3600;

    private AuthStub() {}

    public static void main(String[] arguments) throws IOException, InterruptedException {
        JsonNode recorded = JSON.readTree(Files.readString(Path.of(arguments[0]), StandardCharsets.UTF_8));
        String accessToken = recorded.properties().iterator().next().getValue().stringValue();
        ObjectNode token = JsonNodeFactory.instance.objectNode();
        token.put("access_token", accessToken);
        token.put("refresh_token", "parity-refresh");
        token.put("token_type", "Bearer");
        token.put("expires_in", EXPIRES_IN);
        byte[] body = JSON.writeValueAsBytes(token);
        List<HttpServer> servers = new ArrayList<>();
        for (int index = 1; index < arguments.length; index++) {
            HttpServer server = HttpServer.create(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), Integer.parseInt(arguments[index])), 0);
            server.createContext("/oauth2/default/v1/token", exchange -> respond(exchange, body));
            server.start();
            servers.add(server);
        }
        OUT.println("auth stub listening on " + (arguments.length - 1) + " port(s)");
        OUT.flush();
        new CountDownLatch(1).await();
        servers.forEach(server -> server.stop(0));
    }

    private static void respond(HttpExchange exchange, byte[] body) throws IOException {
        exchange.getRequestBody().readAllBytes();
        exchange.getResponseHeaders().add("content-type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }
}
