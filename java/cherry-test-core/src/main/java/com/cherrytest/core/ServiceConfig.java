package com.cherrytest.core;

import com.cherrytest.common.container.PullPolicy;
import com.cherrytest.common.rustcompat.RustOrdering;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * {@code projects.<id>.services.<name>}: a Compose service ({@code service}) or a service Cherry Test starts from
 * {@code image}.
 */
public record ServiceConfig(
        String service,
        int port,
        @Nullable ServiceHealth health,
        @Nullable String kind,
        @Nullable String image,
        @Nullable PullPolicy pull,
        SortedMap<String, String> env,
        List<String> tmpfs,
        List<ServiceMount> mounts) {
    public ServiceConfig {
        TreeMap<String, String> sorted = new TreeMap<>(RustOrdering.STRING_ORDER);
        sorted.putAll(env);
        env = sorted;
        tmpfs = List.copyOf(tmpfs);
        mounts = List.copyOf(mounts);
    }

    /** A Compose service, as discovery writes it. */
    public static ServiceConfig compose(
            String service, int port, @Nullable ServiceHealth health, @Nullable String kind) {
        return new ServiceConfig(service, port, health, kind, null, null, new TreeMap<>(), List.of(), List.of());
    }

    /** Whether Cherry Test starts this service from an image instead of Docker Compose. */
    public boolean isImage() {
        return image != null;
    }
}
