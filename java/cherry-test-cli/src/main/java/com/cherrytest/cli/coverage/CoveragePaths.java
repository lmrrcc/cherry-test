package com.cherrytest.cli.coverage;

import com.cherrytest.cli.RunDir;
import com.cherrytest.cli.TestKind;
import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.common.rustcompat.RustIoError;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class CoveragePaths {
    private final Path projectDir;
    private final Path lcovDir;

    public CoveragePaths(RunDir runDir, String projectId) {
        this.projectDir = runDir.targetsDir(projectId);
        this.lcovDir = runDir.coverageDir(projectId);
    }

    public Path projectDir() {
        return projectDir;
    }

    public Path lcovDir() {
        return lcovDir;
    }

    public Path lcovFile(String packageName) {
        return lcovDir.resolve(packageName + ".lcov");
    }

    public Path kindLcovFile(String packageName, TestKind kind) {
        return lcovDir.resolve(packageName + "-" + kind.asStr() + ".lcov");
    }

    public void ensureDirs() {
        create(projectDir);
        create(lcovDir);
    }

    private static void create(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException exception) {
            throw AnyhowError.context("create " + directory, new AnyhowError(RustIoError.display(exception)));
        }
    }
}
