package com.example.ialocal.ui.models

import com.example.ialocal.models.ModelDownloadPhase

/** Allows concise membership checks when a download state is optional in Compose. */
operator fun Set<ModelDownloadPhase>.contains(phase: ModelDownloadPhase?): Boolean =
    phase != null && any { it == phase }
