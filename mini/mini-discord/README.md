# Mini-Discord

Mini-Discord is a stateful Java 25 simulator for Discord community automation. It runs locally with synthetic identities, applications, credentials and guilds. It has no Discord SDK, outbound Discord client, database or dependency on a real Discord account. JUG Lugano is represented by a reusable synthetic fixture and an executable membership contract; no JUG website, production bot or reconciler is included.

The original implementation request is preserved in [TASK.md](TASK.md). Only its completion checkboxes may be changed. Executed checks, measurements and delivery details are recorded in [VERIFICATION.md](VERIFICATION.md).

## Repository and architecture

The component lives at `mini/mini-discord` in the Cherry Test repository, as requested. Existing Mini-Slack infrastructure was inspected at `app/mini/mini-slack`; the new component does not require moving that submodule. It is an independent Maven package, rather than a module of the Cherry Test Java reactor.

The implementation follows Mini-Slack's JDK HTTP server, virtual-thread lifecycle, dynamic ports, Maven wrapper and quality-gate configuration. It reuses the existing Cherry Test Maven adapter, Surefire/Failsafe import, JaCoCo coverage import, integration JSONL schema, Compose lifecycle and service environment variables. Slack-specific domain code is not copied into Discord. The runtime has no dependency on Cherry Test; any HTTP consumer can use it.

| Component | Responsibility |
| --- | --- |
| `MiniDiscordServer`, `Main`, `Reply` | HTTP transport, lifecycle, loopback binding, request bounds, immutable response snapshots, endpoint discovery |
| `DiscordState`, `StateStore`, `TestClock` | Separate identities and memberships; replaceable storage boundary; deterministic string IDs; injectable time |
| `OAuth` | Consent requests, single-use codes, client authentication, user grants, refresh, expiration and revocation |
| `PermissionEngine` | Guild bitfields, owner/Administrator bypass, hierarchy, channel overwrites, screening and timeouts |
| `GuildApi`, `ChannelApi`, `Administration`, `Messages` | Public v10 resource contracts and validated mutations |
| `Gateway` | JSON WebSocket subset, Identify, heartbeat, intents and dispatch |
| `TestApi`, `Fixtures` | Private fixture loading, synthetic actions, reset, state inspection and test clock |
| `Faults`, `Evidence` | Deterministic failures and buckets; redacted request/event evidence |

`DiscordState` owns domain transitions shared by the protocol adapters. It receives a `StateStore` containing resource collections; the supplied implementation allocates independent in-memory tables. A future store must preserve mutation atomicity under the server lock and snapshot isolation; no database implementation is included. Each server owns its maps, clock, locks, OAuth grants, evidence and Gateway connections. Mutations are serialized per instance, and response/event objects are detached before the lock is released. Fixture imports build a candidate state before replacing the live state. The storage implementation is intentionally in memory; persistence would be implemented behind this boundary, not in HTTP handlers.

Each test starts its own server on dynamic ports. A Compose scope uses a unique project name. Delays and disconnected responses do not hold the state lock. Reset clears resources, credentials, faults, audit history and request/event evidence, and closes Gateway sessions with code 4009. The clock retains its current value; fixtures schedule dates relative to that value. A reset invalidates all previously issued credentials.

## Build and test

Requires JDK 25 and the included Maven wrapper. Runtime libraries are Jackson 3.2.3, SnakeYAML Engine 3.0.1 and JSpecify. Tests use JUnit 6.1.3 and AssertJ. Compiler warnings are errors; the build also runs Error Prone, Spotless and JaCoCo.

From this directory, a prepared local Maven cache supports:

```sh
sh scripts/offline-tests.sh spotless:apply verify
```

On macOS this runs Maven and its child JVMs under `scripts/loopback-only.sb`: the operating system denies outbound networking except loopback. IPv4 is selected for compatibility with that policy. `SecurityReliabilityTest` requires the isolated-test flag and verifies an immediate socket-policy or missing-route failure against a documentation-only IP address. It does not probe a Discord host. Tests never follow redirects to external locations.

On Linux, build the test image during dependency preparation, then execute it without a network:

```sh
docker build --target offline-tests -t mini-discord-tests .
docker run --rm --network none mini-discord-tests verify
```

Dependency and base-image acquisition happen before the isolated execution. The preparation build explicitly skips unit and integration tests, while resolving the JUnit providers and formatter needed by the offline verify phase. Maven's `-o` flag alone is not a network policy. The test suite intentionally requires an isolated execution, including when run by Cherry Test.

