# Language adapters

Cherry Test uses executable adapters to run tests. The core owns phase ordering, result validation, coverage merging, exit status, and the browser report. An adapter owns the language-specific commands and report conversion.

A new adapter does not require a new Rust build of Cherry Test. Register its command in `cherry-test.yaml`, then select it on a package or a phase. If that command is `cargo run` and the adapter itself invokes Cargo in the same workspace, pass a separate `--target-dir` so the two Cargo processes do not share a target lock.

## Built-in adapters

| Adapter | Runner | Test results | Coverage |
| --- | --- | --- | --- |
| `cargo` | Cargo | Rust test output | cargo-llvm-cov / LCOV |
| `vitest` | Vitest | Vitest JSON | V8 / LCOV |
| `maven` | Maven Surefire and Failsafe | JUnit XML | JaCoCo XML converted to LCOV |
| `gradle` | Gradle Test | JUnit XML | JaCoCo XML converted to LCOV |
| `cmake` | CMake and CTest | CTest JUnit XML | Clang / LLVM |
| `playwright` | Playwright | Playwright JSON, screenshots, traces | Coverage supplied by the application setup |
| `command` | Your command | Adapter JSON | LCOV supplied by your command |

JUnit imports preserve failed tests, errors, skips, parameterized names, and durations. JaCoCo imports preserve covered and uncovered source lines. They do not invent function or branch measurements that are absent from the source report.

The common phases are `unit`, `integration`, `functional`, and `visual`. The existing `ui` name remains supported. Each phase has a separate output directory. Coverage is merged by source file and line, so a line covered in several phases is counted once in the combined percentage.

## Configuration

```yaml
defaults:
  execution: local
  strict: true

adapters:
  company-tests:
    command: cargo
    args: [run, --target-dir, target/adapter, -p, company-adapter, --quiet, --]
    timeout_secs: 1800

projects:
  shop:
    path: .
    packages:
      prices:
        adapter: maven
        manifest: prices/pom.xml
        kinds: [unit, integration]
        options:
          image: maven:3.9.11-eclipse-temurin-21
        integration:
          adapter: company-tests
          options:
            service: prices
```

