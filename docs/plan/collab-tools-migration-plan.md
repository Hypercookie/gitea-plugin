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

### M1 - view model layer
Gitea data exposed as coroutine/`StateFlow` view models the collaboration
components consume. Keeps `GiteaApiClient`, DTOs and `PendingReviewStore`.
- [ ] Per-PR data holder with its own `CoroutineScope`: details, review
      threads (grouped from `PullReviewComment`s), pending comments,
      current user; reload on demand.
- [ ] Thread VM implementing `CodeReviewResolvableItemViewModel` and
      `CodeReviewFoldableThreadViewModel`; comment VM (author, body, dates,
      can edit/delete, reactions later).
- [ ] Reply and new-comment VMs extending
      `CodeReviewSubmittableTextViewModelBase`.

### M2 - review in the editor
Replaces `CommentGutterController`, `InlineComponentHost`,
`WorkingCopyCommentEditorListener`, `CommentBoxPanels` (editor parts).
- [ ] `EditorFactoryListener` installer (same shape as GitHub's
      `GHPRReviewInEditorController$InstallerListener`), one coroutine scope
      per editor, cancelled on editor release.
- [ ] Per-file model implementing `CodeReviewEditorModel<I>`
      (inlays + gutter controls) and `CodeReviewEditorGutterActionableChangesModel`.
- [ ] Inlays via `renderInlays` + `CodeReviewComponentInlayRenderer`;
      gutter comment icons via `CodeReviewEditorGutterControlsRenderer.setupIn`;
      changed-line markers via `CodeReviewEditorGutterChangesRenderer.setupIn`.
- [ ] Thread component from `CodeReviewChatItemUIUtil`,
      `TimelineThreadCommentsPanel`, `CodeReviewCommentTextFieldFactory`,
      `CommentInputActionsComponentFactory`.
- [ ] Re-check the parked "new comment box doesn't close" bug: the code it
      lives in is deleted here. Close it in `pr-review-feature-plan.md` or
      re-file it against the new code.

### M3 - review in the diff viewer
- [ ] `GiteaCommentDiffExtension` on the same models and components.

### M4 - tool window shell
- [ ] "Pull Requests" tool window: list plus one closable tab per opened PR.

### M5 - pull request list
- [ ] `ReviewListViewModel`, `ReviewListItemPresentation`,
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
| I1 | `ReviewToolwindowProjectViewModel` + `ReviewToolwindowTabsManager` | Interface is `@Internal`; the public tab manager is generic over it | Own tab manager over `ToolWindow.contentManager` using public `ReviewTab`/`ReviewTabsComponentFactory`-like split | M4 |
| I2 | `ReviewListComponentFactory`, `ReviewListCellRenderer*` | `@Internal` | Own `JBList` + renderer built from public `ReviewListItemPresentation`, `TagPresentation`, `UserPresentation`, `UnreadDotIcon` | M5 |
| I3 | `ReviewListSearchPanelFactory`, `ChooserPopupUtil` | `@Internal` | Own panel composed from `ReviewListSearchTextFieldFactory`, `DropDownComponentFactory`, `CollaborationToolsPopupUtil` | M5 |
| I4 | `ListLoader`, `ReloadablePotentiallyInfiniteListLoader`, `ApiPageUtil` | `@Internal` | Own paged loader (`page`/`limit` query, `StateFlow<List>`, load-more on scroll) | M5 |
| I5 | `CodeReviewChangeListViewModelBase`, `CodeReviewCommitsChangesStateHandler` | `@Internal` | Own `CodeReviewChangeListViewModel` / `CodeReviewChangesViewModel` implementations | M6 |
| I6 | `ReviewInEditorUtil` (review toolbar, `trackDocumentDiffSync`, line transfer, marker color scheme) | `@Internal` | Own document-vs-PR-head range tracking on public `ComparisonManager`/`DiffUtil`; own line mapping; own `LineStatusMarkerColorScheme`; own in-editor toolbar or keep the editor notification banner | M2 |
| I7 | `MutableCodeReviewEditorGutterChangesModel` | `@Internal` | Own `CodeReviewEditorGutterActionableChangesModel` implementation | M2 |
| I8 | `CodeReviewNavigableEditorViewModel` | `@Internal` | Own next/previous comment actions | M2 |
| I9 | `CodeReviewCommentUIUtil` (`createEditorInlayPanel`, edit/delete/reaction icon buttons, folded thread controls, resolve text) | `@Internal` | Own inlay panel wrapper (rounded border, editor background), own `InlineIconButton`s | M2 |
| I10 | `CodeReviewSubmitPopupHandler` | `@Internal` | Own popup over public `CodeReviewSubmitViewModel` (keeps the Phase 28 popup shape) | M8 |
| I11 | `TimelineDiffComponentFactory` | `@Internal` | Own diff-hunk snippet for review threads in the timeline (`diff_hunk` rendered with an editor or `DiffUtil` viewer) | M7 |
| I12 | `CollaborationToolsUIUtil`, `SwingBindingsKt`, `ActionUtilKt`, `MigLayoutUtilKt`, `ui.Either`, `CoroutineUtilKt` | `@Internal` | Small own helpers (flow-to-Swing bindings, `launchOnShow`-style scoping) | all |
| I13 | `TokenLoginDialog`, `TokenLoginInputPanelFactory` | `@ScheduledForRemoval` | Own `DialogWrapper` over public `TokenLoginPanelModel`/`LoginModel` | M9 |
| I14 | `CodeReviewCreateReviewUIUtil` | `@Internal` | Only needed for "create pull request", not in this migration's scope | - |
| I15 | `CodeReviewDiffVirtualFile` | `@Experimental` (allowed) | Used if it fits; otherwise own `LightVirtualFile` + `FileEditorProvider` for the timeline tab | M7 |

### No Gitea API

| # | Official behavior | Gitea 1.27.3 | Workaround | Phase |
|---|---|---|---|---|
| G1 | Edit/delete review comments via a dedicated endpoint | No review-comment edit/delete endpoint | `PATCH`/`DELETE /issues/comments/{id}` work on review comments (probed) | M2 |
| G2 | Reactions returned with comments | No reactions on `PullReviewComment` | One `GET /issues/comments/{id}/reactions` per comment; add/remove via the same endpoint | M2 |
| G3 | GraphQL timeline with typed review threads | REST only; review comments and timeline are separate | Merge `/issues/{n}/timeline` with review comments client-side | M7 |
| G4 | Mark files as viewed | Web UI only | Dropped; viewed state not shown | M6 |
| G5 | Multiline comments (`WithMultilineComments`) | Comments anchor to one line | Implement only `CodeReviewCommentableEditorModel`, not the multiline variant | M2 |
| G6 | Review-state list filters | Not a `/pulls` or issues-search parameter | Client-side over loaded pages, or omitted | M5 |
| G7 | ETag-based list refresh | No ETag on list responses | Poll and compare `updated_at` | M5 |
| G8 | Text search on the PR list | `/pulls` has no `q` | Use `/repos/issues/search?type=pulls&q=...` | M5 |
| G9 | Review thread resolution per thread | Resolution is per comment (`resolver`); we resolve the first comment | Treat first comment's `resolver` as the thread state | M1 |
| G10 | Outdated thread detection | No `outdated` flag | `position == 0` with `original_position > 0` means outdated | M1 |
| G11 | CI check runs with details | Commit statuses only (`/commits/{ref}/status`) | Map statuses to `CodeReviewCIJob` | M6 |

### Experimental API in use

Filled in as phases land, from `verifyPlugin` output.

| API | Where | Phase |
|---|---|---|
