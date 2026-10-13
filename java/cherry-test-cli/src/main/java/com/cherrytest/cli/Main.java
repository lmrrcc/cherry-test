package com.cherrytest.cli;

import com.cherrytest.cli.adapters.Adapters;
import com.cherrytest.cli.adapters.ProcessRunner;
import com.cherrytest.cli.nativeos.Terminal;
import com.cherrytest.common.rustcompat.AnyhowError;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class Main {
    public static final String VERSION = "0.3.16";

    private Main() {}

    public static void main(String[] arguments) {
        int code;
        try {
            code = run(List.of(arguments));
        } catch (CommandLine.UsageError error) {
            Terminal.ERR.print(
                    "error: " + error.getMessage()
                            + "\n\nUsage: cherry-test [OPTIONS] [FILTER] [-- <CARGO_TEST_ARGS>...]\n       cherry-test <COMMAND>\n\nFor more information, try '--help'.\n");
            Terminal.ERR.flush();
            code = 2;
        } catch (RuntimeException error) {
            Terminal.ERR.print("Error: " + AnyhowError.debug(error) + "\n");
            Terminal.ERR.flush();
            code = 1;
        }
        System.exit(code);
    }

    static int run(List<String> arguments) {
        CommandLine.Parsed parsed = CommandLine.parse(arguments);
        return switch (parsed) {
            case CommandLine.Help help -> {
                Terminal.print(help.text());
                yield 0;
            }
            case CommandLine.Version ignored -> {
                Terminal.println("cherry-test " + VERSION);
                yield 0;
            }
            case CommandLine.Update update -> Update.runUpdate(update.repo(), update.branch(), update.quiet());
            case CommandLine.Adapter adapter -> Adapters.cli(adapter.name(), adapter.operation());
            case CommandLine.Run run -> {
                ProcessRunner.installCancellation();
                yield CherryTest.runTests(run.cli(), selfCommand());
            }
        };
    }

    static Adapters.SelfCommand selfCommand() {
        return (name, workingDirectory, stdin) -> {
            String java = ProcessHandle.current().info().command().orElse("java");
            List<String> command = new ArrayList<>();
            command.add("--enable-native-access=ALL-UNNAMED");
            command.add("-cp");
            command.add(absoluteClassPath(System.getProperty("java.class.path"), currentDirectory()));
            command.add(Main.class.getName());
            command.add("adapter");
            command.add(name);
            command.add("run");
            return new ProcessRunner.Spec(java, command, Map.of(), workingDirectory, stdin);
        };
    }

    static String absoluteClassPath(String classPath, Path base) {
        return Arrays.stream(classPath.split(File.pathSeparator, -1))
                .map(entry -> base.resolve(entry).normalize().toString())
                .collect(Collectors.joining(File.pathSeparator));
    }

    static Path currentDirectory() {
        return Path.of("").toAbsolutePath();
    }
}
