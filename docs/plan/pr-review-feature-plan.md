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
      **Later removed** (during UI pass 3): this file had no skip/assumption
      guard, so every one of its 7 tests fired a real HTTP call in CI too,
      where there's no Gitea instance — `./gradlew check` failed on every
      push/PR. Deleted; `GiteaApiClient` changes go back to ad-hoc `curl`
      verification against the live instance, same as this phase's spike.

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
- **Fixed after manual testing found it**: user reported "I do not see my
  own replies in the UI — just the original comment" after successfully
  posting a reply. Root cause was two separate bugs, both fixed:
  1. Comments were never grouped by line — each comment (including replies)
     got its own gutter icon showing only itself, when Gitea threads review
     comments by matching path+position (there's no explicit parent-comment
     id in the API), so a reply and its parent share a line and should share
     one icon showing the whole thread.
  2. No refresh after a successful reply — even once grouped, a just-posted
     reply wouldn't appear until the diff was reopened.
  `GiteaCommentDiffExtension` rewritten: `DiffCommentGutterController` groups
  comments by line into threads, tracks its own `RangeHighlighter`s per
  editor so it can remove and re-add them, and `showThreadPopup` displays
  every comment in the thread (not just one) with the reply box below all of
  them. On successful reply: `GiteaDiffCommentContext.fetchFreshComments()`
  (new) re-fetches, then `render()` rebuilds both editors' gutters from that
  fresh list — no diff reopen needed.
  Verified: `compileKotlin` + `buildPlugin` succeed.
  **Manually re-tested, confirmed working**: PR #1's `README.md` (4 comments
  all on line 1) correctly grouped into one icon showing all 4; PR #2's
  reply-then-refresh flow confirmed live-updating.
  One false alarm during this round: user initially reported PR #1 showing
  *zero* gutter icons. Added temporary diagnostic logging
  (`thisLogger().warn` in `onViewerCreated`/`installThreads`, since removed)
  rather than guessing — log showed the highlighter was created successfully
  at line 0 with no errors. Turned out to be scroll position: PR #1's diff
  auto-scrolls to its actual changed lines (~12/15), and the comment's line 1
  was simply off-screen above that. Not a bug — worth remembering as a
  UX-in-testing gotcha (a comment on an unchanged/scrolled-past line can look
  like a missing feature).

## Phase 8 — pending review: add inline comment [x]
- [x] `pullrequest/PendingReviewStore.kt` — one instance per opened PR detail
      tab (not per-diff), shared across every file diffed for that PR so
      pending comments survive closing/reopening a file's diff and Phase 9's
      submit can see comments added from any file. Pure in-memory, listener
      callback for UI to react to changes.
- [x] `GiteaCommentDiffExtension` rewritten around per-line rendering: each
      line gets **at most one** gutter icon — pending (orange/"Modified"
      icon) takes priority over an existing submitted thread (balloon icon),
      which takes priority over a plain "+" add-affordance (only shown on
      lines actually inside a `SimpleDiffChange` range for that side, via
      `SimpleDiffViewer.getDiffChanges()` / `getStartLine`/`getEndLine` — both
      confirmed public via `javap`). Known v1 limitation: a line with an
      existing submitted thread can't also get a "+" to add a second,
      separate pending comment on that same line — acceptable for now, not
      revisited this pass.
      **Why restrict "+" to diff-changed lines**: Gitea's API does **not**
      validate this server-side (confirmed in the Phase 2 hardening pass —
      it silently accepted comments on context lines and even completely
      out-of-diff lines), so enforcing "only comment on lines actually in the
      diff" is entirely this plugin's job, matching GitHub/GitLab's UX.
- [x] Add-comment popup is purely local (`PendingReviewStore.addComment`, no
      network call) — click a pending icon again to see the queued text and
      Discard it.
