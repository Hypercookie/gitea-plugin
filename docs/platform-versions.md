# IDE and Gitea versions

How the plugin supports several IntelliJ Platform versions and Gitea versions
at once, and how it keeps up with new ones.

## IDE line profiles

The IntelliJ Platform changes between yearly releases in ways one plugin
archive can't always bridge. 2026.3 (build 263), for example, moved the
version control code into a bundled "Version Control" plugin and split it into
modules that don't exist in 252 to 262; a plugin descriptor can't depend on a
module the IDE lacks. So the build runs once per range of IDE lines, each
described by a profile in `platforms/<line>/`:

| File                                   | Contents                                                                                     |
|----------------------------------------|----------------------------------------------------------------------------------------------|
| `platform.properties`                  | IDE to compile and test against, `sinceBuild`/`untilBuild`, lines to verify, bundled plugins and modules |
| `src/main/resources/META-INF/gitea-platform.xml` | The `<dependencies>` block of `plugin.xml`, which includes it                       |
| `src/main/kotlin/`                     | Main code that differs per line (optional)                                                  |
| `src/test/kotlin/`                     | Test code that differs per line, such as `PlatformTestCompat.kt`                            |

`-Pplatform=<line>` picks the profile (default in `gradle.properties`). Each
profile's archive gets its own version, `1.0.0-252`, `1.0.0-263`, because each
is a separate Marketplace upload; the Release workflow builds and publishes
all of them.

### Code that differs per line

Most of the plugin is shared. When an API the plugin uses changes between
lines (experimental code review APIs often do), put a small function or class
with the same signature in each profile's `src/main/kotlin/` (or
`src/test/kotlin/`) and call it from the shared code. Only the profile being
built is on the source path. `PlatformTestCompat.kt` is an example: 263
changed `DynamicPlugins.checkCanUnloadWithoutRestart`.

Keep these files small and give each profile the same declarations, so the
shared code compiles against every profile.

### Adding a new IDE line

The weekly check (below) first extends the newest profile's `untilBuild` to a
new line. If the Build workflow then passes, nothing else is needed. If the
line breaks the build or the Plugin Verifier:

1. `python3 scripts/check-version-updates.py --new-profile --write` copies the
   newest profile to `platforms/<new line>/` with the new line's build range.
   Reset the previous profile's `untilBuild` and `verifyLines`.
2. Fix the new profile: modules in `platform.properties` and
   `gitea-platform.xml`, and line-specific code in its `src/`.
3. `./gradlew check verifyPlugin -Pplatform=<line> -PverifyIdeLine=<line>`.

## Gitea versions

`.github/gitea-versions.txt` lists the Gitea versions the live tests run
against: the newest patch of every supported release series, oldest first.
`scripts/gitea-test-server.sh start <version>` starts any of them from the
release binary with the test fixture (no Docker needed), and the Build
workflow runs the live tests against each.

Features only newer servers have are gated on the server's version
(`GET /api/v1/version`) in `GiteaServerFeatures`:

| Feature                  | Since |
|--------------------------|-------|
| Resolve review comments  | 1.26  |
| Reply to review comments | 1.27  |

`GiteaLiveIntegrationTest.testServerFeaturesMatchServerEndpoints` checks each
gate against the server it runs on. When a new Gitea version adds or changes
an endpoint the plugin uses, add a gate there, use it where the feature is
offered, and extend the test.

## Keeping up

`scripts/check-version-updates.py` finds:

- newer builds of the IDE each profile compiles against (EAP or RC while a
  line has no release yet),
- new IDE lines, including EAPs, and lines missing from `verifyLines`,
- new Gitea patch releases and release series.

Without `--write` it only reports. The Version Check workflow runs it every
Monday and opens or refreshes a pull request on the `version-updates` branch,
whose Build run verifies the changes. A scheduled Claude task does the same
check, runs the builds and live tests itself, and fixes what a new version
breaks, so updates don't depend on GitHub Actions minutes.
