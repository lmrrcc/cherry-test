# Code Review — cherry-test workspace (initial uncommitted tree)

| Field | Value |
|-------|-------|
| **Date** | 2026-05-23 |
| **Scope** | Full working tree — no commits yet; all files untracked |
| **Verdict** | **CHANGES REQUIRED** |
| **Score** | 48 / 100 |
| **Decision** | Block merge until `cargo build --workspace` succeeds and the launcher binary compiles |

**Critical:** missing `package_kinds` import in `cherry-test` runner; `expect` in sample service binaries  
**Major:** unused Postgres in mini compose; no Bitcoin address validation; duplicated naming parser; weak `mcap-rust` integration coverage; HTTP records store full response bodies  
**Minor:** `.idea/` not gitignored; unused-import warnings; macro test wrapper drops error context; `cargo audit` not installed locally; `.cursor/` gitignored so review prompt is not versioned

---

## Executive Summary

This review covers the full initial `cargo cherry-test` workspace: launcher, core runtime, proc-macro, sample Axum services, and YAML config. Config loading and `cherry-test-core` scope tests were fixed since the prior review — `cargo test -p cherry-test-core` passes five tests.

The workspace **still does not build**: `test/cherry-test/src/runner.rs` calls `package_kinds` without importing it from `crate::config`. Until that is fixed, `cargo cherry-test` cannot run. Sample services still panic on bind/serve failures via `expect`. Address the launcher compile error first, then close documentation, validation, and integration-coverage gaps.

---

## Strengths

1. **Integration test convention enforced at compile time** — `cherry-test-macros` validates async signature, `TestEnv` parameter, naming pattern, and optional scope attribute.
2. **Config loader fixed** — `locate_config` in `cherry-test-core` no longer references an out-of-scope `deprecated` binding; fallback returns `(cherry-test.yaml, false)` cleanly.
3. **Scope tests aligned with current config model** — `cherry-test-core` `sample_config()` matches `WorkspaceConfig` and all five unit tests pass.
4. **Scope-aware Docker orchestration** — compose merge, health waits, env export to `target/cherry-test/env.json`, teardown with `docker compose down -v`.
5. **Preflight and coverage pipeline** — discovers integration tests from source, skips Docker when unavailable, isolated `CARGO_TARGET_DIR`, LCOV merge, terminal + HTML reports.
6. **Review workflow prompt added** — `.cursor/prompts/review-changed-code.md` defines how to audit changes and write this report (local only while `.cursor/` stays gitignored).

---

## Critical Issues

### C1 — Launcher crate fails to compile (`package_kinds` not in scope)

**Location:** `test/cherry-test/src/runner.rs:78`

**Problem:** `run_project_member` calls `package_kinds(opts.config, &member.name, &project.id)` but `runner.rs` imports only `Config`, `ExternalRunner`, and `TestKind` from `crate::config`. The function lives in `test/cherry-test/src/config.rs:37` and is imported in `report/model.rs` but not in `runner.rs`.

**Impact:** `cargo build --workspace` fails; `cargo test -p cherry-test` fails; the primary `cargo cherry-test` entrypoint is unusable.

**Fix:** Add `package_kinds` to the import in `runner.rs`:

```rust
use crate::config::{package_kinds, Config, ExternalRunner, TestKind};
```

---

### C2 — `expect` in sample service binaries

**Location:** `mini/mini-rs-api/src/main.rs:6-7`, `mcap/mcap-rust/src/main.rs:6-7`

**Problem:** Bind and serve use `.expect("bind")` and `.expect("serve")`, violating the workspace rule against panics in non-test code.

**Impact:** Startup failures panic instead of returning a structured exit code; inconsistent with security-oriented service guidelines.

**Fix:** Propagate errors with `?` from `main` (return `anyhow::Result<()>`) or map failures to `std::process::exit(1)` after `tracing::error!`.

```6:7:mini/mini-rs-api/src/main.rs
    let listener = tokio::net::TcpListener::bind(&addr).await.expect("bind");
    axum::serve(listener, api::router()).await.expect("serve");
```

---

## Major Issues

### M1 — README and config documentation drift

**Location:** `README.md:10-11,69-75`; runtime config at `cherry-test.yaml`

**Problem:** README layout and configuration sections must name `cherry-test.yaml` as the config file (first candidate in `CONFIG_CANDIDATES`).

**Impact:** New contributors edit the wrong config shape; agent docs and README disagree with runtime behavior.

**Fix:** Document `cherry-test.yaml` as the single source of truth; describe `defaults`, nested `projects.*.packages`, and `scopes`; remove or clearly deprecate TOML references.

---

### M2 — Postgres service wired in compose but unused by `mini-rs-api`

**Location:** `mini/docker-compose.test.yml:2-22`, `mini/mini-rs-api/src/api.rs`

**Problem:** Compose starts `mini-db` with health checks and `mini-rs-api` depends on it, but the API returns hardcoded balances with no database access.

**Impact:** Integration tests imply full-stack coverage while Docker boot is heavier than needed; production-readiness rules flag simulated data paths.

**Fix:** Connect `mini-rs-api` to Postgres for real lookups, or remove `mini-db` until persistence exists and document the sample as HTTP-only.

---

### M3 — No input validation on Bitcoin address path parameter

