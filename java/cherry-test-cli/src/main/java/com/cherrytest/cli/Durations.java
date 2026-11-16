package com.cherrytest.cli;

import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.common.rustcompat.RustIoError;
import com.cherrytest.common.rustcompat.RustOrdering;
import com.cherrytest.common.rustcompat.SerdeJsonWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Recorded job durations, read from and written to {@code durations.json} beside the runs directory. */
public final class Durations {
    private static final long WINDOW = 5;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final SortedMap<String, Entry> jobs = new TreeMap<>(RustOrdering.STRING_ORDER);

    /** One job's moving average in milliseconds and how many runs it covers. */
    public record Entry(long millis, long runs) {}

    /** {@code durations.json} in the directory that holds the runs directory: {@code target/cherry-test/} by default. */
    public static Path path(Path workspaceRoot, Path runsBase) {
        Path base = runsBase.isAbsolute() ? runsBase : workspaceRoot.resolve(runsBase);
        Path parent = base.getParent();
        return (parent == null ? base : parent).resolve("durations.json");
    }

    /** Reads {@code path}; a missing or unreadable file gives no recorded durations. */
    public static Durations load(Path path) {
        Durations durations = new Durations();
        String text;
        try {
            text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException unreadable) {
            return durations;
        }
        try {
            JsonNode document = JSON.readTree(text);
            JsonNode version = document.get("version");
            JsonNode jobs = document.get("jobs");
            if (version == null || !version.isIntegralNumber() || jobs == null || !jobs.isObject()) {
                return new Durations();
            }
            for (Map.Entry<String, JsonNode> job : jobs.properties()) {
                JsonNode millis = job.getValue().get("millis");
                JsonNode runs = job.getValue().get("runs");
                if (!unsigned(millis) || !unsigned(runs)) {
                    return new Durations();
                }
                durations.jobs.put(job.getKey(), new Entry(millis.asLong(), runs.asLong()));
            }
            return durations;
        } catch (RuntimeException invalid) {
            return new Durations();
        }
    }

    private static boolean unsigned(@Nullable JsonNode value) {
        return value != null && value.isIntegralNumber() && value.canConvertToLong() && value.asLong() >= 0;
    }

    public void save(Path path) {
        ObjectNode jobsNode = JsonNodeFactory.instance.objectNode();
        jobs.forEach((key, entry) -> {
            ObjectNode node = jobsNode.putObject(key);
            node.put("millis", entry.millis());
            node.put("runs", entry.runs());
        });
        ObjectNode document = JsonNodeFactory.instance.objectNode();
        document.put("version", 1);
        document.set("jobs", jobsNode);
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, SerdeJsonWriter.pretty(document), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw AnyhowError.context("write " + path, new AnyhowError(RustIoError.display(exception)));
        }
    }

    public @Nullable Entry get(Plan.JobKey key) {
        return jobs.get(key.id());
    }

    public boolean isEmpty() {
        return jobs.isEmpty();
    }

    /** Folds one run's elapsed time into the job's moving average, rounding to the nearest millisecond. */
    public void record(Plan.JobKey key, Duration elapsed) {
        long sample = elapsed.toMillis();
        Entry entry = jobs.getOrDefault(key.id(), new Entry(sample, 0));
        long weight = Math.min(entry.runs(), WINDOW - 1);
        long divisor = weight + 1;
        long millis = (entry.millis() * weight + sample + divisor / 2) / divisor;
        jobs.put(key.id(), new Entry(millis, entry.runs() == Long.MAX_VALUE ? Long.MAX_VALUE : entry.runs() + 1));
    }

    /** The sum of every recorded phase of one package, or {@code null} when none was recorded. */
    public @Nullable Long packageTotal(String project, String packageName) {
        String prefix = project + "/" + packageName + "/";
        boolean found = false;
        long total = 0;
        for (Map.Entry<String, Entry> job : jobs.entrySet()) {
            if (job.getKey().startsWith(prefix)
                    && !job.getKey().substring(prefix.length()).contains("/")) {
                found = true;
                total += job.getValue().millis();
            }
        }
        return found ? total : null;
    }

    /** Every job's estimate: its recorded average, or else the 75th percentile of the plan's known jobs. */
    public Plan estimate(Plan plan) {
        List<Long> known = new ArrayList<>();
        plan.jobs().forEach(job -> {
            Entry entry = get(job.key());
            if (entry != null) {
                known.add(entry.millis());
            }
        });
        long fallback = percentile75(known);
        List<Plan.PhasePlan> phases = new ArrayList<>();
        for (Plan.PhasePlan phase : plan.phases()) {
            List<Plan.Job> jobs = new ArrayList<>();
            for (Plan.Job job : phase.jobs()) {
                Entry entry = get(job.key());
                jobs.add(job.withEstimate(entry == null ? fallback : entry.millis()));
            }
            phases.add(new Plan.PhasePlan(phase.phase(), jobs));
        }
        return new Plan(phases);
    }

    /** The nearest-rank 75th percentile of {@code values}, or 0 when there are none. */
    public static long percentile75(List<Long> values) {
        if (values.isEmpty()) {
            return 0;
        }
        long[] sorted = values.stream().mapToLong(Long::longValue).toArray();
        Arrays.sort(sorted);
        int rank = (3 * sorted.length + 3) / 4;
        return sorted[Math.max(rank, 1) - 1];
    }

    /** Records every finished job of a run in {@code path}, keeping other jobs' averages. */
    public static void update(Path path, List<Map.Entry<Plan.JobKey, Duration>> timings) {
        if (timings.isEmpty()) {
            return;
        }
        Durations durations = load(path);
        timings.forEach(timing -> durations.record(timing.getKey(), timing.getValue()));
        durations.save(path);
    }
}
