# Reference: JetBrains GitHub / GitLab Plugin Feature Set (for Gitea Plugin Parity)

> Research doc, not a spec. Compiled from public JetBrains documentation, Marketplace
> listings, and web search results (no local access to the closed parts of the
> `intellij-community` / `gitlab` plugin source, so architecture notes below are
> "what's publicly documented," not a code read). Last updated 2026-09-24.

## 1. Overview

IntelliJ IDEA bundles two first-party "forge" integrations:

- **GitHub plugin** — bundled with IntelliJ IDEA (Ultimate and Community), maintained by
  JetBrains in `intellij-community` (`plugins/github`). The older and more mature of the
  two; originally shipped basic Git/GitHub sharing years ago, pull request review support
  landed around 2020.2.
- **GitLab plugin** — bundled more recently (roughly 2023.2+), maintained by JetBrains in
  `intellij-community` (`plugins/gitlab`). Deliberately modeled after the GitHub plugin's
  UX (tool window, review mode, diff/editor comment gutters) so the two feel like the same
  feature with a different backend.

Both plugins are the model this project should copy: **not** a from-scratch UX, but the
IntelliJ Platform's now-standard "code review" pattern of tool window → PR/MR list →
detail tab with overview/timeline/diff → inline review comments → submit review → merge.

**Important caveat for architecture-copying:** search evidence strongly suggests both
plugins sit on a shared internal platform module, `platform/collaboration-tools`
(`com.intellij.collaboration.*`), which supplies the review-list view model, timeline
UI, diff-with-comments rendering, and the "code review" tool window shell that both
GitHub and GitLab plugins skin with their own API clients. This was not independently
verified by reading source in this pass (no repo access), so treat it as a **fairly
confident but unconfirmed** architecture hypothesis. Practical implication either way:
even if this module exists and is reusable in theory, it is **internal/unstable platform
API**, not a supported extension point — anything built against it would need to track
IDE version compatibility closely and could break across releases. For a third-party
Gitea plugin, the safer default is to mirror the *user-facing structure* (tool window,
list, detail tabs, diff+comment gutters, review submit flow) using stable, documented
Platform SDK primitives (`ToolWindowFactory`, `Content`, diff API, editor gutter
renderers, persistent settings) rather than depending on `collaboration-tools` classes
directly, unless/until that dependency is confirmed supported.

---

## 2. Authentication & Account Setup

### GitHub plugin
- Settings location: **Settings/Preferences → Version Control → GitHub**.
- Supports **OAuth-style browser login** ("Log In via GitHub…") for github.com, and
  **token-based login** for both github.com and GitHub Enterprise Server
  ("Log In to GitHub Enterprise" with server URL + PAT).
- Token can be pasted directly or generated via a "Generate…" button that opens a
  pre-scoped token-creation page in the browser.
- **Multiple accounts** supported; a **default account per project** can be set when
  more than one account is configured.
- GitHub Enterprise Server requires specifying the instance base URL explicitly.

### GitLab plugin
- Settings location: **Settings/Preferences → Version Control → GitLab**.
- **Token-only** authentication (Personal Access Token) — no OAuth browser login flow
  documented. Token scopes needed: `api`, `read_api`, `read_repository`.
- Explicit **server URL field**, so both gitlab.com and self-managed/GitLab Dedicated
  instances are supported the same way — this is the closest existing analogue to what
  a Gitea plugin needs, since Gitea is almost always self-hosted.
- "Verify setup" action to validate URL + token combination.
- Multiple accounts / servers supported (Add Server → Add Account).
- Recommended minimum versions called out in docs (IDE 2023.2+, GitLab 16.8+) — a sign
  that server-version compatibility is an explicit, documented concern for this kind of
  plugin, worth doing the same for Gitea (Gitea versions do vary in API surface).

