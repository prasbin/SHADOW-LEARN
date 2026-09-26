package com.prasbin.shadowlearn.data.listener

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.prasbin.shadowlearn.data.db.AcademicYear
import com.prasbin.shadowlearn.data.db.ListenerSegment
import com.prasbin.shadowlearn.data.db.ListenerSession
import com.prasbin.shadowlearn.data.db.Module
import com.prasbin.shadowlearn.data.db.Semester
import com.prasbin.shadowlearn.data.db.ShadowLearnDatabase
import com.prasbin.shadowlearn.data.db.Week
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * End-to-end Listener Mode over Room with a fake recorder and a fake
 * monotonic clock: session/segment lifecycle, exact pause math, failure
 * honesty, semester scoping, and process-death rehydration.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ListenerRepositoryTest {

    private class FakeRecorder(
        var startFails: Exception? = null,
        var stopFails: RuntimeException? = null,
        var createFile: Boolean = true,
        var amplitude: Int = 0
    ) : ListenerRecorder {
        var starts = 0
        var pauses = 0
        var resumes = 0
        var stops = 0
        var releases = 0

        override fun start(outputFile: File) {
            starts++
            startFails?.let { throw it }
            if (createFile) {
                outputFile.parentFile?.mkdirs()
                outputFile.writeBytes(ByteArray(16) { it.toByte() })
            }
        }

        override fun pause() {
            pauses++
        }

        override fun resume() {
            resumes++
        }

        override fun stop() {
            stops++
            stopFails?.let { throw it }
        }

        override fun release() {
            releases++
        }

        override fun maxAmplitude(): Int = amplitude
    }

    private lateinit var db: ShadowLearnDatabase
    private lateinit var audioDir: File
    private var monoNow: Long = 0
    private var wallNow: Long = 1_700_000_000_000L
    private lateinit var fake: FakeRecorder
    private lateinit var repo: ListenerRepository
    private var s1: Long = 0
    private var s2: Long = 0

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, ShadowLearnDatabase::class.java)
            .allowMainThreadQueries().build()
        audioDir = File(context.cacheDir, "listener-test").also { it.mkdirs() }
        fake = FakeRecorder()
        repo = ListenerRepository(
            dao = db.listenerDao(),
            audioDir = audioDir,
            recorderFactory = { fake },
            monoClock = { monoNow },
            wallClock = { wallNow },
            dispatcher = Dispatchers.Unconfined
        )
        runBlocking {
            val dao = db.academicDao()
            val y = dao.insertYear(AcademicYear(name = "Year 2", sortOrder = 0))
            s1 = dao.insertSemester(Semester(yearId = y, name = "Semester 1", sortOrder = 0))
            s2 = dao.insertSemester(Semester(yearId = y, name = "Semester 2", sortOrder = 1))
            val m = dao.insertModule(Module(semesterId = s1, name = "AI"))
            dao.insertWeek(Week(moduleId = m, weekNumber = 1))
        }
    }

    @After
    fun tearDown() {
        db.close()
        audioDir.deleteRecursively()
    }

    @Test
    fun startCreatesSessionSegmentAndAudioPath() = runBlocking {
        val rec = repo.start(s1)
        assertEquals(ListenerState.RECORDING, rec.state)
        assertTrue(rec.audioPath!!.startsWith(audioDir.absolutePath))
        assertTrue(File(rec.audioPath).exists())
        val stored = db.listenerDao().session(rec.sessionId)!!
        assertEquals(s1, stored.semesterId)
        assertEquals(ListenerSession.STATUS_RECORDING, stored.status)
        assertEquals(rec.audioPath, stored.audioPath)
        val segs = db.listenerDao().segments(rec.sessionId)
        assertEquals(1, segs.size)
        assertEquals(0, segs[0].position)
        assertEquals(0, segs[0].startedAtMs)
        assertEquals(ListenerSegment.STATUS_PENDING, segs[0].transcriptStatus)
        assertEquals(ListenerSegment.TRANSCRIPT_PENDING, segs[0].transcript)
    }

    @Test
    fun pauseResumeCutsSegmentsWithExactTiming() = runBlocking {
        val rec = repo.start(s1)
        monoNow = 5_000
        repo.pause()
        assertEquals(ListenerState.PAUSED, repo.currentState())
        var segs = db.listenerDao().segments(rec.sessionId)
        assertEquals(1, segs.size)
        assertEquals(5_000, segs[0].durationMs)
        assertEquals(ListenerSession.STATUS_PAUSED, db.listenerDao().session(rec.sessionId)!!.status)
        // Paused gap must not leak into any segment.
        monoNow = 7_000
        repo.resume()
        segs = db.listenerDao().segments(rec.sessionId)
        assertEquals(2, segs.size)
        assertEquals(1, segs[1].position)
        assertEquals(7_000, segs[1].startedAtMs)
        assertEquals(0, segs[1].durationMs)
        monoNow = 10_000
        repo.stop()
        segs = db.listenerDao().segments(rec.sessionId)
        assertEquals(2, segs.size)
        assertEquals(3_000, segs[1].durationMs)
        val stored = db.listenerDao().session(rec.sessionId)!!
        assertEquals(ListenerSession.STATUS_COMPLETED, stored.status)
        assertNotNull(stored.completedAt)
    }

    @Test
    fun segmentPositionsAreSequentialAcrossPauses() = runBlocking {
        val rec = repo.start(s1)
        repeat(3) {
            monoNow += 1_000
            repo.pause()
            monoNow += 500
            repo.resume()
        }
        repo.stop()
        val positions = db.listenerDao().segments(rec.sessionId).map { it.position }
        assertEquals(listOf(0, 1, 2, 3), positions)
    }

    @Test
    fun invalidTransitionsAreRejected() = runBlocking {
        try {
            repo.pause()
            fail("pause without start must throw")
        } catch (_: IllegalStateException) {
        }
        try {
            repo.stop()
            fail("stop without start must throw")
        } catch (_: IllegalStateException) {
        }
        try {
            repo.resume()
            fail("resume without pause must throw")
        } catch (_: IllegalStateException) {
        }
        val rec = repo.start(s1)
        try {
            repo.start(s1)
            fail("double start must throw")
        } catch (_: IllegalStateException) {
        }
        try {
            repo.resume()
            fail("resume while recording must throw")
        } catch (_: IllegalStateException) {
        }
        repo.stop()
        try {
            repo.pause()
            fail("pause after completion must throw")
        } catch (_: IllegalStateException) {
        }
        assertEquals(1, fake.starts)
    }

    @Test
    fun startRequiresSemester() = runBlocking {
        try {
            repo.start(0)
            fail("semester 0 must throw")
        } catch (_: IllegalArgumentException) {
        }
        try {
            repo.start(-3)
            fail("negative semester must throw")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun recorderStartFailureMarksSessionFailed() = runBlocking {
        fake.startFails = SecurityException("mic denied")
        try {
            repo.start(s1)
            fail("start must propagate the failure")
        } catch (e: ListenerException) {
            assertTrue(
                "msg=" + e.message,
                e.message!!.contains("could not start", ignoreCase = true)
            )
            // The SecurityException must survive somewhere in the chain.
            // (Walk, don't assert direct identity: kotlinx-coroutines
            // stack-trace recovery can interpose a link when the throw hops
            // the Room executor thread under Dispatchers.Unconfined.)
            var t: Throwable? = e
            var seenSecurity = false
            while (t != null) {
                if (t is SecurityException) seenSecurity = true
                t = t.cause
            }
            assertTrue("SecurityException must be preserved in the chain", seenSecurity)
        }
        assertEquals(ListenerState.IDLE, repo.currentState())
        val sessions = db.listenerDao().sessionsOfSemester(s1)
        assertEquals(1, sessions.size)
        assertEquals(ListenerSession.STATUS_FAILED, sessions[0].status)
        assertTrue(fake.releases > 0)
        // A retry works without any reset: the machine never left IDLE.
        fake.startFails = null
        val retry = repo.start(s1)
        assertEquals(ListenerState.RECORDING, retry.state)
        repo.stop()
        assertEquals(
            ListenerSession.STATUS_COMPLETED,
            db.listenerDao().session(retry.sessionId)!!.status
        )
    }

    @Test
    fun stopWithEmptyAudioFileClearsPathButCompletes() = runBlocking {
        fake.createFile = false // recorder "succeeds" but leaves no usable file
        val rec = repo.start(s1)
        repo.stop()
        val stored = db.listenerDao().session(rec.sessionId)!!
        assertEquals(ListenerSession.STATUS_COMPLETED, stored.status)
        assertNull("missing audio must be reported honestly", stored.audioPath)
    }

    @Test
    fun recorderStopThrowStillPersistsCompletedSession() = runBlocking {
        fake.stopFails = RuntimeException("stop called in invalid state")
        val rec = repo.start(s1)
        try {
            repo.stop()
            fail("stop error must surface")
        } catch (e: ListenerException) {
            assertTrue(e.message!!.contains("still saved", ignoreCase = true))
        }
        assertEquals(ListenerSession.STATUS_COMPLETED, db.listenerDao().session(rec.sessionId)!!.status)
    }

    @Test
    fun sessionsAreScopedToSemester() = runBlocking {
        val a = repo.start(s1)
        repo.stop()
        repo.reset()
        val b = repo.start(s2)
        repo.stop()
        assertEquals(listOf(a.sessionId), db.listenerDao().sessionsOfSemester(s1).map { it.id })
        assertEquals(listOf(b.sessionId), db.listenerDao().sessionsOfSemester(s2).map { it.id })
    }

    @Test
    fun rehydrateMarksInterruptedAndKeepsSegments() = runBlocking {
        val rec = repo.start(s1)
        monoNow = 4_000
        repo.pause()
        monoNow = 6_000
        repo.resume() // segment 1 left open, then the "process dies"
        val repo2 = ListenerRepository(
            dao = db.listenerDao(),
            audioDir = audioDir,
            recorderFactory = { FakeRecorder() },
            monoClock = { monoNow },
            wallClock = { wallNow },
            dispatcher = Dispatchers.Unconfined
        )
        val recovered = repo2.rehydrate()
        assertNotNull(recovered)
        assertEquals(rec.sessionId, recovered!!.sessionId)
        assertEquals(ListenerSession.STATUS_INTERRUPTED, recovered.status)
        assertEquals(2, recovered.segments.size)
        assertEquals(4_000, recovered.segments[0].durationMs)
        // Tail span closed honestly with unknown length, never fabricated.
        assertEquals(0, recovered.segments[1].durationMs)
        assertEquals(ListenerSession.STATUS_INTERRUPTED, db.listenerDao().session(rec.sessionId)!!.status)
        assertNull(repo2.rehydrate()) // second recovery finds nothing open
    }

    @Test
    fun rehydrateWithNothingOpenReturnsNull() = runBlocking {
        assertNull(repo.rehydrate())
        val rec = repo.start(s1)
        repo.stop()
        assertNull(repo.rehydrate())
    }

    @Test
    fun resetAfterCompletionAllowsNewSession() = runBlocking {
        val a = repo.start(s1)
        repo.stop()
        repo.reset()
        assertEquals(ListenerState.IDLE, repo.currentState())
        val b = repo.start(s1)
        assertTrue(b.sessionId != a.sessionId)
        assertEquals(ListenerState.RECORDING, repo.currentState())
    }

    @Test
    fun noRawAudioBytesAreStoredInSqlite() = runBlocking {
        val rec = repo.start(s1)
        repo.stop()
        val helper = db.openHelper.readableDatabase
        for (table in listOf("listener_sessions", "listener_segments")) {
            val cursor = helper.query("PRAGMA table_info($table)")
            val types = mutableListOf<String>()
            while (cursor.moveToNext()) {
                types += cursor.getString(cursor.getColumnIndex("type"))
            }
            cursor.close()
            assertTrue(
                "$table must hold no BLOB columns (audio lives in files)",
                types.none { it.equals("BLOB", ignoreCase = true) }
            )
        }
        // audioPath is a TEXT path, and the bytes live on disk.
        assertNotNull(db.listenerDao().session(rec.sessionId)!!.audioPath)
    }

    @Test
    fun amplitudeIsHonestAndNeverThrows() = runBlocking {
        assertEquals(0, repo.amplitude())
        fake.amplitude = 12_000
        repo.start(s1)
        assertEquals(12_000, repo.amplitude())
        fake.amplitude = 0
        assertEquals(0, repo.amplitude())
    }

    @Test
    fun audioFileNameIsSafeAndAppPrivate() = runBlocking {
        val rec = repo.start(s1)
        val file = File(rec.audioPath!!)
        assertEquals(audioDir, file.parentFile)
        assertTrue(file.name.matches(Regex("listener_\\d+_\\d+\\.m4a")))
    }
}
