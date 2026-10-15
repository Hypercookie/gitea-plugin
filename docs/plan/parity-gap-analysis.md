# Parity gap analysis: Gitea plugin vs. JetBrains GitHub/GitLab plugins

Phase 42 output, 2026-10-14. Compares this plugin with the GitHub and GitLab
plugins bundled in IntelliJ IDEA Ultimate 2025.2.6.2.

## Method

Static analysis only (user decision: no live walkthrough).

- Plugin descriptors: `plugins/vcs-github-IU/lib/vcs-github.jar`
  (`intellij.vcs.github.xml`), `plugins/vcs-gitlab-IU/lib/vcs-gitlab.jar`.
  Extension points, actions, action groups, tool window, settings.
- User-visible strings: `messages/GithubBundle.properties`,
  `messages/GitLabBundle.properties`, and the shared
  `messages/CollaborationToolsBundle.properties` in
  `lib/modules/intellij.platform.collaborationTools.jar`. Almost every flow
  has a string, so the bundles act as a feature inventory.
- Class structure via `unzip -l` and `javap -v`, including `ApiStatus`
  annotations on `intellij.platform.collaborationTools`.
- Gitea API support: `/swagger.v1.json` from the local `gitea/gitea:1.27.3`
  instance, plus live calls against `plugindev/pr-test` where swagger was
  ambiguous (see "API probes" below).

GitLab's merge request UI uses the same collaborationTools components as
GitHub and follows the same structure (list with quick filters, details,
review in editor, submit popup, draft notes). Where the two differ, GitHub
is the reference, because Gitea's model is closer to GitHub's.

## Key finding: the shared code review library

Both official plugins are thin layers over `intellij.platform.collaborationTools`
(`com.intellij.collaboration.*`). It provides the list, search/filter bar,
details header, branch chip, status rows, commit list, changes tree, review
in editor (gutter, inlays, navigation), comment text fields, reactions,
timeline building blocks, submit popup, avatars and login UI.

`javap` on its `ui/codereview/**` classes (210 top-level classes):

| Marker | Count |
|---|---|
| none (public API) | 152 |
| `@ApiStatus.Internal` | 30 |
| `@ApiStatus.Experimental` | 24 |
| `@ApiStatus.Obsolete` / `ScheduledForRemoval` / `NonExtendable` | 6 |

Public (unmarked) and directly useful: `CodeReviewChangeListComponentFactory`,
`CodeReviewCommentTextFieldFactory`, `CodeReviewMarkdownEditor`,
`CommentInputActionsComponentFactory`, all of `details.*` except
`GroupedRenderer` (title, description, branch, commits, status, actions),
`editor.CodeReviewInEditorViewModel`, `CodeReviewEditorGutterControlsRenderer`,
`CodeReviewEditorGutterChangesRenderer`, `CodeReviewComponentInlayRenderer`,
`reactions.*`, `list.ReviewListViewModel`, `list.search.*` except
`ReviewListSearchPanelFactory`/`ChooserPopupUtil`, `review.CodeReviewSubmitViewModel`,
`avatar.*`, `timeline.StatusMessageComponentFactory`.

Internal (do not use): `CodeReviewCommentUIUtil`, `RoundedPanel`,
`ReviewListComponentFactory`, `ReviewListCellRenderer*`,
`ReviewListSearchPanelFactory`, `CodeReviewSubmitPopupHandler`,
`DiffUIUtil`, `ReviewInEditorUtil`, `TimelineDiffComponentFactory`.

The module is declared as a dependency the normal way
(`<module name="intellij.platform.collaborationTools"/>` in GitHub's
descriptor; `bundledModule(...)` in Gradle).

**Recommendation:** depend on collaborationTools and rebuild the UI on its
public components. Exact visual and behavioral parity is otherwise a
reimplementation of this library by hand, which is what Phases 18-41 have
been doing piece by piece (`RoundedCardPanel` copies `RoundedPanel`,
`InlineComponentHost` copies the inlay mounting, our filter bar
approximates `list.search`). The internal pieces (list renderer, search
panel factory, submit popup handler) still need our own versions, built
from the public view models. This is a large refactor and needs a
decision before Phases 30-37 continue; see "Proposed plan" below.

## API probes (local Gitea 1.27.3)

Swagger has no edit/delete/reaction endpoints for pull request review
comments. Tested the issue-comment endpoints against a review comment
reply created for the test, then deleted it:

