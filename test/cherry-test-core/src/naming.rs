use anyhow::{bail, Result};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TestId {
    pub project: String,
    pub suite: String,
    pub test: String,
    pub full_name: String,
}

pub fn parse_test_name(full_name: &str) -> Result<TestId> {
    const PROJECT: &str = "_project_";
    const SUITE: &str = "_suite_";
    const TEST: &str = "_test";

    if !full_name.ends_with(TEST) {
        bail!(
            "cherry-test name `{full_name}` must end with `_test`; expected \
             {{project}}_project_{{suite}}_suite_{{test}}_test"
        );
    }

    let stem = full_name.strip_suffix(TEST).unwrap();
    let Some(suite_idx) = stem.rfind(SUITE) else {
        bail!("{}", naming_error(full_name));
    };
    let (before_suite, test_part) = stem.split_at(suite_idx);
    let Some(test) = test_part.strip_prefix(SUITE) else {
        bail!("{}", naming_error(full_name));
    };

    let Some(project_idx) = before_suite.find(PROJECT) else {
        bail!("{}", naming_error(full_name));
    };
    let (project, suite_part) = before_suite.split_at(project_idx);
    let Some(suite) = suite_part.strip_prefix(PROJECT) else {
        bail!("{}", naming_error(full_name));
    };

    if project.is_empty() || suite.is_empty() || test.is_empty() {
        bail!("{}", naming_error(full_name));
    }

    Ok(TestId {
        project: project.to_string(),
        suite: suite.to_string(),
        test: test.to_string(),
        full_name: full_name.to_string(),
    })
}

fn naming_error(full_name: &str) -> String {
    format!(
        "cherry-test name `{full_name}` must match \
         {{project}}_project_{{suite}}_suite_{{test}}_test"
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_convention() {
        let id = parse_test_name("mini_project_wallet_suite_get_address_value_usdt_test").unwrap();
        assert_eq!(id.project, "mini");
        assert_eq!(id.suite, "wallet");
        assert_eq!(id.test, "get_address_value_usdt");
    }
}
