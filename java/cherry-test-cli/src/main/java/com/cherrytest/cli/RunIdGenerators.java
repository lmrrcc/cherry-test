package com.cherrytest.cli;

final class RunIdGenerators {
    static final String RUN_ID_PROPERTY = "cherrytest.runId";

    private RunIdGenerators() {}

    static RunIdGenerator current() {
        String fixed = System.getProperty(RUN_ID_PROPERTY);
        if (fixed != null && !fixed.isEmpty()) {
            return RunIdGenerator.fixed(fixed);
        }
        return RunIdGenerator.systemClock();
    }
}
