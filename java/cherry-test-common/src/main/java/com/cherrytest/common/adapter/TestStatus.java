package com.cherrytest.common.adapter;

import com.cherrytest.common.rustcompat.AnyhowError;

public enum TestStatus {
    PASSED("passed", "Passed"),
    FAILED("failed", "Failed"),
    SKIPPED("skipped", "Skipped");

    private final String wireName;
    private final String debugName;

    TestStatus(String wireName, String debugName) {
        this.wireName = wireName;
        this.debugName = debugName;
    }

    public String wireName() {
        return wireName;
    }

    public String debugName() {
        return debugName;
    }

    public static TestStatus fromWire(String value) {
        for (TestStatus status : values()) {
            if (status.wireName.equals(value)) {
                return status;
            }
        }
        throw new AnyhowError("unknown variant `" + value + "`, expected one of `passed`, `failed`, `skipped`");
    }
}
