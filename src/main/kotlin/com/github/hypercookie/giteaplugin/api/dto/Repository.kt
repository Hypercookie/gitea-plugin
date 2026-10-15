package com.github.hypercookie.giteaplugin.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `GET /repos/{owner}/{repo}` — only the fields the plugin reads. */
@Serializable
data class GiteaRepository(
    @SerialName("default_branch") val defaultBranch: String? = null,
)
