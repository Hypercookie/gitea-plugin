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

The user interface is built on the IntelliJ Platform's code review components
(`intellij.platform.collaborationTools`), the same components the bundled
GitHub and GitLab plugins use, so it looks and behaves like them.

### Accounts and settings

- Accounts are managed in <kbd>Settings</kbd> | <kbd>Version Control</kbd> |
  <kbd>Gitea</kbd>. Adding an account opens a login dialog that checks the
  server URL and access token before the account is saved. The token of an
  existing account can be replaced, and one account can be set as the
  project default.
- Access tokens are stored in the IDE's password safe.
- The project's Git remotes are matched against the configured accounts by host
  and path prefix, so servers hosted under a subpath (for example
  `https://example.com/gitea`) are supported. HTTPS, `ssh://` and scp-style
  (`git@host:owner/repo.git`) remote URLs are recognized.

### Pull request list

- The "Pull Requests" tool window lists the pull requests of the linked
  repository. Rows show title, number, author, labels, assignees, state and
  comment count. More pull requests load as you scroll.
- A search field with quick filters (open, your pull requests, assigned to
  you, review requests) and filters for state, author, label, assignee and
  sort order.
- Context menu: View Pull Request (also Enter or double-click), Checkout,
  Open in Browser, Copy Link, Refresh List. Checkout asks for confirmation
  when the working tree has uncommitted changes.

### Pull request details

- Each opened pull request gets its own tab with the title, a commit selector
  ("Changes from N commits"), the source branch with checkout, the changed
  files as a tree with the number of unresolved threads per file, commit
  status checks, merge conflicts, and the reviewers the pull request is
  waiting for.
- Selecting files and choosing Show Diff opens them in the IDE's diff viewer,
  for the whole pull request or a single commit.
- "View Timeline" opens the timeline in an editor tab: the description,
  comments, reviews, pushed commits and other events, with a field for new
  comments.
- Reactions show as pills under the description and each comment, with who
  reacted on hover. Click a pill to add or remove your reaction, or the smiley
  in a comment's header to pick a new one from the reactions the server allows.

### Review in the editor

- When the checked-out branch belongs to an open pull request, review threads
  appear below the commented lines in the regular editor, and lines changed by
  the pull request are marked in the gutter.
- Comment positions follow local edits: a thread stays on its line when lines
  are added or removed above it. Threads whose line was changed since the
  comment was written are hidden.
- Reply to a thread and resolve or unresolve it inline.
- See, add and remove reactions on review comments, as in the timeline.
- Hover the gutter to add a comment on a line. A new comment can start or
  extend a pending review, or be posted on its own ("Add Single Comment").
- The same comments are shown in the pull request diff viewer, on both sides.
- A banner above the editor shows when a review is pending and when the local
  branch is out of sync with the pull request.

### Submitting reviews

- "Submit Review" opens a popup with the number of pending comments, a review
  summary, and the actions Approve, Request Changes and Comment. Pending
  comments can be discarded from the popup.
- Approve and Request Changes are not offered on pull requests you authored,
  since Gitea does not allow them.

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
   <kbd>Applications</kbd>. The plugin reads your user profile, pull requests,
   issues and repository contents, and writes pull request reviews and
   comments, so the token needs read and write access to repositories and
   issues.
2. Open <kbd>Settings</kbd> | <kbd>Version Control</kbd> | <kbd>Gitea</kbd> and
   click the add button.
3. Enter the server URL (for example `https://gitea.example.com`) and the
   token, then click Log In.

## Usage

1. Open a project whose Git remote points to a repository on the configured
   Gitea server.
2. Open the "Pull Requests" tool window (Gitea icon).
3. Double-click a pull request to open its tab. Select changed files and use
   Show Diff to review them with comments, or click "View Timeline".
4. To review in the editor, use Checkout on the pull request or its branch.
   Files you open on that branch show existing comments, and new ones can be
   added from the gutter.
5. Click "Submit Review" in the pull request tab or the editor banner and
   choose Approve, Request Changes or Comment.

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

| Package                   | Contents                                                                       |
|---------------------------|--------------------------------------------------------------------------------|
| `accounts`                | Account model, account manager and storage, project default account           |
| `api`                     | Gitea REST API client (`java.net.http`) and `kotlinx.serialization` DTOs       |
| `icons`                   | Gitea icons                                                                    |
| `pullrequest`             | Editor banner, branch to pull request resolution, pending review storage      |
| `pullrequest/toolwindow`  | Tool window and its tab management                                             |
| `pullrequest/list`        | Pull request list, search and filters                                          |
| `pullrequest/details`     | Pull request tab: header, commits, branch, changes tree, status, diffs        |
| `pullrequest/timeline`    | Timeline editor tab                                                            |
| `pullrequest/reactions`   | Reactions on comments and the description: data, pills, picker                |
| `pullrequest/review`      | Review data, thread grouping, line mapping, submit review popup               |
| `pullrequest/review/editor` | Review in the editor: model, inlays, gutter                                  |
| `pullrequest/diff`        | Diff viewer extension that shows comments in the diff viewer                  |
| `repo`                    | Git remote URL parsing and matching remotes to configured accounts            |
| `settings`                | Settings page and token login                                                  |
| `util`                    | Coroutine scopes and small UI helpers                                          |

The plugin uses only public IntelliJ Platform API. `./gradlew verifyPlugin`
fails the build on any use of `@ApiStatus.Internal` API.

Further documentation:

- `docs/plan/pr-review-feature-plan.md`: feature plan, implementation notes and
  status of each phase.
- `docs/plan/collab-tools-migration-plan.md`: the move to the platform's code
  review components, including the list of parts implemented in this plugin
  because no public API or Gitea API exists for them.
- `docs/plan/parity-gap-analysis.md`: comparison with the GitHub and GitLab
  plugins.
- `docs/local-gitea-dev-instance.md`: setting up a local Gitea instance in
  Docker for manual testing.

## Known limitations

- Checkout only works for pull requests whose branch is in the same repository.
  Pull requests from forks are not supported.
- Editing and deleting comments, requesting reviewers, editing
  labels and assignees, creating, merging and closing pull requests are not
  implemented.
- Gitea returns no reactions with comments, so they load with one request per
  comment after the comments appear. Other people's new reactions show up
  after "Refresh Pull Request" or when the timeline is reopened.
- Files cannot be marked as viewed and comments cannot span several lines;
  Gitea's API has no support for either.
- Review threads are not shown with their code snippet in the timeline.
- Timeline tabs are not restored after an IDE restart.
- Several features have not yet been tested in a running IDE. See the plan
  documents for details.

## License

Licensed under the Apache License, Version 2.0. See `LICENSE`.

Parts of the user interface mirror code from IntelliJ IDEA Community Edition,
which is also licensed under the Apache License 2.0. See `NOTICE`.
