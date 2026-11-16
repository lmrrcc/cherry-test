package com.cherrytest.common.container;

import com.cherrytest.common.rustcompat.RustOrdering;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/** One image service started for a job, or for a scope in local execution. */
public record SidecarService(
        String project,
        String service,
        String name,
        String image,
        PullPolicy pull,
        int port,
        String kind,
        SortedMap<String, String> env,
        List<String> tmpfs,
        List<BindMount> mounts,
        HealthCheck health) {
    public SidecarService {
        TreeMap<String, String> sorted = new TreeMap<>(RustOrdering.STRING_ORDER);
        sorted.putAll(env);
        env = sorted;
        tmpfs = List.copyOf(tmpfs);
        mounts = List.copyOf(mounts);
    }
}
