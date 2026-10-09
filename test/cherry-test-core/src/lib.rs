pub mod auth;
pub mod config;
pub mod env;
pub mod http;
pub mod integration;
pub mod naming;
pub mod repo_root;
pub mod report;
pub mod scope;
pub mod workspace;

pub use cherry_test_macros::cherry_test;
pub use config::{
    AuthProfileConfig, Defaults, ExternalRunner, PackageConfig, PackageRuntime, ProjectConfig,
    ScopeConfig, ServiceConfig, TestItConfig, WorkspaceConfig,
};
pub use env::{DbEnv, ProjectEnv, ServiceEnv, TestEnv};
pub use http::HttpRecord;
pub use integration::run;
pub use naming::{parse_test_name, TestId};
pub use repo_root::{resolve_config_roots, resolve_repo_root};
pub use scope::{effective_scope, is_none, needs_docker, resolve_projects};
pub use workspace::{
    boot_scope, boot_workspace, docker_available, teardown_scopes, teardown_workspace,
    COMPOSE_PROJECT,
};

pub mod prelude {
    pub use crate::{cherry_test, HttpRecord, TestEnv};
}

pub fn assert_greeting(name: &str, greet: impl Fn(&str) -> String) {
    let expected = format!("hello, {name}");
    assert_eq!(greet(name), expected);
}
