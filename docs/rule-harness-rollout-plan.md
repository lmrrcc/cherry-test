# Rule harness — 100% coverage rollout plan

Planning document for reaching full harness coverage of the `.cursor` bundle. **This file is not runner input.** Discovery remains glob-only from `.cursor/rules/harness.yaml`.

## Executive summary

The rule harness (`review-it rules harness`) proves each review rule with checked-in fixtures: static detectors for narrow mechanical rules, and **live Cursor Agent review + semantic judge** for semantic rules. **Semantic coverage is AI-first:** expectations live in `test.yaml` (`expected_result`, evidence, `should_not_accept`); the judge evaluates actual agent output. The default full suite runs **static + LLM (agent + judge)** with **runtime cache** and must finish with **0 failed, 0 skipped**. Committed `goldens/*.matrix.json` and provenance sidecars are deprecated (S3.5); do not add new golden files.

**Current baseline (2026-05-31):**

| Metric | Value |
|--------|------:|
| `.mdc` rule files on disk | 937 |
| Skill sections in `rules.yaml` | 46 |
| `SKILL.md` files under `.cursor/skills` | 46 |
| Rule harness tests (`*-test/test.yaml`) | 15 |
| Rule coverage (unique rules with tests) | ~1.6% |
| `alwaysApply` rules covered | 14 / 14 (S0 complete) |
| Explicit `review.enforce: static` | 3 |
| Explicit `review.enforce: llm` | 14+ |
| Most rules | implicit / default LLM routing |

**S0 (2026-05-31):** All 14 `alwaysApply` rules have passing harness tests. Full suite: `review-it rules harness` → 15 fixtures (includes `reject-contextual-stubs`, which is not `alwaysApply`), 0 failed, 0 skipped.

**100% rule coverage** means every active `.cursor/rules/<skill>/<rule-id>.mdc` has either a **passing** `<rule-id>-test/test.yaml` or a **tracked** `blocked` / `deprecated` status with reason in `harness-coverage.yaml`.

**100% skill coverage** means every skill in `rules.yaml` has all included rules covered or blocked, on-disk `.mdc` files indexed or excluded, `SKILL.md` links consistent, and an optional `<skill>-test/` only when cross-rule workflow behavior must be proven.

**First milestone:** ~~100% `alwaysApply`~~ (done) + 100% `production-ready-code-standards` + 0 broken `appliesTo` in covered rules + default harness **0 skipped**.

**Final milestone:** 100% active rules covered or blocked, all skills complete, `review-it rules harness` passes from a clean checkout, coverage report shows no unclassified rules.

Tracking artifact: [`.cursor/rules/harness-coverage.yaml`](../.cursor/rules/harness-coverage.yaml) (planning only).

---

## Frozen contract (Phase 0)

Do not regress:

```text
.cursor/rules/harness.yaml
.cursor/rules/<skill>/<rule-id>-test/test.yaml
.cursor/rules/<skill>/<rule-id>-test/fixture.rs
.cursor/skills/<skill>/<skill>-test/test.yaml    # optional, workflow-only
```

**Forbidden (do not reintroduce):**

- `harness-rules.yaml` as runner input
- `.cursor/rule-harness/`
- `*.harness/`
- `case.yaml`
- Real fixture corpora inside `cherry-test` (synthetic temp-dir tests only)

**Proof command:**

```bash
review-it rules harness
# or: cargo run -p review-it -- rules harness
```

Expected: `N fixture(s), N passed, 0 failed, 0 skipped`, exit `0`.

**PR governance (hard rule):**

Any PR that adds or changes a rule with `review.enforce: static` or `review.enforce: llm` must add or update the matching `<rule-id>-test/test.yaml` in the same PR (unless the rule is explicitly marked `advisory` / `blocked` in `harness-coverage.yaml` with reason).

---

## Phase 1 — Inventory everything

### Methodology

1. **Glob discovery (runner):** `harness.yaml` → `*/*-test/test.yaml` (optional `../skills/*/*-test/test.yaml`).
2. **On-disk rules:** `find .cursor/rules -name '*.mdc'`.
3. **Index:** parse `.cursor/rules/rules.yaml` `rules:` sections (`include` / `exclude` / `override`).
4. **Frontmatter audit:** per `.mdc` — `alwaysApply`, `review.enforce`, `appliesTo`, `globs`, stacks, `appliesTo_valid`.
5. **Cross-checks:**
   - rules in `include` without `.mdc`
   - `.mdc` on disk not in any skill `include` (or only in virtual skills like `rust-api-change`)
   - `SKILL.md` links vs `include` lists

