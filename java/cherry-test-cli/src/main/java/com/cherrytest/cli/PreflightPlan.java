package com.cherrytest.cli;

import java.util.ArrayList;
import java.util.List;

public final class PreflightPlan {
    private final boolean dockerAvailable;
    private final List<String> skipTests;
    private final List<SkippedTest> skipped;
    private final List<String> scopesToBoot;
    private final List<DiscoveredTest> discovered;

    public PreflightPlan(
            boolean dockerAvailable,
            List<String> skipTests,
            List<SkippedTest> skipped,
            List<String> scopesToBoot,
            List<DiscoveredTest> discovered) {
        this.dockerAvailable = dockerAvailable;
        this.skipTests = new ArrayList<>(skipTests);
        this.skipped = new ArrayList<>(skipped);
        this.scopesToBoot = new ArrayList<>(scopesToBoot);
        this.discovered = new ArrayList<>(discovered);
    }

    public boolean dockerAvailable() {
        return dockerAvailable;
    }

    public List<String> skipTests() {
        return skipTests;
    }

    public List<SkippedTest> skipped() {
        return skipped;
    }

    public List<String> scopesToBoot() {
        return scopesToBoot;
    }

    public List<DiscoveredTest> discovered() {
        return discovered;
    }

    public PreflightPlan copy() {
        return new PreflightPlan(dockerAvailable, skipTests, skipped, scopesToBoot, discovered);
    }
}
