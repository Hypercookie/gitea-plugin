package com.github.hypercookie.giteaplugin.pullrequest.reactions

import com.github.hypercookie.giteaplugin.util.GiteaImages
import com.intellij.collaboration.ui.codereview.reactions.CodeReviewReactionsUIUtil
import com.intellij.collaboration.ui.icon.AsyncImageIconsProvider
import com.intellij.collaboration.ui.icon.CachingIconsProvider
import com.intellij.collaboration.ui.icon.IconsProvider
import com.intellij.icons.AllIcons
import com.intellij.util.IconUtil
import kotlinx.coroutines.CoroutineScope
import java.awt.Image
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.swing.Icon

/**
 * Icons for reactions, by Gitea reaction name (`+1`, `heart`, ...).
 *
 * The GitHub plugin downloads GitHub's emoji images (`GHReactionImageLoader`).
 * Gitea's web UI instead shows its reactions as Unicode emoji, except the
 * server's custom emoji (`[ui] CUSTOM_EMOJIS`), which are images under
 * `/assets/img/emoji/`. This does the same: a Unicode emoji drawn with the
 * platform's `CodeReviewReactionsUIUtil.createUnicodeEmojiIcon` (what the
 * GitLab plugin uses), or the server's image for a custom emoji.
 */
internal class GiteaReactionIcons(
    cs: CoroutineScope,
    serverUrl: String,
    private val isCustom: (String) -> Boolean,
) : IconsProvider<String> {

    private val customIcons = CachingIconsProvider(
        AsyncImageIconsProvider(cs, object : AsyncImageIconsProvider.AsyncImageLoader<String> {
            override suspend fun load(key: String): Image? {
                val name = URLEncoder.encode(key, StandardCharsets.UTF_8).replace("+", "%20")
                return GiteaImages.load("${serverUrl.trimEnd('/')}/assets/img/emoji/$name.png")
            }

            override fun createBaseIcon(key: String?, iconSize: Int): Icon = IconUtil.resizeSquared(AllIcons.Nodes.Unknown, iconSize)
        }),
    )

    override fun getIcon(key: String?, iconSize: Int): Icon {
        val unicode = key?.let(GiteaEmoji::unicode)
        return when {
            key == null -> IconUtil.resizeSquared(AllIcons.Nodes.Unknown, iconSize)
            unicode != null -> CodeReviewReactionsUIUtil.createUnicodeEmojiIcon(unicode, iconSize)
            isCustom(key) -> customIcons.getIcon(key, iconSize)
            else -> IconUtil.resizeSquared(AllIcons.Nodes.Unknown, iconSize)
        }
    }
}

/** Unicode for the emoji names Gitea servers commonly allow as reactions. */
internal object GiteaEmoji {

    /** Names as Gitea's emoji data spells them (gemoji aliases, plus Gitea's
     * own `laugh` for 😆 and `hooray` for 🎉 used by its default reactions). */
    private val byName: Map<String, String> = mapOf(
        "+1" to "👍", "thumbsup" to "👍",
        "-1" to "👎", "thumbsdown" to "👎",
        "laugh" to "😆", "laughing" to "😆", "satisfied" to "😆",
        "hooray" to "🎉", "tada" to "🎉",
        "confused" to "😕",
        "heart" to "❤️",
        "rocket" to "🚀",
        "eyes" to "👀",
        "smile" to "😄",
        "smiley" to "😃",
        "grinning" to "😀",
        "joy" to "😂",
        "wink" to "😉",
        "slightly_smiling_face" to "🙂",
        "thinking" to "🤔",
        "cry" to "😢",
        "sob" to "😭",
        "angry" to "😠",
        "scream" to "😱",
        "sunglasses" to "😎",
        "heart_eyes" to "😍",
        "clap" to "👏",
        "pray" to "🙏",
        "ok_hand" to "👌",
        "wave" to "👋",
        "muscle" to "💪",
        "raised_hands" to "🙌",
        "fire" to "🔥",
        "100" to "💯",
        "sparkles" to "✨",
        "star" to "⭐",
        "zap" to "⚡",
        "boom" to "💥",
        "bug" to "🐛",
        "white_check_mark" to "✅",
        "heavy_check_mark" to "✔️",
        "x" to "❌",
        "warning" to "⚠️",
        "question" to "❓",
        "exclamation" to "❗",
        "coffee" to "☕",
        "beer" to "🍺",
        "trophy" to "🏆",
        "bulb" to "💡",
        "memo" to "📝",
        "lock" to "🔒",
        "see_no_evil" to "🙈",
        "facepalm" to "🤦",
        "shrug" to "🤷",
    )

    fun unicode(name: String): String? = byName[name]

    /** Name shown in the pill tooltip, like the GitHub plugin's
     * `GHReactionContent.presentableName` ("thumbs up"). */
    fun presentableName(name: String): String = when (name) {
        "+1", "thumbsup" -> "thumbs up"
        "-1", "thumbsdown" -> "thumbs down"
        else -> name.replace('_', ' ')
    }
}
