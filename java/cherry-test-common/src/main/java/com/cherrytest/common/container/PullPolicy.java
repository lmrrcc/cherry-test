package com.cherrytest.common.container;

import com.cherrytest.common.rustcompat.AnyhowError;
import java.util.List;

/** Whether docker run pulls the image first. */
public enum PullPolicy {
    NEVER("never"),
    MISSING("missing"),
    ALWAYS("always");

    public static final List<String> WIRE_NAMES = List.of("never", "missing", "always");

    private final String wireName;

    PullPolicy(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static PullPolicy fromWire(String value) {
        for (PullPolicy candidate : values()) {
            if (candidate.wireName.equals(value)) {
                return candidate;
            }
        }
        throw new AnyhowError(Expected.unknownVariant(value, WIRE_NAMES));
    }
}
