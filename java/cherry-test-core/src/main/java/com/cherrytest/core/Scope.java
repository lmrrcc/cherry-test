package com.cherrytest.core;

import com.cherrytest.common.gitdiff.GitDiff;
import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.common.rustcompat.RustText;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

public final class Scope {
    private Scope() {}

    public static boolean isNone(String scope) {
        return RustText.toAsciiLowercase(scope).equals("none");
    }

    public static boolean isGlobal(String scope) {
        return switch (RustText.toAsciiLowercase(scope)) {
            case "workspace", "all", "global" -> true;
            default -> false;
        };
    }

    public static boolean needsDocker(String scope) {
        return !isNone(scope);
    }

    public static String effectiveScope(TestItConfig config, @Nullable String attributeScope) {
        return attributeScope != null ? attributeScope : config.defaultScope();
    }

    public static List<String> resolveProjects(TestItConfig config, String scope) {
        if (isNone(scope)) {
            return List.of();
        }
        if (isGlobal(scope)) {
            return List.copyOf(config.projects().keySet());
        }
        ScopeConfig scopeConfig = config.scopes().get(scope);
        if (scopeConfig != null) {
            return scopeConfig.projects();
        }
        if (config.projects().containsKey(scope)) {
            return List.of(scope);
        }
        List<String> known = new ArrayList<>(config.scopes().keySet());
        known.addAll(config.projects().keySet());
        throw new AnyhowError("unknown cherry-test scope `" + scope + "`; known scopes/projects: "
                + String.join(", ", GitDiff.sortedDedup(known)));
    }

    public static boolean serviceFilter(TestItConfig config, String scope, String project, String service) {
        if (isNone(scope) || isGlobal(scope) || config.projects().containsKey(scope)) {
            return true;
        }
        ScopeConfig scopeConfig = config.scopes().get(scope);
        if (scopeConfig == null) {
            return true;
        }
        List<String> services = scopeConfig.services().get(project);
        if (services == null) {
            return true;
        }
        return services.contains(service);
    }
}
