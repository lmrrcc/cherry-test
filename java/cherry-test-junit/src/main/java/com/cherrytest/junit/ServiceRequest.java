package com.cherrytest.junit;

import com.cherrytest.common.process.ProcessEnvironment;
import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.core.Auth;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

public final class ServiceRequest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final TestEnv env;
    private final String targetProject;
    private final String targetService;
    private final String url;
    private final String method;
    private @Nullable String body;
    private @Nullable String bearer;
    private @Nullable String contentType;

    ServiceRequest(
            TestEnv env, String targetProject, String targetService, String baseUrl, String method, String path) {
        this.env = env;
        this.targetProject = targetProject;
        this.targetService = targetService;
        this.method = method;
        this.url = path.startsWith("http://") || path.startsWith("https://")
                ? path
                : trimEnd(baseUrl, '/') + "/" + trimStart(path, '/');
        String profile = env.authProfile();
        this.bearer = profile == null ? null : bearerFromEnvironment(profile);
    }

    public ServiceRequest bearer(String token) {
        this.bearer = token;
        return this;
    }

    public ServiceRequest jsonBody(Object value) {
        try {
            this.body = JSON.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw AnyhowError.context("serialize JSON request body", exception);
        }
        this.contentType = "application/json";
        return this;
    }

    public HttpRecord send() {
        long started = System.nanoTime();
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .method(
                        method,
                        body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (contentType != null) {
            request.header("content-type", contentType);
        }
        if (bearer != null) {
            request.header("authorization", "Bearer " + bearer);
        }
        String authorization = bearer == null ? null : "Bearer [redacted]";
        int status;
        String responseBody;
        try {
            HttpResponse<String> response = env.client().send(request.build(), HttpResponse.BodyHandlers.ofString());
            status = response.statusCode();
            responseBody = response.body();
        } catch (IOException exception) {
            status = 0;
            responseBody = "error sending request for url (" + url + ")";
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            status = 0;
            responseBody = "error sending request for url (" + url + ")";
        }
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        HttpRecord record = new HttpRecord(
                env.testId(),
                env.scope(),
                targetProject,
                targetService,
                elapsedMs,
                method,
                url,
                status,
                body,
                authorization,
                responseBody,
                true,
                null);
        env.record(record);
        return record;
    }

    private static @Nullable String bearerFromEnvironment(String profile) {
        String token = ProcessEnvironment.get(Auth.envVarForProfile(profile));
        return token == null || token.isEmpty() ? null : token;
    }

    private static String trimEnd(String value, char character) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == character) {
            end--;
        }
        return value.substring(0, end);
    }

    private static String trimStart(String value, char character) {
        int start = 0;
        while (start < value.length() && value.charAt(start) == character) {
            start++;
        }
        return value.substring(start);
    }
}
