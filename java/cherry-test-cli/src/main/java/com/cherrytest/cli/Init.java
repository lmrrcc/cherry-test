package com.cherrytest.cli;

import com.cherrytest.cli.adapters.Json;
import com.cherrytest.cli.nativeos.Terminal;
import com.cherrytest.common.process.CommandOutput;
import com.cherrytest.common.process.SystemCommand;
import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.common.rustcompat.RustIoError;
import com.cherrytest.common.rustcompat.RustOrdering;
import com.cherrytest.core.PackageConfig;
import com.cherrytest.core.PackageRuntime;
import com.cherrytest.core.ProjectConfig;
import com.cherrytest.core.Scope;
import com.cherrytest.core.TestItConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

public final class Init {
    private Init() {}

    public static TestPlan initializeWithConfig(
            Path workspaceRoot, TestItConfig config, @Nullable String filter, boolean quiet) {
        if (config.projects().isEmpty()) {
            throw new AnyhowError("no projects configured; add projects to " + config.configPath());
        }
        SortedSet<String> workspacePackages = loadWorkspacePackageNames(workspaceRoot);
        List<ProjectGroup> projects =
                Filter.applyFilter(resolveProjectsFromConfig(workspaceRoot, config, workspacePackages), filter);
        if (projects.isEmpty()) {
            throw new AnyhowError("no testable workspace members found");
        }
        PreflightPlan preflight = Preflight.buildPreflightPlan(workspaceRoot, projects, config, quiet);
        warnNodePrerequisites(projects, config, quiet);
        printInitSummary(projects, config, preflight, quiet);
        return new TestPlan(config, projects, preflight.discovered(), preflight);
    }

    static List<ProjectGroup> resolveProjectsFromConfig(
            Path workspaceRoot, TestItConfig config, SortedSet<String> workspacePackages) {
        List<ProjectGroup> out = new ArrayList<>();
        for (Map.Entry<String, ProjectConfig> entry : config.projects().entrySet()) {
            String projectId = entry.getKey();
            ProjectConfig project = entry.getValue();
            String base = project.path() == null ? projectId : project.path();
            Path projectDir = workspaceRoot.resolve(base);
            if (!Files.isDirectory(projectDir)) {
                throw new AnyhowError("project `" + projectId + "` path `" + projectDir + "` does not exist");
            }
            if (project.packages().isEmpty()) {
                throw new AnyhowError("project `" + projectId + "` has no packages configured");
            }
            List<WorkspaceMember> members = new ArrayList<>();
            for (Map.Entry<String, PackageConfig> packageEntry :
                    project.packages().entrySet()) {
                String packageName = packageEntry.getKey();
                PackageConfig packageConfig = packageEntry.getValue();
                PackageRuntime runtime = packageConfig.runtime();
                if (packageConfig.adapter() == null
                        && runtime == PackageRuntime.RUST
                        && !workspacePackages.isEmpty()
                        && !workspacePackages.contains(packageName)) {
                    throw new AnyhowError(
                            "package `" + packageName + "` in project `" + projectId + "` is not a workspace member");
                }
                Path manifest = packageConfig.manifest() != null
                        ? packageConfig.manifest()
                        : Path.of(base)
                                .resolve(packageName)
                                .resolve(runtime == PackageRuntime.NODE ? "package.json" : "Cargo.toml");
                Path manifestPath = manifest.isAbsolute() ? manifest : workspaceRoot.resolve(manifest);
                if (!Files.exists(manifestPath)) {
                    throw new AnyhowError("manifest for `" + packageName + "` not found at " + manifestPath);
                }
                Path relative =
                        manifestPath.startsWith(workspaceRoot) ? workspaceRoot.relativize(manifestPath) : manifestPath;
                members.add(new WorkspaceMember(packageName, relative, projectId, runtime));
            }
            members.sort((left, right) -> RustOrdering.compareStrings(left.name(), right.name()));
            out.add(new ProjectGroup(projectId, members));
        }
        return out;
    }

