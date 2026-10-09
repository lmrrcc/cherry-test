pub use cherry_test_core::{
    Defaults, ExternalRunner, PackageConfig, PackageRuntime, ProjectConfig, ScopeConfig,
    ServiceConfig, TestItConfig,
};

pub type Config = TestItConfig;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TestKind {
    Unit,
    Integration,
    Functional,
    Ui,
    Visual,
    Mutation,
}

impl TestKind {
    pub fn parse(s: &str) -> Option<Self> {
        match s.to_lowercase().as_str() {
            "unit" => Some(Self::Unit),
            "integration" => Some(Self::Integration),
            "functional" => Some(Self::Functional),
            "ui" => Some(Self::Ui),
            "visual" => Some(Self::Visual),
            "mutation" => Some(Self::Mutation),
            _ => None,
        }
    }

    pub fn as_str(self) -> &'static str {
        match self {
            Self::Unit => "unit",
            Self::Integration => "integration",
            Self::Functional => "functional",
            Self::Ui => "ui",
            Self::Visual => "visual",
            Self::Mutation => "mutation",
        }
    }
}

pub fn package_kinds(config: &TestItConfig, package_name: &str, project_id: &str) -> Vec<TestKind> {
    config
        .package_kinds(package_name, project_id)
        .iter()
        .filter_map(|k| TestKind::parse(k))
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_kind_parse() {
        assert_eq!(TestKind::parse("unit"), Some(TestKind::Unit));
        assert_eq!(TestKind::parse("UI"), Some(TestKind::Ui));
        assert_eq!(TestKind::parse("mutation"), Some(TestKind::Mutation));
        assert_eq!(TestKind::Mutation.as_str(), "mutation");
        assert!(TestKind::parse("unknown").is_none());
    }
}
