package com.cherrytest.junit;

import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.core.ServiceEndpoint;
import com.cherrytest.core.TestId;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public final class TestEnv {
    private final TestId testId;
    private final String scope;
    private final @Nullable String authProfile;
    private final Map<String, ServiceEndpoint> registry;
    private final HttpClient client;
    private final List<HttpRecord> records = new ArrayList<>();

    TestEnv(TestId testId, String scope, @Nullable String authProfile, Map<String, ServiceEndpoint> registry) {
        this.testId = testId;
        this.scope = scope;
        this.authProfile = authProfile;
        this.registry = Map.copyOf(registry);
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public TestId testId() {
        return testId;
    }

    public String scope() {
        return scope;
    }

    public @Nullable String authProfile() {
        return authProfile;
    }

    public ProjectEnv project(String project) {
        return new ProjectEnv(this, project);
    }

    ServiceEndpoint endpoint(String project, String service) {
        ServiceEndpoint endpoint = registry.get(project + "\u0000" + service);
        if (endpoint == null) {
            List<String> known = registry.keySet().stream()
                    .map(key -> key.replace('\u0000', '.'))
                    .toList();
            throw new AnyhowError(
                    "unknown service `" + project + "." + service + "`; known: " + String.join(", ", known));
        }
        return endpoint;
    }

    HttpClient client() {
        return client;
    }

    synchronized void record(HttpRecord record) {
        records.add(record);
    }

    synchronized List<HttpRecord> drainRecords() {
        List<HttpRecord> drained = List.copyOf(records);
        records.clear();
        return drained;
    }
}
