use std::path::{Path, PathBuf};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use anyhow::{bail, Context, Result};
use reqwest::Client;
use serde::{Deserialize, Serialize};

use crate::config::AuthProfileConfig;
use crate::workspace::{process_environment, Environment};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OAuthCache {
    pub access_token: String,
    pub refresh_token: String,
    pub access_expires_at: u64,
    pub refresh_expires_at: u64,
}

pub fn env_var_for_profile(profile: &str) -> String {
    format!(
        "CHERRY_TEST_AUTH_{}_ACCESS_TOKEN",
        profile.to_uppercase().replace('-', "_")
    )
}

/// Fetches or refreshes the token of one profile and returns its `CHERRY_TEST_AUTH_*` variables.
///
/// The Okta mock URL is read from `env`; nothing global changes.
pub fn profile_token_env_blocking(
    workspace_root: &Path,
    profile_name: &str,
    profile: &AuthProfileConfig,
    env: &Environment,
) -> Result<Vec<(String, String)>> {
    tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .context("tokio runtime for auth preflight")?
        .block_on(profile_token_env(
            workspace_root,
            profile_name,
            profile,
            env,
        ))
}

/// Ensures a token for the current process and exports it to the process environment.
pub async fn ensure_profile_token(
    workspace_root: &Path,
    profile_name: &str,
    profile: &AuthProfileConfig,
) -> Result<()> {
    let env = process_environment();
    for (key, value) in profile_token_env(workspace_root, profile_name, profile, &env).await? {
        std::env::set_var(key, value);
    }
    Ok(())
}

async fn profile_token_env(
    workspace_root: &Path,
    profile_name: &str,
    profile: &AuthProfileConfig,
    env: &Environment,
) -> Result<Vec<(String, String)>> {
    let cache_path = resolve_cache_path(workspace_root, profile);
    let client = Client::new();
    let token_url = resolve_token_url(profile, env)?;
    let docker_okta = service_token_url(profile, env).is_ok();
    if docker_okta {
        let fresh = cherry_test_grant(&client, &token_url, profile).await?;
        write_cache(&cache_path, &fresh)?;
        return Ok(token_env(profile_name, &fresh.access_token));
    }
    if let Some(cache) = read_cache(&cache_path)? {
        if now_secs() < cache.access_expires_at.saturating_sub(30) {
            return Ok(token_env(profile_name, &cache.access_token));
        }
        if now_secs() < cache.refresh_expires_at.saturating_sub(30) {
            match refresh_token(&client, &token_url, profile, &cache.refresh_token).await {
                Ok(updated) => {
                    write_cache(&cache_path, &updated)?;
                    return Ok(token_env(profile_name, &updated.access_token));
                }
                Err(_) => {}
            }
        }
    }
    let fresh = cherry_test_grant(&client, &token_url, profile).await?;
    write_cache(&cache_path, &fresh)?;
    Ok(token_env(profile_name, &fresh.access_token))
}

pub fn bearer_from_env(profile: &str) -> Option<String> {
    let key = env_var_for_profile(profile);
    std::env::var(&key).ok().filter(|v| !v.is_empty())
}

/// The access token and token type variables of one profile, in export order.
fn token_env(profile: &str, access_token: &str) -> Vec<(String, String)> {
    vec![
        (env_var_for_profile(profile), access_token.to_string()),
        (
            format!(
                "CHERRY_TEST_AUTH_{}_TOKEN_TYPE",
                profile.to_uppercase().replace('-', "_")
            ),
            "Bearer".to_string(),
        ),
    ]
}

fn resolve_cache_path(workspace_root: &Path, profile: &AuthProfileConfig) -> PathBuf {
    let path = &profile.cache_path;
    if path.is_absolute() {
        path.clone()
    } else {
        workspace_root.join(path)
    }
}

fn resolve_token_url(profile: &AuthProfileConfig, env: &Environment) -> Result<String> {
    if let Ok(dynamic) = service_token_url(profile, env) {
        return Ok(dynamic);
    }
    profile
        .token_url
        .clone()
        .ok_or_else(|| anyhow::anyhow!("token_url missing and okta_api service URL unavailable"))
}

