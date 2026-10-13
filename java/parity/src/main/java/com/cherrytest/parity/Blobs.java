package com.cherrytest.parity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class Blobs {
    private Blobs() {}

    static String store(Path directory, byte[] content) throws IOException {
        String name = sha256(content);
        Files.createDirectories(directory);
        Path target = directory.resolve(name);
        if (!Files.exists(target)) {
            Files.write(target, content);
        }
        return name;
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
