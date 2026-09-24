package com.github.hypercookie.giteaplugin.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class ChangedFile(
    val filename: String,
    val status: String,
    val additions: Int = 0,
    val deletions: Int = 0,
    val changes: Int = 0,
    @kotlinx.serialization.SerialName("previous_filename") val previousFilename: String? = null,
)
