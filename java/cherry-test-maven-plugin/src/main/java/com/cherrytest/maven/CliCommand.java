package com.cherrytest.maven;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

final class CliCommand {
    static final String MAIN_CLASS = "com.cherrytest.cli.Main";

    private CliCommand() {}

    static List<String> of(Path java, List<Path> classpath, String arguments) {
        List<String> command = new ArrayList<>(List.of(
                java.toString(),
                "--enable-native-access=ALL-UNNAMED",
                "-cp",
                classpath.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator)),
                MAIN_CLASS));
        command.addAll(arguments(arguments));
        return List.copyOf(command);
    }

    static List<String> arguments(String arguments) {
        return Arrays.stream(arguments.strip().split("\\s+"))
                .filter(argument -> !argument.isEmpty())
                .toList();
    }
}
