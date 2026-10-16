package com.github.hypercookie.giteaplugin.pullrequest.review

import com.github.hypercookie.giteaplugin.api.dto.GiteaUser
import com.github.hypercookie.giteaplugin.util.GiteaImages
import com.intellij.collaboration.ui.icon.AsyncImageIconsProvider
import com.intellij.collaboration.ui.icon.CachingIconsProvider
import com.intellij.collaboration.ui.icon.IconsProvider
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.IconUtil
import com.intellij.util.ui.ImageUtil
import icons.CollaborationToolsIcons
import kotlinx.coroutines.CoroutineScope
import java.awt.Image
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
                return GiteaImages.load(key)
            }

            // Same as the GitHub plugin's avatar loader: a default avatar
            // while loading, and round avatars once loaded.
            override fun createBaseIcon(key: String?, iconSize: Int): Icon =
                IconUtil.resizeSquared(CollaborationToolsIcons.Review.DefaultAvatar, iconSize)

            override suspend fun postProcess(image: Image): Image =
                ImageUtil.createCircleImage(ImageUtil.toBufferedImage(image))
        }),
    )

    override fun getIcon(key: GiteaUser?, iconSize: Int): Icon = delegate.getIcon(key?.avatarUrl.orEmpty(), iconSize)

    companion object {
        fun getInstance(project: Project): GiteaAvatarIcons = project.service()
    }
}
