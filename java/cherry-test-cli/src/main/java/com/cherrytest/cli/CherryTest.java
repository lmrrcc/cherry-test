package com.cherrytest.cli;

import com.cherrytest.cli.adapters.Adapters;
import com.cherrytest.cli.coverage.Coverage;
import com.cherrytest.cli.coverage.CoverageIgnore;
import com.cherrytest.cli.coverage.CoverageSummary;
import com.cherrytest.cli.nativeos.Terminal;
import com.cherrytest.cli.report.CoverageTotals;
import com.cherrytest.cli.report.HtmlReport;
import com.cherrytest.cli.report.ReportModel;
import com.cherrytest.cli.report.ReportOutput;
import com.cherrytest.cli.report.ReportSnapshot;
import com.cherrytest.cli.report.Theme;
import com.cherrytest.common.adapter.ExecutionMode;
import com.cherrytest.common.process.ProcessEnvironment;
import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.common.rustcompat.RustText;
import com.cherrytest.core.PackageConfig;
import com.cherrytest.core.PackageRuntime;
import com.cherrytest.core.RepoRoot;
import com.cherrytest.core.TestItConfig;
import com.cherrytest.core.Workspace;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

public final class CherryTest {
    private CherryTest() {}

    static boolean showBanner(RunCli cli) {
        return !cli.noBanner() && !cli.quiet();
    }

    static int runTests(RunCli cli, Adapters.SelfCommand self) {
        if (showBanner(cli)) {
            Terminal.println();
            Terminal.println(Banner.render(Theme.colorsEnabled()));
            Terminal.println();
        }
        List<Path> configRoots = RepoRoot.resolveConfigRoots(cli.root(), cli.filter());
        int exitCode = 0;
        boolean multi = configRoots.size() > 1;
        for (Path configRoot : configRoots) {
            if (multi && !cli.quiet()) {
                Terminal.println("cherry-test: config root " + configRoot);
            }
            int code = runTestsAtRoot(cli, configRoot, self);
            if (code != 0) {
                exitCode = code;
                if (!cli.keepGoing()) {
                    return exitCode;
                }
            }
        }
        return exitCode;
    }

