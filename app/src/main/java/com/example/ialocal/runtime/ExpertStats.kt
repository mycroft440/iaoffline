package com.example.ialocal.runtime

import android.content.Context
import java.io.File

/**
 * Files where the AI process counts, per model, the experts each layer of a mixture of experts read
 * from storage picks while writing (step 23 b of scripts/prepare_llama_android.sh). When the model
 * loads again, the most used experts are read into memory in the background. They live in the cache
 * directory: if Android clears it, the counting starts again.
 */
object ExpertStats {
    private const val DIR = "expert-stats"

    fun file(context: Context, modelId: String): File = File(File(context.cacheDir, DIR), "$modelId.bin")

    fun forget(context: Context, modelId: String) {
        val file = file(context, modelId)
        file.delete()
        File(file.path + ".partial").delete()
    }
}
