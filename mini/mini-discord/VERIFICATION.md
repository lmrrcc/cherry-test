# Mini-Discord implementation verification

Component: `mini/mini-discord`, in the Cherry Test repository. Verification date: 2026-10-15.

## Architecture and integration

The component is an independent Java 25 Maven service. It follows the existing Java Mini-Slack conventions: JDK HTTP listeners, virtual threads, dynamic ports, the Maven wrapper, pinned Java container images, and the existing quality gates. It reuses Cherry Test's Maven adapter, JUnit/Surefire/Failsafe result import, JaCoCo coverage import, HTTP JSONL report format, Compose scopes and exported service variables. No runner or report framework was duplicated or changed.

Nineteen source files separate HTTP lifecycle, the Discord domain, OAuth, authorization, resources, Gateway, fixtures, faults and evidence. `DiscordState` provides validated transitions over a replaceable `StateStore`; the shipped implementation uses independent in-memory tables. A per-server lock serializes mutations. Response and event snapshots are detached before network writes. Candidate fixture state is built before replacing live state. Deterministic IDs remain JSON strings, while synthetic credentials are generated for each execution.

OAuth codes, access tokens and refresh tokens bind to one application and user. Joining validates the bot installation, bot permission, user identity, matching application and `guilds.join`. Permissions combine guild roles and Discord overwrite precedence, with hierarchy checks, owner protection, Administrator bypass, screening and timeouts. Removing `MEMBER`, kicking and banning produce distinct state transitions. Gateway implements the documented, tested JSON/WebSocket subset described in the README; the private event recorder also captures events outside that subset.

The root configuration registers `mini-discord`; the component and Compose example use the actual Java runner schema. The CLI source was not changed. The saved task's original 51,694 characters have SHA-256 `a7b93e67fed50b55dbababf9a3bba593cc06f731cda185d83a85477a2e10fe81`; only appended completion boxes may change.

## API coverage

| API | Implemented | Tested | Deliberate boundaries |
| --- | --- | --- | --- |
| OAuth2 | Yes | Yes | Local private consent, authorization-code/refresh/revoke; identify, guilds.join, optional email; no unverified PKCE claim |
| Guilds | Yes | Yes | Supported settings; creation via private controls |
| Guild members | Yes | Yes | Join 201/duplicate 204, pagination, screening, modifications, kick; privileged member intent enforced |
| Roles | Yes | Yes | CRUD, order, grant/revoke, hierarchy, managed-role restrictions, bitfields |
| Channels | Yes | Yes | Text, announcement, category, forum, voice and Stage structures; inheritance and overwrite precedence |
| Invites | Yes | Yes | Create/list/get/revoke, expiry, usage limits, temporary membership; synthetic acceptance via controls |
| Moderation | Yes | Yes | Ban/get/list/unban, kick, timeout/removal, audit, Auto Moderation rule CRUD; no spam classifier |
| Scheduled events | Yes | Yes | CRUD, status transitions, channel permissions, external locations, interested users; no recurring events |
| Messages and forums | Yes | Yes | Bot text/embeds, history/edit/delete, threads and forum posts; no attachments or voice streaming |
| Gateway | Subset | Yes | Discovery, Hello/Identify/Ready/heartbeat, intents, required member/guild/role/channel dispatch and close errors; no compression, resume replay, sharding or full Gateway claim |

