# Migration to IntelliJ collaboration tools

Branch: `feature/collaboration-tools-migration` (from `main` at `3c6542c`).
Started 2026-10-14.

## Goal and rules

Rebuild the plugin's UI on `intellij.platform.collaborationTools`
(`com.intellij.collaboration.*`), the module the bundled GitHub and GitLab
plugins are built on. User decision: build with these APIs only, migrate
everything, and record every piece we have to implement ourselves.

Rules:

1. Use public `collaborationTools` and platform API. Never reference a class
   or member annotated `@ApiStatus.Internal`. `@ApiStatus.Experimental` is
   allowed (most of the module is experimental at package level, see
   below); each experimental area is noted in the ledger as an upgrade risk.
2. Where the official plugins call an internal helper, we write our own
   equivalent from public pieces and add a ledger entry.
3. Every phase builds green, passes `./gradlew test` and
   `./gradlew verifyPlugin` (configured to fail on internal API usage),
   deletes the code it replaces, and is committed on its own.
4. Each phase is marked "not sandbox-tested" until the user runs it.
5. Where the API we need is internal, mirror the corresponding
   intellij-community source (Apache 2.0) at the build we target
   (`idea/252.28539.54`), for both behavior and UI, and list the source
   files in `NOTICE` (user instruction, 2026-10-14).
6. `@ApiStatus.Internal` is often on single members, not whole classes
   (for example only the two-argument `createEditorInlayPanel` is
   internal; the one-argument overload is public). The class-level survey
   below over-counts; `verifyPlugin` is the authority.

Gates (in place since `42751ac`):

- `PluginDescriptorLoadTest`: fails if the descriptor's module dependency
  doesn't resolve (checked by breaking it on purpose; the test failed with
  "plugin disabled at load time").
- `verifyPlugin` against IU 2025.2.6.2, failure levels
  `COMPATIBILITY_PROBLEMS`, `INTERNAL_API_USAGES`, `INVALID_PLUGIN`.
  Baseline: compatible, 0 internal usages, 5 experimental usages (all in
  `InlineComponentHost`, removed in M2).

## API survey

`javap -v` over all 439 top-level classes in
`lib/modules/intellij.platform.collaborationTools.jar` (2025.2.6.2):

| Marker (class or member level) | Classes |
|---|---|
| none | 307 |
| `Experimental` | 52 |
| `Internal` (alone or combined) | 69 |
| other (`Obsolete`, `ScheduledForRemoval`, `NonExtendable`) | 11 |

`package-info` is `@Experimental` for most packages (`async`, `auth.ui`,
`ui.toolwindow`, `ui.util`, `ui.codereview.details.model`,
`ui.codereview.diff.model`, `ui.codereview.review`, `ui.codereview.timeline`,
and others), so in practice most of the module is experimental API.

Internal classes the GitHub plugin calls (found by scanning its class files
for `com/intellij/collaboration/` references and intersecting with the
internal list). Each one is something we must replace:

```
api.page.ApiPageUtil                      async.CoroutineUtilKt
async.ListLoader / ListLoaderKt           async.GraphQLListLoader
async.ReloadablePotentiallyInfiniteListLoader
async.MappingScopedItemsContainer         async.Updated
ui.CollaborationToolsUIUtil(Kt)           ui.Either
ui.codereview.comment.CodeReviewCommentUIUtil
ui.codereview.create.CodeReviewCreateReviewUIUtil
ui.codereview.details.model.CodeReviewChangeListViewModelBase
ui.codereview.details.model.CodeReviewCommitsChangesStateHandler
ui.codereview.editor.CodeReviewNavigableEditorViewModel
ui.codereview.editor.MutableCodeReviewEditorGutterChangesModel
ui.codereview.editor.ReviewInEditorUtil
ui.codereview.list.ReviewListComponentFactory
ui.codereview.list.search.ChooserPopupUtil
ui.codereview.list.search.ReviewListSearchPanelFactory
ui.codereview.review.CodeReviewSubmitPopupHandler
ui.codereview.timeline.TimelineDiffComponentFactory
ui.toolwindow.ReviewToolwindowProjectViewModel
ui.util.ActionUtilKt  ui.util.MigLayoutUtilKt  ui.util.SwingBindingsKt
```