## Local execution and endpoint discovery

`Main` reads these environment variables:

| Variable | Default / meaning |
| --- | --- |
| `MINI_DISCORD_MODE` | `LOCAL_MINI_DISCORD`; all other modes fail startup |
| `MINI_DISCORD_BIND` | `127.0.0.1`; `::1` is also allowed |
| `MINI_DISCORD_PORT` | `0`, allocated dynamically |
| `MINI_DISCORD_GATEWAY_PORT` | `0`, separate dynamic WebSocket listener |
| `MINI_DISCORD_CONTAINER` | `false`; explicit opt-in required for wildcard binding |
| `MINI_DISCORD_TEST_API` | `false`; opt-in required to enable controls |
| `MINI_DISCORD_TEST_SECRET` | At least 32 characters when controls are enabled |
| `MINI_DISCORD_FIXTURE` | Optional YAML recipe path loaded before readiness |
| `MINI_DISCORD_CLOCK` | `2030-01-01T00:00:00Z`; controlled UTC starting instant |
| `MINI_DISCORD_ENDPOINT_FILE` | Optional file receiving the endpoint map, without credentials |

The packaged process is exercised by `scripts/local-smoke.py`. It allocates dynamic ports, waits for readiness, runs the external-process membership contract, records startup time and terminates the process on success or failure.

An embedded caller can construct `MiniDiscordServer` with a `Clock`, or use `MiniDiscordServer.local()` with a deterministic `TestClock`. `seed(Fixtures.load("jug-lugano-discord"))` returns the private fixture manifest, including newly generated synthetic credentials. `environment("mini_discord", "discord")` exposes:

```text
CHERRY_TEST_MINI_DISCORD_DISCORD_URL
CHERRY_TEST_MINI_DISCORD_DISCORD_HOST
CHERRY_TEST_MINI_DISCORD_DISCORD_PORT
DISCORD_API_BASE_URL
DISCORD_OAUTH_BASE_URL
DISCORD_GATEWAY_URL
DISCORD_TEST_CONTROL_URL
```

Public REST uses `http://127.0.0.1:<port>/api/v10`; authorization uses `/oauth2/authorize`. The control URL is for the test harness only. Pass bot/user credentials separately to the consumer; do not pass the control secret to application code.

## Authentication and OAuth

Bot requests require `Authorization: Bot <synthetic-token>`. OAuth identity requests require `Authorization: Bearer <synthetic-access-token>`. The token classes are not interchangeable. Unknown, disabled or revoked credentials fail authentication. Tokens are always generated by Mini-Discord with a `mini_discord_` prefix; imported production tokens are not supported.

Create an application through `POST /_test/applications` (alias `/bots`) with `name`, `redirect_uris`, optional `authorized_scopes`, and optional `members_intent`. The private response returns `client_id`, `client_secret`, `bot_token` and `bot_user_id`. Install it using `POST /_test/installations` with `application_id`, `guild_id`, `requested_permissions`, `authorized_permissions`, optional `roles` and `position`. Authorization cannot exceed requested permissions. Administrator is never granted implicitly.

The authorization-code flow is executable:

1. `GET /oauth2/authorize` with `client_id`, exact registered `redirect_uri`, `response_type=code`, `scope` and optional `state`.
2. The simulator redirects to a private local consent resource. Extract its request ID; no Discord login page is impersonated.
3. `POST /_test/oauth/authorize` with `request_id`, `user_id` and optional selected `scopes`, or `/oauth/deny` to deny consent.
4. The private consent response contains a callback `redirect_uri` with a code or error and the preserved state.
5. Exchange the code at `POST /api/v10/oauth2/token`, using `application/x-www-form-urlencoded` and client credentials in the form or HTTP Basic authentication.
6. Retrieve identity at `GET /api/v10/users/@me`.

Scopes are `identify`, `guilds.join` and `email`. Email is returned only with an authorized `email` scope and is never the identity key. All snowflakes are JSON strings. Redirect URIs must be loopback and match the registered value exactly. The consumer must validate CSRF state; the simulator preserves it, including a deliberate mismatch test.