### Takeaway for Gitea
Gitea's own auth model (self-hosted, PAT-based, optional OAuth2 apps you register
yourself) maps far more closely to the **GitLab plugin's pattern**: no assumption of a
single canonical hosted instance, explicit server URL + token as the primary flow,
verify/test-connection action, multiple servers/accounts.

---

## 3. Pull / Merge Request Features

Both plugins converge on essentially the same feature list; differences are noted.

| Feature | GitHub plugin | GitLab plugin |
|---|---|---|
| List/browse PRs & MRs in a tool window | Yes | Yes |
| Filter by state, author, assignee, label | Yes | Yes |
| Filter by reviewer | — (not documented) | Yes |
| Sort (newest/oldest, most/least commented, recently updated) | Yes | Yes (comparable) |
| Unseen-item indicators (dots on tool window icon) | Yes | Yes |
| Detail view: Overview tab + Timeline tab | Yes | Yes |
| Diff viewer per changed file, with comment counts | Yes | Yes |
| Commit-by-commit filtering/selection in diff | Yes | Yes (commit dropdown) |
| Checkout PR/MR branch locally from the IDE | Yes | Yes |
| **Review Mode**: highlighted diff + inline comments shown live in the editor (not just Diff view) | Yes | Yes |
| Add inline comments via gutter "+" | Yes | Yes |
| Multi-line comment ranges | Yes | Yes (GitLab 18.6+ for multiline per docs) |
| Suggested-code comments (Markdown suggestion blocks) | Yes | Not explicitly documented |
| @mentions in comments | Yes | Yes |
| Pending/draft review comments ("start a review") before submitting | Yes | Yes ("Pending") |
| Submit review: Approve / Request changes / Comment | **Approve / Request changes / Comment** | **Approve / (feedback without approval)** — no distinct "Request changes" state documented; has **Revoke Approval** instead |
| Create PR/MR from IDE | Yes (`Git \| GitHub \| Create Pull Request`), with push notification offering to open one | Yes (`Git \| GitLab \| View Merge Requests` → create), with post-push notification |
| PR/MR template support (`pull_request_template.md`) | Yes | Not explicitly documented (GitLab supports MR templates server-side; IDE-side handling unconfirmed) |
| Set assignees / reviewers / labels when creating | Assignees/labels (via GitHub) | Explicit: assignees, reviewers, labels |
| Merge options | Merge, Squash and merge, Rebase | Merge, Squash and merge |
| Delete source branch after merge | Not explicitly documented | Yes, explicit checkbox |
| Close without merging | Yes | Yes |
| Request additional review | Yes | Yes |
| CI/status display in PR/MR view | Status checks shown (pass/fail/pending) via GitHub Actions status checks | Pipeline widget in Overview tab (pre-merge and post-merge pipeline status) |
| Comment navigation shortcuts | Ctrl+Alt+Up/Down | Ctrl+Alt+Up/Down |
| Manual refresh | Ctrl+F5 / right-click Refresh | Ctrl+F5 |
| Editor review-comment color customization | — | Settings → Editor → Color Scheme → Code Review |

