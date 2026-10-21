# Local Gitea dev instance

For the live tests alone, `scripts/gitea-test-server.sh` is quicker: it
downloads any Gitea version's release binary, starts it on port 3000 and
creates the fixture below (no Docker needed):

```bash
eval "$(scripts/gitea-test-server.sh start 1.27.3)"
./gradlew test --tests '*GiteaLiveIntegrationTest*' --rerun
scripts/gitea-test-server.sh stop
```

The rest of this page sets up a longer-lived instance in Docker for manual
testing.

A throwaway Gitea server in Docker for manual testing and for
`GiteaLiveIntegrationTest`. It listens on `127.0.0.1` only.

## Start

```bash
docker run -d --name gitea-plugin-dev \
  -p 127.0.0.1:3000:3000 -p 127.0.0.1:2222:2222 \
  -e USER_UID=1000 -e USER_GID=1000 \
  -e GITEA__database__DB_TYPE=sqlite3 \
  -e GITEA__security__INSTALL_LOCK=true \
  -e GITEA__server__ROOT_URL=http://localhost:3000/ \
  -e GITEA__server__SSH_PORT=2222 \
  -e GITEA__server__SSH_LISTEN_PORT=2222 \
  -v gitea-plugin-dev-data:/data \
  gitea/gitea:latest

until curl -sf http://localhost:3000/api/v1/version >/dev/null; do sleep 2; done
```

`INSTALL_LOCK` skips the install wizard. `SSH_LISTEN_PORT` must be set too:
without it Gitea's SSH server tries port 22, which the image's own `sshd`
already uses, and the container crash-loops.

Stop with `docker stop gitea-plugin-dev`, wipe with
`docker rm -f gitea-plugin-dev && docker volume rm gitea-plugin-dev-data`.

## Users and token

```bash
docker exec -u git gitea-plugin-dev gitea admin user create \
  --username plugindev --password '<password>' \
  --email plugindev@localhost.test --admin --must-change-password=false

curl -s -u plugindev:'<password>' -X POST \
  http://localhost:3000/api/v1/users/plugindev/tokens \
  -H "Content-Type: application/json" \
  -d '{"name":"dev","scopes":["write:repository","write:user","write:issue"]}'
```

The `sha1` field of the response is the token. Create a second user,
`alice`, the same way (without `--admin`) for the fixture below.

## Test fixture

`GiteaLiveIntegrationTest` expects a repository `plugindev/pr-test` with:

- PR #1: a branch with README.md changes against `main`, with review threads
  on README.md (inline comments and replies). It must conflict with `main`.
- PR #7: an open PR by a user `alice` that merges cleanly and changes `src/Greeter.kt`
  (line 4 must be commentable). The tests post their own commit statuses
  and reviews and clean up after themselves.

Run the live tests with:

```bash
GITEA_DEV_TOKEN=<token> ./gradlew test --tests '*GiteaLiveIntegrationTest*' --rerun
```

`GITEA_DEV_URL` overrides the default `http://localhost:3000`.
