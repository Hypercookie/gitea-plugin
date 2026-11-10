# Gitea pull requests & MCP for JetBrains IDEs

[![Build](https://github.com/Hypercookie/gitea-plugin/actions/workflows/build.yml/badge.svg)](https://github.com/Hypercookie/gitea-plugin/actions/workflows/build.yml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

**Gitea pull requests & MCP** lets you review [Gitea](https://about.gitea.com/) pull requests without leaving your
IDE: browse and filter pull requests, read the timeline, see review comments
next to your code, and reply, resolve, comment and submit reviews. It is built
on the IntelliJ Platform's code review components, so it looks and works like
the bundled GitHub and GitLab plugins.

_Unofficial community plugin, not affiliated with or endorsed by the Gitea project._

## What sets it apart

As of November 2026, and to the best of our knowledge from the JetBrains Marketplace listings of the other Gitea plugins:

- **Multi-line comments** (opt-in): comment on a selected range of lines, in the editor and in diffs. Gitea's API has no line ranges, so the plugin stores the range with the comment; the other Gitea plugins we compared don't offer it.
- **Code suggestions** (opt-in): write a suggestion into a review comment and apply it to the local file in one click, as one undo step. This also works for suggestions typed in Gitea's web UI.
- **Full MCP integration:** AI assistants connect through the IDE's MCP Server and can read pull requests, comment, reply, resolve threads, react, and queue and submit whole reviews. Both the read tools and the write tools are off by default and switched on separately.

## Features

- **Pull request list** with search and quick filters (open, mine, assigned to me, review requests), plus state, author, label and assignee filters.
- **Details tab** with commits, changed files (filterable), status checks, merge conflicts and reviewers, and a timeline with reactions.
- **Review in the editor:** threads appear under the commented lines on the checked-out branch. Reply, resolve, add comments from the gutter, and collect them in a pending review.
- **Diff viewer** support with comment navigation across files.
- **Submit reviews** with Approve, Request Changes or Comment.
- **Git log column** showing the commit status checks (opt-in).

Multi-line comments and suggestions are under <kbd>Settings</kbd> | <kbd>Version Control</kbd> | <kbd>Gitea</kbd> | **Experimental**, and the MCP switches are under **Features**; details are in the [reference](docs/features.md).

## Requirements

- IntelliJ IDEA 2025.2 or later (or another JetBrains IDE on build 252+) with the Git plugin.
- Gitea 1.24 or later. Resolving comments needs 1.26, replying to them 1.27; on older servers those actions are hidden.

## Installation

- **JetBrains Marketplace:** in the IDE open <kbd>Settings</kbd> | <kbd>Plugins</kbd> | <kbd>Marketplace</kbd> and search for "Gitea pull requests & MCP". _(Once published.)_
- **From a release:** download the zip for your IDE from the [latest release](https://github.com/Hypercookie/gitea-plugin/releases/latest) (`-252` for 2025.2 to 2026.2, `-263` for 2026.3) and use <kbd>Settings</kbd> | <kbd>Plugins</kbd> | <kbd>⚙</kbd> | <kbd>Install Plugin from Disk...</kbd>.

## Getting started

1. In Gitea, create an access token under <kbd>Settings</kbd> | <kbd>Applications</kbd> with read and write access to repositories and issues.
2. In the IDE open <kbd>Settings</kbd> | <kbd>Version Control</kbd> | <kbd>Gitea</kbd>, click <kbd>+</kbd>, enter the server URL and the token, and log in. The token is stored in the IDE's password safe.
3. Open a project whose Git remote points to that server and open the **Pull Requests** tool window.
4. Double-click a pull request. To review in the editor, check out its branch.
5. Click **Submit Review** in the pull request tab or the editor banner.

## More

- [Full feature and settings reference, MCP tools, known limitations](docs/features.md)
- [Development guide](docs/development.md)
- [Changelog](CHANGELOG.md)

## AI disclaimer

This project was largely implemented with GenAI tools, and I make no effort to hide it. I build it in my free time next to a full-time job, and I have done my best to keep the code to a good standard, the features working and security practices followed.

## License

[Apache License 2.0](LICENSE). Parts of the user interface are adapted from IntelliJ IDEA Community Edition, also Apache 2.0; see [NOTICE](NOTICE).