### Planning tracker (not runner input)

Maintain [`.cursor/rules/harness-coverage.yaml`](../.cursor/rules/harness-coverage.yaml):

- `schema_version`, `summary`, `milestones`
- Per-rule detail for P1 / P2 skills
- Per-skill rollups for long tail
- `detectors` mapping (Phase 5)

### Four inventory reports (generate each sprint)

| Report | Contents |
|--------|----------|
| 1. Rules by skill | All `.mdc` grouped by directory / `rules.yaml` skill |
| 2. Missing tests | Active rules with `has_test: false` and not `blocked` |
| 3. Broken frontmatter | Malformed `appliesTo`, missing globs/stacks, unknown static detector |
| 4. Index drift | In `rules.yaml` but no file; file exists but not indexed |

Suggested generator (future tooling): small script reading `rules.yaml` + walking `.mdc` + globbing `*-test/` — output markdown or JSON; **must not** be wired into the harness runner.

### Per-rule inventory fields

```text
skill, rule_id, mdc_path
listed_in_rules_yaml, linked_from_skill_md
alwaysApply, review.enforce, globs, stacks
appliesTo_valid, has_test, test_path
has_bad_fixture, has_good_fixture, has_golden
status: none|planned|static|llm-golden|blocked|deprecated
blocked_reason, owner, priority
```

---

## Phase 2 — Metadata hygiene

Before mass golden authoring, classify every active rule:

| Bucket | Action |
|--------|--------|
| `valid-static` | `review.enforce: static` + known detector id in test |
| `valid-llm` | `review.enforce: llm` + valid `appliesTo` / globs / stacks |
| `valid-advisory` | applicability-only or `blocked` with reason |
| `implicit-llm-needs-decision` | normalize to `llm` or mark `blocked` |
| `broken-appliesTo` | fix YAML (`globs` nested under `appliesTo`) |
| `missing-globs` / `missing-stack` | fix or block |
| `duplicate-rule-id` | dedupe or block one copy |
| `indexed-but-missing-file` | fix index or remove stem |
| `file-but-not-indexed` | add to skill `include` or document exclusion |

**Policy:**

- **Static:** `review.enforce: static` → must map to a registered static detector.
- **LLM:** `review.enforce: llm` → must form valid review pairs (fix `appliesTo` first).
- **Implicit:** normalize to `llm` or `blocked` / `advisory`.
- **Advisory:** harness optional; track as `blocked` with `advisory-only` if skipped intentionally.

**Exit:** every active rule has exactly one classification; no unclassified rules in tracker.

---

## Phase 3 — P1: alwaysApply rules (14)

| Rule ID | Harness status |
|---------|----------------|
| `reject-todo-fixme-hack-in-production` | **covered** (static) |
| `log-no-console-in-services-backend-rust-tracing` | **covered** (static) |
| `reject-placeholder-and-not-implemented` | **covered** (LLM golden) |
| `reject-fake-or-simulated-data` | **covered** (LLM golden) |
| `reject-fail-soft-and-silent-swallow` | **covered** (LLM golden) |
| `reject-contextual-stubs` | **covered** (LLM golden; not alwaysApply) |
| `enforce-require-compilation-tests-and-readiness-evidence-backend` | **covered** (LLM golden) |
| `anti-no-layer-bypassing-backend` | **covered** (LLM golden) |
| `review-block-listed-critical-vulnerabilities-backend` | **covered** (LLM golden) |
| `input-validate-and-sanitize-all-user-input-backend` | **covered** (LLM golden) |
| `data-ban-hardcoded-secrets-and-credentials-backend` | **covered** (static, `no-hardcoded-secrets`) |
| `ex-do-not-swallow-exceptions-backend-rust-tracing` | **covered** (LLM golden) |
| `req-implement-just-in-time` | **covered** (LLM golden) |
| `dead-remove-unused-code-instead-of-commenting` | **covered** (LLM golden) |
| `nsc-def-principled-default` | **covered** (LLM golden) |

**Exit (met 2026-05-31):** all 14 `alwaysApply: true` rules covered; full suite **0 skipped**.

---

## Phase 4 — production-ready-code-standards (19 on disk)

Work order:

