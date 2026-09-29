package org.futo.inputmethod.latin.uix.issues

import org.futo.inputmethod.latin.SuggestedWords
import org.futo.inputmethod.latin.SuggestedWords.SuggestedWordInfo

/**
 * Keeps a short in-memory timeline of what the keyboard did (keys, commits, autocorrections,
 * swipes, suggestion strip contents...) so that it can be attached to an issue report.
 *
 * Nothing here is written to disk. The timeline only leaves memory when the user explicitly
 * captures an issue with the Report Issue action. Password fields are never recorded.
 */
object IssueRecorder {
    /** Entries older than this are dropped */
    const val MAX_AGE_MS = 90_000L

    /** Hard cap on the number of entries, whatever their age */
    const val MAX_ENTRIES = 600

    private const val TEXT_SNAPSHOT_LENGTH = 48

    class Entry(
        val time: Long,
        val kind: String,
        val detail: String,
        var text: String? = null
    )

    private val entries = ArrayDeque<Entry>()

    /** Set while an issue is being written, so that typing the description is not recorded */
    @Volatile
    var paused = false

    /** Set while the current field is a password field */
    @Volatile
    private var sensitiveField = false

    private var lastStrip: String? = null
    private var lastCorrectionRow: String? = null

    private val isRecording get() = !paused && !sensitiveField

    @JvmStatic
    fun setSensitiveField(sensitive: Boolean) {
        sensitiveField = sensitive
    }

    @JvmStatic
    fun log(kind: String, detail: String) {
        if(!isRecording) return
        val now = System.currentTimeMillis()
        synchronized(entries) {
            entries.addLast(Entry(now, kind, detail))
            while(entries.size > MAX_ENTRIES
                || (entries.isNotEmpty() && now - entries.first().time > MAX_AGE_MS)) {
                entries.removeFirst()
            }
        }
    }

    /** Attaches a snapshot of the text before the cursor to the most recent entry. */
    @JvmStatic
    fun attachText(text: String?) {
        if(!isRecording || text == null) return
        synchronized(entries) {
            val last = entries.lastOrNull() ?: return
            if(last.text == null) last.text = text.takeLast(TEXT_SNAPSHOT_LENGTH)
        }
    }

    /** Logs the suggestion strip, skipping it if it did not change since the last time. */
    @JvmStatic
    fun logSuggestionStrip(words: SuggestedWords?) {
        if(!isRecording) return
        val description = describeSuggestions(words, 4)
        if(description == lastStrip) return
        lastStrip = description
        log("strip", description)
    }

    @JvmStatic
    fun logCorrectionRow(candidates: List<String>?, index: Int) {
        if(!isRecording) return
        val description = candidates?.mapIndexed { i, c ->
            if(i == index) "[$c]" else c
        }?.joinToString(" ") ?: "(hidden)"
        if(description == lastCorrectionRow) return
        lastCorrectionRow = description
        log("row", description)
    }

    fun snapshot(): List<Entry> = synchronized(entries) {
        val now = System.currentTimeMillis()
        entries.filter { now - it.time <= MAX_AGE_MS }.map { Entry(it.time, it.kind, it.detail, it.text) }
    }

    fun clear() = synchronized(entries) {
        entries.clear()
        lastStrip = null
        lastCorrectionRow = null
    }

    @JvmStatic
    fun kindName(info: SuggestedWordInfo): String = when(info.kind) {
        SuggestedWordInfo.KIND_TYPED -> "typed"
        SuggestedWordInfo.KIND_CORRECTION -> "corr"
        SuggestedWordInfo.KIND_COMPLETION -> "compl"
        SuggestedWordInfo.KIND_WHITELIST -> "whitelist"
        SuggestedWordInfo.KIND_BLACKLIST -> "blacklist"
        SuggestedWordInfo.KIND_HARDCODED -> "hardcoded"
        SuggestedWordInfo.KIND_APP_DEFINED -> "app"
        SuggestedWordInfo.KIND_SHORTCUT -> "shortcut"
        SuggestedWordInfo.KIND_PREDICTION -> "pred"
        SuggestedWordInfo.KIND_RESUMED -> "resumed"
        SuggestedWordInfo.KIND_OOV_CORRECTION -> "oov"
        SuggestedWordInfo.KIND_EMOJI_SUGGESTION -> "emoji"
        SuggestedWordInfo.KIND_UNDO -> "undo"
        else -> "kind${info.kind}"
    }

    @JvmStatic
    fun describeSuggestion(info: SuggestedWordInfo): String = buildString {
        append('"').append(info.mWord).append('"')
        append(" (").append(kindName(info))
        if(info.mScore != 0) append(' ').append(info.mScore)
        if(info.isExactMatch) append(" exact")
        if(info.isAprapreateForAutoCorrection) append(" ac-ok")
        if(info.mOriginatesFromTransformerLM) append(" LM")
        info.mSourceDict?.let { append(' ').append(it.mDictType) }
        append(')')
    }

    @JvmStatic
    fun describeSuggestions(words: SuggestedWords?, limit: Int): String {
        if(words == null || words.isEmpty) return "(empty)"
        return buildString {
            if(words.mWillAutoCorrect) append("[will autocorrect] ")
            if(words.isPrediction) append("[prediction] ")
            words.mTypedWordInfo?.let {
                append("typed=\"").append(it.mWord).append('"')
                append(if(words.mTypedWordValid) " (valid)" else " (not in dict)")
                append(" | ")
            }
            append(
                words.mSuggestedWordInfoList.take(limit).joinToString(" · ") { describeSuggestion(it) }
            )
        }
    }
}
