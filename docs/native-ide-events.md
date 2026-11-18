# Native IDE event reporting (Java CLI)

Set `CHERRY_TEST_IDE_EVENTS` to an output JSONL path before launching the Java CLI. The stream supplements existing terminal, HTML, adapter protocol v1 and LCOV reports. The Rust CLI does not yet emit this optional stream. Each configuration root has its existing run-directory ID. Allocate a new output path per CLI invocation; sequential configuration roots append separate runs to that path. Concurrent processes must not share an event file.

The Java CLI emits events after configuration initialization and creation of the run directory. A configuration/preflight error before that point can produce no events. A killed process can leave a truncated last JSON line or no terminal event. Neither case establishes a successful or complete run. The ordinary process exit code remains authoritative. Events are UTF-8 JSON, one object per line, flushed after each record. Requested event-write failures fail the invocation instead of silently losing evidence. With the variable unset there is no event file or source-hashing traversal.

## Schema version 1

Every event contains `schemaVersion: 1`, `event`, `runId`, zero-based increasing `sequence`, UTC ISO `timestamp`, and monotonic `elapsedNanos` measured within this run. Consumers must not compare elapsed clocks across runs.

| Event | Fields |
| --- | --- |
| `run.started` | `workspaceRoot`, `sourceRoot`, `implementation: "cherry-test-java"`, `configPath` when found, `coverageEnabled` boolean, `scope` phase names, `sourceBefore`, `sourceBeforeComplete`, `sourceBeforeErrors`, `compiledIdentity: "unknown"`, `perTestAttribution: false` |
| `phase.started` | phase identity, `coverageRequested`, `execution` |
| `phase.completed` | phase identity, `success`, `failureScope`, `completeness`, `testInventoryComplete: "unknown"`, `coverageComplete: "unknown"`, `coverageRequested`, `coverageAvailability`, `tests`, `diagnostics`, `coverage`, `coveragePrecisionArtifacts`, `logs`, `resultPath`, `compiledIdentity: "unknown"`, `perTestAttribution: false` |
| `run.completed` | `outcome`, `complete`, `sourceAfter`, `sourceAfterComplete`, `sourceAfterErrors`, `sourceStable`, `rawFailure` or null, `resultDirectory`, `htmlReport` or null, `compiledIdentity: "unknown"`, `perTestAttribution: false` |

Phase identity comprises `projectId`, `moduleId` (workspace-relative manifest parent, `.` at root), `packageId`, `phaseId`, `runtimeId` (actual adapter name, such as `maven`), `configuredPackageRuntime`, and `phaseKey`. `configuredPackageRuntime` exposes the existing configuration enum (currently RUST/NODE), **not** an inferred language or JVM version. Maven packages can have configuredPackageRuntime RUST. `phaseKey` is a SHA-256 of project, normalized manifest path, package and phase separated by newlines. Pair it with `runId` for an invocation identity. No runtime version is invented.

`scope` lists the requested/default phases; package configuration can narrow the phases that actually produce events. `phase.started` precedes adapter execution after existing scope/auth preparation. `phase.completed.completeness` is `complete` when the adapter returns, `incomplete` on an orchestration exception, or `cancelled`. It does not certify that every conceivable test was discovered or that every probe was flushed: the two explicit completeness fields remain `unknown` because protocol v1 cannot establish those facts. `coverageAvailability` is `disabled`, `unavailable`, or `present`; present means artifacts exist, not that coverage is complete or current.

`failureScope` is `none`, `tests` when at least one actual test fails, or `execution` for an unsuccessful phase without a reported failing test. It does not rule out simultaneous infrastructure problems; preserve `diagnostics`. `run.completed.outcome` is `passed`, `failed`, `cancelled`, or `error`. Its `complete` indicates the orchestration reached its terminal result, not that all tests or coverage are complete. `error` and cancellation are incomplete. Failure and unknown must never be converted into passing status.

Each test preserves `name`, `status` (`passed`, `failed`, `skipped`), `durationMs`, `message` (including the original failure detail/stack text), optional `source`, optional one-based `line`, and `attachments`. It adds `id` (SHA-256 of stable phase identity, name and duplicate occurrence index) and `locationRole: "test-source-or-reported-failure"`. Paths supplied by adapters are normalized relative to the workspace where possible. A location can be a test declaration or a failure location; it is **not** a per-production-line execution relation. Missing source/line stay null. The legacy saved adapter report may add a synthetic `runner` test for infrastructure failure; native events deliberately preserve only the original adapter tests.

