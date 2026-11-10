# Features and settings reference

Detailed reference moved out of the README. Back to the [README](../README.md).

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
- The changed files can be filtered: changed locally, has unresolved, pending,
  your own or any comments (or none), and by change type (added, modified,
  deleted, renamed) and file type. Active filters combine.
- The diff tab is named after the current file and follows you when you step
  to another one. **Select in Changes Tree** finds the diffed file in the
  pull request's changes tree.
- Open the diff of the whole pull request or of a single commit in the IDE's
  diff viewer, or use **Jump to Source** (<kbd>F4</kbd>) in the changed files
  to open the local file.
- **Previous Comment** and **Next Comment** in the diff toolbar step through
  the review threads of the pull request, across files. Resolved threads are
  skipped unless **Include Resolved Comments in Navigation** is turned on in
  the diff's context menu.
- The timeline opens in an editor tab with the description, comments, reviews,
  pushed commits and other events, and a field for new comments.
- Reactions show as pills under the description and each comment. Hover a pill
  to see who reacted, click it to add or remove your own reaction, or pick a
  new one with the smiley in the comment's header.

### Review in the editor

- On the branch of an open pull request, review threads appear under the
  commented lines in the regular editor, and lines changed by the pull request
  are marked in the gutter.
- Threads follow your local edits. A thread whose line has changed since the
  comment was written stays at the changed block, collapsed and marked
  **Outdated**.
- Reply to threads, resolve and unresolve them, and add new comments from the
  gutter, either to a pending review (<kbd>Cmd/Ctrl</kbd>+<kbd>Enter</kbd>) or
  as a single comment (<kbd>Shift</kbd>+<kbd>Cmd/Ctrl</kbd>+<kbd>Enter</kbd>).
- Edit and delete your own comments, in the editor and in the timeline.
- Code blocks in comments have Copy and Apply buttons on hover; Apply
  replaces the commented line with the block.
- The same comments appear in the side-by-side, unified and one-sided diff
  viewers.
- Typing `@` in a comment suggests users to mention.
- Review comments show reactions, which you can add and remove as in the
  timeline.
- A banner above the editor shows when a review is pending, and when the
  local branch is ahead of, behind or diverged from the pull request.
- The pull request is found through the branch's upstream, so local branches
  with a different name work, and multi-root projects are supported.

### Experimental features

Both are off by default, might change or break, and are turned on under
<kbd>Settings</kbd> | <kbd>Version Control</kbd> | <kbd>Gitea</kbd> |
**Experimental**. Gitea's API has neither, so the plugin stores the data as a
hidden marker in the comment text, which Gitea does not display.

- **Comment on multiple lines**: select lines and use **Add Comment on
  Selected Lines**. The range is highlighted in the editor and diffs. Ranges
  written by others are always shown as a "Lines 3-5" label.
- **Code suggestions in review comments**: **Suggest Change** starts a comment
  with a suggestion block holding the commented lines. Anyone using the plugin
  gets an **Apply suggestion** button under it, which replaces the lines in the
  local file as one undo step (and refuses if the original code has changed).
  Plain `suggestion` blocks typed in the Gitea web UI get the button too. A
  comment can hold one suggestion.

### Submitting reviews

- **Submit Review** shows the number of pending comments, a field for the
  review summary, and the actions **Approve**, **Request Changes** and
  **Comment**. Pending comments can also be discarded.
- Approve and Request Changes are hidden on your own pull requests, which Gitea
  does not allow.

### Git log

- A **Gitea Commit Checks** column shows the commit status of each commit in
  the Git log. Click it to open the check or the commit page. It is off by
  default, since it asks the server once per commit shown; turn it on in the
  Gitea settings.

### Accounts

- Accounts are managed under <kbd>Settings</kbd> | <kbd>Version Control</kbd> |
  <kbd>Gitea</kbd>. The server URL and access token are checked before an
  account is saved, and one account can be set as the project default.
- Access tokens are stored in the IDE's password safe.
- Git remotes are matched to accounts by host and path, so servers hosted
  under a subpath (such as `https://example.com/gitea`) work. HTTPS, `ssh://`
  and `git@host:owner/repo.git` remotes are supported.

## Settings

Under <kbd>Settings</kbd> | <kbd>Version Control</kbd> | <kbd>Gitea</kbd>:

- **Accounts** and the project's default account.
- **Features**: turn off review comments in the editor, reactions, the pull
  request suggestion after a push and reloading on window focus (turning a
  feature off also stops its requests), and turn on the commit checks column
  in the Git log. Two switches for the [MCP tools](#mcp-server-tools) are here
  too.
- **Experimental**: multi-line comments and code suggestions, see above.
- **Debug**: a status bar widget counting the Gitea API requests sent since
  the IDE started.

## MCP server tools

If the IDE's MCP Server plugin is installed, the plugin can offer Gitea tools
to AI assistants connected to it. The plugin works as before without the MCP
Server plugin.

- **Off by default.** Turn on **Offer Gitea tools to the IDE's MCP server
  (read-only)** under <kbd>Settings</kbd> | <kbd>Version Control</kbd> |
  <kbd>Gitea</kbd> | **Features**. Turning it off removes the tools again
  without a restart. Once on, they show as "Gitea" under <kbd>Settings</kbd> |
  <kbd>Tools</kbd> | <kbd>MCP Server</kbd> | **Exposed Tools**, where each can
  be switched off.
- **Read tools**: list repositories, list and search pull requests, and read a
  pull request's details, files, commits, timeline, checks, comments, review
  threads and reactions. `gitea_get_pending_review` shows the comments queued
  in the IDE's pending review.
- **Write tools** need the second switch, **Also allow changing pull
  requests**, which is off by default. They comment, reply, resolve threads,
  add and remove reactions and submit reviews as your Gitea account, so treat
  the pull request text an assistant reads as untrusted input.
- **Review mode** (also behind the write switch): `gitea_add_pending_review_comment`
  queues inline comments without posting them, `gitea_discard_pending_review`
  removes one or all, and `gitea_submit_pending_review` submits them as one
  review with Approve, Comment or Request Changes. The queue is the same
  pending review the IDE shows in the editor and diff.


## Known limitations

- Pull requests from forks cannot be checked out.
- Requesting reviewers, editing labels and assignees, and creating, merging or
  closing pull requests are not supported yet.
- Gitea returns no reactions with comments, so they load with one request per
  comment. Other people's new reactions appear after refreshing the pull
  request or reopening the timeline.
- Files cannot be marked as viewed, because Gitea's API does not support it.
  It has no multi-line comments or suggestions either; the experimental
  versions of both rely on hidden markers in the comment text.
- The timeline does not show the code snippet of review threads.
- Timeline tabs are not restored after restarting the IDE.

