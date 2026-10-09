use std::collections::BTreeMap;
use std::path::PathBuf;

use serde::{Deserialize, Serialize};

pub const PROTOCOL_VERSION: u32 = 1;

#[derive(Debug, Clone, Copy, Default, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum ExecutionMode {
    #[default]
    Local,
    Container,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct AdapterConfig {
    pub command: String,
    #[serde(default)]
    pub args: Vec<String>,
    #[serde(default = "default_timeout")]
    pub timeout_secs: u64,
}

pub fn default_timeout() -> u64 {
    1800
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct AdapterRequest {
    pub protocol: u32,
    pub project: String,
    pub package: String,
    pub phase: String,
    pub root: PathBuf,
    pub manifest: PathBuf,
    pub cwd: PathBuf,
    pub output: PathBuf,
    pub execution: ExecutionMode,
    pub coverage: bool,
    pub strict: bool,
    #[serde(default)]
    pub args: Vec<String>,
    #[serde(default)]
    pub env: BTreeMap<String, String>,
    #[serde(default)]
    pub options: BTreeMap<String, serde_json::Value>,
}

#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum TestStatus {
    Passed,
    Failed,
    Skipped,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct Attachment {
    pub name: String,
    pub path: PathBuf,
    pub media_type: String,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct TestResult {
    pub name: String,
    pub status: TestStatus,
    #[serde(default)]
    pub duration_ms: u64,
    #[serde(default)]
    pub message: Option<String>,
    #[serde(default)]
    pub source: Option<PathBuf>,
    #[serde(default)]
    pub line: Option<u32>,
    #[serde(default)]
    pub attachments: Vec<Attachment>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct AdapterResponse {
    pub protocol: u32,
    pub success: bool,
    #[serde(default)]
    pub tests: Vec<TestResult>,
    #[serde(default)]
    pub coverage: Vec<PathBuf>,
    #[serde(default)]
    pub diagnostics: Vec<String>,
}

impl Default for AdapterResponse {
    fn default() -> Self {
        Self {
            protocol: PROTOCOL_VERSION,
            success: true,
            tests: Vec::new(),
            coverage: Vec::new(),
            diagnostics: Vec::new(),
        }
    }
}
