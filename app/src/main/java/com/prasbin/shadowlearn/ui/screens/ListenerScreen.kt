package com.prasbin.shadowlearn.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.prasbin.shadowlearn.data.db.ListenerSegment
import com.prasbin.shadowlearn.ui.components.SectionCard
import com.prasbin.shadowlearn.ui.listener.ListenerUiKind
import com.prasbin.shadowlearn.ui.listener.ListenerUiState
import com.prasbin.shadowlearn.ui.listener.ListenerViewModel
import com.prasbin.shadowlearn.navigation.Routes

/**
 * Phase 7 Listener Mode — lecture recording + transcript-ready segments —
 * with the Phase 10 on-device transcription seam.
 *
 * SYSTEM identity like Search/Quiz. Honest by design: segments show
 * "Transcript pending." until a TRANSCRIBE pass runs; the engine writes
 * verbatim text (READY) or an honest reason (FAILED) — never invented
 * speech. Transcription is explicit per session, never background work.
 */
@Composable
fun ListenerScreen(
    vm: ListenerViewModel = viewModel(factory = ListenerViewModel.factory(LocalContext.current)),
    onNavigate: (String) -> Unit = {}
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            vm.onPermissionGranted()
        } else {
            val permanently = try {
                val activity = context as? androidx.activity.ComponentActivity
                activity?.let {
                    !androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(
                        it, android.Manifest.permission.RECORD_AUDIO
                    )
                } ?: false
            } catch (_: Exception) {
                false
            }
            vm.onPermissionDenied(permanently)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                "LISTEN",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Text("Listener Mode", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Record lectures into transcript-ready sessions",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item { ScopeCard(s) }

        when (s.kind) {
            ListenerUiKind.LOADING -> item {
                SectionCard("Status") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Loading listener state…")
                    }
                }
            }
            ListenerUiKind.NO_SEMESTER -> item {
                SectionCard("No semester selected") {
                    Text("Select an academic year and semester in Settings to attach recordings to.")
                    TextButton(
                        onClick = { onNavigate(Routes.SETTINGS) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("OPEN SETTINGS") }
                }
            }
            ListenerUiKind.IDLE -> {
                item { AboutCard(sessionCount = s.sessionCount) }
                item {
                    Button(onClick = { vm.onStartPressed() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Text("START RECORDING")
                    }
                }
            }
            ListenerUiKind.REQUESTING_PERMISSION -> {
                item { PermissionCard(s) }
                item {
                    Button(
                        onClick = { permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("GRANT MICROPHONE ACCESS")
                    }
                }
                if (s.permissionPermanentlyDenied) {
                    item {
                        OutlinedButton(
                            onClick = {
                                val intent = Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null)
                                )
                                context.startActivity(intent)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("OPEN APP SETTINGS")
                        }
                    }
                }
                item {
                    TextButton(onClick = { vm.cancelPermissionRequest() }, modifier = Modifier.fillMaxWidth()) {
                        Text("NOT NOW")
                    }
                }
            }
            ListenerUiKind.RECORDING -> {
                item { RecordingCard(s, paused = false) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = { vm.pause() }, modifier = Modifier.weight(1f).height(48.dp)) {
                            Text("PAUSE")
                        }
                        Button(onClick = { vm.stop() }, modifier = Modifier.weight(1f).height(48.dp)) {
                            Text("STOP")
                        }
                    }
                }
            }
            ListenerUiKind.PAUSED -> {
                item { RecordingCard(s, paused = true) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = { vm.resume() }, modifier = Modifier.weight(1f).height(48.dp)) {
                            Text("RESUME")
                        }
                        Button(onClick = { vm.stop() }, modifier = Modifier.weight(1f).height(48.dp)) {
                            Text("FINISH")
                        }
                    }
                }
            }
            ListenerUiKind.SEGMENTS -> {
                if (s.error != null) {
                    item {
                        SectionCard("Recovered session") {
                            Text(s.error!!, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                item { SessionSummaryCard(s) }
                item {
                    s.understanding?.let { UnderstandingCard(
                        understanding = it,
                        onOpenWeek = { weekId -> onNavigate(Routes.hierarchyWeek(weekId)) },
                        onPractice = { fileId -> onNavigate(Routes.practiceQuiz(fileId)) }
                    ) }
                }
                item { TranscriptionCard(s, onTranscribe = { vm.transcribeCurrentSession() }) }
                if (s.segments.any { it.transcriptStatus == ListenerSegment.STATUS_READY }) {
                    item {
                        Button(
                            onClick = { vm.sendReadyToCards(); onNavigate(Routes.FLASHCARDS) },
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) { Text("SEND READY TO CARDS ›") }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "READY transcripts feed flashcard review in the Cards tab.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                itemsIndexed(s.segments, key = { _, seg -> seg.id }) { i, seg ->
                    SegmentRow(i, seg) { vm.selectSegment(seg) }
                }
                item {
                    Button(onClick = { vm.newRecording() }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Text("NEW RECORDING")
                    }
                }
            }
            ListenerUiKind.ERROR -> {
                item {
                    SectionCard("Recording error") {
                        Text(
                            s.error ?: "Recording failed.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (s.segments.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "${s.segments.size} segment(s) were saved before the failure " +
                                    "and are kept below — nothing was deleted.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                if (s.segments.isNotEmpty()) {
                    itemsIndexed(s.segments, key = { _, seg -> seg.id }) { i, seg ->
                        SegmentRow(i, seg) { vm.selectSegment(seg) }
                    }
                }
                item {
                    Button(onClick = { vm.newRecording() }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Text("BACK TO LISTENER")
                    }
                }
            }
        }
    }

    s.selectedSegment?.let { seg ->
        AlertDialog(
            onDismissRequest = { vm.selectSegment(null) },
            title = { Text("SEGMENT ${seg.position + 1}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MonoText("TIME  ${formatRange(seg.startedAtMs, seg.durationMs)}")
                    MonoText("STATUS  ${seg.transcriptStatus.uppercase()}")
                    Text(seg.transcript, style = MaterialTheme.typography.bodyMedium)
                    if (seg.transcriptStatus == ListenerSegment.STATUS_READY) {
                        Text(
                            "Offline transcript (verbatim engine output) — this text can feed flashcard review.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            "No transcript yet. Run TRANSCRIBE on the session; " +
                                "an unavailable engine reports the reason here instead of inventing text.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.selectSegment(null) }) { Text("CLOSE") }
            }
        )
    }
}

@Composable
private fun ScopeCard(s: ListenerUiState) {
    SectionCard("Scope · current semester") {
        val scope = listOfNotNull(s.yearName, s.semesterName).joinToString(" · ").ifEmpty { "None selected" }
        MonoText(scope.uppercase())
    }
}

@Composable
private fun AboutCard(sessionCount: Int) {
    SectionCard("How it works") {
        Text(
            "Listener Mode records the lecture microphone into one audio file " +
                "per session and cuts transcript-ready segments at every " +
                "pause/resume boundary. Transcription runs only when you tap " +
                "TRANSCRIBE on a finished session — the on-device engine " +
                "writes verbatim text, or the segment honestly reports why " +
                "it could not be transcribed.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(8.dp))
        MonoText("SESSIONS THIS SEMESTER: $sessionCount")
        Spacer(Modifier.height(4.dp))
        Text(
            "Recording needs microphone access and runs as a visible " +
                "foreground service — never silently in the background.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PermissionCard(s: ListenerUiState) {
    SectionCard("Microphone permission") {
        Text(
            "SHADOW LEARN records lecture audio only while you keep a " +
                "session open, and stores it in the app's private files. " +
                "Grant microphone access to start recording.",
            style = MaterialTheme.typography.bodyMedium
        )
        if (s.permissionDenied) {
            Spacer(Modifier.height(8.dp))
            Text(
                if (s.permissionPermanentlyDenied)
                    "Permission was denied permanently — enable the microphone " +
                        "in the app settings, or go back."
                else
                    "Permission was denied — recording cannot start without " +
                        "it. You can try again or go back.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun RecordingCard(s: ListenerUiState, paused: Boolean) {
    val accent = if (paused) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
    SectionCard(if (paused) "Paused" else "Recording") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RecordingDot(active = !paused, color = accent)
            Spacer(Modifier.width(10.dp))
            Text(
                if (paused) "Capture suspended — resume or finish." else "Capturing lecture audio…",
                style = MaterialTheme.typography.bodyLarge
            )
        }
        Spacer(Modifier.height(8.dp))
        MonoText("ELAPSED  ${formatMs(s.elapsedMs)}")
        MonoText("SEGMENTS  ${s.segmentCount}")
        Spacer(Modifier.height(8.dp))
        val level = (s.amplitude / 32767f).coerceIn(0f, 1f)
        LinearProgressIndicator(
            progress = { level },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(4.dp))
        MonoText("AUDIO LEVEL  ${s.amplitude}/32767")
        if (!paused && s.amplitude == 0 && s.elapsedMs > 2000) {
            Text(
                "Level reads 0 on this device/emulator — capture still " +
                    "runs; the file is the source of truth.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RecordingDot(active: Boolean, color: Color) {
    Canvas(modifier = Modifier.size(14.dp)) {
        drawCircle(color = color, radius = size.minDimension / 2)
    }
    if (!active) {
        Spacer(Modifier.width(0.dp)) // static paused dot: no fake pulse animation
    }
}

@Composable
private fun SessionSummaryCard(s: ListenerUiState) {
    SectionCard("Session #${s.reviewSessionId} · ${s.reviewStatus?.uppercase()}") {
        MonoText("SEGMENTS  ${s.segments.size}")
        val ready = s.segments.count { it.transcriptStatus == ListenerSegment.STATUS_READY }
        val pending = s.segments.count { it.transcriptStatus == ListenerSegment.STATUS_PENDING }
        val failed = s.segments.count { it.transcriptStatus == ListenerSegment.STATUS_FAILED }
        MonoText("TRANSCRIPTS  $ready READY · $pending PENDING · $failed FAILED")
        val path = s.reviewAudioPath
        MonoText(if (path != null) "AUDIO  saved" else "AUDIO  missing — file unavailable")
        if (path != null) {
            Text(
                path.substringAfterLast('/'),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * I7 derived session understanding: evidence counts, verbatim key points,
 * and indexed sources the transcript verifiably connects to. No scores,
 * no summaries invented beyond transcript text, no new destinations —
 * every action reuses an existing route.
 */
@Composable
private fun UnderstandingCard(
    understanding: com.prasbin.shadowlearn.data.listener.SessionUnderstanding,
    onOpenWeek: (Long) -> Unit,
    onPractice: (Long) -> Unit
) {
    SectionCard("Listener Intelligence") {
        MonoText(
            "UNDERSTANDING  ${understanding.academicCount} ACADEMIC · " +
                "${understanding.transcriptOnlyCount} TRANSCRIPT · " +
                "${understanding.fillerCount} FILLER · " +
                "${understanding.unknownCount} UNKNOWN"
        )
        if (understanding.readyCount == 0) {
            Spacer(Modifier.height(4.dp))
            Text(
                "No transcribed academic content yet — transcribe the session to enable understanding.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            if (understanding.keyPoints.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "KEY POINTS",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                understanding.keyPoints.forEach { point ->
                    Text(
                        "“${point.text}”",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "SEGMENT ${point.position + 1}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }
            if (understanding.groundedSources.isNotEmpty()) {
                Text(
                    "CONNECTED MATERIAL",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                understanding.groundedSources.forEach { source ->
                    Text(source.fileName, style = MaterialTheme.typography.titleSmall)
                    if (source.terms.isNotEmpty()) {
                        Text(
                            "Shared terms: ${source.terms.joinToString(", ")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    source.weekId?.let { weekId ->
                        Spacer(Modifier.height(4.dp))
                        TextButton(
                            onClick = { onOpenWeek(weekId) },
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) { Text("OPEN SOURCE ›") }
                    }
                }
            }
            understanding.practiceFileId?.let { fileId ->
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = { onPractice(fileId) },
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Text("PRACTICE ›") }
            }
        }
    }
}

@Composable
private fun TranscriptionCard(s: ListenerUiState, onTranscribe: () -> Unit) {
    val pending = s.segments.count { it.transcriptStatus == ListenerSegment.STATUS_PENDING }
    SectionCard("Transcription") {
        Text(
            "On-device speech-to-text runs only here, on tap — never in the " +
                "background. Audio never leaves the device.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(8.dp))
        if (s.transcription == com.prasbin.shadowlearn.ui.listener.TranscriptionUi.TRANSCRIBING) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text("Transcribing segments…")
            }
        } else {
            Button(
                onClick = onTranscribe,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                enabled = pending > 0
            ) {
                Text(if (pending > 0) "TRANSCRIBE ($pending PENDING)" else "TRANSCRIBE")
            }
        }
        s.transcriptionMessage?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SegmentRow(index: Int, seg: ListenerSegment, onTap: () -> Unit) {
    Card(
        onClick = onTap,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp)) {
            MonoText("SEGMENT ${index + 1} · ${seg.transcriptStatus.uppercase()}")
            Spacer(Modifier.height(4.dp))
            Text(
                "${formatRange(seg.startedAtMs, seg.durationMs)} · ${formatDuration(seg.durationMs)}",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                seg.transcript,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MonoText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
}

private fun formatMs(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "open span"
    val seconds = ms / 1000
    return if (seconds < 60) "${seconds}s" else formatMs(ms)
}

private fun formatRange(startMs: Long, durationMs: Long): String =
    "${formatMs(startMs)} → ${if (durationMs <= 0) "…" else formatMs(startMs + durationMs)}"
