# Plan: PR Inline Comments & Review Feature

Goal: view PRs, read/reply to inline code comments inside the editor, create and
submit reviews (approve/comment/request changes) — from IntelliJ, against Gitea.

Background/context: `docs/research/github-gitlab-plugin-features.md` (GitHub/GitLab
plugin feature parity research), `docs/local-gitea-dev-instance.md` (local Gitea
Docker instance for manual testing).

Build order: sequential, one phase at a time, each phase verified against the
local Gitea dev instance before moving to the next. Not parallelized across
agents — phases depend on each other's data shapes (API client → comment UI →
review submit).

Status legend: `[ ]` not started, `[~]` in progress, `[x]` done.

## Phase 0 — deps & structure [x]
- [x] HTTP transport: JDK's `java.net.http.HttpClient` (no extra dependency,
      avoids classloader conflicts with IJ Platform's internal Ktor). Decided
      against Ktor client for this reason.
- [x] `kotlinx.serialization` (1.7.3) added for JSON DTOs — `build.gradle.kts` +
      `settings.gradle.kts` (`org.jetbrains.kotlin.plugin.serialization` 2.1.20).
      Verified: `./gradlew compileKotlin` succeeds.
- [ ] Package layout under `com.github.hypercookie.giteaplugin`:
      `api/` (client+DTOs), `accounts/` (settings/auth), `repo/` (git remote
      matching), `pullrequest/` (tool window, detail view, review), `settings/`.
      (created incrementally as each phase adds files, not as empty dirs)

## Phase 1 — account & auth [x]
- [x] `GiteaAccount` model: server URL + display name
      (`accounts/GiteaAccount.kt`).
- [x] Token stored via `PasswordSafe`, not plain XML
      (`accounts/GiteaAccountManager.kt`, app-level `@Service` +
      `PersistentStateComponent` for non-secret fields).
- [x] `Configurable` UI: Settings → Version Control → Gitea. Add/remove account,
      server URL field, token field, "Verify connection" action (calls
      `GET /api/v1/user` via a minimal `api/GiteaApiClient.kt`).
      (`settings/GiteaConfigurable.kt`, registered in `plugin.xml` as
      `applicationConfigurable` under `parentId="vcs"`.)
      Note: URL subpath tolerance not yet exercised/tested — plain text field,
      no normalization beyond trailing-slash trim. Revisit in Phase 3 when
      remote matching needs it.
- [x] Default account per project: `accounts/GiteaProjectAccountSettings.kt`
      (project-level `@Service` + `PersistentStateComponent`, stores selected
      account id). No UI to set it yet — wire into Phase 3/4 once there's a
      concrete "pick default account for this repo" UX moment.
- [x] Verified: `./gradlew compileKotlin` and `./gradlew buildPlugin` both
      succeed; configurable shows up during the build's searchable-options
      pass ("Found 392 configurables").
- [ ] Not yet manually tested in a running sandbox IDE against the local Gitea
      instance (Verify Connection button unclicked in practice) — do this
      before/alongside Phase 2.

