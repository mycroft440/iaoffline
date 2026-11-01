package com.example.ialocal.runtime

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * Files where the AI process keeps each model's processed system prompt between loads, so the
 * first answer after opening the app does not process it again (see the native patch in
 * scripts/prepare_llama_android.sh). They live in the cache directory: if Android clears it when
 * storage runs low, the prompt is simply processed again.
 */
object PromptCache {
    private const val DIR = "prompt-cache"
    /** Profiles' prompts kept per model; switching among them does not process them again. */
    const val FILES_PER_MODEL = 4

    /**
     * One file per model, reasoning switch (templates such as Gemma 4's render it differently) and
     * profile: [stablePrompt] is the system prompt without the part that changes daily. The least
     * recently used files beyond [FILES_PER_MODEL] are deleted.
     */
    fun file(context: Context, modelId: String, thinking: Boolean, stablePrompt: String): File {
        val dir = File(context.cacheDir, DIR)
        val file = File(dir, "$modelId-${if (thinking) "thinking" else "direct"}-${hashOf(stablePrompt)}.state")
        if (file.exists()) file.setLastModified(System.currentTimeMillis())
        val ofModel = filesOf(modelId)
        dir.listFiles { candidate -> ofModel.matches(candidate.name) && candidate.name.endsWith(".state") }
            ?.filter { it != file }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(FILES_PER_MODEL - 1)
            ?.forEach { it.delete() }
        return file
    }

    fun forget(context: Context, modelId: String) {
        val ofModel = filesOf(modelId)
        File(context.cacheDir, DIR).listFiles { file -> ofModel.matches(file.name) }?.forEach { it.delete() }
    }

    /** This model's files (and their ".partial" while being written), not those of a model whose id starts like it. */
    private fun filesOf(modelId: String) = Regex(Regex.escape(modelId) + "-(thinking|direct)(-[0-9a-f]+)?\\.state(\\.partial)?")

    internal fun hashOf(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(6).joinToString("") { "%02x".format(it) }
}