    private static int runTestsAtRoot(RunCli cli, Path workspace, Adapters.SelfCommand self) {
        ProcessEnvironment.set("CHERRY_TEST_WORKSPACE_ROOT", workspace.toString());
        TestItConfig config = TestItConfig.load(workspace);
        boolean needsScaffold = Scaffold.configNeedsScaffold(config, workspace);
        if (cli.forceInit() || needsScaffold) {
            if (needsScaffold && !config.projects().isEmpty() && !cli.quiet()) {
                Terminal.eprintln("cherry-test: regenerated cherry-test.yaml (invalid or missing project layout)");
            }
            config = Scaffold.scaffoldAndWrite(workspace, cli.quiet());
        }
        if (cli.execution() != null) {
            config = config.withExecution(
                    cli.execution().equals("container") ? ExecutionMode.CONTAINER : ExecutionMode.LOCAL);
        }
        config.defaults(config.defaults().withStrict(config.defaults().strict() || cli.strict()));
        if (cli.initOnly()) {
            return 0;
        }
        TestPlan plan;
        try {
            plan = Init.initializeWithConfig(workspace, config, cli.filter(), cli.quiet());
        } catch (AnyhowError error) {
            if (AnyhowError.display(error).contains("no testable workspace members found")) {
                Terminal.eprintln("no testable workspace members found");
                return cli.strict() ? 1 : 0;
            }
            throw error;
        }
        boolean coverageRequested = plan.config().defaults().coverage() && !cli.noCoverage() && !skipCoverageEnv();
        boolean hasRustPackages = false;
        for (ProjectGroup project : plan.projects()) {
            for (WorkspaceMember member : project.members()) {
                PackageConfig packageConfig = plan.config().packageConfig(project.id(), member.name());
                String adapter = packageConfig.adapter() == null ? "cargo" : packageConfig.adapter();
                if (member.runtime() == PackageRuntime.RUST && adapter.equals("cargo")) {
                    hasRustPackages = true;
                }
            }
        }
        if (coverageRequested && hasRustPackages && !Coverage.llvmCovAvailable()) {
            Terminal.eprintln(
                    "warning: cargo-llvm-cov not found; running tests without coverage. Install: cargo install cargo-llvm-cov && rustup component add llvm-tools-preview");
        }
        RunDir runDir = RunDir.create(workspace, plan.config().defaults().runsBase(), RunIdGenerators.current());
        if (!cli.quiet()) {
            Terminal.println("cherry-test run: " + runDir.id() + " (" + runDir.root() + ")");
            Terminal.println();
        }
        runDir.exportEnv();
        ProcessEnvironment.set("CHERRY_TEST_RUN_ID", runDir.id());
        ProcessEnvironment.set("CHERRY_TEST_COMPOSE_PROJECT", "cherry-test-" + runDir.id());
        Runner.RunOptions options = new Runner.RunOptions(
                workspace,
                runDir,
                plan.config(),
                coverageRequested,
                cli.quiet(),
                cli.verbose() ? Runner.TestListing.AT_END_OF_RUN : Runner.TestListing.AFTER_EACH_PHASE,
                cli.cargoTestArgs(),
                cli.kinds(),
                self);
        RunOutcome outcome = Runner.runProjects(plan.projects(), options, plan.preflight());
        Path mergedLcov = runDir.mergedLcov();
        CoverageTotals coverageTotals = CoverageTotals.EMPTY;
        boolean htmlEnabled = plan.config().defaults().htmlReport() && !cli.noHtml();
        CoverageIgnore coverageIgnore = CoverageIgnore.load(workspace, plan.config());
        if (coverageRequested) {
            Coverage.mergeLcovFiles(outcome.lcovFiles(), mergedLcov);
            coverageTotals = new CoverageTotals(
                    phaseSummary(runDir, outcome.unitLcovFiles(), "unit", coverageIgnore),
                    phaseSummary(runDir, outcome.integrationLcovFiles(), "integration", coverageIgnore),
                    Coverage.summaryForLcov(mergedLcov, coverageIgnore));
        }
        if (!cli.quiet()) {
            Terminal.println("==> report snapshot");
        }
        ReportSnapshot snapshot = ReportModel.buildReportSnapshot(
                workspace,
                runDir,
                plan.projects(),
                outcome,
                plan.preflight(),
                coverageTotals,
                plan.config(),
                coverageRequested,
                !cli.noCrap());
        boolean reportFailed = false;
        if (htmlEnabled) {
            if (!cli.quiet()) {
                Terminal.println("==> html report");
            }
            try {
                HtmlReport.writeHtmlReport(snapshot);
                if (HtmlReport.shouldOpenReport(cli.noOpen(), plan.config().defaults())) {
                    try {
                        HtmlReport.openHtmlReport(snapshot.reportPath());
                    } catch (RuntimeException error) {
                        if (!cli.quiet()) {
                            Terminal.eprintln("warning: could not open html report: " + AnyhowError.alternate(error));
                        }
                    }
                }
            } catch (RuntimeException error) {
                reportFailed = true;
                if (!cli.quiet()) {
                    Terminal.eprintln("warning: html report failed: " + AnyhowError.alternate(error));
                }
            }
        }
        try {
            runDir.updateLatestSymlink(workspace, plan.config().defaults().runsBase());
        } catch (RuntimeException error) {
            if (!cli.quiet()) {
                Terminal.eprintln("warning: latest symlink failed: " + AnyhowError.alternate(error));
            }
        }
        ReportOutput.printAggregateReport(snapshot, !cli.quiet(), cli.verbose() && !cli.quiet());
        if (outcome.ranIntegration()) {
            try {
                Workspace.teardownScopes();
            } catch (RuntimeException teardownIgnoredLikeRust) {
                return outcome.failed() || reportFailed ? 1 : 0;
            }
        }
        return outcome.failed() || reportFailed ? 1 : 0;
    }

    private static @Nullable CoverageSummary phaseSummary(
            RunDir runDir, List<Path> files, String name, CoverageIgnore ignore) {
        if (files.isEmpty()) {
            return null;
        }
        Path merged = runDir.root().resolve(name + ".lcov");
        Coverage.mergeLcovFiles(files, merged);
        return Coverage.summaryForLcov(merged, ignore);
    }

    private static boolean skipCoverageEnv() {
        String value = ProcessEnvironment.get("CHERRY_TEST_SKIP_COVERAGE");
        return value != null
                && (value.equals("1") || RustText.toAsciiLowercase(value).equals("true"));
    }
}