## Phase 2 — Gitea API client
- [x] **Spike done** against local instance (`gitea/gitea:1.27.3`,
      `curl http://localhost:3000/swagger.v1.json`). Findings:
  - Review state enum (`CreatePullReviewOptions.event` /
    `SubmitPullReviewOptions.event` / `PullReview.state`): `APPROVED`,
    `PENDING`, `COMMENT`, `REQUEST_CHANGES`, `REQUEST_REVIEW`.
    **Request Changes is supported** — no fallback needed, risk resolved.
  - Inline comments go in the **same call** as review creation:
    `POST /repos/{owner}/{repo}/pulls/{index}/reviews` body
    (`CreatePullReviewOptions`) has `event`, `body`, `commit_id`, and a
    `comments[]` array of `{body, path, new_position, old_position}`
    (`CreatePullReviewComment`). One call submits state + all pending inline
    comments together — matches the Phase 8/9 pending-then-submit design.
  - Reply endpoint exists and matches Phase 7 exactly:
    `POST /repos/{owner}/{repo}/pulls/{index}/comments/{id}/replies`
    (`CreatePullReviewCommentReplyOptions` body).
  - Bonus, not originally planned: `POST .../pulls/comments/{id}/resolve` and
    `.../unresolve` exist — cheap addition once Phase 6/7 land.
  - Listing: `GET .../pulls/{index}/reviews` (all reviews),
    `GET .../pulls/{index}/reviews/{id}/comments` (comments of one review).
    Top-level (non-inline) PR comments are the shared issue-comment stream:
    `GET/POST .../issues/{index}/comments` (PRs are issues in Gitea's model).
  - `PullReviewComment` fields: `id, body, path, position (new line),
    original_position (old line), commit_id, diff_hunk, user, created_at,
    updated_at, pull_request_review_id`.
- [x] `GiteaApiClient` expanded (`api/GiteaApiClient.kt`): list/get PRs, get
      changed files, list issue (top-level) comments + post one, list
      reviews, list a review's inline comments, create/submit a review
      (with inline comments), reply to a comment, resolve/unresolve.
- [x] DTOs added under `api/dto/`: `PullRequest`/`PrBranchInfo`,
      `ChangedFile`, `IssueComment`/`CreateIssueCommentOption`, `PullReview`/
      `ReviewEvent`/`PullReviewComment`/`CreatePullReviewOptions`/
      `CreatePullReviewComment`/`CreatePullReviewCommentReplyOptions`.
- [x] **Verified against real data**, not just compiled: created a live test
      repo/PR (`plugindev/pr-test` #1, see
      docs/local-gitea-dev-instance.md "Test fixture" section) and wrote
      `src/test/kotlin/.../api/GiteaApiClientIntegrationTest.kt` — 7 JUnit
      tests hitting every new client method against the real local Gitea
      instance. All 7 pass (`GITEA_DEV_TOKEN=<token> ./gradlew test --tests
      '*GiteaApiClientIntegrationTest'`). Confirms real JSON responses decode
      correctly, including the `ReviewEvent` enum and nested `User`/branch
      objects — not just that the DTOs compile.

## Phase 3 — repo linking
- [ ] Enumerate `GitRepositoryManager` repos + remotes (git4idea) on project open.
- [ ] Match remote URL against configured account base URLs, subpath-tolerant
      (not bare-hostname like GitHub plugin — Gitea is always self-hosted, often
      behind a subpath). Prompt on ambiguity.
- [ ] Result: `GiteaRepoContext` (account + owner + repo) available per project.

## Phase 4 — PR list tool window
- [ ] New `ToolWindowFactory` ("Pull Requests"), replacing/extending the
      `MyToolWindowFactory` scaffold.
- [ ] List view: PR number, title, author, state. Manual refresh action.

## Phase 5 — PR detail view
- [ ] Opening a PR from the list → new `Content` tab. Overview (description,
      base/head branch) + changed-files list.
- [ ] Diff viewer per changed file via Platform diff API (`DiffContentFactory`,
      `DiffRequestChain`), not a custom diff view.

## Phase 6 — inline comments, read-only
- [ ] Fetch existing review comments for PR (file+line keyed).
- [ ] Render as gutter icons/inlay in the diff viewer at file+line (Review Mode
      pattern). Click → popup/panel showing comment thread.

## Phase 7 — reply to comment
- [ ] Reply action on existing comment thread → POST reply (exact endpoint per
      Phase 2 spike result).

## Phase 8 — pending review: add inline comment
- [ ] Gutter "+" action on any diff line → add comment, buffered locally as
      "pending" (not yet submitted).
- [ ] Visual indicator distinguishing pending vs submitted comments.

## Phase 9 — submit review
- [ ] Actions: Approve / Comment / Request Changes (fall back to
      Approve/Comment-only if Request Changes unconfirmed on target Gitea
      version, per Phase 2 spike).
- [ ] Bundle pending comments (Phase 8) into one
      `POST /pulls/{index}/reviews` call with chosen state.

## Phase 10 — polish
- [ ] Background execution via project `CoroutineScope` service — no EDT
      blocking on network calls.
- [ ] Loading/error states in UI, retry on failure.
- [ ] Manual test pass against local Gitea instance: create a real PR with
      comments, exercise full flow end to end.

## Open risks / verify before Phase 9
- ~~Review-state enum values~~ — resolved, see Phase 2.
- ~~Whether Gitea models inline comments as part of the review object or a
  separate endpoint~~ — resolved, see Phase 2 (same call).
- Minimum Gitea API version to target still undecided — spike was against
  1.27.3 (current `latest` tag); haven't checked how far back this schema is
  stable.
- `JpMand/gitea-idea-integration` has an open PR titled "Implement Gitea Pull
  Request Tool with UI and Review Features" — worth a read before Phase 4,
  may save time or reveal a dead end. Not yet reviewed.
