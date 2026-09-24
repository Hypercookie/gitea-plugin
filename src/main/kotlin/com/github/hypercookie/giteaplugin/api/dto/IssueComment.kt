package com.github.hypercookie.giteaplugin.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A top-level (non-inline) PR comment. Gitea models PRs as issues, so these
 * come from the shared `/issues/{index}/comments` endpoint. */
@Serializable
data class IssueComment(
    val id: Long,
    val body: String,
    val user: GiteaUser? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
)

@Serializable
data class CreateIssueCommentOption(val body: String)
