package com.cherrytest.common.process;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

public final class ExecutableLookup {
    private ExecutableLookup() {}

    public static String resolve(String program, @Nullable String path, @Nullable Path workingDirectory) {
        if (program.contains("/")) {
            return program;
        }
        if (path == null) {
            return program;
        }
        for (String entry : path.split(File.pathSeparator, -1)) {
            Path directory = entry.isEmpty() ? Path.of(".") : Path.of(entry);
            Path candidate = directory.resolve(program);
            if (!candidate.isAbsolute() && workingDirectory != null) {
                candidate = workingDirectory.resolve(candidate);
            }
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }
        return program;
    }
}
