package com.cherrytest.common.gitdiff;

import java.nio.file.Path;

final class RustJoin {
    private RustJoin() {}

    static Path join(Path base, String relative) {
        return com.cherrytest.common.rustcompat.RustPath.join(base, relative);
    }
}
