# Messenger Pro backend

Zero-dependency Node 22 service (built-in `http`, `crypto`, `node:sqlite`) that
provides real accounts, contacts, messaging, receipts, reactions, presence and an
event stream for the Android client.

## Run locally

```bash
cd server
AUTH_SECRET="$(openssl rand -hex 32)" PORT=8080 DATABASE_PATH=./data/messenger.db \
  node --experimental-sqlite src/index.mjs
curl http://localhost:8080/healthz
node --test test/*.test.mjs
```

## API

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/healthz` | Liveness probe |
| POST | `/v1/accounts` | Register and receive a bearer token |
| POST | `/v1/sessions` | Log in |
| DELETE | `/v1/sessions` | Log out (revokes the session) |
| GET | `/v1/me` | Current account |
| GET/PUT/DELETE | `/v1/contacts[/:id]` | Contact list and preferences |
| POST | `/v1/messages` | Send (idempotent by `clientId`) |
| GET | `/v1/messages/:peerId` | Thread history with a cursor |
| PATCH/DELETE | `/v1/messages/:id` | Edit or delete for everyone |
| POST | `/v1/messages/:id/read` | Read receipt |
| POST/DELETE | `/v1/messages/:id/reactions` | Reactions |
| GET | `/v1/threads` | Conversation summaries and unread counts |
| POST/GET | `/v1/presence[/:id]` | Presence update and lookup |
| GET | `/v1/sync?since=` | Poll events after a cursor |
| GET | `/v1/events?since=` | SSE event stream |

Authentication is `Authorization: Bearer <token>`. Tokens are HMAC-SHA256 signed
and bound to a revocable session row. Passwords use PBKDF2-SHA256 (120k rounds).

## Deploy

The service is Dockerised. `fly.toml` is ready for Fly.io:

```bash
flyctl apps create messenger-pro-backend
flyctl volumes create messenger_data --region jnb --size 1
flyctl secrets set AUTH_SECRET="$(openssl rand -hex 32)" --app messenger-pro-backend
flyctl deploy . --remote-only
```

Then set the URL in the Android app under Settings -> Server. Set `FLY_API_TOKEN`
as a repository secret to let `.github/workflows/backend-ci.yml` deploy on push.
Without that secret CI only runs the tests.

## Not included

Push notifications (needs FCM/APNs credentials), calls, media upload, SMS/email
OTP and AI features still require external services. Their fixtures stay BLOCKED.
