package com.cherrytest.common.process;

import com.cherrytest.common.rustcompat.RustOrdering;
import com.cherrytest.common.rustcompat.RustText;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.SortedMap;
import java.util.TreeMap;

public final class EnvFile {
    private EnvFile() {}

    public static void apply(Path workspaceRoot, String projectId) {
        read(workspaceRoot, projectId).forEach(ProcessEnvironment::set);
    }

    /** Variables from {@code .env.test} at the configuration root, or else from {@code <project>/.env.test}. */
    public static SortedMap<String, String> read(Path workspaceRoot, String projectId) {
        SortedMap<String, String> variables = new TreeMap<>(RustOrdering.STRING_ORDER);
        Path local = workspaceRoot.resolve(".env.test");
        Path nested = workspaceRoot.resolve(projectId).resolve(".env.test");
        Path envPath;
        if (Files.isRegularFile(local)) {
            envPath = local;
        } else if (Files.isRegularFile(nested)) {
            envPath = nested;
        } else {
            return variables;
        }
        String content;
        try {
            content = Files.readString(envPath, StandardCharsets.UTF_8);
        } catch (IOException | UncheckedIOException exception) {
            return variables;
        }
        for (String rawLine : RustText.lines(content)) {
            String line = RustText.trim(rawLine);
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int equals = line.indexOf('=');
            if (equals >= 0) {
                variables.put(RustText.trim(line.substring(0, equals)), RustText.trim(line.substring(equals + 1)));
            }
        }
        return variables;
    }
}
