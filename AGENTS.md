# AGENTS.md

## Start here

This repository implements **Cherry Test** — a workspace test aggregator: it discovers packages, runs Rust and Node tests, boots Docker Compose scopes, merges LCOV coverage, and generates terminal + HTML reports.

User-facing documentation: [README.md](README.md). Quick reference: [CLAUDE.md](CLAUDE.md).

This is a **security-oriented** repository. Test framework bugs can silently hide failures, coverage gaps can mask vulnerabilities, and Docker orchestration errors can produce false-green CI. Treat changes to `test/cherry-test*` and `test/cherry-test-core*` with extra care.

---

## Do / Don't

| Do | Don't |
|----|-------|
| Read README + this file before editing | Add `//` or `/* */` comments in source files |
| Run `cargo test -p cherry-test -p cherry-test-core` after launcher changes | Use `unwrap()` / `expect()` in non-test service code |
| Run `cargo cherry-test mini --no-open` for integration smoke tests | Commit `target/` or coverage artifacts |
| Match existing naming and file layout | Introduce `TODO` / `FIXME` in committed code |
| Update `cherry-test.yaml` when adding packages | Hardcode secrets or credentials |
| Use `?` and typed errors in Axum handlers | Panic in service code paths |
| Run `cargo audit` before opening a PR | Assume `--keep-going` works (not wired yet) |
| Keep package names aligned with Cargo/npm manifests | Use a config filename other than `cherry-test.yaml` |

---

## Repository layout

```
Cargo.toml              Cherry Test workspace (test/cherry-test, cherry-test-common, cherry-test-core, cherry-test-macros)
install.sh              standalone install to ~/.local/bin
.cargo/config.toml      cargo cherry-test alias
docs/                   documentation assets
app/mini/
  cherry-test.yaml             sample project config (paths relative to app/mini/)
  ...                   Rust API, mcap lib, TS lib/web packages
test/
  cherry-test/              test CLI (cherry-test, cargo-cherry-test)
  cherry-test-common/       shared git diff, discovery, config/env helpers
  cherry-test-core/         integration runtime, Docker, TestEnv
  cherry-test-macros/       #[cherry_test] proc macro
```

### Crate boundaries (guardrails)

| Rule | Why |
|------|-----|
| **Keep** Docker/test runtime in `cherry-test-core` | `cherry-test-core` owns the integration runtime |
| **Keep** shared git-diff/config helpers in `cherry-test-common` | Single source of changed-file/config logic |
| **Keep** `#[cherry_test]` validation in `cherry-test-macros` | Macro is the compile-time naming contract |

After touching an area, run the relevant checks:

```bash
cargo test -p cherry-test -p cherry-test-core          # test orchestration + core
cargo build -p cherry-test                         # CLI builds
```

