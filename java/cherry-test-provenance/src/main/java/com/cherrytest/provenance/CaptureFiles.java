package com.cherrytest.provenance;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

final class CaptureFiles {
    private CaptureFiles() {}

    static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    static void write(Properties values, String kind, String id) throws IOException {
        String directory = System.getenv("CHERRY_TEST_PROVENANCE_DIR");
        String token = System.getenv("CHERRY_TEST_PROVENANCE_TOKEN");
        if (directory == null || token == null || !token.matches("[a-f0-9-]{36}"))
            throw new IOException("Missing Cherry compilation/execution capture identity");
        Path root = Path.of(directory);
        Files.createDirectories(root);
        values.setProperty("schemaVersion", "1");
        values.setProperty("token", token);
        Path temporary = Files.createTempFile(root, "capture-", ".tmp");
        try {
            try (OutputStream stream = Files.newOutputStream(temporary)) {
                values.store(stream, null);
            }
            Files.move(temporary, root.resolve(id + "." + kind), StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
