package mini.discord;

import java.util.Map;
import tools.jackson.databind.JsonNode;

record Reply(int status, JsonNode body, Map<String, String> headers, int delayMillis, boolean disconnect) {
    Reply {
        body = body.deepCopy();
        headers = Map.copyOf(headers);
    }

    static Reply of(int status, JsonNode body) {
        return new Reply(status, body, Map.of(), 0, false);
    }

    static Reply empty() {
        return of(204, Json.obj());
    }

    static Reply ok(JsonNode body) {
        return of(200, body);
    }
}
