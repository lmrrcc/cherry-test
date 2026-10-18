# CLAUDE.md

Quick reference for Claude/Cursor when working in the Cherry Test repository. Full docs: [README.md](README.md). Agent workflows: [AGENTS.md](AGENTS.md).

## Project overview

Multi-project Cargo workspace providing `cargo cherry-test` / `cherry-test` — a test aggregator that runs unit and integration tests across project groups, orchestrates Docker Compose scopes, collects LLVM/Vitest coverage, diffs changed files against git, and produces terminal + HTML reports.

Security-oriented: mistakes here can compromise test integrity or leak secrets through logs and artifacts.

## Stack

| Layer | Technology |
|-------|------------|
| Test framework | Rust 2021 — `cherry-test`, `cherry-test-core`, `cherry-test-macros`, `cherry-test-common` |
| Sample services | Axum 0.8 + Tokio 1 (`mini-rs-api`, `mini-rs-lib-mcap`) |
| Frontend samples | React 18, Vue 3, TypeScript 5 strict (Vitest) |
| Containers | Docker Compose per project |
| Coverage | cargo-llvm-cov (Rust), Vitest LCOV (Node) |
| Config | `cherry-test.yaml` |

## Mandatory rules — always apply (zero tolerance)

These rules apply to every change without exception.

**Production integrity:**
- No `TODO`, `FIXME`, `HACK`, `XXX` in production source — tracked task or immediate fix required
- No placeholder returns, empty bodies where logic is required, or `todo!()`/`unimplemented!()` — ship complete code
- No hardcoded fake data, simulated API responses, or fallbacks to fake data on real-call failures
- Never return empty success objects on critical errors; never swallow errors without rethrowing a typed domain error after logging
- Changes claiming production-ready must have successful compilation and passing tests as evidence

**Security:**
- No hardcoded credentials, API keys, tokens, or secrets in source — use environment variables
- Validate and sanitize all user-controlled input (bodies, path params, query params, headers, files) at the boundary before business or persistence logic uses it
- Block release on: SQLi, XSS, auth bypass, authorization failure, hardcoded secrets, missing input validation, IDOR, unencrypted sensitive data

**Architecture:**
- Reject controllers or adapters that bypass the application layer or embed business logic directly — route through use cases

**Logging:**
- No `println!` / `eprintln!` in service code — use `tracing::{info,warn,error}` with structured fields
- Never silently swallow errors — log with structured fields then propagate as a typed domain error

**Code quality:**
- Implement functionality only when an immediate concrete requirement exists — no speculative generalization
- Remove unused code instead of commenting it out — version control preserves history
- Never apply workarounds or temporary solutions without explicit user permission — implement the correct fix

**TypeScript:**
- `"strict": true` always; no `any` — use `unknown` + narrowing; no `dangerouslySetInnerHTML`

**General:**
- No comments in source code — use clear naming; markdown docs are fine
- No empty lines in function bodies
- No `unwrap()` / `expect()` in service code — use `?` and typed errors
- No `unsafe` without PR justification
- `cargo audit` clean before merge

## Project structure

```
/
├── Cargo.toml              # workspace (test/cherry-test, cherry-test-common, cherry-test-core, cherry-test-macros)
├── install.sh              # standalone install (Cherry Test)
├── .cargo/config.toml      # cargo cherry-test alias
└── test/
    ├── cherry-test/            # test CLI (cherry-test, cargo-cherry-test)
    ├── cherry-test-common/     # shared git diff, config, env helpers
    ├── cherry-test-core/       # integration runtime, Docker, TestEnv
    └── cherry-test-macros/     # #[cherry_test] proc macro
```

Sample project: `app/mini/` is a submodule to [lmrrcc/mini](https://github.com/lmrrcc/mini) (branch `mini`; `git diff main..mini` there). Cherry Test stays on `main` only.

Shop: `app/shop/` is a submodule to [lmrrcc/shop](https://github.com/lmrrcc/shop) (branch `main`). Run it with `cargo cherry-test --root app/shop --no-open`. Shop changes belong in that repository.

Config discovery: walk up from cwd for `cherry-test.yaml`; if none at the Cargo workspace root, scan immediate child dirs (e.g. `my-project/cherry-test.yaml`). Config fallbacks: `cherry-test.yml`, `cherry-test.toml`.

## Essential commands

```bash
cargo build -p cherry-test            # build the CLI

cargo cherry-test                     # all projects
cargo cherry-test mini                # project group
cargo cherry-test mini-rs-api         # single package
cargo cherry-test --no-coverage --no-open
cherry-test                           # standalone binary
cherry-test --init-only               # scaffold cherry-test.yaml only
cherry-test update                    # reinstall Cherry Test from GitHub

./install.sh                      # install Cherry Test to ~/.local/bin

cargo test -p cherry-test -p cherry-test-core -p cherry-test-common -p cherry-test-macros
git submodule update --init app/mini && cd app/mini && cargo test -p mini-rs-api
cargo audit
```

Sample: `git submodule update --init app/mini && cd app/mini && pnpm install && cd ../.. && cargo cherry-test mini --no-open`

Standalone copy: `git clone -b mini https://github.com/lmrrcc/mini.git && cd mini && pnpm install && cherry-test --no-open`

Install from source: `cargo install --git https://github.com/lmrrcc/test --branch main --locked --bins cherry-test`

Config: **`cherry-test.yaml`** (fallbacks: `cherry-test.yml`, `cherry-test.toml`). **Submodules:** one root `cherry-test.yaml` with `projects.<id>.path` pointing at nested git checkouts. Run dirs: `target/cherry-test/runs/<run-id>/`.

## Integration test pattern

```rust
use cherry_test_core::prelude::*;

#[cherry_test(scope = "mini_wallet")]
async fn mini_project_wallet_suite_get_balance_test(env: TestEnv) {
    let resp = env.project("mini").service("api")?.get("/health").send().await;
    resp.assert_status(200);
}
```

Naming: `{project}_project_{suite}_suite_{test}_test` — enforced at compile time.

## Configuration

**`cherry-test.yaml`**. See README [Configuration](README.md#configuration-testyaml) for schema (`defaults` + `projects` + `scopes`).

Key sections:

- `defaults` — coverage, html_report, open_report, runs_base, scope, diff_base
- `projects.<id>` — path, compose, services, packages
- `scopes.<name>` — Docker scope within a project (e.g. `mini_wallet: [mini]`)

Node packages need `runtime: node` + `unit:` runner block in config.

## Security checklist

- [ ] No secrets in source or fixtures
- [ ] No `unwrap()` / `expect()` in non-test code
- [ ] HTTP inputs validated
- [ ] `cargo audit` clean (no high/critical)
- [ ] No new `unsafe` without PR justification
- [ ] Logs free of PII/secrets
- [ ] Never commit `target/`
- [ ] `cargo test -p cherry-test -p cherry-test-core` passes
- [ ] With `mini` submodule init: `cargo cherry-test mini --no-open` smoke test passes

## Deep docs

- [README.md](README.md) — installation, usage, reports, limitations
- [AGENTS.md](AGENTS.md) — repo map, workflows, env vars, PR checklist