| Call on a review (code) comment | Result |
|---|---|
| `GET /issues/comments/{id}` | 204, empty body (not usable for reads) |
| `PATCH /issues/comments/{id}` | 200, body updated |
| `POST /issues/comments/{id}/reactions` | 201 |
| `GET /issues/comments/{id}/reactions` | 200, list with user and content |
| `DELETE /issues/comments/{id}/reactions` | 204 |
| `DELETE /issues/comments/{id}` | 204, comment gone |

So edit, delete and reactions work on review comments through the
issue-comment endpoints. `PullReviewComment` has no reactions field;
reactions need one `GET .../reactions` per comment.

## Gap table

Effort: S (< 1 day), M (1-3 days), L (> 3 days).
API: yes / partial / no, against Gitea 1.27.3.

### Entry points and integration

| Flow | Official | Ours | Gap | API | Effort |
|---|---|---|---|---|---|
| Tool window | "Pull Requests", left anchor, one closable tab per opened PR | "Gitea Pull Requests", right anchor, single list + detail | Name, anchor, tab-per-PR model | n/a | M |
| Git menu group | Create PR, View PRs, Sync Fork, Open in Browser (Git > GitHub) | none | Missing | yes | S |
| Open in Browser / Copy Link | Editor, project view, tab popups; `Git.Hosting.Open.In.Browser.Group` | none | Missing | yes (URL only) | S |
| Clone dialog | `VcsCloneDialogExtension` lists the account's repos | none | Missing | yes (`/user/repos`) | M |
| Share project | `GithubShareAction` creates a repo and pushes | none | Missing | yes (`POST /user/repos`) | M |
| Git HTTP auth | `GitHttpAuthDataProvider` supplies the account token for push/pull | none, user configures git credentials separately | Missing | n/a | S |
| Hosting service, protected branches | `gitRepositoryHostingService`, `gitProtectedBranchProvider` (blocks force-push) | none | Missing | yes (`branch_protections`) | S |
| Branch widget | `gitCurrentBranchPresenter` shows "#12 on branch"; branch popup has Show PR, Submit Review, Update Branch, Toggle Review Mode | top-of-editor banner only | Missing | n/a | M |
| Git log commit checks | `vcsLogCommitStatusProvider` "GitHub Commit Checks" column in the Log tab (Ultimate only), loaded for visible rows | "Gitea Commit Checks" column for roots that map to a Gitea account: icon per combined state, tooltip with each context, click opens the check or commit page; session cache (finished checks never refetched, running 60 s, none/unpushed 2 min doubling to 30/10 min), 4 parallel requests, 100-request burst then 1/s, server backoff on 429/5xx | Done | yes (`/commits/{sha}/status`, one request per commit, no batch endpoint) | M |
| Push notification | "Create pull request" link in the push success balloon | none | Missing | n/a | S |
| Settings | Project-level page under Version Control, accounts list with modal "Add Account" login dialog (token or OAuth), clone-via-SSH option | Application-level page, inline stub row for add (Phase 37) | Add dialog, project vs. app level | n/a | M |
| Gist / snippet | Create Gist | none | Out of scope (Gitea has no gists) | no | - |
| AI review/summary | extension points | none | Out of scope | n/a | - |

### Pull request list

| Flow | Official | Ours | Gap | API | Effort |
|---|---|---|---|---|---|
| Search field | One search field with filter chips, search history (`PersistingReviewListSearchHistoryModel`) | Separate title text field | Chips, history | yes (`q` on `/repos/issues/search`; `/pulls` itself has no text query) | M |
| Quick filters | Open, Your PRs, Assigned to you, Review requests | none | Missing | yes (`created`, `assigned`, `review_requested`, `reviewed` on `/repos/issues/search` with `type=pulls`) | S |
| Filters | State (open/closed/merged), Author, Label, Assignee, Review state, Sort (6 options) | State (open/closed/all), Author (client-side), Sort | Merged state, label, assignee, review state | partial (review state not filterable server-side) | M |
| Row | Title, `#n · created X, by Y`, state tag (DRAFT/CLOSED/MERGED), labels, assignee/reviewer avatars, conflict icon, unresolved-comment count, unread dot | Title, number, date, author, avatar, state, comment count | Labels, reviewers, conflict icon, draft tag, unread dot | yes | M |
| Paging | Infinite scroll | first page only | Missing | yes | S |
| Auto refresh | ETag check every 30 s, "The list is outdated. Refresh" banner | manual refresh | Missing | partial (no ETag on list; poll and compare) | S |
| Empty/error states | "No pull requests matching filters. Clear filters", "Can't load pull requests", auth failure | status label | Proper empty-state text with actions | n/a | S |
| Context menu | Show, Open in Browser, Copy Link, Refresh | View Details, Checkout | Open/Copy Link | yes | S |

