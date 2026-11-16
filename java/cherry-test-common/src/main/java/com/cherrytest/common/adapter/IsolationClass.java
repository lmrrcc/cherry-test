package com.cherrytest.common.adapter;

import com.cherrytest.common.rustcompat.AnyhowError;

/** How a failed test behaved when it was rerun alone. */
public enum IsolationClass {
    /** Failed every time alone. */
    REAL,
    /** Passed every time alone: it failed only together with the rest of its package. */
    INTERFERENCE,
    /** Passed some times and failed others alone. */
    FLAKY,
    /** The reruns could not decide: the adapter cannot run one test, or a rerun hit an infrastructure error. */
    INCONCLUSIVE;

    public String wireName() {
        return name();
    }

    public static IsolationClass fromWire(String value) {
        for (IsolationClass candidate : values()) {
            if (candidate.name().equals(value)) {
                return candidate;
            }
        }
        throw new AnyhowError(
                "unknown variant `" + value + "`, expected one of `REAL`, `INTERFERENCE`, `FLAKY`, `INCONCLUSIVE`");
    }
}
