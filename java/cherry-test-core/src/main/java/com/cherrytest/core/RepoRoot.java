package com.cherrytest.core;

import com.cherrytest.common.process.CommandOutput;
import com.cherrytest.common.process.ProcessEnvironment;
import com.cherrytest.common.process.SystemCommand;
import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.common.rustcompat.RustIoError;
import com.cherrytest.common.rustcompat.RustOrdering;
import com.cherrytest.common.rustcompat.RustText;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.Nullable;

public final class RepoRoot {
    private static final List<String> CONFIG_NAMES = List.of("cherry-test.yaml", "cherry-test.yml", "cherry-test.toml");
    private static final List<String> SKIP_NESTED_DIR_NAMES =
            List.of("target", "node_modules", ".git", ".idea", ".cursor", "dist", "build", "test");

    private RepoRoot() {}

    public static Path resolveRepoRoot(@Nullable Path overrideRoot) {
        List<Path> roots = resolveConfigRoots(overrideRoot, null);
        if (roots.isEmpty()) {
            throw new AnyhowError("no cherry-test config root found");
        }
        return roots.getFirst();
    }

    public static List<Path> resolveConfigRoots(@Nullable Path overrideRoot, @Nullable String filter) {
        if (overrideRoot != null) {
            Path root;
            try {
                root = overrideRoot.toAbsolutePath().toRealPath();
            } catch (IOException exception) {
                throw AnyhowError.context(
                        "resolve repo root " + overrideRoot, new AnyhowError(RustIoError.display(exception)));
            }
            if (!Files.isDirectory(root)) {
                throw new AnyhowError("repo root `" + root + "` is not a directory");
            }
            return List.of(root);
        }
        String environmentRoot = ProcessEnvironment.get("CHERRY_TEST_WORKSPACE_ROOT");
        if (environmentRoot != null && !environmentRoot.isEmpty()) {
            Path path = Path.of(environmentRoot);
            if (Files.isDirectory(path)) {
                return List.of(path);
            }
        }
        Path start = Path.of("").toAbsolutePath();
        Path walked = walkUpForConfig(start);
        if (walked != null) {
            return List.of(walked);
        }
        Path cargoWorkspace = tryCargoWorkspaceRoot();
        if (cargoWorkspace != null) {
            if (hasConfigFile(cargoWorkspace)) {
                return List.of(cargoWorkspace);
            }
            return selectNestedRoots(cargoWorkspace, findNestedConfigDirs(cargoWorkspace), filter);
        }
        try {
            return List.of(start.toRealPath());
        } catch (IOException exception) {
            throw AnyhowError.context("resolve repo root " + start, new AnyhowError(RustIoError.display(exception)));
        }
    }

    static List<Path> selectNestedRoots(Path cargoWorkspace, List<Path> nested, @Nullable String filter) {
        if (nested.isEmpty()) {
            return List.of(cargoWorkspace);
        }
        if (filter != null) {
            List<Path> filtered = nested.stream()
                    .filter(directory -> directory.getFileName() != null
                            && directory.getFileName().toString().equals(filter))
                    .toList();
            if (!filtered.isEmpty()) {
                return filtered;
            }
        }
        if (nested.size() == 1) {
            return List.of(nested.getFirst());
        }
        return List.copyOf(nested);
    }

    public static List<Path> findNestedConfigDirs(Path cargoWorkspace) {
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(cargoWorkspace)) {
            for (Path path : entries) {
                if (!Files.isDirectory(path)) {
                    continue;
                }
                String name =
                        path.getFileName() == null ? "" : path.getFileName().toString();
                if (SKIP_NESTED_DIR_NAMES.contains(name)) {
                    continue;
                }
                if (hasConfigFile(path)) {
                    out.add(path);
                }
            }
        } catch (IOException exception) {
            return out;
        }
        out.sort(Comparator.comparing(Path::toString, RustOrdering.PATH_ORDER));
        return out;
    }

    static @Nullable Path walkUpForConfig(Path start) {
        Path directory = start;
        while (directory != null) {
            if (hasConfigFile(directory)) {
                return directory;
            }
            directory = directory.getParent();
        }
        return null;
    }

    private static boolean hasConfigFile(Path directory) {
        return CONFIG_NAMES.stream().anyMatch(name -> Files.exists(directory.resolve(name)));
    }

    private static @Nullable Path tryCargoWorkspaceRoot() {
        CommandOutput output;
        try {
            output = SystemCommand.of("cargo")
                    .args("locate-project", "--workspace", "--message-format=plain")
                    .output();
        } catch (IOException exception) {
            return null;
        }
        if (!output.success()) {
            return null;
        }
        String manifest = RustText.trim(output.stdoutUtf8Strict());
        Path parent = Path.of(manifest).getParent();
        return parent;
    }
}