**Location:** `mini/mini-rs-api/src/api.rs:40-56`

**Problem:** `Path(address)` is accepted without format or length checks; any string returns a synthetic success payload with fixed amounts.

**Impact:** Violates validate-all-input-at-boundary rules; unbounded path segments and misleading success for invalid input.

**Fix:** Validate bech32/base58 shape and length; return `400` with a typed error response for invalid addresses.

---

### M4 — Duplicated test naming parser in proc-macro crate

**Location:** `test/cherry-test-macros/src/lib.rs:12-48` vs `test/cherry-test-core/src/naming.rs`

**Problem:** Nearly identical parsing logic exists in both crates. The macro calls `cherry_test_core_naming::parse_test_name` locally instead of sharing `cherry-test-core`.

**Impact:** Naming rule changes can diverge between compile-time macro checks and runtime parsing.

**Fix:** Expose naming from `cherry-test-core` (or a tiny shared crate) and depend on it from `cherry-test-macros`.

---

### M5 — Weak cross-service integration coverage for `mcap-rust`

**Location:** `cherry-test.yaml:31-32`; no `mcap/mcap-rust/tests/integration.rs`

**Problem:** `mcap-rust` is configured with `kinds: [unit]` only. The `mini_wallet` scope boots both projects and mini's integration test calls mcap, but mcap has no dedicated integration crate.

**Impact:** Regressions in `mcap-rust` HTTP contract may only surface indirectly through mini's test.

**Fix:** Add `mcap-rust/tests/integration.rs` with health and `/btc/usdt` contract tests; set `kinds: [unit, integration]`.

---

### M6 — HTTP recording persists full response bodies

**Location:** `test/cherry-test-core/src/http.rs:111-131`

**Problem:** Every HTTP exchange stores the entire response body in memory and on disk for reports.

**Impact:** If services return PII or secrets, artifacts under `target/cherry-test/reports` become a disclosure surface.

**Fix:** Truncate or redact bodies by default; opt in to full capture via env flag for debugging.

---

## Minor Issues

### N1 — `.idea/` not excluded from VCS

**Location:** repository root `.gitignore`

**Problem:** JetBrains metadata (`.idea/`) is untracked but not listed in `.gitignore` (only `target/`, `.cursor/`, `.claude/`).

**Fix:** Add `.idea/` before first commit.

---

### N2 — Unused-import warnings in test code paths

**Location:** `test/cherry-test-core/src/scope.rs:70` (`BTreeMap`); `test/cherry-test/src/report/model.rs:6` (`package_kinds`)

**Problem:** Compiler warnings on every build of affected crates.

**Fix:** Remove unused imports; fixing C1 may resolve the `report/model.rs` warning if the import is only needed there.

---

### N3 — Macro-generated integration test loses structured error context

**Location:** `test/cherry-test-macros/src/lib.rs:192`

**Problem:** Generated tests use `.expect("cherry-test integration test failed")` on runtime `run()` results instead of printing the `anyhow` chain.

**Fix:** Use `unwrap_or_else(|e| panic!("{e:#}"))` or equivalent for clearer CI failures.

---

### N4 — `cargo audit` not available locally

**Problem:** `cargo audit` is not installed; security checklist from `AGENTS.md` cannot be verified in this environment.

**Fix:** Document as a prerequisite; add CI step once the repo has commits.

---

### N5 — Review prompt not versioned

**Location:** `.gitignore:3` (`.cursor/`), `.cursor/prompts/review-changed-code.md`

**Problem:** The new review prompt lives under `.cursor/`, which is gitignored, so teammates will not get it from clone.

**Fix:** Either stop ignoring `.cursor/prompts/` or copy the prompt to a tracked path (for example `docs/review-changed-code.md`).

---

## Assessment Breakdown

| Area | Weight | Score | Notes |
|------|--------|-------|-------|
| Build / compile | 25% | 15 | `cherry-test-core` and sample services build; launcher fails on missing import |
| Tests | 20% | 55 | Core unit tests pass; launcher tests cannot compile; integration path unverified this run |
| Architecture | 15% | 75 | Clear launcher / core / macro / support split |
| Security | 15% | 50 | No secrets in source; missing address validation; full HTTP body logging |
| Production readiness | 15% | 40 | Sample services use hardcoded data; compose/db mismatch |
| Docs / config | 10% | 45 | README behind `cherry-test.yaml`; review prompt local-only |

---

## Final Recommendation

**CHANGES REQUIRED** — fix **C1** immediately so `cargo build --workspace` and `cargo test -p cherry-test` succeed. Then address **C2** and **M1–M3** before treating sample services or docs as production-aligned.

**Suggested order**

1. Add `package_kinds` import in `runner.rs` and confirm `cargo build --workspace` (minutes).
2. Run `cargo test -p cherry-test` and, with Docker, `cargo cherry-test --no-coverage` (same day).
3. Replace `expect` in `mini-rs-api` and `mcap-rust` `main` with propagated errors (under 1 hour).
4. Align README with `cherry-test.yaml` and nested package config (half day).
5. Add Bitcoin address validation and `mcap-rust` integration tests (1–2 days).

**Verdict (repeat):** **CHANGES REQUIRED** — do not merge until the workspace builds and the launcher crate compiles.
