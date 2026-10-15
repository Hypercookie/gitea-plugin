package com.github.hypercookie.giteaplugin.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PrBranchInfo(
    val label: String? = null,
    val ref: String,
    val sha: String,
)

@Serializable
data class PullRequest(
    val id: Long,
    val number: Long,
    val title: String,
    val body: String? = null,
    val state: String,
    val draft: Boolean = false,
    val merged: Boolean = false,
    val mergeable: Boolean? = null,
    val user: GiteaUser? = null,
    val base: PrBranchInfo,
    val head: PrBranchInfo,
    @SerialName("html_url") val htmlUrl: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("changed_files") val changedFiles: Int? = null,
    val additions: Int? = null,
    val deletions: Int? = null,
    val comments: Int? = null,
    /** Common ancestor of head and base; the "before" side of the PR's own
     * changes (`base.sha` would also include unrelated target-branch work). */
    @SerialName("merge_base") val mergeBase: String? = null,
    val labels: List<GiteaLabel> = emptyList(),
    val assignees: List<GiteaUser>? = null,
    @SerialName("requested_reviewers") val requestedReviewers: List<GiteaUser>? = null,
)