Consent requests and codes expire after 300 simulated seconds. Codes are single-use. Access tokens expire after 3,600 seconds. Refresh tokens expire after 30 simulated days and rotate after use. Revoking an access or refresh token invalidates the authorization's grants for that application/user. Test controls can revoke consent. The unversioned `/api/oauth2/token` and `/api/oauth2/token/revoke` aliases are also supported. PKCE, implicit grants, client-credentials grants and external redirects are explicitly unsupported.

## Membership, roles and protected channels

Adding a member requires a bot installed in that guild, `CREATE_INSTANT_INVITE`, and an unexpired user token with `guilds.join` issued to the same application and requested user. A new member returns 201; an existing member returns 204 without duplication. Adding a member never creates an identity.

Membership screening is independent of platform approval. A screened guild joins new users as `pending`; `/_test/members/{id}/complete-screening` completes it. Explicit initial roles in the add-member request bypass screening, as documented by Discord. Changing roles later does not pretend that JUG has approved a person.

| Operation | Guild membership | Protected access | Can rejoin? |
| --- | --- | --- | --- |
| Remove `MEMBER` | Preserved | Removed by overwrites | Already a member |
| Kick | Removed | Removed | Yes, through an authorized join or invite |
| Ban | Removed | Removed | Only after unban |

The permission engine combines guild base permissions and roles, then applies everyone denies/allows, aggregated role denies/allows and member-specific denies/allows. Role positions constrain management actions but do not change overwrite precedence. Owner and explicit `ADMINISTRATOR` bypass channel restrictions. Timeouts retain viewing/history but prevent communication. Private threads additionally require thread membership or management permission.

New roles start below the bot and inherit `@everyone` permissions when no bitfield is supplied. Setting the `MANAGE_ROLES` bit in a channel overwrite requires actual `ADMINISTRATOR`; ordinary role-management permission is insufficient.

Category overwrites are copied to synchronized children. Changes propagate while children remain synchronized. Explicit child overwrites detach them; `lock_permissions` synchronizes on repositioning. Deleting a category does not make its formerly protected children public.

Ordinary OAuth bearer tokens cannot read arbitrary Discord channel history. Tests verify ordinary-user visibility through the clearly private `/_test/read-channel` simulated-user action, and independently exercise the same permission engine through public channel APIs using a second synthetic bot with member-level roles. No undocumented public user-token API is invented.

## Fixtures

YAML recipes are in `src/main/resources/fixtures`; they contain no credentials or production IDs.

| File | State |
| --- | --- |
| `empty.yaml` | Owner, bot and the two entry channels |
| `jug-lugano-discord.yaml` | Roles, public/member/staff channels, tagged forum, voice channels, five future events and standard community identities |
| `unauthorized-joining.yaml` | An unapproved user has accepted a synthetic invite, with one recorded use and no `MEMBER` role |
| `expired-membership.yaml` | Existing member without the approval role |
| `large.yaml` | 1,000 ordinary synthetic members plus owner and bot |
| `performance.yaml` | Optional 10,000 ordinary members plus owner and bot |
| `multiple-guilds.yaml` | Two independent guilds, bot installed only in the first |

The standard community contains one staff user, one moderator, five students, five professionals, two mentors, one speaker, a pending user, an unapproved user, a banned user, a kicked identity and an expired member. Category labels such as `STUDENT` do not replace `MEMBER`. `ADMIN` has controlled management permissions and does not include Discord `ADMINISTRATOR`. Guild ownership represents `OWNER`.

Fixture role/channel recipes feed the actual permission engine. `screening`, `members` (0–10,000) and `guilds` (1–10) are configurable. Invalid fixture input leaves the previous state intact.

## Private test API

Every route requires `X-Mini-Discord-Secret`. Browser Origin headers are rejected. Controls are disabled unless explicitly enabled, even on loopback.

