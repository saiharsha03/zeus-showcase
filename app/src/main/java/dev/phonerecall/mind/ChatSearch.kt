package dev.phonerecall.mind

/** search_chats on the phone: plain word match over his past turns with Athena, best and newest first. */
object ChatSearch {
    /** One turn: when (epoch ms), who said it ("The owner" or "Athena"), the words. */
    data class Turn(val at: Long, val who: String, val text: String)

    fun words(query: String): List<String> = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }.distinct()

    fun rank(turns: List<Turn>, query: String, k: Int = 8): List<Turn> {
        val w = words(query)
        if (w.isEmpty()) return emptyList()
        return turns.map { t -> t to w.count { t.text.lowercase().contains(it) } }.filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<Turn, Int>> { it.second }.thenByDescending { it.first.at }).take(k).map { it.first }
    }
}
