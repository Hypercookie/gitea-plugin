package com.github.hypercookie.giteaplugin.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One user's reaction on an issue or comment (`Reaction` in Gitea's
 * swagger). [content] is the reaction's name, e.g. `+1` or `heart`. */
@Serializable
data class GiteaReaction(
    val user: GiteaUser? = null,
    val content: String,
    @SerialName("created_at") val createdAt: String? = null,
)

/** Body for adding or removing a reaction (`EditReactionOption`). */
@Serializable
data class EditReactionOption(val content: String)

/** `GET /settings/ui` (`GeneralUISettings`): the reactions this server
 * accepts (`[ui] REACTIONS`) and its custom emoji (`[ui] CUSTOM_EMOJIS`). */
@Serializable
data class GiteaUISettings(
    @SerialName("allowed_reactions") val allowedReactions: List<String> = emptyList(),
    @SerialName("custom_emojis") val customEmojis: List<String> = emptyList(),
)
