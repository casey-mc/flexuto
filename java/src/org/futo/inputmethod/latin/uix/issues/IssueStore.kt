package org.futo.inputmethod.latin.uix.issues

import android.content.Context
import android.os.Build
import android.util.Log
import org.futo.inputmethod.latin.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class IssueReport(
    val id: Long,
    val createdAt: Long,
    val description: String,
    /** Editor state at capture time: app, field, text around the cursor, IME debug info */
    val context: String,
    val settings: String,
    val entries: List<IssueRecorder.Entry>,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("createdAt", createdAt)
        put("description", description)
        put("context", context)
        put("settings", settings)
        put("entries", JSONArray().apply {
            entries.forEach { e ->
                put(JSONObject().apply {
                    put("t", e.time)
                    put("k", e.kind)
                    put("d", e.detail)
                    e.text?.let { put("x", it) }
                })
            }
        })
    }

    companion object {
        fun fromJson(o: JSONObject): IssueReport {
            val arr = o.optJSONArray("entries") ?: JSONArray()
            return IssueReport(
                id = o.getLong("id"),
                createdAt = o.getLong("createdAt"),
                description = o.optString("description"),
                context = o.optString("context"),
                settings = o.optString("settings"),
                entries = List(arr.length()) { i ->
                    val e = arr.getJSONObject(i)
                    IssueRecorder.Entry(
                        e.getLong("t"), e.getString("k"), e.getString("d"),
                        if(e.has("x")) e.getString("x") else null
                    )
                }
            )
        }
    }
}

/** Saved issue reports, kept in a JSON file in the app's private storage. */
object IssueStore {
    private const val TAG = "IssueStore"
    private const val FILE_NAME = "issue_reports.json"

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    fun load(context: Context): List<IssueReport> = try {
        val f = file(context)
        if(!f.exists()) emptyList() else {
            val arr = JSONArray(f.readText())
            List(arr.length()) { IssueReport.fromJson(arr.getJSONObject(it)) }
        }
    } catch(e: Exception) {
        Log.e(TAG, "Failed to load issue reports", e)
        emptyList()
    }

    private fun save(context: Context, reports: List<IssueReport>) {
        val arr = JSONArray()
        reports.forEach { arr.put(it.toJson()) }
        file(context).writeText(arr.toString())
    }

    fun add(context: Context, report: IssueReport): List<IssueReport> {
        val reports = load(context) + report
        save(context, reports)
        return reports
    }

    fun remove(context: Context, id: Long): List<IssueReport> {
        val reports = load(context).filter { it.id != id }
        save(context, reports)
        return reports
    }

    fun clear(context: Context) {
        file(context).delete()
    }

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private fun formatEntries(report: IssueReport): String = buildString {
        report.entries.forEach { e ->
            val dt = (e.time - report.createdAt) / 1000.0
            append(String.format(Locale.US, "%7.2fs  %-8s %s", dt, e.kind, e.detail))
            e.text?.let { append("\n                   text: \"").append(it).append('"') }
            append('\n')
        }
    }

    /** Formats reports as Markdown, meant to be pasted into a conversation for debugging. */
    fun export(reports: List<IssueReport>): String = buildString {
        appendLine("# Keyboard issue reports (${reports.size})")
        appendLine()
        appendLine("Build: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.FLAVOR}${if(BuildConfig.DEBUG) ", debug" else ""})")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine()
        appendLine("Timeline notation: times are relative to when the issue was captured. `text` is the")
        appendLine("editor text before the cursor after that step; `[...]` is the composing word and `|` the cursor.")

        var previousSettings: String? = null
        reports.forEachIndexed { i, report ->
            appendLine()
            appendLine("---")
            appendLine()
            appendLine("## Issue ${i + 1}: ${report.description.lineSequence().firstOrNull()?.take(80) ?: ""}")
            appendLine()
            appendLine("Captured: ${timeFormat.format(Date(report.createdAt))}")
            appendLine()
            appendLine("**Description:**")
            appendLine()
            appendLine(report.description.ifBlank { "(no description)" })
            appendLine()
            appendLine("**Context at capture:**")
            appendLine("```")
            appendLine(report.context.trimEnd())
            appendLine("```")
            appendLine()
            appendLine("**Timeline (${report.entries.size} events, oldest first):**")
            appendLine("```")
            append(formatEntries(report))
            appendLine("```")
            appendLine()
            if(report.settings == previousSettings) {
                appendLine("**Settings:** same as previous issue")
            } else {
                appendLine("**Settings:**")
                appendLine("```")
                appendLine(report.settings.trimEnd())
                appendLine("```")
            }
            previousSettings = report.settings
        }
    }
}
