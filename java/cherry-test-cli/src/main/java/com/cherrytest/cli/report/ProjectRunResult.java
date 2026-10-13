package com.cherrytest.cli.report;

public record ProjectRunResult(String projectId, String packageName, boolean testsPassed, PackageCoverage coverage) {}
