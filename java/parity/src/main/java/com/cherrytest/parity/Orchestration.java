package com.cherrytest.parity;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class Orchestration {
    private static final PrintStream OUT = System.out;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private Orchestration() {}

    public static void main(String[] arguments) throws IOException {
        List<String> problems = problems(Path.of(arguments[0]), Path.of(arguments[1]));
        problems.forEach(OUT::println);
        System.exit(problems.isEmpty() ? 0 : 1);
    }

    static List<String> problems(Path cassette, Path replayState) throws IOException {
        List<JsonNode> entries = new ArrayList<>();
        for (String line : Files.readAllLines(cassette.resolve("index.jsonl"), StandardCharsets.UTF_8)) {
            entries.add(JSON.readTree(line));
        }
        List<Integer> consumed = Files.readAllLines(replayState, StandardCharsets.UTF_8).stream()
                .filter(line -> !line.isBlank())
                .map(Integer::valueOf)
                .toList();
        List<String> problems = new ArrayList<>();
        if (consumed.size() != entries.size() || new HashSet<>(consumed).size() != consumed.size()) {
            problems.add("FAILED orchestration: replay consumed " + consumed.size() + " of " + entries.size()
                    + " recorded tool calls");
            return problems;
        }
        int position = 0;
        while (position < entries.size()) {
            int end = blockEnd(entries, position);
            Set<Integer> expected = new HashSet<>();
            Set<Integer> actual = new HashSet<>();
            for (int index = position; index < end; index++) {
                expected.add(index);
                actual.add(consumed.get(index));
            }
            if (!expected.equals(actual) || (end - position == 1 && consumed.get(position) != position)) {
                problems.add("FAILED orchestration: recorded calls " + position + ".." + (end - 1)
                        + " were replayed as " + consumed.subList(position, end));
            }
            position = end;
        }
        if (problems.isEmpty()) {
            OUT.println("IDENTICAL orchestration (" + entries.size() + " tool calls in recorded order)");
        }
        return problems;
    }

    private static int blockEnd(List<JsonNode> entries, int start) {
        if (!composePort(entries.get(start))) {
            return start + 1;
        }
        int end = start;
        while (end < entries.size() && composePort(entries.get(end))) {
            end++;
        }
        return end;
    }

    private static boolean composePort(JsonNode entry) {
        List<String> argv = new ArrayList<>();
        entry.get("argv").forEach(argument -> argv.add(argument.stringValue()));
        return Placeholders.isComposePort(entry.get("tool").stringValue(), argv);
    }
}
