package com.cherrytest.junit;

import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.core.TestId;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public record HttpRecord(
        TestId testId,
        String scope,
        String targetProject,
        String targetService,
        long elapsedMs,
        String method,
        String url,
        int status,
        @Nullable String requestBody,
        @Nullable String authorization,
        String responseBody,
        boolean passed,
        @Nullable String failureReason) {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public void assertStatus(int expected) {
        if (status != expected) {
            throw new AssertionError("assertion `left == right` failed: expected HTTP " + expected + ", got " + status
                    + " for " + url + "\n  left: " + status + "\n right: " + expected);
        }
    }

    public String responseBodyText() {
        return responseBody;
    }

    public JsonNode responseBodyJson() {
        try {
            return JSON.readTree(responseBody);
        } catch (JacksonException exception) {
            throw AnyhowError.context("parse response JSON", exception);
        }
    }

    HttpRecord withOutcome(boolean outcomePassed, @Nullable String reason) {
        return new HttpRecord(
                testId,
                scope,
                targetProject,
                targetService,
                elapsedMs,
                method,
                url,
                status,
                requestBody,
                authorization,
                responseBody,
                outcomePassed,
                reason);
    }
}
