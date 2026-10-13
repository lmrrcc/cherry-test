package com.cherrytest.cli.crap;

import java.util.ArrayList;
import java.util.List;

final class RustLexicalPath {
    private enum Kind {
        ROOT,
        CURRENT,
        PARENT,
        NORMAL
    }

    private record Component(Kind kind, String name) {}

    private final List<Component> components;

    private RustLexicalPath(List<Component> components) {
        this.components = List.copyOf(components);
    }

    static RustLexicalPath of(String path) {
        List<Component> components = new ArrayList<>();
        if (path.startsWith("/")) {
            components.add(new Component(Kind.ROOT, ""));
        } else if (path.equals(".") || path.startsWith("./")) {
            components.add(new Component(Kind.CURRENT, ""));
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            components.add(segment.equals("..") ? new Component(Kind.PARENT, "") : new Component(Kind.NORMAL, segment));
        }
        return new RustLexicalPath(components);
    }

    static String join(String base, String relative) {
        if (relative.isEmpty()) {
            return base.endsWith("/") ? base : base + "/";
        }
        if (relative.startsWith("/")) {
            return relative;
        }
        return base.endsWith("/") ? base + relative : base + "/" + relative;
    }

    RustLexicalPath normalized() {
        List<Component> out = new ArrayList<>();
        for (Component component : components) {
            switch (component.kind()) {
                case CURRENT -> {}
                case PARENT -> {
                    if (!out.isEmpty() && out.getLast().kind() == Kind.NORMAL) {
                        out.removeLast();
                    } else if (out.isEmpty() || out.getLast().kind() == Kind.PARENT) {
                        out.add(component);
                    }
                }
                case ROOT, NORMAL -> out.add(component);
            }
        }
        return new RustLexicalPath(out);
    }

    boolean isEmpty() {
        return components.isEmpty();
    }

    int componentCount() {
        return components.size();
    }

    boolean startsWith(RustLexicalPath prefix) {
        if (prefix.components.size() > components.size()) {
            return false;
        }
        for (int index = 0; index < prefix.components.size(); index++) {
            if (!prefix.components.get(index).equals(components.get(index))) {
                return false;
            }
        }
        return true;
    }
}