    private static SortedSet<String> loadWorkspacePackageNames(Path workspaceRoot) {
        Path manifest = workspaceRoot.resolve("Cargo.toml");
        TreeSet<String> names = new TreeSet<>(RustOrdering.STRING_ORDER);
        if (!Files.exists(manifest)) {
            return names;
        }
        String contents;
        try {
            contents = Files.readString(manifest, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new AnyhowError(RustIoError.display(exception), null);
        }
        if (!contents.contains("[workspace]")) {
            return names;
        }
        CommandOutput output;
        try {
            output = SystemCommand.of(cargoProgram())
                    .args("metadata", "--format-version", "1", "--manifest-path", manifest.toString())
                    .output();
        } catch (IOException exception) {
            return names;
        }
        if (!output.success()) {
            return names;
        }
        JsonNode metadata;
        try {
            metadata = Json.parseBytes(output.stdout());
        } catch (AnyhowError error) {
            return names;
        }
        Set<String> members = new HashSet<>();
        JsonNode memberIds = Json.index(metadata, "workspace_members");
        if (memberIds.isArray()) {
            memberIds.forEach(member -> members.add(member.stringValue()));
        }
        JsonNode packages = Json.index(metadata, "packages");
        if (packages.isArray()) {
            for (JsonNode packageNode : packages) {
                String id = Json.string(Json.index(packageNode, "id"));
                String name = Json.string(Json.index(packageNode, "name"));
                if (id != null && name != null && members.contains(id)) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    private static String cargoProgram() {
        String cargo = com.cherrytest.common.process.ProcessEnvironment.get("CARGO");
        return cargo == null ? "cargo" : cargo;
    }

    private static void printInitSummary(
            List<ProjectGroup> projects, TestItConfig config, PreflightPlan preflight, boolean quiet) {
        if (quiet) {
            return;
        }
        long packageCount =
                projects.stream().mapToLong(project -> project.members().size()).sum();
        long integrationCount = preflight.discovered().stream()
                .filter(test -> !test.suite().equals("unit"))
                .count();
        long unitCount = Math.max(0, preflight.discovered().size() - integrationCount);
        long scoped = preflight.discovered().stream()
                .filter(test -> Scope.needsDocker(test.effectiveScope()))
                .count();
        Terminal.println("cherry-test init: " + projects.size() + " projects, " + packageCount + " packages, "
                + preflight.discovered().size() + " test(s) (" + unitCount + " unit, " + integrationCount
                + " integration, "
                + scoped + " scoped)");
        for (ProjectGroup project : projects) {
            for (WorkspaceMember member : project.members()) {
                List<String> labels = new ArrayList<>();
                for (String kind : config.packageKinds(member.name(), project.id())) {
                    TestKind parsed = TestKind.parse(kind);
                    if (parsed != null) {
                        labels.add(parsed.asStr());
                    }
                }
                long memberUnit = preflight.discovered().stream()
                        .filter(test -> test.packageName().equals(member.name())
                                && test.suite().equals("unit"))
                        .count();
                long memberIntegration = preflight.discovered().stream()
                        .filter(test -> test.packageName().equals(member.name())
                                && !test.suite().equals("unit"))
                        .count();
                StringBuilder line = new StringBuilder("  ")
                        .append(project.id())
                        .append('/')
                        .append(member.name())
                        .append(" — ")
                        .append(String.join(", ", labels));
                if (memberUnit > 0) {
                    line.append(" (").append(memberUnit).append(" unit test(s))");
                }
                if (memberIntegration > 0) {
                    line.append(" (").append(memberIntegration).append(" integration test(s))");
                }
                Terminal.println(line.toString());
            }
        }
        Terminal.println();
    }

    private static void warnNodePrerequisites(List<ProjectGroup> projects, TestItConfig config, boolean quiet) {
        if (quiet) {
            return;
        }
        boolean needsNode = projects.stream()
                .anyMatch(project -> project.members().stream()
                        .anyMatch(member -> config.packageRuntime(member.name(), project.id()) == PackageRuntime.NODE));
        if (!needsNode) {
            return;
        }
        if (!commandAvailable("node") && NodeRuntime.resolveNodeBinary() == null) {
            Terminal.eprintln("warning: node not found; node packages may fail");
        }
    }

    private static boolean commandAvailable(String name) {
        try {
            return SystemCommand.of(name).arg("--version").output().success();
        } catch (IOException exception) {
            return false;
        }
    }
}