- [x] `PullRequestDetailPanel` shows a live "N pending comment(s)" label
      (via `PendingReviewStore`'s listener), independent of which file's diff
      is currently open.
- [x] Verified: `./gradlew compileKotlin` and `./gradlew buildPlugin` both
      succeed.

## Phase 9 — submit review [x]
- [x] Three buttons in `PullRequestDetailPanel` (always visible, not
      per-file): Approve / Comment / Request Changes. **No fallback needed**
      — Request Changes confirmed supported by the target Gitea version back
      in the Phase 2 spike.
- [x] `submitReview(event)` bundles every pending comment across every
      diffed file into one `client.createReview(...)` call
      (`CreatePullReviewOptions(event, commitId = pr.head.sha, comments = ...)`
      — `commitId` pinned per the DTO doc comment from the hardening pass, so
      the review doesn't show as stale on the next commit). On success:
      clears `PendingReviewStore` (across all files at once, not just the
      currently open one). On failure (e.g. Gitea's "approve your own pull
      is not allowed" for self-review, hit repeatedly during earlier
      testing): shown inline, pending comments preserved for retry.
- [x] Verified: `./gradlew compileKotlin` and `./gradlew buildPlugin` both
      succeed.
- [ ] **Not yet manually tested** — Phases 8 and 9 built together in one
      pass since they're only meaningfully testable together (queue a
      comment, then submit). Needs a `runIde` restart. Test plan: open a
      file's diff, click "+" on a couple of changed lines, add comment text,
      confirm pending icon appears and "N pending comment(s)" label updates;
      click a pending icon and Discard one to confirm removal; click
      "Comment" (not Approve/Request Changes — this account authored
      `pr-test`'s PRs, so those are expected to fail with Gitea's
      self-review restriction, same as integration-test findings) and
      confirm the pending comments land as a real review on the PR
      (check via `http://localhost:3000/plugindev/pr-test/pulls/<n>`, or the
      icons should switch from pending→submitted style on next diff open).

## Phase 10 — polish
- [ ] Background execution via project `CoroutineScope` service — no EDT
      blocking on network calls.
- [ ] Loading/error states in UI, retry on failure.
- [ ] Manual test pass against local Gitea instance: create a real PR with
      comments, exercise full flow end to end.

## Architecture pivot (after Phase 9) — working-copy comments

User feedback after manual-testing Phases 6-9: diff-viewer-only comments are
"building this wrong." Real want: (1) comments addable on **any** line, not
just `SimpleDiffChange` ranges; (2) PR list double-click **checks out the
branch** instead of opening a detail tab; (3) once on a branch with an open
PR — however you got there — comments show as gutter icons **in the regular
editor**, not only inside an explicit diff popup; (4) review actions
reachable from a persistent tool window regardless of which file is open.
Gutter icons OK for now; fully embedded inline UI (GitHub-plugin style) is
future work, not v1.

Decisions (confirmed with user via `AskUserQuestion`):
- **Diff tab stays** as a secondary "see the whole changeset" view — not
  replaced. Working-copy editor comments become primary for reading/adding.
- **Automatic activation**: any file opened on a branch with a matching open
  PR gets comment gutters, no explicit "start review" toggle.
- **Line drift v1 = accept + warn**: anchor comments by position at install
  time (`RangeMarker`-backed highlighter, same as today), show a staleness
  indicator when local `HEAD` != `pr.head.sha` or the document is modified,
  rather than building a real head-blob↔working-copy line mapping now
  (that's the `collaboration-tools`-dependent problem our stable-API-only
  decision was avoiding — see Phase 6 note. Revisit only if staleness proves
  unacceptable in practice).

Advisor-identified build order (dependency order, not user-facing order):

### Phase 11 — drop the changed-lines-only restriction [x]
- [x] Removed `addableLines`/`getDiffChanges()` gating — "+" on every line.
      Also removed the `onAfterRediff` listener (it existed only to fix a
      race in computing `addableLines`, which no longer exists) — render now
      happens immediately in `onViewerCreated`, no waiting on rediff.

### Phase 12 — extract gutter controller off `SimpleDiffViewer` [x]
- [x] New `pullrequest/comments/CommentGutterController.kt` — takes a plain
      `EditorEx` + `GiteaDiffCommentContext` + `useOriginalPosition: Boolean`
      instead of a `SimpleDiffViewer`/`Side`. `GiteaCommentDiffExtension` now
      just constructs two instances (base/head editor) and wires the
      pending-store refresh listener; all rendering/popup code moved to the
      shared controller. Verified: `./gradlew compileKotlin` succeeds.

### Phase 13 — branch → PR resolution [x]
- [x] `pullrequest/GiteaBranchPrResolverService.kt` (project-level `@Service`):
      `currentBranchName()` via `GitRepositoryManager...repositories.first().currentBranch?.name`;
      `resolveForCurrentBranch()` fetches `listPullRequests` and filters by
      `head.ref == branch`, picking the most-recently-`updatedAt` on a
      multi-match. Cached per branch name (including a cached "no match"),
      cleared by `invalidate()`.
- [x] `pullrequest/GiteaBranchChangeListener.kt`, implements git4idea's
      `GitRepositoryChangeListener`, calls `invalidate()` on every repo
      change. Registered declaratively via `<projectListeners>` in
      `plugin.xml` against topic `git4idea.repo.GitRepositoryChangeListener`
      — no manual `MessageBusConnection` needed.
      **Known v1 limitation**: doesn't refresh gutters already installed in
      editors opened before the branch switch, only newly-opened ones.
- [x] Verified: `./gradlew compileKotlin` + `./gradlew buildPlugin` succeed.

### Phase 14 — comments in the regular editor [x]
- [x] `pullrequest/comments/WorkingCopyCommentEditorListener.kt`, implements
      `FileEditorManagerListener.fileOpened`, registered via
      `<projectListeners>` against topic
      `com.intellij.openapi.fileEditor.FileEditorManagerListener` (fires
      automatically on every file open — the "automatic activation"
      decision above, no explicit toggle). Resolves repo context → PR for
      current branch (Phase 13) → fetches that path's comments → installs a
      Phase 12 `CommentGutterController` on the real `EditorEx`, always with
      `useOriginalPosition = false` (working copy is the PR's head side, so
      only the `position`/`new_position` field applies — never
      `originalPosition`). Tracks installed editors in a map to avoid
      double-installing on repeat `fileOpened` events, swept on
      dispose/`fileClosed`.
- [ ] **Staleness indicator not yet built** — deferred, not blocking v1 per
      the "accept + warn" decision; still needs a comparison of local `HEAD`
      vs `pr.head.sha` (and/or document-modified check) surfaced on the
      gutter icon/tooltip. Do this once the base flow is manually verified.
- [x] Add-comment popup reuses the same `PendingReviewStore`, now sourced
      from Phase 15's project-level `PendingReviewStoreService` so it's
      shared with the detail tab's diff view for the same PR.
- [x] Verified: `./gradlew compileKotlin` + `./gradlew buildPlugin` succeed
      (including the `buildSearchableOptions`/plugin-verify pass, confirming
      both new `<projectListeners>` entries are structurally valid).

### Phase 15 — project-level pending store [x]
- [x] `pullrequest/PendingReviewStoreService.kt` (project-level `@Service`):
      one `PendingReviewStore` per `"$owner/$repo#$prNumber"` key.
      `PullRequestDetailPanel` now calls `storeFor(...)` instead of
      constructing its own `PendingReviewStore()` — same instance a
      working-copy editor for that PR uses.
- [ ] **Tool-window review-action buttons not yet moved** — Approve/Comment/
      Request Changes still live only in `PullRequestDetailPanel`, not
      reachable from the tool window while just browsing a file on a PR
      branch (no detail tab open). This was bundled into Phase 15 in the
      original plan but is being split out — see Phase 17 below.
- [x] Verified: `./gradlew compileKotlin` + `./gradlew buildPlugin` succeed.

### Phase 17 — tool window review actions (was part of Phase 15) [x]
- [x] `pullrequest/ReviewActionsPanel.kt` — Approve/Comment/Request Changes +
      pending-count label, extracted out of `PullRequestDetailPanel` (which
      now just instantiates it) so it can be reused anywhere a
      `PendingReviewStore` needs a submit UI.
- [x] `pullrequest/CurrentBranchReviewPanel.kt` — resolves "the PR for the
      current branch" (Phase 13, forcing a cache `invalidate()` on manual
      refresh) and embeds a `ReviewActionsPanel` for it. Mounted at the
      **top of the PR list tab** (`PullRequestListPanel`, `BorderLayout.NORTH`,
      above the toolbar/list) so it's visible regardless of which file is
      being edited, without needing a detail tab open.
- [x] Verified: `./gradlew compileKotlin` + `./gradlew buildPlugin` succeed.

### Phase 16 — checkout on double-click [x]
- [x] `PullRequestListPanel` double-click now calls `checkoutBranch(pr)` —
      `GitBrancher.getInstance(project).checkout(pr.head.ref, false, listOf(repo), callback)`.
      Confirms first via `Messages.showYesNoDialog` if
      `ChangeListManager.getInstance(project).allChanges` is non-empty
      (dirty working tree) — this is a hard-to-reverse action on the user's
      tree. New "View Details" toolbar button opens the old detail-tab flow
      for the selected row instead (kept, per the "keep both" decision).
      **Known v1 limitation**: only resolves same-repo PRs (branch already
      exists locally or as `origin/<ref>`) — fork-based PRs not handled.