The bundled sample is the **`app/mini/` submodule** ([lmrrcc/mini](https://github.com/lmrrcc/mini), branch `mini`). Run `git submodule update --init app/mini`, then `cargo cherry-test mini --no-open`. Sample-only diffs: `git diff main..mini` inside that repo. Cherry Test has no `mini` branch.

Shop is the **`app/shop/` submodule** ([lmrrcc/shop](https://github.com/lmrrcc/shop), branch `main`). Run `git submodule update --init app/shop`, then `cargo cherry-test --root app/shop --no-open`. Shop changes belong in that repository, not in Cherry Test.

### Where to change what

| Path | Change when… |
|------|----------------|
| `test/cherry-test/src/lib.rs` | CLI flags, orchestration, exit codes |
| `test/cherry-test/src/scaffold.rs` | auto-discovery rules, compose/scope inference |
| `test/cherry-test/src/init.rs` | project resolution, workspace validation |
| `test/cherry-test/src/preflight.rs` | Docker scope planning, skip reasons |
| `test/cherry-test/src/runner.rs` | Rust/Node/external execution |
| `test/cherry-test/src/coverage.rs` | llvm-cov, LCOV merge/normalize |
| `test/cherry-test/src/git_diff.rs` | changed-file coverage (may delegate to cherry-test-common) |
| `test/cherry-test/src/report/` | HTML, terminal, diff view, test view |
| `test/cherry-test-common/src/` | shared config, env helpers, git diff helpers, `report_shell` (shared `index.html` CSS + theme) |
| `test/cherry-test-core/src/workspace.rs` | Docker boot/teardown, env export |
| `test/cherry-test-core/src/integration.rs` | integration test runtime |
| `test/cherry-test-core/src/config.rs` | cherry-test.yaml schema and I/O |
| `test/cherry-test-macros/src/lib.rs` | `#[cherry_test]` validation/expansion |
| `app/mini/cherry-test.yaml` (or per-project `cherry-test.yaml`) | project/package/service/scope config |
| `install.sh`, `test/cherry-test/src/update.rs` | install/update flow |

---

## Commands

```bash
cherry-test                              # standalone CLI (after install.sh)
cherry-test mini                         # filter by project group
cherry-test mini-rs-api                  # filter by package name
cherry-test --init-only                  # auto-generate cherry-test.yaml, no run
cherry-test --force-init                 # regenerate cherry-test.yaml
cherry-test --no-coverage --no-open      # fast local run
cherry-test update                       # reinstall Cherry Test from GitHub main
cherry-test update --repo "$PWD"         # reinstall from local checkout

cargo cherry-test                        # in-repo alias (same flags)
cargo cherry-test -- --nocapture         # forward to cargo test

cargo test -p cherry-test                # launcher unit tests
cargo test -p cherry-test-core           # core unit tests
cargo test -p cherry-test-common         # shared helpers unit tests
cargo test -p cherry-test-macros         # macro tests

git submodule update --init app/mini # init the sample
cd app/mini && pnpm install          # required before Node package runs
cargo audit                          # run before every PR
```

Standalone install:

```bash
curl -fsSL https://lmrr-cc.pages.dev/install/brew-tap.sh | bash && brew install lmrr/cc/cherry-test

curl -fsSL https://lmrr-cc.pages.dev/install/cherry-test.sh | bash
export PATH="$HOME/.local/bin:$PATH"

./install.sh                         # install Cherry Test
```

Root workspace has no `app/mini/*` members — run `cd app/mini && cargo test -p mini-rs-api` or use `cargo cherry-test mini-rs-api`.

---

## Common agent workflows

### 1. Add a new Rust package

1. Add crate to the project workspace `Cargo.toml` `members` (e.g. `app/mini/Cargo.toml` for sample crates)
2. Add entry under `projects.<id>.packages.<name>` in that project's `cherry-test.yaml` with `kinds: [unit]` or `[unit, integration]`
3. If integration tests use Docker, add `compose` + `services` under the project
4. Run `cargo cherry-test <package-name> --no-open`

Or run `cherry-test --force-init` to regenerate config (inspect the diff carefully).

### 2. Add a Vitest package

1. Create package with Vitest in `package.json` scripts or devDependencies
2. Run `pnpm install` in the project directory
3. Add to `cherry-test.yaml`:

```yaml
packages:
  my-ts-lib:
    runtime: node
    manifest: my-project/my-ts-lib/package.json
    kinds: [unit]
    unit:
      command: pnpm
      args: [exec, vitest, run, --coverage]
      cwd: my-project/my-ts-lib
```

4. Run `cargo cherry-test my-ts-lib --no-open`

### 3. Add a scoped integration test

1. Write test in `tests/` with `#[cherry_test(scope = "my_scope")]`
2. Name function `{project}_project_{suite}_suite_{test}_test`
3. Use `env.project("…").service("…")?` for HTTP calls
4. Add scope to `cherry-test.yaml` if cross-project:

```yaml
scopes:
  my_scope:
    projects: [project_a, project_b]
```

5. Ensure each project's `docker-compose.test.yml` is correct
6. Run with Docker available: `cargo cherry-test --no-open`

### 4. Fix coverage or report bug

1. Reproduce: `cargo cherry-test mini --no-open` (tests + report)
2. Inspect `target/cherry-test/latest/reports/index.html` and terminal output
3. Likely files: `test/cherry-test/src/report/`, `test/cherry-test-common/src/`
4. Add/update unit tests in `test/cherry-test/` before changing report logic

---

## Integration test contract

Macro: `#[cherry_test(scope = "optional_scope")]` from `cherry_test_core::prelude::*`.

Naming pattern (compile-time enforced):

```text
{project}_project_{suite}_suite_{test}_test
```

Example (scope boots `mini` compose stack including mcap service):

```rust
use cherry_test_core::prelude::*;

#[cherry_test(scope = "mini_wallet")]
async fn mini_project_wallet_suite_get_address_value_usdt_test(env: TestEnv) {
    let resp = env
        .project("mini")
        .service("api")?
        .get("/bitcoin/address/bc1q...")
        .send()
        .await;
    resp.assert_status(200);
}
```

Rules:

- `async fn` only
- Single parameter: `env: TestEnv`
- No return type
- Lowercase snake_case segments

Without Docker, scoped tests are **skipped** (recorded in JSONL with reason), not failed.

---

## cherry-test.yaml schema

Each project group keeps config beside the code it describes (e.g. `app/mini/cherry-test.yaml`). Paths are relative to that directory.

Discovery: walk up from cwd; if none at the Cargo workspace root, scan immediate child directories; filter by directory name (e.g. `cherry-test mini`) selects one root.

Config file: **`cherry-test.yaml`** (fallback: `cherry-test.yml`, `cherry-test.toml`).

```yaml
defaults:
  coverage: true
  html_report: true
  open_report: true
  runs_base: target/cherry-test/runs
  scope: global          # none | global | project id | scope name
  diff_base: main          # or empty | empty-tree | root for full tracked tree vs empty commit

projects:
  <id>:
    path: <dir>
    diff_base: main        # optional per-project override
    compose: <path>/docker-compose.test.yml
    services:
      <name>:
        service: <compose-service-name>
        port: <port>
        health: /health
        kind: postgres    # optional, for db services
    packages:
      <crate-or-npm-name>:
        kinds: [unit, integration]
        runtime: node     # optional, for Vitest
        manifest: path/to/package.json
        unit:
          command: pnpm
          args: [exec, vitest, run, --coverage]
          cwd: path/to/package
```

Scopes:

```yaml
scopes:
  mini_wallet:
    projects: [mini]
```

Init phase: load config, validate workspace members, discover integration tests, print summary, run Docker preflight.

---

## Environment variables

### Cherry Test runtime

| Variable | Purpose |
|----------|---------|
| `CHERRY_TEST_WORKSPACE_ROOT` | Repo root override |
| `CHERRY_TEST_SKIP_COVERAGE` | Skip coverage (`1` / `true`) |
| `CHERRY_TEST_SKIP_DOCKER` | Skip Docker boot |
| `CHERRY_TEST_NO_OPEN` | Do not open HTML report |
| `RUST_LOG` | Enable tracing diagnostics (e.g. `RUST_LOG=cherry_test=info`) |
| `CHERRY_TEST_RUN_DIR` | Current run directory (set by Cherry Test) |
| `CHERRY_TEST_REPORT_DIR` | Reports subdirectory (set by Cherry Test) |
| `CHERRY_TEST_{PROJECT}_{SERVICE}_HOST` | Service host after Docker boot |
| `CHERRY_TEST_{PROJECT}_{SERVICE}_PORT` | Mapped port |
| `CHERRY_TEST_{PROJECT}_{SERVICE}_URL` | HTTP base URL |

Default run directory: `target/cherry-test/runs/<run-id>/` (override with `defaults.runs_base`).

### Install / update

| Variable | Purpose |
|----------|---------|
| `CHERRY_TEST_REPO` | Git URL or local path (default: `https://github.com/lmrrcc/test`) |
| `CHERRY_TEST_VERSION` | Git branch (default: `main`) |
| `CHERRY_TEST_PATH` | Crate path in repo (default: `test/cherry-test`) |
| `CHERRY_TEST_FORCE=1` | Pass `--force` to `cargo install` |

Vitest (set by runner, not `CHERRY_TEST_` prefix): `VITEST_JSON_REPORT`, `VITEST_COVERAGE_DIR`.

---

## Coding rules

Always apply (zero tolerance):

- No `TODO` / `FIXME` / `HACK` / `XXX` in production source
- No placeholder returns, `todo!()` / `unimplemented!()`, or fake/simulated data
- No hardcoded secrets, API keys, or credentials — use environment variables
- Validate and sanitize all user-controlled input at the boundary
- No `println!` / `eprintln!` in service code — use `tracing::{info,warn,error}` with structured fields
- Never silently swallow errors — log with structured fields then propagate a typed domain error
- No `unwrap()` / `expect()` in service code — use `?` and typed errors
- No `unsafe` without PR justification
- No comments in source code — use clear naming
- Remove unused code instead of commenting it out

---

## Error handling pattern (sample services)

```rust
pub async fn handler(Path(id): Path<Uuid>) -> Result<Json<Response>, AppError> {
    let record = repository.find(id).await?;
    Ok(Json(record.into()))
}
```

Never panic in service code. Map errors to typed `AppError` with appropriate HTTP status. Log at origin with structured fields.

---

## Logging pattern

```rust
tracing::info!(request_id = %id, address = %addr, "lookup started");
tracing::warn!(latency_ms = latency, "upstream slow");
tracing::error!(error = %e, "database query failed");
```

Never log secrets, tokens, full request bodies, or unredacted PII.

---

## Security checklist (required before opening a PR)

- [ ] `cargo audit` — zero high/critical advisories
- [ ] `npm audit` (if frontend changed) — zero high/critical advisories
- [ ] No `unwrap()` / `expect()` introduced in non-test code
- [ ] All new HTTP inputs validated before use
- [ ] No secrets in source, fixtures, or test output
- [ ] No new `unsafe` without documented justification in PR
- [ ] Coverage artifacts are gitignored (`target/`)
- [ ] Docker health checks present for every new service
- [ ] Integration tests pass against real Docker services (not mocks)
- [ ] Launcher/core unit tests pass: `cargo test -p cherry-test -p cherry-test-core`
- [ ] Smoke test: `cargo cherry-test mini --no-open`

Test framework changes (runner, coverage merge, report generation) require extra scrutiny — they can hide real test failures.

---

## Coverage artifacts

Each run writes to `target/cherry-test/runs/{run-id}/`. Symlink `target/cherry-test/latest` points at the most recent run.

| Path | Contents |
|------|----------|
| `runs/{run-id}/targets/{project}/` | Isolated CARGO_TARGET_DIR |
| `runs/{run-id}/coverage/{project}/*.lcov` | Per-project LCOV |
| `runs/{run-id}/coverage/merged.lcov` | Workspace merged LCOV |
| `runs/{run-id}/reports/index.html` | Aggregate HTML report |
| `runs/{run-id}/reports/integration/*.jsonl` | Integration HTTP records |
| `runs/{run-id}/reports/unit/*.json` | Vitest JSON results |
| `runs/{run-id}/env.json` | Docker endpoint snapshot |
| `latest` | Symlink to most recent run |

**Never commit** anything under `target/`.

After a run with `html_report: true`, the report opens locally when `open_report: true`. Skipped in CI. Disable with `--no-open`, `CHERRY_TEST_NO_OPEN=1`, or config.