fn service_token_url(profile: &AuthProfileConfig, env: &Environment) -> Result<String> {
    let project = profile.okta_api_project.as_deref().unwrap_or("mini");
    let service = profile.okta_api_service.as_deref().unwrap_or("okta_api");
    let prefix = format!(
        "CHERRY_TEST_{}_{}",
        project.to_uppercase(),
        service.to_uppercase()
    );
    let base = env
        .get(&format!("{prefix}_URL"))
        .context("okta_api service URL not exported; boot docker scope first")?;
    Ok(format!(
        "{}/oauth2/default/v1/token",
        base.trim_end_matches('/')
    ))
}

fn read_cache(path: &Path) -> Result<Option<OAuthCache>> {
    if !path.exists() {
        return Ok(None);
    }
    let raw = std::fs::read_to_string(path)?;
    let cache: OAuthCache = serde_json::from_str(&raw).context("parse oauth cache")?;
    Ok(Some(cache))
}

fn write_cache(path: &Path, cache: &OAuthCache) -> Result<()> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    std::fs::write(path, serde_json::to_string_pretty(cache)?)?;
    Ok(())
}

async fn cherry_test_grant(
    client: &Client,
    token_url: &str,
    profile: &AuthProfileConfig,
) -> Result<OAuthCache> {
    let params = [
        ("grant_type", "cherry_test"),
        ("email", profile.email.as_str()),
        ("client_id", profile.client_id.as_str()),
        ("client_secret", profile.client_secret.as_str()),
    ];
    let response = client
        .post(token_url)
        .form(&params)
        .send()
        .await
        .context("cherry_test token request")?;
    if !response.status().is_success() {
        let body = response.text().await.unwrap_or_default();
        bail!("cherry_test grant failed: {body}");
    }
    let body: TokenResponse = response.json().await.context("parse token response")?;
    build_cache(profile, &body)
}

async fn refresh_token(
    client: &Client,
    token_url: &str,
    profile: &AuthProfileConfig,
    refresh_token: &str,
) -> Result<OAuthCache> {
    let params = [
        ("grant_type", "refresh_token"),
        ("refresh_token", refresh_token),
        ("client_id", profile.client_id.as_str()),
        ("client_secret", profile.client_secret.as_str()),
    ];
    let response = client
        .post(token_url)
        .form(&params)
        .send()
        .await
        .context("refresh token request")?;
    if !response.status().is_success() {
        bail!("refresh token failed");
    }
    let body: TokenResponse = response.json().await.context("parse refresh response")?;
    build_cache(profile, &body)
}

fn build_cache(profile: &AuthProfileConfig, body: &TokenResponse) -> Result<OAuthCache> {
    let now = now_secs();
    Ok(OAuthCache {
        access_token: body.access_token.clone(),
        refresh_token: body.refresh_token.clone(),
        access_expires_at: now + body.expires_in,
        refresh_expires_at: now + profile.refresh_ttl_secs,
    })
}

fn now_secs() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or(Duration::ZERO)
        .as_secs()
}

#[derive(Debug, Deserialize)]
struct TokenResponse {
    access_token: String,
    refresh_token: String,
    expires_in: u64,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn env_var_name_for_okta() {
        assert_eq!(
            env_var_for_profile("okta"),
            "CHERRY_TEST_AUTH_OKTA_ACCESS_TOKEN"
        );
    }

    #[test]
    fn token_env_names_the_access_token_and_its_type() {
        assert_eq!(
            token_env("okta-mock", "abc"),
            vec![
                (
                    "CHERRY_TEST_AUTH_OKTA_MOCK_ACCESS_TOKEN".to_string(),
                    "abc".to_string()
                ),
                (
                    "CHERRY_TEST_AUTH_OKTA_MOCK_TOKEN_TYPE".to_string(),
                    "Bearer".to_string()
                ),
            ]
        );
    }

    #[test]
    fn build_cache_sets_expiry() {
        let profile = AuthProfileConfig {
            profile: "okta".into(),
            issuer: "http://example/oauth2/default".into(),
            client_id: "c".into(),
            client_secret: "s".into(),
            audience: "aud".into(),
            email: "luca@email.com".into(),
            access_ttl_secs: 3600,
            refresh_ttl_secs: 28800,
            token_url: None,
            cache_path: PathBuf::from("target/cherry-test/oauth/okta.json"),
            okta_api_project: None,
            okta_api_service: None,
        };
        let body = TokenResponse {
            access_token: "a".into(),
            refresh_token: "r".into(),
            expires_in: 60,
        };
        let cache = build_cache(&profile, &body).unwrap();
        assert_eq!(cache.access_token, "a");
        assert!(cache.access_expires_at >= now_secs());
    }
}
