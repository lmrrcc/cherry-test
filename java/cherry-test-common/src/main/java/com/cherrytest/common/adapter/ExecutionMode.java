package com.cherrytest.common.adapter;

import com.cherrytest.common.rustcompat.AnyhowError;

public enum ExecutionMode {
    LOCAL("local"),
    CONTAINER("container");

    private final String wireName;

    ExecutionMode(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static ExecutionMode fromWire(String value) {
        for (ExecutionMode mode : values()) {
            if (mode.wireName.equals(value)) {
                return mode;
            }
        }
        throw new AnyhowError("unknown variant `" + value + "`, expected `local` or `container`");
    }
}