Unknown endpoints fail explicitly. Unsupported write fields fail validation. The [README compatibility matrix](README.md#public-compatibility-matrix) describes additional exclusions and links the official contracts. Real server provisioning, production credentials and JUG business logic are outside this component.

## Executed checks

| Check | Result | Evidence |
| --- | --- | --- |
| Earlier isolated host Maven verify | 59 passed; 0 failed; 0 skipped | `target/verify.log`; before the two final role regressions |
| Final focused permission regressions | 9 passed; 0 failed; 0 skipped | `target/permission-final.log` |
| Required end-to-end workflows | All 22 passed | Failsafe XML; `MembershipWorkflowIT` |
| Four-worker parallel unit/HTTP execution | 37 passed; 0 failed; 0 skipped | `target/parallel-validation.log` |
| Optional 10,000-member fixture | Passed, plus all 7 security/reliability tests | `target/fixture-validation.log` |
| Packaged process, health and complete external lifecycle | 1 passed; clean process shutdown | `target/local-smoke.log` |
| Java Cherry Test CLI package | Passed | `target/cherry-cli-build.log` |
| Real local Cherry Test run | 59 passed; HTML, coverage and 22 HTTP scenario traces generated | `target/cherry-cli-final.log`; `target/cherry-test/runs/20261015-162857-c36098/reports/index.html` |
| Final Docker image and network-disabled suite | 61 passed: 39 unit/HTTP/Gateway + 22 E2E; 0 failed; 0 skipped; Maven wall time 92 seconds | `target/docker-build-final.log`; `target/docker-offline-tests.log`; `target/offline-evidence` |
| Final Compose service and internal-network consumer | 1 complete external lifecycle passed; 0 failed/skipped; JUnit 2.270 seconds; Maven 21.546 seconds | `target/container-smoke.log`; `target/container-evidence`; health `healthy`, consumer network `internal=true`, zero service capabilities/no external route verified, stack removed |
| Cherry Test in a network-disabled container | 61 passed; 0 failed/skipped; 86.8% combined line coverage; HTML and 22 HTTP scenario traces generated | `target/cherry-cli-docker.log`; `target/cherry-test/runs/20261015-170639-d8f3e3/reports/index.html` |
| Cherry Test-managed Compose scope | 1 complete lifecycle passed; dynamic localhost endpoint exported; service and both networks removed | `target/cherry-cli-compose.log`; `target/cherry-test-container/runs/20261015-171552-2e18d0/reports/index.html`; run `env.json` |

The Surefire/Failsafe JUnit provider is an explicit plugin dependency so offline runs cannot silently depend on a pre-existing host cache. Maven verification includes Java compilation with Error Prone, dependency convergence, Spotless and JaCoCo. Existing Java CLI packaging was also executed. No Rust launcher source was changed by this task. CI is configured in the root `.github/workflows/mini-discord.yml`; a hosted Actions execution has not been triggered from this workspace. Git-diff coverage is not validated by the isolated container run; its reported combined line coverage comes from JaCoCo.

## Measurements

Actual single-machine observations, not production capacity guarantees. Docker and unrelated workloads were active during these host measurements.

| Measurement | Observed |
| --- | --- |
| Packaged process readiness | 758.98 ms |
| 1,000-user fixture HTTP load | 37.82 ms |
| Member enumeration, 137-item pages | 522.22 ms |
| 3,000 concurrent role grants, revocations and kicks | 9,199.47 ms |
| Bulk elapsed time divided by operation count | 3.07 ms/operation; throughput metric, not individual request latency |
| Successful role-change server duration, 2,010 recorded requests | Mean 14.39 ms; p95 67 ms; millisecond-resolution server timings |
| JVM used heap after bulk operations | 290,468,608 bytes; not peak RSS |
| 10,000-user fixture load | 948.34 ms |
| Host JUnit suites, before the two final role regressions | 14.119 s unit/HTTP + 23.757 s integration |
| Host Maven phases, including build and reports | 53.038 s unit phase + 84 s integration phase |

The 1,000-user and role-latency figures are from the host Cherry Test run, with 3,234 recorded HTTP exchanges and no matching unredacted synthetic credentials. Raw measurements: `target/cherry-test/runs/20261015-162857-c36098/reports/adapters/mini_discord/mini-discord/integration/build/performance.json`, `target/startup-performance.json`, `target/performance-10000.json`, and the captured HTTP evidence. Cherry Test's HTTP view aggregates request durations, so concurrent request totals are not wall-clock benchmark times; the measurements above use explicit timers and JUnit XML. The bulk test checks 1,000 removal events, 2,000 role-update events, enumeration uniqueness, partial failures, successful retries and duplicate rejoining.

The final Docker Cherry Test run used a 512 MiB JVM heap limit and two active processors, with the other Docker workloads temporarily suspended:

| Final Docker measurement | Observed |
| --- | --- |
| 1,000-user fixture load | 21.70 ms |
| Member enumeration | 666.19 ms |
| 3,000 concurrent mutations | 5,534.53 ms |
| Bulk elapsed time divided by operation count | 1.84 ms/operation |
| JVM used heap after bulk operations | 158,856,624 bytes |
| Sum of the seven JUnit suite durations | 34.066 s |

These values are stored in `target/cherry-test/runs/20261015-170639-d8f3e3/reports/adapters/mini_discord/mini-discord/integration/build/performance.json` and the run's JUnit XML. Scanning its 117 JSON/JSONL evidence files found no unredacted synthetic credentials.

## Security evidence

- Host test JVMs run under `scripts/loopback-only.sb`; the kernel denies outbound network connections except loopback. The suite verifies an immediate denied/unroutable connection to a reserved documentation IP. It never probes Discord.
- Docker test execution uses `--network none`; the Compose consumer network is internal. For localhost publication on Docker 29.5.3, the service has a second bridge, but its entrypoint removes IPv4/IPv6 default routes before starting Java. The process runs as UID 10001 with `CapEff=0` and `NoNewPrivs=1`. The smoke workflow asserts these properties and an unreachable documentation IP in `target/container-evidence/network-policy.txt`. Dependency preparation is a separate network-enabled build step. Docker's [port-publishing documentation](https://docs.docker.com/engine/network/port-publishing/) describes the distinction between publication and network reachability.
- Runtime sources contain no Discord SDK or production API client. The only HTTP client in production sources is the health subcommand, fixed to `127.0.0.1`.
- Only `LOCAL_MINI_DISCORD` starts. Other modes and production environment flags are rejected. Synthetic credentials cannot be imported as production tokens.
- Test controls are disabled by default, require a dedicated generated secret, reject browser origins, and bind to loopback unless explicitly configured for an isolated container.
- Tests verify redaction of bot tokens, client secrets, access/refresh tokens, authorization codes and identity fields. Public evidence does not store control credentials.
- Fixtures use synthetic IDs and identities. No real Discord application, guild, account, token, invitation recipient or JUG member data was used. No test or runtime contacted a Discord production API. Official documentation was read separately to verify contracts.

## Future JUG consumer

A future integration should implement approval policy, CSRF state validation, secure token storage and refresh, role mapping, desired-versus-actual reconciliation, retry/backoff and event processing. It should use `MEMBER` revocation for temporary access suspension and choose kick or ban for permanent removal according to its policy. It must separately provision a real Discord application, authorized redirects, guild installation, narrowly scoped bot permissions, role placement, member intent and manual community/screening settings. None of those production resources or JUG client features were implemented here.

## Local environment restoration

The user authorized temporary suspension of 38 existing Docker workloads. Thirty-six paused containers were unpaused after testing; Cassandra and Grafana had been temporarily stopped to release memory. Docker returned its pre-existing HTTP 500/unresponsive-daemon condition during restoration. A complete authorized Docker Desktop stop/start recovered the daemon. Cassandra, Grafana and the two Gandalf containers without automatic restart were explicitly started. Final inspection confirmed all 38 original containers running, none paused and none restarting. Three application health checks were still in their startup period; this confirms process restoration, not a new validation of those unrelated applications. Evidence: `target/docker-paused.json` and `target/docker-restored.json`. Both Mini-Discord test stacks and their networks had already been removed successfully before restoration began.

## File inventory

Outside the component, this task adds `.github/workflows/mini-discord.yml` and registers the package in root `cherry-test.yaml`. Existing unrelated workspace changes are preserved. The component was moved from its earlier temporary submodule location to the requested root path.

### Build, packaging, configuration and documentation

- `.dockerignore`
- `.gitignore`
- `.mvn/jvm.config`
- `.mvn/wrapper/maven-wrapper.properties`
- `Dockerfile`
- `README.md`
- `TASK.md`
- `VERIFICATION.md`
- `cherry-test.yaml`
- `docker-compose.test.yml`
- `examples/compose/cherry-test.yaml`
- `mvnw`
- `pom.xml`

### Production Java

- `src/main/java/mini/discord/Administration.java`
- `src/main/java/mini/discord/ApiError.java`
- `src/main/java/mini/discord/ChannelApi.java`
- `src/main/java/mini/discord/DiscordState.java`
- `src/main/java/mini/discord/Evidence.java`
- `src/main/java/mini/discord/Faults.java`
- `src/main/java/mini/discord/Fixtures.java`
- `src/main/java/mini/discord/Gateway.java`
- `src/main/java/mini/discord/GuildApi.java`
- `src/main/java/mini/discord/Json.java`
- `src/main/java/mini/discord/Main.java`
- `src/main/java/mini/discord/Messages.java`
- `src/main/java/mini/discord/MiniDiscordServer.java`
- `src/main/java/mini/discord/OAuth.java`
- `src/main/java/mini/discord/PermissionEngine.java`
- `src/main/java/mini/discord/Reply.java`
- `src/main/java/mini/discord/StateStore.java`
- `src/main/java/mini/discord/TestApi.java`
- `src/main/java/mini/discord/TestClock.java`

### Fixtures

- `src/main/resources/fixtures/empty.yaml`
- `src/main/resources/fixtures/expired-membership.yaml`
- `src/main/resources/fixtures/jug-lugano-discord.yaml`
- `src/main/resources/fixtures/large.yaml`
- `src/main/resources/fixtures/multiple-guilds.yaml`
- `src/main/resources/fixtures/performance.yaml`
- `src/main/resources/fixtures/unauthorized-joining.yaml`

### Automated tests

- `src/test/java/mini/discord/AdministrationTest.java`
- `src/test/java/mini/discord/ContainerContract.java`
- `src/test/java/mini/discord/GatewayTest.java`
- `src/test/java/mini/discord/HttpFixture.java`
- `src/test/java/mini/discord/MembershipWorkflowIT.java`
- `src/test/java/mini/discord/OAuthTest.java`
- `src/test/java/mini/discord/PermissionsTest.java`
- `src/test/java/mini/discord/SecurityReliabilityTest.java`
- `src/test/java/mini/discord/StateTest.java`

### Executable workflows

- `scripts/cherry-test-compose.sh`
- `scripts/loopback-docker.sb`
- `scripts/cherry-test-docker.sh`
- `scripts/cherry-test.sh`
- `scripts/container-entrypoint.sh`
- `scripts/container-smoke.sh`
- `scripts/local-smoke.py`
- `scripts/loopback-only.sb`
- `scripts/offline-tests.sh`

