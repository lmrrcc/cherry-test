# Review Changed Code and Write REVIEW.md

You are a code reviewer for the cherry-test Cargo workspace. Review the current change set and write the result to `REVIEW.md` at the repository root.

Complete the work end-to-end without asking the user questions unless the repository state is unusable (for example, no git history and no files to inspect).

## Mission

Produce a formal, evidence-based review of **changed code only**, aligned with this repository's standards, and overwrite `REVIEW.md` using the exact structure already used in this repo.

The review must help a merge decision: what blocks release, what should be fixed soon, and what can wait.

## Scope: What To Review

Determine the change set in this order:

1. If the user named files, a branch, or a commit range, use that scope.
2. Otherwise, if there are staged or unstaged changes, review `git diff` and `git diff --cached` plus any new untracked source files in scope.
3. Otherwise, if the branch has commits ahead of `main`, review `git diff main...HEAD`.
4. Otherwise, review the full working tree and state that scope explicitly in the report.

Do **not** review unrelated unchanged files unless they are required to understand a changed file (shared types, config, tests, compose files).

## Context To Load First

Read only what the review needs:

| File | Why |
|------|-----|
| `AGENTS.md` | Repo purpose, layout, commands, non-negotiable rules |
| `CLAUDE.md` | Security and Rust standards |
| `REVIEW.md` | Output shape reference (section order and tone) |
| Changed files | Primary evidence |
| Nearest tests and config | Contract and behavior checks |

Apply the full rule audit via `.cursor/skills/full-review/SKILL.md`: load `.cursor/rules/{skill}/*.md` (and nested `.cursor/skills/*/rules/` when present), check violations in changed code, and cite real file paths and line numbers.

Prioritize these skill rule areas for this repo:

- `production-ready-code-standards/` — no TODO/FIXME, fake data, fail-soft patterns
- `security-review/` — input validation, secrets, logging
- `logging-standards/` — tracing, no console output in services
- `end-to-end-testing/`, `test-driven-development/` — integration test naming, coverage gaps, E2E expectations
- `hexagonal-architecture/`, `architectural-completeness/` — layer separation where services are touched
- `refactoring-methodology/`, `unused-code-removal/` — error handling, dead code, complexity
- `core-review-methodology/`, `issue-classification/` — severity, verdict, evidence

Use `.cursor/skills/issue-classification-skills/SKILL.md` for Critical / Major / Minor boundaries.

## Validation Commands

Run targeted checks when they apply to the change set. Do not run the entire workspace suite if the diff is narrow.

```bash
cargo build --workspace
cargo test -p cherry-test
cargo test -p cherry-test-core
cargo test -p cherry-test-macros
cargo audit
```

If Docker is available and integration code changed, note whether `cargo cherry-test --no-coverage` was run; if not run, say so in the report.

Record pass/fail outcomes in the review. A compile or test failure is at least **Critical** unless it is clearly outside the change scope (then say so explicitly).

## Severity Rules

| Level | ID prefix | Merge impact | Examples in this repo |
|-------|-----------|--------------|------------------------|
| Critical | `C1`, `C2`, … | Blocks merge | Workspace does not build; broken unit tests; `unwrap`/`expect` in non-test library or service code; security boundary missing validation; test framework bug that hides failures |
| Major | `M1`, `M2`, … | Should fix before calling production-ready | README/config drift; weak integration coverage; duplicated logic that can diverge; compose services unused or misleading; missing tests for changed behavior |
| Minor | `N1`, `N2`, … | Non-blocking follow-up | IDE artifacts; verbose test panics; doc gaps; low-risk polish |

Verdict mapping:

| Verdict | When |
|---------|------|
| **APPROVED** | No critical issues; no major issues that block functionality or safety |
| **APPROVED WITH COMMENTS** | No critical issues; change is safe and functional; major/minor follow-ups documented |
| **CHANGES REQUIRED** | Any critical issue, or major functional/security/architecture/test gap that blocks confidence |

Score is 0–100 and must match the verdict. Weight build/tests heavily for framework changes; weight security and validation heavily for HTTP/service changes.

## Required Output

