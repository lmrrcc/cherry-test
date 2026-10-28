package com.cherrytest.cli.adapters;

import com.cherrytest.cli.nativeos.Terminal;
import com.cherrytest.common.adapter.AdapterRequest;
import com.cherrytest.common.adapter.ExecutionMode;
import com.cherrytest.common.container.BindMount;
import com.cherrytest.common.container.CacheConfig;
import com.cherrytest.common.container.CacheVolume;
import com.cherrytest.common.container.ContainerConfig;
import com.cherrytest.common.container.ContainerEnvConfig;
import com.cherrytest.common.container.ContainerSpec;
import com.cherrytest.common.container.Durations;
import com.cherrytest.common.container.MountConfig;
import com.cherrytest.common.container.NetworkMode;
import com.cherrytest.common.container.PullPolicy;
import com.cherrytest.common.container.SeedMode;
import com.cherrytest.common.container.WorkspaceAccess;
import com.cherrytest.common.container.WritableConfig;
import com.cherrytest.common.container.WritableMount;
import com.cherrytest.common.process.CommandOutput;
import com.cherrytest.common.process.ProcessEnvironment;
import com.cherrytest.common.process.SystemCommand;
import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.common.rustcompat.RustIoError;
import com.cherrytest.common.rustcompat.RustOrdering;
import com.cherrytest.common.rustcompat.RustText;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.LongPredicate;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Hardened container execution: resolves the {@code container:} block for one package phase,
 * builds the {@code docker run} command line, and removes containers when runs end or die.
 */
public final class Container {
    /** Label that carries the run id of every container Cherry Test starts. */
    public static final String RUN_LABEL = "cherry-test.run";
    /** Label that carries the {@code project/package/phase} key of the job. */
    public static final String JOB_LABEL = "cherry-test.job";
    /** Label that carries the process id of the Cherry Test run that owns the container. */
    public static final String PID_LABEL = "cherry-test.pid";
    /** Environment variable that passes the owning run's process id to adapter processes. */
    public static final String OWNER_PID_ENV = "CHERRY_TEST_OWNER_PID";

    private Container() {}

    /** Host-side facts for one container invocation that are not part of the resolved spec. */
    public record ContainerCall(
            String program,
            List<String> args,
            long ownerPid,
            @Nullable String user,
            List<String> authNames) {
        public ContainerCall {
            args = List.copyOf(args);
            authNames = List.copyOf(authNames);
        }
    }

