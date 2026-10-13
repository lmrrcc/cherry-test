package com.cherrytest.cli.nativeos;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import com.cherrytest.common.rustcompat.AnyhowError;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@SuppressWarnings("restricted")
public final class Libc {
    private static final boolean MAC =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    private static final int O_RDONLY = 0;
    private static final int O_WRONLY = 1;
    private static final int O_CREAT = MAC ? 0x200 : 0x40;
    private static final int O_TRUNC = MAC ? 0x400 : 0x200;
    private static final int POSIX_SPAWN_SETPGROUP = 0x2;
    private static final int WNOHANG = 1;
    private static final long OPAQUE_SIZE = 1024;
    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LOOKUP = LINKER.defaultLookup();
    private static final MethodHandle ISATTY = handle("isatty", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    private static final MethodHandle SPAWN = handle(
            "posix_spawn", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle ATTR_INIT =
            handle("posix_spawnattr_init", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle ATTR_DESTROY =
            handle("posix_spawnattr_destroy", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle ATTR_SETFLAGS =
            handle("posix_spawnattr_setflags", FunctionDescriptor.of(JAVA_INT, ADDRESS, ValueLayout.JAVA_SHORT));
    private static final MethodHandle ATTR_SETPGROUP =
            handle("posix_spawnattr_setpgroup", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle ACTIONS_INIT =
            handle("posix_spawn_file_actions_init", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle ACTIONS_DESTROY =
            handle("posix_spawn_file_actions_destroy", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle ACTIONS_ADDOPEN = handle(
            "posix_spawn_file_actions_addopen",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
    private static final MethodHandle ACTIONS_ADDCHDIR =
            handle("posix_spawn_file_actions_addchdir_np", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle WAITPID =
            handle("waitpid", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT));

    private Libc() {}

    private static MethodHandle handle(String name, FunctionDescriptor descriptor) {
        MemorySegment symbol =
                LOOKUP.find(name).orElseThrow(() -> new IllegalStateException("libc symbol missing: " + name));
        return LINKER.downcallHandle(symbol, descriptor);
    }

    public static boolean isatty(int descriptor) {
        try {
            return (int) ISATTY.invokeExact(descriptor) == 1;
        } catch (Throwable throwable) {
            return false;
        }
    }

    public static int spawnInNewGroup(
            String program,
            List<String> arguments,
            Map<String, String> environment,
            Path workingDirectory,
            Path stdin,
            Path stdout,
            Path stderr) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment attributes = arena.allocate(OPAQUE_SIZE, 16);
            MemorySegment actions = arena.allocate(OPAQUE_SIZE, 16);
            check((int) ATTR_INIT.invokeExact(attributes), "posix_spawnattr_init");
            check((int) ACTIONS_INIT.invokeExact(actions), "posix_spawn_file_actions_init");
            try {
                check(
                        (int) ATTR_SETFLAGS.invokeExact(attributes, (short) POSIX_SPAWN_SETPGROUP),
                        "posix_spawnattr_setflags");
                check((int) ATTR_SETPGROUP.invokeExact(attributes, 0), "posix_spawnattr_setpgroup");
                check(
                        (int) ACTIONS_ADDCHDIR.invokeExact(actions, arena.allocateFrom(workingDirectory.toString())),
                        "chdir");
                check(
                        (int) ACTIONS_ADDOPEN.invokeExact(
                                actions, 0, arena.allocateFrom(stdin.toString()), O_RDONLY, 0),
                        "stdin");
                check(
                        (int) ACTIONS_ADDOPEN.invokeExact(
                                actions, 1, arena.allocateFrom(stdout.toString()), O_WRONLY | O_CREAT | O_TRUNC, 0644),
                        "stdout");
                check(
                        (int) ACTIONS_ADDOPEN.invokeExact(
                                actions, 2, arena.allocateFrom(stderr.toString()), O_WRONLY | O_CREAT | O_TRUNC, 0644),
                        "stderr");
                MemorySegment argv = arena.allocate(ADDRESS, arguments.size() + 2L);
                argv.setAtIndex(ADDRESS, 0, arena.allocateFrom(program));
                for (int index = 0; index < arguments.size(); index++) {
                    argv.setAtIndex(ADDRESS, index + 1L, arena.allocateFrom(arguments.get(index)));
                }
                argv.setAtIndex(ADDRESS, arguments.size() + 1L, MemorySegment.NULL);
                List<Map.Entry<String, String>> entries = List.copyOf(environment.entrySet());
                MemorySegment envp = arena.allocate(ADDRESS, entries.size() + 1L);
                for (int index = 0; index < entries.size(); index++) {
                    Map.Entry<String, String> entry = entries.get(index);
                    envp.setAtIndex(ADDRESS, index, arena.allocateFrom(entry.getKey() + "=" + entry.getValue()));
                }
                envp.setAtIndex(ADDRESS, entries.size(), MemorySegment.NULL);
                MemorySegment pid = arena.allocate(JAVA_INT);
                int status = (int) SPAWN.invokeExact(pid, arena.allocateFrom(program), actions, attributes, argv, envp);
                if (status != 0) {
                    throw new AnyhowError(OsError.describe(status));
                }
                return pid.get(JAVA_INT, 0);
            } finally {
                int ignoredActions = (int) ACTIONS_DESTROY.invokeExact(actions);
                int ignoredAttributes = (int) ATTR_DESTROY.invokeExact(attributes);
                if (ignoredActions != 0 || ignoredAttributes != 0) {
                    Thread.onSpinWait();
                }
            }
        } catch (AnyhowError error) {
            throw error;
        } catch (Throwable throwable) {
            throw new AnyhowError("posix_spawn failed: " + throwable.getMessage(), throwable);
        }
    }

    public static ExitStatus tryWait(int pid) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment status = arena.allocate(JAVA_INT);
            int result = (int) WAITPID.invokeExact(pid, status, WNOHANG);
            if (result == 0) {
                return ExitStatus.RUNNING;
            }
            if (result < 0) {
                return ExitStatus.of(-1);
            }
            return ExitStatus.of(status.get(JAVA_INT, 0));
        } catch (Throwable throwable) {
            throw new AnyhowError("waitpid failed: " + throwable.getMessage(), throwable);
        }
    }

    private static void check(int status, String operation) {
        if (status != 0) {
            throw new AnyhowError(operation + ": " + OsError.describe(status));
        }
    }
}
