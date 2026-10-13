package com.cherrytest.core;

import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.common.rustcompat.RustIoError;
import com.cherrytest.common.rustcompat.RustText;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class IntegrationRecords {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private IntegrationRecords() {}

    public static List<JsonNode> readAllRecords(Path reportDir) {
        Path integrationDir = reportDir.resolve("integration");
        if (!Files.exists(integrationDir)) {
            return List.of();
        }
        List<JsonNode> rows = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(integrationDir)) {
            for (Path entry : entries) {
                Path fileName = entry.getFileName();
                if (fileName == null || !fileName.toString().endsWith(".jsonl")) {
                    continue;
                }
                String content = Files.readString(entry, StandardCharsets.UTF_8);
                for (String line : RustText.lines(content)) {
                    if (RustText.trim(line).isEmpty()) {
                        continue;
                    }
                    rows.add(JSON.readTree(line));
                }
            }
        } catch (IOException exception) {
            throw new AnyhowError(RustIoError.display(exception), null);
        }
        return rows;
    }
}
