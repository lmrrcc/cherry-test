package com.cherrytest.cli;

public record SkippedTest(
        String packageName, String function, String scope, String project, String suite, String test, String reason) {}
