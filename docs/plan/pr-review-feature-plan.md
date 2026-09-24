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

## Phase 3 — repo linking [x]
- [x] Added `Git4Idea` as a plugin dependency (`<depends>` in `plugin.xml` +
      `bundledPlugin("Git4Idea")` in `build.gradle.kts`) — confirmed plugin id
      by inspecting the bundled `vcs-git` jar's own `plugin.xml`, id is
      `Git4Idea` (directory name changed, id didn't).
- [x] `repo/GiteaUrlUtil.kt`: parses both scp-like (`git@host:owner/repo.git`)
      and scheme-based (`https://`, `ssh://`) remote URLs into host +
      path-segments; matches a remote against a configured account's server
      URL by host + path-**prefix** (subpath-tolerant, not bare-hostname —
      see file header comment for why this differs from GitHub/GitLab).
      12 unit tests in `GiteaUrlUtilTest.kt`, all passing — https/ssh/scp-like
      forms, custom ports, subpath servers, case-insensitive host match,
      non-matching host/subpath, malformed owner/repo shape.
- [x] `repo/GiteaRepoLinkService.kt` (project-level `@Service`): enumerates
      `GitRepositoryManager.getInstance(project).repositories` and their
      remotes, matches each against every configured account, returns
      `GiteaRepoContext` (account + owner + repo + remote URL).
      `findAllMatches()` returns everything (for a future disambiguation UI);
      `resolveDefault()` returns a single unambiguous match, or falls back to
      the project's configured default account (Phase 1) when there's more
      than one candidate.
- [ ] **No disambiguation UI yet** — `resolveDefault()` returns null on an
      unresolved ambiguity rather than prompting. Add a prompt when Phase 4's
      tool window needs one (i.e. don't build UI speculatively here).
- [x] Verified: `./gradlew compileKotlin`, `./gradlew test --tests
      '*GiteaUrlUtilTest'`, and `./gradlew buildPlugin` all succeed with the
      new Git4Idea dependency wired in.

## Phase 4 — PR list tool window [x]
- [x] New `ToolWindowFactory`: `pullrequest/GiteaPullRequestsToolWindowFactory.kt`,
      registered as tool window id `"Gitea Pull Requests"` (anchored right),
      **added alongside** the `MyToolWindow` scaffold rather than replacing it
      — scaffold removal deferred to Phase 10 cleanup, not worth the risk of
      breaking `MyPluginTest`'s `testProjectService` for a cosmetic change now.
- [x] `pullrequest/PullRequestListPanel.kt`: list view (PR number, title,
      author, state via a custom `ListCellRenderer`), manual Refresh button.
      Resolves the repo via `GiteaRepoLinkService.resolveDefault()` (Phase 3)
      and the token via `GiteaAccountManager` (Phase 1); shows a clear status
      message when no repo is linked or no token is saved, instead of a raw
      error.
- [x] Verified: `./gradlew compileKotlin` and `./gradlew buildPlugin` both
      succeed.
- [x] **Manually verified by the user in a running IDE**: added an account,
      PR list loaded correctly for `~/dev-infra/pr-test`. (One false start:
      user's first `runIde` session had been started before Phases 1-5
      existed — stale sandbox process, explains an initial "nothing shows up"
      report. Restarting `runIde` fixed it. Worth remembering for future
      manual-test rounds: if a `runIde` session has been open across several
      rebuilds, restart it before trusting a "doesn't work" report.)

## Pre-Phase-5 hardening pass [x]

Before writing any UI-heavy code, resolved the two design-blocking unknowns
and fixed several correctness/leak issues flagged in review. All done and
verified (`compileKotlin`, `test`, `buildPlugin` all pass) before Phase 5 below.

- [x] **Position semantics, empirically confirmed**: `position`/`new_position`/
      `old_position` in Gitea's review-comment API are **real file line
      numbers**, not GitHub-style diff-hunk offsets. Proven by constructing a
      PR where file-line and hunk-offset would give different numbers
      (`@@ -9,10 +9,10 @@` hunk, edit at file line 15 = hunk-offset 9), posting
      a comment with `new_position: 15`, and inspecting the server-generated
      `diff_hunk` in the response: `@@ -13,3 +13,3 @@` — Gitea centered a
      fresh 3-line-context hunk exactly on file line 15, which is only
      possible if it read `15` as a real line number. Confirms the
      `NewLineNum`/`OldLineNum`/`LineNum` Go field names (§ Phase 2 spike) were
      the right signal. Practical effect: Phase 6 gutter placement maps
      directly to editor line numbers, no diff-offset translation needed.
      Also confirmed empirically: Gitea does **not** server-validate
      position against the actual diff — old-side, context-line, and
      completely out-of-diff positions were all accepted (HTTP 200). The
      plugin must self-restrict where the gutter "+" appears; the server
      won't catch a bad line.
- [x] **Diff-viewer extension point, confirmed to exist**: `com.intellij.diff.DiffExtension`
      (`onViewerCreated(FrameDiffTool.DiffViewer, DiffContext, DiffRequest)`),
      registered as `<diff.DiffExtension implementation="..."/>`, is a real,
      public, `dynamic="true"` extension point declared in the platform's own
      `PlatformExtensionPoints.xml` (confirmed by inspecting the bundled
      `app-client.jar`) and used internally by
      `ShowBlankDiffWindowDiffExtension`. This is the stable, documented way
      to reach a diff viewer's editors and attach gutter/inlay UI — not the
      internal `collaboration-tools` module GitHub/GitLab likely use (see
      Phase 5/6 §1 caveat). Phase 5's diff viewer and Phase 6's gutter
      comments should both build on this from the start.
- [x] **Shared `HttpClient`**: `GiteaApiClient` was building a new
      `java.net.http.HttpClient` (selector thread + executor) per instance,
      and a fresh instance is constructed on every UI action. Moved to a
      companion-object singleton shared across all `GiteaApiClient`s.
- [x] **Project/app `CoroutineScope` services introduced now, not deferred to
      Phase 10**: `util/GiteaCoroutineScopes.kt` — `GiteaAppCoroutineScope`
      (app-level, for the account Settings UI which has no project) and
      `GiteaProjectCoroutineScope` (project-level, for tool-window UI),
      both using the Platform's constructor-injected `CoroutineScope`
      light-service pattern (lifecycle-managed, no manual `Disposable`).
      `GiteaConfigurable.verifyConnection()` and `PullRequestListPanel.refresh()`
      both switched from raw `Thread { runBlocking { ... } }` +
      `SwingUtilities.invokeLater` to `scope.launch { ... withContext(Dispatchers.Main) { ... } }`.
      `Dispatchers.Main` resolves to the IDE's EDT dispatcher via the
      platform's own `MainDispatcherFactory` registration (confirmed present:
      `com.intellij.openapi.application.impl.EdtCoroutineDispatcher$Main.class`
      in `app-client.jar`) — no extra import needed.
      **Not yet manually verified in a running IDE** that EDT routing actually
      works end to end (only compiles); first `runIde` manual test should
      watch for this.
- [x] **`GiteaConfigurable.isModified()`/`apply()` were both inert**
      (hardcoded `false` / no-op), meaning Settings' OK/Apply buttons stayed
      disabled regardless of real pending edits, and clicking OK would
      silently drop an unsaved field edit if "Save selected account" wasn't
      clicked first. Added a `dirty` flag (set by field `DocumentListener`s
      and Add/Remove), `isModified()` returns it, `apply()` now commits a
      pending edit via `saveSelected()`.
- [x] **`CreatePullReviewOptions.commitId` guidance documented + demonstrated**:
      doc comment on the DTO explains it should be the PR's head SHA
      (`PullRequest.head.sha`) to avoid `PullReview.stale` becoming true as
      soon as the PR gets a new commit; the integration test's review-submit
      case now passes it, as the pattern Phase 9 production code should
      follow.
- [x] **Integration test brittleness fixed**: `lists reviews and their inline
      comments` / `replies to an inline review comment` previously used
      `.first()` on reviews/comments, which is order-dependent and breaks as
      the shared fixture PR accumulates more test-run data over time. Now
      anchor on a fixed, known comment body from the original fixture setup
      instead of positional indexing.
- [ ] **Not done, deliberately out of scope for this pass**: reading
      `JpMand/gitea-idea-integration`'s open PR ("Implement Gitea Pull Request
      Tool with UI and Review Features") — still the single highest-value
      unread prior art, specifically for *how* it attaches comments to the
      diff viewer. Worth a fork/read before Phase 6 (gutter rendering)
      specifically, even though Phase 5 (plain diff viewer, no comments yet)
      doesn't strictly need it.

## Phase 5 — PR detail view [x]
- [x] Double-clicking a PR row (`PullRequestListPanel`) opens (or re-selects,
      keyed by `#<number>` tab name) a `Content` tab in the same tool window —
      `pullrequest/PullRequestDetailPanel.kt`. Overview (title, head→base
      branches, state/draft, description) on top, changed-files list below.
- [x] Diff viewer: double-click a changed file → fetches base/head file text
      concurrently (`async`/`awaitAll`) via a new `GiteaApiClient.getFileContentOrNull`
      (Gitea's `contents` endpoint, base64-decoded; 404 → null, so added/removed
      files show one empty side) → `DiffContentFactory.create` +
      `SimpleDiffRequest` → `DiffManager.getInstance().showDiff(project, request)`.
      Platform's own diff viewer, not a custom one — and per the hardening
      pass, this is the same viewer `com.intellij.diff.DiffExtension` (Phase 6)
      will hook into.
- [x] Local clone of the `plugindev/pr-test` fixture set up at `~/dev-infra/pr-test`
      (all three branches: `main`, `feature`, `feature2`; remote URL stripped
      of the embedded credentials git needed for the initial clone) — ready
      for the manual `runIde` test below.
- [x] Verified: `./gradlew compileKotlin` and `./gradlew buildPlugin` both
      succeed.
- [x] **Manually verified by the user in a running IDE**: PR list loads,
      double-clicking a PR opens a detail tab, double-clicking a changed file
      shows a real diff in the Platform diff viewer. Confirmed working
      end-to-end. User noted the UI is "very rough" — expected, plain Swing
      scaffolding with no styling pass; polish is Phase 10, deliberately not
      done yet.

## Phase 6 — inline comments, read-only [x]
- [x] **Prior-art check done first, as planned**: read
      `JpMand/gitea-idea-integration`'s `GiteaPRDiffExtension`/`GiteaPRInlayModel`/
      etc (via a research fork — see conversation, not persisted as a
      separate doc). Findings: (a) they hook `DiffExtension.onViewerCreated`
      the same way we planned; (b) their code independently confirms
      position = 1-indexed real file line number (`line - 1` for editor
      conversion), corroborating our own empirical test; (c) their rendering
      is a full inlay-based comment UI built on `com.intellij.collaboration.*`
      — confirmed real, and confirmed to require
      `@Suppress("UnstableApiUsage")` throughout. **Deliberately not copied**:
      that module is exactly the internal/unstable dependency our plan chose
      to avoid (see Phase 5/6 §1 caveat in the research doc). Built our own
      version on stable primitives instead (below).
- [x] `client.getAllReviewComments()` added to `GiteaApiClient` — flattens
      every review's comments for a PR into one list.
- [x] `pullrequest/diff/GiteaCommentDiffExtension.kt` — extends
      `com.intellij.diff.DiffExtension`, registered via `<diff.DiffExtension>`.
      Reads a `GiteaDiffCommentContext` (filename + comments) off the
      `DiffRequest`'s user data (attached by `PullRequestDetailPanel.showDiff`
      before calling `DiffManager.showDiff`), gets both editors via
      `TwosideTextDiffViewer.getEditor1()/getEditor2()` (public, confirmed via
      `javap` on the bundled platform jar), and for each comment adds a
      `RangeHighlighter` with a `GutterIconRenderer` at `position - 1` /
      `originalPosition - 1` (0-indexed editor line). Click → a
      `JBPopupFactory` popup showing author + body. All stable, public
      Platform API — no `collaboration-tools` dependency.
