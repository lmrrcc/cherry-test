package com.cherrytest.parity;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class OktaPorts {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final PrintStream OUT = System.out;
    private static final String OKTA_SERVICE = "mini-rs-api-okta";

    private OktaPorts() {}

    public static void main(String[] arguments) throws IOException {
        Path cassette = Path.of(arguments[0]);
        Set<String> ports = new LinkedHashSet<>();
        for (String line : Files.readAllLines(cassette.resolve("index.jsonl"), StandardCharsets.UTF_8)) {
            JsonNode entry = JSON.readTree(line);
            List<String> argv = new ArrayList<>();
            entry.get("argv").forEach(argument -> argv.add(argument.stringValue()));
            if (Placeholders.isComposePort(entry.get("tool").stringValue(), argv) && argv.contains(OKTA_SERVICE)) {
                String mapped = Files.readString(
                                cassette.resolve("blobs")
                                        .resolve(entry.get("stdout").stringValue()),
                                StandardCharsets.UTF_8)
                        .strip();
                ports.add(mapped.substring(mapped.lastIndexOf(':') + 1));
            }
        }
        OUT.println(String.join(" ", ports));
    }
}
