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