    /**
     * Resolves the container settings of a package phase, or {@code null} for local execution or
     * when neither the defaults nor the package declare a {@code container:} block.
     */
    public static @Nullable ContainerSpec resolve(
            @Nullable ContainerConfig defaults,
            @Nullable ContainerConfig packageConfig,
            AdapterRequest request,
            String runId,
            Path runRoot) {
        if (request.execution() != ExecutionMode.CONTAINER) {
            return null;
        }
        ContainerConfig config = ContainerConfig.merged(defaults, packageConfig);
        if (config == null) {
            return null;
        }
        Path mountRoot = ProcessRunner.mountRoot(request).normalize();
        Path root = request.root().normalize();
        String image = config.image();
        if (image == null) {
            JsonNode option = request.options().get("image");
            if (option != null && option.isString()) {
                image = option.stringValue();
            }
        }
        if (image == null) {
            throw new AnyhowError("container execution requires container.image or options.image");
        }
        if (image.isEmpty() || image.startsWith("-")) {
            throw new AnyhowError("container.image: invalid image `" + image + "`");
        }
        String user = config.user();
        if (user != null && !user.equals("host") && !isUserName(user)) {
            throw new AnyhowError("container.user: `" + user + "` is not `host` or a user[:group] name or id");
        }
        List<String> configuredCapDrop = config.capDrop();
        List<String> capDrop = configuredCapDrop == null ? List.of("ALL") : configuredCapDrop;
        for (String capability : capDrop) {
            if (capability.isEmpty() || !capability.chars().allMatch(c -> isAsciiAlphanumeric(c) || c == '_')) {
                throw new AnyhowError("container.cap_drop: `" + capability + "` is not a capability name");
            }
        }
        String cpus = quantity("cpus", config.cpus());
        String memory = quantity("memory", config.memory());
        String shmSize = quantity("shm_size", config.shmSize());
        List<String> configuredTmpfs = config.tmpfs();
        List<String> tmpfs = configuredTmpfs == null ? List.of() : configuredTmpfs;
        for (String path : tmpfs) {
            if (!path.startsWith("/") || path.codePoints().anyMatch(RustText::isWhitespace)) {
                throw new AnyhowError("container.tmpfs: `" + path + "` is not an absolute container path");
            }
        }
        Path jobDir = runRoot.resolve("jobs")
                .resolve(request.project())
                .resolve(request.packageName())
                .resolve(request.phase())
                .resolve("w");
        List<WritableMount> writable = new ArrayList<>();
        List<WritableConfig> configuredWritable = config.writable();
        for (WritableConfig entry : configuredWritable == null ? List.<WritableConfig>of() : configuredWritable) {
            String context = "container.writable: `" + entry.path() + "`";
            Path relative = withContext(context, () -> workspaceRelative(entry.path()));
            Path origin = root.resolve(relative);
            String target = withContext(context, () -> containerTarget(mountRoot, origin));
            writable.add(new WritableMount(origin, jobDir.resolve(relative), target, entry.seed() == SeedMode.COPY));
        }
        List<BindMount> mounts = new ArrayList<>();
        List<MountConfig> configuredMounts = config.mounts();
        for (MountConfig entry : configuredMounts == null ? List.<MountConfig>of() : configuredMounts) {
            if (entry.source().isEmpty() || entry.source().contains(":")) {
                throw new AnyhowError("container.mounts: invalid source `" + entry.source() + "`");
            }
            Path source = root.resolve(entry.source()).normalize();
            String explicit = entry.target();
            String target = explicit != null
                    ? withContext("container.mounts: target `" + explicit + "`", () -> absoluteTarget(explicit))
                    : withContext(
                            "container.mounts: source `" + entry.source() + "`",
                            () -> defaultMountTarget(mountRoot, source));
            mounts.add(new BindMount(source, target, entry.readOnly()));
        }
        List<CacheVolume> caches;
        List<CacheConfig> configuredCaches = config.caches();
        if (configuredCaches != null) {
            caches = new ArrayList<>();
            for (CacheConfig cache : configuredCaches) {
                if (!isVolumeName(cache.volume())) {
                    throw new AnyhowError("container.caches: `" + cache.volume() + "` is not a volume name");
                }
                String target = withContext(
                        "container.caches: target `" + cache.target() + "`", () -> absoluteTarget(cache.target()));
                caches.add(new CacheVolume(cache.volume(), target));
            }
        } else {
            caches = defaultCaches(image);
        }
        ContainerEnvConfig env = config.env();
        List<String> configuredPass = env == null ? null : env.pass();
        List<String> envPass = configuredPass == null ? List.of() : configuredPass;
        SortedMap<String, String> envSet = new TreeMap<>(RustOrdering.STRING_ORDER);
        SortedMap<String, String> configuredSet = env == null ? null : env.set();
        if (configuredSet != null) {
            envSet.putAll(configuredSet);
        }
        PullPolicy pull = config.pull();
        NetworkMode network = config.network();
        Boolean noNewPrivileges = config.noNewPrivileges();
        Boolean readOnlyRoot = config.readOnlyRoot();
        Long timeout = config.timeout();
        WorkspaceAccess workspace = config.workspace();
        List<String> names = new ArrayList<>(envPass);
        names.addAll(envSet.keySet());
        for (String name : names) {
            if (!isEnvName(name)) {
                throw new AnyhowError("container.env: `" + name + "` is not a variable name");
            }
        }
        return new ContainerSpec(
                containerName(runId, request.project(), request.packageName(), request.phase()),
                runId,
                request.project() + "/" + request.packageName() + "/" + request.phase(),
                image,
                pull == null ? PullPolicy.NEVER : pull,
                network == null ? NetworkMode.NONE : network,
                user,
                capDrop,
                noNewPrivileges == null || noNewPrivileges,
                readOnlyRoot != null && readOnlyRoot,
                cpus,
                memory,
                config.pids(),
                shmSize,
                tmpfs,
                timeout == null ? Durations.DEFAULT_TIMEOUT_SECS : timeout,
                workspace == null ? WorkspaceAccess.RO : workspace,
                mountRoot,
                List.of(request.output()),
                writable,
                mounts,
                caches,
                envPass,
                envSet);
    }

