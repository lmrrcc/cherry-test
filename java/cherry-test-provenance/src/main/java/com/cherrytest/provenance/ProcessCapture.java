package com.cherrytest.provenance;

import java.lang.instrument.Instrumentation;
import java.util.*;

public final class ProcessCapture {
    private ProcessCapture() {}

    public static void premain(String options, Instrumentation instrumentation) throws Exception {
        if (System.getenv("CHERRY_TEST_PROVENANCE_TOKEN") == null) return;
        Class<?> api = Class.forName("org.jacoco.agent.rt.IAgent");
        Object agent =
                Class.forName("org.jacoco.agent.rt.RT").getMethod("getAgent").invoke(null);
        String id = UUID.randomUUID().toString();
        String session = "cherry:" + System.getenv("CHERRY_TEST_PROVENANCE_TOKEN") + ":" + id;
        api.getMethod("setSessionId", String.class).invoke(agent, session);
        Properties manifest = new Properties();
        manifest.setProperty("session", session);
        manifest.setProperty("pid", Long.toString(ProcessHandle.current().pid()));
        manifest.setProperty(
                "processStart",
                ProcessHandle.current().info().startInstant().orElseThrow().toString());
        manifest.setProperty("runtime", System.getProperty("java.runtime.version"));
        manifest.setProperty("jacoco", api.getMethod("getVersion").invoke(agent).toString());
        CaptureFiles.write(manifest, "process", id);
        Runtime.getRuntime()
                .addShutdownHook(new Thread(
                        () -> {
                            try {
                                manifest.setProperty(
                                        "completedAt", java.time.Instant.now().toString());
                                CaptureFiles.write(manifest, "finished", id);
                            } catch (java.io.IOException failure) {
                                System.getLogger(ProcessCapture.class.getName())
                                        .log(
                                                System.Logger.Level.ERROR,
                                                "Cherry process completion capture failed",
                                                failure);
                            }
                        },
                        "cherry-provenance-completion"));
    }
}
