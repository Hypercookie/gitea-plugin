# Local Gitea Dev Instance

A local, throwaway Gitea server running in Docker, used to develop and manually
test the Gitea IntelliJ plugin against a real Gitea API/UI. SQLite-backed, no
external database required. Bound to `127.0.0.1` only — never exposed outside
localhost.

## What's running

| Thing            | Value                              |
|------------------|-------------------------------------|
| Image             | `gitea/gitea:latest`               |
| Container name    | `gitea-plugin-dev`                 |
| Named volume      | `gitea-plugin-dev-data`            |
| HTTP (web/API)    | http://localhost:3000              |
| SSH (git over ssh)| `localhost:2222`                   |
| Database          | SQLite (inside the data volume, no separate DB container) |
| Install wizard    | Skipped — pre-seeded via `GITEA__...` env vars (`INSTALL_LOCK=true`) |

Both ports are published as `127.0.0.1:PORT->PORT` (not `0.0.0.0`), so the
instance is not reachable from other machines on the network.

## Admin credentials

| Field    | Value                        |
|----------|-------------------------------|
| Username | `plugindev`                  |
| Email    | `plugindev@localhost.test`   |
| Password | `PluginDev759062c05dce!`     |
| Role     | Admin (`is_admin: true`)     |

This is throwaway local dev infra only reachable on `127.0.0.1`, so the
password is recorded here in plain text as requested. It was generated
randomly at setup time and created like this:

```bash
PASS="PluginDev$(openssl rand -hex 6)!"
docker exec -u git gitea-plugin-dev gitea admin user create \
  --username plugindev \
  --password "$PASS" \
  --email plugindev@localhost.test \
  --admin \
  --must-change-password=false
```

If you don't have the password anymore (e.g. new session, didn't save it),
just reset it directly on the running container:

```bash
docker exec -u git gitea-plugin-dev gitea admin user change-password \
  --username plugindev \
  --password 'SomeNewDevPassword123!'
```

Or create a brand new admin user the same way if you'd rather not touch the
existing one.

## How this was started

A `docker-compose.yml` was used, kept outside the repo since this is infra
and not part of the plugin codebase. It lives at:

```
~/dev-infra/gitea-plugin-dev/docker-compose.yml
```

Exact contents:

```yaml
name: gitea-plugin-dev

networks:
  gitea-plugin-dev:
    external: false

volumes:
  gitea-plugin-dev-data:
    name: gitea-plugin-dev-data

services:
  gitea:
    image: gitea/gitea:latest
    container_name: gitea-plugin-dev
    environment:
      - USER_UID=1000
      - USER_GID=1000
      - GITEA__database__DB_TYPE=sqlite3
      - GITEA__security__INSTALL_LOCK=true
      - GITEA__server__DOMAIN=localhost
      - GITEA__server__SSH_DOMAIN=localhost
      - GITEA__server__ROOT_URL=http://localhost:3000/
      - GITEA__server__HTTP_PORT=3000
      - GITEA__server__SSH_PORT=2222
      - GITEA__server__SSH_LISTEN_PORT=2222
      - GITEA__server__START_SSH_SERVER=true
      - GITEA__server__DISABLE_SSH=false
    restart: unless-stopped
    networks:
      - gitea-plugin-dev
    volumes:
      - gitea-plugin-dev-data:/data
      - /etc/timezone:/etc/timezone:ro
      - /etc/localtime:/etc/localtime:ro
    ports:
      - "127.0.0.1:3000:3000"
      - "127.0.0.1:2222:2222"
```

