use std::collections::BTreeMap;
use std::path::PathBuf;

use serde::{Deserialize, Serialize};

use crate::container::ContainerSpec;

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
    /// The adapter runs only the tests a request names in `select`, so failed tests can be rerun alone.
    #[serde(default, skip_serializing_if = "is_false")]
    pub isolate: bool,
}

fn is_false(value: &bool) -> bool {
    !*value
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
    /// Resolved `container:` settings; present only for container execution with a configured block.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub container: Option<ContainerSpec>,
    /// Test names to run and nothing else; present only when a failed test is rerun alone by an
    /// adapter registered with `isolate: true`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub select: Option<Vec<String>>,
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
    /// What rerunning this failed test alone showed; set by Cherry Test, never by an adapter.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub isolation: Option<Isolation>,
}

/// How a failed test behaved when it was rerun alone.
#[derive(Debug, Clone, Copy, Deserialize, Serialize, PartialEq, Eq, PartialOrd, Ord)]
#[serde(rename_all = "UPPERCASE")]
pub enum IsolationClass {
    /// Failed every time alone.
    Real,
    /// Passed every time alone: it failed only together with the rest of its package.
    Interference,
    /// Passed some times and failed others alone.
    Flaky,
    /// The reruns could not decide: the adapter cannot run one test, or a rerun hit an infrastructure error.
    Inconclusive,
}

impl IsolationClass {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Real => "REAL",
            Self::Interference => "INTERFERENCE",
            Self::Flaky => "FLAKY",
            Self::Inconclusive => "INCONCLUSIVE",
        }
    }
}

/// The classification of one failed test and the reruns behind it.
#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
pub struct Isolation {
    pub class: IsolationClass,
    pub runs: u32,
    pub passed: u32,
    /// Output directory of each rerun, relative to the run's `reports/`.
    pub logs: Vec<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub reason: Option<String>,
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