Use `--execution local` to use installed toolchains, or `--execution container` to run tools in `options.image`. A package can override the default with `execution`. External adapters receive the selected mode and implement it themselves. Add a `container:` block to harden container execution, as described in [Container settings](#container-settings).

Use `--kinds unit,integration` to select phases. `--kinds mutation` runs a package's `mutation` command on the host and does not collect that output as coverage. A default run does not include it. `--no-crap` skips the CRAP calculation. A normal run includes it. `--strict` fails a run when a selected phase reports zero tests, a skipped test, missing requested coverage, invalid output, or a runner failure. A failed test always fails the phase, even if its adapter reports `success: true`. `--no-coverage` explicitly disables the coverage requirement.

Service coverage requires instrumentation and collection in the adapter or service setup. Starting Docker alone does not measure code. The mini price adapter demonstrates service coverage in integration, functional, and visual phases, with a fresh service process for each phase.

## Container settings

Without a `container:` block, container execution keeps its original behavior: the checkout is mounted read-write at `/workspace`, the container uses the default network, and the timeout is 1800 seconds. A block under `defaults` or under a package turns on hardened execution for built-in adapters. A package block overrides the defaults block field by field. A list or map in the package block replaces the whole value from the defaults block. Unknown keys are rejected.

```yaml
defaults:
  execution: container
  container:
    image: maven:3.9.11-eclipse-temurin-25
    pull: never
    network: none
    user: host
    cap_drop: [ALL]
    no_new_privileges: true
    read_only_root: true
    tmpfs: [/tmp]
    cpus: 2
    memory: 4g
    pids: 1024
    shm_size: 256m
    timeout: 30m
    workspace: ro
    writable:
      - prices/generated
      - {path: prices/.cache, seed: copy}
    mounts:
      - {source: ../shared-fixtures}
      - {source: ../datasets, target: /data, read_only: true}
    caches:
      - {volume: cherry-test-m2, target: /tmp/home/.m2}
    env:
      pass: [CI, MAVEN_OPTS]
      set: {LANG: C.UTF-8}

projects:
  shop:
    path: .
    packages:
      prices:
        adapter: maven
        manifest: prices/pom.xml
        kinds: [unit, integration]
        container:
          network: bridge
          timeout: 45m
```

| Field | Default when the block is present | Effect |
| --- | --- | --- |
| `image` | `options.image` | Image that runs the tools. One of the two is required. |
| `pull` | `never` | `docker run --pull`: `never`, `missing`, or `always`. |
| `network` | `none` | `none` or `bridge`. Only `bridge` reaches services on host ports through `host.docker.internal`. |
| `user` | the image user | `host` runs as your user and group id with `HOME=/tmp/home`. Any other value, such as `1000:1000`, is passed to `--user`. |
| `cap_drop` | `[ALL]` | Linux capabilities removed with `--cap-drop`. |
| `no_new_privileges` | `true` | Adds `--security-opt no-new-privileges`. |
| `read_only_root` | `false` | Makes the image filesystem read-only. List the paths that need writes in `tmpfs`. |
| `cpus`, `memory`, `pids`, `shm_size` | not set | `--cpus`, `--memory`, `--pids-limit`, and `--shm-size`. |
| `tmpfs` | `[]` | Absolute container paths mounted as tmpfs, such as `/tmp` or `/run:size=16m`. |
| `timeout` | `1800s` | Limit for each package phase: seconds, or a value such as `90s`, `30m`, or `2h`. |
| `workspace` | `ro` | Mount mode of the checkout at `/workspace`: `ro` or `rw`. |
| `writable` | `[]` | Workspace-relative directories that the job may write. Each one is replaced by a fresh directory for that job, `<run>/jobs/<project>/<package>/<phase>/w/<path>`. With `seed: copy`, the current contents are copied in first. The checkout itself is not changed. |
| `mounts` | `[]` | Extra bind mounts: `source`, optional `target`, and `read_only` (default `true`). A relative `source` starts at the configuration root and may point outside it. Without `target`, the source keeps its position relative to `/workspace`: `../shared-fixtures` next to the checkout is mounted at `/shared-fixtures`. Sources more than one level above the checkout need a `target`. |
| `caches` | image defaults | Named volumes: `volume` and absolute `target`. Without `caches`, the `maven:`, `gradle:`, and `cherry-test-rust:` images get the same dependency volumes as before. |
| `env` | not set | `pass` copies named variables from your environment. `set` adds fixed values. |

The package phase output directory is always mounted read-write, so reports, coverage, and Maven or Gradle build directories under it work with a read-only workspace. The Cargo adapter also keeps its shared target directory, `target/cherry-test/cargo/container`, writable. Tools that write elsewhere in the checkout need a `writable` entry, for example a lock file update, a Vite cache under `node_modules/.vite`, or a CMake build tree outside the output directory.

With `network: none`, the container cannot reach Compose services or download dependencies. Use `network: bridge` for integration tests that call services, and fill dependency caches before running offline. With `user: host`, the home directory is `/tmp/home`. Point cache targets there, and add `/tmp` to `tmpfs` when the root filesystem is read-only.

Each container is named `ct-<run-id>-<project>-<package>-<phase>`. Characters other than letters, digits, `_`, `.`, and `-` become `-`. Containers carry the labels `cherry-test.run=<run-id>`, `cherry-test.job=<project>/<package>/<phase>`, and `cherry-test.pid=<pid>`, where the pid belongs to the Cherry Test process that owns the run. When a phase times out or the run is cancelled, Cherry Test runs `docker kill` on the container before it stops the Docker client. When the run ends, it removes any container labelled with its run id. When a run starts, it removes containers of other runs whose owning process no longer exists. Containers without an owner label are left alone. These cleanup steps run only when a package uses container execution with a `container:` block.

External adapters receive the resolved settings in the request's `container` field and the owner pid in `CHERRY_TEST_OWNER_PID`. If an external adapter starts containers, use the same name and labels so that cleanup also covers them.

## Executable protocol, version 1

Cherry Test starts the registered command with its configured arguments followed by `run`. It sends one JSON request on standard input. The adapter writes one JSON response on standard output. Send progress and logs to standard error or files in the output directory.

Request fields:

| Field | Meaning |
| --- | --- |
| `protocol` | Must be `1` |
| `project`, `package`, `phase` | Selected test target |
| `root`, `manifest`, `cwd`, `output` | Absolute workspace, manifest, working, and output paths |
| `execution` | `local` or `container` |
| `coverage`, `strict` | Requested checks |
| `args` | Phase arguments |
| `env` | Runtime settings and service endpoints |
| `options` | Adapter-specific settings from the package and phase |
| `container` | Resolved container settings; present only for container execution with a `container:` block |

Response fields:

| Field | Meaning |
| --- | --- |
| `protocol` | Must be `1` |
| `success` | Whether the runner completed successfully |
| `tests` | Individual test results |
| `coverage` | Absolute paths to LCOV files inside `output` |
| `diagnostics` | Setup, runner, or collection errors |

Each test has `name`, `status` (`passed`, `failed`, or `skipped`), `duration_ms`, optional `message`, optional `source` and `line`, and an `attachments` list. Each attachment has `name`, `path`, and `media_type`.

Test names must be unique within a package phase. All attachment and coverage paths must resolve to real files inside `output`; symlinks cannot escape it. Copy expected screenshots into `output` before returning them. Do not return successful invented tests when a tool did not run. Return a failed response with a diagnostic instead.

LCOV files need `SF`, `DA`, and `end_of_record` records. Only source paths inside the selected workspace contribute to its coverage. External dependency sources are excluded. Built-in container paths under `/workspace` are translated back to the checkout. Malformed, empty, or truncated coverage is an error.

Built-in adapters also expose `describe` and `discover`:

```bash
cherry-test adapter maven describe
printf '{"root":"/absolute/workspace"}' | cherry-test adapter maven discover
```

Discovery recognizes Cargo, package.json, Maven, Gradle, and CMake manifests. Review generated configuration: unusual source layouts, integration tasks, toolchain images, and service scopes need project-specific settings.

## Runner conventions

Maven projects should honor `-Dtestit.buildDirectory=...` as their build directory and `-DskipUnitTests=true` for the integration phase. Configure Surefire for unit tests, Failsafe for integration tests, and JaCoCo XML reports. The sample POM shows this setup.

Gradle projects should honor `-PtestItBuildDir=...` and provide `test`, `integrationTest`, `jacocoTestReport`, and `jacocoIntegrationTestReport`. Reports must be written beneath that build directory. Source roots are `src/main/java` and `src/main/kotlin`.

CMake projects should honor `CHERRY_TEST_COVERAGE` and label CTest tests `unit` or `integration`. The built-in adapter uses Clang coverage and exports the `native-tests` executable by default. Override its relative build path with `options.coverage_binary`.

The command adapter receives `CHERRY_TEST_ADAPTER_OUTPUT` and must write its response to `result.json` there. In non-strict mode, legacy commands without a report retain exit-status behavior, explicitly labeled as a command result.

## Reports and cleanup

The main HTML report links to `phases.html`. That page shows each package phase, failures, screenshots, trace links, and covered or uncovered source lines. It works from a local directory or an unpacked CI artifact and needs no IDE.

Adapter requests, responses, command output, and native reports remain in `reports/adapters/<project>/<package>/<phase>/`. Keep that directory with the main report when uploading CI artifacts.

Adapters must stop their own services and remove temporary containers in a cleanup block, including on failure. The sample uses a unique Compose project and temporary PostgreSQL data for each run. Cherry Test sends termination to timed-out adapter process groups before forcing them to stop. Cleanup failures must be reported as failures.

For a complete external adapter, see the shop adapter in `app/shop/tools/shop-adapter`. The mini price sample remains a separate multi-language checkout.
