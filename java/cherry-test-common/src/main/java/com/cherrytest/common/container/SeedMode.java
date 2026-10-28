package com.cherrytest.common.container;

import com.cherrytest.common.rustcompat.AnyhowError;
import java.util.List;

/** How a writable path starts. */
public enum SeedMode {
    COPY("copy");

    public static final List<String> WIRE_NAMES = List.of("copy");

    private final String wireName;

    SeedMode(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static SeedMode fromWire(String value) {
        for (SeedMode candidate : values()) {
            if (candidate.wireName.equals(value)) {
                return candidate;
            }
        }
        throw new AnyhowError(Expected.unknownVariant(value, WIRE_NAMES));
    }
}
