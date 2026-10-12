package com.github.hypercookie.giteaplugin.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One entry from `GET .../issues/{index}/timeline` (PRs are issues in
 * Gitea's model). Confirmed against a live 1.27.3 instance for Phase 27 —
 * real data only ever produced `type` values `pull_push`, `review`, and
 * `comment` for our fixture PRs; other types (`label`, `assignee`,
 * `milestone`, title-change, etc.) are decoded defensively but rendered via
 * a generic fallback in `TimelinePanel`, not special-cased, since we have no
 * real data to verify their shape against yet. */
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
