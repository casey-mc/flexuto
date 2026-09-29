package org.futo.inputmethod.latin.uix.actions

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.futo.inputmethod.engine.general.GeneralIME
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.settings.Settings
import org.futo.inputmethod.latin.uix.Action
import org.futo.inputmethod.latin.uix.ActionTextEditor
import org.futo.inputmethod.latin.uix.ActionWindow
import org.futo.inputmethod.latin.uix.CloseResult
import org.futo.inputmethod.latin.uix.DialogRequestItem
import org.futo.inputmethod.latin.uix.KeyboardManagerForAction
import org.futo.inputmethod.latin.uix.issues.IssueRecorder
import org.futo.inputmethod.latin.uix.issues.IssueReport
import org.futo.inputmethod.latin.uix.issues.IssueStore
import org.futo.inputmethod.latin.uix.settings.ScrollableList
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun captureIssue(manager: KeyboardManagerForAction): IssueReport {
    val now = System.currentTimeMillis()
    val context = try {
        (manager.getCurrentIME() as? GeneralIME)?.issueContext() ?: "(not in the general IME)"
    } catch(e: Exception) {
        "(failed to read context: $e)"
    }
    val settings = try {
        Settings.getInstance().current?.dump() ?: "(unavailable)"
    } catch(e: Exception) {
        "(failed to read settings: $e)"
    }

    return IssueReport(
        id = now,
        createdAt = now,
        description = "",
        context = context,
        settings = settings,
        entries = IssueRecorder.snapshot()
    )
}

private fun copyIssues(context: Context, reports: List<IssueReport>) {
    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboardManager.setPrimaryClip(ClipData.newPlainText("Keyboard issue reports", IssueStore.export(reports)))
    Toast.makeText(context, "Copied ${reports.size} issue(s)", Toast.LENGTH_SHORT).show()
}

private fun shareIssues(context: Context, reports: List<IssueReport>) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Keyboard issue reports (${reports.size})")
        putExtra(Intent.EXTRA_TEXT, IssueStore.export(reports))
    }
    val chooser = Intent.createChooser(intent, "Share issue reports").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(chooser)
    } catch(e: Exception) {
        Toast.makeText(context, "Could not share: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
    }
}

private val listTimeFormat = SimpleDateFormat("MMM d HH:mm", Locale.US)

val ReportIssueAction = Action(
    icon = R.drawable.report_issue,
    name = R.string.action_report_issue_title,
    simplePressImpl = null,
    canShowKeyboard = true,
    windowImpl = { manager, _ ->
        val context = manager.getContext()

        // Take the snapshot right away, before anything typed in this window can be recorded
        val draft = mutableStateOf<IssueReport?>(captureIssue(manager))
        IssueRecorder.paused = true

        val description = mutableStateOf("")
        val saved = mutableStateOf(IssueStore.load(context))

        object : ActionWindow() {
            @Composable
            override fun windowName(): String = stringResource(R.string.action_report_issue_title)

            @Composable
            override fun WindowContents(keyboardShown: Boolean) {
                DisposableEffect(Unit) {
                    onDispose { IssueRecorder.paused = false }
                }

                ScrollableList(modifier = Modifier.padding(8.dp, 0.dp)) {
                    draft.value?.let { report ->
                        DraftEditor(report, description,
                            onSave = {
                                try {
                                    saved.value = IssueStore.add(
                                        context,
                                        report.copy(description = description.value.trim())
                                    )
                                    Toast.makeText(context, "Issue saved (${saved.value.size} total)", Toast.LENGTH_SHORT).show()
                                    draft.value = null
                                    description.value = ""
                                    manager.closeActionWindow()
                                } catch(e: Exception) {
                                    Toast.makeText(context, "Could not save: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                                }
                            },
                            onDiscard = {
                                draft.value = null
                                description.value = ""
                            }
                        )
                        Spacer(Modifier.height(16.dp))
                    }

                    SavedIssues(
                        saved.value,
                        onCopy = { copyIssues(context, saved.value) },
                        onShare = { shareIssues(context, saved.value) },
                        onClear = {
                            manager.requestDialog(
                                "Delete all ${saved.value.size} saved issue reports?",
                                listOf(
                                    DialogRequestItem("Cancel") { },
                                    DialogRequestItem("Delete") {
                                        IssueStore.clear(context)
                                        saved.value = emptyList()
                                    }
                                ),
                                { }
                            )
                        },
                        onDelete = { saved.value = IssueStore.remove(context, it.id) }
                    )
                }
            }

            override fun close(): CloseResult {
                IssueRecorder.paused = false
                return CloseResult.Default
            }
        }
    },
)

@Composable
private fun DraftEditor(
    report: IssueReport,
    description: androidx.compose.runtime.MutableState<String>,
    onSave: () -> Unit,
    onDiscard: () -> Unit
) {
    val commits = report.entries.count { it.kind == "commit" }
    val seconds = report.entries.firstOrNull()?.let { (report.createdAt - it.time) / 1000 } ?: 0
    Text(
        "Captured ${report.entries.size} events ($commits words) from the last ${seconds}s. " +
                "What went wrong, and what did you expect instead?",
        style = MaterialTheme.typography.bodyMedium
    )
    Spacer(Modifier.height(8.dp))
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().height(96.dp)
    ) {
        Box(Modifier.padding(8.dp), contentAlignment = Alignment.TopStart) {
            ActionTextEditor(
                description,
                multiline = true,
                autocorrect = true,
                placeholder = "e.g. typed \"its\", wanted it left alone but it became \"it's\""
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Row {
        Button(onClick = onSave) { Text("Save issue") }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = onDiscard) { Text("Discard") }
    }
}

@Composable
private fun SavedIssues(
    reports: List<IssueReport>,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onClear: () -> Unit,
    onDelete: (IssueReport) -> Unit
) {
    Text("Saved issues (${reports.size})", style = DebugTitle)
    if(reports.isEmpty()) {
        Text("None yet. Open this action right after the keyboard misbehaves to capture one.", style = DebugLabel)
        return
    }

    Row {
        TextButton(onClick = onCopy) { Text("Copy all") }
        TextButton(onClick = onShare) { Text("Share") }
        TextButton(onClick = onClear) { Text("Delete all") }
    }

    reports.forEachIndexed { i, report ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1.0f)) {
                Text(
                    "${i + 1}. ${report.description.ifBlank { "(no description)" }}",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2
                )
                Text(
                    "${listTimeFormat.format(Date(report.createdAt))} · ${report.entries.size} events",
                    style = DebugLabel
                )
            }
            TextButton(onClick = { onDelete(report) }) { Text("Delete") }
        }
    }
}
