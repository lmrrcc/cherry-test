package com.cherrytest.parity;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class LiveCompare {
    private static final PrintStream OUT = System.out;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int SHOWN = 40;
    private static final Pattern TOOL_OUTPUT = Pattern.compile("^(#\\d+( .*)?| (Container|Network|Image|Volume) .*)$");
    private static final Pattern MILLISECONDS = Pattern.compile("\\b\\d+ms\\b");
    private static final Pattern ROW_DURATION = Pattern.compile("^(    .*\\s)\\d+(\\s+\\S+\\s+\\S+\\s*)$");
    private static final Pattern PORT = Pattern.compile("(127\\.0\\.0\\.1|localhost):\\d+");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern HITS = Pattern.compile("^(DA:\\d+,|FNDA:)(\\d+)(.*)$");
    private static final Pattern BRANCH = Pattern.compile("^(BRDA:\\d+,\\d+,\\d+,)(\\d+)$");

    private LiveCompare() {}

    record Run(Path stdout, Path exit, Path directory, String runId) {
        static Run of(String prefix, String runId) {
            return new Run(Path.of(prefix + ".stdout"), Path.of(prefix + ".exit"), Path.of(prefix + "-run"), runId);
        }
    }

    public static void main(String[] arguments) throws IOException {
        Run expected = Run.of(arguments[0], arguments[1]);
        Run actual = Run.of(arguments[2], arguments[3]);
        List<String> differences = new ArrayList<>();
        if (!Files.readString(expected.exit())
                .strip()
                .equals(Files.readString(actual.exit()).strip())) {
            differences.add("exit: " + Files.readString(expected.exit()).strip() + " vs "
                    + Files.readString(actual.exit()).strip());
        }
        differences.addAll(sequence("terminal", terminal(expected), terminal(actual)));
        differences.addAll(results(expected, actual));
        differences.addAll(coverage(expected, actual));
        differences.addAll(records(expected, actual));
        if (differences.isEmpty()) {
            OUT.println("LIVE IDENTICAL exit, terminal (durations, ports and tool build output aside), test results, "
                    + "coverage and integration records");
            return;
        }
        differences.stream().limit(SHOWN).forEach(OUT::println);
        OUT.println(differences.size() + " live differences");
        System.exit(1);
    }

    static List<String> terminal(Run run) throws IOException {
        return Files.readAllLines(run.stdout(), StandardCharsets.UTF_8).stream()
                .map(line -> terminalLine(line, run.runId()))
                .flatMap(Optional::stream)
                .toList();
    }

    static Optional<String> terminalLine(String line, String runId) {
        if (line.isBlank() || TOOL_OUTPUT.matcher(line).matches()) {
            return Optional.empty();
        }
        String normalized =
                MILLISECONDS.matcher(ports(line.replace(runId, "RUN-ID"))).replaceAll("Nms");
        Matcher row = ROW_DURATION.matcher(normalized);
        if (row.matches()) {
            normalized = row.group(1) + "N" + row.group(2);
        }
        return Optional.of(SPACES.matcher(normalized.strip()).replaceAll(" "));
    }

    static List<String> results(Run expected, Run actual) throws IOException {
        SortedMap<String, List<String>> left = adapterResults(expected);
        SortedMap<String, List<String>> right = adapterResults(actual);
        List<String> differences = new ArrayList<>(keys(left.keySet(), right.keySet(), "result"));
        for (String key : left.keySet()) {
            if (right.containsKey(key)) {
                differences.addAll(sequence(key, left.get(key), right.get(key)));
            }
        }
        return differences;
    }

    static SortedMap<String, List<String>> adapterResults(Run run) throws IOException {
        SortedMap<String, List<String>> results = new TreeMap<>();
        for (Path file : files(run.directory().resolve("reports/adapters"), "result.json")) {
            JsonNode result = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8))
                    .path("result");
            List<String> lines = new ArrayList<>();
            lines.add("success " + result.path("success").asBoolean());
            result.path("diagnostics")
                    .valueStream()
                    .map(diagnostic -> "diagnostic " + normalized(diagnostic.asString(""), run.runId()))
                    .forEach(lines::add);
            result.path("tests")
                    .valueStream()
                    .map(test -> "test " + test.path("name").asString("") + " "
                            + test.path("status").asString("")
                            + (test.path("message").isString()
                                    ? " :: " + normalized(test.path("message").asString(), run.runId())
                                    : ""))
                    .sorted()
                    .forEach(lines::add);
            results.put(run.directory().relativize(file).toString(), List.copyOf(lines));
        }
        return results;
    }

    static List<String> coverage(Run expected, Run actual) throws IOException {
        SortedMap<String, List<String>> left = lcovFiles(expected);
        SortedMap<String, List<String>> right = lcovFiles(actual);
        List<String> differences = new ArrayList<>(keys(left.keySet(), right.keySet(), "coverage"));
        for (String key : left.keySet()) {
            if (right.containsKey(key)) {
                differences.addAll(sequence(key, left.get(key), right.get(key)));
            }
        }
        return differences;
    }

    static SortedMap<String, List<String>> lcovFiles(Run run) throws IOException {
        SortedMap<String, List<String>> files = new TreeMap<>();
        List<Path> candidates = new ArrayList<>(files(run.directory().resolve("coverage"), ".lcov"));
        try (Stream<Path> top = Files.list(run.directory())) {
            top.filter(path -> path.toString().endsWith(".lcov")).forEach(candidates::add);
        }
        for (Path file : candidates) {
            files.put(
                    run.directory().relativize(file).toString(),
                    Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                            .map(line -> lcovLine(line.replace(run.runId(), "RUN-ID")))
                            .toList());
        }
        return files;
    }

    static String lcovLine(String line) {
        Matcher hits = HITS.matcher(line);
        if (hits.matches()) {
            return hits.group(1) + (Long.parseLong(hits.group(2)) > 0 ? "hit" : "0") + hits.group(3);
        }
        Matcher branch = BRANCH.matcher(line);
        if (branch.matches()) {
            return branch.group(1) + (Long.parseLong(branch.group(2)) > 0 ? "taken" : "0");
        }
        return line;
    }

    static List<String> records(Run expected, Run actual) throws IOException {
        return sequence("integration records", integrationRecords(expected), integrationRecords(actual));
    }

    static List<String> integrationRecords(Run run) throws IOException {
        SortedSet<String> records = new TreeSet<>();
        for (Path file : files(run.directory().resolve("reports/integration"), ".jsonl")) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode record = JSON.readTree(line);
                records.add(run.directory().relativize(file) + " "
                        + record.path("full_name").asString("") + " "
                        + record.path("method").asString("") + " "
                        + ports(record.path("url").asString("")) + " "
                        + record.path("status").asString(record.path("status").toString()) + " passed="
                        + record.path("passed").asBoolean());
            }
        }
        return List.copyOf(records);
    }

    static String normalized(String text, String runId) {
        return ports(text.replace(runId, "RUN-ID"));
    }

    static String ports(String text) {
        return PORT.matcher(text).replaceAll("$1:PORT");
    }

    static List<String> keys(Set<String> left, Set<String> right, String kind) {
        List<String> differences = new ArrayList<>();
        new TreeSet<>(left)
                .stream()
                        .filter(key -> !right.contains(key))
                        .forEach(key -> differences.add(kind + " only in rust: " + key));
        new TreeSet<>(right)
                .stream()
                        .filter(key -> !left.contains(key))
                        .forEach(key -> differences.add(kind + " only in java: " + key));
        return differences;
    }

    static List<String> sequence(String label, List<String> left, List<String> right) {
        List<String> differences = new ArrayList<>();
        int size = Math.max(left.size(), right.size());
        for (int index = 0; index < size; index++) {
            String expected = index < left.size() ? left.get(index) : "<missing>";
            String actual = index < right.size() ? right.get(index) : "<missing>";
            if (!expected.equals(actual)) {
                differences.add(label + " line " + (index + 1) + ":\n  rust: " + expected + "\n  java: " + actual);
                if (differences.size() >= SHOWN) {
                    break;
                }
            }
        }
        return differences;
    }

    static List<Path> files(Path directory, String suffix) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(suffix))
                    .filter(path -> !path.toString().contains("/target/")
                            && !path.toString().contains("/node_modules/"))
                    .sorted()
                    .toList();
        }
    }
}
