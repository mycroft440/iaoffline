package com.example.ialocal.agent

/**
 * Rewrites the "harmony" output format of OpenAI's gpt-oss models, as it streams, into the tags the
 * rest of the app understands. The analysis channel becomes a `<think>…</think>` block, the final
 * channel becomes plain answer text and the remaining control tokens (`<|start|>assistant`,
 * `<|return|>`, …) are dropped:
 *
 * `<|channel|>analysis<|message|>R<|end|><|start|>assistant<|channel|>final<|message|>A` → `<think>R</think>A`
 *
 * Output without `<|…|>` tokens passes through unchanged.
 */
class HarmonyNormalizer {
    private val pending = StringBuilder()
    private var inAnalysis = false

    /** What follows a control token until its payload ends, such as a role or a channel name. */
    private enum class Skip { NONE, ROLE, CHANNEL }
    private var skip = Skip.NONE
    private val header = StringBuilder()

    fun push(chunk: String): String {
        pending.append(chunk)
        return drain(finished = false)
    }

    fun finish(): String = drain(finished = true) + if (inAnalysis) {
        inAnalysis = false
        CLOSE
    } else ""

    private fun drain(finished: Boolean): String {
        val out = StringBuilder()
        while (pending.isNotEmpty()) {
            val start = pending.indexOf(TOKEN_START)
            if (skip != Skip.NONE) {
                // A role or channel name runs until the next control token.
                val end = if (start >= 0) start else if (finished) pending.length else heldBack()
                header.append(pending, 0, end)
                pending.delete(0, end)
                if (start < 0) break
                if (skip == Skip.CHANNEL) out.append(openChannel(header.toString().trim()))
                header.setLength(0)
                skip = Skip.NONE
                continue
            }
            if (start < 0) {
                val end = if (finished) pending.length else heldBack()
                out.append(pending, 0, end)
                pending.delete(0, end)
                break
            }
            out.append(pending, 0, start)
            pending.delete(0, start)
            val close = pending.indexOf(TOKEN_END, TOKEN_START.length)
            if (close < 0) {
                if (finished || pending.length > MAX_TOKEN_LENGTH) {
                    // Not a control token after all.
                    out.append(pending)
                    pending.setLength(0)
                }
                break
            }
            val token = pending.substring(0, close + TOKEN_END.length)
            pending.delete(0, token.length)
            when (token) {
                // The role after <|start|> and the format after <|constrain|> are not text either.
                "<|start|>", "<|constrain|>" -> skip = Skip.ROLE
                "<|channel|>" -> skip = Skip.CHANNEL
                "<|end|>", "<|return|>", "<|call|>" -> if (inAnalysis) {
                    inAnalysis = false
                    out.append(CLOSE)
                }
                else -> Unit // <|message|> and other control tokens carry no text.
            }
        }
        return out.toString()
    }

    private fun openChannel(channel: String): String = when {
        channel.startsWith("analysis") && !inAnalysis -> {
            inAnalysis = true
            OPEN
        }
        !channel.startsWith("analysis") && inAnalysis -> {
            inAnalysis = false
            CLOSE
        }
        else -> ""
    }

    /** Length of [pending] that can be released now, keeping a suffix that may start `<|`. */
    private fun heldBack(): Int = if (pending.endsWith("<")) pending.length - 1 else pending.length

    private companion object {
        const val TOKEN_START = "<|"
        const val TOKEN_END = "|>"
        const val MAX_TOKEN_LENGTH = 32
        const val OPEN = "<think>"
        const val CLOSE = "</think>"
    }
}
