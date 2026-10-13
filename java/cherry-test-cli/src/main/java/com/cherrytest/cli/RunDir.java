package com.cherrytest.cli;

import com.cherrytest.common.process.ProcessEnvironment;
import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.common.rustcompat.RustIoError;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public record RunDir(String id, Path root, Path reports, Path coverage) {
    public static RunDir create(Path workspaceRoot, Path runsBase, RunIdGenerator ids) {
        String id = ids.next();
        Path base = runsBase.isAbsolute() ? runsBase : workspaceRoot.resolve(runsBase);
        Path root = base.resolve(id);
        Path reports = root.resolve("reports");
        Path coverage = root.resolve("coverage");
        createDirectories(reports);
        createDirectories(coverage);
        return new RunDir(id, root, reports, coverage);
    }

    private static void createDirectories(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException exception) {
            throw AnyhowError.context("create " + directory, new AnyhowError(RustIoError.display(exception)));
        }
    }

    public Path targetsDir(String projectId) {
        return root.resolve("targets").resolve(projectId);
    }

    public Path coverageDir(String projectId) {
        return coverage.resolve(projectId);
    }

    public Path mergedLcov() {
        return coverage.resolve("merged.lcov");
    }

    public Path reportHtml() {
        return reports.resolve("index.html");
    }

    public Path envJson() {
        return root.resolve("env.json");
    }

    public void exportEnv() {
        ProcessEnvironment.set("CHERRY_TEST_RUN_DIR", root.toString());
        ProcessEnvironment.set("CHERRY_TEST_REPORT_DIR", reports.toString());
    }

    private static void deleteIgnoringErrors(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException removalIgnoredLikeRust) {
            return;
        }
    }

    public void updateLatestSymlink(Path workspaceRoot, Path runsBase) {
        Path base = runsBase.isAbsolute() ? runsBase : workspaceRoot.resolve(runsBase);
        Path latestParent = base.getParent() == null ? base : base.getParent();
        Path latest = latestParent.resolve("latest");
        createDirectories(latestParent);
        deleteIgnoringErrors(latest);
        try {
            Files.createSymbolicLink(latest, root);
        } catch (IOException exception) {
            throw AnyhowError.context(
                    "symlink " + latest + " -> " + root, new AnyhowError(RustIoError.display(exception)));
        }
    }
}
