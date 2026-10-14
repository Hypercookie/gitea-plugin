import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    // JSON (de)serialization for Gitea API DTOs. HTTP transport uses JDK's
    // built-in java.net.http.HttpClient — no separate HTTP client dependency,
    // avoids classloader conflicts with whatever the IJ Platform bundles internally.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Renders Gitea comment bodies (Markdown source) for the timeline tab.
    // Small, pure-Java, no transitive deps — same "avoid classloader
    // conflicts with the IJ Platform" reasoning as the HTTP client choice
    // in Phase 0, just for Markdown instead of JSON.
    implementation("org.commonmark:commonmark:0.21.0")

    // Coroutines are bundled by the IntelliJ Platform at runtime; compileOnly
    // avoids shipping/duplicating a second copy on the plugin classpath.
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2025.2.6.2")
        bundledPlugin("Git4Idea")
        // Shared code review UI the bundled GitHub/GitLab plugins are built
        // on (list, details, review in editor, comments, reactions).
        // Public, non-@Internal API only — see
        // docs/plan/collab-tools-migration-plan.md.
        bundledModule("intellij.platform.collaborationTools")
        // collaborationTools' own module dependencies; without them on the
        // compile classpath, Git4Idea's GitRepository supertypes stop
        // resolving.
        bundledModule("intellij.platform.vcs.dvcs.impl")
        bundledModule("intellij.platform.vcs.dvcs.impl.shared")
        bundledModule("intellij.platform.vcs.log.impl")
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginVerification {
        // Gate for the "public IntelliJ APIs only" rule: the verifier
        // reports @ApiStatus.Internal, experimental and deprecated usages.
        ides {
            create("IU", "2025.2.6.2")
        }
        failureLevel = listOf(
            org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.INTERNAL_API_USAGES,
            org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
        )
    }
}