- [x] Verified: `./gradlew compileKotlin` + `./gradlew buildPlugin` succeed.

All of Phases 11-17 (the architecture-pivot work) built and **manually
verified by the user in a running sandbox**: checkout-on-double-click,
"View Details" fallback, working-copy editor gutters (comment-anywhere),
tool window's current-branch review panel, shared pending store across
detail-tab and working-copy editor — confirmed working.

## UI pass — embedded inline comments (modern IntelliJ native)

User's explicit design direction (asked per the "ask before UI styling"
rule): comments render as real embedded boxes in the editor (not popups),
expandable to a full conversation; the commented line is highlighted;
pressing gutter "+" opens an inline box to type a new comment; pending
comments surface in a top bar with a push/submit action; avatars shown in
comment boxes.

**Feasibility check done first** (same discipline as the original
diff-viewer spike): confirmed `com.intellij.openapi.editor.ComponentInlayRenderer`
(`app-client.jar`) — a **public**, `@ApiStatus.Experimental` (not
`@ApiStatus.Internal`) class implementing `EditorCustomElementRenderer`,
constructible with any `java.awt.Component` + `ComponentInlayAlignment`, used
via `InlayModel.addBlockElement(offset, InlayProperties, renderer)`. This is
the *base* class JetBrains' own bundled GitHub plugin builds its PR-review
inline UI on (`GHPRReviewThreadEditorInlayRenderer extends
com.intellij.collaboration.ui.codereview.editor.CodeReviewComponentInlayRenderer
extends com.intellij.openapi.editor.ComponentInlayRenderer` — confirmed via
`javap` on the bundled `vcs-github` + `intellij.platform.collaborationTools`
jars). We can use `ComponentInlayRenderer` directly and skip the
`collaboration-tools` layer entirely — real embedded Swing boxes without the
internal-API risk this plan has avoided from the start. `@ApiStatus.Experimental`
is a materially smaller risk than fully internal/unannotated classes: it's
public, documented-as-such API, just not signature-frozen across versions.
Also confirmed: `EditorNotificationProvider`/`EditorNotifications` (fully
stable, not experimental) for the "pending changes top bar" — the same
mechanism used for e.g. VCS/Maven "reload" banners.

**Root cause found for "boxes render but stay empty"**: `editor.inlayModel.addBlockElement(offset, InlayProperties, ComponentInlayRenderer)`
alone does **not** mount the component — it only registers a custom-painted
block element, so the hosted `JComponent`'s children never get a real
Swing layout pass (`component.parent` stayed `null`; forcing
`setBounds`+`validate()` ourselves didn't help either — confirmed via
targeted logging, then via `advisor()` after two failed fix attempts). The
actual fix, found by disassembling JetBrains' own GitHub plugin
(`EditorComponentInlaysUtilKt.insertComponent`,
`intellij.platform.collaborationTools.jar`): it doesn't call
`addBlockElement` directly either — it calls the top-level
**`com.intellij.openapi.editor.addComponentInlay(editor, offset, properties, component, alignment)`**
function (same public `ComponentInlay.kt` file as `ComponentInlayRenderer`,
`app-client.jar`), which delegates to
`com.intellij.openapi.editor.impl.ComponentInlayManager` — the piece that
actually mounts the component into the editor's real component hierarchy.
Switching `InlineComponentHost.install` to this function (instead of
manually constructing a `ComponentInlayRenderer` + calling
`addBlockElement`) fixed it immediately — **manually verified by the user**,
boxes render with real content now. Lesson for next time: when a stable
public class has a same-file top-level helper function, prefer it over the
lower-level primitive — it's very likely load-bearing, not just sugar.

### Phase 18 — inline comment/new-comment boxes (replace popups) [x]
- [x] `pullrequest/comments/InlineComponentHost.kt`: helper wrapping
      `com.intellij.openapi.editor.addComponentInlay(...)` (not the lower-level
      `addBlockElement` — see root-cause note above), returns the `Inlay`
      (itself a `Disposable`) so `CommentGutterController` can track/dispose/
      [`relayout`] (for resize on expand/collapse) per line.
- [x] `pullrequest/comments/CommentThreadBoxPanel.kt`: collapsed by default
      (avatar + author + first-line snippet + "+N more" if a thread), click
      anywhere expands to the full conversation (every comment, avatar +
      author + body) with a reply box at the bottom — replaces
      `showThreadPopup`. Styled with `RoundedLineBorder`, `JBColor`,
      `JBUI.Borders`/`JBFont` — "modern IntelliJ native" look, not raw Swing
      defaults.
- [x] `pullrequest/comments/NewCommentBoxPanel.kt`: text area + Add/Cancel,
      same visual language — replaces `showAddCommentPopup`. Gutter "+" stays
      as the launcher (cheap, discoverable, matches the user's own framing
      "when I press plus on the gutter").
- [x] `pullrequest/comments/PendingCommentBoxPanel.kt`: pending (not yet
      submitted) comment shown inline with a Discard action — replaces
      `showPendingPopup`.
- [x] `CommentGutterController` rewritten: keeps the "+" `GutterIconRenderer`
      per line as the click target, but on click/render now installs the
      matching inline box (thread/pending/new) instead of a `JBPopup`. Adds a
      background-color `RangeHighlighter` (`TextAttributes.backgroundColor`,
      not just a gutter icon) on any line with a thread or pending comment —
      "the line they belong to must be highlighted."
- [x] **Manually verified by the user in a running sandbox**: boxes render
      with real content (avatar, author, body), expand/collapse works, "+"
      opens a matching compose box.

### Phase 19 — avatars [x]
- [x] `GiteaUser.avatarUrl` (`avatar_url`) added to the DTO — Gitea already
      resolves this server-side (uploaded avatar or federated Gravatar), no
      need to hash emails ourselves.
- [x] `pullrequest/comments/AvatarService.kt`: async-loads + circularly-clips
      + caches (by URL+size) avatar `Icon`s, used inside
      `CommentThreadBoxPanel`.

### Phase 20 — pending-changes top bar [x]
- [x] `pullrequest/PrReviewEditorNotificationProvider.kt`, implements
      `EditorNotificationProvider` (`EP_NAME`, registered in `plugin.xml`).
      Shows a banner above the editor — reusing `ReviewActionsPanel` — when
      the open file's branch resolves to a PR **and** that PR's
      `PendingReviewStore` is non-empty; disappears again once submitted.
      `PendingReviewStoreService` calls
      `EditorNotifications.getInstance(project).updateAllNotifications()`
      whenever any store it hands out changes, so the banner reacts live
      without needing the file reopened.