| Route | Operation |
| --- | --- |
| `POST /_test/reset`, `POST /_test/seed` | Clear state or replace it atomically from a recipe |
| `GET /_test/state` | Sanitized snapshot, including memberships, roles, bans and scheduled events |
| `POST /_test/users`, `/guilds`, `/applications`, `/bots` | Create synthetic identities, guilds or applications |
| `POST /_test/installations` | Explicit bot installation and permissions |
| `PATCH /_test/applications/{id}` | Toggle enabled/revoked/member-intent authorization |
| `POST /_test/oauth/authorize`, `/deny`, `/revoke` | Consent, denial and revocation |
| `POST /_test/members/{id}/join`, `/leave`, `/complete-screening`, `/disconnect` | Simulated user actions, with `guild_id`; disconnect removes an unprivileged temporary invite member |
| `POST /_test/invites/{code}/accept` | Simulated invitation acceptance with `user_id` |
| `POST /_test/permissions`, `/read-channel` | Compute user/channel permissions or exercise visibility |
| `POST`, `DELETE /_test/scheduled-events/{id}/interest` | Synthetic subscription action with guild and user IDs |
| `POST /_test/clock` | Advance `advance_seconds`, without sleeping |
| `POST`, `DELETE /_test/faults` | Configure or clear faults and rate-limit rules |
| `POST /_test/rate-limits` | Configure deterministic bucket windows |
| `GET`, `DELETE /_test/requests`, `/_test/events` | Independently inspect/clear captured HTTP and dispatch history |

Application creation accepts only generated credentials. Fixture/state/consent controls are not Discord endpoints and must not be used by production integration code.

## Faults and rate limits

A fault rule accepts `route`, `fault` (or a supported `status` of 429/500/503), `failNext`, `after`, `always`, `retryAfterSeconds`, `delayMs` and `global`. Routes use templates such as `PUT /guilds/{guildId}/members/{userId}`. Matching rules are consumed in insertion order. Counts and windows are deterministic.

Supported faults are invalid/expired tokens, missing scope/permissions, unknown guild/user/member/role, hierarchy violation, banned user, OAuth denial/code expiration, 429, 500, 503, timeout, network disconnect and response delay. `ALREADY_MEMBER` requires a pre-existing member and still validates the actual public request; it cannot fabricate a successful membership. Timeout/disconnect faults occur before mutation. Delays are bounded to 30 seconds; normal tests use the clock for expiry and retry windows.

Natural limit rules accept `route`, `bucket`, `limit`, `windowSeconds`, `global` and `shared`. Buckets distinguish major guild/channel resources and authenticated principals unless configured shared. HTTP 429 supplies `Retry-After`, rate-limit reset/bucket/remaining headers and Discord's `retry_after`/`global` body. No production quota is hardcoded.

## Gateway subset

`GET /api/v10/gateway` and `/gateway/bot` discover the WebSocket listener. The simulator supports v10 JSON, Hello, Identify, Ready, guild initialization, Heartbeat/ACK, monotonically increasing session sequence numbers and close/error behavior. Specify intents explicitly. `GUILDS` is bit 0; `GUILD_MEMBERS` is bit 1 and must also be enabled on the synthetic application. Unauthorized privileged intents close with 4014; unsupported intents close with 4013. Member enumeration also requires application-level member-intent authorization.

Live delivery covers `GUILD_MEMBER_ADD/REMOVE/UPDATE`, `GUILD_ROLE_CREATE/UPDATE/DELETE`, `CHANNEL_CREATE/UPDATE/DELETE` and `GUILD_CREATE/UPDATE`. Events are filtered by installation, intents and channel visibility. All additional generated domain events remain available through the private recorder. `MESSAGE_CONTENT` is neither requested nor required.

This is not a complete Gateway implementation: compression, ETF, multi-shard operation, session resume/replay, fragmented client frames, presence, voice, message intents and member-chunk requests are unsupported. Resume closes with 4007. A stalled heartbeat closes the connection; a full subscriber queue closes with 4008 rather than silently losing events. The private recorder is not presented as public Gateway delivery.

## Public compatibility matrix

All paths below are relative to `/api/v10`, except authorization. The corresponding HTTP tests and 22 workflow scenarios run against actual listeners.

| API | Implemented and tested | Limits |
| --- | --- | --- |
| OAuth2 | Authorize, token exchange/refresh/revoke, current user | Local private consent; scopes listed above |
| Guilds | `GET/PATCH /guilds/{id}` | Supported settings only; guild creation is private |
| Members | List with `limit`/`after`; get/add/modify/kick | Nickname, roles, mute/deaf, timeout; no voice transport |
| Roles | List/create/modify/delete; reorder; member grant/revoke | Name, bitfield, color, hoist, mentionable; managed-role and hierarchy enforcement |
| Channels | Guild list/create/reposition; get/modify/delete; overwrite put/delete | Text, category, announcement, forum, voice and Stage structures |
| Invites | Channel create/list, guild list, public get, authorized delete | Expiration, use limits, temporary membership; acceptance is private |
| Moderation | Kick, ban/list/get/unban, timeout/remove timeout, audit log | Auto Moderation rule CRUD and validation; no Discord spam classifier or automatic rule execution |
| Scheduled events | List/create/get/update/delete, status transitions, interested-user enumeration | External, voice and Stage targets; no recurring schedules or event images |
| Messages | Create/get/history/edit/delete text and embeds | Bot authorship and visibility enforced; no attachment/media storage |
| Threads/forums | Create/get/update/delete, thread messages, basic membership and forum posts/tags | No archived-thread discovery, advanced tag editing or full client behavior |
| Gateway | Discovery and the membership/guild/role/channel subset above | Explicit subset, not complete Discord Gateway compatibility |