### Pull request details

| Flow | Official | Ours | Gap | API | Effort |
|---|---|---|---|---|---|
| Header | Title, state tag, Markdown description, "Changes from N commits" commit selector | Title, Markdown description | Commit selector | yes (`/pulls/{n}/commits`) | M |
| Branch chip | `base <- head` with Checkout, Checkout as detached HEAD, Show in Git Log, Copy Branch Name | Checkout in list context menu | Missing chip and actions | n/a | S |
| Reviewers / assignees / labels | Inline editable lists with popups | none | Missing | yes (`requested_reviewers`, `issues/{n}/assignees`, `issues/{n}/labels`) | M |
| Status rows | CI checks (passed/failed/in progress, Details link), conflicts (with Resolve), unresolved conversations, approvals ("Waiting for review from X", "Missing reviewer", required approvals) | none | Missing | yes (`commits/{ref}/status`, `mergeable`, `branch_protections.required_approvals`) | M |
| Actions | Merge split button (Merge, Squash and Merge, Rebase) with commit message dialog; Close / Reopen; Post (draft to ready); Request / Re-request Review; Set Myself as Reviewer | none | Missing | yes (`POST /pulls/{n}/merge`, `do` = merge / rebase / rebase-merge / squash / fast-forward-only / manually-merged; repo `allow_*` flags; `PATCH /issues/{n}` state) | L |
| Changes tree | Changes tree with Group By, Mark as Viewed / Not Viewed, "N files not viewed" tooltip | Changes tree (Phase 40) | Viewed state | no (Gitea has viewed state in the web UI only) | - |
| Diff | All-in-one combined diff (advanced setting) or per file, opened as an editor tab; next/previous comment navigation (`PreviousOccurence`/`NextOccurence` shortcuts); "Show discussions: All / Unresolved only / Do not show" | Per-file diff viewer with gutter comments | Combined diff, navigation, thread filter | n/a | M |
| Refresh | Refresh action bound to the platform Refresh shortcut, per-panel popups | toolbar refresh | Shortcut binding | n/a | S |

### Timeline

