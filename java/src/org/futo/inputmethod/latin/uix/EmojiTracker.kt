package org.futo.inputmethod.latin.uix

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey


val lastUsedEmoji = stringPreferencesKey("last_used_emoji")
const val EmojiLimit = 32

/**
 * Personal emoji bigrams: "which emoji did I type after which". Serialized as
 * prev FIELD next FIELD count, records separated by RECORD. Keys are skin-tone-stripped.
 */
val emojiBigrams = stringPreferencesKey("emoji_bigrams")
const val EmojiBigramLimit = 512
private const val BigramRecordSep = ''
private const val BigramFieldSep = ''

/** Two emoji typed further apart than this are not considered a pair. */
private const val EmojiBigramWindowMs = 5L * 60L * 1000L

object EmojiTracker {
    @Volatile private var lastEmoji: String? = null
    @Volatile private var lastEmojiTime = 0L

    suspend fun Context.useEmoji(emoji: String) {
        if(isDeviceLocked) return

        val now = System.currentTimeMillis()
        val prev = lastEmoji
        val prevTime = lastEmojiTime
        lastEmoji = emoji
        lastEmojiTime = now

        val prevKey = prev?.let { EmojiRecommender.normalize(it) }
        val curKey = EmojiRecommender.normalize(emoji)
        val recordPair = prevKey != null && prevKey != curKey && now - prevTime < EmojiBigramWindowMs

        dataStore.edit {
            val combined = emoji + "<|>" + (it[lastUsedEmoji] ?: "")
            it[lastUsedEmoji] = combined.split("<|>").distinct().take(EmojiLimit).joinToString("<|>")

            if(recordPair) {
                val counts = parseBigrams(it[emojiBigrams] ?: "")
                val key = prevKey + BigramFieldSep + curKey
                counts[key] = (counts[key] ?: 0) + 1
                it[emojiBigrams] = serializeBigrams(counts)
            }
        }
    }

    suspend fun Context.getRecentEmojis(): List<String> {
        if(isDeviceLocked) return listOf()

        return getSetting(lastUsedEmoji, "")
            .split("<|>")
            .filter { it.isNotBlank() }
            .distinct()
    }

    /** Emoji the user has typed right after [emoji] before, most frequent first. */
    suspend fun Context.getEmojiFollowers(emoji: String): List<String> {
        if(isDeviceLocked) return listOf()

        val prefix = EmojiRecommender.normalize(emoji) + BigramFieldSep
        return parseBigrams(getSetting(emojiBigrams, ""))
            .filterKeys { it.startsWith(prefix) }
            .entries
            .sortedByDescending { it.value }
            .map { it.key.substring(prefix.length) }
    }

    suspend fun Context.resetRecentEmojis() {
        if(isDeviceLocked) return

        setSetting(lastUsedEmoji, "")
        setSetting(emojiBigrams, "")
    }

    private fun parseBigrams(serialized: String): HashMap<String, Int> {
        val result = HashMap<String, Int>()
        if(serialized.isEmpty()) return result
        serialized.split(BigramRecordSep).forEach { record ->
            val idx = record.lastIndexOf(BigramFieldSep)
            if(idx <= 0) return@forEach
            val count = record.substring(idx + 1).toIntOrNull() ?: return@forEach
            result[record.substring(0, idx)] = count
        }
        return result
    }

    private fun serializeBigrams(counts: Map<String, Int>): String =
        counts.entries
            .sortedByDescending { it.value }
            .take(EmojiBigramLimit)
            .joinToString(BigramRecordSep.toString()) { it.key + BigramFieldSep + it.value }
}
