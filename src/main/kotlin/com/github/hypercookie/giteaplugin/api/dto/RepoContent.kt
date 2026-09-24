package com.github.hypercookie.giteaplugin.api.dto

import kotlinx.serialization.Serializable

/** `GET /repos/{owner}/{repo}/contents/{filepath}?ref=...` — only the fields
 * needed to reconstruct a file's text at a given ref for the diff viewer. */
@Serializable
data class RepoContent(
    val content: String? = null,
    val encoding: String? = null,
    val type: String,
)
