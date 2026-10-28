package com.cherrytest.common.container;

import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/** Host variables passed into the container and fixed values set inside it. */
public record ContainerEnvConfig(
        @Nullable List<String> pass, @Nullable SortedMap<String, String> set) {
    public static final List<String> FIELDS = List.of("pass", "set");

    public ContainerEnvConfig {
        pass = pass == null ? null : List.copyOf(pass);
        if (set != null) {
            TreeMap<String, String> copy = new TreeMap<>(set.comparator());
            copy.putAll(set);
            set = copy;
        }
    }

    static @Nullable ContainerEnvConfig merged(@Nullable ContainerEnvConfig base, @Nullable ContainerEnvConfig top) {
        if (base == null) {
            return top;
        }
        if (top == null) {
            return base;
        }
        return new ContainerEnvConfig(ContainerConfig.or(top.pass, base.pass), ContainerConfig.or(top.set, base.set));
    }
}
