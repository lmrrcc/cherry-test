package com.cherrytest.common.process;

import com.cherrytest.common.rustcompat.RustOrdering;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

public final class ProcessEnvironment {
    private static final Map<String, String> SET = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> REMOVED = new ConcurrentHashMap<>();
    private static final Map<String, String> CHILDREN_ONLY = new ConcurrentHashMap<>();

    private ProcessEnvironment() {}

    public static @Nullable String get(String name) {
        if (REMOVED.containsKey(name)) {
            return null;
        }
        String value = SET.get(name);
        return value != null ? value : System.getenv(name);
    }

    public static boolean isPresent(String name) {
        return get(name) != null;
    }

    public static void set(String name, String value) {
        REMOVED.remove(name);
        SET.put(name, value);
    }

    public static void remove(String name) {
        SET.remove(name);
        REMOVED.put(name, Boolean.TRUE);
    }

    public static Map<String, String> snapshot() {
        Map<String, String> merged = new LinkedHashMap<>(System.getenv());
        REMOVED.keySet().forEach(merged::remove);
        merged.putAll(SET);
        return merged;
    }

    public static Map<String, String> sortedSnapshot() {
        return new TreeMap<>(snapshot());
    }

    /** The process environment in Rust string order, as a run captures it once before it starts. */
    public static SortedMap<String, String> explicitSnapshot() {
        SortedMap<String, String> sorted = new TreeMap<>(RustOrdering.STRING_ORDER);
        sorted.putAll(snapshot());
        return sorted;
    }

    public static void setForChildren(String name, String value) {
        CHILDREN_ONLY.put(name, value);
    }

    public static void applyTo(Map<String, String> childEnvironment) {
        REMOVED.keySet().forEach(childEnvironment::remove);
        childEnvironment.putAll(SET);
        childEnvironment.putAll(CHILDREN_ONLY);
    }
}