Follow-up round after the UI pass fix:
- [x] **Staleness indicator** (Phase 14): `PrReviewEditorNotificationProvider`
      now also shows a lighter warning half of its banner (independent of
      the pending-comments half) when local repo `HEAD` != `pr.head.sha` or
      `FileDocumentManager.isFileModified(file)` — "comment positions may be
      inaccurate," per the "accept + warn" decision.
- [x] **Branch-change live refresh** (Phase 13): `WorkingCopyCommentEditorListener`
      now also implements `GitRepositoryChangeListener` (registered against
      both topics via `<projectListeners>` — confirmed the platform shares
      one instance per project across every topic a class is registered
      for). On `repositoryChanged`, invalidates the branch→PR cache,
      triggers `EditorNotifications.updateAllNotifications()`, and
      re-resolves every currently open editor (not just newly-opened ones)
      against the new branch. `GiteaBranchChangeListener` (the old,
      cache-invalidation-only class) deleted — merged in here.
- [ ] **Fork-based PR checkout** (Phase 16) still open — only same-repo
      branches resolve today. Lower priority (needs a second account/fork to
      even test); left as a known limitation.

Both **manually verified by the user in a running sandbox**: branch switch
with a file open live-updates gutters/boxes and the top banner; editing (or
committing ahead) on a PR branch surfaces the staleness warning banner.

## UI pass 2 — native IntelliJ look, Gitea branding

User's explicit direction: "research how to build modern nice looking
intellij idea uis and rework the uis of this plugin. I want the gitea icon
and such. I want modern ui elements. They should look like intellij
themselves put them there."

