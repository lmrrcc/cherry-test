package com.cherrytest.cli;

import com.cherrytest.cli.nativeos.Terminal;
import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.common.rustcompat.RustOrdering;
import com.cherrytest.core.Auth;
import com.cherrytest.core.AuthProfileConfig;
import com.cherrytest.core.TestItConfig;
import java.nio.file.Path;
import java.util.TreeSet;

public final class AuthPreflight {
    private AuthPreflight() {}

    public static void runAuthPreflight(Path workspaceRoot, TestItConfig config, PreflightPlan plan, boolean quiet) {
        TreeSet<String> profiles = new TreeSet<>(RustOrdering.STRING_ORDER);
        for (DiscoveredTest test : plan.discovered()) {
            if (test.attrAuth() != null) {
                profiles.add(test.attrAuth());
            }
        }
        if (profiles.isEmpty()) {
            return;
        }
        for (String profileName : profiles) {
            AuthProfileConfig profile;
            try {
                profile = config.authProfile(profileName);
            } catch (AnyhowError error) {
                markAuthSkip(plan, profileName, AnyhowError.display(error), quiet);
                continue;
            }
            try {
                Auth.ensureProfileToken(workspaceRoot, profileName, profile);
            } catch (RuntimeException error) {
                markAuthSkip(plan, profileName, AnyhowError.alternate(error), quiet);
                continue;
            }
            if (!quiet) {
                Terminal.println("auth preflight: profile `" + profileName + "` ready");
            }
        }
    }

    private static void markAuthSkip(PreflightPlan plan, String profile, String reason, boolean quiet) {
        if (!quiet) {
            Terminal.eprintln("warning: auth profile `" + profile + "` unavailable: " + reason);
        }
        for (DiscoveredTest test : plan.discovered()) {
            if (!profile.equals(test.attrAuth())) {
                continue;
            }
            if (plan.skipTests().contains(test.function())) {
                continue;
            }
            if (!quiet) {
                Terminal.eprintln("  - " + test.function() + " (auth=" + profile + ")");
            }
            plan.skipTests().add(test.function());
            plan.skipped()
                    .add(new SkippedTest(
                            test.packageName(),
                            test.function(),
                            test.effectiveScope(),
                            test.project(),
                            test.suite(),
                            test.test(),
                            "auth unavailable"));
        }
    }
}
