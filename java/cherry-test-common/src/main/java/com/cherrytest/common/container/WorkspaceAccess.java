package com.cherrytest.common.container;

import com.cherrytest.common.rustcompat.AnyhowError;
import java.util.List;

/** Access mode of the /workspace bind mount. */
public enum WorkspaceAccess {
    RO("ro"),
    RW("rw");

    public static final List<String> WIRE_NAMES = List.of("ro", "rw");

    private final String wireName;

    WorkspaceAccess(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static WorkspaceAccess fromWire(String value) {
        for (WorkspaceAccess candidate : values()) {
            if (candidate.wireName.equals(value)) {
                return candidate;
            }
        }
        throw new AnyhowError(Expected.unknownVariant(value, WIRE_NAMES));
    }
}