    /**
     * Builds the {@code docker run} arguments for one tool invocation. Pure: the same spec, request
     * and call always produce the same command line.
     */
    public static List<String> containerArgv(ContainerSpec spec, AdapterRequest request, ContainerCall call) {
        String mountRoot = spec.mountRoot().toString();
        List<String> argv = new ArrayList<>(List.of("run", "--rm", "--init"));
        push(argv, "--name", spec.name());
        push(argv, "--label", RUN_LABEL + "=" + spec.run());
        push(argv, "--label", JOB_LABEL + "=" + spec.job());
        push(argv, "--label", PID_LABEL + "=" + call.ownerPid());
        push(argv, "--pull", spec.pull().wireName());
        push(argv, "--network", spec.network().wireName());
        if (spec.network() != NetworkMode.NONE) {
            push(argv, "--add-host", "host.docker.internal:host-gateway");
        }
        String user = call.user();
        if (user != null) {
            push(argv, "--user", user);
        }
        for (String capability : spec.capDrop()) {
            push(argv, "--cap-drop", capability);
        }
        if (spec.noNewPrivileges()) {
            push(argv, "--security-opt", "no-new-privileges");
        }
        if (spec.readOnlyRoot()) {
            argv.add("--read-only");
        }
        String cpus = spec.cpus();
        if (cpus != null) {
            push(argv, "--cpus", cpus);
        }
        String memory = spec.memory();
        if (memory != null) {
            push(argv, "--memory", memory);
        }
        Long pids = spec.pids();
        if (pids != null) {
            push(argv, "--pids-limit", Long.toUnsignedString(pids));
        }
        String shmSize = spec.shmSize();
        if (shmSize != null) {
            push(argv, "--shm-size", shmSize);
        }
        for (String path : spec.tmpfs()) {
            push(argv, "--tmpfs", path);
        }
        push(argv, "-v", mountRoot + ":/workspace" + readOnly(spec.workspace() == WorkspaceAccess.RO));
        for (Path output : spec.outputs()) {
            String host = output.toString();
            push(argv, "-v", host + ":" + map(mountRoot, host));
        }
        for (WritableMount path : spec.writable()) {
            push(argv, "-v", path.source() + ":" + path.target());
        }
        for (BindMount mount : spec.mounts()) {
            push(argv, "-v", mount.source() + ":" + mount.target() + readOnly(mount.readOnly()));
        }
        for (CacheVolume cache : spec.caches()) {
            push(argv, "-v", cache.volume() + ":" + cache.target());
        }
        push(argv, "-w", map(mountRoot, request.cwd().toString()));
        if ("host".equals(spec.user())) {
            push(argv, "-e", "HOME=/tmp/home");
        }
        for (String name : call.authNames()) {
            push(argv, "-e", name);
        }
        for (Map.Entry<String, String> entry : request.env().entrySet()) {
            String value = map(mountRoot, entry.getValue())
                    .replace("127.0.0.1:", "host.docker.internal:")
                    .replace("localhost:", "host.docker.internal:");
            push(argv, "-e", entry.getKey() + "=" + value);
        }
        for (String name : spec.envPass()) {
            push(argv, "-e", name);
        }
        for (Map.Entry<String, String> entry : spec.envSet().entrySet()) {
            push(argv, "-e", entry.getKey() + "=" + entry.getValue());
        }
        argv.add(spec.image());
        argv.add(map(mountRoot, call.program()));
        for (String argument : call.args()) {
            argv.add(map(mountRoot, argument));
        }
        return argv;
    }

    /**
     * Runs one tool invocation in a container described by {@code spec}. On timeout or cancellation
     * the container is killed by name before the Docker client is stopped.
     */
    public static boolean run(
            AdapterRequest request, ContainerSpec spec, String program, List<String> arguments, String label) {
        prepare(spec);
        String user = "host".equals(spec.user()) ? hostUser() : spec.user();
        List<String> authNames = new ArrayList<>();
        for (String key : ProcessEnvironment.snapshot().keySet()) {
            if (key.startsWith("CHERRY_TEST_AUTH_")) {
                authNames.add(key);
            }
        }
        authNames.sort(RustOrdering.STRING_ORDER);
        ContainerCall call = new ContainerCall(program, arguments, ownerPid(), user, authNames);
        ProcessRunner.Spec docker =
                new ProcessRunner.Spec("docker", containerArgv(spec, request, call), Map.of(), null, null);
        String name = spec.name();
        return ProcessRunner.capture(
                docker, request.output().resolve(label), spec.timeoutSecs(), false, () -> kill(name));
    }

