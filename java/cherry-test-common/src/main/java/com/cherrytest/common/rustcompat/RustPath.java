package com.cherrytest.common.rustcompat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

public final class RustPath {
    private RustPath() {}

    public static Path canonicalizeOrSelf(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException exception) {
            return path;
        }
    }

    public static Path canonicalize(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException exception) {
            throw new AnyhowError(RustIoError.display(exception), null);
        }
    }

    public static Path join(Path base, String relative) {
        if (relative.isEmpty()) {
            return base;
        }
        return base.resolve(relative);
    }

    public static @Nullable Path stripPrefix(Path path, Path base) {
        if (!path.startsWith(base)) {
            return null;
        }
        return base.relativize(path);
    }

    public static String lossy(Path path) {
        return path.toString();
    }

    public static boolean exists(Path path) {
        return Files.exists(path);
    }

    public static boolean isFile(Path path) {
        return Files.isRegularFile(path);
    }

    public static boolean isDir(Path path) {
        return Files.isDirectory(path);
    }

    public static boolean isSymlinkOrMissing(Path path) {
        return !Files.exists(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path);
    }

    public static @Nullable String extension(Path path) {
        Path fileName = path.getFileName();
        if (fileName == null) {
            return null;
        }
        String name = fileName.toString();
        int dot = name.lastIndexOf('.');
        if (dot <= 0) {
            return null;
        }
        return name.substring(dot + 1);
    }

    public static @Nullable String fileStem(Path path) {
        Path fileName = path.getFileName();
        if (fileName == null) {
            return null;
        }
        String name = fileName.toString();
        if (name.equals("..")) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    public static Path withExtension(Path path, String extension) {
        Path fileName = path.getFileName();
        if (fileName == null) {
            return path;
        }
        String stem = fileStem(path);
        String replaced =
                extension.isEmpty() ? (stem == null ? "" : stem) : (stem == null ? "" : stem) + "." + extension;
        Path parent = path.getParent();
        return parent == null ? Path.of(replaced) : parent.resolve(replaced);
    }
}
