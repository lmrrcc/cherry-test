package com.cherrytest.parity;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

final class Snapshot {
    private final Path root;
    private final Map<String, Entry> entries;

    private Snapshot(Path root, Map<String, Entry> entries) {
        this.root = root;
        this.entries = entries;
    }

    sealed interface Entry permits Directory, RegularFile, Link {}

    record Directory() implements Entry {}

    record RegularFile(String signature, long inode) implements Entry {}

    record Link(String target) implements Entry {}

    static Snapshot of(@Nullable Path root, @Nullable Path runDir) throws IOException {
        Map<String, Entry> entries = new TreeMap<>();
        if (root == null || !Files.isDirectory(root)) {
            return new Snapshot(Path.of(""), entries);
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                if (excluded(root, runDir, directory)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (!directory.equals(root)) {
                    entries.put(root.relativize(directory).toString(), new Directory());
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                String relative = root.relativize(file).toString();
                if (attributes.isSymbolicLink()) {
                    entries.put(relative, new Link(Files.readSymbolicLink(file).toString()));
                } else if (attributes.isRegularFile()) {
                    entries.put(
                            relative,
                            new RegularFile(
                                    attributes.size() + ":"
                                            + attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS),
                                    inode(file)));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return new Snapshot(root, entries);
    }

    static long inode(Path file) throws IOException {
        return ((Number) Files.getAttribute(file, "unix:ino")).longValue();
    }

    static boolean excluded(Path root, @Nullable Path runDir, Path directory) {
        if (runDir != null && runDir.startsWith(directory)) {
            return false;
        }
        if (runDir != null && directory.startsWith(runDir)) {
            Path relative = runDir.relativize(directory);
            for (int index = 4; index < relative.getNameCount(); index++) {
                if (relative.getName(index).toString().equals("target")) {
                    return true;
                }
            }
            return false;
        }
        Path name = directory.getFileName();
        if (name != null && Set.of("node_modules", ".git").contains(name.toString())) {
            return true;
        }
        Path relative = root.relativize(directory);
        return relative.getNameCount() > 0
                && !relative.toString().isEmpty()
                && relative.getName(0).toString().equals("target");
    }

    Path root() {
        return root;
    }

    List<Map.Entry<String, Entry>> changedSince(Snapshot before, Set<Long> skippedInodes) {
        List<Map.Entry<String, Entry>> changed = new ArrayList<>();
        entries.forEach((path, entry) -> {
            if (entry instanceof RegularFile file && skippedInodes.contains(file.inode())) {
                return;
            }
            if (!entry.equals(before.entries.get(path))) {
                changed.add(Map.entry(path, entry));
            }
        });
        return changed;
    }

    List<String> deletedIn(Snapshot after) {
        List<String> deleted = new ArrayList<>();
        for (String path : entries.keySet()) {
            if (!after.entries.containsKey(path)) {
                deleted.add(path);
            }
        }
        return deleted.reversed();
    }
}
