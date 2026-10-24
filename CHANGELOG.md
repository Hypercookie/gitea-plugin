<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Gitea Changelog

## [Unreleased]
### Added
- Support for IntelliJ 2026.3 (build 263), as a separate plugin archive: 2026.3 moved the version control modules the plugin depends on.

### Changed
- Resolve and Reply on review comments are hidden on Gitea servers that lack them (resolving needs Gitea 1.26, replying 1.27) instead of failing.
- Tested with Gitea 1.24 to 1.27 and 28.1.

## [1.0.0] - 2026-10-16
### Added
- Settings to turn off the commit checks column, review comments in the editor, reactions, the pull request suggestion after a push and reloading on window focus; turning a feature off also stops its requests.
- An opt-in debug widget in the status bar that counts the Gitea API requests sent since the IDE started.
- Code blocks in review comments in the editor: Apply and Copy icon buttons on hover; Apply replaces the commented line with the block (greyed out, with the reason, where the file can't be edited).
- Shift+Cmd+Enter (Shift+Ctrl+Enter on Windows and Linux) posts a new review comment as a single comment; Cmd+Enter still starts or adds to the review.

### Changed
- Supports IDE builds 252 to 262 (2025.2 to 2026.2); 2026.3 support follows in a later version.
- Review comments in the editor widen to fit their longest code line, up to the editor's width; wider code scrolls sideways instead of being cut off.
- Code blocks in the pull request timeline scroll sideways and have a Copy button on hover, and timeline comments always fit the view's width instead of being cut off on the right.
- Code blocks typed in a comment field keep completion and highlighting for their language but no longer show errors for code that isn't a complete file (such as a Java statement outside a class).
- Comment inputs no longer show the "Enter to add a new line" hint.
- Far fewer Gitea API requests: coming back to the IDE reloads pull request data at most every two minutes and keeps reaction lists fetched in the last five; the background comment check only fetches the comments of reviews that changed; commits, changed files and reviews are fetched once and shared by the views that show them; git events at an unchanged HEAD no longer fetch the pull request each time; paged lists no longer ask for an empty page after the last one.
- The Gitea Commit Checks column in the Git log is off by default, since it sends one request per commit shown; turn it on under Settings | Version Control | Gitea.
- The debug request counter's tooltip lists the most requested endpoints.

### Fixed
- Start Review, Add Single Comment, Reply and Comment stay disabled, and their shortcuts do nothing, until the comment has text other than whitespace.

## [0.1.0-beta] - 2026-10-15
### Added
- Gitea accounts with token login, stored in the IDE password safe.
- Pull Requests tool window with search, quick filters, filters and paging.
- Pull request tab with commits, changed files, status checks, reviewers and diffs.
- Pull request timeline in an editor tab, with new top-level comments.
- Review threads in the editor and the diff viewer: reply, resolve, add comments.
- Reactions on the description, comments and review comments: see who reacted, add and remove your own.
- Pending reviews, submitted as approval, comment or change request.
- Branch checkout from the pull request list.
- A notification after pushing a branch with no open pull request, opening Gitea's page to create one.
- Commit status checks in a "Gitea Commit Checks" column of the Git log.
- Editable pull request diffs and Jump to Source while the pull request branch is checked out.

[Unreleased]: https://github.com/Hypercookie/gitea-plugin/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/Hypercookie/gitea-plugin/compare/v0.1.0-beta...v1.0.0
[0.1.0-beta]: https://github.com/Hypercookie/gitea-plugin/commits/v0.1.0-beta
