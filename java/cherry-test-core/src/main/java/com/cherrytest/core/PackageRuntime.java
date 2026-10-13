package com.cherrytest.core;

public enum PackageRuntime {
    RUST("rust"),
    NODE("node");

    private final String wireName;

    PackageRuntime(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
