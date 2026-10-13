package com.cherrytest.cli;

import com.cherrytest.common.rustcompat.RustText;
import com.cherrytest.core.TestItConfig;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

public enum TestKind {
    UNIT("unit"),
    INTEGRATION("integration"),
    FUNCTIONAL("functional"),
    UI("ui"),
    VISUAL("visual"),
    MUTATION("mutation");

    private final String text;

    TestKind(String text) {
        this.text = text;
    }

    public static @Nullable TestKind parse(String value) {
        return switch (RustText.toLowercase(value)) {
            case "unit" -> UNIT;
            case "integration" -> INTEGRATION;
            case "functional" -> FUNCTIONAL;
            case "ui" -> UI;
            case "visual" -> VISUAL;
            case "mutation" -> MUTATION;
            default -> null;
        };
    }

    public String asStr() {
        return text;
    }

    public static List<TestKind> packageKinds(TestItConfig config, String packageName, String projectId) {
        List<TestKind> kinds = new ArrayList<>();
        for (String kind : config.packageKinds(packageName, projectId)) {
            TestKind parsed = parse(kind);
            if (parsed != null) {
                kinds.add(parsed);
            }
        }
        return kinds;
    }
}