Artifacts contain absolute `path`, `format`, and byte SHA-256 `sha256`; a read error yields null hash and an `error` field. Ordinary `coverage` artifacts use `format: "lcov"`. Their original adapter `SF:` paths can be absolute; resolve and confine them to the selected workspace. Do not blindly open arbitrary paths from imported event data. `logs` retains hashed references to the existing raw adapter/test stdout/stderr files when present; compilation or infrastructure failures can be explained there even when adapter diagnostics only report missing tests. `resultPath` is the existing saved protocol-v1 adapter result. `resultDirectory` and optional `htmlReport` expose the real generated reports. Hashes must be verified before importing artifacts.

## Precise JaCoCo sidecar

The built-in Maven and Gradle adapters additionally write `<lcov filename>.native.json`. Existing binary `DA:n,0|1` LCOV remains compatible. `coveragePrecisionArtifacts` identifies these sidecars as `jacoco-lines-v1`:

```json
{
  "schemaVersion": 1,
  "format": "jacoco-lines-v1",
  "compiledIdentity": "unknown",
  "perTestAttribution": false,
  "files": [{
    "source": "/workspace/src/main/java/demo/Price.java",
    "lines": [{"line": 7, "ci": 8, "mi": 0, "cb": 2, "mb": 2}]
  }]
}
```

`ci`, `mi`, `cb`, `mb` retain the actual covered/missed instruction and branch counts. Missing XML counters are null, not zero. They are counters, not execution frequencies. A line with covered and missed branches is partially branch-covered even when all its instructions are covered. No per-test attribution is supplied. Custom mini/shop adapters continue to emit their existing aggregate LCOV unless they explicitly adopt a precise sidecar; their flattened binary LCOV cannot recreate discarded JaCoCo branch counters. Coverage from long-running server JVMs also includes startup/health requests and may be incomplete when forcibly terminated.

## Provenance and freshness

`sourceBefore` and `sourceAfter` map workspace-relative source paths to SHA-256 of actual file bytes, including test sources. Supported suffixes: java, kt, kts, scala, rs, ts, tsx, js, jsx, mjs, cjs, go, c, h, cpp, hpp, cc, cs, py, swift. Traversal does not follow directory symlinks and excludes `.git`, `.cherry-test`, `.testit`, `target`, `build`, `node_modules`, `dist`, `.gradle`, `.idea`, `.vscode`, `coverage`, `.next`, `.venv`, and `vendor`. Source files larger than 64 MiB and captures beyond 200,000 files are reported as capture errors. This is source evidence, not a complete build-input manifest: build scripts, classpath, compiler options and dependencies are not attested.

`sourceStable` means the two successfully captured endpoint maps match. It cannot detect an edit followed by restoration between snapshots. It **does not prove that executed bytecode came from those source bytes**. Existing adapter protocol v1 and JaCoCo XML carry neither a compiler input digest nor an executing-class/source mapping. `compiledIdentity` is therefore explicitly `unknown`. Consumers must keep current-source freshness unknown unless they possess independent matching compilation provenance. Changed source hashes make matching old source results stale; an absent hash must never mean current. Retain useful recorded outcomes as historical evidence without painting unverified current lines green/cyan.

## Coverage and test failure are independent

Java and Rust report renderers no longer turn every uncovered line in a failed package into a failed line. `DiffView` still accepts its legacy package-failure parameter, and existing `LineCov.FAIL`/file failure fields remain available for compatibility, but package failure no longer generates that coverage classification. `AdapterView` renders measured-uncovered lines as uncovered regardless of the phase outcome. Actual test failures remain in test/package summaries and genuine failure frames. Deleted diff lines keep no current coverage. This intentionally corrects the previous misleading red failure decoration while retaining report structure and compatible public types.

## Reproduction fixture

`java/cherry-test-cli/src/test/resources/native-ide` is a self-contained Maven/JUnit fixture: two actual tests exercise integer discount calculation. `Price.java` contains partially exercised branches and an uncalled delivery method. Copy it to a disposable workspace. Use JDK 25 for the Java CLI, Maven on PATH (or a Maven wrapper in the copied fixture), JUnit 6.1.3 and JaCoCo 0.8.15 as pinned by its POM.

```sh
cd java
./mvnw -pl cherry-test-cli -am package -DskipTests
CHERRY_TEST_IDE_EVENTS=/tmp/cherry-native-pass.jsonl \
  java --enable-native-access=ALL-UNNAMED -jar cherry-test-cli/target/cherry-test-java.jar \
  --root /path/to/fixture-copy --execution local --no-open --no-banner --no-crap
```

