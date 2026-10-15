package com.github.hypercookie.giteaplugin.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One entry from `GET .../issues/{index}/timeline` (PRs are issues in
 * Gitea's model). Fields not every event type carries are optional. */
@Serializable
data class TimelineEvent(
    val id: Long,
    val type: String,
    val body: String? = null,
    val user: GiteaUser? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("review_id") val reviewId: Long? = null,
    val label: TimelineLabel? = null,
    val assignee: GiteaUser? = null,
    @SerialName("old_title") val oldTitle: String? = null,
    @SerialName("new_title") val newTitle: String? = null,
)

@Serializable
data class TimelineLabel(val name: String? = null, val color: String? = null)

/** `TimelineEvent.body` for `type == "pull_push"` is itself a JSON string
 * (not a nested object) — this decodes that inner payload. */
@Serializable
data class PullPushBody(
    @SerialName("is_force_push") val isForcePush: Boolean = false,
    @SerialName("commit_ids") val commitIds: List<String> = emptyList(),
)