    /**
     * Creates the output and per-job writable directories, seeds writable paths that ask for it, and
     * checks that extra mount sources exist.
     */
    public static void prepare(ContainerSpec spec) {
        for (Path output : spec.outputs()) {
            createDirectories(output);
        }
        for (WritableMount path : spec.writable()) {
            if (Files.exists(path.origin()) && !Files.isDirectory(path.origin())) {
                throw new AnyhowError("writable path " + path.origin() + " is not a directory");
            }
            createDirectories(path.origin());
            if (!Files.exists(path.source())) {
                createDirectories(path.source());
                if (path.seed()) {
                    copyTree(path.origin(), path.source());
                }
            }
        }
        for (BindMount mount : spec.mounts()) {
            if (!Files.exists(mount.source())) {
                throw new AnyhowError("mount source " + mount.source() + " does not exist");
            }
        }
    }

    /** Docker container name for a job: {@code ct-<run>-<project>-<package>-<phase>}, sanitized. */
    public static String containerName(String runId, String project, String packageName, String phase) {
        return sanitizeName("ct-" + runId + "-" + project + "-" + packageName + "-" + phase);
    }

    /**
     * Replaces every character Docker does not accept in a container name with {@code -}, and makes
     * sure the name starts with a letter or digit.
     */
    public static String sanitizeName(String raw) {
        StringBuilder name = new StringBuilder();
        raw.codePoints()
                .forEach(c -> name.append(
                        isAsciiAlphanumeric(c) || c == '_' || c == '.' || c == '-' ? Character.toString(c) : "-"));
        if (!name.isEmpty() && isAsciiAlphanumeric(name.charAt(0))) {
            return name.toString();
        }
        return "ct" + name;
    }

    /** Volumes kept for well-known toolchain images when {@code caches} is not configured. */
    public static List<CacheVolume> defaultCaches(String image) {
        List<CacheVolume> caches = new ArrayList<>();
        if (image.startsWith("maven:")) {
            caches.add(new CacheVolume("cherry-test-polyglot-maven", "/root/.m2"));
        }
        if (image.startsWith("gradle:")) {
            caches.add(new CacheVolume("cherry-test-polyglot-gradle", "/home/gradle/.gradle"));
        }
        if (image.startsWith("cherry-test-rust:")) {
            caches.add(new CacheVolume("cherry-test-polyglot-cargo", "/usr/local/cargo/registry"));
        }
        return caches;
    }

    /** Process id recorded in the owner label: the run that started the adapter. */
    public static long ownerPid() {
        String value = ProcessEnvironment.get(OWNER_PID_ENV);
        if (value != null) {
            try {
                return Integer.toUnsignedLong(Integer.parseUnsignedInt(value));
            } catch (NumberFormatException ignored) {
                return ProcessHandle.current().pid();
            }
        }
        return ProcessHandle.current().pid();
    }

    /** Stops a container by name. A container that already ended is not an error. */
    public static void kill(String name) {
        try {
            SystemCommand.of("docker").args("kill", name).output();
        } catch (IOException exception) {
            System.getLogger(Container.class.getName())
                    .log(System.Logger.Level.WARNING, "could not run docker kill for " + name, exception);
        }
    }

    /** Run start: removes containers left behind by runs that died, and reports what it removed. */
    public static void startRun(String runId, boolean quiet) {
        try {
            int count = reapStale(runId);
            if (count > 0 && !quiet) {
                Terminal.println("==> removed " + count + " leftover containers of earlier runs");
            }
        } catch (RuntimeException error) {
            Terminal.eprintln("warning: could not remove leftover containers: " + AnyhowError.alternate(error));
        }
    }

    /** Run end: removes every container this run started that is still present. */
    public static void finishRun(String runId) {
        try {
            removeRun(runId);
        } catch (RuntimeException error) {
            Terminal.eprintln(
                    "warning: could not remove containers of run " + runId + ": " + AnyhowError.alternate(error));
        }
    }

