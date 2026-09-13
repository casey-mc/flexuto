package org.futo.inputmethod.latin.uix

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.EmojiTracker.getEmojiFollowers
import org.futo.inputmethod.latin.uix.EmojiTracker.getRecentEmojis
import org.futo.inputmethod.latin.uix.actions.PersistentEmojiState
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * What the emoji action bar knows about the editor. [composingWord] is the word being typed
 * (empty when not composing); [textBeforeCursor] is only filled when not composing and is used
 * to find the emoji the user just typed.
 */
data class EmojiBarContext(
    val composingWord: String,
    val textBeforeCursor: String,
)

/**
 * Picks the emoji shown in the emoji action bar:
 *  1. matches for the word being typed,
 *  2. emoji related to the emoji just typed (personal bigrams, then the static
 *     neighbor table built by tools/emoji-neighbors/build_neighbors.py),
 *  3. recently used emoji,
 *  4. a default popular set.
 */
object EmojiRecommender {
    private val Defaults = listOf(
        "😂", "❤️", "🤣", "😍", "😊",
        "🙏", "😭", "😘", "👍", "🥰",
        "😅", "🔥", "🎉", "🤔", "😎",
        "👀", "💀", "🙌", "💯", "😢",
        "😉", "👋", "🤗", "😴",
    )

    @Volatile private var neighbors: Map<String, List<String>>? = null
    @Volatile private var canonical: Map<String, String>? = null
    private val loadLock = Any()

    /** Strips skin tone modifiers and variation selectors so a toned thumbs-up and the plain one share a key. */
    fun normalize(emoji: String): String {
        val sb = StringBuilder(emoji.length)
        var i = 0
        while(i < emoji.length) {
            val cp = emoji.codePointAt(i)
            i += Character.charCount(cp)
            if(cp in 0x1F3FB..0x1F3FF || cp == 0xFE0F || cp == 0xFE0E) continue
            sb.appendCodePoint(cp)
        }
        return sb.toString()
    }

    private fun loadTable(context: Context) {
        if(neighbors != null) return
        synchronized(loadLock) {
            if(neighbors != null) return
            try {
                val stream = GZIPInputStream(context.resources.openRawResource(R.raw.emoji_neighbors))
                val text = InputStreamReader(stream, StandardCharsets.UTF_8).use { it.readText() }
                val root = Json.parseToJsonElement(text).jsonObject
                val emojis = root["e"]!!.jsonArray.map { it.jsonPrimitive.content }
                val lists = root["n"]!!.jsonArray

                val table = HashMap<String, List<String>>(emojis.size * 2)
                val canon = HashMap<String, String>(emojis.size * 2)
                emojis.forEachIndexed { idx, emoji ->
                    val key = normalize(emoji)
                    table[key] = lists[idx].jsonArray.map { emojis[it.jsonPrimitive.int] }
                    canon[key] = emoji
                }
                canonical = canon
                neighbors = table
            } catch(e: Exception) {
                e.printStackTrace()
                canonical = emptyMap()
                neighbors = emptyMap()
            }
        }
    }

    suspend fun ensureLoaded(context: Context) {
        if(PersistentEmojiState.emojis.value == null) {
            PersistentEmojiState.loadEmojis(context)
        }
        withContext(Dispatchers.IO) { loadTable(context) }
    }

    /** The gemoji spelling of [emoji] (right variation selectors, no skin tone), or null if unknown. */
    fun canonicalOf(emoji: String): String? = canonical?.get(normalize(emoji))

    fun neighborsOf(emoji: String): List<String> =
        neighbors?.get(normalize(emoji)) ?: emptyList()

    private fun isEmojiCodePoint(cp: Int): Boolean =
        cp == 0x200D || cp == 0xFE0F || cp == 0xFE0E || cp == 0x20E3
                || cp in 0x1F3FB..0x1F3FF
                || cp in 0x1F1E6..0x1F1FF
                || cp in 0xE0020..0xE007F
                || cp >= 0x1F000
                || cp in 0x2190..0x21FF || cp in 0x2300..0x23FF || cp in 0x2460..0x24FF
                || cp in 0x25A0..0x27BF || cp in 0x2900..0x297F || cp in 0x2B00..0x2BFF
                || cp == 0x00A9 || cp == 0x00AE || cp == 0x203C || cp == 0x2049
                || cp == 0x2122 || cp == 0x2139 || cp == 0x3030 || cp == 0x303D
                || cp == 0x3297 || cp == 0x3299
                || (cp in 0x30..0x39) || cp == 0x23 || cp == 0x2A

