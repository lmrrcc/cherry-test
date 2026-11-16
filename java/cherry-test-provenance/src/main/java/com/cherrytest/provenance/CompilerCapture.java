package com.cherrytest.provenance;

import com.sun.source.util.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class CompilerCapture implements Plugin {
    @Override
    public String getName() {
        return "CherryProvenance";
    }

    @Override
    public void init(JavacTask task, String... args) {
        if (System.getenv("CHERRY_TEST_PROVENANCE_TOKEN") == null) return;
        if (args.length < 1) throw new IllegalArgumentException("CherryProvenance requires the class output directory");
        Path classes = Path.of(String.join(" ", args)).toAbsolutePath().normalize();
        if (Files.isDirectory(classes)) {
            try (var paths = Files.walk(classes)) {
                if (paths.anyMatch(path -> path.toString().endsWith(".class"))) return;
            } catch (IOException error) {
                throw new UncheckedIOException("Cannot inspect compiler output directory", error);
            }
        }
        String generation = UUID.randomUUID().toString();
        Map<URI, String> sources = new LinkedHashMap<>();
        Map<URI, String> beforeParse = new LinkedHashMap<>();
        Map<String, URI> generated = new LinkedHashMap<>();
        task.addTaskListener(new TaskListener() {
            @Override
            public void started(TaskEvent event) {
                if (event.getKind() == TaskEvent.Kind.PARSE) {
                    try {
                        var input = event.getSourceFile();
                        beforeParse.put(
                                input.toUri(),
                                CaptureFiles.hash(
                                        input.getCharContent(true).toString().getBytes(StandardCharsets.UTF_8)));
                    } catch (IOException error) {
                        throw new UncheckedIOException("Cherry compiler input capture failed", error);
                    }
                }
            }

            @Override
            public void finished(TaskEvent event) {
                try {
                    if (event.getKind() == TaskEvent.Kind.PARSE) {
                        var input = event.getSourceFile();
                        String afterParse = CaptureFiles.hash(
                                input.getCharContent(true).toString().getBytes(StandardCharsets.UTF_8));
                        if (afterParse.equals(beforeParse.get(input.toUri()))) sources.put(input.toUri(), afterParse);
                    } else if (event.getKind() == TaskEvent.Kind.GENERATE) {
                        String name = task.getElements()
                                .getBinaryName(event.getTypeElement())
                                .toString();
                        generated.put(name, event.getSourceFile().toUri());
                    } else if (event.getKind() == TaskEvent.Kind.COMPILATION && !generated.isEmpty()) {
                        Properties manifest = new Properties();
                        manifest.setProperty("generation", generation);
                        manifest.setProperty(
                                "compiler",
                                System.getProperty("java.vendor") + " / javac " + System.getProperty("java.version"));
                        int index = 0;
                        for (var item : generated.entrySet()) {
                            URI uri = item.getValue();
                            String hash = sources.get(uri);
                            if (hash == null || !uri.getScheme().equals("file")) continue;
                            Path source = Path.of(uri).toAbsolutePath().normalize();
                            if (!hash.equals(CaptureFiles.hash(Files.readAllBytes(source)))) continue;
                            Path output = classes.resolve(item.getKey().replace('.', '/') + ".class");
                            if (!Files.isRegularFile(output)) continue;
                            String prefix = "class." + index++ + ".";
                            manifest.setProperty(prefix + "name", item.getKey().replace('.', '/'));
                            manifest.setProperty(prefix + "source", source.toString());
                            manifest.setProperty(prefix + "sourceHash", hash);
                            manifest.setProperty(prefix + "path", output.toString());
                            manifest.setProperty(prefix + "sha256", CaptureFiles.hash(Files.readAllBytes(output)));
                        }
                        manifest.setProperty("count", Integer.toString(index));
                        CaptureFiles.write(manifest, "compile", generation);
                    }
                } catch (IOException error) {
                    throw new UncheckedIOException("Cherry compiler capture failed", error);
                }
            }
        });
    }
}
