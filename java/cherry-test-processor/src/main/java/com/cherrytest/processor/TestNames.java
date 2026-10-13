package com.cherrytest.processor;

import java.util.Optional;

final class TestNames {
    private static final String PROJECT = "_project_";
    private static final String SUITE = "_suite_";
    private static final String TEST = "_test";

    private TestNames() {}

    static Optional<String> violation(String fullName) {
        if (!fullName.endsWith(TEST)) {
            return Optional.of("cherry-test name `" + fullName
                    + "` must end with `_test`; expected {project}_project_{suite}_suite_{test}_test");
        }
        String stem = fullName.substring(0, fullName.length() - TEST.length());
        int suiteIndex = stem.lastIndexOf(SUITE);
        if (suiteIndex < 0) {
            return Optional.of(namingError(fullName));
        }
        String beforeSuite = stem.substring(0, suiteIndex);
        String test = stem.substring(suiteIndex + SUITE.length());
        int projectIndex = beforeSuite.indexOf(PROJECT);
        if (projectIndex < 0) {
            return Optional.of(namingError(fullName));
        }
        String project = beforeSuite.substring(0, projectIndex);
        String suite = beforeSuite.substring(projectIndex + PROJECT.length());
        if (project.isEmpty() || suite.isEmpty() || test.isEmpty()) {
            return Optional.of(namingError(fullName));
        }
        return Optional.empty();
    }

    private static String namingError(String fullName) {
        return "cherry-test name `" + fullName + "` must match {project}_project_{suite}_suite_{test}_test";
    }
}
