package com.cherrytest.cli.adapters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cherrytest.common.adapter.AdapterRequest;
import com.cherrytest.common.adapter.ExecutionMode;
import com.cherrytest.common.rustcompat.AnyhowError;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import tools.jackson.databind.node.JsonNodeFactory;

class PitestSourceTest {
    @TempDir
    Path root;

    private AdapterRequest request(String tool) throws IOException {
        var options = new TreeMap<String, tools.jackson.databind.JsonNode>();
        options.put("mutation_build_tool", JsonNodeFactory.instance.stringNode(tool));
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
                new TreeMap<>(),
                options);
    }

    private String source(String path, String content) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return path;
    }

    private static void classFile(Path file, String name, String source) throws IOException {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        writer.visitSource(source, null);
        writer.visitEnd();
        Files.createDirectories(file.getParent());
        Files.write(file, writer.toByteArray());
    }

    @Test
    void package_selection_ignores_commented_and_quoted_declarations() throws IOException {
        assertThat(Pitest.sourcePackage("/*\npackage wrong;\n*/\npackage demo . prices;\nclass Price {}"))
                .isEqualTo("demo.prices");
        assertThat(Pitest.sourcePackage(
                        "@file:Suppress(\"\"\"\npackage wrong\n\"\"\")\npackage demo.prices\nfun price() = 1"))
                .isEqualTo("demo.prices");
        assertThat(Pitest.sourcePackage("/* outer /* nested */ package wrong; */\npackage demo\nclass Price"))
                .isEqualTo("demo");
        assertThat(Pitest.sourcePackage("class Price { String example = \"package wrong;\"; }\n// package wrong;"))
                .isEmpty();
        String source =
                source("src/main/java/demo/Price.java", "/*\npackage wrong;\n*/\npackage demo; class Price {}\n");
        Path classes = root.resolve("output/classes");
        classFile(classes.resolve("demo/Price.class"), "demo/Price", "Price.java");
        assertThat(Pitest.classesForSources(request("maven"), List.of(source), classes))
                .containsOnlyKeys("demo.Price");
    }

    @Test
    void gradle_mapping_only_reads_java_and_kotlin_main_classes() throws IOException {
        String java = source("src/main/java/demo/Price.java", "package demo; class Price {}\n");
        String kotlin = source("src/main/kotlin/demo/Amounts.kt", "package demo\nfun price() = 1\n");
        Path classes = root.resolve("output/build/classes");
        classFile(classes.resolve("java/main/demo/Price.class"), "demo/Price", "Price.java");
        classFile(classes.resolve("kotlin/main/demo/AmountsKt.class"), "demo/AmountsKt", "Amounts.kt");
        classFile(classes.resolve("java/test/demo/TestPrice.class"), "demo/TestPrice", "Price.java");
        classFile(classes.resolve("kotlin/test/demo/TestAmounts.class"), "demo/TestAmounts", "Amounts.kt");
        assertThat(Pitest.classesForSources(request("gradle"), List.of(java, kotlin), classes))
                .containsOnlyKeys("demo.AmountsKt", "demo.Price");
    }

    @Test
    void class_mapping_rejects_files_and_roots_outside_build_output() throws IOException {
        String source = source("src/main/java/demo/Price.java", "package demo; class Price {}\n");
        Path external = root.resolve("external/demo/Price.class");
        classFile(external, "demo/Price", "Price.java");
        AdapterRequest request = request("maven");
        Path classes = root.resolve("output/classes");
        Files.createDirectories(classes);
        Files.createSymbolicLink(classes.resolve("Price.class"), external);
        assertThatThrownBy(() -> Pitest.classesForSources(request, List.of(source), classes))
                .isInstanceOf(AnyhowError.class)
                .hasMessageContaining("escapes build output");
        Path linkedRoot = root.resolve("output/linked-classes");
        Files.createSymbolicLink(linkedRoot, root.resolve("external"));
        assertThatThrownBy(() -> Pitest.classesForSources(request, List.of(source), linkedRoot))
                .isInstanceOf(AnyhowError.class)
                .hasMessageContaining("escape build output");
    }

    @Test
    void wildcard_class_names_cannot_broaden_pit_targets() throws IOException {
        String source = source("src/main/kotlin/demo/Price.kt", "package demo\nclass Price\n");
        AdapterRequest request = request("maven");
        for (String suffix : List.of("*", "?", ",", "[", "]")) {
            Path classes = Files.createTempDirectory(root.resolve("output"), "classes-");
            classFile(classes.resolve("Price.class"), "demo/Price" + suffix, "Price.kt");
            assertThatThrownBy(() -> Pitest.classesForSources(request, List.of(source), classes))
                    .isInstanceOf(AnyhowError.class)
                    .hasMessageContaining("exact PIT target");
        }
    }
}
