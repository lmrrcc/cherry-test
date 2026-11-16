package com.cherrytest.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuthTest {
    @Test
    void env_var_name_for_okta() {
        assertThat(Auth.envVarForProfile("okta")).isEqualTo("CHERRY_TEST_AUTH_OKTA_ACCESS_TOKEN");
    }

    @Test
    void token_env_names_the_access_token_and_its_type() {
        assertThat(Auth.tokenEnv("okta-mock", "abc"))
                .containsExactly(
                        Map.entry("CHERRY_TEST_AUTH_OKTA_MOCK_ACCESS_TOKEN", "abc"),
                        Map.entry("CHERRY_TEST_AUTH_OKTA_MOCK_TOKEN_TYPE", "Bearer"));
    }

    @Test
    void build_cache_sets_expiry() {
        AuthProfileConfig profile = new AuthProfileConfig(
                "okta",
                "http://example/oauth2/default",
                "c",
                "s",
                "aud",
                "luca@email.com",
                3600,
                28800,
                null,
                Path.of("target/cherry-test/oauth/okta.json"),
                null,
                null);
        Auth.OAuthCache cache = Auth.buildCache(profile, new Auth.TokenResponse("a", "r", 60));
        assertThat(cache.accessToken()).isEqualTo("a");
        assertThat(cache.accessExpiresAt()).isGreaterThanOrEqualTo(Auth.nowSecs());
    }
}
