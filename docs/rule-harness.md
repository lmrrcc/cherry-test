# Rule harness (RTDD)

The rule harness proves applicability, detection, and non-regression for `.cursor` review rules using checked-in fixtures instead of git diffs.

## Ownership split

| Owner | Responsibility |
|-------|----------------|
| **cherry-test / review-it** (`cherry-test-review`) | Harness runner, CLI (`review-it rules harness`), manifest parsing, discovery, static/LLM execution, semantic judge, runtime cache, reports, framework tests (temp dirs + synthetic rules only) |
| **`.cursor` bundle** (e.g. `lmrrcc/.cursor`) | Rule bodies, skill docs, `harness.yaml` under `.cursor/rules/`, per-rule `<rule-id>-test/` beside `.mdc` files, per-skill `<skill>-test/` beside `SKILL.md`, fixtures |

Rule and skill **tests** live beside the content they validate. There is no separate harness corpus tree (for example `.cursor/rule-harness/`).

The cherry-test monorepo must **not** check in real bundle fixture corpora.

**100% coverage rollout:** planning tracker [`.cursor/rules/harness-coverage.yaml`](../.cursor/rules/harness-coverage.yaml) (not runner input) and phased program [rule-harness-rollout-plan.md](./rule-harness-rollout-plan.md). **S0 complete:** all 14 `alwaysApply` rules have harness tests. **S1–S3:** production-ready-code-standards, security-review, and logging-standards skills covered in the bundle. **S3.6:** LLM rules run through the **same review pipeline as project review** (Cursor Agent + semantic judge); expectations live in `test.yaml`, not committed golden matrices.

**PR merge gate:** run `review-it rules harness` with no filters; partial modes (`--static-only`, `--llm-mode skip`, `--skill`, `--rule`) are for authoring only.

## AI-first coverage policy (S3.6)

Semantic rules are validated by exercising **Cursor Agent** on staged fixtures and judging output against `test.yaml`. Committed `goldens/*.matrix.json` and provenance sidecars are **not** part of the canonical contract.

| Tracker status | Meaning |
|----------------|---------|
| `static-covered` | Narrow mechanical rule with a deterministic detector (TODO/FIXME, `println!`, hardcoded secret pattern). |
| `ai-live-covered` | Harness runs Cursor Agent + judge for the rule; expectations in `test.yaml`. |
| `ai-judge-covered` | Same as `ai-live-covered`; judge block present and passing in CI or manual full run. |
| `blocked` | Cannot be covered yet; concrete reason required. |

Deprecated tracker values (do not use for new rules): `llm-golden`, `ai-golden-covered`, `ai-golden-needs-provenance`.

**Authoring workflow for LLM fixtures:**

1. Add `<rule-id>-test/test.yaml` + `fixture.rs` (or `.java`) with `expect.review.expected_result`, `evidence`, and `expect.judge`.
2. Run `review-it rules harness --rule <rule-id>` (uses cache after the first successful run).
3. Tune `expected_result` / `should_not_accept` until the judge passes.
4. Commit `test.yaml` and fixture only (no golden matrix files).

## Naming convention

| Kind | Directory | Manifest |
|------|-----------|----------|
| Rule test | `.cursor/rules/<skill>/<rule-id>-test/` | `test.yaml` |
| Skill test | `.cursor/skills/<skill>/<skill>-test/` | `test.yaml` |

`test.yaml` is authoritative for `rule_id`, `skill`, fixtures, and expectations. The runner does not infer rule IDs from directory or file names.

**Good fixtures are optional.** A single bad fixture is valid. The harness does not require a `kind: good` fixture and does not print a “no good fixture” note in normal output.

## Default full-suite run

From the bundle or repo root (where `.cursor/rules/harness.yaml` exists):

```bash
review-it rules harness
```

or from source:

```bash
cargo run -p review-it -- rules harness
```

This is the normal command. It:

- discovers all `*/*-test/test.yaml` files under `.cursor/rules`
- runs **static** fixtures with static detectors
- runs **LLM** fixtures through the **normal review application path** (Cursor Agent on the staged file/rule pair)
- runs an **LLM judge** against `expect.review.expected_result`, evidence markers, and `should_not_accept`
- uses a **runtime cache** under `target/rule-harness/cache/` (review agent + judge) unless `--no-cache` or `--refresh-ai`
- **skips nothing** unless you use an explicit partial mode (below)

