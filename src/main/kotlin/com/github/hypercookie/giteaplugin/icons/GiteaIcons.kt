package com.github.hypercookie.giteaplugin.icons

import com.intellij.openapi.util.IconLoader

/**
 * Original pull request glyph used for the tool window and other Gitea
 * entries. `gitea.svg`/`gitea_dark.svg` are monochrome (IntelliJ's tool
 * window icon convention); the colored plugin icon is
 * `META-INF/pluginIcon.svg`. Neither uses Gitea's logo.
 */
object GiteaIcons {
    @JvmField
    val Gitea = IconLoader.getIcon("/icons/gitea.svg", GiteaIcons::class.java)
}
