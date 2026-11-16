package com.cherrytest.cli;

import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.core.Jobs;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

final class CommandLine {
    private static final List<String> KINDS = List.of("unit", "integration", "functional", "ui", "visual", "mutation");

    sealed interface Parsed permits Run, Update, Adapter, Version, Help {}

    record Run(RunCli cli) implements Parsed {}

    record Update(@Nullable String repo, @Nullable String branch, boolean quiet) implements Parsed {}

    record Adapter(String name, String operation) implements Parsed {}

    record Version() implements Parsed {}

    record Help(String text) implements Parsed {}

    static final class UsageError extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final boolean showUsage;

        UsageError(String message) {
            this(message, true);
        }

        /** An error that clap prints without the usage lines, such as an invalid or missing option value. */
        UsageError(String message, boolean showUsage) {
            super(message, null, false, false);
            this.showUsage = showUsage;
        }

        boolean showUsage() {
            return showUsage;
        }
    }

    private CommandLine() {}

    static Parsed parse(List<String> arguments) {
        if (!arguments.isEmpty() && arguments.getFirst().equals("update")) {
            return parseUpdate(arguments.subList(1, arguments.size()));
        }
        if (!arguments.isEmpty() && arguments.getFirst().equals("adapter")) {
            return parseAdapter(arguments.subList(1, arguments.size()));
        }
        String filter = null;
        Path root = null;
        boolean initOnly = false;
        boolean forceInit = false;
        boolean noCoverage = false;
        boolean noCrap = false;
        String execution = null;
        boolean strict = false;
        List<String> kinds = new ArrayList<>();
        boolean noHtml = false;
        boolean noOpen = false;
        boolean noBanner = false;
        boolean isolate = false;
        boolean noIsolate = false;
        boolean keepGoing = false;
        boolean quiet = false;
        boolean verbose = false;
        Jobs jobs = null;
        Shard shard = null;
        List<String> trailing = new ArrayList<>();
        int index = 0;
        while (index < arguments.size()) {
            String argument = arguments.get(index);
            if (argument.equals("--")) {
                trailing.addAll(arguments.subList(index + 1, arguments.size()));
                break;
            }
            String value = null;
            String name = argument;
            int equals = argument.indexOf('=');
            if (argument.startsWith("--") && equals > 0) {
                name = argument.substring(0, equals);
                value = argument.substring(equals + 1);
            }
            switch (name) {
                case "-h", "--help" -> {
                    return new Help(helpText());
                }
                case "-V", "--version" -> {
                    return new Version();
                }
                case "--root" -> {
                    String rootValue = value != null ? value : requireValue(arguments, ++index, "--root <ROOT>");
                    root = Path.of(rootValue);
                }
                case "--init-only" -> initOnly = true;
                case "--force-init" -> forceInit = true;
                case "--no-coverage" -> noCoverage = true;
                case "--no-crap" -> noCrap = true;
                case "--execution" -> {
                    String executionValue =
                            value != null ? value : requireValue(arguments, ++index, "--execution <EXECUTION>");
                    if (!executionValue.equals("local") && !executionValue.equals("container")) {
                        throw new UsageError(
                                "invalid value '" + executionValue
                                        + "' for '--execution <EXECUTION>'\n  [possible values: local, container]",
                                false);
                    }
                    execution = executionValue;
                }
                case "--strict" -> strict = true;
                case "--kinds" -> {
                    String kindsValue = value != null ? value : requireValue(arguments, ++index, "--kinds <KINDS>");
                    for (String kind : kindsValue.split(",", -1)) {
                        if (!KINDS.contains(kind)) {
                            throw new UsageError(
                                    "invalid value '" + kind
                                            + "' for '--kinds <KINDS>'\n  [possible values: unit, integration, functional, ui, visual, mutation]",
                                    false);
                        }
                        kinds.add(kind);
                    }
                }
                case "--jobs" -> {
                    String jobsValue = value != null ? value : requireValue(arguments, ++index, "--jobs <N|auto>");
                    try {
                        jobs = Jobs.parse(jobsValue);
                    } catch (IllegalArgumentException invalid) {
                        throw new UsageError(
                                "invalid value '" + jobsValue + "' for '--jobs <N|auto>': " + Jobs.EXPECTED, false);
                    }
                }
                case "--shard" -> {
                    String shardValue = value != null ? value : requireValue(arguments, ++index, "--shard <I/N>");
                    try {
                        shard = Shard.parse(shardValue);
                    } catch (IllegalArgumentException invalid) {
                        throw new UsageError(
                                "invalid value '" + shardValue + "' for '--shard <I/N>': " + Shard.EXPECTED, false);
                    }
                }
                case "--no-html" -> noHtml = true;
                case "--no-open" -> noOpen = true;
                case "--no-banner" -> noBanner = true;
                case "--isolate" -> {
                    isolate = true;
                    noIsolate = false;
                }
                case "--no-isolate" -> {
                    noIsolate = true;
                    isolate = false;
                }
                case "--keep-going" -> keepGoing = true;
                case "-q", "--quiet" -> quiet = true;
                case "-v", "--verbose" -> verbose = true;
                default -> {
                    if (argument.startsWith("-") && !argument.equals("-")) {
                        if (!argument.startsWith("--") && argument.length() > 2) {
                            for (char flag : argument.substring(1).toCharArray()) {
                                switch (flag) {
                                    case 'q' -> quiet = true;
                                    case 'v' -> verbose = true;
                                    default -> throw new UsageError("unexpected argument '-" + flag + "' found");
                                }
                            }
                        } else {
                            throw new UsageError("unexpected argument '" + argument + "' found");
                        }
                    } else if (filter == null) {
                        filter = argument;
                    } else {
                        throw new UsageError("unexpected argument '" + argument + "' found");
                    }
                }
            }
            index++;
        }
        return new Run(new RunCli(
                filter,
                root,
                initOnly,
                forceInit,
                noCoverage,
                noCrap,
                execution,
                strict,
                kinds,
                jobs,
                shard,
                noHtml,
                noOpen,
                noBanner,
                isolate,
                noIsolate,
                keepGoing,
                quiet,
                verbose,
                trailing));
    }

    private static String requireValue(List<String> arguments, int index, String usage) {
        if (index >= arguments.size()) {
            throw new UsageError("a value is required for '" + usage + "' but none was supplied", false);
        }
        return arguments.get(index);
    }

    private static Parsed parseUpdate(List<String> arguments) {
        String repo = null;
        String branch = null;
        boolean quiet = false;
        for (int index = 0; index < arguments.size(); index++) {
            String argument = arguments.get(index);
            switch (argument) {
                case "--repo" -> repo = requireValue(arguments, ++index, "--repo <REPO>");
                case "--branch" -> branch = requireValue(arguments, ++index, "--branch <BRANCH>");
                case "-q", "--quiet" -> quiet = true;
                case "-h", "--help" -> {
                    return new Help(updateHelpText());
                }
                default -> {
                    if (argument.startsWith("--repo=")) {
                        repo = argument.substring("--repo=".length());
                    } else if (argument.startsWith("--branch=")) {
                        branch = argument.substring("--branch=".length());
                    } else {
                        throw new UsageError("unexpected argument '" + argument + "' found");
                    }
                }
            }
        }
        return new Update(repo, branch, quiet);
    }

    private static Parsed parseAdapter(List<String> arguments) {
        if (arguments.isEmpty()) {
            throw new UsageError("the following required arguments were not provided:\n  <NAME>");
        }
        if (arguments.size() > 2) {
            throw new UsageError("unexpected argument '" + arguments.get(2) + "' found");
        }
        return new Adapter(arguments.get(0), arguments.size() > 1 ? arguments.get(1) : "run");
    }

    static String helpText() {
        return """
                cherry-test: workspace tests, coverage and browser reports

                Usage: cherry-test [OPTIONS] [FILTER] [-- <CARGO_TEST_ARGS>...]
                       cherry-test <COMMAND>

                Commands:
                  update   Reinstall cherry-test from GitHub
                  adapter  Run a bundled adapter
                  help     Print this message or the help of the given subcommand(s)

                Arguments:
                  [FILTER]              Project group id (e.g. mini), package name (e.g. mini-rs-api) or @group
                  [CARGO_TEST_ARGS]...  Extra args forwarded to cargo test

                Options:
                      --root <ROOT>            Repo root directory (default: auto-detect from cwd)
                      --init-only              Create cherry-test.yaml from discovered packages and exit
                      --force-init             Regenerate cherry-test.yaml even when it already exists
                      --no-coverage
                      --no-crap                Do not calculate cyclomatic complexity or report CRAP
                      --execution <EXECUTION>  [possible values: local, container]
                      --strict
                      --kinds <KINDS>          Phases to run. mutation runs only when it is named here [possible values: unit, integration, functional, ui, visual, mutation]
                      --jobs <N|auto>          Jobs to run at once, or auto for one per CPU (default: defaults.parallel.jobs, else 1)
                      --shard <I/N>            Run only shard I of N; packages are spread by recorded durations
                      --no-html
                      --no-open                Do not open the HTML report in a browser
                      --no-banner              Do not print the startup banner
                      --isolate                Rerun each failed test alone to classify it as REAL, INTERFERENCE, FLAKY or INCONCLUSIVE (default: defaults.isolate.on_failure)
                      --no-isolate             Do not rerun failed tests alone
                      --keep-going             Continue after a project failure
                  -q, --quiet
                  -v, --verbose                Print each suite and test result in the terminal
                  -h, --help                   Print help
                  -V, --version                Print version
                """;
    }

    static String updateHelpText() {
        return """
                Reinstall cherry-test from GitHub

                Usage: cherry-test update [OPTIONS]

                Options:
                      --repo <REPO>      Git repository URL (default: CHERRY_TEST_REPO or https://github.com/lmrrcc/cherry-test)
                      --branch <BRANCH>  Branch to install (default: CHERRY_TEST_VERSION or main)
                  -q, --quiet
                  -h, --help             Print help
                """;
    }

    static AnyhowError usage(UsageError error) {
        return new AnyhowError(error.getMessage() == null ? "invalid arguments" : error.getMessage());
    }
}
