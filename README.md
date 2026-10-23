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

## Optional real backend

`server/` contains a zero-dependency Node 22 backend (built-in HTTP, crypto and
SQLite) with real registration, sessions, contacts, messaging, delivery and read
receipts, reactions, presence, threads, polling and an SSE event stream. The
Android app can use it from the sign-in screen: enter a server URL and either
sign in or register. Leave the URL blank to keep using offline local mode.

```bash
cd server
node --test test/*.test.mjs
node --experimental-sqlite src/index.mjs
```

The backend is Dockerised and `server/fly.toml` targets Fly.io. CI runs the
backend tests on every change and deploys only when the `FLY_API_TOKEN`
repository secret exists. Until a server is deployed the transport-dependent
fixtures stay `IN_PROGRESS`; see `server/README.md`.

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
- `remote/` + `ui/MessengerController.kt` - HTTP client, JSON codec, event replay
  and server-mode wiring; `server/` - zero-dependency Node backend.

## Known external blockers

Cross-device messaging and presence now have a tested backend and client, but they
stay `IN_PROGRESS` until a server is actually deployed (hosting credentials are
not configured). Calling, media, end-to-end encryption, push notifications and AI
features still need external services and remain `BLOCKED` with the missing
dependency recorded rather than being reported as done.
