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
| Pixels | `index.html` and `phases.html`, selected interactive states and anchors, light and dark theme, 1440×900 and 390×844 | `Pixels` (Playwright, zero tolerance) |

## Validation on 2026-10-16

The current CLIs were compared using frozen copies of the four local sample workspaces, including their
working-tree changes. Both CLIs used the same copy and external tool outputs; these results do not claim
that the sample workspaces were clean checkouts of their pinned submodule commits.

| App / filter | Run | Tool calls | Terminal, orchestration and run directory | Final pixel gate |
|---|---|---|---|---|
| `shop` | `20261016-182202-f431a8` | 230 | identical, exit 1 | 92 of 92 screenshots identical |
| `shop-java` | `20261016-181547-0105d8` | 637 | identical, exit 1 | 92 of 92 screenshots identical |
| `mini`, project `mini` | `20261016-182000-654bd0` | 136 | identical, exit 0 | 92 of 92 screenshots identical |
| `mini-java`, project `mini` | `20261016-182315-d18198` | 814 | identical, exit 0 | 52 of 52 screenshots identical |

Both Shop runs report the deliberate `reports_a_price_mismatch` failure. The selected Mini integration
tests passed against real Docker services: 7 Rust tests and 9 Java tests, with no skips.
Across the four pairs, 971 report files match byte for byte, and all 1,817 recorded tool calls match the
orchestration contract. Local evidence includes SHA-256 hashes for each report file.

The first Shop pixel pass found 61 one-level RGB differences across 3 of 92 screenshots, confined to
rounded control borders. The report files were identical. Recapturing the same files at both paths
produced 40 identical comparisons with the original browser settings and another 40 with software
rendering. The original screenshots and logs were retained locally. The final gate uses software
rendering, a fixed sRGB color profile and disabled Skia runtime optimizations, without masks or tolerance.
The browser was Google Chrome for Testing 156.0.8078.4 (headless shell build 1248), driven by Playwright 1.63.0
on macOS ARM64 with device scale factor 1.

The comparator skips image decoding only when the PNG bytes already match. Different bytes still receive
the exact pixel comparison. Regression tests cover identical bytes, identical pixels with different
bytes, and rejection of a single-channel difference of one RGB level. The pixel process has a 512 MiB
heap limit and reports progress for each state.

Validation also passed 273 Rust tests and 333 Java tests before the comparator update, then all 7 parity
harness tests after adding its 3 regression tests. Regenerated compatibility vectors match the committed
files, and `cargo audit` reports zero vulnerabilities after the QUIC and TLS dependency updates.
These gates establish parity for the recorded inputs and sampled visual states, not for every possible input.

## Historical results on 2026-10-13

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
rendered one dark-theme region differently between two captures of the same bytes. Tiles bound capture dimensions:
every tile of the Rust report is compared with the same tile of the Java report.
The current gate additionally fixes the rendering configuration as described above.

## Normalisations

Run recording, replay and comparison in the same shell environment. The environment gate also checks inherited
variables such as `PATH`, terminal settings and tool invocation identifiers. Launching the stages through separate
automation calls can change those inputs and must not be accepted as an identical execution.

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
