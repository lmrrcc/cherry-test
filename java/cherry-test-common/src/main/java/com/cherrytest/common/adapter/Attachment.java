package com.cherrytest.common.adapter;

import java.nio.file.Path;

public record Attachment(String name, Path path, String mediaType) {
    public Attachment withPath(Path replacement) {
        return new Attachment(name, replacement, mediaType);
    }
}
