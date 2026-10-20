package mini.discord;

import static mini.discord.Json.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

final class Multipart {
    private Multipart() {}

    static JsonNode parse(String contentType, byte[] bytes) {
        var match =
                Pattern.compile("boundary=\"?([A-Za-z0-9-]{1,80})\"?(?:;|$)").matcher(contentType);
        if (!match.find()) throw ApiError.invalid("multipart boundary");
        String boundary = match.group(1);
        String raw = new String(bytes, StandardCharsets.ISO_8859_1);
        String[] parts = raw.split(Pattern.quote("--" + boundary), -1);
        if (parts.length < 3 || parts.length > 5 || !parts[0].isEmpty() || !parts[parts.length - 1].equals("--\r\n"))
            throw ApiError.invalid("multipart structure");
        ObjectNode payload = null;
        var uploads = new HashMap<Integer, ObjectNode>();
        for (int i = 1; i < parts.length - 1; i++) {
            String part = parts[i];
            if (!part.startsWith("\r\n") || !part.endsWith("\r\n")) throw ApiError.invalid("multipart part");
            int separator = part.indexOf("\r\n\r\n");
            if (separator < 0 || separator > 2048) throw ApiError.invalid("multipart headers");
            String headers = part.substring(2, separator);
            byte[] body = part.substring(separator + 4, part.length() - 2).getBytes(StandardCharsets.ISO_8859_1);
            var name = Pattern.compile("name=\"([^\"]+)\"").matcher(headers);
            if (!name.find()) throw ApiError.invalid("multipart name");
            if (name.group(1).equals("payload_json")) {
                if (payload != null) throw ApiError.invalid("duplicate payload");
                JsonNode parsed = MAPPER.readTree(body);
                if (!(parsed instanceof ObjectNode object)) throw ApiError.invalid("payload_json");
                payload = object;
            } else {
                var id = Pattern.compile("files\\[([01])]$").matcher(name.group(1));
                var filename = Pattern.compile("filename=\"([A-Za-z0-9][A-Za-z0-9._ -]{0,95})\"")
                        .matcher(headers);
                var type = Pattern.compile("(?im)^Content-Type: ([a-zA-Z0-9/+.-]+)$")
                        .matcher(headers);
                if (!id.matches() || !filename.find() || !type.find() || body.length == 0 || body.length > 262144)
                    throw ApiError.invalid("multipart file");
                int index = Integer.parseInt(id.group(1));
                if (uploads.putIfAbsent(
                                index,
                                obj(
                                        "id",
                                        index,
                                        "filename",
                                        filename.group(1),
                                        "size",
                                        body.length,
                                        "content_type",
                                        type.group(1),
                                        "sha256",
                                        digest(body)))
                        != null) throw ApiError.invalid("duplicate file");
            }
        }
        if (payload == null
                || uploads.isEmpty()
                || !payload.path("attachments").isArray()
                || payload.path("attachments").size() != uploads.size()) throw ApiError.invalid("attachments");
        List<ObjectNode> attachments = new ArrayList<>();
        for (var item : payload.path("attachments")) {
            int id = integer(item, "id", -1, 0, 1);
            var upload = uploads.remove(id);
            if (upload == null || !upload.path("filename").asString().equals(required(item, "filename")))
                throw ApiError.invalid("attachment metadata");
            attachments.add(upload);
        }
        payload.set("attachments", arr(attachments));
        return payload;
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
