package com.cherrytest.parity;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

public final class Compare {
    private static final PrintStream OUT = System.out;
    private static final int CONTEXT = 120;

    private Compare() {}

    sealed interface Node permits Directory, Content, Link {}

    record Directory() implements Node {}

    record Content(Path file, boolean executable) implements Node {}

    record Link(String target) implements Node {}

    record Rename(String from, String to) {
        static final Rename NONE = new Rename("", "");

        String path(String value) {
            return from.isEmpty() ? value : value.replace(from, to);
        }

        byte[] content(byte[] value) {
            return from.isEmpty()
                    ? value
                    : Placeholders.replace(
                            value, from.getBytes(StandardCharsets.UTF_8), to.getBytes(StandardCharsets.UTF_8));
        }
    }

    public static void main(String[] arguments) throws IOException {
        Path expected = Path.of(arguments[0]);
        Path actual = Path.of(arguments[1]);
        Rename rename = arguments.length == 4 ? new Rename(arguments[3], arguments[2]) : Rename.NONE;
        List<String> differences = differences(expected, actual, rename);
        differences.forEach(OUT::println);
        OUT.println(
                differences.isEmpty() ? "IDENTICAL " + expected + " " + actual : differences.size() + " difference(s)");
        System.exit(differences.isEmpty() ? 0 : 1);
    }

    static List<String> differences(Path expected, Path actual, Rename rename) throws IOException {
        Map<String, Node> left = tree(expected);
        Map<String, Node> right = new TreeMap<>();
        tree(actual).forEach((path, node) -> right.put(rename.path(path), node));
        TreeSet<String> paths = new TreeSet<>(left.keySet());
        paths.addAll(right.keySet());
        List<String> differences = new ArrayList<>();
        for (String path : paths) {
            Node rust = left.get(path);
            Node java = right.get(path);
            if (rust == null) {
                differences.add("only in java: " + path);
            } else if (java == null) {
                differences.add("only in rust: " + path);
            } else {
                String difference = difference(rust, java, rename);
                if (!difference.isEmpty()) {
                    differences.add("differs: " + path + " " + difference);
                }
            }
        }
        return differences;
    }

    private static String difference(Node rust, Node java, Rename rename) throws IOException {
        return switch (rust) {
            case Directory _ -> java instanceof Directory ? "" : "(kind)";
            case Link link ->
                java instanceof Link other
                        ? (link.target().equals(rename.path(other.target()))
                                ? ""
                                : "(link " + link.target() + " vs " + other.target() + ")")
                        : "(kind)";
            case Content content ->
                java instanceof Content other ? contentDifference(content, other, rename) : "(kind)";
        };
    }

    private static String contentDifference(Content rust, Content java, Rename rename) throws IOException {
        byte[] left = Files.readAllBytes(rust.file());
        byte[] right = rename.content(Files.readAllBytes(java.file()));
        if (rust.executable() != java.executable()) {
            return "(executable bit " + rust.executable() + " vs " + java.executable() + ")";
        }
        int mismatch = Arrays.mismatch(left, right);
        if (mismatch < 0) {
            return "";
        }
        return "(sizes " + left.length + " vs " + right.length + ", first difference at byte " + mismatch + ")\n"
                + "    rust: " + excerpt(left, mismatch) + "\n"
                + "    java: " + excerpt(right, mismatch);
    }

    private static String excerpt(byte[] content, int offset) {
        int start = Math.max(0, offset - CONTEXT / 2);
        int end = Math.min(content.length, offset + CONTEXT / 2);
        return new String(content, start, end - start, StandardCharsets.UTF_8)
                .replace("\\", "\\\\")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                .replace("\u001b", "\\e");
    }

    static Map<String, Node> tree(Path root) throws IOException {
        Map<String, Node> nodes = new TreeMap<>();
        if (!Files.exists(root)) {
            return nodes;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                Path relative = root.relativize(directory);
                for (int index = 4; index < relative.getNameCount(); index++) {
                    if (relative.getName(index).toString().equals("target")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                }
                if (!directory.equals(root)) {
                    nodes.put(relative.toString(), new Directory());
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                String relative = root.relativize(file).toString();
                if (attributes.isSymbolicLink()) {
                    nodes.put(relative, new Link(Files.readSymbolicLink(file).toString()));
                } else {
                    nodes.put(relative, new Content(file, Files.isExecutable(file)));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return nodes;
    }
}
