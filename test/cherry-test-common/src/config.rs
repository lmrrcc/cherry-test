use anyhow::{bail, Result};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum ReviewChangedScope {
    #[default]
    Branch,
    Worktree,
    BranchAndWorktree,
}

impl ReviewChangedScope {
    pub fn parse(value: &str) -> Result<Self> {
        match value.trim().to_ascii_lowercase().as_str() {
            "branch" | "range" | "diff-base" | "diff_base" => Ok(Self::Branch),
            "worktree" | "local" | "uncommitted" => Ok(Self::Worktree),
            "branch-and-worktree" | "branch_and_worktree" | "both" | "all" => {
                Ok(Self::BranchAndWorktree)
            }
            other => bail!(
                "invalid review_changed_scope {other:?}; use branch, worktree, or branch-and-worktree"
            ),
        }
    }

    pub fn as_str(self) -> &'static str {
        match self {
            Self::Branch => "branch",
            Self::Worktree => "worktree",
            Self::BranchAndWorktree => "branch-and-worktree",
        }
    }
}
