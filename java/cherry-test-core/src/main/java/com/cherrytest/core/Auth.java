package com.cherrytest.core;

import com.cherrytest.common.process.ProcessEnvironment;
import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.common.rustcompat.RustIoError;
import com.cherrytest.common.rustcompat.RustText;
import com.cherrytest.common.rustcompat.SerdeJsonWriter;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

public final class Auth {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    record OAuthCache(String accessToken, String refreshToken, long accessExpiresAt, long refreshExpiresAt) {}

    private Auth() {}

    public static String envVarForProfile(String profile) {
        return "CHERRY_TEST_AUTH_" + RustText.toUppercase(profile).replace('-', '_') + "_ACCESS_TOKEN";
    }

    public static void ensureProfileToken(Path workspaceRoot, String profileName, AuthProfileConfig profile) {
        Path cachePath = resolveCachePath(workspaceRoot, profile);
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        String tokenUrl = resolveTokenUrl(profile);
        boolean dockerOkta = serviceTokenUrl(profile) != null;
        if (dockerOkta) {
            OAuthCache fresh = cherryTestGrant(client, tokenUrl, profile);
            writeCache(cachePath, fresh);
            exportToken(profileName, fresh.accessToken());
            return;
        }
        OAuthCache cache = readCache(cachePath);
        if (cache != null) {
            if (nowSecs() < saturatingSub(cache.accessExpiresAt(), 30)) {
                exportToken(profileName, cache.accessToken());
                return;
            }
            if (nowSecs() < saturatingSub(cache.refreshExpiresAt(), 30)) {
                OAuthCache updated = tryRefresh(client, tokenUrl, profile, cache.refreshToken());
                if (updated != null) {
                    writeCache(cachePath, updated);
                    exportToken(profileName, updated.accessToken());
                    return;
                }
            }
        }
        OAuthCache fresh = cherryTestGrant(client, tokenUrl, profile);
        writeCache(cachePath, fresh);
        exportToken(profileName, fresh.accessToken());
    }

    private static @Nullable OAuthCache tryRefresh(
            HttpClient client, String tokenUrl, AuthProfileConfig profile, String refreshToken) {
        try {
            return refreshToken(client, tokenUrl, profile, refreshToken);
        } catch (AnyhowError refreshFailure) {
            return null;
        }
    }

    private static long saturatingSub(long value, long amount) {
        return value < amount ? 0 : value - amount;
    }

    private static void exportToken(String profile, String accessToken) {
        ProcessEnvironment.set(envVarForProfile(profile), accessToken);
        ProcessEnvironment.set(
                "CHERRY_TEST_AUTH_" + RustText.toUppercase(profile).replace('-', '_') + "_TOKEN_TYPE", "Bearer");
    }

    private static Path resolveCachePath(Path workspaceRoot, AuthProfileConfig profile) {
        Path path = profile.cachePath();
        return path.isAbsolute() ? path : workspaceRoot.resolve(path);
    }

    private static String resolveTokenUrl(AuthProfileConfig profile) {
        String dynamic = serviceTokenUrl(profile);
        if (dynamic != null) {
            return dynamic;
        }
        if (profile.tokenUrl() == null) {
            throw new AnyhowError("token_url missing and okta_api service URL unavailable");
        }
        return profile.tokenUrl();
    }

    private static @Nullable String serviceTokenUrl(AuthProfileConfig profile) {
        String project = profile.oktaApiProject() == null ? "mini" : profile.oktaApiProject();
        String service = profile.oktaApiService() == null ? "okta_api" : profile.oktaApiService();
        String prefix = "CHERRY_TEST_" + RustText.toUppercase(project) + "_" + RustText.toUppercase(service);
        String base = ProcessEnvironment.get(prefix + "_URL");
        if (base == null) {
            return null;
        }
        String trimmed = base;
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed + "/oauth2/default/v1/token";
    }