    /**
     * Removes containers of earlier runs whose owning process is gone. Containers of the current run
     * and containers without an owner label are kept.
     */
    public static int reapStale(String runId) {
        String listing = dockerOutput(List.of(
                "ps",
                "-a",
                "--filter",
                "label=" + RUN_LABEL,
                "--format",
                "{{.ID}}\t{{.Label \"" + RUN_LABEL + "\"}}\t{{.Label \"" + PID_LABEL + "\"}}"));
        List<String> stale = staleContainers(listing, runId, Container::processAlive);
        remove(stale);
        return stale.size();
    }

    /** Removes every container labelled with this run. */
    public static int removeRun(String runId) {
        String listing = dockerOutput(List.of("ps", "-aq", "--filter", "label=" + RUN_LABEL + "=" + runId));
        List<String> ids = new ArrayList<>();
        for (String line : RustText.lines(listing)) {
            String id = RustText.trim(line);
            if (!id.isEmpty()) {
                ids.add(id);
            }
        }
        remove(ids);
        return ids.size();
    }

    /**
     * Selects container ids from {@code docker ps} lines {@code <id>\t<run>\t<pid>} that belong to
     * another run whose owner process is no longer alive.
     */
    public static List<String> staleContainers(String listing, String runId, LongPredicate alive) {
        List<String> stale = new ArrayList<>();
        for (String line : RustText.lines(listing)) {
            String[] fields = line.split("\t", -1);
            if (fields.length < 3) {
                continue;
            }
            String id = RustText.trim(fields[0]);
            String run = RustText.trim(fields[1]);
            long pid;
            try {
                pid = Integer.toUnsignedLong(Integer.parseUnsignedInt(RustText.trim(fields[2])));
            } catch (NumberFormatException ignored) {
                continue;
            }
            if (!id.isEmpty() && !run.equals(runId) && !alive.test(pid)) {
                stale.add(id);
            }
        }
        return stale;
    }

    private static void remove(List<String> ids) {
        if (ids.isEmpty()) {
            return;
        }
        List<String> args = new ArrayList<>(List.of("rm", "-f"));
        args.addAll(ids);
        CommandOutput output;
        try {
            output = SystemCommand.of("docker").args(args).output();
        } catch (IOException exception) {
            throw AnyhowError.context("start `docker rm`", new AnyhowError(RustIoError.display(exception)));
        }
        if (!output.success()) {
            throw new AnyhowError("docker rm failed: " + RustText.trim(output.stderrLossy()));
        }
    }

    private static String dockerOutput(List<String> args) {
        CommandOutput output;
        try {
            output = SystemCommand.of("docker").args(args).output();
        } catch (IOException exception) {
            throw AnyhowError.context("start `docker ps`", new AnyhowError(RustIoError.display(exception)));
        }
        if (!output.success()) {
            throw new AnyhowError("docker ps failed: " + RustText.trim(output.stderrLossy()));
        }
        return output.stdoutLossy();
    }

    private static boolean processAlive(long pid) {
        try {
            return SystemCommand.of("ps")
                    .args("-p", Long.toString(pid))
                    .output()
                    .success();
        } catch (IOException exception) {
            return true;
        }
    }

    private static String hostUser() {
        return id("-u") + ":" + id("-g");
    }

    private static String id(String flag) {
        CommandOutput output;
        try {
            output = SystemCommand.of("id").arg(flag).output();
        } catch (IOException exception) {
            throw AnyhowError.context("start `id`", new AnyhowError(RustIoError.display(exception)));
        }
        String value = RustText.trim(output.stdoutLossy());
        if (!output.success() || value.isEmpty() || !value.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw new AnyhowError("could not read the host user id with `id " + flag + "`");
        }
        return value;
    }

    private static void copyTree(Path from, Path to) {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(from)) {
            for (Path entry : entries) {
                Path name = entry.getFileName();
                Path target = to.resolve(name == null ? "" : name.toString());
                if (Files.isSymbolicLink(entry)) {
                    Files.createSymbolicLink(target, Files.readSymbolicLink(entry));
                } else if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(target);
                    copyTree(entry, target);
                } else {
                    Files.copy(entry, target, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        } catch (IOException exception) {
            throw AnyhowError.context("read " + from, new AnyhowError(RustIoError.display(exception)));
        }
    }

    private static void createDirectories(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException exception) {
            throw AnyhowError.context("create " + directory, new AnyhowError(RustIoError.display(exception)));
        }
    }

