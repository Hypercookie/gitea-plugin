package com.github.hypercookie.giteaplugin.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GiteaLabel(
    val id: Long,
    val name: String,
    /** Hex without `#`, as Gitea returns it (e.g. `e11d21`). */
    val color: String? = null,
    val description: String? = null,
)

/** `Issue.pull_request` — present when the issue is a pull request. */
@Serializable
data class PullRequestMeta(
    val merged: Boolean = false,
    val draft: Boolean = false,
    @SerialName("html_url") val htmlUrl: String? = null,
)

@Serializable
data class IssueRepository(
    val name: String? = null,
    val owner: String? = null,
    @SerialName("full_name") val fullName: String? = null,
)

/** An issue as returned by `GET /repos/{owner}/{repo}/issues` and
 * `GET /repos/issues/search`. With `type=pulls`, every result is a pull
 * request; the list view uses these because those endpoints support text
 * search and assignee filters that `/pulls` lacks. */
@Serializable
data class GiteaIssue(
    val id: Long,
    val number: Long,
    val title: String,
    val state: String,
    val user: GiteaUser? = null,
    val labels: List<GiteaLabel> = emptyList(),
    val assignees: List<GiteaUser>? = null,
    val comments: Int = 0,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    @SerialName("pull_request") val pullRequest: PullRequestMeta? = null,
    val repository: IssueRepository? = null,
)