Raw data (regenerable): the scratchpad `collab-status.txt` and
`gh-collab-refs.txt`; the commands are in this plan's git history.

## Phases

### M0 - foundation [x]
- [x] Branch, `bundledModule("intellij.platform.collaborationTools")` plus
      the `vcs.dvcs.impl`, `vcs.dvcs.impl.shared`, `vcs.log.impl` modules it
      needs on the compile classpath (without them Git4Idea's
      `GitRepository` supertypes stop resolving).
- [x] `<dependencies><module name="intellij.platform.collaborationTools"/>`
      in `plugin.xml`.
- [x] Descriptor load test, verifier gate. Display name `gitea-plugin` ->
      `Gitea` (verifier rejects the template word "plugin").

### M1 - view model layer [x]
Gitea data exposed as coroutine/`StateFlow` view models the collaboration
components consume. Keeps `GiteaApiClient`, DTOs and `PendingReviewStore`
(which gained a `StateFlow`).
- [x] `GiteaReviewSessionService`: current-branch PR as a flow, debounced
      on `GIT_REPO_CHANGE`, same PR updated in place.
- [x] `ReviewThreads` (grouping by path, side, line, `commit_id`) and
      `LineMapping` with JUnit tests (11 cases).
- [x] Per-PR data holder with its own `CoroutineScope`: details, review
      threads (grouped from `PullReviewComment`s), pending comments,
      current user; reload on demand.
- [x] Thread VM implementing `CodeReviewResolvableItemViewModel`.
      `CodeReviewFoldableThreadViewModel` not needed: the editor thread
      shows all comments, like GitHub's compact thread.
- [x] Reply and new-comment VMs extending
      `CodeReviewSubmittableTextViewModelBase`.

### M2 - review in the editor [x] (not sandbox-tested)
Replaces `CommentGutterController`, `InlineComponentHost`,
`WorkingCopyCommentEditorListener`, `CommentBoxPanels` (editor parts).
- [x] `EditorFactoryListener` installer (same shape as GitHub's
      `GHPRReviewInEditorController$InstallerListener`), one coroutine scope
      per editor, cancelled on editor release.
- [x] Line mapping (I6): fetch the file at each thread's `commit_id`, at
      `pr.head.sha` and at the merge base; `ComparisonManager.compareLines`
      gives anchor-commit->document (thread placement, outdated), document->
      head (new comments; lines inside local edits are not commentable) and
      merge base->head pushed through head->document (change markers, so
      local uncommitted edits are not shown as PR changes). Renamed files
      use `previous_filename` for the base. Pure logic covered by JUnit.
- [x] Session kept alive while the PR number is unchanged; a new head sha
      reloads in place (no inlay teardown, reply drafts survive).
      `GitRepositoryChangeListener` triggers are debounced.
- [x] Repository resolved per file (`getRepositoryForFile`), not
      `repositories.firstOrNull()`.
- [x] Banner condition changes from "comments may be on the wrong line"
      to "local HEAD != `pr.head.sha`" (GitHub's out-of-sync state).
- [x] `<editorFactoryListener>` added in the same commit that removes both
      `WorkingCopyCommentEditorListener` registrations.
- [x] Per-file model implementing `CodeReviewEditorModel<I>`
      (inlays + gutter controls) and `CodeReviewEditorGutterActionableChangesModel`.
- [x] Inlays via `renderInlays` + `CodeReviewComponentInlayRenderer`;
      gutter comment icons via `CodeReviewEditorGutterControlsRenderer.setupIn`;
      changed-line markers via `CodeReviewEditorGutterChangesRenderer.setupIn`.
- [x] Thread component from `CodeReviewChatItemUIUtil`,
      `TimelineThreadCommentsPanel`, `CodeReviewCommentTextFieldFactory`,
      `CommentInputActionsComponentFactory`.
- [x] Parked "new comment box doesn't close" bug: the code it lived in
      (`CommentGutterController`, `InlineComponentHost`,
      `WorkingCopyCommentEditorListener`) is deleted. New-comment inlays are
      now removed by the model (`newComments` state) and rendered by the
      platform's `renderInlays`. Marked "needs sandbox re-check" in
      `pr-review-feature-plan.md`.
- [x] Also fixed on the way: the PR diff's left side was fetched at
      `base.sha`; old-side comment lines refer to the merge base, so it now
      uses `merge_base` (`PullRequest.mergeBase` added).
- Implementation notes: new comments offer "Start Review" / "Add Review
  Comment" (pending) and "Add Single Comment" (immediate `createReview`
  with event COMMENT), with the platform's discard confirmation. Thread
  view models live in the editor scope and are not cancelled when a thread
  disappears until the editor closes (small leak, acceptable).

