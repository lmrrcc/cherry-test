package com.cherrytest.common.config;

import com.cherrytest.common.rustcompat.AnyhowError;
import com.cherrytest.common.rustcompat.RustDebug;
import com.cherrytest.common.rustcompat.RustText;

public enum ReviewChangedScope {
    BRANCH("branch"),
    WORKTREE("worktree"),
    BRANCH_AND_WORKTREE("branch-and-worktree");

    private final String text;

    ReviewChangedScope(String text) {
        this.text = text;
    }

    public static ReviewChangedScope parse(String value) {
        return switch (RustText.toAsciiLowercase(RustText.trim(value))) {
            case "branch", "range", "diff-base", "diff_base" -> BRANCH;
            case "worktree", "local", "uncommitted" -> WORKTREE;
            case "branch-and-worktree", "branch_and_worktree", "both", "all" -> BRANCH_AND_WORKTREE;
            default ->
                throw new AnyhowError("invalid review_changed_scope "
                        + RustDebug.string(RustText.toAsciiLowercase(RustText.trim(value)))
                        + "; use branch, worktree, or branch-and-worktree");
        };
    }

    public String asStr() {
        return text;
    }
}
