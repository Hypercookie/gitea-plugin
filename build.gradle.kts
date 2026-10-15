import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import java.time.Duration

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

group = providers.gradleProperty("group").get()
version = providers.gradleProperty("version").get()

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // Plugins run on the IDE's bundled Kotlin stdlib (2.2 in 2025.2, the
        // sinceBuild line), not the compiler's. Newer API levels emit
        // bytecode that stdlib can't read: Kotlin 2.4 writes coroutine
        // debug metadata v2, which 2.2's DebugProbes reject, so every
        // resumed coroutine dies and its caller waits forever.
        apiVersion = org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2
        languageVersion = org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2
        // Kotlin 2.2+ defaults to ENABLE, which emits compatibility bridges in
        // classes for inherited interface defaults. On platform interfaces
        // like ToolWindowFactory those bridges count as overrides of
        // @ApiStatus.Internal methods and fail verifyPlugin.
        jvmDefault = org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    // JSON (de)serialization for Gitea API DTOs. HTTP transport uses JDK's
    // built-in java.net.http.HttpClient — no separate HTTP client dependency,
    // avoids classloader conflicts with whatever the IJ Platform bundles internally.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // Markdown rendering uses the org.intellij.markdown parser bundled with the
    // IntelliJ Platform (as the GitHub plugin does), so no Markdown library is
    // shipped with the plugin.

    // Coroutines are bundled by the IntelliJ Platform at runtime; compileOnly
    // avoids shipping/duplicating a second copy on the plugin classpath.
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    intellijPlatform {
        intellijIdea("2025.2.6.2")
        bundledPlugin("Git4Idea")
        // Shared code review UI the bundled GitHub/GitLab plugins are built
        // on (list, details, review in editor, comments). Public,
        // non-@Internal API only; verifyPlugin enforces this.
        bundledModule("intellij.platform.collaborationTools")
        // collaborationTools' own module dependencies; without them on the
        // compile classpath, Git4Idea's GitRepository supertypes stop
        // resolving.
        bundledModule("intellij.platform.vcs.dvcs.impl")
        bundledModule("intellij.platform.vcs.dvcs.impl.shared")
        bundledModule("intellij.platform.vcs.log.impl")
        // AsyncChangesTree (the PR changes tree from CodeReviewChangeListComponentFactory).
        bundledModule("intellij.platform.vcs.impl")
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        version = providers.gradleProperty("version")

        // Change notes for Marketplace come from the matching CHANGELOG.md
        // section ("Unreleased" until a release patches it).
        val changelog = project.changelog
        changeNotes = providers.gradleProperty("version").map { pluginVersion ->
            with(changelog) {
                renderItem(
                    (getOrNull(pluginVersion) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }

        ideaVersion {
            sinceBuild = "252"
        }
    }

    // Marketplace signing and publishing, used by the Release workflow.
    // Values come from repository secrets; nothing is needed locally.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // A pre-release version like 1.2.0-beta.1 goes to the "beta" channel.
        channels = providers.gradleProperty("version").map {
            listOf(it.substringAfter('-', "").substringBefore('.').ifEmpty { "default" })
        }
    }

    pluginVerification {
        // Gate for the "public IntelliJ APIs only" rule: the verifier
        // reports @ApiStatus.Internal, experimental and deprecated usages.
        // Without -PverifyIdeLine, the latest release of every IDE line from
        // sinceBuild on (no untilBuild is set). CI runs one line per job:
        // -PverifyIdeLine=253 checks the latest 2025.3.x, and
        // -PverifyIdeLine=latest the newest release.
        ides {
            val line = providers.gradleProperty("verifyIdeLine").orNull
            when (line) {
                null -> recommended()
                "latest" -> latest()
                else -> latest {
                    sinceBuild = line
                    untilBuild = "$line.*"
                }
            }
        }
        failureLevel = listOf(
            org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.INTERNAL_API_USAGES,
            org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
        )
    }
}

tasks.test {
    // Live tests talk to a local Gitea dev instance (docs/local-gitea-dev-instance.md).
    // They only run when GITEA_DEV_TOKEN is set, so CI (GitHub Actions) never
    // depends on a Gitea server.
    val giteaToken = providers.environmentVariable("GITEA_DEV_TOKEN")
    if (!giteaToken.isPresent) {
        exclude("**/integration/**")
    }
    // A hung test should fail the build, not hold a CI runner for an hour;
    // the event log names the test that never finished.
    timeout = Duration.ofMinutes(20)
    testLogging {
        events("started", "passed", "skipped", "failed")
    }
}

changelog {
    groups.empty()
    repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
}