### M3 - review in the diff viewer [x] (not sandbox-tested)
Done together with M2, so the old controller can be deleted in one commit.
- [x] `GiteaCommentDiffExtension` on the same models and components:
      head editor shows new-side threads (`position`), base editor shows
      old-side threads (`original_position`).

### M4 - tool window shell [x] (not sandbox-tested)
- [x] Platform `manageReviewToolwindowTabs` with our
      `ReviewToolwindowViewModel`/`ReviewToolwindowProjectViewModel`
      (only the interface's default `refresh()` is internal; we don't
      override it) and `ReviewTabsComponentFactory`. One closable tab per
      opened PR; empty state links to Settings and Retry.
- [x] Anchor left, stripe title "Pull Requests" (id stays
      "Gitea Pull Requests" so it can't clash with the GitHub plugin).
      Refresh is a tool window title action.
- [x] Removed `CurrentBranchReviewPanel` (the official plugins have no
      such panel; the editor banner covers pending comments and submit for
      the current branch) and the old `PullRequestListPanel`.
- PR tabs still host the old `PullRequestDetailPanel` until M6.

### M5 - pull request list [x] (not sandbox-tested)
- [x] Done as below. List rows: platform `ReviewListComponentFactory`
      (labels, assignees, reviewers, state tag, conflict icon, comment
      counter). Lazy paging via `ReviewListUtil.wrapWithLazyVerticalScroll`.
      Context menu: View Pull Request (also Enter/double-click), Checkout,
      Open in Browser, Copy Link, Refresh List. Empty states as in GitHub.
- [x] `ReviewListViewModel`, `ReviewListItemPresentation`,
      `TagPresentation`, `UserPresentation`; search bar from
      `ReviewListSearchPanelViewModelBase`, `ReviewListSearchTextFieldFactory`,
      `DropDownComponentFactory`, `ReviewListQuickFilter`,
      `PersistingReviewListSearchHistoryModel`. Paging.

### M6 - pull request details
- [ ] `CodeReviewDetailsTitleComponentFactory`, `...DescriptionComponentFactory`,
      `...BranchComponentFactory` (`CodeReviewBranchesViewModel`),
      `...CommitsComponentFactory`, `...StatusComponentFactory`
      (`CodeReviewStatusViewModel`), `...ActionsComponentFactory`;
      changes via `CodeReviewChangeListComponentFactory`.

### M7 - timeline
- [ ] Timeline as an editor tab; items from `CodeReviewChatItemUIUtil`,
      `CodeReviewTimelineUIUtil`, `StatusMessageComponentFactory`.

### M8 - submit review
- [ ] `CodeReviewSubmitViewModel` with our own popup.

### M9 - accounts and settings
- [ ] `AccountManagerBase`, `PasswordSafeCredentialsRepository`,
      `AccountsPanelFactory`, `TokenLoginPanelModel`/`LoginModel`; own
      login dialog (`TokenLoginDialog` is scheduled for removal).

### M10 - cleanup
- [ ] Delete every replaced class, `verifyPlugin` 0 internal usages,
      experimental usages listed below, README and plan updated, merge
      decision with the user.

## Ledger: things we implement ourselves

Two kinds of entries:

- **No public IntelliJ API**: the component is internal, scheduled for
  removal, or missing. Entry says what we build, from which public parts.
- **No Gitea API**: the server lacks what the official plugin relies on.
  Entry says the workaround or that the feature is dropped.

### No public IntelliJ API

| # | Official plugin uses | Why we can't | What we build instead | Phase |
|---|---|---|---|---|
| I1 | `ReviewToolwindowProjectViewModel.refresh()` | Only this default member is `@Internal` | Nothing: the interface and `manageReviewToolwindowTabs` are usable; we don't override `refresh()` | M4 done |
| I2 | `ReviewListComponentFactory.create(presenter, options)`, `ReviewListCellRenderer*` | Only the two-argument overload and the renderer classes are `@Internal` | Use the public one-argument `create(presenter)`; rows are not "bordered" like GitHub's (that option is the internal overload) | M5 done |
| I3 | `ReviewListSearchPanelFactory.create(scope, quickFilterListener)`, `ChooserPopupUtil.showAsyncMultipleChooserPopup` | Only these members are `@Internal` | Use public `create(scope)` and single-choice `showAsyncChooserPopup`; filters are single-select (labels too) | M5 done |
| I4 | `ListLoader`, `ReloadablePotentiallyInfiniteListLoader`, `ApiPageUtil` | `@Internal` | `GiteaPullRequestListViewModel`: page counter, `StateFlow` of items, loading and error | M5 done |
| I17 | `PersistingReviewListSearchHistoryModel` backed by a persistent state component | Public, but needs our own state component | Search history kept in memory for the session only | M5 |
| I5 | `CodeReviewChangeListViewModelBase`, `CodeReviewCommitsChangesStateHandler` | `@Internal` | Own `CodeReviewChangeListViewModel` / `CodeReviewChangesViewModel` implementations | M6 |
| I6 | `ReviewInEditorUtil` (review toolbar, `trackDocumentDiffSync`, line transfer, marker color scheme) | `@Internal` | `LineMapping` (`ComparisonManager.compareLines`, debounced on document changes) for line transfer and PR ranges; `CodeReviewEditorGutterChangesRenderer.render` with its default color scheme; review toolbar not built, the editor notification banner stays | M2 done |
| I7 | `MutableCodeReviewEditorGutterChangesModel` | `@Internal` | Own `CodeReviewEditorGutterActionableChangesModel` implementation | M2 |
| I8 | `CodeReviewNavigableEditorViewModel` | `@Internal` | Own next/previous comment actions | M2 |
| I9 | `CodeReviewCommentUIUtil` | Only `createEditorInlayPanel(component, tint)` is `@Internal`; the rest is public | Use the public one-argument `createEditorInlayPanel`, `getInlayPadding`, `createDeleteCommentIconButton`, `getResolveToggleActionText` directly. No own code needed | M2 done |
| I10 | `CodeReviewSubmitPopupHandler` | `@Internal` | Own popup over public `CodeReviewSubmitViewModel` (keeps the Phase 28 popup shape) | M8 |
| I11 | `TimelineDiffComponentFactory` | `@Internal` | Own diff-hunk snippet for review threads in the timeline (`diff_hunk` rendered with an editor or `DiffUtil` viewer) | M7 |
| I12 | (`CollaborationToolsUIUtil.createTagLabel`/`wrapWithLimitedSize` turned out public and are used directly) `CollaborationToolsUIUtil` internal members, `SwingBindingsKt`, `ActionUtilKt` (`swingAction`), `MigLayoutUtilKt`, `ui.Either`, `CoroutineUtilKt`; platform `childScope` (`com.intellij.platform.util.coroutines`, mixed `Internal`/`Experimental` members) | `@Internal` | Small own helpers: child scope (`SupervisorJob` of the parent), flow-to-Swing bindings, `AbstractAction` factory | all |
| I13 | `TokenLoginDialog`, `TokenLoginInputPanelFactory` | `@ScheduledForRemoval` | Own `DialogWrapper` over public `TokenLoginPanelModel`/`LoginModel` | M9 |
| I14 | `CodeReviewCreateReviewUIUtil` | `@Internal` | Only needed for "create pull request", not in this migration's scope | - |
| I16 | Unified (one-pane) diff viewer support | GitHub handles both viewers through internal diff-viewer model helpers | Only `SimpleDiffViewer` (side by side) gets comments; unified viewer shows none | M3 |
| I15 | `CodeReviewDiffVirtualFile` | `@Experimental` (allowed) | Used if it fits; otherwise own `LightVirtualFile` + `FileEditorProvider` for the timeline tab | M7 |

### No Gitea API

| # | Official behavior | Gitea 1.27.3 | Workaround | Phase |
|---|---|---|---|---|
| G1 | Edit/delete review comments via a dedicated endpoint | No review-comment edit/delete endpoint | `PATCH`/`DELETE /issues/comments/{id}` work on review comments (probed) | M2 |
| G2 | Reactions returned with comments | No reactions on `PullReviewComment` | One `GET /issues/comments/{id}/reactions` per comment; add/remove via the same endpoint | M2 |
| G3 | GraphQL timeline with typed review threads | REST only; review comments and timeline are separate | Merge `/issues/{n}/timeline` with review comments client-side | M7 |
| G4 | Mark files as viewed | Web UI only | Dropped; viewed state not shown | M6 |
| G5 | Multiline comments (`WithMultilineComments`) | Comments anchor to one line | Implement only `CodeReviewCommentableEditorModel`, not the multiline variant | M2 |
| G6 | Review-state list filters | Not a parameter of any repository list endpoint | Review filter omitted; "Review requests" quick filter uses `/repos/issues/search?review_requested=true&owner=` filtered to this repository | M5 done |
| G7 | ETag-based list refresh | No ETag on list responses | Poll and compare `updated_at` | M5 |
| G8 | Text search and assignee filter on the PR list | `/pulls` has no `q` or assignee; `/repos/{o}/{r}/issues?type=pulls` has `q`, `created_by`, `assigned_by`, `labels` but no sort | Issues endpoint by default; `/pulls` only when a non-default sort is chosen without text or assignee (sort is ignored otherwise). Issues results carry no mergeability or reviewers, so those row decorations appear only in `/pulls` mode. "Merged" is filtered client-side from closed | M5 done |
| G9 | Review thread resolution per thread | Resolution is per comment (`resolver`); we resolve the first comment | Treat first comment's `resolver` as the thread state | M1 |
| G10 | Outdated thread detection | No `outdated` flag. `position` is the new-side line and `original_position` the old-side line (mutually exclusive, confirmed on `plugindev/pr-test`: comment 15 has `position 0, original_position 15`, an old-side comment). Each comment carries `commit_id`, the commit its line refers to | Map each thread's line from the file at its `commit_id` to the current document; a line that falls inside a changed region is outdated | M2 |
| G11 | CI check runs with details | Commit statuses only (`/commits/{ref}/status`) | Map statuses to `CodeReviewCIJob` | M6 |

### Experimental API in use

Filled in as phases land, from `verifyPlugin` output.

| API | Where | Phase |
|---|---|---|
| `CodeReviewChatItemUIUtil`, `CodeReviewTimelineUIUtil`, `CodeReviewCommentUIUtil`, `CodeReviewCommentTextFieldFactory`, `CommentInputActionsComponentFactory`, `CollaborationToolsUIUtil`, `SimpleHtmlPane` | `GiteaReviewEditorComponents` | M2 |
| `CodeReviewEditorModel`, `CodeReviewInlayModel`, `CodeReviewEditorGutterControlsModel`, `CodeReviewEditorGutterActionableChangesModel`, gutter controls/changes renderers, `renderInlays`, `CodeReviewComponentInlayRenderer`, platform `ComponentInlayRenderer` | `GiteaReviewEditorModel`, `GiteaReviewInEditorController` | M2 |
| `CodeReviewSubmittableTextViewModelBase` | `GiteaReviewViewModels` | M1 |
| `AsyncImageIconsProvider`, `CachingIconsProvider`, `IconsProvider` | `GiteaAvatarIcons` | M2 |
| platform `ListLayout` | `GiteaReviewEditorComponents` | M2 |

`verifyPlugin` after M2/M3: compatible, 0 internal, 163 experimental usages.
After M4/M5: compatible, 0 internal, 301 experimental usages (adds `ui.toolwindow`, `codereview.list`, `codereview.list.search`).
