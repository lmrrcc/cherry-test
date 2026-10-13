package com.cherrytest.common.ignore;

import java.util.List;
import org.jspecify.annotations.Nullable;

public final class PathIgnore {
    private PathIgnore() {}

    public static String normalizePath(String path) {
        return path.replace('\\', '/');
    }

    public static boolean isPathIgnored(String path, List<String> patterns) {
        String normalized = normalizePath(path);
        return patterns.stream().anyMatch(pattern -> matchesPattern(normalized, pattern));
    }

    private static boolean matchesPattern(String path, String rawPattern) {
        String pattern = normalizePath(rawPattern);
        String directory = directoryGlob(pattern);
        if (directory != null) {
            return pathContainsDir(path, directory);
        }
        if (pattern.contains("*")) {
            return globMatch(path, pattern);
        }
        return path.equals(pattern) || path.endsWith("/" + pattern);
    }

    private static @Nullable String directoryGlob(String pattern) {
        if (!pattern.startsWith("**/")) {
            return null;
        }
        String rest = pattern.substring(3);
        if (!rest.endsWith("/**")) {
            return null;
        }
        String directory = rest.substring(0, rest.length() - 3);
        return directory.contains("*") ? null : directory;
    }

    private static boolean pathContainsDir(String path, String directory) {
        return path.contains("/" + directory + "/") || path.startsWith(directory + "/");
    }

    private static boolean globMatch(String path, String pattern) {
        if (pattern.startsWith("**/")) {
            String suffix = pattern.substring(3);
            if (suffix.contains("*")) {
                List<String> parts = splitOnStar(suffix);
                if (parts.size() == 2 && parts.get(0).isEmpty() && parts.get(1).isEmpty()) {
                    return true;
                }
                if (parts.size() == 2 && parts.get(0).isEmpty()) {
                    return path.endsWith(parts.get(1));
                }
                return path.endsWith(trimStartStars(suffix))
                        || splitOnSlash(path).stream().anyMatch(segment -> segmentMatch(segment, suffix));
            }
            return path.endsWith("/" + suffix) || path.equals(suffix) || path.endsWith(suffix);
        }
        if (pattern.contains("*")) {
            List<String> parts = splitOnStar(pattern);
            if (parts.size() == 2) {
                return path.startsWith(parts.get(0)) && path.endsWith(parts.get(1));
            }
        }
        return path.equals(pattern);
    }

    private static boolean segmentMatch(String segment, String pattern) {
        if (!pattern.contains("*")) {
            return segment.equals(pattern);
        }
        List<String> parts = splitOnStar(pattern);
        if (parts.size() == 2 && parts.get(0).isEmpty() && parts.get(1).isEmpty()) {
            return true;
        }
        if (parts.size() == 2 && parts.get(0).isEmpty()) {
            return segment.endsWith(parts.get(1));
        }
        if (parts.size() == 2 && parts.get(1).isEmpty()) {
            return segment.startsWith(parts.get(0));
        }
        return segment.equals(pattern);
    }

    private static String trimStartStars(String value) {
        int index = 0;
        while (index < value.length() && value.charAt(index) == '*') {
            index++;
        }
        return value.substring(index);
    }

    private static List<String> splitOnStar(String value) {
        return List.of(value.split("\\*", -1));
    }

    private static List<String> splitOnSlash(String value) {
        return List.of(value.split("/", -1));
    }
}
