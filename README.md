# Messenger Pro

A native Android messaging app built with Kotlin and Jetpack Compose.
Package: `com.sikorokoro44.messengerpro`.

## What is in this repository

- A Compose UI with sign-in/registration, chats, contacts, activity log and settings.
- Pure-Kotlin engines (no Android dependencies) for accounts, messaging, contacts,
  presence, activity, backups and settings.
- A checksummed local state codec used for on-device persistence across restarts.
- A 600-item feature registry across 25 categories (`feature-registry.json`).
- JVM unit tests for every engine plus registry-integrity checks.
- GitHub Actions CI that runs the tests, builds the debug APK, verifies its
  package name and label with `aapt`, publishes a release and re-downloads the
  public artifact to compare SHA-256.

## Feature registry status

The registry is the source of truth. Statuses are only advanced when there is
implementation **and** automated test evidence; see `tools/status.json`.

At the time of writing:

| Implementation | Count |
| --- | --- |
| COMPLETE | 85 |
| IN_PROGRESS | 23 |
| NOT_STARTED | 291 |
| BLOCKED | 201 |

`IN_PROGRESS` means the local logic exists and is tested, but the feature cannot
be complete without a transport or service. `BLOCKED` entries have a `note` and
a `dependencies` list explaining the missing external service (SMS/email
provider, calling/media infrastructure, object storage, E2E key directory, sync
service, push notifications or an AI service). This repository does **not**
claim 600/600 completion.

## Supabase backend

The production backend lives in `supabase/` and runs on the existing Supabase
project `dflzzyeuntdmcarroype`:

- `supabase/migrations/20261024000100_messenger_schema.sql` defines the
  PostgreSQL schema, RLS policies, triggers and `SECURITY DEFINER` RPCs for
  accounts, contacts, messages, delivery/read receipts, reactions, presence,
  threads, polling events and cursor sync.
- `supabase/functions/messenger-api/index.ts` is the Edge Function that keeps the
  existing `/v1/...` REST contract used by the Android client. It handles
  Supabase Auth registration/login/refresh, maps the caller JWT to a user-scoped
  Supabase client so RLS applies, and returns the wire format the app expects.
- `supabase/scripts/integration.mjs` registers three throwaway accounts against
  the deployed function and verifies persistence, receipts, reactions, editing,
  deletion, presence, refresh tokens and cross-account isolation in two separate
  invocations.

The sign-in screen pre-fills the deployed function URL:

```
https://dflzzyeuntdmcarroype.supabase.co/functions/v1/messenger-api
```

Clearing the field keeps the app in offline local mode. Access tokens are
refreshed automatically from the stored Supabase refresh token.

### Local checks

```bash
deno check --config supabase/functions/messenger-api/deno.json \
  supabase/functions/messenger-api/index.ts

psql -f supabase/tests/sql/bootstrap.sql \
  -f supabase/migrations/20261024000100_messenger_schema.sql \
  -f supabase/tests/sql/verify.sql
```

The SQL checks need a PostgreSQL 16 database. The bootstrap file provides the
small Supabase-compatible surface (`auth.users`, `auth.uid()`, roles and the
Realtime publication) used by the migration.

### Deployment

`.github/workflows/backend-ci.yml` type-checks the function, runs the migration
and SQL assertions against `postgres:16`, then on `main` links the Supabase
project, pushes migrations, deploys `messenger-api`, and runs the live
integration script. Deployment requires these repository secrets:

- `SUPABASE_ACCESS_TOKEN`
- `SUPABASE_DB_PASSWORD`

If either secret is missing the workflow warns and skips deployment. The
function is deployed with JWT verification disabled because it performs its own
Supabase Auth verification.

## Build and test

CI uses JDK 17, Gradle 8.7 and the Android SDK on the GitHub runner:

```bash
node tools/verify-registry.mjs
gradle testDebugUnitTest
gradle assembleDebug
```

Regenerate the registry and the in-app Kotlin copy after editing statuses:

```bash
node tools/generate-registry.mjs
```

## Architecture

- `engine/AuthEngine.kt` - registration, PBKDF2 password hashing, login, sessions,
  password change/reset, verification codes, attempt limiting, deletion, export.
- `engine/MessagingEngine.kt` - shared threads, deduplication, offline queue,
  delivery states, read receipts, edit/delete, reactions, search, disappearing
  messages, drafts, pin/archive/mute/folders.
- `engine/ContactEngine.kt` - contacts, favourites, nicknames, notes, blocking,
  hiding, groups and custom sounds.
- `engine/PresenceEngine.kt` - presence updates, watchers, duplicate suppression,
  quiet hours, last-seen and invisibility privacy.
- `engine/ActivityEngine.kt` - append-only activity log with windows, filters,
  summaries, export and retention.
- `engine/BackupEngine.kt` - versioned, checksummed backup envelope.
- `engine/SettingsEngine.kt` - theme, accent, font scale, privacy and languages.
- `engine/StateCodec.kt` + `engine/MessengerRepository.kt` - persistence and wiring.
- `remote/` + `ui/MessengerController.kt` - HTTP client, JSON codec, event replay,
  refresh-token handling and server-mode wiring.
- `supabase/` - production PostgreSQL schema, RLS, Edge Function API and live
  integration tests.

## Known external blockers

Cross-device messaging and presence now have a tested Supabase backend and client,
but the registry entries advance only after the live integration job has run
against the deployed project. Calling, media, end-to-end encryption, push
notifications and AI features still need external services and remain `BLOCKED`
with the missing dependency recorded rather than being reported as done.
