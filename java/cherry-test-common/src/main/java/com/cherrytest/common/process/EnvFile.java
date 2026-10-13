package com.cherrytest.common.process;

import com.cherrytest.common.rustcompat.RustText;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class EnvFile {
    private EnvFile() {}

    public static void apply(Path workspaceRoot, String projectId) {
        Path local = workspaceRoot.resolve(".env.test");
        Path nested = workspaceRoot.resolve(projectId).resolve(".env.test");
        Path envPath;
        if (Files.isRegularFile(local)) {
            envPath = local;
        } else if (Files.isRegularFile(nested)) {
            envPath = nested;
        } else {
            return;
        }
        String content;
        try {
            content = Files.readString(envPath, StandardCharsets.UTF_8);
        } catch (IOException | UncheckedIOException exception) {
            return;
        }
        for (String rawLine : RustText.lines(content)) {
            String line = RustText.trim(rawLine);
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int equals = line.indexOf('=');
            if (equals >= 0) {
                ProcessEnvironment.set(
                        RustText.trim(line.substring(0, equals)), RustText.trim(line.substring(equals + 1)));
            }
        }
    }
}