1. Finish remaining `reject-*` (`reject-temporary-workaround-comments`)
2. `sem-*` semantic completeness (LLM golden, bad-only first)
3. `rev-*` review checklist rules
4. `defer-*` technical-debt tracking
5. `accept-*` acceptance rules

**Exit (met 2026-05-31):** **19/19** covered. **S3.6 migration (2026-05-31):** all LLM fixtures use live Cursor Agent + semantic judge (`expected_result` in `test.yaml`); no committed `goldens/*.matrix.json`; cache-only rerun verified with `--max-ai-calls 0`.

---

## Phase 5 — Static / detector expansion

Registered detectors (subset; see `static_scan.rs`):

| Detector ID | Candidate rules |
|-------------|-----------------|
| `reject-todo-fixme-hack-in-production` | (mapped) |
| `log-no-console-in-services-backend-rust-tracing` | (mapped) |
| `no-hardcoded-secrets` / `scf-val-no-secrets-backend-rust` | `data-ban-hardcoded-secrets-and-credentials-backend` |
| `rust-no-panic` / `code-no-todo-fixme-in-production-backend-rust` | `reject-placeholder-and-not-implemented` |
| `rust-no-unwrap` / `rust-no-expect` | various Rust production rules |
| `typescript-no-only-tests` | frontend test discipline rules |
| `no-merge-conflict-markers` | CI hygiene rules |

**Decision per rule:** (A) set `review.enforce: static`, (B) keep LLM, (C) add detector only if multi-rule value.

**Exit:** every static detector mapped to ≥1 rule or marked utility-only; every static-enforced rule has passing static fixture.

---

## Phase 6 — High-traffic skills (P4–P5)

Rollout order after P1–P2:

| Priority | Skill | Rules on disk (approx.) | Notes |
|----------|-------|------------------------:|-------|
| P4 | `security-review` | 15 | **complete (S2 + S3.6)** — 15/15; live Cursor Agent + judge; cache verified |
| P4 | `api-review` | 34 | mostly LLM; critical API contract |
| P4 | `logging-standards` | 26 | **complete (S3)** — 26/26 covered |
| P5 | `hexagonal-architecture` | 19 | |
| P5 | `clean-architecture` | 30 | |
| P5 | `domain-driven-design` | 12 | |
| P5 | `test-driven-development` | 30 | |
| P5 | `frontend-review` | 15 | TS fixtures |
| P5 | `openapi-documentation` | 73 | large; batch by prefix |

**Batching:** 10–20 rules per PR; one skill per PR when small; one category per PR when large (`api-`, `sec-`, `err-`).

**Per PR:**

```bash
review-it rules harness
review-it rules harness --skill <skill>
```

**Exit per skill:** 100% of active rule IDs covered or blocked; no skipped fixtures in default full-suite run.

---

## Phase 7 — Long-tail skills

Process remaining `rules.yaml` skills in tier order (see `harness-coverage.yaml` `skills.*.priority`).

Flow per skill:

1. Generate skill inventory
2. Fix frontmatter
3. Add tests in batches of 10–20
4. Run full suite
5. Update tracker
6. Mark skill `complete`

**Fixture patterns:**

| Type | Layout |
|------|--------|
| Static | `test.yaml` + `fixture.rs` |
| LLM | `test.yaml` + `fixture.rs` + `goldens/<id>.matrix.json` |
| Frontend | `fixture.ts` / `fixture.tsx` + golden |
| Multi-file | `fixtures/<case>/` only when single file insufficient |

**Virtual skills** (`rust-api-change`, `standard-backend`, etc.): rules live under other directories; coverage is still per **physical** `.mdc` path — virtual skills complete when all **referenced** rule IDs are covered.

---

## Phase 8 — Skill-level tests (sparse)

Add `.cursor/skills/<skill>/<skill>-test/test.yaml` only for cross-rule workflows:

| Skill | Rationale |
|-------|-----------|
| `api-review` | route-level API + security + error together |
| `security-review` | validation + secrets + block-list |
| `hexagonal-architecture` | multi-file layer boundaries |
| `test-driven-development` | code + test file pairing |
| `review-output-format` | report structure, not single code rule |

**Do not** require one skill test per skill by default.

---

## Phase 9 — CI and governance

| Level | Command / action |
|-------|------------------|
| **PR required** | `review-it rules harness` |
| **PR optional** | `--skill <changed>`, `--rule <changed>` |
| **Nightly** | full harness + sample `review-it` on real repos + coverage report |
| **Manual only** | live LLM checks (if ever added) |

