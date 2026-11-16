package com.cherrytest.common.container;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The {@code container:} block, under {@code defaults} or a package. Every field is optional so
 * that a package block overrides the defaults block field by field.
 */
public record ContainerConfig(
        @Nullable String image,
        @Nullable PullPolicy pull,
        @Nullable NetworkMode network,
        @Nullable String user,
        @Nullable List<String> capDrop,
        @Nullable Boolean noNewPrivileges,
        @Nullable Boolean readOnlyRoot,
        @Nullable String cpus,
        @Nullable String memory,
        @Nullable Long pids,
        @Nullable String shmSize,
        @Nullable List<String> tmpfs,
        @Nullable Long timeout,
        @Nullable WorkspaceAccess workspace,
        @Nullable List<WritableConfig> writable,
        @Nullable List<MountConfig> mounts,
        @Nullable List<CacheConfig> caches,
        @Nullable ContainerEnvConfig env) {
    public static final List<String> FIELDS = List.of(
            "image",
            "pull",
            "network",
            "user",
            "cap_drop",
            "no_new_privileges",
            "read_only_root",
            "cpus",
            "memory",
            "pids",
            "shm_size",
            "tmpfs",
            "timeout",
            "workspace",
            "writable",
            "mounts",
            "caches",
            "env");

    public ContainerConfig {
        capDrop = capDrop == null ? null : List.copyOf(capDrop);
        tmpfs = tmpfs == null ? null : List.copyOf(tmpfs);
        writable = writable == null ? null : List.copyOf(writable);
        mounts = mounts == null ? null : List.copyOf(mounts);
        caches = caches == null ? null : List.copyOf(caches);
    }

    public static ContainerConfig empty() {
        return new ContainerConfig(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null);
    }

    /** Combines the defaults block with a package block; package fields win. */
    public static @Nullable ContainerConfig merged(@Nullable ContainerConfig base, @Nullable ContainerConfig top) {
        if (base == null) {
            return top;
        }
        if (top == null) {
            return base;
        }
        return new ContainerConfig(
                or(top.image, base.image),
                or(top.pull, base.pull),
                or(top.network, base.network),
                or(top.user, base.user),
                or(top.capDrop, base.capDrop),
                or(top.noNewPrivileges, base.noNewPrivileges),
                or(top.readOnlyRoot, base.readOnlyRoot),
                or(top.cpus, base.cpus),
                or(top.memory, base.memory),
                or(top.pids, base.pids),
                or(top.shmSize, base.shmSize),
                or(top.tmpfs, base.tmpfs),
                or(top.timeout, base.timeout),
                or(top.workspace, base.workspace),
                or(top.writable, base.writable),
                or(top.mounts, base.mounts),
                or(top.caches, base.caches),
                ContainerEnvConfig.merged(base.env, top.env));
    }

    static <T> @Nullable T or(@Nullable T preferred, @Nullable T fallback) {
        return preferred != null ? preferred : fallback;
    }
}