A successful full-suite run reports `0 failed` and `0 skipped`. Configuration errors exit `2`; expectation or judge failures exit `1`.

Default resolution when flags are omitted:

- **bundle root:** current directory
- **harness root:** `.cursor/rules`
- **provider:** `cursor`
- **model / judge_model:** `composer-2.5-standard` (Composer 2.5 normal/standard, not fast or max)

Explicit paths remain supported:

```bash
review-it rules harness --bundle . --harness .cursor/rules
```

## Execution model (LLM fixtures)

For each LLM fixture the harness:

1. Stages the fixture into an isolated temporary workspace.
2. Loads project-owned rules from the bundle (same catalog as project review).
3. Narrows applicability to the `rule_id` under test.
4. Asserts `expect.selection` (or legacy `expect.applicability`).
5. Calls the same pair-level review path as production review (`run_harness_pair_agent_review` / Cursor CLI).
6. Asserts structural review expectations (`status`, `findings_min` / `findings_max`, evidence markers).
7. Calls the semantic judge with rule text, fixture source, `expected_result`, and actual agent matrix output.
8. Passes only when the judge returns `verdict: pass` with confidence `high` or `medium`.

There is **no** committed golden matrix as the source of truth. Legacy `expect.llm.golden` fails with a migration message.

## Execution modes

| Mode | CLI | Behavior |
|------|-----|----------|
| **Full suite (default)** | *(no extra flags)* | Static + LLM (Cursor Agent + judge); cache on; 0 skips |
| **Static only** | `--static-only` | Static fixtures only (partial run) |
| **LLM skip** | `--llm-mode skip` | Skips LLM fixtures with `[SKIP]` (partial run) |
| **Refresh AI** | `--refresh-ai` | Ignore cache; re-call agent and judge |
| **No cache** | `--no-cache` | Disable read/write of harness AI cache |
| **Dry run** | `--dry-run` | List AI fixtures without provider calls |
| **Cap cost** | `--max-ai-calls N` | Stop after N provider calls (agent + judge each count) |

`--static-only` forces `--llm-mode skip` internally.

### Model and cache flags

```bash
review-it rules harness \
  --rule req-implement-just-in-time \
  --model composer-2.5-standard \
  --judge-model composer-2.5-standard \
  --cache-dir target/rule-harness/cache
```

```bash
review-it rules harness --no-cache --refresh-ai
```

Cache keys include fixture content, `target_path`, `rule_id`, rule body hash, `test.yaml` expectation hash, review prompt hash, profile, provider, models, and `review-it` version.

## Fixture levels

### Level 1 — single bad-only file (smallest)

```text
reject-todo-fixme-hack-in-production-test/
  test.yaml
  fixture.rs
```

```yaml
fixtures:
  - id: bad_todo_comment
    kind: bad
    file: fixture.rs
    expect:
      selection:
        selected: true
        backend: static
      review:
        status: fail
        findings_min: 1
        evidence:
          - line_contains: "TODO"
```

### Level 2 — simple bad / good files

```text
reject-example-test/
  test.yaml
  bad.rs
  good.rs
```

Optional non-regression coverage; not required by the schema.

### Level 3 — LLM semantic (Cursor Agent + judge)

For rules with `review.enforce: llm` (or default LLM routing). Expectations are **semantic** in `test.yaml`; the agent output is judged, not compared to a checked-in matrix.

```text
req-implement-just-in-time-test/
  test.yaml
  fixture.rs
```

```yaml
schema_version: 1
skill: yagni-principle
rule_id: req-implement-just-in-time
fixtures:
  - id: bad_speculative_billing_adapter
    kind: bad
    file: fixture.rs
    target_path: src/lib.rs
    expect:
      selection:
        selected: true
        backend: llm
      review:
        status: fail
        findings_min: 1
        expected_result: |
          The review should identify speculative billing abstractions
          added before they are needed, connected to YAGNI / just-in-time.
        evidence:
          - line_contains: "UnusedEnterpriseBillingAdapter"
        should_not_accept:
          - A generic style complaint only.
          - A pass with no findings.
      judge:
        mode: llm
        model: composer-2.5-standard
```

