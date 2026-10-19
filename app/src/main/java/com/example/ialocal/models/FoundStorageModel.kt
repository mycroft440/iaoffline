package com.example.ialocal.models

/** Where one GGUF file found in storage is in the search. */
enum class FoundModelStatus {
    /** Found; waits for the files before it. */
    WAITING,
    CHECKING,
    /** Being copied into the app. */
    IMPORTING,
    IMPORTED,
    ALREADY_INSTALLED,
    /** A quick search skips files an earlier search already handled. */
    SEEN_BEFORE,
    INVALID,
    FAILED,
    /** The search was cancelled before this file was imported. */
    CANCELLED,
    ;

    val isPending: Boolean get() = this == WAITING || this == CHECKING || this == IMPORTING
}

/** A GGUF file the storage search found, shown before and while it is imported. */
data class FoundStorageModel(
    val path: String,
    val fileName: String,
    /** The catalog name of the AI, or the file name without its extension. */
    val name: String,
    val sizeBytes: Long,
    val status: FoundModelStatus = FoundModelStatus.WAITING,
    /** Bytes already copied into the app while [status] is [FoundModelStatus.IMPORTING]. */
    val copiedBytes: Long = 0L,
    /** Why the file was not imported, when there is more to say than its status. */
    val detail: String? = null,
) {
    val copyFraction: Float?
        get() = if (status == FoundModelStatus.IMPORTING && sizeBytes > 0) {
            (copiedBytes.toFloat() / sizeBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            null
        }
}

internal fun List<FoundStorageModel>.update(
    index: Int,
    transform: (FoundStorageModel) -> FoundStorageModel,
): List<FoundStorageModel> = if (index in indices) {
    mapIndexed { i, model -> if (i == index) transform(model) else model }
} else {
    this
}

/** Marks every file not yet handled as cancelled, keeping what already happened to the others. */
internal fun List<FoundStorageModel>.cancelPending(): List<FoundStorageModel> = map { model ->
    if (model.status.isPending) model.copy(status = FoundModelStatus.CANCELLED, copiedBytes = 0L) else model
}
