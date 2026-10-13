# Parity between the Rust and Java editions

The Rust edition in `test/` is the reference. The Java edition must produce the same terminal output, exit code,
external tool calls and run directory, byte for byte, and the same rendered HTML, pixel for pixel, for the same
project and the same tool outputs.

## Gates

| Gate | What is compared | Tool |
|---|---|---|
| Orchestration | every external tool call: tool, argv, working directory, environment, order | `Orchestration` |
| Terminal | stdout, stderr and exit code of the CLI | `cmp` |
| Run directory | every file, directory and symlink under `target/cherry-test/runs/<run-id>/` | `Compare` |
| Pixels | `index.html` and `phases.html`, every interactive state, light and dark theme, 1440×900 and 390×844 | `Pixels` (Playwright, zero tolerance) |

## Results

Recorded with the Rust CLI, replayed with the Java CLI, on the unmodified Rust edition of each app:

| App | Run | Tool calls | Terminal | Run directory | Pixels |
|---|---|---|---|---|---|
| `app/shop` | `20261013-011836-c19c38` | 209, same order | identical (exit 1) | identical | 92 of 92 screenshots identical |
| `app/mini` | `20261013-012222-018d68` | 248, same order | identical (exit 1) | identical | 96 of 96 screenshots identical |

Both exits are 1 because both apps keep one failing test on purpose; the Java CLI reports the same failure.

The Java editions of the apps run under both CLIs the same way (Rust CLI recorded, Java CLI replayed):

| App | Run | Tool calls | Terminal | Run directory | Pixels |
|---|---|---|---|---|---|
| `app/shop-java` | `20261013-041918-6c2ba8` | 274, same order | identical (exit 1) | identical | 92 of 92 screenshots identical |
| `app/mini-java`, project `mini` | `20261013-043037-5a6760` | 224, same order | identical (exit 0) | identical | 52 of 52 screenshots identical |

`app/shop-java` keeps the same deliberate failure as the Rust edition (`reports_a_price_mismatch`); its Playwright
visual tests pass against the Rust edition's snapshot images, unchanged.

The pixel gate captures each page in tiles 4000 px high. The tallest reports are far beyond what Chromium can capture
in one screenshot (the mini index is 3.1 MB of HTML), and a single capture of a page about 95,000 px high
rendered one dark-theme region differently between two captures of the same bytes. Tiles remove both problems:
every tile of the Rust report is compared with the same tile of the Java report.

## Normalisations

These are the only differences the gates tolerate. Each one is caused by the environment, not by the CLI.

- **Run id.** The Rust CLI derives its run id from the clock. The Java CLI receives the recorded id through
  `-Dcherrytest.runId`, so both write the same paths. Inside recorded tool output the id is stored as a placeholder
  and expanded on replay.
- **`docker compose port` order.** After one `up`, the Rust CLI asks for the mapped port of each service in the
  iteration order of a `HashMap`, which changes from run to run. The gate accepts any order within that block of
  calls; everything else must match in recorded order.
- **Shim directory.** `PATH` and `NVM_BIN` point at a per-app shim directory during recording and replay.
- **Endpoint variables during `port` calls.** While the ports are being resolved, the `CHERRY_TEST_<P>_<S>_*`
  variables of services resolved earlier in the same block depend on that `HashMap` order too.

## Rust behaviour reproduced on purpose

The Java edition mirrors the Rust edition, including behaviour that looks surprising:

- `GIT_EMPTY_TREE` is the 41-character string used by the Rust code, not git's empty-tree hash.
- JUnit XML with a document type declaration is rejected, as `roxmltree` does.
- `request.json` keeps environment variables whose names contain `PASSWORD`; the Rust filter only drops names
  containing `AUTH`, `TOKEN` or `SECRET`.
- Scaffolding collapses several `build:` services into one `api` service.

## Test mapping

Every Rust test has a Java twin with the same snake_case name, in the matching class:

| Rust | Java |
|---|---|
| `test/cherry-test-common/src/git_diff.rs` | `cherry-test-common/.../gitdiff/GitDiffTest` |
| `test/cherry-test-common/src/report_shell.rs` | `cherry-test-common/.../reportshell/ReportShellTest` |
| `test/cherry-test-common/src/ignore.rs` | `cherry-test-common/.../ignore/PathIgnoreTest` |
| `test/cherry-test-core/src/*.rs` | `cherry-test-core/.../{TestItConfig,RepoRoot,Scope,Auth,TestId}Test` |
| `test/cherry-test-macros/src/lib.rs` | `cherry-test-processor/.../CherryTestProcessorTest` |
| `test/cherry-test/src/**/*.rs` | `cherry-test-cli/src/test/java/**` (one class per Rust module) |
| `test/cherry-test/tests/adapter_contract.rs` | `cherry-test-cli/.../contract/AdapterContractIT` (Failsafe, runs the shaded jar) |
