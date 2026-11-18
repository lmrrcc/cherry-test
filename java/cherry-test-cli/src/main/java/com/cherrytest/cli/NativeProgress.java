package com.cherrytest.cli;

import com.cherrytest.cli.adapters.Json;
import com.cherrytest.common.adapter.AdapterRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import tools.jackson.databind.JsonNode;

final class NativeProgress implements AutoCloseable {
    private final NativeEvents events;
    private final AdapterRequest request;
    private final String adapter;
    private final Path directory;
    private final Set<Path> seen = new HashSet<>();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final Thread worker;

    NativeProgress(NativeEvents events, AdapterRequest request, String adapter, Path directory) {
        this.events = events;
        this.request = request;
        this.adapter = adapter;
        this.directory = directory;
        worker = Thread.ofVirtual().name("cherry-native-progress").unstarted(() -> {
            while (!stopped.get()) {
                drain();
                try {
                    Thread.sleep(50);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
    }

    void start() {
        worker.start();
    }

    private void drain() {
        if (!Files.isDirectory(directory)) return;
        try (var files = Files.list(directory)) {
            var pending = new ArrayList<JsonNode>();
            for (Path path : files.limit(100_001).toList()) {
                if (seen.size() >= 100_000) return;
                if (!path.toString().endsWith(".json")
                        || !seen.add(path)
                        || Files.isSymbolicLink(path)
                        || Files.size(path) > 64 * 1024) continue;
                pending.add(Json.parse(Files.readString(path)));
            }
            pending.sort(java.util.Comparator.comparing(
                    node -> node.path("timestamp").asString()));
            pending.forEach(node -> events.testProgress(request, adapter, node));
        } catch (IOException | RuntimeException ignored) {
            events.progressUnavailable(request, adapter);
        }
    }

    @Override
    public void close() {
        stopped.set(true);
        worker.interrupt();
        try {
            worker.join();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        drain();
    }
}
