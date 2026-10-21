#!/usr/bin/env bash
# Starts a throwaway Gitea server with the fixture GiteaLiveIntegrationTest
# expects (docs/local-gitea-dev-instance.md), from Gitea's release binary:
# no Docker needed.
#
#   scripts/gitea-test-server.sh start 1.27.3   # prints export lines for the tests
#   scripts/gitea-test-server.sh stop
#
# Use it like this:
#   eval "$(scripts/gitea-test-server.sh start 1.27.3)"
#   ./gradlew test --tests '*GiteaLiveIntegrationTest*'
#   scripts/gitea-test-server.sh stop
#
# GITEA_TEST_DIR (default build/gitea-test) holds the binaries and the
# server's data; GITEA_TEST_PORT (default 3000) is the HTTP port. Linux and
# macOS, amd64 and arm64. Needs curl, git and python3.
set -euo pipefail

dir="${GITEA_TEST_DIR:-build/gitea-test}"
port="${GITEA_TEST_PORT:-3000}"
url="http://localhost:$port"
password='plugin-test-Pa55'

log() { echo "$@" >&2; }

stop() {
  if [[ -f "$dir/gitea.pid" ]]; then
    pkill -P "$(cat "$dir/gitea.pid")" 2>/dev/null || true
    kill "$(cat "$dir/gitea.pid")" 2>/dev/null || true
    rm -f "$dir/gitea.pid"
  fi
  rm -rf "$dir/server"
}

binary() {
  local version="$1" os arch
  case "$(uname -s)" in Linux) os=linux ;; Darwin) os=darwin-10.12 ;; *) log "unsupported OS"; exit 1 ;; esac
  case "$(uname -m)" in x86_64 | amd64) arch=amd64 ;; aarch64 | arm64) arch=arm64 ;; *) log "unsupported CPU"; exit 1 ;; esac
  local file="$dir/bin/gitea-$version"
  if [[ ! -x "$file" ]]; then
    mkdir -p "$dir/bin"
    log "Downloading Gitea $version"
    curl -sSfL --retry 3 -o "$file.part" "https://dl.gitea.com/gitea/$version/gitea-$version-$os-$arch"
    chmod +x "$file.part" && mv "$file.part" "$file"
  fi
  echo "$file"
}

# api METHOD PATH [JSON] [USER]: a request as USER (default plugindev) with
# basic auth; prints the response body.
api() {
  local method="$1" path="$2" body="${3:-}" user="${4:-plugindev}"
  curl -sSf -u "$user:$password" -X "$method" -H 'Content-Type: application/json' \
    ${body:+--data "$body"} "$url/api/v1$path"
}

json() { python3 -c "import json,sys; print(json.load(sys.stdin)$1)"; }

# Gitea refuses to run as root, so as root (a container) it runs as a
# separate user that owns its data.
as_gitea() {
  if [[ "$(id -u)" == 0 ]]; then
    id gitea-test >/dev/null 2>&1 || useradd --system --no-create-home gitea-test
    chown -R gitea-test "$dir/server"
    runuser -u gitea-test -- "$@"
  else
    "$@"
  fi
}

start() {
  local version="$1"
  stop
  local gitea; gitea="$(binary "$version")"
  local work="$dir/server"
  mkdir -p "$work/custom/conf"
  work="$(cd "$work" && pwd)"
  cat > "$work/custom/conf/app.ini" <<INI
APP_NAME = Gitea plugin tests
RUN_MODE = prod
WORK_PATH = $work

[server]
HTTP_ADDR = 127.0.0.1
HTTP_PORT = $port
ROOT_URL = $url/
DISABLE_SSH = true
SSH_ROOT_PATH = $work/ssh
OFFLINE_MODE = true

[database]
DB_TYPE = sqlite3
PATH = $work/data/gitea.db

[repository]
ROOT = $work/repos
DEFAULT_BRANCH = main

[security]
INSTALL_LOCK = true
SECRET_KEY = plugin-tests-not-secret
INTERNAL_TOKEN = $("$gitea" generate secret INTERNAL_TOKEN)

[service]
DISABLE_REGISTRATION = true

[log]
ROOT_PATH = $work/log
LEVEL = Warn

[mailer]
ENABLED = false
INI
  local config="$work/custom/conf/app.ini"
  gitea="$(cd "$(dirname "$gitea")" && pwd)/$(basename "$gitea")"
  as_gitea "$gitea" migrate --config "$config" >"$work/migrate.log" 2>&1
  nohup bash -c "$(declare -f as_gitea); dir='$dir'; as_gitea '$gitea' web --config '$config'" >"$work/gitea.log" 2>&1 &
  echo $! > "$dir/gitea.pid"
  for _ in $(seq 1 60); do
    curl -sf "$url/api/v1/version" >/dev/null && break
    sleep 1
  done
  curl -sf "$url/api/v1/version" >/dev/null || { log "Gitea did not start; see $work/gitea.log"; tail -20 "$work/gitea.log" >&2; exit 1; }

  as_gitea "$gitea" admin user create --config "$config" --username plugindev --password "$password" \
    --email plugindev@localhost.test --admin --must-change-password=false >/dev/null
  as_gitea "$gitea" admin user create --config "$config" --username alice --password "$password" \
    --email alice@localhost.test --must-change-password=false >/dev/null
  local token
  token="$(api POST /users/plugindev/tokens \
    '{"name":"plugin-tests","scopes":["write:repository","write:user","write:issue"]}' | json "['sha1']")"

  fixture "$work"
  log "Gitea $version is running at $url"
  echo "export GITEA_DEV_URL=$url"
  echo "export GITEA_DEV_TOKEN=$token"
}