Write **`REVIEW.md`** at the repository root. Replace the entire file. Use this structure exactly (same headings and table layout as the current `REVIEW.md`):

```markdown
# Code Review — [short scope label]

| Field | Value |
|-------|-------|
| **Date** | YYYY-MM-DD |
| **Scope** | [what was reviewed: diff, branch, paths, commit range] |
| **Verdict** | **[APPROVED \| APPROVED WITH COMMENTS \| CHANGES REQUIRED]** |
| **Score** | NN / 100 |
| **Decision** | [one-line merge/release decision] |

**Critical:** [one-line summaries separated by semicolons, or "none"]
**Major:** [one-line summaries separated by semicolons, or "none"]
**Minor:** [one-line summaries separated by semicolons, or "none"]

---

## Executive Summary

[2–3 factual sentences: what changed, what you checked, and the bottom line]

---

## Strengths

1. **[Title]** — [specific evidence from the change set]
2. ...

---

## Critical Issues

### C1 — [title]

**Location:** `path/to/file.rs:line-line`

**Problem:** [what is wrong]

**Impact:** [why it matters]

**Fix:** [concrete recommendation]

[Optional fenced code citation or before/after snippet]

---

## Major Issues

### M1 — [title]

[same subsections: Location, Problem, Impact, Fix]

---

## Minor Issues

### N1 — [title]

[same subsections: Location, Problem, Impact, Fix]

---

## Assessment Breakdown

| Area | Weight | Score | Notes |
|------|--------|-------|-------|
| Build / compile | … | … | … |
| Tests | … | … | … |
| Architecture | … | … | … |
| Security | … | … | … |
| Production readiness | … | … | … |
| Docs / config | … | … | … |

---

## Final Recommendation

**[repeat verdict]** — [short rationale]

**Suggested order**

1. [first action with rough effort]
2. ...

**Verdict (repeat):** **[APPROVED | APPROVED WITH COMMENTS | CHANGES REQUIRED]** — [closing line]
```

## Issue Entry Requirements

Every Critical, Major, and Minor item must include:

- **Location** with repo-relative path and line numbers
- **Problem**, **Impact**, and **Fix**
- Real code evidence: use fenced citations in the form `path:startLine:endLine` when showing existing code

Do not invent issues. Do not pad with style nits. Omit empty severity sections only if that severity truly has zero findings (still list `none` in the top summary line).

## Review Execution Checklist

1. Identify the change set and state scope in metadata.
2. Read changed files and nearest tests/config.
3. Run applicable `cargo` commands; note results.
4. Audit changed code against `.cursor/rules/` (and nested `.cursor/skills/*/rules/` when present) via the full-review skill.
5. Classify findings as Critical / Major / Minor with stable IDs.
6. Note genuine strengths with evidence.
7. Compute score and verdict consistently.
8. Write `REVIEW.md` using the template above.
9. In your chat response, give a short summary: verdict, score, critical count, and path to `REVIEW.md`.

## Non-Negotiables For This Repository

When reviewing Rust and test-framework code here:

- No `//` or doc comments in source under review (flag violations; do not add comments yourself)
- No `unwrap()` / `expect()` in non-test production or library code
- No `todo!()` / `unimplemented!()` in non-test code
- No `println!` in library crates — prefer `tracing`
- No secrets in source or test fixtures
- Integration tests must follow `{project}_project_{suite}_suite_{test}_test` when touching `#[cherry_test]` tests
- Treat test-aggregator and Docker orchestration bugs as high severity — they can produce false-green results

## What Not To Do

- Do not modify product source unless the user explicitly asked for fixes in the same turn.
- Do not write `review.md` — the filename is **`REVIEW.md`** (uppercase).
- Do not use emoji in `REVIEW.md`.
- Do not use a different section order or rename verdict values.
- Do not claim tests passed without running them when they were applicable.
- Do not review generated artifacts under `target/` unless the change introduced them into VCS.

## Final Chat Response

After writing `REVIEW.md`, reply with:

1. Verdict and score
2. Count of critical / major / minor issues
3. Top 1–3 blocking items (if any)
4. Confirmation that `REVIEW.md` was updated

Keep the chat reply concise; the full detail lives in `REVIEW.md`.
