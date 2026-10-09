use std::sync::Arc;

use anyhow::{bail, Result};
use reqwest::Client;

use crate::http::{HttpClient, HttpRecord, RecordSink};
use crate::naming::TestId;
use crate::workspace::{ServiceEndpoint, ServiceRegistry};

#[derive(Clone)]
pub struct TestEnv {
    pub test_id: TestId,
    pub scope: String,
    pub auth_profile: Option<String>,
    registry: Arc<ServiceRegistry>,
    pub(crate) client: Client,
    pub(crate) records: RecordSink,
}

impl TestEnv {
    pub fn new(
        test_id: TestId,
        scope: String,
        auth_profile: Option<String>,
        registry: ServiceRegistry,
    ) -> Self {
        Self {
            test_id,
            scope,
            auth_profile,
            registry: Arc::new(registry),
            client: Client::new(),
            records: RecordSink::new(),
        }
    }

    pub fn project<'a>(&'a self, project: &'a str) -> ProjectEnv<'a> {
        ProjectEnv { env: self, project }
    }

    pub(crate) fn endpoint(&self, project: &str, service: &str) -> Result<&ServiceEndpoint> {
        self.registry
            .get(&(project.to_string(), service.to_string()))
            .ok_or_else(|| {
                let known: Vec<_> = self
                    .registry
                    .keys()
                    .map(|(p, s)| format!("{p}.{s}"))
                    .collect();
                anyhow::anyhow!(
                    "unknown service `{project}.{service}`; known: {}",
                    known.join(", ")
                )
            })
    }

    pub fn drain_records(&self) -> Vec<HttpRecord> {
        self.records.drain()
    }
}

pub struct ProjectEnv<'a> {
    env: &'a TestEnv,
    project: &'a str,
}

impl<'a> ProjectEnv<'a> {
    pub fn service(&self, service: &str) -> Result<ServiceEnv<'a>> {
        let endpoint = self.env.endpoint(self.project, service)?;
        if endpoint.kind != "http" {
            bail!(
                "service `{}`.`{}` is kind `{}`, not http",
                self.project,
                service,
                endpoint.kind
            );
        }
        let base_url = endpoint
            .url
            .clone()
            .ok_or_else(|| anyhow::anyhow!("no URL for `{}`.`{}`", self.project, service))?;
        Ok(ServiceEnv {
            env: self.env,
            target_project: self.project.to_string(),
            target_service: service.to_string(),
            base_url,
        })
    }

    pub fn db(&self, service: &str) -> Result<DbEnv> {
        let endpoint = self.env.endpoint(self.project, service)?;
        Ok(DbEnv {
            project: self.project.to_string(),
            service: service.to_string(),
            host: endpoint.host.clone(),
            port: endpoint.port,
            kind: endpoint.kind.clone(),
        })
    }
}

pub struct DbEnv {
    pub project: String,
    pub service: String,
    pub host: String,
    pub port: u16,
    pub kind: String,
}

pub struct ServiceEnv<'a> {
    env: &'a TestEnv,
    target_project: String,
    target_service: String,
    base_url: String,
}

impl<'a> ServiceEnv<'a> {
    pub fn url(&self) -> &str {
        &self.base_url
    }

    pub fn get(&self, path: &str) -> HttpClient<'a> {
        HttpClient::new(
            self.env,
            &self.target_project,
            &self.target_service,
            &self.base_url,
            "GET",
            path,
            None,
        )
    }

    pub fn post(&self, path: &str) -> HttpClient<'a> {
        HttpClient::new(
            self.env,
            &self.target_project,
            &self.target_service,
            &self.base_url,
            "POST",
            path,
            None,
        )
    }
}