(Note: the `version: "3"` key some Gitea docs/examples include is omitted —
it's obsolete in current Docker Compose and just prints a warning.)

Notes on the env vars:
- `GITEA__security__INSTALL_LOCK=true` + `GITEA__database__DB_TYPE=sqlite3`
  skip the interactive web install wizard entirely — Gitea boots straight
  into a fully installed, ready-to-use instance.
- `GITEA__server__SSH_PORT` is the port *advertised* in clone URLs/UI;
  `GITEA__server__SSH_LISTEN_PORT` is the port Gitea's SSH server actually
  binds to inside the container. Both must be set to `2222` — see
  "Troubleshooting notes from setup" below for why this matters (without
  `SSH_LISTEN_PORT` the container crash-loops).

Equivalent single-command version (no compose file needed) if you'd rather
not save a compose file at all:

```bash
docker volume create gitea-plugin-dev-data

docker run -d \
  --name gitea-plugin-dev \
  -p 127.0.0.1:3000:3000 \
  -p 127.0.0.1:2222:2222 \
  -e USER_UID=1000 \
  -e USER_GID=1000 \
  -e GITEA__database__DB_TYPE=sqlite3 \
  -e GITEA__security__INSTALL_LOCK=true \
  -e GITEA__server__DOMAIN=localhost \
  -e GITEA__server__SSH_DOMAIN=localhost \
  -e GITEA__server__ROOT_URL=http://localhost:3000/ \
  -e GITEA__server__HTTP_PORT=3000 \
  -e GITEA__server__SSH_PORT=2222 \
  -e GITEA__server__SSH_LISTEN_PORT=2222 \
  -e GITEA__server__START_SSH_SERVER=true \
  -e GITEA__server__DISABLE_SSH=false \
  -v gitea-plugin-dev-data:/data \
  --restart unless-stopped \
  gitea/gitea:latest
```

Then create the admin user with the `docker exec ... gitea admin user create`
command shown above.

## Verifying it works

```bash
# Unauthenticated — should return {"version":"1.27.3"} (or whatever the
# latest tag currently resolves to)
curl -s http://localhost:3000/api/v1/version

# Authenticated via basic auth
curl -s -u plugindev:'<password>' http://localhost:3000/api/v1/user

# Authenticated via API token (see next section for how to generate one)
curl -s -H "Authorization: token <token>" http://localhost:3000/api/v1/user
```

## Test fixture: `plugindev/pr-test` repo + PR #1

Created during Phase 2 of the PR review feature build
(`docs/plan/pr-review-feature-plan.md`) to exercise `GiteaApiClient` against
real data. Left in place for reuse in later phases (tool window, diff view,
etc) rather than torn down after the integration test ran.

- Repo `plugindev/pr-test`, branches `main` (auto-init) and `feature` (one
  README.md commit ahead).
- PR #1: `feature` → `main`, title "Test PR for plugin dev", open.
- Review #1 on PR #1: state `COMMENT`, one inline comment on `README.md` line
  1 ("nit: consider rewording this line"), plus reply/top-level comments and
  a second review added by `GiteaApiClientIntegrationTest`.
- Recreate the same way if the instance gets wiped — see the `curl` sequence
  in the Phase 2 section of the plan doc, or just re-run
  `GiteaApiClientIntegrationTest` after creating a fresh repo/PR (it doesn't
  create fixtures itself, only reads/writes against existing PR #1).

### Extra fixture PRs #4-#6 (added for UI-pass-3 manual testing)

Created to give Phase 27+ (timeline tab, submit-review popup, etc.) richer,
more "lively" data to render than a single-comment PR — multiple commits,
comment threads with replies, a second author, and all three PR states.
A second user, `revbot` (password `RevBot759062c05dce!`, email
`revbot@localhost.test`), was created for this so comments/reviews come from
more than one login. Branches `feature3`/`feature4`/`feature5` added to
`plugindev/pr-test`, each off `main`.

- **PR #4** (`feature3` → `main`, **open**) — "Add notes section and tidy
  README". 3 separate commits. 2 top-level issue comments (one from each
  user). Two inline review-comment threads on `README.md`: one at line 23
  with a reply (revbot comments, plugindev replies — comment id 50, reply id
  54), one standalone at line 24 (plugindev, comment id 52, no reply).
- **PR #5** (`feature4` → `main`, **merged**) — "Add status section to
  README". 2 commits. revbot left a top-level comment plus an `APPROVED`
  review; plugindev replied with a top-level comment and then merged it
  (plain merge, not squash/rebase).
- **PR #6** (`feature5` → `main`, **closed, not merged**) — "Experimental
  deprecated-notes section". 1 commit, 1 top-level comment explaining why,
  then closed via `PATCH .../issues/6` (`state: closed`) rather than merged.

All three reuse the same trivial README-edit style as PR #1 — the point is
PR/comment/commit metadata variety for UI testing, not interesting diffs.

Run the integration test suite with:
```bash
GITEA_DEV_TOKEN=<token> ./gradlew test --tests '*GiteaApiClientIntegrationTest'
```

## Generating an API token (for the plugin to use)

Via the API (basic auth to bootstrap the token itself):

```bash
curl -s -u plugindev:'<password>' -X POST \
  http://localhost:3000/api/v1/users/plugindev/tokens \
  -H "Content-Type: application/json" \
  -d '{"name":"my-token-name","scopes":["write:repository","write:user","write:issue","write:organization"]}'
```

The response's `sha1` field is the token value — it is only shown once, at
creation time. Adjust `scopes` to whatever the plugin needs to test
(`write:admin` gives full admin API access if needed for testing).

Or via the UI: log in at http://localhost:3000 as `plugindev`, then
**Settings → Applications → Generate New Token**.

To list/delete tokens later:

```bash
curl -s -u plugindev:'<password>' http://localhost:3000/api/v1/users/plugindev/tokens
curl -s -u plugindev:'<password>' -X DELETE \
  http://localhost:3000/api/v1/users/plugindev/tokens/<token-name>
```

## Stopping / starting / removing

The container is named `gitea-plugin-dev`, so the plain `docker` commands
below work regardless of which directory or Compose project started it —
this is the simplest, always-correct way to control it:

```bash
docker stop gitea-plugin-dev
docker start gitea-plugin-dev
docker rm -f gitea-plugin-dev            # remove container, keep volume
docker volume rm gitea-plugin-dev-data   # also wipe all data
```

Alternatively, since the compose file at
`~/dev-infra/gitea-plugin-dev/docker-compose.yml` declares
`name: gitea-plugin-dev`, running Compose from that exact directory also
targets the same container/project:

```bash
cd ~/dev-infra/gitea-plugin-dev

docker compose stop      # stop, keep container + data
docker compose start     # resume a stopped instance
docker compose down      # remove container (data volume persists)
docker compose down -v   # remove container AND the data volume (full wipe)
```

Check status any time with:

```bash
docker ps --filter name=gitea-plugin-dev
docker logs gitea-plugin-dev --tail 50
```

## Recreating from scratch

1. Make sure Docker Desktop is running (`open -a Docker`, then poll
   `docker info` until it responds — can take 30-60s on first launch).
2. Remove any previous instance if present:
   ```bash
   docker rm -f gitea-plugin-dev 2>/dev/null
   docker volume rm gitea-plugin-dev-data 2>/dev/null
   ```
3. Run the `docker compose up -d` (or `docker run`) command above.
4. Wait for the API to respond:
   ```bash
   until curl -sf http://localhost:3000/api/v1/version >/dev/null; do sleep 2; done
   ```
5. Create the admin user with the `gitea admin user create` command above.
6. Verify with the `curl` checks in the "Verifying it works" section.
7. Generate a fresh API token for the plugin as needed.

## Troubleshooting notes from setup

- Gitea's built-in SSH server binds to container port `22` **unless you
  also set `GITEA__server__SSH_LISTEN_PORT`** (not just `SSH_PORT`, which is
  only the *advertised* port used in clone URLs/UI). The `gitea/gitea` image
  also runs its own bundled OpenSSH `sshd` inside the container (confirmed
  in `docker logs`: `Server listening on 0.0.0.0 port 22`, logged from
  inside the container, unrelated to anything on the host). That process
  already held container port `22`, so Gitea's own SSH listener failed with
  `bind: address already in use` and crash-looped until
  `SSH_LISTEN_PORT=2222` was added, moving Gitea's listener off port `22`
  to match the `2222` host port mapping.
- Docker Desktop was not running at the start of setup; it was started with
  `open -a Docker` and became available within one `docker info` poll
  (no long wait needed in this case, but budget up to ~60s on a cold start).