**Sources**: JetBrains docs for
[Review incoming GitHub pull requests](https://www.jetbrains.com/help/idea/work-with-github-pull-requests.html),
[Create and merge GitHub pull requests](https://www.jetbrains.com/help/idea/create-and-merge-github-pull-requests.html),
[Review incoming GitLab merge requests](https://www.jetbrains.com/help/idea/work-with-gitlab-merge-requests.html),
[Create and merge GitLab merge requests](https://www.jetbrains.com/help/idea/create-and-merge-gitlab-merge-requests.html).

---

## 4. Issue Tracking Integration

Neither plugin has a dedicated "Issues" tool window comparable to the PR/MR tool window
in the current IDE versions. (Older IntelliJ versions had a `Git | GitHub | View Issues`
menu entry, but this is legacy/older-UI behavior and not part of the modern GitHub
plugin's documented feature set — do not rely on it as current behavior.)

Instead, GitHub/GitLab/Gitea *issue* integration in the IntelliJ Platform is handled by
a separate, older, general-purpose subsystem: **Task Management**
(`Settings → Tools → Tasks → Servers`, backed by the `TaskRepositoryType` extension
point in `plugins/tasks/tasks-core`). This lets an issue tracker (Jira, YouTrack,
GitHub, GitLab, and others via server type plugins) show up as a source of "tasks" you
can open, associate a branch/changelist with, and reference in commit messages — it is
not a rich issue browser/board.

This is corroborated by existing third-party Gitea integration work:
[`e1fueg0/intellij-gitea-plugin`](https://github.com/e1fueg0/intellij-gitea-plugin) is
explicitly described as "Gitea issue tracker integration" and its source lives under
`src/biz/elfuego/idea/issues/gitea`, i.e., it plugs into the Task Management system
rather than building a standalone issues UI.

**Takeaway**: full-blown issue *browsing/creation/commenting* UI is not something either
official plugin provides as a first-class tool window — it's out of scope for "parity."
The realistic, low-cost analogue is implementing a `TaskRepositoryType` for Gitea issues
(supports linking branches/commits to issues, quick-open), not building a bespoke issues
panel.

---

## 5. CI / Pipeline Status Integration

- **GitHub plugin**: surfaces GitHub Actions **status checks** (pending/success/failure)
  on pull requests, gates merge on required checks per branch protection. IntelliJ IDEA
  also provides general coding assistance for `.github/workflows` YAML (separate from PR
  review). A separate third-party "GitHub Actions Manager" plugin exists for a fuller
  Actions-run browser (view runs, re-run, cancel, approve deployments) — this is **not**
  part of the bundled GitHub plugin itself.
- **GitLab plugin**: shows **pipeline status widgets** in the MR Overview tab, covering
  pre-merge and post-merge pipeline/environment state, sourced from GitLab CI/CD.
- Neither plugin includes a full CI run log viewer; they show pass/fail/pending status
  and link out to the web UI for details.

**Takeaway for Gitea**: Gitea Actions (Gitea's built-in CI, roughly Actions-compatible)
exposes commit statuses via API and a "workflow run status changed" webhook event.
Showing simple pass/fail/pending status on PRs is realistic; a full run-log viewer is
not required for parity and would be a stretch feature.

---

## 6. Notifications

There is **no in-IDE "notifications inbox"** bundled with either plugin — this was an
initial assumption worth correcting. What actually exists:

- **Unseen-item indicators**: blue/white dots overlaid on the tool window icon and on
  individual PR/MR rows when there's new activity.
- **Post-push balloon notification**: after pushing a branch, IntelliJ shows a
  notification offering to open a new PR/MR immediately.
- **VCS status-bar widget**: surfaces a "Submit Review" action when you have a pending
  review on the currently checked-out branch.
- A third-party plugin (`wlk/intellij-github-notifications`) exists specifically to pipe
  GitHub's actual notification feed into the IDE — this underscores that it is *not*
  something the bundled plugin does natively.

---

## 7. Settings UI

- **GitHub**: `Settings → Version Control → GitHub`. Add/remove accounts, set default
  account per project, SSH vs HTTPS clone preference, connection timeout, "mark files as
  viewed automatically" toggle for PR diffs.
- **GitLab**: `Settings → Version Control → GitLab`. Add server + account (URL + PAT),
  "Verify setup," manage multiple servers/accounts, default account per project.
- Both are **application-level account stores** (credentials/tokens are global to the
  IDE installation, stored via the Platform's secure `PasswordSafe`), but expose a
  **per-project default account** so a multi-repo workspace can pick which account
  applies to which project/repo without re-authenticating.
- Editor-level settings: GitLab plugin adds a **Code Review color scheme** page under
  `Settings → Editor → Color Scheme` for diff/comment highlight colors.

---

## 8. Toolwindow(s) and UI Surfaces

- **GitHub**: "Pull Requests" tool window (usually docked alongside Git/Commit tool
  windows). List view with filter/sort toolbar; detail tab per opened PR (Overview +
  Timeline sub-tabs); Diff tab (Ctrl+D) and Review Mode overlay in the main editor (F4
  cycles between review comments).
- **GitLab**: "Merge Requests" tool window, same structural pattern (list → detail
  Overview/Timeline → Diff/Review Mode in editor).
- Both add entries under the `Git` main-menu (`Git | GitHub | ...`, `Git | GitLab | ...`)
  for View/Create actions, and both add a VCS status-bar widget for the active
  review/branch state.

---

## 9. Git Remote Detection / Repository Linking

Mechanism (inferred from platform behavior, git4idea being the shared Git integration
layer both plugins sit on top of, since neither plugin reimplements Git remote handling):

1. On project open / repo refresh, the plugin enumerates local Git repositories via
   git4idea's `GitRepositoryManager`, and for each repo, its configured remotes
   (`GitRemote` — parsed from `.git/config`).
2. Each remote URL (SSH `git@host:owner/repo.git` or HTTPS `https://host/owner/repo.git`
   forms, including custom SSH ports/paths) is parsed and matched by **host** against
   the hosts of currently configured accounts (GitHub: `github.com` or a configured GHE
   URL; GitLab: gitlab.com or a configured self-managed URL).
3. If a project has **multiple remotes** that could match, or matches to more than one
   configured account/server, the user is prompted to choose which repository/account
   to associate (documented explicitly for GitHub: "If the file points to more than one
   remote, you will be prompted to select the appropriate repository").
4. Cloning through the IDE (`Get from VCS` dialog with GitHub/GitLab tab) sets up the Git
   root mapping and remote automatically.
5. Remote management surfaces in `Git | Manage Remotes`.

**Gitea-specific design consequence** (this is the one point where Gitea genuinely
differs from GitHub/GitLab and can't just copy the pattern):
- GitHub and GitLab are recognizable by a small, fixed set of hostnames/URL shapes
  (`github.com`, or an admin-declared GHE/self-managed URL that's still the *whole*
  host). **There is no way to infer "this remote is a Gitea remote" purely from a URL**
  — Gitea is always self-hosted at an arbitrary domain, and often behind a **subpath**
  (e.g. `https://git.example.com/gitea/owner/repo.git`), not just an arbitrary host root.
  So repo-to-account matching for a Gitea plugin **must be account-driven**: match a
  remote's URL against the base URL(s) of servers the user has explicitly configured in
  settings, and that matching must tolerate a path prefix, not just a bare host. This
  can't be "detect Gitea automatically" the way GitHub detection can lean on a fixed
  hostname.

---

## 10. Notable Architecture Patterns (light touch)

Publicly visible/documented patterns worth mirroring, without over-fitting to internal
JetBrains classes:

- **Account abstraction**: pluggable "account" concept (server + credentials) decoupled
  from any single project, stored at the application level, referenced per-project via a
  default-account setting. Credentials go through the Platform's `PasswordSafe`, not
  plain settings XML.
- **Server abstraction**: GitLab plugin explicitly separates "Server" (URL) from
  "Account" (token bound to a server) — good pattern for Gitea, where one user might
  have accounts on several internal Gitea instances.
- **Tool window = list + reusable detail viewer**: single tool window, list on one side,
  tabs for open items; each opened PR/MR becomes a `Content`/tab, closable independently.
- **Diff integration reuses the Platform's diff viewer** rather than a custom one, with
  comments rendered as editor-gutter/inlay overlays keyed by file+line — this is what
  makes "Review Mode in the editor" possible instead of just a Diff-tab-only comment
  view.
- **Both plugins likely share a common internal review UI layer**
  (`platform/collaboration-tools`, unconfirmed by direct source read in this session —
  see caveat in §1) for list/timeline/diff-comment rendering, with GitHub- and
  GitLab-specific code limited to the REST/GraphQL API client and data mapping. If true,
  the practical lesson for a Gitea plugin isn't "depend on that module" but "structure
  the plugin the same way": one thin API-client layer per forge, one shared/reusable
  review-UI layer, so future forges (or Gitea API version differences) only touch the
  client layer.
- **Push-triggered notification hook**: both plugins listen for a successful `git push`
  and offer a "create PR/MR" action contextually, rather than requiring the user to find
  a menu item.

---

## 11. Gitea API Capability Check (grounding for the checklist)

Confirmed via Gitea's public API docs (`docs.gitea.com/api/`) and SDK:

- **Pull requests**: list, get, create, edit; **diff/patch** retrieval; commits and
  changed-files listing; **reviews** — Gitea's Go SDK exposes `CreatePullReview`,
  `SubmitPullReview`, `DismissPullReview` methods, consistent with GitHub-style
  review states (approve / request changes / comment / pending-then-submit), though the
  exact enum values were not independently confirmed by reading the OpenAPI schema in
  this session — **treat "Request Changes" support as likely but unconfirmed** and
  verify against the target Gitea version's Swagger doc (`/api/swagger`) before relying
  on it.
- **Merge**: `POST /repos/{owner}/{repo}/pulls/{index}/merge` confirmed with a `do` field
  supporting `merge`, `rebase`, `rebase-merge`, `squash`, `fast-forward-only`,
  `manually-merged`, plus `delete_branch_after_merge` and
  `merge_when_checks_succeed` booleans. This is at least as rich as GitHub/GitLab's
  merge-method options.
- **Issues**: list/create/edit, comments, labels, milestones, assignees, reactions,
  time tracking, dependencies/blocking relationships — solid parity with GitHub Issues
  at the data level, even though (per §4) neither reference plugin builds a rich issues
  UI on top of it.
- **Notifications**: Gitea has a real notifications API (`/notifications`, threads,
  mark-read, subscriptions) — actually a capability GitHub/GitLab plugins largely don't
  surface as an inbox (see §6). This is a place a Gitea plugin could arguably exceed the
  reference plugins if wanted, though it's not required for parity.
- **Commit status / CI**: commit status API and Gitea Actions workflow-run webhook events
  exist, sufficient for simple pass/fail/pending display on a PR.
- **Repository/org/user management, webhooks**: broadly comparable to GitHub's API
  surface, per Gitea's own docs.

Sources: [Gitea API docs](https://docs.gitea.com/api/), [Merge a pull request
operation](https://docs.gitea.com/api/operations/repo-merge-pull-request/), [Gitea SDK
godoc](https://pkg.go.dev/code.gitea.io/sdk/gitea), [Gitea Pull Request usage
docs](https://docs.gitea.com/usage/pull-request), [Gitea notification system
(DeepWiki)](https://deepwiki.com/go-gitea/gitea/7.3-notification-system).

---

## 12. Existing Gitea IntelliJ Plugins (prior art, not required reading but useful)

Several third-party attempts already exist and are worth a quick look before building
from scratch, if only to avoid known dead ends:

- [`plugins.jetbrains.com/plugin/22477-gitea`](https://plugins.jetbrains.com/plugin/22477-gitea)
  — Marketplace listing; per its description, supports repo clone + task
  management/time-tracking, with issue and PR management marked "coming soon."
- [`LeonDevLifeLog/gitea-idea-plugin`](https://github.com/LeonDevLifeLog/gitea-idea-plugin)
  — built from the IntelliJ Platform Plugin Template (same starting point as this repo);
  187 stars, most active-looking of the group; worth checking its actual feature
  coverage against this doc's checklist before starting fresh.
- [`e1fueg0/intellij-gitea-plugin`](https://github.com/e1fueg0/intellij-gitea-plugin) —
  Task Management (`TaskRepositoryType`) integration for Gitea issues specifically (see
  §4).
- [`JpMand/gitea-idea-integration`](https://github.com/JpMand/gitea-idea-integration) —
  has an open PR ("Implement Gitea Pull Request Tool with UI and Review Features")
  suggesting active PR-review work in progress; worth checking its state/design before
  building an equivalent from scratch.

None of these were read in depth (out of scope for this pass — the task is GitHub/GitLab
parity research), but flagging them so the team doesn't duplicate solved problems.

---

## 13. Gitea Plugin Parity Checklist

Tiered by (a) user value relative to the GitHub/GitLab reference plugins, and (b)
realistic support in Gitea's API (per §11) — not just "would be nice."

### Must-have (core parity — the plugin isn't usable without these)

- [ ] **Account management**: add/remove Gitea account(s); server URL + Personal Access
      Token; "Verify/Test connection" action; credentials via `PasswordSafe`.
- [ ] **Self-hosted server support as the default assumption**, including URL **subpath**
      tolerance (not just bare hostnames) — see §9.
- [ ] **Multiple accounts/servers**, with a **default account per project**.
- [ ] **Git remote → Gitea repo matching**, account-driven, tolerant of SSH/HTTPS and
      subpath URLs; prompt on ambiguity.
- [ ] **Pull Requests tool window**: list PRs for the linked repo, with state/author
      filter at minimum.
- [ ] **PR detail view**: description, base/head branch, changed-files list, diff viewer.
- [ ] **Checkout PR branch locally** from the tool window.
- [ ] **Create PR from IDE**: pick base/head branch, title, description; post-push
      notification offering to open the create-PR flow.
- [ ] **Whole-PR (issue-level) commenting**: read and post top-level comments.
- [ ] **Approve** action (confirmed supported by Gitea's review API).
- [ ] **Merge PR from IDE** with method selection (merge/squash/rebase at minimum —
      Gitea's `do` field supports more) and delete-source-branch option.
- [ ] Manual refresh action.

### Nice-to-have (meaningful parity gap if missing, but plugin is usable without them)

- [ ] **Inline/line-level review comments** in the diff view, with pending-then-submit
      semantics (verify Gitea's line-comment API positions first — confirm before
      committing to editor-gutter Review Mode).
- [ ] **Review Mode in the editor** (not just the Diff tab) — bigger lift, matches both
      reference plugins' signature UX.
- [ ] **Request changes** review state — implement once confirmed against target Gitea
      version's API (see §11 caveat); fall back to "Approve / Comment only" if
      unavailable on older Gitea versions.
- [ ] **Timeline tab** (chronological PR activity/comments separate from the diff view).
- [ ] Filter by label, milestone, assignee (Gitea API supports all three).
- [ ] Set labels/milestone/assignees when creating or editing a PR.
- [ ] Simple **commit-status/CI display** (pass/fail/pending) on PR list and detail,
      sourced from Gitea's commit-status or Actions-run API.
- [ ] Unseen-item indicators (dot on tool window icon) via periodic polling (Gitea has no
      push/websocket event stream comparable to GitHub's, so this will be poll-based).
- [ ] **Issue integration via `TaskRepositoryType`** (Task Management), not a bespoke
      issues UI — matches actual reference-plugin scope (§4) and existing prior art.
- [ ] PR/MR **description templates** (`.gitea/PULL_REQUEST_TEMPLATE.md` if Gitea
      supports the convention — verify) auto-filled on create.
- [ ] Comment navigation keyboard shortcuts, consistent with reference plugins.

### Explicitly out of scope / low priority for v1

- Full CI run-log viewer (neither reference plugin does this either; simple status only).
- Rich issues browser/board UI (neither reference plugin has this; Task Management is
  the real parity target).
- OAuth browser login flow (Gitea supports OAuth2 apps, but for a self-hosted, often
  admin-locked-down target, PAT-based auth is the pragmatic default; OAuth can be a
  later enhancement).
- Notifications inbox (neither reference plugin has one, despite Gitea's API supporting
  it — could be a differentiator later, not a parity requirement).
- Suggested-code comment blocks, drag-and-drop file attachments in comments — polish
  items from the GitLab plugin's comment editor, not core.

### Unconfirmed / verify-before-building

- Exact allowed values and required fields for Gitea's `POST /pulls/{index}/reviews`
  (review state enum, whether inline position is set in the same call or via a separate
  review-comment endpoint) — check the Swagger doc (`https://<gitea-host>/api/swagger`)
  for the specific Gitea version(s) being targeted.
- Whether Gitea has a server-recognized PR/MR description template file convention.
- Minimum Gitea API version to target — Gitea's API surface has grown over versions;
  decide a floor (e.g. last N releases) the way the GitLab plugin documents a minimum
  server version.

---

## 14. References

- [Review incoming GitHub pull requests — IntelliJ IDEA docs](https://www.jetbrains.com/help/idea/work-with-github-pull-requests.html)
- [Create and merge GitHub pull requests — IntelliJ IDEA docs](https://www.jetbrains.com/help/idea/create-and-merge-github-pull-requests.html)
- [Set up a GitHub account — IntelliJ IDEA docs](https://www.jetbrains.com/help/idea/set-up-a-github-account.html)
- [GitHub settings page — IntelliJ IDEA docs](https://www.jetbrains.com/help/idea/settings-version-control-github.html)
- [Manage projects hosted on GitHub — IntelliJ IDEA docs](https://www.jetbrains.com/help/idea/manage-projects-hosted-on-github.html)
- [Review incoming GitLab merge requests — IntelliJ IDEA docs](https://www.jetbrains.com/help/idea/work-with-gitlab-merge-requests.html)
- [Create and merge GitLab merge requests — IntelliJ IDEA docs](https://www.jetbrains.com/help/idea/create-and-merge-gitlab-merge-requests.html)
- [Set up a GitLab account — IntelliJ IDEA docs](https://www.jetbrains.com/help/idea/set-up-a-gitlab-account.html)
- [Git integration with issue trackers — IntelliJ IDEA docs](https://www.jetbrains.com/help/idea/handling-issues.html)
- [GitHub Actions support — IntelliJ IDEA docs](https://www.jetbrains.com/help/idea/github-actions.html)
- [Working with GitLab Merge Requests in IntelliJ IDEA — foojay.io](https://foojay.io/today/working-with-gitlab-merge-requests-in-intellij-idea/)
- [Gitea API documentation](https://docs.gitea.com/api/)
- [Gitea: Merge a pull request operation](https://docs.gitea.com/api/operations/repo-merge-pull-request/)
- [Gitea: Pull Request usage docs](https://docs.gitea.com/usage/pull-request)
- [Gitea Go SDK godoc](https://pkg.go.dev/code.gitea.io/sdk/gitea)
- [Gitea Notification System — DeepWiki](https://deepwiki.com/go-gitea/gitea/7.3-notification-system)
- [Gitea webhooks docs](https://docs.gitea.com/usage/repository/webhooks/)
- [`plugins/tasks/tasks-core/.../gitlab/GitlabRepository.java` — intellij-community](https://github.com/JetBrains/intellij-community/blob/master/plugins/tasks/tasks-core/src/com/intellij/tasks/gitlab/GitlabRepository.java)
- [Gitea plugin — JetBrains Marketplace](https://plugins.jetbrains.com/plugin/22477-gitea)
- [`LeonDevLifeLog/gitea-idea-plugin`](https://github.com/LeonDevLifeLog/gitea-idea-plugin)
- [`e1fueg0/intellij-gitea-plugin`](https://github.com/e1fueg0/intellij-gitea-plugin)
- [`JpMand/gitea-idea-integration`](https://github.com/JpMand/gitea-idea-integration)
