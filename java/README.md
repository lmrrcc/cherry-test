# Cherry Test — Java edition

A second implementation of Cherry Test in **Java 25 LTS**, built alongside the Rust edition in `test/`. The Rust
edition is the reference: the Java edition must produce **byte-for-byte and pixel-for-pixel
identical reports** for the same project and the same tool outputs.

## Status

| Area | State |
|---|---|
| Maven reactor, wrapper, quality gates (Error Prone, NullAway/JSpecify, Spotless, JaCoCo, enforcer) | done |
| `rustcompat` kit: Rust-exact number formatting, text semantics, string/path ordering, serde_json bytes | done, golden-tested |
| `cherry-test-common`, `cherry-test-core` | done; every Rust test ported under its Rust name |
| `cherry-test-junit` (`@CherryTest`, `TestEnv`, HTTP records) and `cherry-test-processor` | done |
| `cherry-test-cli` (all 7 built-in adapters, external protocol v1, coverage, CRAP, HTML and terminal reports) | done; matching Rust test names retained |
| Parity harness: record/replay, orchestration, byte and pixel gates | done |
| Byte and pixel parity on `app/shop` | see [current validation](PARITY.md#validation-on-2026-10-16) |
| Byte and pixel parity on `app/mini` | see [current validation](PARITY.md#validation-on-2026-10-16) |
| `app/shop-java` (Spring Boot 4.1 API, Java adapter) under both CLIs | see [current validation](PARITY.md#validation-on-2026-10-16) |
| `app/mini-java` (wallet API, Okta mock, Java prices adapter, mock upstream) under both CLIs | see [current validation](PARITY.md#validation-on-2026-10-16) |
| Distribution: shaded jar, `java/install.sh`, `cherry-test-java` launcher, `update`, Maven plugin | done |

Every Rust test name has a Java counterpart with the same name; `PARITY.md` maps them and lists the comparison
results. The 2026-10-16 validation passed 333 Java tests and 273 Rust tests, followed by all 7 parity harness
tests after adding 3 comparator regression tests. Regenerated compatibility vectors matched, and `cargo audit`
reported zero vulnerabilities after updating the QUIC and TLS dependencies.

## Install and run

```bash
java/install.sh                              # builds the CLI, installs cherry-test-java to ~/.local/bin
cherry-test-java --root app/shop --no-open   # same flags, config and reports as cherry-test
./mvnw cherry-test:run -Dcherry-test.args="mini --no-open"   # from a Maven project, like cargo cherry-test
```

`cherry-test-java update` reinstalls from GitHub, like `cherry-test update`. The Maven plugin
(`cherry-test-maven-plugin`) forks the CLI with the classpath Maven resolves; a project gets the `cherry-test:` prefix
by declaring the plugin in `pluginManagement`.

## Requirements

- JDK 25 (Corretto 25 is used locally and in CI)
- No Maven install needed: use `./mvnw`
- Rust toolchain only to regenerate golden vectors

## Build

```bash
cd java
./mvnw verify
```

`verify` compiles with `--release 25` under Error Prone and NullAway, runs the tests with JaCoCo and checks
formatting. `./mvnw spotless:apply` fixes formatting.

## Golden vectors

The `rustcompat` kit reproduces the Rust behaviour that reaches Cherry Test reports: `{}` and `{:.N}` float
formatting (exact binary value, half-even ties, no locale), Unicode `White_Space` trimming, `str::lines`,
UTF-8 byte ordering of strings, component-wise ordering of paths, and serde_json's compact and pretty output with
sorted or declaration-ordered keys.

Its expected values are not written by hand. `parity/vectors-rs` is a standalone Rust crate (its own
`[workspace]`, not a member of the root workspace) pinned to the same `serde` and `serde_json` versions as the
Rust edition. It computes the vectors with the real Rust standard library:

```bash
cargo run --release --locked --manifest-path java/parity/vectors-rs/Cargo.toml -- java/cherry-test-common/src/test/resources/vectors
```

CI regenerates them and fails if the committed files differ.

## Parity harness

`parity/parity.sh` records one run of the unmodified Rust CLI and replays it for the Java CLI:

```bash
cargo build --release -p cherry-test --target-dir java/parity/target/rust
(cd java && ./mvnw -B -DskipTests package)
java/parity/parity.sh record app/shop    # Rust CLI, real tools, every call captured in a cassette
java/parity/parity.sh replay app/shop    # Java CLI, tools answered from the cassette
java/parity/parity.sh compare app/shop   # terminal, exit code, orchestration and run dir, byte for byte
java/parity/parity.sh pixels app/shop    # index.html and phases.html, light and dark, desktop and phone
java/parity/parity.sh record app/mini-java mini   # optional third argument: the CLI filter for both runs
```

During recording every external tool (`cargo`, `node`, `pnpm`, `git`, `docker`, `cmake`, `python3`, ...) runs
through a shim that forwards to the real tool and stores its argv, working directory, environment, output, exit code
and the files it wrote. During replay the shim serves the recorded call only if the Java CLI asks for the same tool
with the same arguments in the same directory; any other call is a miss and fails the gate. The Rust CLI's own run id
is reused, so the two runs write the same paths.

The only normalisations are documented in `PARITY.md`: the run id inside recorded files, the order of the
`docker compose port` calls that follow one `up` (the Rust CLI iterates a `HashMap`), and the shim directory in
`PATH`.