- [x] Verified: `./gradlew compileKotlin` and `./gradlew buildPlugin` both
      succeed (searchable-options skipped in this build only because it
      conflicts with a concurrently-running manual-test `runIde` sandbox —
      not a code issue).
- [x] **Manually verified by the user in a running IDE** (fresh `runIde`
      restart, per the process lesson from Phase 5): gutter icons render
      correctly on PR #2's `README.md` diff. UI still "rough" per the user —
      expected, unstyled Swing, Phase 10 territory.

## Phase 7 — reply to comment [x]
- [x] `GiteaDiffCommentContext` extended with `owner`/`repo`/`prNumber`/
      `client` so `GiteaCommentDiffExtension` can make API calls, not just
      render data — needed for reply (and every later write action).
- [x] Comment popup (from Phase 6) extended with a reply text area + "Reply"
      button → `client.replyToComment(...)`
      (`POST /pulls/{index}/comments/{id}/replies`, confirmed endpoint from
      Phase 2 spike). On success: closes the popup, shows a notification
      (new `"Gitea"` notification group registered in `plugin.xml`). On
      failure: inline status text in the popup, button re-enabled to retry.
- [x] Verified: `./gradlew compileKotlin` and `./gradlew buildPlugin` both
      succeed.
- [ ] **Not yet manually tested** — needs a `runIde` restart (new
      `notificationGroup` registration). Test plan: open the same PR #2
      `README.md` diff, click a comment gutter icon, type a reply, submit,
      confirm it lands on the PR in the Gitea web UI
      (`http://localhost:3000/plugindev/pr-test/pulls/2`).
- **Known gap, not fixed here**: after a successful reply, the gutter icon
  set isn't refreshed — the new reply comment won't show its own icon until
  the diff is reopened. Acceptable for now (Phase 6/7 scope is "read +
  reply," not live-refresh); worth a follow-up once Phase 8/9 add more
  mutation flows that'll want the same refresh anyway.

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