Research done first (WebSearch/WebFetch, not guessed):
- **Gitea logo**: official mark from Gitea's brand assets (mirrored on
  Wikimedia Commons), **MIT-licensed** — free to use/redistribute, no
  trademark blocker for a dev tool identifying the service it integrates
  with. Used as `META-INF/pluginIcon.svg` (full color) and a derived
  monochrome tool-window icon pair (`icons/gitea.svg` / `gitea_dark.svg`,
  `#6C707E`/`#AFB1B3` per JetBrains' tool-window icon convention —
  simplified from the source SVG's two-color (white teabag + green cup)
  artwork into a single-fill silhouette, dropping the teabag inner-detail
  path, since it wouldn't read at 16px anyway).
- **Kotlin UI DSL v2** (`com.intellij.ui.dsl.builder`, `panel {}`): per
  JetBrains' own docs, explicitly **not** intended for tool-window controls
  — it's for dialogs/Settings pages with state-bound inputs. Right fit for
  `GiteaConfigurable` (a Settings page); wrong fit for the tool window/list/
  detail panels, which should stay on `SimpleToolWindowPanel` +
  `ActionToolbar` + `ColoredListCellRenderer`/`SimpleColoredComponent` — the
  same primitives JetBrains' own tool windows (VCS log, Git branches, etc.)
  are built on.
- Confirmed via `javap`: `SimpleToolWindowPanel` (toolbar+content shell),
  `ColoredListCellRenderer<T>`/`SimpleColoredComponent.append(text,
  SimpleTextAttributes)` (rich, colored, icon-bearing list rows — no more
  plain `label.text = "..."` renderers), `ActionManager.createActionToolbar`.

Also removed as part of this pass: the unmodified plugin-template scaffold
(`MyToolWindowFactory`/`MyToolWindow` — an unrelated "shuffle a random
number" tool window tab — and `MyProjectActivity`, both still present and
flagged since Phase 4 as "remove in Phase 10 cleanup"; the generic template
`<description>`). `MyProjectService`/`MyBundle` kept — `MyPluginTest` still
exercises them directly.

### Phase 21 — Gitea icon [x]
- [x] `META-INF/pluginIcon.svg` — full-color official Gitea logo.
- [x] `icons/gitea.svg` + `icons/gitea_dark.svg` — monochrome tool-window
      variant, `icons/GiteaIcons.kt` holder (`IconLoader.getIcon`).
- [x] Wired into the tool window (`icon="...GiteaIcons.Gitea"` on
      `<toolWindow>`) and the `"Gitea"` notification group.
- [x] Template scaffold removed: `MyToolWindowFactory.kt`,
      `MyProjectActivity.kt`, their `plugin.xml` entries, generic
      `<description>` replaced with a real one.
- [x] Verified: `./gradlew compileKotlin`, `./gradlew test --tests
      '*MyPluginTest*'` (confirms removing the scaffold didn't break the
      still-used `MyProjectService`/`MyBundle`) both succeed.

### Phase 22 — tool window shell + PR list [x]
- [x] `PullRequestListPanel` rebuilt on `SimpleToolWindowPanel` with a real
      `ActionToolbar` (Refresh, View Details — `AnAction`s with icons via
      `getActionUpdateThread() = ActionUpdateThread.EDT`, not raw
      `JButton`s). `GiteaPullRequestsToolWindowFactory` needed no changes —
      `SimpleToolWindowPanel` is still just a `JComponent`.
- [x] PR list rows via `ColoredListCellRenderer`: icon per state
      (`AllIcons.Vcs.Branch` open/draft, `AllIcons.Vcs.Merge` merged,
      `AllIcons.General.Error` closed), colored bold state text (GitHub's
      familiar green/purple/red/grey convention — recognizable, not
      reinvented), number/title/author as separate styled fragments instead
      of one plain concatenated string.

### Phase 23 — detail panel + changed-files list [x]
- [x] `PullRequestDetailPanel`'s changed-files list gets the same
      `ColoredListCellRenderer` treatment (status icon —
      Add/Remove/MoveTo2/Edit — + colored +N/-N). Overview section restyled:
      `JBFont.h2()` title with a branch/merge icon, colored inline state
      badge (HTML label), `JBUI.Borders` spacing instead of raw `BoxLayout`
      + default-font labels.

### Phase 24 — settings page via Kotlin UI DSL [x]
- [x] `GiteaConfigurable`'s account list gets a `ColoredListCellRenderer`
      (Gitea icon + name + greyed server URL, was a bare `toString()`);
      list/details split moved from raw `BorderLayout` WEST/CENTER to
      `OnePixelSplitter` (the native resizable master-detail pattern, e.g.
      Live Templates); `verifyConnection()` now sets a success/failure icon
      on the status label, not just text. The details form was already
      `panel {}`-based from Phase 1 (the right call per the Kotlin UI DSL
      research above) — left as-is.

### Phase 25 — comment boxes / banners polish [x]
- [x] Gitea icon added to the pending-changes top bar's PR label and
      `CurrentBranchReviewPanel`'s status label; its "Refresh" `JButton`
      swapped for an `ActionLink` (the lighter, native pattern for an inline
      banner action). `CommentBoxPanels`/`PrReviewEditorNotificationProvider`
      were already on `JBUI`/`JBColor` from the prior UI pass — no changes
      needed there.
- [x] Verified: `./gradlew compileKotlin`, `./gradlew buildPlugin`, and
      `./gradlew test --tests '*MyPluginTest*' --tests '*GiteaUrlUtilTest*'`
      all succeed.

## UI pass 3 — match JetBrains' own GitHub Pull Requests plugin UI

User direction: look at
https://www.jetbrains.com/help/idea/work-with-github-pull-requests.html,
download its screenshots, implement this plugin's UI to match. 13 screenshots
fetched to scratchpad and reviewed (`incoming_pull_requests.png`,
`pull_request_options.png`, `gh_pending_updates.png`, `view_pull_request.png`,
`local_branch_from_pr.png`, `disable_review_mode.png`,
`gh_review_mark_gutter.png`, `github_pull_request_review_view_diff.png`,
`gh_add_suggestion.png`, `comment_options.png`,
`approve-requestchanges-comment-gh.png`, `gh_merge_pr.png`,
`gh_merge_pr_no_review.png`).

**Scope note**: matching *layout, structure, and interaction patterns* —
master-detail split, filter bar, timeline feed, inline review-comment cards,
submit-review popup, merge-dropdown. Not pulling JetBrains/GitHub's actual
icon or avatar image assets into this plugin — those are their branded art,
and we already have our own (`GiteaIcons`, `AllIcons.*`, Gitea's logo). Same
approach as UI pass 2.

Gap analysis — current plugin vs the reference UI, by screenshot:

- `incoming_pull_requests.png` (list): search box + filter/sort row (State,
  Author, Label, Assignee, Review, Sort dropdowns), label tag icon on a row.
  **We have**: plain list, Refresh/View Details toolbar only, no search, no
  filters, no labels. Gap: Phase 26.
- `pull_request_options.png` / `view_pull_request.png` (detail pane):
  "View Timeline" link, "Changes from N commits ▾" + branch/tag chip row,
  file tree grouped by directory with per-file comment-count badges,
  "Missing reviewer" / "Waiting for review from X" status row,
  "Request Review…" / "Submit Review (N)…" button with `⋮` overflow.
  **We have**: flat changed-files list (no commit grouping), no timeline, no
  reviewer-request concept at all, review actions as always-visible buttons
  not a popup-triggering button. Gaps: Phase 27 (timeline), Phase 28 (submit
  popup), Phase 32 (reviewer request), Phase 35 (commits/branch chip).
- `gh_pending_updates.png`: blue-dot badge on the tool window icon for
  unseen updates. **We have**: no such badge. Gap: Phase 36 (small).
- `local_branch_from_pr.png`, `disable_review_mode.png`: branch checkout +
  a popup to toggle review-mode on/off. **We have**: checkout-on-double-click
  (Phase 16) already covers the checkout half; no explicit review-mode
  toggle, but our "automatic activation" decision (architecture pivot,
  after Phase 9) was a deliberate simplification of this — not revisiting
  unless it causes real friction.
- `gh_review_mark_gutter.png`: pink/magenta vertical bar on changed lines +
  gutter speech-bubble icon. **We have**: background-color highlight +
  gutter icon already (Phase 18) — different color choice only, trivial.
- `github_pull_request_review_view_diff.png`: unrelated editor
  intention-action toolbar (code-suggestion popup), not GH-PR-specific —
  **out of scope**, skip.
- `gh_add_suggestion.png`: inline compose box with a split button
  "Start Review ▾" (dropdown: "Add Single Comment"). **We have**: single
  "Add" button only (`NewCommentBoxPanel`, Phase 18), no start-review/
  single-comment distinction — minor, folds into Phase 28.
- `comment_options.png`: hover-reveal edit/delete/react icons on your own
  comment, 👍 reaction pill with count, Reply/Resolve text links.
  **We have**: reply box (Phase 7) but **no Resolve button wired in UI**
  despite `resolveComment`/`unresolveComment` already existing in
  `GiteaApiClient` since Phase 2/6 hardening — dead capability. No
  edit/delete, no reactions. Gaps: Phase 29 (resolve — cheap, API already
  there), Phase 30 (edit/delete — needs an API spike), Phase 31 (reactions —
  needs an API spike).
- `approve-requestchanges-comment-gh.png`: "Submit Review" as a popup card
  (title + pending-comment count + trash/close icons, big textarea, then
  Approve/Request Changes/Comment as three buttons in the card), opened from
  a bottom "Submit Review (N)…" button. **We have**: `ReviewActionsPanel`'s
  three buttons always inline, no popup, no review-body textarea threaded
  into `createReview`'s `body` field (currently likely empty/unused — verify
  during Phase 28). Gap: Phase 28.
- `gh_merge_pr.png` / `gh_merge_pr_no_review.png`: **Merge.../▾ dropdown**
  (Merge, Squash and Merge, Rebase) + implicit Close. **We have**: no merge
  or close feature at all — this plugin has never had one. Gap: Phase 34,
  the biggest net-new feature in this pass, needs its own API spike (Gitea's
  merge endpoint, allowed-methods per repo setting, close-PR endpoint).

Build order (spike-first for anything not yet confirmed against the Gitea
API, matching this plan's established discipline — e.g. Phase 2/6/18's
"confirm before building" pattern). **Local Gitea dev instance was down
when this plan was written (`curl localhost:3000` failed) — first step of
every spike-flagged phase is bringing it back up and re-running
`swagger.v1.json`,** not assuming the Phase 2 findings still cover these
new endpoints.

### Phase 26 — PR list: search + filter/sort bar [x]
- [x] **Spike done**: local Gitea instance was up after all (container
      `gitea-plugin-dev` already running; earlier "DOWN" check in this plan
      was from a different shell). Re-fetched `/swagger.v1.json` fresh.
      `GET .../pulls` supports `state` (open/closed/all), `sort` (oldest,
      recentupdate, recentclose, leastupdate, mostcomment, leastcomment,
      priority), `poster` (exact author login), `labels` (label **IDs**,
      not names — needs Phase 33's label decoding first), `milestone`. No
      `assignee` or review-state query param on this endpoint at all (the
      richer `q`/`created_by`/`assigned_by` filters live on
      `/issues?type=pulls` instead, a different response shape than
      `PullRequest` — not worth switching the data source just for this).
      Decision: **State and Sort are server-side** (`listPullRequests` now
      takes `sort`/`poster` params); **search text and Author filter
      client-side** against the already-loaded page — simpler, no extra
      round trip, and Gitea's `poster` needs an exact login the Author
      dropdown already derives from loaded data for free. Label/Assignee
      dropdowns deferred to Phase 33.
- [x] `PullRequestListPanel`: search field (live, `DocumentListener`) +
      State/Author/Sort `JComboBox`es above the list, `CurrentBranchReviewPanel`
      kept above the new filter bar (was already there).
      **Bug caught before testing**: first draft added both
      `currentBranchReviewPanel.component` and the new filter-bar panel at
      `BorderLayout.NORTH` on the same parent — `BorderLayout` silently
      drops the first on a duplicate-region add, which would have made the
      current-branch panel disappear. Fixed by nesting them in one NORTH
      sub-panel before `compileKotlin` was run, so it never reached a build.
- [x] Verified: `./gradlew compileKotlin` and `./gradlew buildPlugin` both
      succeed.
- [ ] Not yet manually tested in a running sandbox IDE.

### Phase 27 — Timeline tab [x]
- [x] **Spike done**: `GET .../issues/{index}/timeline` exists (PRs are
      issues), confirmed shape via `TimelineComment` in a fresh
      `/swagger.v1.json` plus real data from `plugindev/pr-test#1`. Real
      `type` values seen: `pull_push` (body is a JSON string
      `{"is_force_push":bool,"commit_ids":[...]}`, not a nested object —
      worth remembering, easy to miss), `review` (body = review summary
      text, blank when a review has no summary comment), `comment` (plain
      top-level comment). No `label`/`assignee`/`milestone` events in real
      data, so those are decoded defensively but not specially rendered —
      not worth building UI for a shape we haven't verified.
      Also confirmed a dedicated `GET .../pulls/{index}/commits` endpoint —
      used instead of trying to reconstruct commit details from
      `pull_push`'s bare `commit_ids` array.
- [x] New DTOs: `api/dto/TimelineEvent.kt` (`TimelineEvent`, `TimelineLabel`,
      `PullPushBody`), `api/dto/GitCommit.kt` (`GitCommit`/`CommitDetail`/
      `CommitSignature`). `GiteaApiClient.getTimeline`/`getPullRequestCommits`
      added.
- [x] New `pullrequest/TimelinePanel.kt`: chronological feed — avatar +
      bold author + timestamp per entry (`comment`/non-blank `review` as a
      full comment card reusing `CommentBoxPanels.kt`'s `boxPanel`/
      `avatarLabel`/`readOnlyBody`/`escapeHtml` helpers, now `internal` instead
      of file-private so this file can reuse them instead of duplicating);
      `pull_push` rendered as "`<user>` added N commit(s)…" + an indented
      hash/message/author/time line per commit (cross-referenced from the
      `/commits` fetch by SHA); any other `type` falls back to a generic
      "`<user>` `<type>`" line. Bottom compose box posts a new top-level
      comment via `createIssueComment`, then reloads the feed.
- [x] Wired: `PullRequestDetailPanel` takes an `onViewTimeline: () -> Unit`
      callback, renders it as an `ActionLink("View Timeline")` under the
      branch/state line (matching the existing `ActionLink` convention from
      `CurrentBranchReviewPanel`). `PullRequestListPanel.openTimelineTab`
      opens/reselects a dedicated `"#N timeline"` tool-window tab —
      same dedupe-by-tab-name pattern as the existing detail tab, not a
      split view inside the detail tab (simpler, lower risk, no layout
      rework of an already-working panel).
- [x] Verified: `./gradlew compileKotlin` and `./gradlew buildPlugin` both
      succeed.
- [x] **Manually tested, bug found and fixed**: user reported timeline
      comment boxes were "ugly as hell" — flat oversized boxes with a hard
      black border and monospace text instead of normal prose. Root cause:
      `commentCard` reused `CommentBoxPanels.kt`'s `readOnlyBody` (a
      `JBTextArea`), which is correct for the editor-inlay comment boxes
      it was built for — there `InlineComponentHost` hands the inlay a known
      width before layout, so the text area's line-wrap math has something
      to wrap against. Inside `TimelinePanel`'s plain `BoxLayout` column in
      a scroll pane, nothing constrains that width first, so the text area
      reports a huge unwrapped preferred size — the oversized box; the
      border just looked worse stretched that big (same `boxPanel()` style
      already approved for the gutter comment boxes, not actually changed).
      Fixed by rendering the body as an HTML `JBLabel` with an explicit
      `<body style='width: 480px'>` instead — forces wrapping at a fixed
      pixel width independent of container layout, and uses the UI's normal
      proportional font instead of the text area's monospace default. Not
      real Markdown rendering (no bold/code/link parsing) — out of scope
      for this fix, just "looks like prose, not a code block."
      Refresh-button report from the same round turned out not to be a bug:
      confirmed with the user that the current-branch panel's "Refresh"
      link does refetch (status text flashes), it just no-ops when nothing
      actually changed — expected, not fixed.
      Verified: `./gradlew compileKotlin` + `./gradlew buildPlugin` succeed.
- [x] **Second bug found on re-test, screenshot provided**: the width fix
      above didn't fix it — cards still rendered huge with a large blank
      gap below the text. Real root cause: every row added to
      `feedColumn`'s `BoxLayout` (the comment card wrapper, the system-event
      row, the commit-push column, and the individual commit rows inside
      it) left its `maximumSize` at the Swing default, which is effectively
      unbounded. A vertical `BoxLayout` asks each child's `getMaximumSize()`
      to decide how to divide up leftover space in the scroll viewport, so
      every row got stretched to fill an equal share of it — hence
      same-ish-sized giant boxes regardless of actual content length. The
      earlier width-only fix was real (fixed the font/wrapping) but
      incomplete — masked by this second bug, which produced the same
      "huge box" symptom for a different reason. Fixed with a `capHeight()`
      helper (`maximumSize = Dimension(MAX_VALUE, preferredSize.height)`,
      called *after* a component's children are added so `preferredSize`
      reflects real content) applied to every row added to any `BoxLayout`
      column, nested ones included. Also tightened spacing while in there:
      `feedColumn` gets outer padding, each entry gets a consistent bottom
      margin instead of the previous inconsistent per-type padding, commit
      rows get a touch of their own vertical breathing room.
      Verified: `./gradlew compileKotlin` + `./gradlew buildPlugin` succeed.