**Contributor rules:**

- **New rule:** must ship `<rule-id>-test/test.yaml` unless `advisory` / `blocked` in tracker.
- **Changed rule:** update fixture / golden when behavior changes.
- **Changed detector:** run all static-backed rule tests.
- **Changed prompt/schema:** run all LLM golden tests.
- **Changed `SKILL.md`:** fix links; update skill-level test if workflow changed.

---

## Priority tiers (summary)

| Tier | Scope |
|------|--------|
| **P0** | Maintain existing 6 tests; frozen contract; default 0 skipped |
| **P1** | All `alwaysApply` rules (14) |
| **P2** | `production-ready-code-standards` (19 on disk) |
| **P3** | Static / detector-backed expansion |
| **P4** | `security-review`, `api-review`, `logging-standards` |
| **P5** | Architecture, domain, testing, frontend, OpenAPI |
| **P6** | Long-tail skills (~46 total) |
| **P7** | Optional skill-level workflow tests |

---

## Per-skill rollout order (46 skills)

Order balances alwaysApply overlap, business risk, and existing tests:

1. ~~`production-ready-code-standards` (P2)~~ complete — S3.6 migrated (19/19)
2. ~~`logging-standards` (P4)~~ complete (S3)
3. ~~`production-readiness-enhancements-summary` (P1 overlap)~~ complete — **26/26** S3.6 (S12 batches 1–3)
4. ~~`security-review` (P4)~~ complete — S3.6 migrated (15/15)
5. ~~`no-shortcuts-or-temporary-solutions` (P1 overlap)~~ complete — **8/8** S3.6 (S13)
6. `hexagonal-architecture` (P5)
7. ~~`yagni-principle` (P1 overlap)~~ complete — **23/23** S3.6 (S14 batches 1–2)
8. ~~`unused-code-removal` (P1 overlap)~~ complete — **34/34** S3.6 (S15 batches 1–3)
9. ~~`core-review-methodology`~~ complete — **8/8** S3.6 (S16)
10. ~~`issue-classification`~~ complete — **9/9** S3.6 (S17)
11. `review-output-format` — **15/15** S3.6 complete (S20)
12. `review-efficiency` — **28/28** S3.6 complete (S21)
13. `review-documentation` — **34/34** S3.6 complete (S22); **review cluster done**
14. ~~`deferred-items-tracking`~~ complete — **11/11** S3.6 (S18)
15. ~~`change-size-guidelines`~~ complete — **8/8** S3.6 (S19)
16. `production-readiness-checklist` — **8/8** S3.6 complete (S23)
17. `api-review` (P4, large)
18. `integration-verification` — **36/36** S3.6 complete (S24)
19. `architectural-completeness` — **33/33** S3.6 complete (S25)
20. `clean-architecture`
21. `domain-driven-design`
22. `domain-model-naming`
23. `hexagonal-naming-conventions` — **11/11** S3.6 complete (S32)
24. `test-driven-development`
25. `behavior-driven-development` — **37/37** S3.6 complete (S26)
26. `end-to-end-testing` — **36/36** S3.6 complete (S29)
27. `refactoring-methodology` — **37/37** S3.6 complete (S28)
28. `design-methodology` — **37/37** S3.6 complete (S27)
29. `configuration-management`
30. `deployment-platform-secrets-policy`
31. `vault-mandatory`
32. `external-integration`
33. `feign-declarative-clients`
34. `openapi-documentation` (large batches)
35. `frontend-review`
36. `frontend-feature`
37. `vue-feature`
38. `javadoc-avoidance`
39. `single-line-documentation`
40. `naming-conventions` — **14/14** S3.6 complete (S33)
41. `no-boolean-api-parameters`
42. `no-direct-database-modifications`
43. `no-shell-scripts`
44. `dijkstra-formal-verification` — **32/32** S3.6 complete (S31)
45. `network-health-validation`
46. `quick-review`
47. Virtual / stack skills: `standard-backend`, `rust-api-change`, `rust-architecture-change`, `rust-domain-change`, `rust-security-change`, `docs-contract-change` — close when all referenced stems are covered

---

## Rule test templates

### Static rule

```yaml
schema_version: 1
skill: <skill>
rule_id: <rule-id>
fixtures:
  - id: bad_<case>
    kind: bad
    file: fixture.rs
    target_path: src/lib.rs
    expect:
      applicability:
        applies: true
        backend: static
      review:
        status: fail
        findings_min: 1
        locations:
          - line_contains: "<needle>"
```

