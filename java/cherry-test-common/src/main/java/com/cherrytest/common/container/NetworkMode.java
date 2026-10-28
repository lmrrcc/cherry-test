package com.cherrytest.common.container;

import com.cherrytest.common.rustcompat.AnyhowError;
import java.util.List;

/** Network attached to the container. */
public enum NetworkMode {
    NONE("none"),
    BRIDGE("bridge");

    public static final List<String> WIRE_NAMES = List.of("none", "bridge");

    private final String wireName;

    NetworkMode(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static NetworkMode fromWire(String value) {
        for (NetworkMode candidate : values()) {
            if (candidate.wireName.equals(value)) {
                return candidate;
            }
        }
        throw new AnyhowError(Expected.unknownVariant(value, WIRE_NAMES));
    }
}