For one genuine assertion failure, change the copied fixture POM's `fixture.fail` property to `true` and rerun with a fresh event path. The fixture intentionally uses Surefire `testFailureIgnore` so JaCoCo report generation executes after assertion failure. Cherry Test reads the actual JUnit failure, returns exit code 1 and marks the phase/run failed; Maven's continued report generation is not treated as test success. Restore the property to false for the passing state. No services, Docker, private projects or production APIs are involved. Surefire normally omits file/line in XML; the sample correctly keeps them absent rather than inventing attribution.

## Optional compiler/process provenance (schema-v1 extension, 15 November 2026)

The JVM adapter supplies a fresh `CHERRY_TEST_PROVENANCE_TOKEN` and `CHERRY_TEST_PROVENANCE_DIR`. Projects may opt into the `cherry-test-provenance` javac plugin and premain agent. No project files are rewritten. Install the module with the CLI reactor (`./mvnw -pl cherry-test-cli -am install`). The javac hook requires fresh class output and an explicit output path; incremental/reused output stays unverified. The process agent must follow JaCoCo in the JVM argument list. Both hooks are inert outside the Cherry capture environment.

Each source in `jacoco-lines-v1` may carry `provenance` with `schemaVersion: 1` and `status: verified`, `mismatch` or `unknown`. Verified objects include `sourceHash`, `classIdentity`, `compilationId`, `compilerIdentity`, `processId`, `sessionId`, `collectionId`, `executionHashes`, and `classFiles: [{path, sha256}]`. Cherry verifies the actual compiler manifests and session receipts, reads `.exec` probes, matches class identities and recomputes counters; mismatched XML cannot certify freshness. Mixed bytecode generations and missing process collections fail closed. Instrumented JVMs must supply a matching shutdown receipt and have exited; an interim dump from a still-running process is not a completed collection. Paths and artifact sizes are bounded.

A consumer must additionally validate its buffer hash, retained class artifacts, run completion and generation. A completed failing test run may have valid coverage; no per-test production attribution is implied. Top-level legacy `compiledIdentity: unknown` remains conservative for mixed providers. Older consumers can ignore the optional per-file object. Kotlin/ECJ/Rust and other providers without capture remain unknown; no counters are fabricated for wholly unobserved classes. This is cooperating local tool evidence, not protection against malicious build scripts.

The opt-in Maven configuration and native end-to-end fixture are in TurboJ `integration-tests/fixtures/cherry-native`; its architecture document describes reproduction and remaining limits.

## Parallel jobs and external JVM services (16 November 2026)

Native events now share the sidecar/container scheduler. Event writes and sequence assignment are serialized; each phase retains project, module, package and runtime identity. `TERM` uses Cherry's existing cancellation handler so it can stop owned containers and flush partial results. Hosts should allow its 30-second process-group grace before forcing termination.

External adapters may opt into precise server evidence by writing `coverage-evidence.json` in their invocation output directory:

```json
{
  "schemaVersion": 1,
  "provider": "jacoco",
  "lcov": "/workspace/target/run/coverage.lcov",
  "sourceRoots": ["/workspace/service/src/main/java"],
  "classDirectories": ["/workspace/target/run/server-build/classes"],
  "executionDirectory": "/workspace/target/run/server-coverage"
}
```

The LCOV artifact must also be listed in the adapter response. All paths must resolve inside the workspace; LCOV and execution data must belong to this invocation's output. The host reads real `.exec` probes and original class files to regenerate instruction/branch counters. It does not sum per-process line counters. The legacy LCOV and HTML reporting interfaces remain available.

The Java runner supplies `CHERRY_TEST_PROVENANCE_TOKEN`, `CHERRY_TEST_PROVENANCE_DIR`, and a non-secret `CHERRY_TEST_COVERAGE_COLLECTION` identity to external adapters when coverage is enabled. Compile into a fresh output directory with the existing javac capture plugin, and start each measured JVM with JaCoCo first, then the provenance agent. Every captured process must exit and supply both its matching execution session and completion receipt. Missing/incomplete evidence stays unknown. A manifest without compiler capture, such as the Kotlin control, preserves precise counters but cannot certify current source identity.

The mini and shop-java samples activate their optional Maven provenance profile only when `CHERRY_TEST_IDE_EVENTS` requests native results. The capture token alone does not activate it, so standalone coverage runs retain their existing dependencies. Install the companion provenance artifact before opting into native results. Effective-model checks cover both environments; actual standalone mini unit tests and native mini/server executions verify the distinction.

### Probe union across phases

Precision sidecars may contain `executionFiles: [{path, sha256}]`. At normal completion, the Java runner verifies these artifacts again and merges their JaCoCo probes only for identical source/class identities. It publishes an optional `coverageUnion` artifact on `run.completed`. Per-phase tests remain separate; coverage is aggregate, never per-test attribution.