`test.yaml` should set `expect.review.static.detector` when multiple detectors could match.

### LLM golden rule

```yaml
fixtures:
  - id: bad_<case>
    kind: bad
    file: fixture.rs
    target_path: src/lib.rs
    expect:
      applicability:
        applies: true
        backend: llm
      review:
        status: fail
        findings_min: 1
      llm:
        golden: goldens/bad_<case>.matrix.json
```

Golden: `pair_id: p0001`, `status: fail` for bad fixtures, validated against agent matrix schema.

### Implicit LLM rule

Same as LLM golden; add normalization PR to set `review.enforce: llm` on `.mdc` when ready.

### Advisory rule

```yaml
# harness-coverage.yaml
status: blocked
blocked_reason: advisory-only
```

Optional applicability-only test proving rule does not fire on out-of-scope files.

### Frontend (TS/TSX)

Replace `fixture.rs` with `fixture.ts` / `fixture.tsx`; keep golden matrix; use `appliesTo` for `typescript` / `frontend` stacks.

### Multi-file / mini-project

```text
<rule-id>-test/
  test.yaml
  fixtures/<case>/
    src/...
    package.json   # only when needed
  goldens/<case>.matrix.json
```

Use only when a single file cannot express the violation.

---

## Definition of done

### One rule

- Valid `.mdc` frontmatter
- `<rule-id>-test/test.yaml` with `schema_version`, `skill`, `rule_id`, fixtures, applicability + review expectations
- At least one `kind: bad` fixture
- Static → passes detector; LLM → passes committed golden
- Default harness runs test with **0 skipped**
- High false-positive risk → add `kind: good` non-regression fixture

### One skill

- Every active `include` rule covered or blocked
- Every on-disk `.mdc` indexed or intentionally excluded
- Every `SKILL.md` rule link resolves
- Optional `<skill>-test/` only for workflow behavior
- `review-it rules harness --skill <skill>` → 0 skipped

### Whole bundle

- 100% active rules covered or blocked
- All skills `complete` in tracker
- `review-it rules harness` passes from clean checkout
- Coverage report: no `status: none` without owner/plan

---

## Tracking metrics

Report from `harness-coverage.yaml` summary (updated each milestone):

```text
total_rules, covered_rules, blocked_rules, coverage_percent
static_rules_total, static_rules_covered
llm_rules_total, llm_rules_covered
implicit_rules_total, implicit_rules_normalized
alwaysApply_total, alwaysApply_covered
skills_total, skills_100_percent_complete
rules_with_good_fixture, rules_bad_only
broken_frontmatter_count, missing_golden_count
```

---

## Risks and mitigations

| Risk | Mitigation |
|------|------------|
| Mass golden authoring drift | Bad-only first; good fixtures only for high FP risk |
| Broken `appliesTo` blocks LLM pairs | Phase 2 gate before Phase 4+ |
| Golden left at `status: pass` after debugging | CI full suite; code review checklist |
| Static detector too noisy | Keep as LLM; narrow detector patterns |
| 937 rules × authoring cost | 10–20 rules/PR; tracker ownership per skill |
| Virtual skills (`rust-*`) confuse coverage | Cover physical `.mdc`; virtual skill = rollup complete when refs done |
| Tracker becomes runner input | Document + lint: harness must not read `harness-coverage.yaml` |
| Implicit rules never normalized | Bucket `implicit-llm-needs-decision` must hit zero before final milestone |

---

## Batch execution plan (PR-sized)

