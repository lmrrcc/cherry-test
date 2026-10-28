package com.cherrytest.core;

import com.cherrytest.common.container.Expected;
import com.cherrytest.common.container.PullPolicy;
import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.common.rustcompat.RustOrdering;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * Reads one {@code projects.<id>.services.<name>} entry. The service map itself accepts unknown keys, as the Rust
 * {@code ServiceConfig}; its {@code health} probe and {@code mounts} entries reject them.
 */
final class ServiceYaml {
    private ServiceYaml() {}

    static ServiceConfig parse(Object value, String path) {
        String servicePath = path + ".";
        Map<String, Object> map = YamlValues.mapping(value, path);
        Object port = YamlValues.field(map, "port");
        if (port == null) {
            throw new AnyhowError(servicePath + ": missing field `port`");
        }
        String pull = YamlValues.optionalString(map, "pull", servicePath);
        return new ServiceConfig(
                YamlValues.stringOr(map, "service", servicePath, ""),
                (int) YamlValues.unsigned(port, servicePath + "port", 0xFFFFL),
                health(YamlValues.field(map, "health"), path + ".health"),
                YamlValues.optionalString(map, "kind", servicePath),
                YamlValues.optionalString(map, "image", servicePath),
                pull == null ? null : ContainerYaml.variant(pull, path + ".pull", PullPolicy::fromWire),
                env(YamlValues.field(map, "env"), path + ".env"),
                YamlValues.stringList(map, "tmpfs", servicePath),
                mounts(YamlValues.field(map, "mounts"), path + ".mounts"));
    }

    private static @Nullable ServiceHealth health(@Nullable Object value, String path) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return new ServiceHealth.HttpPath(text);
        }
        if (!(value instanceof Map<?, ?>)) {
            throw new AnyhowError(path + ": invalid type: " + YamlValues.describe(value)
                    + ", expected an HTTP path or a map with `exec`, `tcp` and `timeout`");
        }
        Map<String, Object> map = YamlValues.mapping(value, path);
        List<String> exec = null;
        Boolean tcp = null;
        Long timeout = null;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = entry.getKey();
            Object raw = entry.getValue();
            if (!HealthProbe.FIELDS.contains(key)) {
                throw new AnyhowError(path + ": " + Expected.unknownField(key, HealthProbe.FIELDS));
            }
            if (raw == YamlValues.NullValue.INSTANCE) {
                continue;
            }
            switch (key) {
                case "exec" -> exec = ContainerYaml.texts(raw, path + ".exec");
                case "tcp" -> tcp = ContainerYaml.bool(raw, path + ".tcp");
                default -> timeout = ContainerYaml.timeout(raw, path + ".timeout");
            }
        }
        return new ServiceHealth.Probe(new HealthProbe(exec, tcp, timeout));
    }

    private static SortedMap<String, String> env(@Nullable Object value, String path) {
        SortedMap<String, String> env = new TreeMap<>(RustOrdering.STRING_ORDER);
        if (value == null) {
            return env;
        }
        if (!(value instanceof Map<?, ?>)) {
            throw new AnyhowError(path + ": invalid type: " + YamlValues.describe(value) + ", expected a map");
        }
        for (Map.Entry<String, Object> variable :
                YamlValues.mapping(value, path).entrySet()) {
            env.put(variable.getKey(), YamlValues.string(variable.getValue(), path + "." + variable.getKey()));
        }
        return env;
    }

    private static List<ServiceMount> mounts(@Nullable Object value, String path) {
        if (value == null) {
            return List.of();
        }
        return ContainerYaml.sequence(value, path, ServiceYaml::mount);
    }

    private static ServiceMount mount(Object value, String path) {
        Map<String, Object> map = ContainerYaml.struct(value, path, "ServiceMount");
        String source = null;
        String target = null;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = entry.getKey();
            if (!ServiceMount.FIELDS.contains(key)) {
                throw new AnyhowError(path + ": " + Expected.unknownField(key, ServiceMount.FIELDS));
            }
            if (key.equals("source")) {
                source = ContainerYaml.text(entry.getValue(), path + ".source");
            } else {
                target = ContainerYaml.text(entry.getValue(), path + ".target");
            }
        }
        if (source == null) {
            throw new AnyhowError(path + ": missing field `source`");
        }
        if (target == null) {
            throw new AnyhowError(path + ": missing field `target`");
        }
        return new ServiceMount(source, target);
    }
}