    private static @Nullable String quantity(String field, @Nullable String value) {
        if (value != null && !isQuantity(value)) {
            throw new AnyhowError("container." + field + ": `" + value + "` is not a number or size");
        }
        return value;
    }

    private static Path workspaceRelative(String path) {
        if (path.isEmpty() || path.contains(":") || path.startsWith("/")) {
            throw new AnyhowError("must be a path inside the workspace");
        }
        Path relative = Path.of("");
        for (Path part : Path.of(path)) {
            String name = part.toString();
            if (name.equals("..")) {
                throw new AnyhowError("must be a path inside the workspace");
            }
            if (!name.equals(".") && !name.isEmpty()) {
                relative = relative.resolve(name);
            }
        }
        if (relative.toString().isEmpty()) {
            throw new AnyhowError("must be a path inside the workspace");
        }
        return relative;
    }

    private static String containerTarget(Path mountRoot, Path host) {
        if (!host.startsWith(mountRoot)) {
            throw new AnyhowError("must be a path inside the workspace");
        }
        return "/workspace/" + mountRoot.relativize(host);
    }

    private static String defaultMountTarget(Path mountRoot, Path source) {
        if (source.startsWith(mountRoot)) {
            Path inside = mountRoot.relativize(source);
            if (inside.toString().isEmpty()) {
                throw new AnyhowError("is the workspace itself");
            }
            return "/workspace/" + inside;
        }
        Path parent = mountRoot.getParent();
        if (parent != null && source.startsWith(parent) && !source.equals(parent)) {
            return "/" + parent.relativize(source);
        }
        throw new AnyhowError("is more than one level above the workspace; set `target`");
    }

    private static String absoluteTarget(String target) {
        if (!target.startsWith("/") || target.contains(":")) {
            throw new AnyhowError("must be an absolute container path");
        }
        Path normalized = Path.of(target).normalize();
        for (Path part : normalized) {
            if (part.toString().equals("..")) {
                throw new AnyhowError("must be an absolute container path");
            }
        }
        return normalized.toString();
    }

    private static <T> T withContext(String context, java.util.function.Supplier<T> step) {
        try {
            return step.get();
        } catch (AnyhowError error) {
            throw AnyhowError.context(context, error);
        }
    }

    private static void push(List<String> argv, String option, String value) {
        argv.add(option);
        argv.add(value);
    }

    private static String readOnly(boolean flag) {
        return flag ? ":ro" : "";
    }

    private static String map(String mountRoot, String value) {
        return value.replace(mountRoot, "/workspace");
    }

    private static boolean isAsciiAlphanumeric(int c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isAsciiAlphabetic(int c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isUserName(String value) {
        int colon = value.indexOf(':');
        if (colon >= 0) {
            return isUserPart(value.substring(0, colon)) && isUserPart(value.substring(colon + 1));
        }
        return isUserPart(value);
    }

    private static boolean isUserPart(String text) {
        return !text.isEmpty()
                && (isAsciiAlphanumeric(text.charAt(0)) || text.charAt(0) == '_')
                && text.chars().allMatch(c -> isAsciiAlphanumeric(c) || c == '_' || c == '.' || c == '-');
    }

    private static boolean isQuantity(String value) {
        return !value.isEmpty()
                && value.charAt(0) >= '0'
                && value.charAt(0) <= '9'
                && value.chars().allMatch(c -> isAsciiAlphanumeric(c) || c == '.');
    }

    private static boolean isVolumeName(String value) {
        return !value.isEmpty()
                && isAsciiAlphanumeric(value.charAt(0))
                && value.chars().allMatch(c -> isAsciiAlphanumeric(c) || c == '_' || c == '.' || c == '-');
    }

    private static boolean isEnvName(String value) {
        return !value.isEmpty()
                && (isAsciiAlphabetic(value.charAt(0)) || value.charAt(0) == '_')
                && value.chars().allMatch(c -> isAsciiAlphanumeric(c) || c == '_');
    }
}
