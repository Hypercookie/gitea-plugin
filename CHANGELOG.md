<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Gitea Changelog

## [Unreleased]
### Added
- Settings to turn off the commit checks column, review comments in the editor, reactions, the pull request suggestion after a push and reloading on window focus; turning a feature off also stops its requests.
- An opt-in debug widget in the status bar that counts the Gitea API requests sent since the IDE started.
- Code blocks in review comments in the editor: Apply and Copy icon buttons on hover; Apply replaces the commented line with the block (greyed out, with the reason, where the file can't be edited).

### Changed
- Review comments in the editor widen to fit their longest code line, up to the editor's width; wider code scrolls sideways instead of being cut off.
- Code blocks typed in a comment field keep completion and highlighting for their language but no longer show errors for code that isn't a complete file (such as a Java statement outside a class).

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

[Unreleased]: https://github.com/Hypercookie/gitea-plugin/compare/v0.1.0-beta...HEAD
[0.1.0-beta]: https://github.com/Hypercookie/gitea-plugin/commits/v0.1.0-beta
