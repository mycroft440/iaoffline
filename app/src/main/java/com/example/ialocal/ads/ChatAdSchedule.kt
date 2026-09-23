package com.example.ialocal.ads

/**
 * Decides which user turns in a chat get a banner in the assistant reply. The gap between banners
 * cycles through 2, 4 and 3 user messages, so banners follow user messages 2, 6, 9, 11, 15, 18…
 */
object ChatAdSchedule {
    private val GAPS = intArrayOf(2, 4, 3)

    /** [userMessageNumber] is 1-based within the conversation. */
    fun showsBannerAfter(userMessageNumber: Int): Boolean {
        if (userMessageNumber < 1) return false
        val cycleLength = GAPS.sum()
        var position = (userMessageNumber - 1) % cycleLength + 1
        for (gap in GAPS) {
            if (position == gap) return true
            if (position < gap) return false
            position -= gap
        }
        return false
    }

    /**
     * Returns the ids of assistant messages that answer a scheduled user message. Only the first
     * assistant reply after that user message is marked.
     */
    fun <T> assistantIdsWithBanner(
        messages: List<T>,
        isUser: (T) -> Boolean,
        id: (T) -> String,
    ): Set<String> {
        val result = hashSetOf<String>()
        var userCount = 0
        var pending = false
        messages.forEach { message ->
            if (isUser(message)) {
                userCount += 1
                pending = showsBannerAfter(userCount)
            } else if (pending) {
                result += id(message)
                pending = false
            }
        }
        return result
    }

    /**
     * Splits an assistant answer where the banner goes: before the first code block, otherwise after
     * the first paragraph. When neither exists the whole answer comes first and the banner follows it.
     */
    fun splitForBanner(answer: String): Pair<String, String> {
        val codeStart = answer.indexOf("```")
        if (codeStart >= 0) return answer.substring(0, codeStart).trimEnd() to answer.substring(codeStart)
        val paragraphEnd = answer.indexOf("\n\n")
        if (paragraphEnd >= 0) {
            return answer.substring(0, paragraphEnd).trimEnd() to answer.substring(paragraphEnd).trimStart()
        }
        return answer to ""
    }
}
