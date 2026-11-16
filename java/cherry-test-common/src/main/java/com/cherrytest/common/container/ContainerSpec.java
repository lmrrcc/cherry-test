package com.cherrytest.common.container;

import com.cherrytest.common.rustcompat.RustOrdering;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * The container settings resolved for one package phase. Host paths are absolute; targets are paths
 * inside the container.
 */
public record ContainerSpec(
        String name,
        String run,
        String job,
        String image,
        PullPolicy pull,
        NetworkMode network,
        @Nullable String user,
        List<String> capDrop,
        boolean noNewPrivileges,
        boolean readOnlyRoot,
        @Nullable String cpus,
        @Nullable String memory,
        @Nullable Long pids,
        @Nullable String shmSize,
        List<String> tmpfs,
        long timeoutSecs,
        WorkspaceAccess workspace,
        Path mountRoot,
        List<Path> outputs,
        List<WritableMount> writable,
        List<BindMount> mounts,
        List<CacheVolume> caches,
        List<String> envPass,
        SortedMap<String, String> envSet,
        @Nullable SidecarSpec sidecars) {
    public ContainerSpec {
        capDrop = List.copyOf(capDrop);
        tmpfs = List.copyOf(tmpfs);
        outputs = List.copyOf(outputs);
        writable = List.copyOf(writable);
        mounts = List.copyOf(mounts);
        caches = List.copyOf(caches);
        envPass = List.copyOf(envPass);
        TreeMap<String, String> sorted = new TreeMap<>(RustOrdering.STRING_ORDER);
        sorted.putAll(envSet);
        envSet = sorted;
    }

    /** The same spec with one more host directory that stays writable at its workspace path. */
    public ContainerSpec withOutput(Path output) {
        List<Path> extended = new ArrayList<>(outputs);
        extended.add(output);
        return new ContainerSpec(
                name,
                run,
                job,
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
                timeoutSecs,
                workspace,
                mountRoot,
                extended,
                writable,
                mounts,
                caches,
                envPass,
                envSet,
                sidecars);
    }

    /** The same spec under another container name. */
    public ContainerSpec withName(String replacement) {
        return new ContainerSpec(
                replacement,
                run,
                job,
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
                timeoutSecs,
                workspace,
                mountRoot,
                outputs,
                writable,
                mounts,
                caches,
                envPass,
                envSet,
                sidecars);
    }

    /** The same spec with the job's image services, or none. */
    public ContainerSpec withSidecars(@Nullable SidecarSpec replacement) {
        return new ContainerSpec(
                name,
                run,
                job,
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
                timeoutSecs,
                workspace,
                mountRoot,
                outputs,
                writable,
                mounts,
                caches,
                envPass,
                envSet,
                replacement);
    }
}
