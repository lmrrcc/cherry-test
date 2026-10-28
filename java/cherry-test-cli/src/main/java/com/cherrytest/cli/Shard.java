package com.cherrytest.cli;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** {@code --shard I/N}: run the packages assigned to shard {@code index} (1-based) of {@code count}. */
public record Shard(int index, int count) {
    public static final String EXPECTED = "expected I/N with 1 <= I <= N, such as 1/4";

    public static Shard parse(String value) {
        int slash = value.indexOf('/');
        if (slash < 0) {
            throw new IllegalArgumentException(EXPECTED);
        }
        long index = parseCount(value.substring(0, slash));
        long count = parseCount(value.substring(slash + 1));
        if (index == 0 || count == 0 || index > count) {
            throw new IllegalArgumentException(EXPECTED);
        }
        return new Shard((int) index, (int) count);
    }

    private static long parseCount(String text) {
        if (text.isEmpty() || !text.chars().allMatch(character -> character >= '0' && character <= '9')) {
            throw new IllegalArgumentException(EXPECTED);
        }
        try {
            long value = Long.parseLong(text);
            if (value > 0xFFFFFFFFL || value > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(EXPECTED);
            }
            return value;
        } catch (NumberFormatException tooLarge) {
            throw new IllegalArgumentException(EXPECTED, tooLarge);
        }
    }

    @Override
    public String toString() {
        return index + "/" + count;
    }

    /**
     * The 0-based shard of every package, given each package's recorded total in configuration order. With no
     * recorded total, packages are dealt round-robin. Otherwise the longest package goes first to the least loaded
     * shard (lowest shard on ties); packages without a record count as the 75th percentile of recorded ones.
     */
    public static List<Integer> assign(List<@Nullable Long> totals, int count) {
        int shards = Math.max(count, 1);
        List<Long> known = new ArrayList<>();
        for (Long total : totals) {
            if (total != null) {
                known.add(total);
            }
        }
        List<Integer> assignment = new ArrayList<>();
        if (known.isEmpty()) {
            for (int position = 0; position < totals.size(); position++) {
                assignment.add(position % shards);
            }
            return assignment;
        }
        long fallback = Durations.percentile75(known);
        long[] estimates = new long[totals.size()];
        for (int position = 0; position < totals.size(); position++) {
            Long total = totals.get(position);
            estimates[position] = total == null ? fallback : total;
        }
        List<Integer> order = new ArrayList<>();
        for (int position = 0; position < totals.size(); position++) {
            order.add(position);
        }
        order.sort(Comparator.<Integer>comparingLong(position -> estimates[position])
                .reversed()
                .thenComparingInt(position -> position));
        long[] loads = new long[shards];
        int[] result = new int[totals.size()];
        for (int position : order) {
            int target = 0;
            for (int shard = 0; shard < shards; shard++) {
                if (loads[shard] < loads[target]) {
                    target = shard;
                }
            }
            loads[target] += estimates[position];
            result[position] = target;
        }
        for (int shard : result) {
            assignment.add(shard);
        }
        return assignment;
    }

    /** Keeps the packages of {@code shard}, in configuration order; projects left without packages are dropped. */
    public static List<ProjectGroup> select(List<ProjectGroup> projects, Shard shard, Durations durations) {
        List<@Nullable Long> totals = new ArrayList<>();
        for (ProjectGroup project : projects) {
            for (WorkspaceMember member : project.members()) {
                totals.add(durations.packageTotal(project.id(), member.name()));
            }
        }
        List<Integer> shards = assign(totals, shard.count());
        List<ProjectGroup> selected = new ArrayList<>();
        int position = 0;
        for (ProjectGroup project : projects) {
            List<WorkspaceMember> members = new ArrayList<>();
            for (WorkspaceMember member : project.members()) {
                if (shards.get(position) + 1 == shard.index()) {
                    members.add(member);
                }
                position++;
            }
            if (!members.isEmpty()) {
                selected.add(new ProjectGroup(project.id(), members));
            }
        }
        return selected;
    }
}
