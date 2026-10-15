package com.github.hypercookie.giteaplugin.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One entry from `GET .../pulls/{index}/commits`. Only the fields
 * the plugin uses are decoded. */
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

@Serializable
data class CommitParent(val sha: String)

@Serializable
data class CommitAffectedFile(val filename: String, val status: String? = null)

/** `GET /repos/{owner}/{repo}/git/commits/{sha}` with its changed files. */
@Serializable
data class GitCommitWithFiles(
    val sha: String,
    val parents: List<CommitParent> = emptyList(),
    val files: List<CommitAffectedFile>? = null,
)

/** One entry of `GET /repos/{owner}/{repo}/commits/{ref}/status`. */
@Serializable
data class CommitStatus(
    val context: String? = null,
    val status: String? = null,
    val description: String? = null,
    @SerialName("target_url") val targetUrl: String? = null,
)

@Serializable
data class CombinedCommitStatus(
    val state: String? = null,
    val statuses: List<CommitStatus>? = null,
)
