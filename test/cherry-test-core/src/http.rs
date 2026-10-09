use std::sync::{Arc, Mutex};

use anyhow::{Context, Result};
use reqwest::Method;
use serde_json::Value;

use crate::auth;
use crate::env::TestEnv;
use crate::naming::TestId;

#[derive(Debug, Clone)]
pub struct HttpRecord {
    pub test_id: TestId,
    pub scope: String,
    pub target_project: String,
    pub target_service: String,
    pub elapsed_ms: u128,
    pub method: String,
    pub url: String,
    pub status: u16,
    pub request_body: Option<String>,
    pub authorization: Option<String>,
    pub response_body: String,
    pub passed: bool,
    pub failure_reason: Option<String>,
}

impl HttpRecord {
    pub fn assert_status(&self, expected: u16) {
        assert_eq!(
            self.status, expected,
            "expected HTTP {expected}, got {} for {}",
            self.status, self.url
        );
    }

    pub fn response_body_text(&self) -> &str {
        &self.response_body
    }

    pub fn response_body_json(&self) -> Result<Value> {
        serde_json::from_str(&self.response_body).context("parse response JSON")
    }
}

#[derive(Clone, Default)]
pub struct RecordSink(Arc<Mutex<Vec<HttpRecord>>>);

impl RecordSink {
    pub fn new() -> Self {
        Self(Arc::new(Mutex::new(Vec::new())))
    }

    pub fn push(&self, record: HttpRecord) {
        self.0.lock().unwrap().push(record);
    }

    pub fn drain(&self) -> Vec<HttpRecord> {
        let mut guard = self.0.lock().unwrap();
        std::mem::take(&mut *guard)
    }
}

pub struct HttpClient<'a> {
    env: &'a TestEnv,
    target_project: String,
    target_service: String,
    url: String,
    method: String,
    body: Option<String>,
    bearer: Option<String>,
    content_type: Option<String>,
}

impl<'a> HttpClient<'a> {
    pub fn new(
        env: &'a TestEnv,
        target_project: &str,
        target_service: &str,
        base_url: &str,
        method: &str,
        path: &str,
        body: Option<String>,
    ) -> Self {
        let url = if path.starts_with("http://") || path.starts_with("https://") {
            path.to_string()
        } else {
            format!(
                "{}/{}",
                base_url.trim_end_matches('/'),
                path.trim_start_matches('/')
            )
        };
        let bearer = env
            .auth_profile
            .as_ref()
            .and_then(|profile| auth::bearer_from_env(profile));
        Self {
            env,
            target_project: target_project.to_string(),
            target_service: target_service.to_string(),
            url,
            method: method.to_string(),
            body,
            bearer,
            content_type: None,
        }
    }

    pub fn bearer(mut self, token: impl Into<String>) -> Self {
        self.bearer = Some(token.into());
        self
    }

    pub fn json_body(mut self, value: &Value) -> Result<Self> {
        self.body = Some(serde_json::to_string(value)?);
        self.content_type = Some("application/json".into());
        Ok(self)
    }

    pub async fn send(self) -> HttpRecord {
        let started = std::time::Instant::now();
        let client = self.env.client.clone();
        let method = Method::from_bytes(self.method.as_bytes()).unwrap_or(Method::GET);
        let mut req = client.request(method, &self.url);
        if let Some(body) = &self.body {
            req = req.body(body.clone());
        }
        if let Some(content_type) = &self.content_type {
            req = req.header("content-type", content_type);
        }
        if let Some(token) = &self.bearer {
            req = req.header("authorization", format!("Bearer {token}"));
        }
        let authorization = self
            .bearer
            .as_ref()
            .map(|_| "Bearer [redacted]".to_string());
        let response: Result<reqwest::Response, reqwest::Error> = req.send().await;
        let elapsed_ms = started.elapsed().as_millis();
        let (status, response_body) = match response {
            Ok(resp) => {
                let status = resp.status().as_u16();
                let body = resp.text().await.unwrap_or_default();
                (status, body)
            }
            Err(err) => (0, err.to_string()),
        };
        let record = HttpRecord {
            test_id: self.env.test_id.clone(),
            scope: self.env.scope.clone(),
            target_project: self.target_project,
            target_service: self.target_service,
            elapsed_ms,
            method: self.method,
            url: self.url,
            status,
            request_body: self.body,
            authorization,
            response_body,
            passed: true,
            failure_reason: None,
        };
        self.env.records.push(record.clone());
        record
    }
}