- [x] **Follow-up round, user happy with the sizing fix but asked for two
      more things**: (1) real Markdown rendering — comment bodies were
      still plain-text-escaped, not parsed; (2) "make it look more like a
      timeline," specifically a connecting line down the left edge with a
      dot per event (referencing a generic timeline-infographic screenshot
      for the *shape* only — single left-side line+dots, not that image's
      colors/branding, which aren't part of this codebase).
  - Added `org.commonmark:commonmark:0.21.0` (`build.gradle.kts`) — small,
    pure-Java, no transitive deps, same "avoid classloader conflicts with
    the IJ Platform" reasoning Phase 0 used for picking the HTTP client.
    `wrappedBody` now parses real Markdown (`Parser`/`HtmlRenderer`,
    `escapeHtml(true)` so literal HTML a commenter typed can't inject
    markup) instead of escape-and-`<br>`. `<img>` tags stripped from the
    rendered HTML afterward — Swing's `JLabel` HTML view fetches `<img
    src>` eagerly and unconditionally, so a PR comment's Markdown image
    syntax would otherwise make the IDE fetch an arbitrary attacker-chosen
    URL just from opening the timeline. Kept the fixed-width `<body
    style='width:...'>` wrapping trick from the previous fix (still needed
    for the same reason: forces wrap at a known pixel width regardless of
    container layout) plus a tiny inline stylesheet zeroing `<p>` margins
    (commonmark wraps every block in `<p>`, which carries default Swing
    HTML margins that would have reintroduced extra vertical gaps).
  - New `TimelineGutter` (private `JComponent`, custom-painted): a vertical
    line + colored dot per row, `drawAbove`/`drawBelow` flags skip the line
    past the first/last event. Dot color varies by event type, reusing
    existing palette values already used elsewhere in this codebase rather
    than inventing new ones (comment = blue, review = the same purple as
    "merged" state, commit push = the same green as diff additions, other
    = gray). Each row is now `BorderLayout(WEST=gutter, CENTER=content)`;
    the gutter paints for the row's *entire* height (via `BorderLayout`
    stretch, not its own small `preferredSize`), so consecutive rows'
    segments align into one unbroken line even though each is a separate
    component — the trick is putting all of a row's spacing/margin inside
    its `CENTER` content, never on the row wrapper itself, so the gutter
    has no gap to break the line at.
  - Verified: `./gradlew compileKotlin` + `./gradlew buildPlugin` succeed.
- [ ] Not yet re-tested in a running sandbox IDE after this round.
- [x] **Follow-up round 2**: user asked for one consistent comment-box style
      everywhere, "look how such a box might look in IntelliJ itself and
      copy that style." Spiked it the same way this plan has spiked
      everything else — `javap` on the bundled
      `intellij.platform.collaborationTools.jar` (confirmed present under
      the resolved `ideaIU-2025.2.6.2` platform distribution, not a new
      dependency) to see what JetBrains' own GitHub/GitLab comment box is
      actually built on: `com.intellij.collaboration.ui.codereview.comment.RoundedPanel`
      (background+border painted as one filled/stroked round-rect, real
      rounded corners) and `CodeReviewCommentUIUtil` (a `COMMENT_BUBBLE_BORDER_COLOR`
      token, an arc-radius constant, small icon-button factories for
      edit/delete/react). Not taken as a dependency (that's the bundled
      GitHub plugin's internal module, same reasoning as the original
      diff-viewer-extension decision to stay off `collaboration-tools`) —
      used as a reference to build our own equivalent.
  - Found the actual inconsistency this was about: `CommentBoxPanels.kt`'s
    `boxPanel()` (the editor-inlay gutter comment boxes, Phase 18) and
    `TimelinePanel`'s comment cards both called the *same* `boxPanel()`
    function already, so the box chrome was already shared — but body
    rendering wasn't: the gutter boxes still used `readOnlyBody` (a plain
    `JBTextArea`, pre-dating this round's Markdown work), while only the
    timeline got the commonmark upgrade. Fixed at the root: `boxPanel()`
    rebuilt on a new `RoundedCardPanel` (our own simple version of the
    `RoundedPanel` idea above — `fillRoundRect`/`drawRoundRect`, not a
    square fill behind a separate rounded border), and Markdown rendering
    centralized into `CommentBoxPanels.kt` as one `MarkdownRendering`
    object used by two call sites: `markdownLabel(text, widthPx)` (fixed
    width — timeline, same reason as before) and `markdownView(text)` (a
    non-editable `JEditorPane`, sized by whatever width its container
    actually gives it — the editor-inlay boxes, via
    `InlineComponentHost`/`ComponentInlayAlignment.STRETCH_TO_CONTENT_WIDTH`,
    which already provides a real width before layout, the same mechanism
    that made the old `JBTextArea` wrap correctly there — kept that
    sizing path rather than risk regressing Phase 18's already-approved,
    previously-fragile inlay rendering, swapped only the widget type to
    gain HTML/Markdown). `TimelinePanel` now calls the shared
    `markdownLabel` instead of owning its own commonmark instances.
  - **Not done**: no Reply button added to timeline comment cards, even
    though the gutter boxes have one. Not an oversight — Gitea has no
    reply-to-a-specific-top-level-comment endpoint (only inline review
    comments support threaded replies, `POST .../comments/{id}/replies`);
    the timeline's single bottom compose box is the real equivalent
    already. Edit/delete/react buttons (seen in the `CodeReviewCommentUIUtil`
    spike above) also not added — no backing API wiring exists yet
    (Phase 30/31, still queued, need their own spike first).
  - Verified: `./gradlew compileKotlin` + `./gradlew buildPlugin` succeed.
- [ ] Not yet tested in a running sandbox IDE after this round.

### Phase 28 — Submit Review as a popup card
- [ ] Rework `ReviewActionsPanel`: bottom button becomes
      "Submit Review (N)…", opens a `JBPopup`-hosted card: title + pending
      count + discard(trash)/close icons, a review-body `JBTextArea` (wire
      into `CreatePullReviewOptions.body`, currently unused), then
      Approve/Request Changes/Comment buttons inside the card.
- [ ] `NewCommentBoxPanel`'s single "Add" button becomes a split button
      ("Start Review ▾" / "Add Single Comment") — cosmetic relabeling only,
      no new backend behavior (both already just add to
      `PendingReviewStore`).

### Phase 29 — wire up Resolve/Unresolve [cheap]
- [ ] `CommentThreadBoxPanel`: add Reply/Resolve text links under each
      comment (API already exists: `resolveComment`/`unresolveComment`,
      unused since Phase 2/6). Resolved threads get a visibly different
      (dimmed/collapsed) state.

### Phase 30 — edit/delete own comments
- [ ] Spike: confirm Gitea's `PATCH`/`DELETE` endpoints for a review
      comment and for a top-level issue comment (not yet in our client).
- [ ] Add `editComment`/`deleteComment` to `GiteaApiClient`; hover-reveal
      pencil/trash icons on a comment authored by the current user in
      `CommentThreadBoxPanel`.

### Phase 31 — reactions
- [ ] Spike: confirm Gitea's reaction endpoints for issue/review comments
      (`POST/DELETE .../reactions`) and response shape (who reacted, which
      emoji, counts).
- [ ] Reaction pill row (👍 + count, add-reaction affordance) in
      `CommentThreadBoxPanel`.

### Phase 32 — reviewer request + status row
- [ ] Spike: confirm Gitea's request-reviewers endpoint
      (`POST .../requested_reviewers`) and whether `PullRequest` already
      carries a requested-reviewers list we're not decoding yet.
- [ ] "Missing reviewer" / "Waiting for review from X" status row +
      "Request Review…" action in `PullRequestDetailPanel`.

### Phase 33 — labels/assignee chips
- [ ] Label tag icon + colored chip in list rows and detail overview
      (Gitea's label color is already in its API, DTO not yet decoding it).
      Feeds Phase 26's label filter.

### Phase 34 — merge / close
- [ ] Spike: Gitea's merge endpoint (`POST .../merge`, `Do` field for
      merge/squash/rebase strategy — confirm which are enabled by default
      and how a disabled strategy is reported) and close endpoint
      (`PATCH .../issues/{index}` state=closed, PRs are issues).
- [ ] "Merge…" split button + dropdown (strategy choices) and close action
      in `PullRequestDetailPanel`/`ReviewActionsPanel` area, confirmation
      dialog before merge (hard-to-reverse, matches the checkout
      confirmation precedent from Phase 16).

### Phase 36 — tool window unseen-update badge [small, do last]
- [ ] Blue-dot overlay icon on the tool window icon when a background
      refresh finds new activity on the current branch's PR (reuse Phase 13/
      20's branch→PR resolution + notification-provider refresh hook as the
      trigger).

### Phase 37 — rework "Add Account" into a proper dialog
User feedback after Phase 26 manual test: the "+" in Settings → Version
Control → Gitea's account list currently inserts an inert stub row
(`GiteaAccount("New account", "https://")`, `GiteaConfigurable.kt:87-93`)
straight into the list with a blank token already saved to `PasswordSafe`,
then relies on the user noticing the details panel, editing three fields,
and remembering to click "Save selected account" — no dedicated add flow,
unlike a native JetBrains account dialog (Git/GitHub Settings → Accounts
use a modal "Add Account" with inline verification before anything is
persisted).
- [ ] Spike: check whether a reusable `DialogWrapper`-based "Add Account"
      shape exists in the bundled platform/VCS jars worth confirming via
      `javap` (same discipline as Phase 5/6/18) before freehand design —
      if nothing reusable turns up, build a plain `DialogWrapper` from
      scratch, same as every other dialog in this plugin so far.
- [ ] New `settings/AddGiteaAccountDialog.kt` (`DialogWrapper`): Server URL
      field, Token field, inline "Verify connection" reusing the existing
      `GiteaApiClient.getCurrentUser()` check from
      `GiteaConfigurable.verifyConnection()` (extract shared logic rather
      than duplicate it). OK stays disabled until verification succeeds —
      nothing gets written to `PasswordSafe`/`GiteaAccountManager` until a
      real token against a real server is confirmed. Account name
      auto-filled from the verified user's login (editable), not a literal
      "New account" placeholder.
- [ ] `GiteaConfigurable`'s `ToolbarDecorator.setAddAction` opens this
      dialog instead of inserting a stub row directly; only calls
      `manager.addAccount(...)` on a successful OK.
- [ ] Editing an already-added account stays as-is (inline details panel +
      "Save selected account") — only the add path changes; that part
      already matches the native edit-in-place pattern JetBrains' own
      Settings pages use.
- [ ] Verified: `./gradlew compileKotlin` + `./gradlew buildPlugin` succeed.
- [ ] Manually tested in a running sandbox IDE.

Not planned: the floating code-suggestion mini-toolbar in
`github_pull_request_review_view_diff.png` — that's the Platform's own
editor intention-action UI, unrelated to the GitHub PR plugin, out of scope.

### Phase 38 — PR list redesign (toolbar, avatars, context menu) [x]
User direction: only one Refresh control in the whole tool window; drop the
toolbar's "View Details" button (felt out of place); move both View Details
and Checkout into a right-click context menu on the row instead; add author
avatars to each row; "orient yourself on the GitHub plugin again" (re-looked
at the same `incoming_pull_requests.png`/`pull_request_options.png`
screenshots from UI pass 3's research).

- [x] **Only one Refresh**: `CurrentBranchReviewPanel`'s own `ActionLink`
      "Refresh" removed (its `refresh()` stays public). The tool window's
      top-toolbar `RefreshAction` now calls both `refresh()` (the PR list)
      and `currentBranchReviewPanel.refresh()` — one button, refreshes
      everything, instead of two separately-triggered refreshes that
      confused manual testing earlier this session.
- [x] **Toolbar down to one action**: `ViewDetailsAction` removed from the
      `ActionToolbar`'s action group (now just `RefreshAction`).
- [x] **Row context menu**: `PopupHandler.installPopupMenu(prList,
      DefaultActionGroup(ViewDetailsAction(), CheckoutAction()), ...)` — the
      standard IntelliJ-native way to attach a right-click menu to a list
      (not a hand-rolled `JPopupMenu`). New `CheckoutAction` just calls the
      existing `checkoutBranch()` (same one double-click already used — no
      behavior change there, just a second way to reach it). `ViewDetailsAction`
      moved here unchanged from the old toolbar.
- [x] **Row redesign**: `PullRequestCellRenderer` rewritten from
      `ColoredListCellRenderer` (single-line `SimpleColoredComponent` text
      run) to a plain `ListCellRenderer<PullRequest>` building a real
      composed `JPanel` — needed for a second text line and a
      right-aligned avatar, matching the reference screenshot's row shape:
      bold title, gray "#N · created DATE, by AUTHOR" subtitle below it,
      trailing state text + comment count + author avatar
      (`comments.avatarLabel`, the same helper used everywhere else a
      Gitea user's avatar shows up — Phase 19).
- [x] Avoided two unverified-API guesses rather than compile-and-pray: a
      `JBFont` convenience method for the smaller subtitle font (used
      `title.font.deriveFont(size - 1f)` instead, since `Font.deriveFont`
      is guaranteed-stable JDK API) and an `AllIcons.General.Balloon`-style
      "comment" icon that isn't confirmed to exist (plain "`N` comments"
      text instead). Matches this plan's standing rule of confirming
      platform API before relying on it, not guessing icon/method names.
- [x] Verified: `./gradlew compileKotlin` + `./gradlew buildPlugin` succeed.
- [ ] Not yet tested in a running sandbox IDE.

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