Run one rule:

```bash
review-it rules harness --rule req-implement-just-in-time
```

### Level 4 — shared file with regions (future)

`region:` in `test.yaml` is rejected until region extraction is implemented.

### Level 5 — mini-project / directory

Only when a rule needs multiple files, package layout, or config files.

## File fixture fields

- `file` — relative to `test.yaml`
- `target_path` — optional; defaults to `file` (e.g. `fixture.rs` or `src/lib.rs`)
- `file` and `root` are mutually exclusive
- For a single changed file, `evidence` may use `line_contains` without a path

Legacy `expect.applicability` is accepted; prefer `expect.selection` with `selected` / `backend`.

## `harness.yaml`

```yaml
schema_version: 1
discovery:
  test_glob: "*/*-test/test.yaml"
ai:
  provider: cursor
  model: composer-2.5-standard
  judge_model: composer-2.5-standard
  cache: true
```

Or with skill tests:

```yaml
discovery:
  test_globs:
    - "*/*-test/test.yaml"
    - "../skills/*/*-test/test.yaml"
```

Discovery is glob-driven: any `*/*-test/test.yaml` under `.cursor/rules` (and optional skill globs) is a harness case. Adding a new `*-test/test.yaml` is enough; there is no harness allowlist file (`harness-rules.yaml` is not used). Do not use `.cursor/rule-harness/`, `*.harness/`, or `case.yaml`.

Optional `rules.yaml` in the harness root is only used when the runner loads the review catalog (same format as project `rules.yaml`); it is not required for discovery.

## Commands

**Full suite (default — run all discovered tests):**

```bash
cargo run -p review-it -- rules harness
```

Or with explicit paths:

```bash
cargo run -p review-it -- rules harness \
  --bundle . \
  --harness .cursor/rules
```

**Partial / debug runs:**

```bash
cargo run -p review-it -- rules harness --static-only
cargo run -p review-it -- rules harness --llm-mode skip
cargo run -p review-it -- rules harness --rule req-implement-just-in-time
cargo run -p review-it -- rules harness --skill production-ready-code-standards
cargo run -p review-it -- rules harness --backend llm
cargo run -p review-it -- rules harness --dry-run --max-ai-calls 5
```

Use a current binary: `cargo run -p review-it -- …` or reinstall `review-it` from this repo.

## Cursor indexing

```text
.cursor/rules/**/*-test/
.cursor/skills/**/*-test/
```

Commit in `.cursorignore` / `.cursorindexingignore`; do **not** gitignore test corpora.

## Exit codes

| Code | Meaning |
|------|---------|
| 0 | All run fixtures passed; full-suite runs must have `0 skipped` |
| 1 | Expectation or judge failure |
| 2 | Config / schema error; zero tests discovered; skipped fixtures in a full-suite run |

In the **default** full-suite run, LLM fixtures are not skipped. Use `--llm-mode skip` or `--static-only` only when you intentionally want a partial run.

## Migration from golden mode (S3.5 → S3.6)

Remove from each LLM test directory:

- `goldens/*.matrix.json`
- `goldens/*.provenance.yaml`
- `expect.llm` / `mode: golden` blocks

Add `expect.review.expected_result`, `evidence`, `should_not_accept`, and `expect.judge`. Use existing golden summaries only as drafting notes for `expected_result`.

Helper scripts (cherry-test repo): `scripts/migrate_harness_s36.py` (generic draft), `scripts/migrate_prcs_s36.py`, `scripts/migrate_security_review_s36.py` (tuned per-skill expectations).

**Migrated to S3.6 (verified):** `yagni-principle/req-implement-just-in-time` (proof); `production-ready-code-standards` (19 fixtures); `security-review` (15 fixtures; 1 static + 14 AI/judge); cache-only `--max-ai-calls 0` on each.

Removed CLI flags: `--update-golden`, `--strict-provenance`, `--llm-mode golden`, `--llm-mode live`, `--allow-live-llm`. Default `--llm-mode` is `ai`.