    private static @Nullable OAuthCache readCache(Path path) {
        if (!Files.exists(path)) {
            return null;
        }
        String raw;
        try {
            raw = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new AnyhowError(RustIoError.display(exception), null);
        }
        try {
            JsonNode node = JSON.readTree(raw);
            return new OAuthCache(
                    node.get("access_token").stringValue(),
                    node.get("refresh_token").stringValue(),
                    node.get("access_expires_at").longValue(),
                    node.get("refresh_expires_at").longValue());
        } catch (RuntimeException exception) {
            throw AnyhowError.context("parse oauth cache", exception);
        }
    }

    private static void writeCache(Path path, OAuthCache cache) {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("access_token", cache.accessToken());
            node.put("refresh_token", cache.refreshToken());
            node.put("access_expires_at", cache.accessExpiresAt());
            node.put("refresh_expires_at", cache.refreshExpiresAt());
            Files.writeString(path, SerdeJsonWriter.pretty(node), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new AnyhowError(RustIoError.display(exception), null);
        }
    }

    private static OAuthCache cherryTestGrant(HttpClient client, String tokenUrl, AuthProfileConfig profile) {
        List<Map.Entry<String, String>> parameters = List.of(
                Map.entry("grant_type", "cherry_test"),
                Map.entry("email", profile.email()),
                Map.entry("client_id", profile.clientId()),
                Map.entry("client_secret", profile.clientSecret()));
        HttpResponse<String> response = post(client, tokenUrl, parameters, "cherry_test token request");
        if (response.statusCode() < 200 || response.statusCode() > 299) {
            throw new AnyhowError("cherry_test grant failed: " + response.body());
        }
        return buildCache(profile, parseTokenResponse(response.body(), "parse token response"));
    }

    private static OAuthCache refreshToken(
            HttpClient client, String tokenUrl, AuthProfileConfig profile, String refreshToken) {
        List<Map.Entry<String, String>> parameters = List.of(
                Map.entry("grant_type", "refresh_token"),
                Map.entry("refresh_token", refreshToken),
                Map.entry("client_id", profile.clientId()),
                Map.entry("client_secret", profile.clientSecret()));
        HttpResponse<String> response = post(client, tokenUrl, parameters, "refresh token request");
        if (response.statusCode() < 200 || response.statusCode() > 299) {
            throw new AnyhowError("refresh token failed");
        }
        return buildCache(profile, parseTokenResponse(response.body(), "parse refresh response"));
    }

    private static HttpResponse<String> post(
            HttpClient client, String url, List<Map.Entry<String, String>> parameters, String context) {
        StringBuilder form = new StringBuilder();
        for (Map.Entry<String, String> parameter : parameters) {
            if (!form.isEmpty()) {
                form.append('&');
            }
            form.append(URLEncoder.encode(parameter.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(parameter.getValue(), StandardCharsets.UTF_8));
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("content-type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form.toString()))
                .build();
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw AnyhowError.context(context, new AnyhowError("error sending request for url (" + url + ")"));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw AnyhowError.context(context, new AnyhowError("interrupted"));
        }
    }

    record TokenResponse(String accessToken, String refreshToken, long expiresIn) {}

    private static TokenResponse parseTokenResponse(String body, String context) {
        try {
            JsonNode node = JSON.readTree(body);
            return new TokenResponse(
                    node.get("access_token").stringValue(),
                    node.get("refresh_token").stringValue(),
                    node.get("expires_in").longValue());
        } catch (RuntimeException exception) {
            throw AnyhowError.context(context, exception);
        }
    }

    static OAuthCache buildCache(AuthProfileConfig profile, TokenResponse body) {
        long now = nowSecs();
        return new OAuthCache(
                body.accessToken(), body.refreshToken(), now + body.expiresIn(), now + profile.refreshTtlSecs());
    }

    static long nowSecs() {
        return System.currentTimeMillis() / 1000;
    }
}