The supported route families are:

| Path, relative to `/api/v10` | Methods |
| --- | --- |
| `/users/@me`, `/users/{user}` | GET |
| `/guilds/{guild}` | GET, PATCH |
| `/guilds/{guild}/members` | GET |
| `/guilds/{guild}/members/{user}` | GET, PUT, PATCH, DELETE |
| `/guilds/{guild}/members/{user}/roles/{role}` | PUT, DELETE |
| `/guilds/{guild}/roles` | GET, POST, PATCH (positions) |
| `/guilds/{guild}/roles/{role}` | PATCH, DELETE |
| `/guilds/{guild}/channels` | GET, POST, PATCH (positions) |
| `/channels/{channel}` | GET, PATCH, DELETE, including supported thread channels |
| `/channels/{channel}/permissions/{overwrite}` | PUT, DELETE |
| `/channels/{channel}/invites`, `/guilds/{guild}/invites` | GET; POST on the channel route |
| `/invites/{code}` | GET, DELETE |
| `/guilds/{guild}/bans`, `/guilds/{guild}/bans/{user}` | GET; PUT, DELETE on the user route |
| `/guilds/{guild}/audit-logs` | GET |
| `/guilds/{guild}/scheduled-events` | GET, POST |
| `/guilds/{guild}/scheduled-events/{event}` | GET, PATCH, DELETE |
| `/guilds/{guild}/scheduled-events/{event}/users` | GET |
| `/guilds/{guild}/auto-moderation/rules` | GET, POST |
| `/guilds/{guild}/auto-moderation/rules/{rule}` | GET, PATCH, DELETE |
| `/channels/{channel}/messages` | GET, POST |
| `/channels/{channel}/messages/{message}` | GET, PATCH, DELETE |
| `/channels/{channel}/threads`, `/channels/{channel}/messages/{message}/threads` | POST |
| `/channels/{thread}/thread-members`, `/channels/{thread}/thread-members/{user}` | GET on the collection; PUT, DELETE on the user route, including `@me` |
| `/gateway`, `/gateway/bot` | GET |

Unknown routes return HTTP 404 with a documented simulator not-found response. Unsupported write fields return HTTP 400/code 50035. Supported operations never return a precomputed success. Discord's documentation does not specify every error response for every invalid input; generic validation errors and some malformed-input details are deliberately bounded simulator behavior, not a claim of byte-for-byte parity with production.

Unsupported platform areas include webhooks, interactions/application commands, DMs, voice streaming, media processing, attachments, billing, account login, discovery, emoji/sticker management and full guild safety/onboarding configuration. A future real server must separately configure Community eligibility, screening content, privileged intents, application installation and any settings without supported public automation APIs.

