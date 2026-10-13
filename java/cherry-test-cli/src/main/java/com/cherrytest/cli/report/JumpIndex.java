package com.cherrytest.cli.report;

import com.cherrytest.common.rustcompat.RustOrdering;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

public final class JumpIndex {
    private record Def(String name, String phase, String file, long line, String anchor) {}

    private final List<Def> defs = new ArrayList<>();
    private final TreeMap<String, TreeMap<String, List<Integer>>> byPhaseFile =
            new TreeMap<>(RustOrdering.STRING_ORDER);
    private final TreeMap<String, List<Integer>> byName = new TreeMap<>(RustOrdering.STRING_ORDER);

    public static JumpIndex empty() {
        return new JumpIndex();
    }

    public @Nullable String href(@Nullable String phase, String file, long line, String name) {
        if (lineDefines(file, line, name)) {
            return null;
        }
        Def def = resolve(phase, file, line, name);
        if (def == null) {
            return null;
        }
        return phase != null ? "#" + def.anchor() : "phases.html#" + def.anchor();
    }

    public @Nullable String rowAnchor(String phase, String file, long line) {
        TreeMap<String, List<Integer>> files = byPhaseFile.get(phase);
        if (files == null) {
            return null;
        }
        List<Integer> indices = files.get(file);
        if (indices == null) {
            return null;
        }
        for (int index : indices) {
            Def def = defs.get(index);
            if (def.line() == line) {
                return def.anchor();
            }
        }
        return null;
    }

    private boolean lineDefines(String file, long line, String name) {
        List<Integer> indices = byName.get(name);
        if (indices == null) {
            return false;
        }
        return indices.stream()
                .anyMatch(index ->
                        defs.get(index).file().equals(file) && defs.get(index).line() == line);
    }

    private @Nullable Def resolve(@Nullable String phase, String file, long line, String name) {
        if (phase != null) {
            Def nearest = nearestSameFile(phase, file, line, name);
            if (nearest != null) {
                return nearest;
            }
            Def unique = uniqueNamed(name, def -> !def.phase().equals(phase) || isSelf(def, phase, file, line));
            if (unique != null) {
                return unique;
            }
        }
        return uniqueNamed(
                name,
                def -> phase != null
                        ? isSelf(def, phase, file, line)
                        : def.file().equals(file) && def.line() == line);
    }

    private @Nullable Def nearestSameFile(String phase, String file, long line, String name) {
        TreeMap<String, List<Integer>> files = byPhaseFile.get(phase);
        if (files == null) {
            return null;
        }
        List<Integer> indices = files.get(file);
        if (indices == null) {
            return null;
        }
        Integer best = null;
        for (int index : List.copyOf(indices)) {
            Def def = defs.get(index);
            if (!def.name().equals(name) || def.line() == line) {
                continue;
            }
            long candidateLine = def.line();
            boolean replace;
            if (best == null) {
                replace = true;
            } else {
                long currentLine = defs.get(best).line();
                long candidateDistance = Math.abs(candidateLine - line);
                long currentDistance = Math.abs(currentLine - line);
                replace = candidateDistance < currentDistance
                        || (candidateDistance == currentDistance && candidateLine <= line && currentLine > line);
            }
            if (replace) {
                best = index;
            }
        }
        return best == null ? null : defs.get(best);
    }

    private @Nullable Def uniqueNamed(String name, Predicate<Def> exclude) {
        List<Integer> indices = byName.get(name);
        if (indices == null) {
            return null;
        }
        Integer found = null;
        for (int index : List.copyOf(indices)) {
            Def def = defs.get(index);
            if (exclude.test(def)) {
                continue;
            }
            if (found != null) {
                return null;
            }
            found = index;
        }
        return found == null ? null : defs.get(found);
    }

    void insert(String phase, String file, long line, String name) {
        TreeMap<String, List<Integer>> files = byPhaseFile.get(phase);
        if (files != null) {
            List<Integer> indices = files.get(file);
            if (indices != null
                    && indices.stream()
                            .anyMatch(index -> defs.get(index).line() == line
                                    && defs.get(index).name().equals(name))) {
                return;
            }
        }
        String anchor = anchorId(phase, file, line);
        int index = defs.size();
        byPhaseFile
                .computeIfAbsent(phase, key -> new TreeMap<>(RustOrdering.STRING_ORDER))
                .computeIfAbsent(file, key -> new ArrayList<>())
                .add(index);
        byName.computeIfAbsent(name, key -> new ArrayList<>()).add(index);
        defs.add(new Def(name, phase, file, line, anchor));
    }

    private static boolean isSelf(Def def, String phase, String file, long line) {
        return def.phase().equals(phase) && def.file().equals(file) && def.line() == line;
    }

    private static String anchorId(String phase, String file, long line) {
        StringBuilder id = new StringBuilder("cov-");
        pushSlug(id, phase);
        id.append('-');
        pushSlug(id, file);
        id.append("-L").append(line);
        return id.toString();
    }

    private static void pushSlug(StringBuilder out, String value) {
        int index = 0;
        while (index < value.length()) {
            int codePoint = value.codePointAt(index);
            index += Character.charCount(codePoint);
            out.append(RustChar.isAsciiAlphanumeric(codePoint) ? (char) codePoint : '-');
        }
    }
}
