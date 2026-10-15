# Gitea for JetBrains IDEs

[![Build](https://github.com/Hypercookie/gitea-plugin/actions/workflows/build.yml/badge.svg)](https://github.com/Hypercookie/gitea-plugin/actions/workflows/build.yml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

Review [Gitea](https://about.gitea.com/) pull requests without leaving your
IDE. Browse and filter pull requests, read their timeline, see review comments
next to your code, and reply, resolve, comment and submit reviews from IntelliJ
IDEA and other JetBrains IDEs.

The plugin is built on the IntelliJ Platform's code review components, the
same ones the bundled GitHub and GitLab plugins use, so it looks and works
like them.

## Contents

- [Features](#features)
- [Requirements](#requirements)
- [Installation](#installation)
- [Getting started](#getting-started)
- [Known limitations](#known-limitations)
- [Development](#development)
- [License](#license)

## Features

### Pull request list

- A **Pull Requests** tool window lists the pull requests of the current
  repository, with title, number, author, labels, assignees, state and comment
  count. More results load as you scroll.
- Quick filters for open pull requests, your pull requests, pull requests
  assigned to you and review requests, plus filters for state, author, label,
  assignee and sort order.
- View, check out, open in the browser or copy the link of any pull request
  from its context menu.

### Pull request details

- Each pull request opens in its own tab with its commits, source branch,
  changed files, commit status checks, merge conflicts and pending reviewers.
- Open the diff of the whole pull request or of a single commit in the IDE's
  diff viewer, or use **Jump to Source** (<kbd>F4</kbd>) in the changed files
  to open the local file.
- **Previous Comment** and **Next Comment** in the diff toolbar step through
  the review threads of the pull request, across files. Resolved threads are
  skipped unless **Include Resolved Comments in Navigation** is turned on in
  the diff's context menu.
- The timeline opens in an editor tab with the description, comments, reviews,
  pushed commits and other events, and a field for new comments.

### Review in the editor

- On the branch of an open pull request, review threads appear under the
  commented lines in the regular editor, and lines changed by the pull request
  are marked in the gutter.
- Threads follow your local edits. A thread whose line has changed since the
  comment was written stays at the changed block, collapsed and marked
  **Outdated**.
- Reply to threads, resolve and unresolve them, and add new comments from the
  gutter, either to a pending review or as a single comment.
- The same comments appear in the side-by-side, unified and one-sided diff
  viewers.
- Typing `@` in a comment suggests users to mention.
- A banner above the editor shows when a review is pending, and when the
  local branch is ahead of, behind or diverged from the pull request.
- The pull request is found through the branch's upstream, so local branches
  with a different name work, and multi-root projects are supported.

### Submitting reviews

- **Submit Review** shows the number of pending comments, a field for the
  review summary, and the actions **Approve**, **Request Changes** and
  **Comment**. Pending comments can also be discarded.
- Approve and Request Changes are hidden on your own pull requests, which Gitea
  does not allow.

### Git log

- A **Gitea Commit Checks** column shows the commit status of each commit in
  the Git log. Click it to open the check or the commit page.

### Accounts

- Accounts are managed under <kbd>Settings</kbd> | <kbd>Version Control</kbd> |
  <kbd>Gitea</kbd>. The server URL and access token are checked before an
  account is saved, and one account can be set as the project default.
- Access tokens are stored in the IDE's password safe.
- Git remotes are matched to accounts by host and path, so servers hosted
  under a subpath (such as `https://example.com/gitea`) work. HTTPS, `ssh://`
  and `git@host:owner/repo.git` remotes are supported.

## Requirements

- IntelliJ IDEA 2025.2 or later, or another JetBrains IDE on platform build 252
  or later, with the bundled Git plugin enabled.
- Gitea 1.27 is recommended; the plugin is tested against it. Gitea 1.24 and
  earlier lack the endpoints for replying to and resolving review comments.

## Installation

The plugin is in beta. Version 0.1.0-beta is the first release.

1. Download `gitea-plugin-0.1.0-beta.zip` from the
   [0.1.0-beta release](https://github.com/Hypercookie/gitea-plugin/releases/tag/v0.1.0-beta).
   Pre-releases are listed on the
   [releases page](https://github.com/Hypercookie/gitea-plugin/releases) but
   not under "Latest".
2. In the IDE, open <kbd>Settings</kbd> | <kbd>Plugins</kbd> | <kbd>⚙</kbd> |
   <kbd>Install Plugin from Disk...</kbd>, select the downloaded archive (don't
   unzip it) and restart the IDE.

To build from source instead:

```shell
git clone https://github.com/Hypercookie/gitea-plugin.git
cd gitea-plugin
./gradlew buildPlugin
```

The archive is written to `build/distributions/`; install it the same way.

## Getting started

1. In Gitea, create an access token under <kbd>Settings</kbd> |
   <kbd>Applications</kbd> with read and write access to repositories and
   issues. The plugin reads your profile, pull requests, issues and repository
   contents, and writes reviews and comments.
2. In the IDE, open <kbd>Settings</kbd> | <kbd>Version Control</kbd> |
   <kbd>Gitea</kbd>, click <kbd>+</kbd>, enter the server URL (for example
   `https://gitea.example.com`) and the token, and click **Log In**.
3. Open a project whose Git remote points to that server, then open the
   **Pull Requests** tool window.
4. Double-click a pull request to open it. Select changed files and click
   **Show Diff** to review them, or click **View Timeline**.
5. To review in the editor, check out the pull request. Files on its branch
   show existing comments, and you can add new ones from the gutter.
6. Click **Submit Review** in the pull request tab or the editor banner.

## Known limitations

- Pull requests from forks cannot be checked out.
- Editing and deleting comments, reactions, requesting reviewers, editing
  labels and assignees, and creating, merging or closing pull requests are not
  supported yet.
- Files cannot be marked as viewed, and comments cannot span several lines,
  because Gitea's API does not support either.
- The timeline does not show the code snippet of review threads.
- Timeline tabs are not restored after restarting the IDE.

## Development

The project uses Kotlin and the
[IntelliJ Platform Gradle Plugin](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html),
and builds against IntelliJ IDEA 2025.2.

```shell
./gradlew runIde        # start a sandbox IDE with the plugin installed
./gradlew check         # run the tests
./gradlew verifyPlugin  # run the IntelliJ Plugin Verifier
./gradlew buildPlugin   # build the plugin archive
```

`verifyPlugin` checks the plugin against the latest release of every IDE
version since 2025.2. It fails on compatibility problems and on any use of
internal IntelliJ Platform API.

Live integration tests run against a local Gitea instance when
`GITEA_DEV_TOKEN` is set; see
[docs/local-gitea-dev-instance.md](docs/local-gitea-dev-instance.md).

Source layout under `src/main/kotlin/com/github/hypercookie/giteaplugin`:

| Package                     | Contents                                                     |
|-----------------------------|--------------------------------------------------------------|
| `accounts`                  | Accounts, token storage and the project default account     |
| `api`                       | Gitea REST API client and data types                         |
| `log`                       | Commit checks column in the Git log                          |
| `pullrequest`               | Editor banner, branch to pull request lookup, pending review |
| `pullrequest/toolwindow`    | Tool window and its tabs                                     |
| `pullrequest/list`          | Pull request list, search and filters                        |
| `pullrequest/details`       | Pull request tab: commits, branch, files, checks, diffs      |
| `pullrequest/timeline`      | Timeline editor tab                                          |
| `pullrequest/review`        | Review data, threads, line mapping, submit review popup      |
| `pullrequest/review/editor` | Review in the editor: comments and gutter                    |
| `pullrequest/diff`          | Comments in the diff viewer                                  |
| `repo`                      | Matching Git remotes to accounts                             |
| `settings`                  | Settings page and login                                      |
| `util`                      | Shared helpers                                               |

## License

Licensed under the [Apache License 2.0](LICENSE). Parts of the user interface
are adapted from IntelliJ IDEA Community Edition, also licensed under the
Apache License 2.0; see [NOTICE](NOTICE).
