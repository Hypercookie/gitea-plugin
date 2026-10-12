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
        testFramework(TestFrameworkType.Platform)
    }
}
