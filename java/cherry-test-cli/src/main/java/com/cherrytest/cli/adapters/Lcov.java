package com.cherrytest.cli.adapters;

import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.common.rustcompat.RustIoError;
import com.cherrytest.common.rustcompat.RustOrdering;
import com.cherrytest.common.rustcompat.RustParse;
import com.cherrytest.common.rustcompat.RustText;
import com.cherrytest.common.rustcompat.U64;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class Lcov {
    private Lcov() {}

    private static final class SourceRecord {
        private final TreeMap<Long, Long> lines = new TreeMap<>(Long::compareUnsigned);
        private final TreeMap<String, String> definitions = new TreeMap<>(RustOrdering.STRING_ORDER);
        private final TreeMap<String, Long> functions = new TreeMap<>(RustOrdering.STRING_ORDER);
        private final TreeMap<String, Long> branches = new TreeMap<>(RustOrdering.STRING_ORDER);
        private final TreeMap<String, Boolean> branchKnown = new TreeMap<>(RustOrdering.STRING_ORDER);
    }

    public static void merge(List<Path> sources, Path destination) {
        TreeMap<String, SourceRecord> records = new TreeMap<>(RustOrdering.STRING_ORDER);
        for (Path source : sources) {
            String content;
            try {
                content = Files.readString(source, StandardCharsets.UTF_8);
            } catch (IOException exception) {
                throw AnyhowError.context("read coverage " + source, new AnyhowError(RustIoError.display(exception)));
            }
            String current = null;
            for (String line : RustText.lines(content)) {
                if (line.startsWith("SF:")) {
                    String file = line.substring(3);
                    if (current != null || file.isEmpty()) {
                        throw new AnyhowError("invalid LCOV source record");
                    }
                    current = file;
                    records.computeIfAbsent(file, key -> new SourceRecord());
                } else if (line.equals("end_of_record")) {
                    current = null;
                } else if (current != null) {
                    SourceRecord record = records.get(current);
                    if (record == null) {
                        throw new AnyhowError("LCOV current source");
                    }
                    apply(record, line);
                }
            }
            if (current != null) {
                throw new AnyhowError("truncated LCOV record in " + source);
            }
        }
        StringBuilder output = new StringBuilder();
        for (Map.Entry<String, SourceRecord> entry : records.entrySet()) {
            SourceRecord record = entry.getValue();
            output.append("SF:").append(entry.getKey()).append('\n');
            for (String definition : record.definitions.values()) {
                output.append("FN:").append(definition).append('\n');
            }
            for (Map.Entry<String, Long> function : record.functions.entrySet()) {
                output.append("FNDA:")
                        .append(U64.toString(function.getValue()))
                        .append(',')
                        .append(function.getKey())
                        .append('\n');
            }
            long functionsHit = record.functions.values().stream()
                    .filter(count -> count != 0)
                    .count();
            output.append("FNF:").append(record.functions.size()).append('\n');
            output.append("FNH:").append(functionsHit).append('\n');
            for (Map.Entry<Long, Long> line : record.lines.entrySet()) {
                output.append("DA:")
                        .append(U64.toString(line.getKey()))
                        .append(',')
                        .append(U64.toString(line.getValue()))
                        .append('\n');
            }
            long linesHit =
                    record.lines.values().stream().filter(count -> count != 0).count();
            output.append("LF:").append(record.lines.size()).append('\n');
            output.append("LH:").append(linesHit).append('\n');
            long branchesHit = 0;
            for (Map.Entry<String, Boolean> branch : record.branchKnown.entrySet()) {
                output.append("BRDA:").append(branch.getKey()).append(',');
                Long known = record.branches.get(branch.getKey());
                if (branch.getValue() && known != null) {
                    long count = known;
                    output.append(U64.toString(count));
                    if (count != 0) {
                        branchesHit++;
                    }
                } else {
                    output.append('-');
                }
                output.append('\n');
            }
            output.append("BRF:").append(record.branchKnown.size()).append('\n');
            output.append("BRH:").append(branchesHit).append('\n');
            output.append("end_of_record\n");
        }
        try {
            Files.writeString(destination, output.toString(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new AnyhowError(RustIoError.display(exception), null);
        }
    }

    private static void apply(SourceRecord record, String line) {
        if (line.startsWith("DA:")) {
            String[] values = line.substring(3).split(",", -1);
            long number = RustParse.u64(values[0]);
            if (values.length < 2) {
                throw new AnyhowError("LCOV count");
            }
            long count = RustParse.u64(values[1]);
            if (number == 0) {
                throw new AnyhowError("invalid LCOV line zero");
            }
            record.lines.put(
                    number, U64.checkedAdd(record.lines.getOrDefault(number, 0L), count, "coverage counter overflow"));
        } else if (line.startsWith("FN:")) {
            String value = line.substring(3);
            int comma = value.indexOf(',');
            if (comma < 0) {
                throw new AnyhowError("LCOV function definition");
            }
            record.definitions.put(value.substring(comma + 1), value);
        } else if (line.startsWith("FNDA:")) {
            String value = line.substring(5);
            int comma = value.indexOf(',');
            if (comma < 0) {
                throw new AnyhowError("LCOV function count");
            }
            String name = value.substring(comma + 1);
            long count = RustParse.u64(value.substring(0, comma));
            record.functions.put(
                    name, U64.checkedAdd(record.functions.getOrDefault(name, 0L), count, "coverage counter overflow"));
        } else if (line.startsWith("BRDA:")) {
            String value = line.substring(5);
            int comma = value.lastIndexOf(',');
            if (comma < 0) {
                throw new AnyhowError("LCOV branch count");
            }
            String key = value.substring(0, comma);
            String count = value.substring(comma + 1);
            record.branchKnown.putIfAbsent(key, Boolean.FALSE);
            if (!count.equals("-")) {
                long parsed = RustParse.u64(count);
                long existing = record.branches.getOrDefault(key, 0L);
                record.branches.put(key, U64.checkedAdd(existing, parsed, "coverage counter overflow"));
                record.branchKnown.put(key, Boolean.TRUE);
            }
        }
    }
}
