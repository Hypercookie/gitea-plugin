package com.github.hypercookie.giteaplugin.pullrequest.review

import com.github.hypercookie.giteaplugin.api.dto.GiteaUser
import com.intellij.collaboration.ui.icon.AsyncImageIconsProvider
import com.intellij.collaboration.ui.icon.CachingIconsProvider
import com.intellij.collaboration.ui.icon.IconsProvider
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Image
import java.net.URI
import javax.imageio.ImageIO
import javax.swing.Icon

/**
 * Avatar icons for Gitea users, through the collaboration tools' own async
 * loading and caching (the same providers the GitHub plugin builds its
 * avatar icons on). Gitea's `avatar_url` already points at the uploaded or
 * Gravatar image, so the loader just downloads it.
 */
@Service(Service.Level.PROJECT)
class GiteaAvatarIcons(cs: CoroutineScope) : IconsProvider<GiteaUser?> {

    private val delegate = CachingIconsProvider(
        AsyncImageIconsProvider(cs, object : AsyncImageIconsProvider.AsyncImageLoader<String> {
            override suspend fun load(key: String): Image? {
                val url = key.takeIf { it.isNotBlank() } ?: return null
                return withContext(Dispatchers.IO) { runCatching { ImageIO.read(URI(url).toURL()) }.getOrNull() }
            }
        }),
    )

    override fun getIcon(key: GiteaUser?, iconSize: Int): Icon = delegate.getIcon(key?.avatarUrl.orEmpty(), iconSize)

    companion object {
        fun getInstance(project: Project): GiteaAvatarIcons = project.service()
    }
}
