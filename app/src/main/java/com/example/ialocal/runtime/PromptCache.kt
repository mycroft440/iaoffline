package com.example.ialocal.runtime

import android.content.Context
import java.io.File

/**
 * Files where the AI process keeps each model's processed system prompt between loads, so the
 * first answer after opening the app does not process it again (see the native patch in
 * scripts/prepare_llama_android.sh). They live in the cache directory: if Android clears it when
 * storage runs low, the prompt is simply processed again.
 */
object PromptCache {
    private const val DIR = "prompt-cache"

    /** One file per model and reasoning switch, which templates such as Gemma 4's render differently. */
    fun file(context: Context, modelId: String, thinking: Boolean): File =
        File(File(context.cacheDir, DIR), "$modelId-${if (thinking) "thinking" else "direct"}.state")

    fun forget(context: Context, modelId: String) {
        File(context.cacheDir, DIR).listFiles { file -> file.name.startsWith("$modelId-") }?.forEach { it.delete() }
    }
}
