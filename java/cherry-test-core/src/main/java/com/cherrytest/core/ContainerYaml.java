package com.cherrytest.core;

import com.cherrytest.common.container.CacheConfig;
import com.cherrytest.common.container.ContainerConfig;
import com.cherrytest.common.container.ContainerEnvConfig;
import com.cherrytest.common.container.Durations;
import com.cherrytest.common.container.Expected;
import com.cherrytest.common.container.MountConfig;
import com.cherrytest.common.container.NetworkMode;
import com.cherrytest.common.container.PullPolicy;
import com.cherrytest.common.container.SeedMode;
import com.cherrytest.common.container.WorkspaceAccess;
import com.cherrytest.common.container.WritableConfig;
import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.common.rustcompat.RustFloatFormat;
import com.cherrytest.common.rustcompat.RustOrdering;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.BiFunction;
import org.jspecify.annotations.Nullable;

/**
 * Reads the {@code container:} block with the strictness of the Rust edition's
 * {@code #[serde(deny_unknown_fields)]}: entries are checked in document order and the first
 * unknown key or invalid value is reported.
 */
final class ContainerYaml {
    private ContainerYaml() {}

    static @Nullable ContainerConfig parse(@Nullable Object value, String path) {
        if (value == null) {
            return null;
        }
        Map<String, Object> map = struct(value, path, "ContainerConfig");
        String image = null;
        PullPolicy pull = null;
        NetworkMode network = null;
        String user = null;
        List<String> capDrop = null;
        Boolean noNewPrivileges = null;
        Boolean readOnlyRoot = null;
        String cpus = null;
        String memory = null;
        Long pids = null;
        String shmSize = null;
        List<String> tmpfs = null;
        Long timeout = null;
        WorkspaceAccess workspace = null;
        List<WritableConfig> writable = null;
        List<MountConfig> mounts = null;
        List<CacheConfig> caches = null;
        ContainerEnvConfig env = null;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = entry.getKey();
            Object raw = entry.getValue();
            String field = path + "." + key;
            if (!ContainerConfig.FIELDS.contains(key)) {
                throw new AnyhowError(path + ": " + Expected.unknownField(key, ContainerConfig.FIELDS));
            }
            if (raw == YamlValues.NullValue.INSTANCE) {
                continue;
            }
            switch (key) {
                case "image" -> image = text(raw, field);
                case "pull" -> pull = variant(raw, field, PullPolicy::fromWire);
                case "network" -> network = variant(raw, field, NetworkMode::fromWire);
                case "user" -> user = text(raw, field);
                case "cap_drop" -> capDrop = texts(raw, field);
                case "no_new_privileges" -> noNewPrivileges = bool(raw, field);
                case "read_only_root" -> readOnlyRoot = bool(raw, field);
                case "cpus" -> cpus = quantity(raw, field);
                case "memory" -> memory = quantity(raw, field);
                case "pids" -> pids = YamlValues.unsigned(raw, field, Long.MAX_VALUE);
                case "shm_size" -> shmSize = quantity(raw, field);
                case "tmpfs" -> tmpfs = texts(raw, field);
                case "timeout" -> timeout = timeout(raw, field);
                case "workspace" -> workspace = variant(raw, field, WorkspaceAccess::fromWire);
                case "writable" -> writable = sequence(raw, field, ContainerYaml::writable);
                case "mounts" -> mounts = sequence(raw, field, ContainerYaml::mount);
                case "caches" -> caches = sequence(raw, field, ContainerYaml::cache);
                default -> env = env(raw, field);
            }
        }
        return new ContainerConfig(
                image,
                pull,
                network,
                user,
                capDrop,
                noNewPrivileges,
                readOnlyRoot,
                cpus,
                memory,
                pids,
                shmSize,
                tmpfs,
                timeout,
                workspace,
                writable,
                mounts,
                caches,
                env);
    }

    private static WritableConfig writable(Object value, String path) {
        if (value instanceof String text) {
            return new WritableConfig(text, null);
        }
        if (!(value instanceof Map<?, ?>)) {
            throw new AnyhowError(path + ": invalid type: " + YamlValues.describe(value)
                    + ", expected a path or a map with `path` and `seed`");
        }
        Map<String, Object> map = YamlValues.mapping(value, path);
        String entryPath = null;
        SeedMode seed = null;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = entry.getKey();
            Object raw = entry.getValue();
            if (!WritableConfig.FIELDS.contains(key)) {
                throw new AnyhowError(path + ": " + Expected.unknownField(key, WritableConfig.FIELDS));
            }
            if (key.equals("path")) {
                entryPath = text(raw, path + ".path");
            } else if (raw != YamlValues.NullValue.INSTANCE) {
                seed = variant(raw, path + ".seed", SeedMode::fromWire);
            }
        }
        if (entryPath == null) {
            throw new AnyhowError(path + ": missing field `path`");
        }
        return new WritableConfig(entryPath, seed);
    }

    private static MountConfig mount(Object value, String path) {
        Map<String, Object> map = struct(value, path, "MountConfig");
        String source = null;
        String target = null;
        boolean readOnly = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = entry.getKey();
            Object raw = entry.getValue();
            if (!MountConfig.FIELDS.contains(key)) {
                throw new AnyhowError(path + ": " + Expected.unknownField(key, MountConfig.FIELDS));
            }
            switch (key) {
                case "source" -> source = text(raw, path + ".source");
                case "target" -> target = raw == YamlValues.NullValue.INSTANCE ? null : text(raw, path + ".target");
                default -> readOnly = bool(raw, path + ".read_only");
            }
        }
        if (source == null) {
            throw new AnyhowError(path + ": missing field `source`");
        }
        return new MountConfig(source, target, readOnly);
    }

    private static CacheConfig cache(Object value, String path) {
        Map<String, Object> map = struct(value, path, "CacheConfig");
        String volume = null;
        String target = null;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = entry.getKey();
            if (!CacheConfig.FIELDS.contains(key)) {
                throw new AnyhowError(path + ": " + Expected.unknownField(key, CacheConfig.FIELDS));
            }
            if (key.equals("volume")) {
                volume = text(entry.getValue(), path + ".volume");
            } else {
                target = text(entry.getValue(), path + ".target");
            }
        }
        if (volume == null) {
            throw new AnyhowError(path + ": missing field `volume`");
        }
        if (target == null) {
            throw new AnyhowError(path + ": missing field `target`");
        }
        return new CacheConfig(volume, target);
    }

    private static ContainerEnvConfig env(Object value, String path) {
        Map<String, Object> map = struct(value, path, "ContainerEnvConfig");
        List<String> pass = null;
        SortedMap<String, String> set = null;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = entry.getKey();
            Object raw = entry.getValue();
            if (!ContainerEnvConfig.FIELDS.contains(key)) {
                throw new AnyhowError(path + ": " + Expected.unknownField(key, ContainerEnvConfig.FIELDS));
            }
            if (raw == YamlValues.NullValue.INSTANCE) {
                continue;
            }
            if (key.equals("pass")) {
                pass = texts(raw, path + ".pass");
            } else {
                if (!(raw instanceof Map<?, ?>)) {
                    throw new AnyhowError(
                            path + ".set: invalid type: " + YamlValues.describe(raw) + ", expected a map");
                }
                set = new TreeMap<>(RustOrdering.STRING_ORDER);
                for (Map.Entry<String, Object> variable :
                        YamlValues.mapping(raw, path + ".set").entrySet()) {
                    set.put(variable.getKey(), text(variable.getValue(), path + ".set." + variable.getKey()));
                }
            }
        }
        return new ContainerEnvConfig(pass, set);
    }

    private static Map<String, Object> struct(Object value, String path, String name) {
        if (!(value instanceof Map<?, ?>)) {
            throw new AnyhowError(path + ": invalid type: " + YamlValues.describe(value) + ", expected struct " + name);
        }
        return YamlValues.mapping(value, path);
    }

    private static <T> List<T> sequence(Object value, String path, BiFunction<Object, String, T> element) {
        if (!(value instanceof List<?> list)) {
            throw new AnyhowError(path + ": invalid type: " + YamlValues.describe(value) + ", expected a sequence");
        }
        List<T> result = new ArrayList<>();
        int index = 0;
        for (Object item : list) {
            result.add(element.apply(item == null ? YamlValues.NullValue.INSTANCE : item, path + "[" + index + "]"));
            index++;
        }
        return result;
    }

    private static List<String> texts(Object value, String path) {
        return sequence(value, path, ContainerYaml::text);
    }

    /** A plain scalar read as a string, the way serde_yaml reads it into a {@code String}. */
    private static String text(Object value, String path) {
        return switch (value) {
            case String text -> text;
            case Boolean bool -> bool.toString();
            case Integer number -> number.toString();
            case Long number -> number.toString();
            case BigInteger number -> number.toString();
            case Double number -> RustFloatFormat.display(number);
            default ->
                throw new AnyhowError(path + ": invalid type: " + YamlValues.describe(value) + ", expected a string");
        };
    }

    private static <T> T variant(Object value, String path, java.util.function.Function<String, T> fromWire) {
        String text = text(value, path);
        try {
            return fromWire.apply(text);
        } catch (AnyhowError error) {
            throw new AnyhowError(path + ": " + error.getMessage(), null);
        }
    }

    private static boolean bool(Object value, String path) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        throw new AnyhowError(path + ": invalid type: " + YamlValues.describe(value) + ", expected a boolean");
    }

    private static String quantity(Object value, String path) {
        return switch (value) {
            case String text -> text;
            case Integer number -> number.toString();
            case Long number -> number.toString();
            case BigInteger number -> number.toString();
            case Double number -> RustFloatFormat.display(number);
            default ->
                throw new AnyhowError(
                        path + ": invalid type: " + YamlValues.describe(value) + ", expected a number or a string");
        };
    }

    private static Long timeout(Object value, String path) {
        String text = switch (value) {
            case String string -> string;
            case Integer number -> number.toString();
            case Long number -> number.toString();
            case BigInteger number -> number.toString();
            default ->
                throw new AnyhowError(path + ": invalid type: " + YamlValues.describe(value)
                        + ", expected a duration such as 1800s, 30m or 2h");
        };
        try {
            return Durations.parse(text);
        } catch (AnyhowError error) {
            throw new AnyhowError(path + ": " + error.getMessage(), null);
        }
    }
}