| Sprint | Target | Rules (approx.) |
|--------|--------|----------------:|
| S0 | P0 freeze + tracker + 8 remaining alwaysApply | 8 |
| S1 | ~~Finish `production-ready-code-standards`~~ | done — 19/19 S3.6 |
| S2 | Static mapping + `data-ban-hardcoded-secrets` + logging | 15–20 |
| S3 | ~~`logging-standards` full skill~~ | done — 26/26 |
| S3.5 | Live Cursor harness + provenance + AI-first statuses | runner + docs; re-baseline goldens per skill |
| S4–S6 | ~~`api-review` in 3 batches~~ | done — 34/34 S3.6 |
| S7 | ~~`hexagonal-architecture` batches 1–2~~ | done — 19/19 S3.6 (dry-run green) |
| S8 | ~~`clean-architecture` batches 1–3~~ | done — 30/30 S3.6 (dry-run green) |
| S9 | ~~`domain-driven-design`~~ | done — 12/12 S3.6 (dry-run green) |
| S10 | ~~`test-driven-development` batches 1–2~~ | done — 30/30 S3.6 (15 rust-rstest + 15 java-junit5) |
| S11 | ~~`frontend-review`~~ | done — 15/15 S3.6 (dry-run green) |
| S12 | `production-readiness-enhancements-summary` batch 1 | done — 10/26 (+ enforce-require S0 → 11/26 dry-run green) |
| S12b | `production-readiness-enhancements-summary` batch 2 | done — 10/26 (21/26 total dry-run green) |
| S12c | ~~`production-readiness-enhancements-summary` batch 3~~ | done — 5/26 (26/26 skill complete) |
| S13 | ~~`no-shortcuts-or-temporary-solutions`~~ | done — 8/8 S3.6 (dry-run green) |
| S14 | ~~`yagni-principle` batches 1–2~~ | done — 23/23 S3.6 (dry-run green) |
| S15 | `unused-code-removal` batch 1 | done — 10/34 (11/34 with S0 dead-remove; dry-run green) |
| S15b | `unused-code-removal` batch 2 | done — 10/34 (21/34 total) |
| S15c | ~~`unused-code-removal` batch 3~~ | done — 13/34 (34/34 skill complete) |
| S16 | ~~`core-review-methodology`~~ | done — 8/8 S3.6 (dry-run green) |
| S17 | ~~`issue-classification`~~ | done — 9/9 S3.6 (dry-run green) |
| S18 | ~~`deferred-items-tracking`~~ | done — 11/11 S3.6 (dry-run green) |
| S19 | ~~`change-size-guidelines`~~ | done — 8/8 S3.6 (dry-run green) |
| S20 | `review-output-format` | done — 15/15 (dry-run green) |
| S21 | `review-efficiency` | done — 28/28 (dry-run green) |
| S22 | `review-documentation` | done — 34/34 (dry-run green); review cluster complete |
| S23 | `production-readiness-checklist` | done — 8/8 (dry-run green) |
| S24 | `integration-verification` | done — 36/36 (dry-run green) |
| S25 | `architectural-completeness` | done — 33/33 (dry-run green); **>50% bundle coverage** |
| S26 | `behavior-driven-development` | done — 37/37 (dry-run green) |
| S27 | `design-methodology` | done — 37/37 (dry-run green) |
| S28 | `refactoring-methodology` | done — 37/37 (dry-run green) |
| S29 | `end-to-end-testing` | done — 36/36 (dry-run green; `*-frontend-backend` stack routing) |
| S30 | `todo-management` | done — 32/32 (dry-run green) |
| S31 | `dijkstra-formal-verification` | done — 32/32 (dry-run green; rust + kotlin fixtures) |
| S32 | `hexagonal-naming-conventions` | done — 11/11 (dry-run green) |
| S33 | `naming-conventions` | done — 14/14 (dry-run green; rust + kotlin) |
| S34 | `vault-mandatory` | done — 18/18 (dry-run green; rust + spring; expanded appliesTo globs) |
| S35 | `single-line-documentation` | done — 18/18 (dry-run green; rust + kotlin) |
| S36 | `no-boolean-api-parameters` | done — 20/20 (dry-run green; rust/axum) |
| S37 | `domain-model-naming` | done — 21/21 (dry-run green; rust + kotlin/jpa) |
| S38 | `javadoc-avoidance` | done — 70/70 (dry-run green; 7 batches via `migrate_javadoc_avoidance_s36.py`) |
| S39 | `openapi-documentation` | done — 73/73 (dry-run green; 8 batches via `migrate_openapi_documentation_s36.py`) |
| S40 | Virtual / zero-disk skills | done — rollup complete; all 937 on-disk `.mdc` harness-covered |
| S∞ | Maintenance | New rules require `<rule-id>-test` in same PR per governance |

Each PR: `review-it rules harness` green, update `harness-coverage.yaml`, no new skipped tests in default mode.

---

## Related docs

- [rule-harness.md](./rule-harness.md) — runner contract, CLI, fixture levels
- [`.cursor/rules/harness.yaml`](../.cursor/rules/harness.yaml) — glob discovery
- [`.cursor/rules/harness-coverage.yaml`](../.cursor/rules/harness-coverage.yaml) — planning tracker
