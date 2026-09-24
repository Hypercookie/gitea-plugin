package com.github.hypercookie.giteaplugin.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GiteaUser(
    val id: Long,
    val login: String,
    @SerialName("full_name") val fullName: String? = null,
    val email: String? = null,
    @SerialName("is_admin") val isAdmin: Boolean = false,
)
