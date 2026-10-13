package com.cherrytest.cli;

import com.cherrytest.cli.nativeos.Terminal;
import com.cherrytest.common.process.ProcessEnvironment;
import com.cherrytest.common.process.SystemCommand;
import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.common.rustcompat.RustIoError;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import org.jspecify.annotations.Nullable;

final class Update {
    static final String DEFAULT_REPO = "https://github.com/lmrrcc/cherry-test";
    static final String DEFAULT_BRANCH = "main";
    static final String JAR_NAME = "cherry-test-java.jar";
    static final String LAUNCHER_NAME = "cherry-test-java";

    private Update() {}

    static int runUpdate(@Nullable String repoArgument, @Nullable String branchArgument, boolean quiet) {
        if (!commandAvailable("git")) {
            throw new AnyhowError("git not found; install Git to update cherry-test-java");
        }
        String repoEnvironment = ProcessEnvironment.get("CHERRY_TEST_REPO");
        String repo = repoArgument != null ? repoArgument : repoEnvironment != null ? repoEnvironment : DEFAULT_REPO;
        String branchEnvironment = ProcessEnvironment.get("CHERRY_TEST_VERSION");
        String branch = branchArgument != null
                ? branchArgument
                : branchEnvironment != null ? branchEnvironment : DEFAULT_BRANCH;
        Path installRoot = installRoot();
        if (!quiet) {
            Terminal.println("cherry-test update: " + repo + " @ " + branch);
        }
        Path source = repo.startsWith("/") || repo.startsWith("./") ? Path.of(repo) : clone(repo, branch);
        Path java = source.resolve("java");
        run(SystemCommand.of(java.resolve("mvnw").toString())
                .args(
                        "-q",
                        "-B",
                        "-f",
                        java.resolve("pom.xml").toString(),
                        "-pl",
                        "cherry-test-cli",
                        "-am",
                        "package",
                        "-DskipTests")
                .currentDir(java));
        Path jar = java.resolve("cherry-test-cli/target").resolve(JAR_NAME);
        Path library = installRoot.resolve("lib").resolve(JAR_NAME);
        Path launcher = installRoot.resolve("bin").resolve(LAUNCHER_NAME);
        try {
            Files.createDirectories(library.getParent());
            Files.createDirectories(launcher.getParent());
            Files.copy(jar, library, StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(launcher, launcherScript(library), StandardCharsets.UTF_8);
            Files.setPosixFilePermissions(launcher, PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (IOException exception) {
            throw new AnyhowError(RustIoError.display(exception), null);
        }
        if (!quiet) {
            Terminal.println("cherry-test updated: " + launcher);
        }
        return 0;
    }

    static String launcherScript(Path library) {
        return "#!/bin/sh\nexec java --enable-native-access=ALL-UNNAMED -jar \"" + library + "\" \"$@\"\n";
    }

    private static Path clone(String repo, String branch) {
        Path directory;
        try {
            directory = Files.createTempDirectory("cherry-test-java-update");
        } catch (IOException exception) {
            throw new AnyhowError(RustIoError.display(exception), null);
        }
        run(SystemCommand.of("git").args("clone", "--depth", "1", "--branch", branch, repo, directory.toString()));
        return directory;
    }

    private static void run(SystemCommand command) {
        int status;
        try {
            status = command.status();
        } catch (IOException exception) {
            throw AnyhowError.context(command.program(), new AnyhowError(RustIoError.display(exception)));
        }
        if (status != 0) {
            throw new AnyhowError("cherry-test update failed");
        }
    }

    static Path installRoot() {
        String installDir = ProcessEnvironment.get("INSTALL_DIR");
        if (installDir != null && !installDir.isEmpty()) {
            Path parent = Path.of(installDir).getParent();
            if (parent == null) {
                throw new AnyhowError("INSTALL_DIR has no parent");
            }
            return parent;
        }
        Path jar;
        try {
            jar = Path.of(Update.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
        } catch (URISyntaxException exception) {
            throw new AnyhowError("current jar location");
        }
        return installRootFromJar(jar);
    }

    static Path installRootFromJar(Path jar) {
        Path directory = jar.getParent();
        if (directory == null) {
            throw new AnyhowError("cherry-test-java jar has no parent directory");
        }
        Path name = directory.getFileName();
        if (name != null && name.toString().equals("lib")) {
            Path parent = directory.getParent();
            if (parent == null) {
                throw new AnyhowError("install lib directory has no parent");
            }
            return parent;
        }
        return directory;
    }

    private static boolean commandAvailable(String name) {
        try {
            return SystemCommand.of(name).arg("--version").output().success();
        } catch (IOException exception) {
            return false;
        }
    }
}
