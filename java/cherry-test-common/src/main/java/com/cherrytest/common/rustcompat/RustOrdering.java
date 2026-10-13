package com.cherrytest.common.rustcompat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class RustOrdering {
    public static final Comparator<String> STRING_ORDER = RustOrdering::compareStrings;
    public static final Comparator<String> PATH_ORDER = RustOrdering::comparePaths;

    private RustOrdering() {}

    public static int compareStrings(String left, String right) {
        int leftIndex = 0;
        int rightIndex = 0;
        while (leftIndex < left.length() && rightIndex < right.length()) {
            int leftCodePoint = left.codePointAt(leftIndex);
            int rightCodePoint = right.codePointAt(rightIndex);
            if (leftCodePoint != rightCodePoint) {
                return Integer.compare(leftCodePoint, rightCodePoint);
            }
            leftIndex += Character.charCount(leftCodePoint);
            rightIndex += Character.charCount(rightCodePoint);
        }
        return Boolean.compare(leftIndex < left.length(), rightIndex < right.length());
    }

    public static int comparePaths(String left, String right) {
        List<PathComponent> leftComponents = PathComponent.parse(left);
        List<PathComponent> rightComponents = PathComponent.parse(right);
        int shared = Math.min(leftComponents.size(), rightComponents.size());
        for (int index = 0; index < shared; index++) {
            int comparison = leftComponents.get(index).compareTo(rightComponents.get(index));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(leftComponents.size(), rightComponents.size());
    }

    private enum PathComponentKind {
        ROOT_DIRECTORY,
        CURRENT_DIRECTORY,
        PARENT_DIRECTORY,
        NORMAL
    }

    private record PathComponent(PathComponentKind kind, String name) implements Comparable<PathComponent> {
        private static final PathComponent ROOT_DIRECTORY = new PathComponent(PathComponentKind.ROOT_DIRECTORY, "");
        private static final PathComponent CURRENT_DIRECTORY =
                new PathComponent(PathComponentKind.CURRENT_DIRECTORY, "");
        private static final PathComponent PARENT_DIRECTORY = new PathComponent(PathComponentKind.PARENT_DIRECTORY, "");

        static List<PathComponent> parse(String path) {
            List<PathComponent> components = new ArrayList<>();
            if (path.startsWith("/")) {
                components.add(ROOT_DIRECTORY);
            } else if (path.equals(".") || path.startsWith("./")) {
                components.add(CURRENT_DIRECTORY);
            }
            int start = 0;
            while (start <= path.length()) {
                int separator = path.indexOf('/', start);
                int end = separator < 0 ? path.length() : separator;
                addSegment(components, path.substring(start, end));
                start = end + 1;
            }
            return components;
        }

        private static void addSegment(List<PathComponent> components, String segment) {
            if (segment.isEmpty() || segment.equals(".")) {
                return;
            }
            components.add(
                    segment.equals("..") ? PARENT_DIRECTORY : new PathComponent(PathComponentKind.NORMAL, segment));
        }

        @Override
        public int compareTo(PathComponent other) {
            int kindComparison = kind.compareTo(other.kind);
            return kindComparison != 0 ? kindComparison : compareStrings(name, other.name);
        }
    }
}
