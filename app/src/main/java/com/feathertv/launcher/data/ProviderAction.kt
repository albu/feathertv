package com.feathertv.launcher.data

/**
 * Clean action item for an installed streaming app.
 */
data class ProviderAction(
    val packageName: String,
    val title: String,
    val tagText: String,
    val isPrimary: Boolean
)
