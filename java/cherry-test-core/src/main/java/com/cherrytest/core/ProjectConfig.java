package com.cherrytest.core;

import com.cherrytest.common.rustcompat.RustOrdering;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

public record ProjectConfig(
        @Nullable String path,
        @Nullable String diffBase,
        @Nullable String compose,
        Map<String, ServiceConfig> services,
        SortedMap<String, PackageConfig> packages,
        @Nullable ExternalRunner functional,
        @Nullable ExternalRunner ui) {
    public ProjectConfig {
        services = new LinkedHashMap<>(services);
        TreeMap<String, PackageConfig> sorted = new TreeMap<>(RustOrdering.STRING_ORDER);
        sorted.putAll(packages);
        packages = sorted;
    }

    public ProjectConfig withPath(String replacement) {
        return new ProjectConfig(replacement, diffBase, compose, services, packages, functional, ui);
    }

    public ProjectConfig withPackages(SortedMap<String, PackageConfig> replacement) {
        return new ProjectConfig(path, diffBase, compose, services, replacement, functional, ui);
    }

    public static ProjectConfig empty() {
        return new ProjectConfig(null, null, null, Map.of(), new TreeMap<>(RustOrdering.STRING_ORDER), null, null);
    }
}
