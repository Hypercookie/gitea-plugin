# Development

Back to the [README](../README.md).


The project uses Kotlin and the
[IntelliJ Platform Gradle Plugin](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html),
and builds once per IDE line range: `platforms/<line>/` holds each range's IDE
version, build range and line-specific code (see
[docs/platform-versions.md](platform-versions.md)). Without
`-Pplatform`, the build targets 252 to 262 against IntelliJ IDEA 2025.2.

```shell
./gradlew runIde                  # start a sandbox IDE with the plugin installed
./gradlew check                   # run the tests
./gradlew verifyPlugin            # run the IntelliJ Plugin Verifier
./gradlew buildPlugin             # build the plugin archive
./gradlew check -Pplatform=263    # the same for 2026.3
```

`verifyPlugin` checks the plugin against the latest release of every IDE
version in the profile's range; `-PverifyIdeLine=261` checks one. It fails on
compatibility problems and on any use of internal IntelliJ Platform API.

Live integration tests run against a Gitea server when `GITEA_DEV_TOKEN` is
set. `scripts/gitea-test-server.sh` starts a throwaway one of any version;
see [docs/local-gitea-dev-instance.md](local-gitea-dev-instance.md).

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
| `pullrequest/reactions`     | Reactions on comments and the description, reaction picker   |
| `pullrequest/review`        | Review data, threads, line mapping, submit review popup      |
| `pullrequest/review/editor` | Review in the editor: comments and gutter                    |
| `pullrequest/diff`          | Comments in the diff viewer                                  |
| `mcp`                       | Gitea tools for the IDE's MCP server                         |
| `repo`                      | Matching Git remotes to accounts                             |
| `settings`                  | Settings page and login                                      |
| `util`                      | Shared helpers                                               |