Contracts were checked against the official [reference](https://docs.discord.com/developers/reference), [OAuth2](https://docs.discord.com/developers/topics/oauth2), [guild](https://docs.discord.com/developers/resources/guild), [permissions](https://docs.discord.com/developers/topics/permissions), [channel](https://docs.discord.com/developers/resources/channel), [message](https://docs.discord.com/developers/resources/message), [invite](https://docs.discord.com/developers/resources/invite), [scheduled event](https://docs.discord.com/developers/resources/guild-scheduled-event), [Auto Moderation](https://docs.discord.com/developers/resources/auto-moderation), [Gateway](https://docs.discord.com/developers/events/gateway) and [rate-limit](https://docs.discord.com/developers/topics/rate-limits) documentation. No live Discord contract calls or recordings were used.

## Cherry Test, evidence and Docker

`cherry-test.yaml` uses the existing `maven` adapter and `unit`/`integration` kinds. `testit.buildDirectory` isolates each Maven phase. Surefire/Failsafe provide actual outcomes and durations; JaCoCo supplies coverage. `scripts/cherry-test.sh` executes the Java Cherry Test JAR under the same loopback-only policy on macOS and uses the isolated Docker image on Linux. The root Cherry Test configuration also registers `mini-discord`.

End-to-end test names use Cherry Test's `{project}_project_{suite}_suite_{test}_test` contract. HTTP JSONL rows are joined to these outcomes. Unit HTTP details are written separately under `unit-http` so they do not inflate the report's test count. `reports/mini-discord` contains captured requests and events; failed assertions include sanitized recent exchanges. Raw OAuth codes, tokens, secrets, authorization headers, email and profile fields are redacted. The request log has a 100,000-entry limit and evidence export fails explicitly on overflow. Audit, request and event logs are separate. Reset discards their instance history.

The Docker runtime uses a pinned Java 25 JRE image, a 256 MiB maximum Java heap, UID 10001, a read-only filesystem and a Java health check. HTTP and Gateway use container ports 8080 and 8081. Host ports are dynamic and published only on `127.0.0.1`. Test JVMs use a 512 MiB maximum heap and two active processors. In-network consumers use `http://mini-discord:8080` and `ws://mini-discord:8081`; host consumers must use Compose's published Gateway mapping.

`scripts/container-smoke.sh` generates a control credential and unique Compose project, builds service/consumer images, waits for health, runs the external contract and removes the stack. The consumer uses an `internal: true` network. The service also joins a bridge for localhost port publication: Docker 29.5.3 did not publish ports on an internal-only network in the verified environment. Before Java starts, `container-entrypoint.sh` removes every IPv4/IPv6 default route, verifies external routing is unavailable, and switches to UID 10001 with no effective capabilities. Only this initialization receives `NET_ADMIN`, `SETUID` and `SETGID`; `no-new-privileges` prevents the service from regaining them. The smoke script verifies the running process UID, zero effective capabilities and the missing external route, recording `network-policy.txt`. No host or daemon firewall configuration is changed.

`examples/compose/cherry-test.yaml` supplies the actual Cherry Test service/scope configuration. On macOS, `sh scripts/cherry-test-compose.sh` runs that scope under a policy permitting only loopback and the selected local Docker Unix socket; it generates a dedicated control secret for the run. Cherry Test's existing Testcontainers provisioner can consume the same scope configuration; this component adds no separate Testcontainers dependency.

The Cherry Test workflow `.github/workflows/mini-discord.yml` prepares the existing Java CLI and test images, runs all tests with `--network none`, exercises the internal Compose network, generates the real Cherry Test HTML report in a network-disabled container and uploads reports and coverage. There is no CI credential for Discord and no fallback to a real endpoint.

With a prepared Maven cache, from the repository root package the existing CLI and execute the component's configuration:

```sh
cd java
./mvnw -o -B -ntp -pl cherry-test-cli -am -DskipTests package
cd ../mini/mini-discord
sh scripts/cherry-test.sh
```

The same report workflow can be run explicitly in Docker on either platform:

```sh
sh scripts/cherry-test-docker.sh
```

`CHERRY_TEST_JAR` may point to another already-built Cherry Test Java CLI. The scripts do not install a separate runner. Docker builds normally use the pinned JDK base; `MINI_DISCORD_BUILD_IMAGE` can select a previously prepared local JDK/dependency image to reuse its Maven cache. Runtime packaging still uses the pinned JRE. For a Compose smoke run, pass that variable in the environment; for `docker build`, pass it as a build argument.

## Troubleshooting and future consumer contract

An offline Maven failure about missing artifacts means the dependency-preparation step has not populated that environment's cache. An immediate socket permission error on loopback under macOS requires the script's IPv4 JVM option. A 401 usually indicates the wrong token type, expiry or revocation. A 403 may indicate installation, scope, privileged intent, channel overwrites or role hierarchy; a valid token alone is insufficient. Empty member pages require checking `after` and `limit`; enumeration defaults to one member, as documented.

For an expired JUG subscription, remove `MEMBER`; do not equate retained guild membership with retained access. A future JUG backend should own approval policy, CSRF validation, encrypted token persistence/refresh, role selection, retry/backoff and desired-versus-actual reconciliation. It will need separate real Discord application credentials, registered production redirects, an installed bot with narrowly granted permissions, adequate role position and authorized member intent. Those real resources are deliberately outside this implementation.
