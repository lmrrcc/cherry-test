package com.cherrytest.cli.adapters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cherrytest.common.adapter.AdapterRequest;
import com.cherrytest.common.adapter.AdapterResponse;
import com.cherrytest.common.adapter.ExecutionMode;
import com.cherrytest.common.process.SystemCommand;
import com.cherrytest.common.rustcompat.AnyhowError;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import tools.jackson.databind.node.JsonNodeFactory;

class PitestTest {
    @TempDir
    Path root;

    private AdapterRequest request(String base) throws IOException {
        var options = new TreeMap<String, tools.jackson.databind.JsonNode>();
        options.put("mutation_tool", JsonNodeFactory.instance.stringNode("pitest"));
        options.put("mutation_build_tool", JsonNodeFactory.instance.stringNode("maven"));
        options.put("mutation_diff_base", JsonNodeFactory.instance.stringNode(base));
        Files.createDirectories(root.resolve("output"));
        return new AdapterRequest(
                1,
                "demo",
                "prices",
                "mutation",
                root,
                root.resolve("pom.xml"),
                root,
                root.resolve("output"),
                ExecutionMode.LOCAL,
                false,
                true,
                List.of(),
                new TreeMap<>(System.getenv()),
                options);
    }

    private void git(String... args) throws IOException {
        var output = SystemCommand.of("git").args(args).currentDir(root).output();
        assertThat(output.success()).as(output.stderrLossy()).isTrue();
    }

    private void initialize() throws IOException {
        git("init", "-b", "main");
        git("config", "user.email", "test@example.invalid");
        git("config", "user.name", "PIT test");
        Files.writeString(root.resolve(".gitignore"), "output/\n");
        source("src/main/java/demo/Price.java", "package demo; class Price { int price() { return 1; } }\n");
        source("src/main/java/demo/Stable.java", "package demo; class Stable {}\n");
        git("add", ".");
        git("commit", "-m", "baseline");
    }

