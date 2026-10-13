package com.cherrytest.cli.coverage;

import com.cherrytest.common.ignore.PathIgnore;
import com.cherrytest.common.rustcompat.RustText;
import com.cherrytest.core.TestItConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class CoverageIgnore {
    private final List<String> patterns;

    private CoverageIgnore(List<String> patterns) {
        this.patterns = List.copyOf(patterns);
    }

    public static CoverageIgnore load(Path workspaceRoot, TestItConfig config) {
        Path path = workspaceRoot.resolve(config.defaults().coverageIgnore());
        return new CoverageIgnore(Files.exists(path) ? loadPatterns(path) : List.of());
    }

    public static CoverageIgnore fromPatterns(List<String> patterns) {
        return new CoverageIgnore(patterns);
    }

    public boolean isIgnored(String path) {
        return PathIgnore.isPathIgnored(path, patterns);
    }

    public List<String> filterPaths(Collection<String> paths) {
        return paths.stream().filter(path -> !isIgnored(path)).toList();
    }

    public List<FileCoverage> filterFileCoverage(List<FileCoverage> files) {
        return files.stream().filter(file -> !isIgnored(file.path())).toList();
    }

    public List<FileLineCoverage> filterLineCoverage(List<FileLineCoverage> files) {
        return files.stream().filter(file -> !isIgnored(file.path())).toList();
    }

    static List<String> loadPatterns(Path path) {
        String content;
        try {
            content = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            content = "";
        }
        List<String> patterns = new ArrayList<>();
        for (String line : RustText.lines(content)) {
            String trimmed = RustText.trim(line);
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                patterns.add(trimmed);
            }
        }
        return patterns;
    }
}