    /**
     * The emoji immediately before the cursor (trailing whitespace ignored), or null. Prefers
     * the longest known sequence so a ZWJ family sequence is returned rather than its last member.
     */
    fun trailingEmoji(text: CharSequence): String? {
        val table = neighbors ?: return null
        var end = text.length
        while(end > 0 && Character.isWhitespace(text[end - 1])) end--
        if(end == 0) return null

        // Collect the run of emoji-ish code points ending at [end]
        var start = end
        var count = 0
        while(start > 0 && count < 16) {
            val cp = Character.codePointBefore(text, start)
            if(!isEmojiCodePoint(cp)) break
            start -= Character.charCount(cp)
            count++
        }
        if(start == end) return null

        var best: String? = null
        var pos = start
        while(pos < end) {
            val candidate = text.subSequence(pos, end).toString()
            if(table.containsKey(normalize(candidate))) {
                best = candidate
                break
            }
            pos += Character.charCount(Character.codePointAt(text, pos))
        }
        return best
    }

    private fun wordMatches(word: String, locale: Locale, limit: Int): List<String> {
        val query = word.lowercase(locale)
        val results = mutableListOf<Pair<String, Int>>()

        PersistentEmojiState.getShortcut(locale, query)?.let { results.add(it to 100) }

        val translations = PersistentEmojiState.getTranslationForLocale(locale)
            ?: PersistentEmojiState.getTranslationForLocale(Locale.ENGLISH)
            ?: return results.map { it.first }
        val ordered = PersistentEmojiState.emojis.value ?: return results.map { it.first }

        for(item in ordered) {
            if(item.category == "ASCII") continue
            val names = translations.emojiToNames[item.emoji]?.names ?: continue
            var score = 0
            for(name in names) {
                val lower = name.lowercase(locale)
                score = maxOf(score, when {
                    lower == query -> 50
                    lower.startsWith(query) -> 20
                    lower.split(' ', '_', '-').any { it.startsWith(query) } -> 5
                    else -> 0
                })
                if(score >= 50) break
            }
            if(score > 0) results.add(item.emoji to score)
            if(results.size > limit * 6) break
        }

        return results
            .sortedByDescending { it.second }
            .map { it.first }
            .distinct()
            .take(limit)
    }

    suspend fun suggest(
        context: Context,
        barContext: EmojiBarContext?,
        locale: Locale,
        limit: Int
    ): List<String> {
        ensureLoaded(context)

        val picked = LinkedHashMap<String, String>() // normalized -> emoji
        fun add(emoji: String) {
            if(picked.size >= limit) return
            val key = normalize(emoji)
            if(key.isEmpty() || picked.containsKey(key)) return
            val canon = canonicalOf(emoji) ?: return
            val emojiMap = PersistentEmojiState.emojiMap
            if(emojiMap.isNotEmpty() && !emojiMap.containsKey(canon)) return
            picked[key] = canon
        }

        val word = barContext?.composingWord ?: ""
        if(word.length >= 2 && word.any { it.isLetter() }) {
            wordMatches(word, locale, limit).forEach { add(it) }
        }

        if(picked.size < limit && word.isEmpty()) {
            val prev = barContext?.textBeforeCursor?.let { trailingEmoji(it) }
            if(prev != null) {
                val prevKey = normalize(prev)
                picked[prevKey] = prev // reserve so we don't suggest it again
                context.getEmojiFollowers(prev).forEach { add(it) }
                neighborsOf(prev).forEach { add(it) }
                picked.remove(prevKey)
            }
        }

        if(picked.size < limit) context.getRecentEmojis().forEach { add(it) }
        if(picked.size < limit) Defaults.forEach { add(it) }

        return picked.values.map { PersistentEmojiState.transformEmojiToLastSkinTone(it) }
    }
}
