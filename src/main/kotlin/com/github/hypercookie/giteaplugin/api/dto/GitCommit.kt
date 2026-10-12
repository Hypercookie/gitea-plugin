package com.github.hypercookie.giteaplugin.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One entry from `GET .../pulls/{index}/commits`. Only the fields
 * `TimelinePanel` (Phase 27) actually renders are decoded. */
@Serializable
data class GitCommit(
    val sha: String,
    val commit: CommitDetail,
    val author: GiteaUser? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
)

@Serializable
data class CommitDetail(
    val message: String,
    val author: CommitSignature,
)

@Serializable
data class CommitSignature(
    val name: String,
    val date: String,
)
