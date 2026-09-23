package com.example.ialocal.models

enum class DownloadEntryStatus {
    /** Waiting for the current download to finish. */
    QUEUED,
    /** Being downloaded, verified or installed right now. */
    ACTIVE,
    /** Stopped by the user or Android; resumes from the partial file on Continuar. */
    PAUSED,
    /** Failed; the partial file is kept so Tentar novamente can resume it. */
    FAILED,
}

data class DownloadEntry(
    val catalogId: String,
    val status: DownloadEntryStatus,
    val message: String? = null,
)

/**
 * Ordered list of catalog downloads the user asked for. Only one entry is [DownloadEntryStatus.ACTIVE]
 * at a time; when it finishes, the first [DownloadEntryStatus.QUEUED] entry is next. Immutable so it
 * can be published through a StateFlow and persisted as a whole.
 */
data class DownloadQueue(val entries: List<DownloadEntry> = emptyList()) {
    val active: DownloadEntry? get() = entries.firstOrNull { it.status == DownloadEntryStatus.ACTIVE }
    val nextQueued: DownloadEntry? get() = entries.firstOrNull { it.status == DownloadEntryStatus.QUEUED }

    fun entry(catalogId: String): DownloadEntry? = entries.firstOrNull { it.catalogId == catalogId }

    /** Adds a download at the end, or re-queues a paused/failed one in place. */
    fun enqueue(catalogId: String): DownloadQueue {
        val existing = entry(catalogId)
        return when (existing?.status) {
            null -> copy(entries = entries + DownloadEntry(catalogId, DownloadEntryStatus.QUEUED))
            DownloadEntryStatus.PAUSED, DownloadEntryStatus.FAILED -> resume(catalogId)
            DownloadEntryStatus.QUEUED, DownloadEntryStatus.ACTIVE -> this
        }
    }

    /** Puts a paused or failed download back in line, ahead of the other waiting ones. */
    fun resume(catalogId: String): DownloadQueue {
        val existing = entry(catalogId) ?: return enqueue(catalogId)
        if (existing.status == DownloadEntryStatus.QUEUED || existing.status == DownloadEntryStatus.ACTIVE) return this
        val others = entries.filterNot { it.catalogId == catalogId }
        val insertAt = others.indexOfFirst { it.status != DownloadEntryStatus.ACTIVE }.let { if (it < 0) others.size else it }
        return copy(
            entries = others.toMutableList().apply {
                add(insertAt, DownloadEntry(catalogId, DownloadEntryStatus.QUEUED))
            },
        )
    }

    fun markActive(catalogId: String): DownloadQueue {
        val base = if (entry(catalogId) == null) enqueue(catalogId) else this
        return base.update(catalogId) { it.copy(status = DownloadEntryStatus.ACTIVE, message = null) }
    }

    fun markPaused(catalogId: String, message: String? = null): DownloadQueue =
        update(catalogId) { it.copy(status = DownloadEntryStatus.PAUSED, message = message) }

    fun markFailed(catalogId: String, message: String?): DownloadQueue =
        update(catalogId) { it.copy(status = DownloadEntryStatus.FAILED, message = message) }

    fun remove(catalogId: String): DownloadQueue = copy(entries = entries.filterNot { it.catalogId == catalogId })

    /** After the process died mid-download, the interrupted entry goes back to the front of the line. */
    fun recoverInterrupted(): DownloadQueue {
        val interrupted = active ?: return this
        return copy(
            entries = listOf(interrupted.copy(status = DownloadEntryStatus.QUEUED, message = null)) +
                entries.filterNot { it.catalogId == interrupted.catalogId },
        )
    }

    private fun update(catalogId: String, change: (DownloadEntry) -> DownloadEntry): DownloadQueue =
        copy(entries = entries.map { if (it.catalogId == catalogId) change(it) else it })

    /** One entry per line: `STATUS<TAB>catalogId<TAB>message`. */
    fun encode(): String = entries.joinToString("\n") { entry ->
        listOf(entry.status.name, entry.catalogId, entry.message.orEmpty().replace(Regex("[\\t\\n\\r]+"), " "))
            .joinToString("\t")
    }

    companion object {
        fun decode(raw: String?): DownloadQueue = DownloadQueue(
            raw.orEmpty().lineSequence().mapNotNull { line ->
                val parts = line.split('\t')
                val status = parts.getOrNull(0)?.let { name -> DownloadEntryStatus.entries.firstOrNull { it.name == name } }
                val id = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
                if (status == null || id == null) null
                else DownloadEntry(id, status, parts.getOrNull(2)?.takeIf { it.isNotBlank() })
            }.distinctBy { it.catalogId }.toList(),
        )
    }
}