A merged proof keeps `collectionId` as its own UUID and lists contributing UUIDs in `collectionIds`. Every session must belong to one of those collections; class files from every compilation are retained for subsequent validation. Existing single-collection proofs are unchanged. New readers replace conflicting per-phase counters with this provider-computed union. Old readers safely retain their conservative unknown state.

An individual collection may have `status: unknown, inputIdentityVerified: true` when compiler/source/process inputs and counters match but a class has no execution record in that collection. This does **not** certify current coverage on its own. A union can certify it only if the merged probes contain every required matching class identity. Missing captures, changed sources/classes, inconsistent artifacts, foreign sessions and incomplete runs never become current through this mechanism.

Qualification included real mini Java/Kotlin services, shop Java's three instrumented JVMs, Rust shop, and JUG's isolated Spring/Quarkus/browser suites. Unsupported compiler provenance remains explicitly unknown. Report aggregation can group HTTP observations and collapse test names differently from the native execution inventory; the event stream preserves each package/phase execution instead of inventing line associations from report totals.

## Native feedback extensions (2026-11-18)

Schema version remains 1. `CHERRY_TEST_IDE_CAPABILITIES` negotiates comma-separated `test-events-v1`, `selectors-v1` and `repositories-v1`; consumers that do not request test events receive only the original run/phase event kinds.

`run.started.capabilities` describes available behavior. `testProgressScope` explicitly distinguishes the Cherry JUnit extension lifecycle from phase-only adapters. New `@CherryTest` runs emit actual JUnit `BeforeEachCallback` / `TestWatcher` observations through a per-phase file channel. The CLI forwards `test.started` and `test.completed` while the adapter runs; observation time and forwarding time are separate. Other adapters do not invent a pending test inventory. The final adapter report remains authoritative. Per-test progress is execution information, never production-line attribution. Install the updated `cherry-test-junit` artifact for these callbacks; an older project dependency continues to supply final results only.

`repositories:[{id,root,worktree}]` includes configured module repositories, including sibling repositories outside the configuration directory. Identity includes the worktree path, not only a commit hash. Phase objects carry `moduleRoot` and `repositoryId`. Source-before/after manifests capture the configuration workspace and explicitly configured source modules. Coverage evidence permits those module source/class roots while report artifacts remain in the owned run directory. Consumers must only trust external roots from a live launched configuration or a validated execution receipt, not from arbitrary imported JSON.

Each final test may contain `selector:{kind,value,phaseKey,runtimeId,adapterId,manifest,exact,reason}`. Exact rerun is currently supported for unambiguous plain Maven JUnit method names and single Cargo test names. Parameterized/dynamic/display-name Java invocations, ordered or shared-instance JUnit classes, custom commands and unsupported adapters are explicitly inexact. They remain eligible for an explicit broader configured-suite rerun.

The optional `CHERRY_TEST_IDE_SELECTION` environment variable names a UTF-8 JSON file:

```json
{"schemaVersion":1,"selectors":[{"kind":"junit-method","value":"demo.PriceTest::discount","phaseKey":"<recorded key>","runtimeId":"maven","adapterId":"maven","manifest":"<actual pom path>","exact":true,"reason":""}]}
```

Selection is limited to 1000 entries and 1 MiB. Before starting tests, the CLI revalidates every identity against the current plan and creates a narrowed plan. Maven supports several exact methods in a phase; Cargo currently accepts one exact name per phase. The acknowledged `run.started.selectionSHA256` hashes the exact request bytes only after validation and application. An older CLI that ignores this environment variable emits no acknowledgement and must not be presented as an exact rerun. The actual reported inventory must match selected names and multiplicity; missing, extra or broadened results produce an infrastructure failure. Coverage remains enabled if the configured phase supplies it, with the narrower scope retained.

The command line and standalone HTML reporting interfaces remain available. These extensions do not change Git or test-to-line attribution semantics. Unknown/missing instrumentation remains unknown.

The lifecycle callback also supplies a reflection-proven declared class/method identity and exact report-name aliases (plain name and declared parameter signature). Only those observed aliases are reconciled to a canonical native test name. `displayName` retains the original XML name; ordinary adapter/HTML reports are not rewritten. This avoids guessing that every parenthesized or decorated test label is a Java method. Ambiguous aliases are not promoted to exact declarations. The runner supplies `CHERRY_TEST_PACKAGE` from the actual configured job.

Full modified Java reactor qualification on 2026-11-18: 425 tests, no failures/errors, two explicitly disabled Docker smoke controls. Actual cross-repository and selected-run validation is retained with the TurboJ consumer evidence. External-source coverage normalization and probe unions are confined to the configuration workspace plus explicitly configured canonical module roots, including symlink checks.
