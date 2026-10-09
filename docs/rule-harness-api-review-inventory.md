# api-review — Phase 2 metadata inventory

Planning artifact for [rule-harness-rollout-plan.md](./rule-harness-rollout-plan.md). **Not runner input.**

**As of:** 2026-06-02 (batch 2 complete)

## Summary

| Check | Count | Notes |
|-------|------:|-------|
| `.mdc` on disk (`.cursor/rules/api-review/`) | 34 | Canonical rule ids (no `-rust-axum` suffix) |
| `rules.yaml` `api-review.include` entries | 34 | Deduped via `scripts/dedupe_rules_yaml_api_review.py` |
| Harness `*-test/test.yaml` | 34 | S3.6 AI fixtures; batch 1 + batch 2 migration scripts |
| `review.enforce: llm` on disk | 34 / 34 | Added for all rules with `appliesTo` |
| Skill status in tracker | **complete** | `harness-coverage.yaml` |

## Index drift (`rules.yaml` vs disk)

**Resolved:** `scripts/dedupe_rules_yaml_api_review.py` removed 34 `-rust-axum` alias lines from `rules.yaml` `api-review.include`. Index entries now match on-disk `.mdc` stems 1:1.

## Frontmatter audit (all 34 rules)

| Field | Status |
|-------|--------|
| `review.enforce` | `llm` on all 34 |
| `review.severity` | `major` on all 34 |
| `review.scope` | `diff` on all 34 |
| `review.appliesTo` | `globs` + `stacks` under `appliesTo` on all 34 |
| `alwaysApply` | `false` |

Top-level `globs: "**/*.rs"` and `stacks: [rust, axum]` remain on several rules for Cursor; harness catalog uses `review.appliesTo` when present.

## Classification (all 34 on-disk rules)

| Prefix | Count | Harness class |
|--------|------:|---------------|
| `api-*` | 8 | `valid-llm` |
| `err-*` | 5 | `valid-llm` |
| `sec-*` | 5 | `valid-llm` |
| `spec-*` | 6 | `valid-llm` |
| `ver-*` | 5 | `valid-llm` |
| `qual-*` | 5 | `valid-llm` |

No `valid-static` candidates in this skill (all need semantic API contract review).

## Migration scripts

| Script | Rules |
|--------|------:|
| `scripts/migrate_api_review_s36_batch1.py` | 10 |
| `scripts/migrate_api_review_s36_batch2.py` | 24 |
| `scripts/dedupe_rules_yaml_api_review.py` | index hygiene |

## Proof commands

```bash
python3 scripts/migrate_api_review_s36_batch1.py
python3 scripts/migrate_api_review_s36_batch2.py
python3 scripts/dedupe_rules_yaml_api_review.py
review-it rules harness --skill api-review
review-it rules harness
```

Full suite requires Cursor Agent + cache; CI uses `scripts/ci-rule-harness.sh`.
