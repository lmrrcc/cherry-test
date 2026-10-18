salva questo prompt su un file e usalo per implementare ogni singola parte end-end puoi modificare questo file solo per mettere check quando le cose sono fatte devi implementer tutto completamente Per Cherry Test costruirei Mini-Discord come simulatore locale completo del ciclo di vita di una community Discord, riutilizzando l'architettura Java già sviluppata per Mini-Slack.

L'obiettivo è poter testare automaticamente tutte le operazioni che JUG Lugano dovrà eseguire: registrazione, collegamento dell'identità Discord, ingresso nel server, assegnazione dei ruoli, gestione dei canali, scadenza dell'iscrizione, revoca dell'accesso e moderazione.

Tre caratteristiche sono particolarmente importanti:

- OAuth2 reale nella struttura: per aggiungere un utente Discord al server tramite API occorre che abbia autorizzato l'app con `guilds.join`. Non basta conoscere l'email o il Discord User ID.&#x20;

  [image](https://www.google.com/s2/favicons?domain=https://github.com\&sz=32)

  GitHub

- Permessi e gerarchia dei ruoli: il simulatore deve rifiutare le operazioni che un bot reale non potrebbe effettuare, anche quando possiede un token valido.&#x20;

  [image](https://www.google.com/s2/favicons?domain=https://github.com\&sz=32)

  GitHub

- Eventi e sincronizzazione: Discord può comunicare gli ingressi, le uscite e le modifiche dei membri tramite Gateway, con specifiche autorizzazioni per gli eventi relativi ai membri.&#x20;

  [image](https://www.google.com/s2/favicons?domain=https://github.com\&sz=32)

  GitHub

  +1



Non aggiungerei alcuna dipendenza da un server Discord reale in questa prima fase. Mini-Discord deve funzionare interamente offline, con utenti sintetici, OAuth simulato e API compatibili con Discord v10.

## Prompt completo per Grok — Mini-Discord

# TASK — CHERRY TEST: IMPLEMENT MINI-DISCORD

## A complete local Discord server API simulator for integration and end-to-end testing

### Project

Cherry Test — Mini Services Arsenal

### New component

`mini/mini-discord`

### Target

Java implementation, compatible with the current Cherry Test architecture.

### First consumer

JUG Lugano Platform.

### Primary objective

Provide a realistic, stateful, deterministic, fully offline Discord API simulator capable of testing complete Discord server administration and membership lifecycle workflows.

---

# 0. MISSION AND SCOPE

Implement **Mini-Discord**, a reusable service inside the Cherry Test testing arsenal.

Mini-Discord must emulate the parts of the official Discord platform necessary for applications to:

1. Connect a user's Discord identity through OAuth2.
2. Authenticate applications and bots.
3. Manage Discord servers (guilds).
4. Manage members.
5. Manage server roles.
6. Manage channel structures.
7. Manage permissions.
8. Add approved users to a server.
9. Remove users when their membership expires.
10. Revoke and restore access.
11. Manage invitations.
12. Manage bans, kicks and moderation.
13. Manage server events.
14. Observe membership changes.
15. Reconcile the actual server state against the desired state.
16. Handle Discord API failures, rate limits and permission errors.
17. Test all of these functions through Cherry Test.

The objective is to support a future JUG Lugano integration where **the JUG platform controls the Discord community through its backend**.

For this task, however:

**IMPLEMENT ONLY MINI-DISCORD AND ITS CHERRY TEST INTEGRATION.**

Do not implement the JUG website, JUG administration frontend, JUG Discord client or production bot yet.

The simulator must be generic and reusable by other applications.

Deliver executable, tested code, not a design-only proposal.

---

# 1. ABSOLUTE SAFETY REQUIREMENTS

Mini-Discord must be an entirely local simulator.

It must:

- Never require real Discord credentials.
- Never connect to Discord production APIs.
- Never access an actual Discord server.
- Never use real JUG member identities.
- Never depend on Discord availability.
- Never require a real Discord account.
- Never send messages or invitations to real users.
- Never install a real Discord application.
- Never access development, integration or production Discord servers without separate authorization.

Do not create any real Discord server during this task.

Do not create or configure any real Discord Developer Application.

Do not add production credentials to the repository.

Do not use real bot tokens for negative testing.

All testing must work offline.

## Environment isolation

Use explicit modes:

```
LOCAL_MINI_DISCORD
REAL_DISCORD_TEST
PRODUCTION
```

Only `LOCAL_MINI_DISCORD` is permitted during this task.

The other modes are future integration targets and must not be configured or accessed.

Do not implement automatic fallback from Mini-Discord to Discord.

## Network restrictions

Run integration tests with outbound Discord access prohibited.

At minimum, tests must fail if any component attempts to call:

```
discord.com
discordapp.com
gateway.discord.gg
```

Use a network-isolated test environment or an equivalent enforceable outbound network policy.

Do not rely only on assertions after requests have already been sent.

---

# 2. INSPECT THE EXISTING CHERRY TEST REPOSITORY

Cherry Test has been migrated from Rust to Java.

The current Java implementation is the architectural source of truth.

Before modifying code, inspect:

- Repository structure.
- Java version.
- Build tool.
- Module organization.
- `mini/` directory.
- Existing Mini-Slack implementation.
- Other existing mini services.
- HTTP server framework.
- JSON serialization.
- Service startup and shutdown.
- Dependency injection.
- Test configuration.
- Cherry Test service discovery.
- Docker and Compose integration.
- Testcontainers support.
- Test runner adapters.
- Fixture handling.
- Report generation.
- Captured HTTP exchanges.
- CI configuration.
- Existing conventions for IDs, ports and logging.

**Reuse the Mini-Slack architecture whenever it is appropriate.**

For example, shared reusable infrastructure may include:

- Service lifecycle.
- Dynamic HTTP port allocation.
- JSON error responses.
- Test fixture loading.
- Synthetic identity generation.
- Fault injection.
- Request recording.
- Health checks.
- Test-only administration.
- Docker packaging.
- Integration with Cherry Test reports.

Do not copy and paste entire Mini-Slack modules if a reusable abstraction would be more appropriate.

Do not introduce unnecessary frameworks or infrastructure.

Do not overwrite work from other agents.

## Proposed directory

```
mini/
  mini-slack/
  mini-discord/
```

Use the requested `mini/mini-discord` location if compatible with the actual repository.

If the project already has a different naming or packaging convention, preserve that convention and explain the resulting location.

## Initial deliverable

Before implementation, provide a brief plan covering:

1. Existing reusable components.
2. Proposed modules.
3. API and OAuth implementation.
4. State storage.
5. Permission engine.
6. Testing architecture.
7. Cherry Test integration.
8. Estimated implementation phases.

Then implement the approved architecture without stopping at the analysis stage.

---

# 3. REQUIRED ARCHITECTURE

Mini-Discord should contain the following logical components:

```
Mini-Discord
│
├── HTTP API Server
│   ├── Discord REST API v10
│   ├── Discord OAuth2
│   ├── Test Administration API
│   └── Health API
│
├── Discord Domain Model
│   ├── Applications
│   ├── Bots
│   ├── Users
│   ├── Guilds
│   ├── Guild Members
│   ├── Roles
│   ├── Categories
│   ├── Channels
│   ├── Permission Overwrites
│   ├── Invites
│   ├── OAuth Authorizations
│   ├── Guild Events
│   ├── Moderation
│   └── Audit Logs
│
├── Authentication & Authorization
│   ├── Bot Tokens
│   ├── OAuth2 User Tokens
│   ├── OAuth Scopes
│   ├── Permissions
│   └── Role Hierarchy
│
├── State Engine
│   ├── In-memory State Store
│   ├── Deterministic IDs
│   ├── State Transitions
│   ├── Test Clock
│   └── Reset & Fixtures
│
├── Gateway / Event Simulation
│   ├── Member Events
│   ├── Role Events
│   ├── Channel Events
│   └── Event Subscriptions
│
├── Fault Injection
│   ├── HTTP Errors
│   ├── Rate Limits
│   ├── Delays
│   ├── Timeouts
│   └── Partial Failures
│
└── Cherry Test Integration
    ├── Start
    ├── Health Check
    ├── Seed
    ├── Run Tests
    ├── Capture Evidence
    ├── Reset
    └── Stop
```

Use the current Cherry Test modularity rather than forcing this exact package structure.

## In-memory implementation

The initial version should use an in-memory state store.

Requirements:

- Thread-safe operations.
- Consistent mutations.
- Deterministic initialization.
- Independent test state.
- Reset capability.
- Snapshot inspection.
- No external database requirement.

Provide abstractions that would permit alternative storage later, but do not implement an unnecessary database layer.

---

# 4. DISCORD API COMPATIBILITY

Target the official Discord REST API version:

`v10`

Base URL for local tests:

```
http://127.0.0.1:<port>/api/v10
```

The port must be dynamically allocated when possible.

The API must reproduce:

- HTTP methods.
- Paths.
- Query parameters.
- JSON request bodies.
- Response schemas.
- HTTP status codes.
- Discord error codes.
- Relevant response headers.
- Authentication.
- Authorization.
- Permission checks.
- State transitions.

**Do not invent Discord API behavior.**

Use official Discord documentation as the contract.

Maintain a documented API compatibility matrix.

Use the current Discord documentation rather than outdated examples.

Official references:

[https://docs.discord.com/developers/reference](https://docs.discord.com/developers/reference)

[https://docs.discord.com/developers/topics/oauth2](https://docs.discord.com/developers/topics/oauth2)

[https://docs.discord.com/developers/resources/guild](https://docs.discord.com/developers/resources/guild)

[https://docs.discord.com/developers/resources/channel](https://docs.discord.com/developers/resources/channel)

[https://docs.discord.com/developers/topics/permissions](https://docs.discord.com/developers/topics/permissions)

[https://docs.discord.com/developers/topics/rate-limits](https://docs.discord.com/developers/topics/rate-limits)

[https://docs.discord.com/developers/events/gateway](https://docs.discord.com/developers/events/gateway)

Where an endpoint is unsupported, return an explicit documented result.

Never return a fabricated success response.

---

# 5. DISCORD IDENTITY MODEL

Create a synthetic Discord user model.

Important fields:

```
DiscordUser
  id
  username
  globalName
  discriminator
  avatar
  bot
  system
  flags
  publicFlags
  locale
  verified
  email
```

Fields must follow the actual Discord API schemas.

Do not require every field to be present.

## Identity rules

Discord IDs are not email addresses.

The primary external identifier is:

`Discord User ID`

Do not use:

- Username as a stable identifier.
- Global name as a stable identifier.
- Email as the Discord primary key.

Represent Discord snowflake identifiers consistently as strings in JSON.

Avoid JavaScript precision loss.

Use deterministic synthetic IDs for fixtures.

Support:

- Regular users.
- Bots.
- Deleted or unavailable users where applicable.
- Users not belonging to a guild.
- Users belonging to multiple guilds.
- Users with different roles in different guilds.

## Guild membership is separate from user identity

A user can exist in Discord without belonging to any particular guild.

Model this distinction explicitly.

---

# 6. DISCORD APPLICATIONS AND BOT AUTHENTICATION

Implement synthetic applications and bot accounts.

Each application contains:

```
Application
  id
  name
  botUserId
  clientId
  clientSecret
  redirectUris
  authorizedScopes
  botToken
  enabled
```

All values must be synthetic.

Bot tokens must be associated with a specific application.

Support:

- Valid token.
- Unknown token.
- Invalid authorization scheme.
- Revoked token.
- Token belonging to another application.
- Bot not installed in a guild.
- Bot installed without required permissions.

For Discord bot REST API calls, authenticate using the official:

```
Authorization: Bot <token>
```

Do not confuse this with OAuth2 Bearer tokens.

The simulator must reject inappropriate token types.

## App installation

Support installing a synthetic bot into a synthetic guild through the test administration API.

A bot installation must define:

- Application.
- Bot identity.
- Target guild.
- Requested permissions.
- Authorized permissions.
- Bot roles.
- Role position.

The installation must not automatically grant Administrator permissions.

---

# 7. OAUTH2 — COMPLETE LOCAL SIMULATION

This is a critical part of Mini-Discord.

JUG Lugano will eventually need users to connect their Discord accounts voluntarily.

Mini-Discord must simulate the complete OAuth2 authorization-code flow.

## Required OAuth endpoints

Implement the documented equivalents of:

```
GET  /oauth2/authorize
POST /api/v10/oauth2/token
POST /api/v10/oauth2/token/revoke
GET  /api/v10/users/@me
```

Use official endpoint contracts and content types.

Support at minimum:

```
identify
guilds.join
```

Support `email` only when actually requested and authorized.

The JUG membership identity must not depend on Discord exposing an email address.

## Authorization-code flow

Simulate:

```
Application
    |
    v
OAuth authorization request
    |
    v
Synthetic user consent
    |
    v
Authorization code
    |
    v
Backend exchanges code
    |
    v
Access token
    |
    v
GET /users/@me
    |
    v
Discord User ID
```

Support:

- Registered redirect URIs.
- `client_id`.
- `client_secret`.
- `response_type=code`.
- Requested scopes.
- `state` parameter.
- Authorization code.
- Code expiration.
- Single-use codes.
- Token expiration.
- Token refresh.
- Token revocation.
- User denial of consent.
- Invalid redirect URI.
- Invalid client credentials.
- Invalid or expired authorization code.
- Invalid scope.
- Missing scope.
- Repeated token exchange.

Follow Discord's documented support for OAuth security parameters; do not claim support for an extension such as PKCE without checking the actual Discord contract.

The simulator should preserve and return `state`; responsibility for CSRF validation remains with the consuming application.

## Test-only consent simulation

Provide a test API that can simulate a user:

- Accepting authorization.
- Denying authorization.
- Granting only selected scopes.
- Revoking consent.

Do not require real Discord login.

Do not create a fake public Discord login page intended to impersonate Discord outside the local test environment.

A simple local consent screen is acceptable if needed for Playwright end-to-end testing.

## Security

Tokens and authorization codes must be scoped correctly to their applications and users.

An access token issued to User A must never identify User B.

Revocation must invalidate subsequent authorized requests as appropriate to the token type.

---

# 8. GUILD MANAGEMENT

The simulator must support one or more independent Discord guilds.

A guild contains:

```
Guild
  id
  name
  ownerId
  description
  icon
  features
  roles
  channels
  members
  invites
  scheduledEvents
  moderationConfiguration
  auditLog
```

Implement the documented Discord guild endpoints required for administrative operations.

Priority endpoints include:

```
GET   /api/v10/guilds/{guildId}
PATCH /api/v10/guilds/{guildId}

GET   /api/v10/guilds/{guildId}/members
GET   /api/v10/guilds/{guildId}/members/{userId}

PUT   /api/v10/guilds/{guildId}/members/{userId}
PATCH /api/v10/guilds/{guildId}/members/{userId}
DELETE /api/v10/guilds/{guildId}/members/{userId}
```

Also implement the relevant documented role, channel and moderation endpoints in the following sections.

A REST method must match the official contract.

The initial guild creation process may use a test-only administrative endpoint.

Do not imply that bots can freely create production Discord servers if that is not supported by the public API.

---

# 9. GUILD MEMBERSHIP LIFECYCLE

This is the highest-priority functional requirement.

Mini-Discord must simulate every important membership transition.

## Membership states

Use internal lifecycle states where useful:

```
NOT_MEMBER
PENDING_SCREENING
ACTIVE
ACCESS_REVOKED
KICKED
BANNED
```

Important: these are simulator/domain states, not necessarily literal values in Discord API responses.

The public REST responses must use real Discord member representations.

## 9.1 Adding a member

Implement:

```
PUT /api/v10/guilds/{guildId}/members/{userId}
```

The operation must require:

- A valid bot token.
- The bot to belong to the guild.
- The documented bot permissions.
- A valid OAuth2 user access token.
- The `guilds.join` scope.
- The user token to belong to the requested user.
- The user authorization to belong to the same application as the bot.

Correct behavior:

- 201 with member object when a new member is added.
- 204 when the user is already a member.
- Appropriate errors for invalid authorization and other failures.

Adding a member must not create a new Discord identity.

## 9.2 Membership Screening

Support optional membership screening.

When enabled, a newly added member may have:

```
{
  "pending": true
}
```

Provide a test-only mechanism for completing screening.

Successful completion must update the member's `pending` status.

Do not confuse:

- Discord membership.
- Discord screening completion.
- JUG membership approval.
- Permission to view protected channels.

These are different conditions.

## 9.3 Updating a member

Support relevant documented member modifications:

- Nickname.
- Roles where appropriate.
- Moderation-related attributes where supported.
- Communication timeout where supported.

Enforce required permissions and hierarchy constraints.

## 9.4 Removing a member

Implement:

```
DELETE /api/v10/guilds/{guildId}/members/{userId}
```

This simulates kicking the member from the guild.

Require `KICK_MEMBERS`.

Return the documented success status and generate a member removal event.

**Important: kicking a member does not permanently prevent rejoining.**

The simulator must allow a kicked user to rejoin if a valid authorized entry path exists.

## 9.5 Banning a member

Implement the documented guild-ban endpoints.

Support:

- Ban user.
- Retrieve ban.
- List bans.
- Unban user.

Enforce `BAN_MEMBERS` and relevant hierarchy restrictions.

A banned user must not be able to rejoin until the ban is removed.

## 9.6 Revoking access without kicking

Support a less destructive strategy:

- Remove the authorized membership role.
- Preserve guild membership.
- Deny visibility of protected channels.
- Allow restoration of the membership role later.

This is important for expired subscriptions or temporary administrative suspensions.

The simulator must distinguish:

```
Member exists in guild
```

from:

```
Member has access to protected community channels
```

These are not equivalent.

---

# 10. ROLE MANAGEMENT

Implement complete role lifecycle support through the documented public API.

Required operations:

```
GET    /api/v10/guilds/{guildId}/roles
POST   /api/v10/guilds/{guildId}/roles

PATCH  /api/v10/guilds/{guildId}/roles/{roleId}
DELETE /api/v10/guilds/{guildId}/roles/{roleId}

PUT    /api/v10/guilds/{guildId}/members/{userId}/roles/{roleId}
DELETE /api/v10/guilds/{guildId}/members/{userId}/roles/{roleId}
```

Also support role reordering using the appropriate official endpoint.

## Role model

Include:

- Role ID.
- Name.
- Position.
- Permissions bitfield.
- Color.
- Hoist.
- Mentionable.
- Managed flag.
- Additional supported metadata.

## Permission enforcement

The simulator must enforce:

- `MANAGE_ROLES`.
- Role position hierarchy.
- Roles above the bot.
- Managed roles that cannot be modified.
- Server owner protection.
- Administrator permissions.
- Guild-specific permissions.

A bot must not be able to modify a role above its own highest role merely because it has `MANAGE_ROLES`.

## Default JUG fixture roles

Create:

```
@everyone
MEMBER
STUDENT
PROFESSIONAL
SPEAKER
MENTOR
MODERATOR
STAFF
ADMIN
```

The fixture should demonstrate realistic permissions without granting excessive privileges.

`ADMIN` must not automatically equal the Discord `ADMINISTRATOR` permission.

Do not automatically grant Discord Administrator privileges because an application user has an administrative role.

The future JUG platform will decide which roles a person should receive.

Mini-Discord only enforces Discord's resulting permission rules.

---

# 11. CHANNEL MANAGEMENT

Implement management of the principal Discord channel types needed by a developer community.

Support:

- Categories.
- Text channels.
- Announcement channels where supported.
- Forum channels.
- Voice channels.
- Stage channels where relevant.

## Core endpoints

```
GET    /api/v10/guilds/{guildId}/channels
POST   /api/v10/guilds/{guildId}/channels

GET    /api/v10/channels/{channelId}
PATCH  /api/v10/channels/{channelId}
DELETE /api/v10/channels/{channelId}
```

Support channel positioning through the official guild channel position endpoint.

## Permission overwrites

Implement:

```
PUT    /api/v10/channels/{channelId}/permissions/{overwriteId}
DELETE /api/v10/channels/{channelId}/permissions/{overwriteId}
```

Support permission overwrites for:

- Roles.
- Individual members.

Implement the actual effective-permission computation.

Do not simply check whether a role is listed on a channel.

The permission engine must account for:

- Guild base permissions.
- Combined role permissions.
- `@everyone` overwrites.
- Role-specific overwrites.
- Member-specific overwrites.
- Discord's documented allow/deny precedence.
- Administrator bypass.
- Channel inheritance.
- Permission requirements for viewing and posting.

## Security invariant

A person without the approved membership role must not be able to read protected channels.

Even if that person is technically a member of the guild.

Model this explicitly.

---

# 12. DEFAULT JUG LUGANO GUILD FIXTURE

Provide a reusable fixture:

`jug-lugano-discord.yaml`

This fixture is synthetic and must never contain real production identifiers.

## Guild

```
Name: JUG Lugano Local
Owner: synthetic-owner
Environment: MINI_DISCORD
```

## Channels

### Public entry / onboarding

```
#benvenuto
#regolamento
```

These are visible to new guild members as defined by the fixture's onboarding policy.

### Member-only channels

```
#annunci
#presentazioni
#java-jvm
#java-ee
#spring
#java-ai
#eventi-talk
#studenti-mentoring
```

### Staff channels

```
#staff
```

### Optional community activities

```
#opportunita-lavoro
#off-topic
```

### Forum channels

At minimum create a synthetic forum channel for:

```
Java Questions
```

With tags such as:

```
Spring
Jakarta EE
Hibernate
JVM
AI
Testing
```

### Voice channels

```
Meetup Online
Mentoring
```

## Required permissions

- `@everyone`: no visibility into protected member channels.
- `MEMBER`: access to ordinary community channels.
- `STUDENT`: ordinary member permissions plus explicitly configured student spaces, if any.
- `PROFESSIONAL`: ordinary member permissions.
- `MENTOR`: access to mentoring features where configured.
- `STAFF`: access to staff channels.
- `MODERATOR`: moderation permissions appropriate to the test fixture.
- `ADMIN`: controlled management permissions.
- `OWNER`: guild ownership.

Enforce every permission through the actual permission engine.

The fixture is not merely visual configuration data.

---

# 13. INVITATION MANAGEMENT

Implement Discord invitation functionality using documented endpoints.

Include support for:

```
POST   /api/v10/channels/{channelId}/invites
GET    /api/v10/guilds/{guildId}/invites
GET    /api/v10/invites/{inviteCode}
DELETE /api/v10/invites/{inviteCode}
```

Respect the official required permissions and authentication behavior.

Model:

- Invite code.
- Guild.
- Channel.
- Creator.
- Maximum uses.
- Number of uses.
- Expiration.
- Revocation.
- Temporary membership where supported.

## Critical security scenario

A user receives a Discord invitation link without first registering with JUG.

The simulator must be able to model that the user enters the guild.

However, the user must not receive the `MEMBER` role automatically.

Consequently, the user must not see protected JUG discussions.

This scenario is essential.

**The platform's membership approval, not possession of a Discord invite link, must determine access to protected channels.**

Test also:

- Expired invite.
- Revoked invite.
- Maximum uses reached.
- Invalid invite code.
- Banned user attempting to join.
- Unauthorized creation of an invite.

---

# 14. MODERATION AND SECURITY

Implement the documented Discord APIs for moderation operations relevant to server administration.

At minimum:

- Kick.
- Ban.
- Unban.
- Timeout.
- Remove timeout.
- Role revocation.
- Audit logs.
- Permission errors.

Add Auto Moderation APIs where documented and practical.

Support:

- Retrieve rules.
- Create rules.
- Modify rules.
- Delete rules.

Keep this modular so that it does not delay the core membership workflow.

Do not fabricate support for Discord administrative settings that have no supported public API.

The compatibility documentation must identify which settings require manual Discord configuration.

---

# 15. SERVER EVENTS AND NOTIFICATIONS

Mini-Discord must support the observable events needed by a future membership synchronizer.

Relevant events include:

```
GUILD_MEMBER_ADD
GUILD_MEMBER_REMOVE
GUILD_MEMBER_UPDATE

GUILD_ROLE_CREATE
GUILD_ROLE_UPDATE
GUILD_ROLE_DELETE

CHANNEL_CREATE
CHANNEL_UPDATE
CHANNEL_DELETE

GUILD_CREATE
GUILD_UPDATE
```

Use the current official event names and payload contracts.

## Gateway support

Inspect the existing Cherry Test requirements.

If downstream clients consume Discord Gateway events, provide a minimal Discord-compatible Gateway WebSocket simulator supporting the required:

- Gateway discovery.
- Connection.
- Hello.
- Heartbeat.
- Identify.
- Ready.
- Intents.
- Event dispatch.
- Connection close/error behavior.

At minimum, support the member, guild, role and channel events listed above.

Do not claim complete Discord Gateway compatibility unless it is implemented and tested.

## Privileged intents

Support simulation of:

```
GUILDS
GUILD_MEMBERS
```

`GUILD_MEMBERS` is a privileged intent.

Require it where Discord's current API requires it, including member events and bulk member listing.

A bot lacking the appropriate intent must not silently receive those events.

Provide negative tests for missing and unauthorized intents.

Do not request or simulate `MESSAGE_CONTENT` as necessary for membership management.

## Event consistency

After a successful state mutation, relevant events must be emitted consistently.

Examples:

- Join → GUILD_MEMBER_ADD.
- Kick → GUILD_MEMBER_REMOVE.
- Role update → GUILD_MEMBER_UPDATE.
- Role creation → GUILD_ROLE_CREATE.

Support event capture and assertions through Cherry Test.

Where exact Gateway support is not available yet, a test-only event recorder may be used, but it must be clearly distinguished from the public Gateway contract.

---

# 16. SERVER EVENTS AND MEETUPS

The future JUG platform may create Discord events for meetups, workshops and mentoring sessions.

Implement the documented Discord Guild Scheduled Events API.

Core features:

- List events.
- Create event.
- Retrieve event.
- Update event.
- Cancel/delete event.
- Start/end event where supported.
- Handle event visibility.
- Assign event channels or external locations.
- Track interested users where supported.

Support a fixture containing:

```
Java Meetup
Spring Boot Workshop
Jakarta EE Technical Session
AI with Java
Student Mentoring
```

Use future synthetic dates and a controllable test clock.

Do not create events on any real Discord server.

---

# 17. MESSAGES, THREADS AND FORUMS

These features are secondary to membership management but should be supported in a reusable simulator.

Implement the official APIs required for:

- Creating bot messages.
- Reading message history.
- Editing bot messages where authorized.
- Deleting messages where authorized.
- Creating threads.
- Retrieving threads.
- Basic forum posts.
- Relevant permissions and message visibility.

Support simple text and embeds first.

Attachment storage, voice streaming, media processing and full Discord client behavior are not required initially.

Where these features are not implemented, document them as unsupported instead of returning arbitrary data.

A complete production Discord client is not the objective.

The objective is API-compatible testing of server automation.

---

# 18. TEST ADMINISTRATION API

Mini-Discord requires a separate, private test API.

This API must never be confused with official Discord endpoints.

Namespace:

```
/_test/
```

Suggested operations:

```
POST   /_test/reset
POST   /_test/seed
GET    /_test/state

POST   /_test/guilds
POST   /_test/users
POST   /_test/bots

POST   /_test/oauth/authorize
POST   /_test/oauth/deny
POST   /_test/oauth/revoke

POST   /_test/members/{id}/join
POST   /_test/members/{id}/leave
POST   /_test/members/{id}/complete-screening

POST   /_test/invites/{id}/accept

POST   /_test/faults
DELETE /_test/faults

GET    /_test/requests
DELETE /_test/requests

GET    /_test/events
DELETE /_test/events
```

Adapt route structure to the existing Mini-Slack test API conventions.

## Security

- Disabled by default outside test mode.
- Bind to loopback for local execution.
- For container networking, require a dedicated test control credential and network isolation.
- Never expose publicly.
- Never accept real Discord tokens.
- Never share test control credentials with production code.

## Useful test operations

A test must be able to:

1. Reset Mini-Discord.
2. Load a known fixture.
3. Generate an OAuth authorization code.
4. Simulate consent.
5. Create a guild member.
6. Assign a role.
7. Revoke a role.
8. Remove a member.
9. Ban a member.
10. Trigger a permission error.
11. Simulate a 429.
12. Inspect state.
13. Verify emitted events.

---

# 19. RATE LIMITING AND FAULT INJECTION

Discord rate limiting must be simulated realistically.

Implement:

- Per-route rate limits.
- Shared buckets where applicable.
- Global rate limits.
- HTTP 429.
- `Retry-After`.
- `X-RateLimit-*` headers.
- Discord-compatible error response bodies.

Do not hardcode production Discord rate limits as immutable application constants.

Allow a test to configure deterministic limits and failures.

Example:

```
{
  "route": "PUT /guilds/{guildId}/members/{userId}",
  "failNext": 2,
  "status": 429,
  "retryAfterSeconds": 1
}
```

Support faults including:

```
INVALID_TOKEN
EXPIRED_TOKEN
MISSING_SCOPE
MISSING_PERMISSIONS
UNKNOWN_GUILD
UNKNOWN_USER
UNKNOWN_MEMBER
UNKNOWN_ROLE
ROLE_HIERARCHY_VIOLATION
ALREADY_MEMBER
BANNED_USER
OAUTH_DENIED
OAUTH_CODE_EXPIRED
RATE_LIMITED
HTTP_500
HTTP_503
TIMEOUT
NETWORK_DISCONNECT
```

The actual HTTP status, JSON response and Discord error code must follow documented behavior.

Internal fault names are not Discord API error codes.

Allow failures to occur:

- Once.
- A fixed number of times.
- Always until disabled.
- After a specified number of requests.

Use a deterministic clock instead of unnecessary real delays where possible.

---

# 20. AUDIT TRAIL AND REQUEST CAPTURE

Every mutation must be observable.

Capture:

```
timestamp
method
path
guildId
actorApplicationId
actorBotId
targetUserId
operation
result
httpStatus
discordErrorCode
duration
correlationId
```

Where appropriate, simulate Discord audit-log entries for actions that Discord records.

Do not record raw access tokens or OAuth authorization codes in public reports.

Redact:

- Authorization headers.
- Client secrets.
- Bot tokens.
- User access tokens.
- Refresh tokens.
- Sensitive identity information.

The event log, audit log and request log must be independently queryable.

---

# 21. CHERRY TEST INTEGRATION

Mini-Discord must become a first-class service in Cherry Test, following the Mini-Slack conventions.

It must support:

- Local start.
- Docker start.
- Health check.
- Dynamic port allocation.
- Fixture initialization.
- Test execution.
- Request capture.
- Event capture.
- Report integration.
- Cleanup.
- Parallel isolation.

The desired flow:

```
Cherry Test
    |
    v
Start Mini-Discord
    |
    v
Create synthetic guild
    |
    v
Install synthetic bot
    |
    v
Load users, roles and channels
    |
    v
Run OAuth tests
    |
    v
Run membership lifecycle tests
    |
    v
Run permissions tests
    |
    v
Run guild administration tests
    |
    v
Run negative and fault tests
    |
    v
Collect evidence
    |
    v
Generate Cherry Test report
    |
    v
Stop Mini-Discord
```

Use the actual Cherry Test YAML and service conventions.

Do not invent configuration syntax that the current Java runner cannot parse.

Expose service addresses through its existing environment-variable mechanism.

Example conceptual variable:

```
DISCORD_API_BASE_URL=http://mini-discord:8080/api/v10
```

Also expose:

```
DISCORD_OAUTH_BASE_URL
DISCORD_GATEWAY_URL
DISCORD_TEST_CONTROL_URL
```

Only where applicable.

## Container support

Provide:

- Dockerfile.
- Minimal runtime image.
- Health endpoint.
- Configurable port.
- Containerized example.
- Docker Compose integration where appropriate.

No persistent database is required.

---

# 22. FIXTURES AND TEST DATA

Provide the following fixtures.

## Fixture A — Empty server

```
Guild: JUG Lugano Local
Users: Owner + Bot
Channels: Basic entry channels
```

## Fixture B — Standard community

```
1 Owner
1 Bot
1 Staff user
1 Moderator
5 Students
5 Professionals
2 Mentors
1 Speaker
1 Pending screening
1 Member without MEMBER role
1 Banned user
1 Kicked user
```

## Fixture C — Unauthorized joining

An unauthorized user joins using an invite link.

The user must not receive the MEMBER role.

Protected channels must remain inaccessible.

## Fixture D — Expired membership

The user remains in the guild but has the MEMBER role removed.

The user cannot read protected channels.

## Fixture E — Large community

Generate:

```
1,000 synthetic users
```

Support:

- Pagination.
- Member enumeration.
- Bulk role assignment.
- Bulk role removal.
- Bulk member removal.
- Performance tests.
- Event consistency.
- Rate limiting.

Also provide an optional 10,000-member fixture for performance testing.

## Fixture F — Multiple guilds

Create two independent synthetic Discord servers.

Verify cross-guild isolation.

A token installed in one guild must not automatically have privileges in the other.

---

# 23. END-TO-END TEST SCENARIOS

These scenarios must run through the actual Mini-Discord public API whenever possible.

Use `/_test` only for fixtures, simulated user actions and inspection.

Do not bypass the public API for the operation being tested.

## Scenario 1 — Complete OAuth authorization

1. Start Mini-Discord.
2. Create a synthetic user.
3. Create a synthetic application.
4. Request `identify guilds.join`.
5. Simulate user consent.
6. Receive authorization code.
7. Exchange code for access token.
8. Retrieve `/users/@me`.
9. Confirm correct Discord User ID.
10. Reject reuse of authorization code.

Expected result: PASS.

## Scenario 2 — Add approved member

1. User authorizes OAuth.
2. Bot is installed in the guild.
3. Bot has required permissions.
4. Add user using `guilds.join`.
5. Verify HTTP 201.
6. Verify guild member exists.
7. Assign MEMBER role.
8. Verify protected channel visibility.
9. Verify member-add and member-update events.

Expected result: PASS.

## Scenario 3 — Missing OAuth scope

Attempt to add a member using a token without `guilds.join`.

Expected result:

Authorization rejected according to Discord's API contract.

No guild membership created.

## Scenario 4 — Bot lacks permission

Attempt to add or modify a member without the required bot permissions.

Expected result:

Operation rejected.

State unchanged.

## Scenario 5 — Role hierarchy

Create a bot role below a protected administrative role.

Attempt to modify the higher role.

Expected result:

Permission failure.

No role mutation.

## Scenario 6 — Duplicate join

Add the same user twice.

Expected result:

First successful addition returns 201.

Second successful request returns 204.

No duplicate member record.

## Scenario 7 — Membership screening

1. Enable screening.
2. Add member.
3. Verify `pending=true`.
4. Complete screening through test controls.
5. Verify `pending=false`.
6. Verify emitted update event.

## Scenario 8 — Unauthorized invite

1. Create an invite link.
2. Simulate external user joining.
3. Do not assign MEMBER.
4. Attempt to access `#java-jvm`.
5. Attempt to access `#staff`.

Expected result:

Both protected channels inaccessible.

## Scenario 9 — Membership expiration

1. Member has MEMBER role.
2. Remove MEMBER role via bot API.
3. Verify membership still exists.
4. Verify protected channels are inaccessible.
5. Restore MEMBER role.
6. Verify access restored.

## Scenario 10 — Kick and rejoin

1. Member is active.
2. Bot kicks member.
3. Verify member removed.
4. Simulate valid rejoining path.
5. Verify the user does not automatically regain privileged membership roles.

## Scenario 11 — Ban and unban

1. Ban member.
2. Attempt to rejoin.
3. Verify failure.
4. Unban member.
5. Re-authorize and rejoin where applicable.
6. Confirm correct final state.

## Scenario 12 — Member reconciliation

Compare a desired-membership dataset against Mini-Discord's actual state.

Include:

- Expected member missing.
- Unexpected member present.
- Member with missing role.
- Member with an unauthorized role.
- Member removed from the guild.
- Member banned.
- Member awaiting screening.

Return the actual Discord state accurately so that a future JUG reconciler can detect discrepancies.

The reconciliation algorithm itself belongs to JUG, not Mini-Discord.

## Scenario 13 — Bulk membership operations

Use 1,000 users.

Test:

- Multiple joins.
- Role grants.
- Role removals.
- Kicks.
- Partial failures.
- Rate limiting.
- Repeated requests.
- State consistency.

## Scenario 14 — Guild management

1. Retrieve guild.
2. Update supported guild settings.
3. Create role.
4. Reposition role.
5. Create category.
6. Create channel.
7. Configure permission overwrites.
8. Update channel.
9. Delete channel.
10. Verify resulting state and emitted events.

## Scenario 15 — Scheduled meetup

1. Create an event.
2. Retrieve event.
3. Modify event.
4. Transition status.
5. Delete or cancel event according to the API.
6. Verify audit/events.

## Scenario 16 — Gateway membership events

Connect a synthetic bot client with the required intents.

Add a user.

Verify the appropriate member event is delivered.

Remove a user.

Verify the appropriate member removal event.

Test missing privileged intent.

## Scenario 17 — OAuth token expiration

1. Complete authorization.
2. Advance test clock.
3. Expire access token.
4. Attempt authorized operation.
5. Verify rejection.
6. Refresh token using supported flow.
7. Retry.
8. Verify success.

## Scenario 18 — OAuth revocation

Revoke consent or token.

Attempt to use the revoked token.

Expected result:

Authorization rejected.

## Scenario 19 — Discord API outage

1. Simulate 503.
2. Attempt operation.
3. Verify no incorrect successful state mutation.
4. Restore service.
5. Retry.
6. Verify recovery.

## Scenario 20 — Rate limiting

1. Configure 429 response.
2. Attempt bulk operations.
3. Verify rate-limit response and headers.
4. Verify client-compatible retry behavior.
5. Verify final state.

## Scenario 21 — Multi-guild isolation

Create Guild A and Guild B.

Install the bot in Guild A only.

Attempt to modify Guild B.

Expected result:

Rejected.

No cross-guild state leakage.

## Scenario 22 — Complete simulated JUG membership lifecycle

This is a **contract scenario inside Mini-Discord**, not an implementation of JUG business logic.

Simulate these externally determined actions:

```
JUG approval
     |
     v
OAuth authorization
     |
     v
Discord user identified
     |
     v
User added to guild
     |
     v
Screening completed if required
     |
     v
MEMBER role assigned
     |
     v
Protected channels accessible
     |
     v
JUG membership expires
     |
     v
MEMBER role revoked
     |
     v
Protected channels inaccessible
     |
     v
JUG membership renewed
     |
     v
MEMBER role restored
     |
     v
Access restored
     |
     v
JUG account permanently disabled
     |
     v
Member kicked or banned according to policy
     |
     v
Access removed and verified
```

The test must use the same public REST contracts that a real Java Discord integration would use.

---

# 24. AUTOMATED JAVA TESTS

Use the existing project test framework.

Implement:

- Unit tests.
- HTTP contract tests.
- OAuth2 tests.
- Authorization tests.
- Role permission tests.
- State transition tests.
- Gateway tests where supported.
- Integration tests.
- Cherry Test end-to-end tests.

Required properties:

- Deterministic.
- Repeatable.
- Independent.
- Parallel-safe.
- No real Discord calls.
- No production credentials.
- No test-order dependency.
- No unnecessary sleeps.

Use an injectable clock to test expiry and timeout behavior.

Use separate test state per execution scope.

Test unexpected failures and verify state consistency after failure.

---

# 25. PERFORMANCE REQUIREMENTS

Mini-Discord is a test tool, not a production-scale Discord replacement.

Nevertheless, it should handle realistic community-sized datasets efficiently.

Targets:

- 1,000-member fixtures must be practical for ordinary CI.
- Member lookup should not require scanning an entire large dataset unnecessarily.
- Role assignment must be consistent under concurrent requests.
- Pagination must work for large guilds.
- Fixture reset must not leave resources running.
- Multiple test executions must not share mutable state accidentally.

Measure:

- Startup time.
- Fixture load time.
- Member enumeration time.
- Role mutation latency.
- Bulk operation duration.
- Memory use.
- Test completion time.

Report actual measurements.

Do not invent benchmark results.

---

# 26. REPORTING

Integrate with Cherry Test's existing reports.

Show:

```
Mini-Discord — Integration Tests

OAuth2
  Authorization code             PASS
  Invalid state scenario         PASS
  Token expiration               PASS
  Token revocation               PASS

Guild Membership
  Add member                     PASS
  Duplicate join                 PASS
  Membership screening           PASS
  Kick member                    PASS
  Ban member                     PASS

Roles
  Assign role                    PASS
  Remove role                    PASS
  Role hierarchy                 PASS

Permissions
  MEMBER channel access          PASS
  Unauthorized invite            PASS
  STAFF restriction              PASS

Resilience
  API 429                        PASS
  OAuth failure                  PASS
  Discord outage                 PASS
```

This is an illustrative report, not a claim that tests already passed.

Where supported by the existing report engine, include:

- Request.
- Sanitized response.
- Assertion.
- Test duration.
- Captured events.
- State before and after.
- Failed permission.
- Error code.

Do not build an independent reporting framework if Cherry Test already provides these facilities.

---

# 27. CONFIGURATION AND DOCUMENTATION

Provide a complete README at:

`mini/mini-discord/README.md`

Document:

1. Purpose.
2. Architecture.
3. Java modules.
4. How to build.
5. How to run.
6. Docker usage.
7. Cherry Test integration.
8. API base URL.
9. OAuth base URL.
10. Synthetic bot setup.
11. Fixtures.
12. Test API.
13. Reset behavior.
14. Role and permission model.
15. Membership lifecycle.
16. Gateway support.
17. Fault injection.
18. Rate limits.
19. Authentication.
20. Supported Discord endpoints.
21. Unsupported endpoints.
22. Compatibility limitations.
23. Security model.
24. Troubleshooting.
25. Example test scenarios.

Include an executable example using the project's real CLI and test configuration.

Do not provide commands that were not actually validated.

---

# 28. IMPLEMENTATION PRIORITIES

Implement in the following order.

## Phase A — Foundation

- Repository analysis.
- Reuse existing Mini infrastructure.
- Local server.
- State store.
- Synthetic guilds.
- Users.
- Applications.
- Bot tokens.
- Test API.
- Fixtures.
- Health checks.

## Phase B — OAuth and Membership

- OAuth authorization code.
- Access tokens.
- User identification.
- `guilds.join`.
- Add member.
- Get member.
- List members.
- Remove member.
- Screening.
- Membership lifecycle.

## Phase C — Roles and Security

- Role CRUD.
- Member role grant/revoke.
- Permission bitfields.
- Role hierarchy.
- Channel permission overwrites.
- Protected-channel access tests.

## Phase D — Complete Server Administration

- Channel CRUD.
- Categories.
- Invites.
- Moderation.
- Bans.
- Scheduled events.
- Audit logs.

## Phase E — Events and Reliability

- Gateway subset.
- Member events.
- Channel events.
- Role events.
- Rate limiting.
- Fault injection.
- Token expiration.
- Recovery scenarios.

## Phase F — Cherry Test Integration

- Lifecycle.
- Docker.
- Fixtures.
- E2E tests.
- CI.
- Reports.
- Documentation.

Do not mark a phase complete unless its required functionality works and its tests pass.

Prioritize the end-to-end membership lifecycle over secondary message/media features.

---

# 29. DEFINITION OF DONE

The task is complete only when:

- `mini/mini-discord` is implemented in the current Cherry Test architecture.
- Mini-Discord starts locally.
- Mini-Discord runs in Docker where needed.
- Mini-Discord runs offline.
- OAuth2 authorization works with synthetic users.
- Bot tokens and OAuth tokens are distinguished correctly.
- A user can authorize `guilds.join`.
- An authorized user can be added to a guild through the public API.
- A member can be retrieved and enumerated.
- Roles can be created, modified and deleted.
- Roles can be granted and revoked.
- Role hierarchy is enforced.
- Permission overwrites are enforced.
- Protected channels are inaccessible without proper roles.
- A member can be kicked.
- A member can be banned and unbanned.
- Membership screening is supported.
- Invitation scenarios are supported.
- Core guild administration is supported.
- Scheduled events are supported.
- Required member events are observable.
- Token expiration and revocation are supported.
- Rate limits and faults can be simulated.
- Test state can be reset.
- At least 1,000 users can be loaded in a test fixture.
- All core E2E membership scenarios pass.
- Cherry Test captures relevant execution evidence.
- CI runs without Discord connectivity.
- No production credentials are required.
- No external Discord server is accessed.
- Documentation includes supported and unsupported functionality.

Items that are incomplete must be reported honestly.

---

# 30. FINAL IMPLEMENTATION REPORT

At the end of implementation, provide:

## A. Architecture

- Modules and classes.
- Reused Mini-Slack components.
- State design.
- HTTP API design.
- OAuth implementation.
- Permissions engine.
- Gateway integration.

## B. Files

List added and modified files.

Explain build and dependency changes.

## C. API Coverage

Provide a compatibility matrix:

| API           | Implemented | Tested | Limitations |
| ------------- | ----------- | ------ | ----------- |
| OAuth2        | Yes/No      | Yes/No | Details     |
| Guilds        | Yes/No      | Yes/No | Details     |
| Guild Members | Yes/No      | Yes/No | Details     |
| Roles         | Yes/No      | Yes/No | Details     |
| Channels      | Yes/No      | Yes/No | Details     |
| Invites       | Yes/No      | Yes/No | Details     |
| Moderation    | Yes/No      | Yes/No | Details     |
| Events        | Yes/No      | Yes/No | Details     |
| Gateway       | Yes/No      | Yes/No | Details     |

## D. Test Results

Report:

- Unit tests executed.
- API tests executed.
- E2E tests executed.
- Passed tests.
- Failed tests.
- Skipped tests.
- Relevant performance measurements.

## E. Security Verification

Confirm with evidence:

- No real Discord tokens used.
- No real Discord API calls made.
- No production data imported.
- No test secrets exposed.
- No unexpected outbound connections.

## F. Next Steps

Identify which APIs and configuration details the future JUG Lugano Discord integration will require.

Do not implement the JUG integration as part of this task.

---

# FINAL EXECUTION INSTRUCTION

**IMPLEMENT MINI-DISCORD AS A REAL WORKING SERVICE, NOT A SET OF STATIC MOCK RESPONSES.**

It must be possible to execute the following scenario entirely offline:

1. Start Cherry Test.
2. Start Mini-Discord.
3. Initialize a guild with channels and roles.
4. Create a synthetic Discord user.
5. Execute OAuth2 authorization.
6. Retrieve the user identity.
7. Add the user to the guild.
8. Complete screening if enabled.
9. Assign the MEMBER role.
10. Verify access to protected channels.
11. Revoke the MEMBER role.
12. Verify access has been revoked.
13. Restore the MEMBER role.
14. Verify access has been restored.
15. Remove the member.
16. Verify the member is no longer in the guild.
17. Capture all API operations and events.
18. Generate the Cherry Test report.
19. Shut down Mini-Discord.
20. Complete without accessing Discord production.

**Do not implement or deploy changes to any real Discord server.**

**Do not start implementing JUG Lugano's Discord client yet.**

Deliver a reusable, thoroughly tested Mini-Discord component that will become the foundation for the next development task: complete remote management of JUG Lugano's Discord community.

## La distinzione che dovremo mantenere nel progetto

Ci sono tre modi diversi di bloccare un membro Discord, e Mini-Discord deve simularli correttamente.

| Operazione            | Risultato                                                           | Utilizzo JUG                                |
| --------------------- | ------------------------------------------------------------------- | ------------------------------------------- |
| Revoca ruolo `MEMBER` | Resta nel server ma perde l'accesso ai canali protetti              | Membership scaduta o sospensione temporanea |
| Kick                  | Viene rimosso dal server, ma potrebbe rientrare con un nuovo invito | Disiscrizione ordinaria                     |
| Ban                   | Viene rimosso e non può rientrare finché permane il ban             | Abusi o esclusioni amministrative           |

Discord documenta API distinte per aggiunta di membri, gestione dei ruoli, rimozione e ban.&#x20;

[image](https://www.google.com/s2/favicons?domain=https://github.com\&sz=32)

GitHub



Per JUG Lugano sceglierei la revoca del ruolo `MEMBER` come meccanismo principale di sospensione, mantenendo l'opzione di rimozione completa quando l'utente chiede di lasciare la community. Per impedire accessi non autorizzati anche attraverso link d'invito, i canali della community dovranno essere protetti dai permessi, indipendentemente dal semplice ingresso nel server.

Con Mini-Discord potremo verificare questo comportamento prima di creare l'app Discord reale e collegarla alla piattaforma JUG.

---

## Verifica di completamento

- [x] Fase A — Foundation
- [x] Fase B — OAuth and Membership
- [x] Fase C — Roles and Security
- [x] Fase D — Complete Server Administration
- [x] Fase E — Events and Reliability
- [x] Fase F — Cherry Test Integration
