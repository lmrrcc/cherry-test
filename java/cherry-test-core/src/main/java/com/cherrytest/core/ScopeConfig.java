package com.cherrytest.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ScopeConfig(List<String> projects, Map<String, List<String>> services) {
    public ScopeConfig {
        projects = List.copyOf(projects);
        services = new LinkedHashMap<>(services);
    }
}