# push REMOTE BRANCH: pushes and waits until Gitea has processed the push
# (it does so in the background; a pull request for a branch it hasn't
# seen yet fails with 404).
push() {
  local remote="$1" branch="$2" sha
  sha="$(git rev-parse "$branch")"
  git push -q "$remote" "$branch" 2>>"$work/git.log"
  for _ in $(seq 1 60); do
    [[ "$(api GET "/repos/plugindev/pr-test/branches/$branch" 2>/dev/null | json "['commit']['id']" 2>/dev/null)" == "$sha" ]] && return
    sleep 0.5
  done
  log "Gitea did not pick up the push of $branch"; exit 1
}

# The repository plugindev/pr-test described in
# docs/local-gitea-dev-instance.md.
fixture() {
  work="$1"
  local git_dir="$1/fixture"
  api POST /user/repos '{"name":"pr-test","default_branch":"main"}' >/dev/null
  api PUT /repos/plugindev/pr-test/collaborators/alice '{"permission":"write"}' >/dev/null

  # Independent of the user's own Git settings (signing, hooks, push options).
  export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1
  rm -rf "$git_dir" && mkdir -p "$git_dir" && cd "$git_dir"
  git init -q -b main
  git config user.name plugindev && git config user.email plugindev@localhost.test
  local remote="http://plugindev:$password@localhost:$port/plugindev/pr-test.git"
  git remote add origin "$remote"

  printf '# pr-test\n\nFixture repository for the plugin tests.\n\nLine 5\nLine 6\nLine 7\n' > README.md
  mkdir -p src
  printf 'class Greeter {\n    fun greet(name: String): String {\n        val greeting = "Hello"\n        return "$greeting, $name!"\n    }\n}\n' > src/Greeter.kt
  git add -A && git commit -qm "Initial commit" && push origin main

  # PR #1 (plugindev): README changes in two commits, so one review thread
  # is anchored at an older commit, and a conflicting README change on main.
  git checkout -q -b readme-update
  sed -i.bak 's/^Line 5$/Line 5 changed on the branch/' README.md && rm README.md.bak
  git commit -qam "Change line 5" && push origin readme-update
  local first_sha; first_sha="$(git rev-parse HEAD)"
  local pr1; pr1="$(api POST /repos/plugindev/pr-test/pulls \
    '{"title":"Update README","head":"readme-update","base":"main","body":"Fixture PR"}' | json "['number']")"
  [[ "$pr1" == 1 ]] || { log "expected PR #1, got #$pr1"; exit 1; }
  api POST /repos/plugindev/pr-test/pulls/1/reviews \
    "{\"event\":\"COMMENT\",\"body\":\"\",\"commit_id\":\"$first_sha\",\"comments\":[{\"path\":\"README.md\",\"body\":\"About line 5\",\"new_position\":5}]}" \
    alice >/dev/null
  printf 'Line 8 added on the branch\n' >> README.md
  git commit -qam "Add line 8" && push origin readme-update
  local head_sha; head_sha="$(git rev-parse HEAD)"
  local review; review="$(api POST /repos/plugindev/pr-test/pulls/1/reviews \
    "{\"event\":\"COMMENT\",\"body\":\"\",\"commit_id\":\"$head_sha\",\"comments\":[{\"path\":\"README.md\",\"body\":\"About line 8\",\"new_position\":8}]}" \
    alice | json "['id']")"
  local comment; comment="$(api GET "/repos/plugindev/pr-test/pulls/1/reviews/$review/comments" '' alice | json "[0]['id']")"
  api POST "/repos/plugindev/pr-test/pulls/1/comments/$comment/replies" '{"body":"Reply from plugindev"}' >/dev/null || true

  git checkout -q main
  sed -i.bak 's/^Line 5$/Line 5 changed on main/' README.md && rm README.md.bak
  git commit -qam "Change line 5 on main" && push origin main

  # Issues #2 to #6, so alice's pull request becomes #7.
  for i in 2 3 4 5 6; do
    api POST /repos/plugindev/pr-test/issues "{\"title\":\"Issue $i\"}" >/dev/null
  done

  # PR #7 (alice): a clean change to src/Greeter.kt.
  git checkout -q -b alice-greeter
  git config user.name alice && git config user.email alice@localhost.test
  sed -i.bak 's/"Hello"/"Hi"/' src/Greeter.kt && rm src/Greeter.kt.bak
  git commit -qam "Greet more casually"
  push "http://alice:$password@localhost:$port/plugindev/pr-test.git" alice-greeter
  local pr7; pr7="$(api POST /repos/plugindev/pr-test/pulls \
    '{"title":"Casual greeting","head":"alice-greeter","base":"main","body":"From alice"}' alice | json "['number']")"
  [[ "$pr7" == 7 ]] || { log "expected PR #7, got #$pr7"; exit 1; }
  cd - >/dev/null
}

case "${1:-}" in
  start) start "${2:?usage: $0 start <gitea-version>}" ;;
  stop) stop ;;
  *) log "usage: $0 start <gitea-version> | stop"; exit 2 ;;
esac
