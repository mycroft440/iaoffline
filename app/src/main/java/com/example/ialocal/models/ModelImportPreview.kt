package com.example.ialocal.models

import android.net.Uri

data class ModelImportPreview(
    val uri: Uri,
    val displayName: String,
    val sourceSizeBytes: Long?,
    val metadata: GgufInspector.Metadata,
    val compatibility: DeviceCompatibilityChecker.Result,
) {
    val suggestedName: String
        get() = metadata.name?.trim().takeUnless { it.isNullOrBlank() }
            ?: displayName.substringBeforeLast('.').ifBlank { "Modelo local" }
}
