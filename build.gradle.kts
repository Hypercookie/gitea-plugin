import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import java.time.Duration
import java.util.Properties

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

// The IDE line this build targets: a directory in platforms/ with its
// platform.properties and any line-specific sources and resources
// (docs/platform-versions.md). -Pplatform=263 builds for 2026.3.
val platform = providers.gradleProperty("platform").get()
val platformDir = layout.projectDirectory.dir("platforms/$platform")
val platformProperties = Properties().apply {
    load(providers.fileContents(platformDir.file("platform.properties")).asText.get().reader())
}
fun platformProperty(name: String): String =
    platformProperties.getProperty(name) ?: error("platforms/$platform/platform.properties lacks $name")
fun platformList(name: String): List<String> =
    platformProperty(name).split(',').map(String::trim).filter(String::isNotEmpty)

// The release version (CHANGELOG.md section, Marketplace channel, Git tag).
val releaseVersion = providers.gradleProperty("version").get()

group = providers.gradleProperty("group").get()
// Each IDE line's build is its own Marketplace upload, which needs a
// version of its own: 1.0.0-252, 1.0.0-263.
version = "$releaseVersion-$platform"

kotlin.sourceSets.main {
    kotlin.srcDir(platformDir.dir("src/main/kotlin"))
    resources.srcDir(platformDir.dir("src/main/resources"))
}
kotlin.sourceSets.test {
    kotlin.srcDir(platformDir.dir("src/test/kotlin"))
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // Plugins run on the IDE's bundled Kotlin stdlib (2.2 in 2025.2, the
        // oldest supported line), not the compiler's. Newer API levels emit
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
        // Per IDE line, with why each module is needed, in
        // platforms/<line>/platform.properties.
        intellijIdea(platformProperty("platformVersion"))
        bundledPlugins(platformList("bundledPlugins"))
        bundledModules(platformList("bundledModules"))
        testBundledPlugins(platformList("testBundledPlugins"))
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        version = project.version.toString()

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
            sinceBuild = platformProperty("sinceBuild")
            untilBuild = platformProperty("untilBuild")
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
        // Without -PverifyIdeLine, the latest release of every IDE line in
        // the platform's sinceBuild..untilBuild range. CI runs one line per
        // job (verifyLines in platform.properties): -PverifyIdeLine=253
        // checks the newest 2025.3 build, an EAP while the line has no
        // release yet.
        ides {
            when (val line = providers.gradleProperty("verifyIdeLine").orNull) {
                null -> recommended()
                else -> latest {
                    sinceBuild = line
                    untilBuild = "$line.*"
                    channels = listOf(
                        org.jetbrains.intellij.platform.gradle.models.ProductRelease.Channel.RELEASE,
                        org.jetbrains.intellij.platform.gradle.models.ProductRelease.Channel.RC,
                        org.jetbrains.intellij.platform.gradle.models.ProductRelease.Channel.EAP,
                    )
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
