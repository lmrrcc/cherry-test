package com.cherrytest.cli;

import com.cherrytest.common.process.ExecutableLookup;
import com.cherrytest.common.process.ProcessEnvironment;
import com.cherrytest.common.rustcompat.RustOrdering;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.Nullable;

public final class NodeRuntime {
    private NodeRuntime() {}

    public static List<String> vitestRunArgs(List<String> runnerArgs) {
        if (runnerArgs.size() >= 3
                && runnerArgs.get(0).equals("exec")
                && runnerArgs.get(1).equals("vitest")) {
            return new ArrayList<>(runnerArgs.subList(2, runnerArgs.size()));
        }
        if (!runnerArgs.isEmpty() && runnerArgs.getFirst().equals("vitest")) {
            return new ArrayList<>(runnerArgs.subList(1, runnerArgs.size()));
        }
        return new ArrayList<>(runnerArgs);
    }

    public static List<String> playwrightRunArgs(List<String> runnerArgs) {
        if (runnerArgs.size() >= 3
                && runnerArgs.get(0).equals("exec")
                && runnerArgs.get(1).equals("playwright")) {
            return new ArrayList<>(runnerArgs.subList(2, runnerArgs.size()));
        }
        if (!runnerArgs.isEmpty() && runnerArgs.getFirst().equals("playwright")) {
            return new ArrayList<>(runnerArgs.subList(1, runnerArgs.size()));
        }
        return new ArrayList<>(runnerArgs);
    }

    public static @Nullable Path resolveNodeBinary() {
        if (nodeWorks("node")) {
            return Path.of("node");
        }
        for (Path path : nodeBinaryCandidates()) {
            if (nodeWorks(path.toString())) {
                return path;
            }
        }
        return null;
    }

    public static String augmentedPath(Path cwd) {
        List<String> segments = new ArrayList<>();
        pushUnique(segments, cwd.resolve("node_modules/.bin").toString());
        for (Path candidate : nodeBinaryCandidates()) {
            Path parent = candidate.getParent();
            if (parent != null) {
                pushUnique(segments, parent.toString());
            }
        }
        String path = ProcessEnvironment.get("PATH");
        if (path != null) {
            pushUnique(segments, path);
        }
        return String.join(":", segments);
    }

    private static void pushUnique(List<String> segments, String value) {
        if (value.isEmpty()) {
            return;
        }
        if (!segments.contains(value)) {
            segments.add(value);
        }
    }

    private static boolean nodeWorks(String node) {
        String executable =
                node.contains("/") ? node : ExecutableLookup.resolve(node, ProcessEnvironment.get("PATH"), null);
        ProcessBuilder builder = new ProcessBuilder(executable, "--version");
        ProcessEnvironment.applyTo(builder.environment());
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.redirectInput(ProcessBuilder.Redirect.INHERIT);
        try {
            return builder.start().waitFor() == 0;
        } catch (IOException exception) {
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static List<Path> nodeBinaryCandidates() {
        List<Path> out = new ArrayList<>();
        String nvmBin = ProcessEnvironment.get("NVM_BIN");
        if (nvmBin != null) {
            out.add(Path.of(nvmBin).resolve("node"));
        }
        String home = ProcessEnvironment.get("HOME");
        if (home != null) {
            Path nvmRoot = Path.of(home).resolve(".nvm/versions/node");
            List<Path> versions = new ArrayList<>();
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(nvmRoot)) {
                entries.forEach(versions::add);
            } catch (IOException exception) {
                versions.clear();
            }
            versions.sort(Comparator.comparing(Path::toString, RustOrdering.PATH_ORDER));
            Collections.reverse(versions);
            for (Path version : versions) {
                out.add(version.resolve("bin/node"));
            }
        }
        out.add(Path.of("/opt/homebrew/bin/node"));
        out.add(Path.of("/usr/local/bin/node"));
        return out;
    }
}