| Flow | Official | Ours | Gap | API | Effort |
|---|---|---|---|---|---|
| Location | Separate editor tab ("Pull Request Timeline") | Embedded in detail panel | Editor tab | n/a | M |
| Events | Comments, reviews (approved, requested changes, reviewed), commits added (grouped), labels, assignees, review requests, renamed, closed/reopened/merged, branch deleted/force-pushed, draft/ready, stale review dismissed | Comments and some events (Phase 27) | Coverage check per event type | yes (`/issues/{n}/timeline`) | M |
| Review threads in timeline | Thread with diff hunk snippet, Resolve, Reply, OUTDATED/RESOLVED tags | Comments only | Threads with hunks | yes (`diff_hunk`) | M |
| Suggested changes | "Suggested change" block with Apply Locally / Commit | none | Missing | partial (no apply API; whether Gitea's web UI renders suggestion blocks is unverified; apply locally is client-side) | M |
| Compose | Markdown editor with Ctrl+Enter to comment, hint text | Plain text area + button | Markdown editor, shortcut | n/a | S |

### Review in the editor

| Flow | Official | Ours | Gap | API | Effort |
|---|---|---|---|---|---|
| Activation | Review Mode, toggled from branch popup or editor; "Update Branch to Enable Review Mode" when out of sync | Always on when branch has an open PR | Toggle, out-of-sync handling | n/a | S |
| Gutter | Changed-line markers vs. PR base (`CodeReviewEditorGutterChangesRenderer`), comment icon on hover, click to add | Full-line background on commented lines, "+" on every line | Change markers, hover-only add icon | n/a | M |
| Add comment | Inline editor, "Start Review" / "Add Review Comment" / "Add Single Comment" (post now), multiline comment on selection, action "Add Review Comment" on caret line with shortcut | Inline box, Start Review / Add to Review | Single comment, multiline, caret action | partial (single comment: yes via review with event COMMENT; multiline: no, Gitea comments are single-line) | S |
| Thread display | Collapsible thread inlay, avatars, "N more replies", tags (OUTDATED, PENDING, RESOLVED), "Show unresolved only / Show all" | Collapsible box, avatars, Resolved tag (Phase 29) | Outdated tag, unresolved-only filter | yes (`position == 0` means outdated) | S |
| Comment actions | Reply, Resolve/Unresolve, Edit, Delete (with confirmation), reactions with pill buttons and "Add reaction" | Reply, Resolve/Unresolve | Edit, delete, reactions | yes (probed, see above) | M |
| Pending comments | Shown in place with PENDING tag, edit/delete | Pending box with Discard | Edit | n/a | S |
| Keyboard | Ctrl+Enter submit, Esc cancel, next/previous comment | buttons only | Shortcuts | n/a | S |
| Submit review | Popup with pending count, discard (with confirm dialog), Markdown body, Approve / Request Changes / Comment | Same shape (Phase 28) | Discard confirmation dialog, Markdown editor | n/a | S |

### Create pull request

| Flow | Official | Ours | Gap | API | Effort |
|---|---|---|---|---|---|
| Create PR tab | Base/head branch selectors, commit and change preview, title/description (generated from commits), reviewers/assignees/labels, push new remote branch dialog, Create / Create Draft, "already exists - View" | none | Missing | yes (`POST /pulls`; draft = `WIP:` title prefix) | L |

### Notifications and state

| Flow | Official | Ours | Gap | API | Effort |
|---|---|---|---|---|---|
| Unseen updates | Per-PR "last seen" state, unread dot in list, tool window badge | none (Phase 36) | Missing | n/a (local state) | S |
| Background refresh | Review data refresh task, timeline "Check for new events" | manual | Missing | n/a | S |

### Look and feel

| Item | Official | Ours |
|---|---|---|
| Comment card | `RoundedPanel` with editor-tooltip background | Own `RoundedCardPanel`, same idea |
| Inlays | `CodeReviewComponentInlayRenderer` | Own `InlineComponentHost` via `addComponentInlay` |
| Avatars | `CodeReviewAvatarUtils`, outlined icons, sizes per context | Own `AvatarService`, fixed 20 px |
| Comment input | `CodeReviewCommentTextFieldFactory`: Markdown editor, placeholder "Review comment", shortcut hints ("Ctrl+Enter to comment") | `JBTextArea` + buttons |
| Buttons | Primary action in default style, secondary as links or plain | Matches since `primaryButton()` |
| Tags | `TagPresentation` uppercase pill (OPEN/DRAFT/MERGED) | plain text |
| Status messages | `StatusMessageComponentFactory` with icons | labels |
| Light/dark | All colors from `JBColor`/`namedColor` | Mostly; hard-coded fallback colors in `RoundedCardPanel` and the line highlight |

## Not possible with Gitea's API

- Mark files as viewed (web UI only, no API).
- Multiline review comments (Gitea comments anchor to one line).
- Review-state list filters (approved, changes requested) on the server
  side; possible only client-side per loaded page.
- Server-side suggested-change apply; apply locally is still possible.

## Proposed plan (needs user sign-off)

Ordered by user-visible impact. Replaces Phases 30-37.

1. **Phase 43: adopt collaborationTools.** Add the module dependency.
   Port comment cards, inlays, avatars and comment input to its public
   components. Keep the API layer and view logic. Fixes most look-and-feel
   gaps at once, and likely replaces the code behind the known new-comment
   bug.
2. **Phase 44: comment actions.** Edit, delete (with confirmation),
   reactions, OUTDATED tag, unresolved-only filter, single comment, caret
   action with shortcut, Ctrl+Enter/Esc. (Absorbs old Phases 29-31.)
3. **Phase 45: details header.** Branch chip with actions, commit selector,
   reviewers/assignees/labels editors, status rows (CI, conflicts,
   approvals, unresolved). (Absorbs old Phases 32-33.)
4. **Phase 46: merge and close.** Merge split button honoring the repo's
   `allow_*` flags, commit message dialog, close/reopen, ready-for-review.
   (Old Phase 34.)
5. **Phase 47: list parity.** Search with chips and history, quick filters,
   merged state, label/assignee filters, paging, row decorations, auto
   refresh, empty states, Open/Copy Link.
6. **Phase 48: IDE integration.** Git menu group, Open in Browser / Copy
   Link everywhere, Git HTTP auth provider, hosting service, protected
   branches, branch widget presenter and popup actions, push notification
   link, Review Mode toggle.
7. **Phase 49: timeline as editor tab.** Full event coverage, review
   threads with diff hunks, suggested changes with Apply Locally.
8. **Phase 50: create pull request.**
9. **Phase 51: accounts.** Project-level settings page, Add Account dialog
   (old Phase 37), clone dialog extension, share project.
10. **Phase 52: unseen updates.** Last-seen state, unread dot, tool window
    badge. (Old Phase 36.)
11. **Tool window shape** (name "Pull Requests", left anchor, one tab per
    PR) folds into Phase 43 or 47, whichever touches it first.
