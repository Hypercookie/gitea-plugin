# gitea-plugin

![Build](https://github.com/Hypercookie/gitea-plugin/workflows/Build/badge.svg)

A Gitea integration for JetBrains IDEs. It lists the pull requests of a Gitea
repository, shows their details, changed files and timeline, displays review
comments inside the editor and the diff viewer, and lets you reply to, resolve
and add comments and submit a review (approve, comment, or request changes)
from the IDE.

The plugin is under active development. See [Known limitations](#known-limitations)
before relying on it.

## Features

### Accounts and settings

- Configure one or more Gitea accounts (name, server URL, access token) in
  <kbd>Settings</kbd> | <kbd>Version Control</kbd> | <kbd>Gitea</kbd>.
- Access tokens are stored in the IDE's password safe, not in plain-text
  configuration files.
- "Verify connection" checks the server URL and token against the Gitea API.
- The project's Git remotes are matched against the configured accounts by host
  and path prefix, so servers hosted under a subpath (for example
  `https://example.com/gitea`) are supported. HTTPS, `ssh://` and scp-style
  (`git@host:owner/repo.git`) remote URLs are recognized.

### Pull request list

- A "Gitea Pull Requests" tool window lists the pull requests of the linked
  repository, with title, number, creation date, author, author avatar, state
  and comment count.
- Filter by state (open, closed, all) and author, search by title, and sort by
  creation date, last update or number of comments.
- Context menu on a row: View Details and Checkout. Checkout switches to the
  pull request's head branch and asks for confirmation when the working tree
  has uncommitted changes.
- A panel at the top of the tool window shows the pull request for the
  currently checked-out branch, with its review actions.

### Pull request details and timeline

- Double-clicking a pull request opens a detail tab with the title, source and
  target branches, state, and the description rendered as Markdown.
- Changed files are shown as a tree grouped by directory, with file-type icons,
  change-status colors and added/removed line counts. Double-clicking a file
  opens it in the IDE's diff viewer.
- The detail tab includes the pull request timeline: comments, reviews and
  pushed commits, with Markdown-rendered comment bodies. New top-level comments
  can be posted from the timeline.

### Review comments in the editor

- When the checked-out branch belongs to an open pull request, review comments
  are shown in the regular editor for every file you open. No separate review
  mode needs to be enabled.
- The same comments are shown in the diff viewer opened from the detail tab.
- Comment threads are displayed as inline boxes below the commented line, with
  avatars and Markdown rendering. Commented lines are highlighted. Threads are
  collapsed by default and expand to the full conversation.
- Reply to a thread, and resolve or unresolve it, directly from the inline box.
- A banner above the editor warns when the local file differs from the commit
  the pull request points to (local `HEAD` differs from the pull request head,
  or the file has unsaved changes), since comment positions may then be
  inaccurate.
- Switching branches updates the comments in already open editors.

### Submitting reviews

- Click the gutter icon on any line to write a new review comment. New comments
  are kept as pending comments until the review is submitted; pending comments
  can be discarded individually.
- Pending comments are shared between the editor and the diff viewer for the
  same pull request, and a banner above the editor shows when a review is
  pending.
- "Submit Review" opens a popup with an optional review summary and the
  actions Approve, Request Changes and Comment. All pending comments are
  submitted together as one review.
- Approve and Request Changes are disabled on pull requests you authored, since
  Gitea does not allow them.

## Requirements

- IntelliJ IDEA 2025.2 or another JetBrains IDE based on platform build 252 or
  later, with the bundled Git plugin enabled.
- A Gitea server. Development and testing was done against Gitea 1.27.3. The
  endpoints for replying to and resolving review comments are not part of the
  Gitea 1.24 API, so older servers may lack these features. Other parts of the
  plugin have not been checked against older versions.

## Installation

The plugin is not published on JetBrains Marketplace. Build it from source:

```shell
git clone https://github.com/Hypercookie/gitea-plugin.git
cd gitea-plugin
./gradlew buildPlugin
```

The plugin archive is written to `build/distributions/`. Install it with
<kbd>Settings</kbd> | <kbd>Plugins</kbd> | gear icon |
<kbd>Install Plugin from Disk...</kbd> and restart the IDE.

## Configuration

1. Create an access token in Gitea under <kbd>Settings</kbd> |
   <kbd>Applications</kbd>. The plugin reads your user profile, pull requests
   and repository contents, and writes pull request reviews and comments, so
   the token needs read and write access to the repositories you want to
   review.
2. Open <kbd>Settings</kbd> | <kbd>Version Control</kbd> | <kbd>Gitea</kbd>.
3. Add an account with the add button, then enter a name, the server URL (for
   example `https://gitea.example.com`) and the token.
4. Click "Verify connection", then "Save selected account".

## Usage

1. Open a project whose Git remote points to a repository on the configured
   Gitea server.
2. Open the "Gitea Pull Requests" tool window. Use the Refresh button to reload
   the list.
3. Double-click a pull request to open its details, changed files and timeline.
   Double-click a file to view its diff with review comments.
4. To review in the editor, right-click the pull request and choose Checkout.
   Files you open on that branch show existing comments and allow adding new
   ones from the gutter.
5. Add comments, then click "Submit Review" in the detail tab, the tool window
   or the editor banner, and choose Approve, Request Changes or Comment.

## Development

The project is based on the
[IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template)
and uses Kotlin and the IntelliJ Platform Gradle Plugin. The IDE target is
IntelliJ IDEA 2025.2.

```shell
./gradlew runIde       # start a sandbox IDE with the plugin installed
./gradlew buildPlugin  # build the plugin archive
./gradlew check        # run tests and checks
```

Source layout under `src/main/kotlin/com/github/hypercookie/giteaplugin`:

| Package       | Contents                                                                        |
|---------------|---------------------------------------------------------------------------------|
| `accounts`    | Account model, account storage and password safe access, per-project default account |
| `api`         | Gitea REST API client (`java.net.http`) and `kotlinx.serialization` DTOs        |
| `icons`       | Gitea icons for the tool window and notifications                              |
| `pullrequest` | Tool window, pull request list, detail tab, timeline, review actions, editor banner, branch to pull request resolution, pending review storage |
| `pullrequest/comments` | Inline comment boxes, gutter handling, avatars, editor listener for working-copy comments |
| `pullrequest/diff` | Diff viewer extension that shows comments in the diff viewer             |
| `repo`        | Git remote URL parsing and matching remotes to configured accounts              |
| `settings`    | The Settings page                                                              |
| `util`        | Coroutine scopes and shared UI helpers                                          |

Further documentation:

- `docs/plan/pr-review-feature-plan.md`: feature plan, implementation notes and
  status of each phase.
- `docs/local-gitea-dev-instance.md`: setting up a local Gitea instance in
  Docker for manual testing.

## Known limitations

- Checkout only works for pull requests whose branch is in the same repository.
  Pull requests from forks are not supported.
- After adding a new comment in the editor, the new-comment box may not close.
  This is a known bug and is being worked on.
- Comment positions are not mapped between the pull request head and a
  modified working copy. When the two differ, a warning banner is shown instead.
- Only one page of pull requests, as returned by the server's default page
  size, is loaded.
- When several remotes match configured accounts, there is no UI to choose
  between them.
- Editing and deleting comments, reactions, requesting reviewers, labels,
  merging and closing pull requests are not implemented.
- Several recent features have not yet been tested in a running IDE. See the
  plan document for details.
