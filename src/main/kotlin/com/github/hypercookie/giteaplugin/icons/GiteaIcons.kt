package com.github.hypercookie.giteaplugin.icons

import com.intellij.openapi.util.IconLoader

/**
 * Gitea's own logo (MIT-licensed, sourced from Gitea's official brand
 * assets), used for the tool window and anywhere else a Gitea-branded icon
 * belongs — per the user's explicit "modern UI" direction rather than a
 * generic VCS glyph. `gitea.svg`/`gitea_dark.svg` are monochrome
 * (IntelliJ's tool-window icon convention); the full-color version lives at
 * `META-INF/pluginIcon.svg`.
 */
object GiteaIcons {
    @JvmField
    val Gitea = IconLoader.getIcon("/icons/gitea.svg", GiteaIcons::class.java)
}
