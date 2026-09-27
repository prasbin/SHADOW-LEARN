package com.prasbin.shadowlearn.data.listener

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.prasbin.shadowlearn.data.cards.CardGenerator
import com.prasbin.shadowlearn.data.db.ListenerSegment
import com.prasbin.shadowlearn.data.db.ListenerSession
import com.prasbin.shadowlearn.data.db.ReadySegmentRow
import com.prasbin.shadowlearn.data.db.ShadowLearnDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Phase 10 transcription tests over real Room with a fake engine.
 *
 * Proves the seam: READY carries the engine's verbatim text (never
 * invented), FAILED carries an honest reason, pending→ready/failed is
 * guarded and idempotent, audio stays in files (never SQLite), and READY
 * rows feed Phase 8 card generation while PENDING/FAILED rows feed zero
 * cards. No network, no real speech model anywhere in this file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TranscriptionTest {

    private class FakeTranscriber(
        var result: (File) -> TranscriptionResult = { TranscriptionResult.Ready("fake transcript") }
    ) : Transcriber {
        val calls = mutableListOf<File>()
        var throwOn: Exception? = null

        override fun transcribe(audioFile: File, languageTag: String?): TranscriptionResult {
            calls += audioFile
            throwOn?.let { throw it }
            return result(audioFile)
        }
    }

    private lateinit var db: ShadowLearnDatabase
    private lateinit var audioDir: File
    private lateinit var fake: FakeTranscriber
    private lateinit var repo: ListenerTranscriptionRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, ShadowLearnDatabase::class.java)
            .allowMainThreadQueries().build()
        audioDir = File(context.cacheDir, "transcription-test").also { it.mkdirs() }
        fake = FakeTranscriber()
        repo = ListenerTranscriptionRepository(
            dao = db.listenerDao(),
            transcriber = fake,
            dispatcher = Dispatchers.Unconfined
        )
    }

    @After
    fun tearDown() {
        db.close()
        audioDir.deleteRecursively()
    }

    private suspend fun sessionWithAudio(bytes: ByteArray? = ByteArray(32) { it.toByte() }): Long {
        val dao = db.listenerDao()
        val id = dao.insertSession(ListenerSession(semesterId = 1))
        if (bytes != null) {
            val file = File(audioDir, "listener_${id}.m4a")
            file.writeBytes(bytes)
            dao.setAudioPath(id, file.absolutePath)
        }
        return id
    }

    private suspend fun pendingSegment(sessionId: Long, position: Int = 0): Long =
        db.listenerDao().insertSegment(
            ListenerSegment(sessionId = sessionId, position = position, startedAtMs = 0)
        )

    // ---- READY path ----------------------------------------------------------

    @Test
    fun readyResultPersistsVerbatimTranscript() = runBlocking {
        val id = sessionWithAudio()
        pendingSegment(id)
        repo.transcribeSession(id)
        val seg = db.listenerDao().segments(id).single()
        assertEquals(ListenerSegment.STATUS_READY, seg.transcriptStatus)
        assertEquals("fake transcript", seg.transcript)
    }

    @Test
    fun pendingTransitionsToReady() = runBlocking {
        val id = sessionWithAudio()
        pendingSegment(id)
        assertEquals(ListenerSegment.STATUS_PENDING, db.listenerDao().segments(id).single().transcriptStatus)
        repo.transcribeSession(id)
        assertEquals(ListenerSegment.STATUS_READY, db.listenerDao().segments(id).single().transcriptStatus)
    }

    @Test
    fun audioFileIsResolvedFromParentSession() = runBlocking {
        val id = sessionWithAudio()
        pendingSegment(id)
        val expected = db.listenerDao().session(id)!!.audioPath!!
        repo.transcribeSession(id)
        assertEquals(1, fake.calls.size)
        assertEquals(File(expected).absolutePath, fake.calls.single().absolutePath)
    }

    @Test
    fun multiplePendingSegmentsPreservePositionOrder() = runBlocking {
        val id = sessionWithAudio()
        pendingSegment(id, 0)
        pendingSegment(id, 1)
        pendingSegment(id, 2)
        var n = 0
        fake.result = { TranscriptionResult.Ready("verbatim-${++n}") }
        repo.transcribeSession(id)
        val texts = db.listenerDao().segments(id).map { it.transcript }
        assertEquals(listOf("verbatim-1", "verbatim-2", "verbatim-3"), texts)
    }

    // ---- FAILED path ---------------------------------------------------------

    @Test
    fun failedResultPersistsHonestReason() = runBlocking {
        val id = sessionWithAudio()
        pendingSegment(id)
        fake.result = { TranscriptionResult.Failed("Speech recognition for \"es-ES\" is not available.") }
        repo.transcribeSession(id)
        val seg = db.listenerDao().segments(id).single()
        assertEquals(ListenerSegment.STATUS_FAILED, seg.transcriptStatus)
        assertEquals("Speech recognition for \"es-ES\" is not available.", seg.transcript)
    }

    @Test
    fun unavailableProductionTranscriberFailsHonestly() {
        val result = UnavailableTranscriber().transcribe(File(audioDir, "x.m4a"))
        assertTrue(result is TranscriptionResult.Failed)
        val reason = (result as TranscriptionResult.Failed).reason
        assertTrue(reason.contains("No on-device speech engine", ignoreCase = true))
    }

    @Test
    fun unavailableProductionTranscriberReportsUnsupportedLanguage() {
        val result = UnavailableTranscriber().transcribe(File(audioDir, "x.m4a"), "es-ES")
        assertTrue(result is TranscriptionResult.Failed)
        assertTrue((result as TranscriptionResult.Failed).reason.contains("es-ES"))
    }

    @Test
    fun missingSessionReturnsZeroWithoutWork() = runBlocking {
        val outcome = repo.transcribeSession(999_999L)
        assertEquals(0, outcome.attempted)
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun missingAudioFileFailsSegmentHonestly() = runBlocking {
        val id = sessionWithAudio(bytes = null)
        pendingSegment(id)
        val outcome = repo.transcribeSession(id)
        assertEquals(1, outcome.attempted)
        assertEquals(0, outcome.ready)
        assertEquals(1, outcome.failed)
        val seg = db.listenerDao().segments(id).single()
        assertEquals(ListenerSegment.STATUS_FAILED, seg.transcriptStatus)
        assertTrue(seg.transcript.contains("audio", ignoreCase = true))
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun emptyAudioFileFailsSegmentHonestly() = runBlocking {
        val id = sessionWithAudio(bytes = ByteArray(0))
        pendingSegment(id)
        repo.transcribeSession(id)
        val seg = db.listenerDao().segments(id).single()
        assertEquals(ListenerSegment.STATUS_FAILED, seg.transcriptStatus)
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun emptyTranscriptTextFailsInsteadOfReady() = runBlocking {
        val id = sessionWithAudio()
        pendingSegment(id)
        fake.result = { TranscriptionResult.Ready("   ") }
        repo.transcribeSession(id)
        val seg = db.listenerDao().segments(id).single()
        assertEquals(ListenerSegment.STATUS_FAILED, seg.transcriptStatus)
    }

    @Test
    fun transcriberThrowBecomesFailedNotCrash() = runBlocking {
        val id = sessionWithAudio()
        pendingSegment(id)
        fake.throwOn = RuntimeException("decoder exploded")
        repo.transcribeSession(id) // must not throw
        val seg = db.listenerDao().segments(id).single()
        assertEquals(ListenerSegment.STATUS_FAILED, seg.transcriptStatus)
        assertTrue(seg.transcript.contains("decoder exploded"))
    }

    // ---- idempotence / scoping ------------------------------------------------

    @Test
    fun readySegmentsAreNeverOverwritten() = runBlocking {
        val id = sessionWithAudio()
        pendingSegment(id)
        repo.transcribeSession(id)
        fake.result = { TranscriptionResult.Ready("INVENTED REPLACEMENT") }
        val outcome = repo.transcribeSession(id)
        assertEquals(0, outcome.attempted)
        assertEquals("fake transcript", db.listenerDao().segments(id).single().transcript)
    }

    @Test
    fun failedSegmentsAreNotSilentlyRetried() = runBlocking {
        val id = sessionWithAudio()
        pendingSegment(id)
        fake.result = { TranscriptionResult.Failed("no engine") }
        repo.transcribeSession(id)
        val callsAfterFirst = fake.calls.size
        repo.transcribeSession(id)
        assertEquals(callsAfterFirst, fake.calls.size)
        assertEquals(ListenerSegment.STATUS_FAILED, db.listenerDao().segments(id).single().transcriptStatus)
    }

    @Test
    fun repeatedTranscriptionIsIdempotent() = runBlocking {
        val id = sessionWithAudio()
        pendingSegment(id)
        val first = repo.transcribeSession(id)
        val second = repo.transcribeSession(id)
        assertEquals(1, first.attempted)
        assertEquals(1, first.ready)
        assertEquals(0, second.attempted)
        assertEquals(0, second.ready)
        assertEquals("fake transcript", db.listenerDao().segments(id).single().transcript)
    }

    @Test
    fun transcriptionIsScopedToItsSession() = runBlocking {
        val a = sessionWithAudio()
        val b = sessionWithAudio()
        pendingSegment(a)
        pendingSegment(b)
        repo.transcribeSession(a)
        assertEquals(ListenerSegment.STATUS_READY, db.listenerDao().segments(a).single().transcriptStatus)
        assertEquals(ListenerSegment.STATUS_PENDING, db.listenerDao().segments(b).single().transcriptStatus)
    }

    @Test
    fun outcomeCountsAreHonest() = runBlocking {
        val id = sessionWithAudio()
        pendingSegment(id, 0)
        pendingSegment(id, 1)
        var n = 0
        fake.result = {
            n++
            if (n == 1) TranscriptionResult.Ready("one") else TranscriptionResult.Failed("two failed")
        }
        val outcome = repo.transcribeSession(id)
        assertEquals(2, outcome.attempted)
        assertEquals(1, outcome.ready)
        assertEquals(1, outcome.failed)
    }

    // ---- storage honesty -----------------------------------------------------

    @Test
    fun noAudioBlobIsStoredInSqlite() = runBlocking {
        val id = sessionWithAudio()
        pendingSegment(id)
        repo.transcribeSession(id)
        val helper = db.openHelper.readableDatabase
        val cursor = helper.query("PRAGMA table_info(listener_segments)")
        val columns = mutableMapOf<String, String>()
        while (cursor.moveToNext()) {
            columns[cursor.getString(cursor.getColumnIndex("name"))] =
                cursor.getString(cursor.getColumnIndex("type"))
        }
        cursor.close()
        assertTrue(
            "listener_segments must hold no BLOB columns (audio lives in files)",
            columns.values.none { it.equals("BLOB", ignoreCase = true) }
        )
        assertEquals("TEXT", columns["transcript"]?.uppercase())
    }

    // ---- Phase 8 card integration --------------------------------------------

    @Test
    fun readySegmentFeedsCardGeneratorVerbatim() = runBlocking {
        val id = sessionWithAudio()
        pendingSegment(id)
        fake.result = { TranscriptionResult.Ready("The professor explained gradient descent in full detail today.") }
        repo.transcribeSession(id)
        val row = db.listenerDao().segments(id).single()
        val cards = CardGenerator.fromSegments(
            listOf(ReadySegmentRow(row.id, row.transcript, row.transcriptStatus))
        )
        assertEquals(1, cards.size)
        assertTrue(cards[0].back.contains("gradient descent"))
        assertEquals("seg:${row.id}", cards[0].contentKey)
    }

    @Test
    fun pendingAndFailedSegmentsFeedZeroCards() {
        val pending = ReadySegmentRow(1, "Some pending transcript text here for length.", "pending")
        val failed = ReadySegmentRow(2, "Some failed transcript text here for length.", "failed")
        assertTrue(CardGenerator.fromSegments(listOf(pending)).isEmpty())
        assertTrue(CardGenerator.fromSegments(listOf(failed)).isEmpty())
    }
}
