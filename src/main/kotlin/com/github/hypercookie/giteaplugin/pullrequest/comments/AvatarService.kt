package com.github.hypercookie.giteaplugin.pullrequest.comments

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.image.BufferedImage
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import javax.swing.Icon
import javax.swing.ImageIcon

/**
 * Loads and circularly-clips user avatars (Gitea's `avatar_url` — already
 * resolved server-side to an uploaded avatar or a federated Gravatar, so no
 * email-hashing needed here), cached by url+size. Used inside
 * the PR list and timeline rows (editor inlays use
 * [com.github.hypercookie.giteaplugin.pullrequest.review.GiteaAvatarIcons]).
 */
object AvatarService {

    private val cache = ConcurrentHashMap<String, Icon?>()

    suspend fun loadIcon(url: String?, sizePx: Int): Icon? {
        if (url.isNullOrBlank()) return null
        val key = "$url@$sizePx"
        cache[key]?.let { return it }
        if (cache.containsKey(key)) return null // cached failure

        val icon = withContext(Dispatchers.IO) {
            runCatching {
                val raw = ImageIO.read(URI(url).toURL()) ?: return@runCatching null
                circularIcon(raw, sizePx)
            }.getOrNull()
        }
        cache[key] = icon
        return icon
    }

    private fun circularIcon(source: BufferedImage, sizePx: Int): Icon {
        val image = BufferedImage(sizePx, sizePx, BufferedImage.TYPE_INT_ARGB)
        val g: Graphics2D = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.clip = Ellipse2D.Float(0f, 0f, sizePx.toFloat(), sizePx.toFloat())
            g.drawImage(source, 0, 0, sizePx, sizePx, null)
        } finally {
            g.dispose()
        }
        return ImageIcon(image)
    }
}
