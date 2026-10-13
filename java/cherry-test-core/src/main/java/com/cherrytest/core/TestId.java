package com.cherrytest.core;

import com.cherrytest.common.rustcompat.AnyhowError;

public record TestId(String project, String suite, String test, String fullName) {
    private static final String PROJECT = "_project_";
    private static final String SUITE = "_suite_";
    private static final String TEST = "_test";

    public static TestId parse(String fullName) {
        if (!fullName.endsWith(TEST)) {
            throw new AnyhowError("cherry-test name `" + fullName
                    + "` must end with `_test`; expected {project}_project_{suite}_suite_{test}_test");
        }
        String stem = fullName.substring(0, fullName.length() - TEST.length());
        int suiteIndex = stem.lastIndexOf(SUITE);
        if (suiteIndex < 0) {
            throw new AnyhowError(namingError(fullName));
        }
        String beforeSuite = stem.substring(0, suiteIndex);
        String test = stem.substring(suiteIndex + SUITE.length());
        int projectIndex = beforeSuite.indexOf(PROJECT);
        if (projectIndex < 0) {
            throw new AnyhowError(namingError(fullName));
        }
        String project = beforeSuite.substring(0, projectIndex);
        String suite = beforeSuite.substring(projectIndex + PROJECT.length());
        if (project.isEmpty() || suite.isEmpty() || test.isEmpty()) {
            throw new AnyhowError(namingError(fullName));
        }
        return new TestId(project, suite, test, fullName);
    }

    public static boolean isValid(String fullName) {
        try {
            parse(fullName);
            return true;
        } catch (AnyhowError error) {
            return false;
        }
    }

    private static String namingError(String fullName) {
        return "cherry-test name `" + fullName + "` must match {project}_project_{suite}_suite_{test}_test";
    }
}