    private void source(String relative, String content) throws IOException {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    @Test
    void changed_sources_include_commits_worktree_staged_and_untracked_production_only() throws IOException {
        initialize();
        git("checkout", "-b", "change");
        source("src/main/java/demo/Price.java", "package demo; class Price { int price() { return 2; } }\n");
        git("add", ".");
        git("commit", "-m", "change price");
        source("src/main/java/demo/Staged.java", "package demo; class Staged {}\n");
        git("add", "src/main/java/demo/Staged.java");
        source("src/main/kotlin/demo/New.kt", "package demo\nfun answer() = 42\n");
        source("src/test/java/demo/PriceTest.java", "class PriceTest {}\n");
        assertThat(Pitest.changedSources(request("main")))
                .containsExactly(
                        "src/main/java/demo/Price.java",
                        "src/main/java/demo/Staged.java",
                        "src/main/kotlin/demo/New.kt");
        assertThatThrownBy(() -> Pitest.changedSources(request("missing-base")))
                .isInstanceOf(AnyhowError.class)
                .hasMessageContaining("git command failed");
    }

    @Test
    void no_changes_is_valid_in_strict_mode_without_invoking_a_tool() throws IOException {
        initialize();
        AdapterRequest request = request("main");
        AdapterResponse response = Builtins.run("pitest", request);
        Adapters.validate(request, response);
        assertThat(response.success()).isTrue();
        assertThat(response.tests()).isEmpty();
        assertThat(Pitest.readReport(request.output().resolve("mutation.json"))
                        .path("status")
                        .asString())
                .isEqualTo("no_changes");
        Files.delete(request.output().resolve("mutation.json"));
        assertThatThrownBy(() -> Adapters.validate(request, AdapterResponse.empty()))
                .hasMessageContaining("missing");
    }

    @Test
    void rejects_existing_build_output_before_compiling_changed_sources() throws IOException {
        initialize();
        source("src/main/java/demo/Price.java", "package demo; class Price { int price() { return 2; } }\n");
        AdapterRequest request = request("main");
        Files.createDirectories(request.output().resolve("build/classes"));
        assertThatThrownBy(() -> Pitest.run(request)).hasMessageContaining("PIT build directory must be fresh");
        assertThat(Files.exists(request.output().resolve("mutation.json"))).isFalse();
    }

    @Test
    void empty_tree_aliases_match_shared_git_selection() throws IOException {
        initialize();
        for (String base : List.of("empty", "empty-tree", "root", " EMPTY ", "Root")) {
            assertThat(Pitest.changedSources(request(base)))
                    .containsExactly("src/main/java/demo/Price.java", "src/main/java/demo/Stable.java");
        }
        assertThatThrownBy(() -> Pitest.changedSources(request(""))).hasMessageContaining("git command failed");
    }

    @Test
    void effective_maven_model_is_removed_after_command_or_parse_failure() throws IOException {
        AdapterRequest request = request("main");
        Path command = root.resolve("maven-model");
        for (int status : List.of(0, 1)) {
            Files.writeString(
                    command,
                    "#!/bin/sh\nfor arg do\ncase \"$arg\" in -Doutput=*) output=${arg#-Doutput=};; esac\ndone\nprintf '<project><build>' > \"$output\"\nexit "
                            + status + "\n");
            assertThat(command.toFile().setExecutable(true)).isTrue();
            assertThatThrownBy(() -> Pitest.readMavenExecutions(request, command.toString()))
                    .isInstanceOf(AnyhowError.class);
            try (var files = Files.list(request.output())) {
                assertThat(files.noneMatch(file -> file.getFileName().toString().startsWith(".pitest-effective-pom-")))
                        .isTrue();
            }
        }
    }

    @Test
    void inherited_maven_pit_executions_are_neutralized_and_reserved_ids_rejected() throws IOException {
        String effective =
                "<project><build><plugins><plugin><groupId>org.pitest</groupId><artifactId>pitest-maven</artifactId><executions><execution><id>inherited-pit</id><phase>test-compile</phase><configuration><skip>false</skip></configuration></execution></executions></plugin></plugins></build></project>";
        var ids = Pitest.effectiveMavenExecutions(effective);
        assertThat(ids).containsExactly("inherited-pit");
        assertThatThrownBy(() -> Pitest.effectiveMavenExecutions(effective.replace("inherited-pit", "cherry-test-pit")))
                .hasMessageContaining("reserved");
        AdapterRequest request = request("main");
        Files.writeString(request.manifest(), "<project/>");
        Path temporary = root.resolve("temporary.xml");
        Pitest.writeMavenPom(request, temporary, List.of("demo.Price"), ids);
        var generated = XmlNodes.parse(Files.readString(temporary));
        var executions = generated.getElementsByTagName("execution");
        for (int index = 0; index < executions.getLength(); index++) {
            var execution = (org.w3c.dom.Element) executions.item(index);
            if ("inherited-pit"
                    .equals(XmlNodes.text(XmlNodes.children(execution, "id").getFirst()))) {
                assertThat(XmlNodes.text(XmlNodes.children(execution, "phase").getFirst()))
                        .isEqualTo("none");
            }
        }
    }

    @Test
    void mutation_cwd_must_match_manifest_before_no_changes() throws IOException {
        AdapterRequest original = request("main");
        Path module = root.resolve("module");
        Files.createDirectory(module);
        AdapterRequest mismatched = new AdapterRequest(
                original.protocol(),
                original.project(),
                original.packageName(),
                original.phase(),
                original.root(),
                module.resolve("pom.xml"),
                original.cwd(),
                original.output(),
                original.execution(),
                original.coverage(),
                original.strict(),
                original.args(),
                original.env(),
                original.options());
        assertThatThrownBy(() -> Pitest.changedSources(mismatched))
                .hasMessageContaining("cwd must be the package manifest directory");
    }

    @Test
    void temporary_pom_preserves_profiles_and_isolates_pit_execution() throws IOException {
        String original = """
                <project><build><outputDirectory>old</outputDirectory><plugins><plugin>
                <groupId>org.pitest</groupId><artifactId>pitest-maven</artifactId>
                <configuration><targetClasses><param>demo.*</param></targetClasses><targetTests><param>demo.PriceTest</param></targetTests></configuration>
                <dependencies><dependency><groupId>org.pitest</groupId><artifactId>pitest-junit5-plugin</artifactId><version>old</version></dependency></dependencies>
                <executions><execution><id>default-cli</id><phase>test-compile</phase><configuration><targetClasses><param>*</param></targetClasses></configuration></execution></executions>
                </plugin></plugins></build><profiles><profile><id>normal</id><activation><activeByDefault>true</activeByDefault></activation>
                <build><directory>other</directory><plugins><plugin><groupId>org.pitest</groupId><artifactId>pitest-maven</artifactId><configuration><targetClasses><param>demo.*</param></targetClasses></configuration></plugin></plugins></build>
                </profile></profiles></project>
                """;
        AdapterRequest request = request("main");
        for (String content : List.of(
                original,
                "<project><modelVersion>4.0.0</modelVersion></project>",
                "<project><build/><profiles/></project>")) {
            Files.writeString(request.manifest(), content);
            Path temporary = root.resolve("temporary.xml");
            Pitest.writeMavenPom(request, temporary, List.of("demo.Price", "demo.Price$Nested"), List.of());
            var generated = XmlNodes.parse(Files.readString(temporary));
            var profiles = generated.getElementsByTagName("profile");
            for (int index = 0; index < profiles.getLength(); index++) {
                var profile = (org.w3c.dom.Element) profiles.item(index);
                assertThat(XmlNodes.text(XmlNodes.children(profile, "id").getFirst()))
                        .isEqualTo("normal");
            }
            var build = XmlNodes.children(generated, "build").getFirst();
            assertThat(XmlNodes.text(XmlNodes.children(build, "outputDirectory").getFirst()))
                    .isEqualTo(request.output().resolve("build/classes").toString());
            var plugins = XmlNodes.children(build, "plugins").getFirst();
            var plugin = XmlNodes.children(plugins, "plugin").getFirst();
            var executions =
                    XmlNodes.children(XmlNodes.children(plugin, "executions").getFirst(), "execution");
            var managed = executions.stream()
                    .filter(execution -> "cherry-test-pit"
                            .equals(XmlNodes.text(
                                    XmlNodes.children(execution, "id").getFirst())))
                    .findFirst()
                    .orElseThrow();
            var config = XmlNodes.children(managed, "configuration").getFirst();
            var targets = XmlNodes.children(config, "targetClasses").getFirst();
            assertThat(targets.getAttribute("combine.self")).isEqualTo("override");
            assertThat(XmlNodes.children(targets, "param").stream().map(XmlNodes::text))
                    .containsExactly("demo.Price", "demo.Price$Nested");
            executions.stream()
                    .filter(execution -> "default-cli"
                            .equals(XmlNodes.text(
                                    XmlNodes.children(execution, "id").getFirst())))
                    .forEach(execution -> assertThat(XmlNodes.text(
                                    XmlNodes.children(execution, "phase").getFirst()))
                            .isEqualTo("none"));
            Files.copy(temporary, request.manifest(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            assertThatThrownBy(() -> Pitest.writeMavenPom(request, temporary, List.of(), List.of()))
                    .hasMessageContaining("reserved");
        }
    }

    @Test
    void compiled_source_metadata_selects_inner_classes_secondary_classes_and_kotlin_facades() throws IOException {
        source(
                "src/main/java/demo/Price.java",
                "package demo; class Price { static class Inner {} } class Secondary {}\n");
        source("src/main/kotlin/demo/Amounts.kt", "package demo\nfun answer() = 42\n");
        Path classes = root.resolve("output/classes");
        Files.createDirectories(classes);
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).isNotNull();
        assertThat(compiler.run(
                        null,
                        null,
                        null,
                        "-g",
                        "-d",
                        classes.toString(),
                        root.resolve("src/main/java/demo/Price.java").toString()))
                .isZero();
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "demo/AmountsKt", null, "java/lang/Object", null);
        writer.visitSource("Amounts.kt", null);
        writer.visitEnd();
        Files.write(classes.resolve("demo/AmountsKt.class"), writer.toByteArray());
        var mapping = Pitest.classesForSources(
                request("main"), List.of("src/main/java/demo/Price.java", "src/main/kotlin/demo/Amounts.kt"), classes);
        assertThat(mapping.keySet())
                .containsExactly("demo.AmountsKt", "demo.Price", "demo.Price$Inner", "demo.Secondary");
        assertThat(mapping.get("demo.AmountsKt")).isEqualTo("src/main/kotlin/demo/Amounts.kt");
    }

    @Test
    void rejects_package_switches_and_disabled_mutation_execution() {
        for (String argument : List.of(
                "-fother.xml",
                "--file=other.xml",
                "-plother",
                "-am",
                "-Dpit.dryRun=true",
                "-DskipTests=true",
                "deploy")) {
            assertThatThrownBy(() -> Pitest.arguments(List.of(argument), true)).isInstanceOf(AnyhowError.class);
        }
        for (String argument :
                List.of("-pother", "--project-dir=other", "-bother.gradle", "--settings-file=other", ":other:pitest")) {
            assertThatThrownBy(() -> Pitest.arguments(List.of(argument), false)).isInstanceOf(AnyhowError.class);
        }
        assertThat(Pitest.arguments(
                        List.of("-Pmutation", "test-compile", "org.pitest:pitest-maven:mutationCoverage"), true))
                .containsExactly("-Pmutation");
    }

    @Test
    void net_reverted_sources_are_excluded_and_empty_tree_works_without_a_commit() throws IOException {
        initialize();
        git("checkout", "-b", "changes");
        source("src/main/java/demo/Price.java", "package demo; class Price { int price() { return 2; } }\n");
        git("add", ".");
        git("commit", "-m", "price");
        source("src/main/java/demo/Price.java", "package demo; class Price { int price() { return 1; } }\n");
        assertThat(Pitest.changedSources(request("main"))).isEmpty();
        Path unborn = root.resolve("unborn");
        Files.createDirectories(unborn);
        assertThat(SystemCommand.of("git")
                        .args("init", "-b", "main")
                        .currentDir(unborn)
                        .output()
                        .success())
                .isTrue();
        source("unborn/src/main/java/New.java", "class New {}\n");
        AdapterRequest base = request("root");
        AdapterRequest nested = new AdapterRequest(
                base.protocol(),
                base.project(),
                base.packageName(),
                base.phase(),
                root,
                unborn.resolve("pom.xml"),
                unborn,
                base.output(),
                base.execution(),
                base.coverage(),
                base.strict(),
                base.args(),
                base.env(),
                base.options());
        assertThat(Pitest.changedSources(nested)).containsExactly("unborn/src/main/java/New.java");
    }

    private static String xml(String status, String detected, String className) {
        return "<mutations><mutation status=\"" + status + "\" detected=\"" + detected
                + "\"><sourceFile>Price.java</sourceFile><mutatedClass>" + className
                + "</mutatedClass><mutatedMethod>price</mutatedMethod><lineNumber>2</lineNumber><mutator>returns</mutator><description>replaced &lt;value&gt;</description><killingTest/></mutation></mutations>";
    }

    @Test
    void imports_mutant_statuses_without_counting_them_as_tests() {
        var classes = Map.of("demo.Price", "src/main/java/demo/Price.java");
        for (String status : List.of("KILLED", "EQUIVALENT", "SURVIVED", "NO_COVERAGE", "TIMED_OUT", "RUN_ERROR")) {
            var mutant = Pitest.parseXml(
                            xml(
                                    status,
                                    Boolean.toString(
                                            Set.of("KILLED", "EQUIVALENT").contains(status)),
                                    "demo.Price"),
                            classes)
                    .getFirst();
            assertThat(mutant.path("status").asString()).isEqualTo(status);
            assertThat(mutant.path("description").asString()).isEqualTo("replaced <value>");
        }
        assertThatThrownBy(() -> Pitest.parseXml(xml("KILLED", "true", "demo.Stable"), classes))
                .hasMessageContaining("outside changed");
        assertThatThrownBy(() -> Pitest.parseXml(xml("EQUIVALENT", "false", "demo.Price"), classes))
                .hasMessageContaining("inconsistent PIT detected status");
        assertThatThrownBy(() -> Pitest.parseXml(xml("UNKNOWN", "true", "demo.Price"), classes))
                .hasMessageContaining("invalid PIT mutation status");
        assertThatThrownBy(() -> Pitest.parseXml(
                        "<!DOCTYPE mutations [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><mutations/>", classes))
                .hasMessageContaining("DTD");
    }

    @Test
    void xml_rejects_mismatched_sources_unknown_elements_and_invalid_locations() {
        var classes = Map.of("demo.Price", "src/main/java/demo/Price.java");
        String valid = xml("KILLED", "true", "demo.Price");
        assertThatThrownBy(() ->
                        Pitest.parseXml(valid.replace("<sourceFile>Price.java", "<sourceFile>Other.java"), classes))
                .hasMessageContaining("source filename");
        assertThatThrownBy(() -> Pitest.parseXml(valid.replace("<sourceFile>Price.java</sourceFile>", ""), classes))
                .hasMessageContaining("sourceFile");
        assertThatThrownBy(() -> Pitest.parseXml(valid.replace("</mutations>", "<unexpected/></mutations>"), classes))
                .hasMessageContaining("unexpected PIT report element");
        assertThatThrownBy(() -> Pitest.parseXml(valid.replace("<lineNumber>2", "<lineNumber>4294967296"), classes))
                .hasMessageContaining("line number");
    }

    @Test
    void xml_report_must_be_confined_and_bounded() throws IOException {
        Path output = root.resolve("output");
        Path xml = output.resolve("pit-reports/mutations.xml");
        Files.createDirectories(xml.getParent());
        var classes = Map.of("demo.Price", "src/main/java/demo/Price.java");
        try (FileChannel channel = FileChannel.open(xml, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.wrap(new byte[] {0}), 32L * 1024 * 1024);
        }
        assertThatThrownBy(() -> Pitest.readXmlReport(output, classes)).hasMessageContaining("32 MiB");
        Files.delete(xml);
        Path outside = root.resolve("outside.xml");
        Files.writeString(outside, xml("KILLED", "true", "demo.Price"));
        Files.createSymbolicLink(xml, outside);
        assertThatThrownBy(() -> Pitest.readXmlReport(output, classes)).hasMessageContaining("escapes adapter output");
        Files.delete(xml);
        Files.copy(outside, xml);
        assertThat(Pitest.readXmlReport(output, classes)).hasSize(1);
    }
}
