package com.prasbin.shadowlearn.data.backup

import com.prasbin.shadowlearn.data.backup.BackupFormat.ManifestException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 13 manifest tests: generation round-trips and every validation
 * rejection. Pure JSON, no database, no network.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupFormatTest {

    private fun sampleManifest(): String = BackupFormat.buildManifest(
        appVersion = "0.1.0-phase1",
        schemaVersion = 7,
        exportedAt = 1_700_000_000_000L,
        yearName = "Year 2",
        semesterName = "Semester 1",
        excludedAudioCount = 1,
        counts = BackupFormat.ManifestCounts(modules = 1, weeks = 1, files = 1),
        files = listOf(
            BackupFormat.ManifestFile(
                "SHADOW_LEARN_ARCHIVE/academic/AI.zip/Week 1/Lecture/l1.pdf",
                "a".repeat(64),
                12
            )
        )
    )

    @Test
    fun manifestRoundTripPreservesFields() {
        val m = BackupFormat.parseManifest(sampleManifest())
        assertEquals(1, m.archiveVersion)
        assertEquals("Year 2", m.yearName)
        assertEquals("Semester 1", m.semesterName)
        assertEquals(false, m.audioIncluded)
        assertEquals(1, m.excludedAudioCount)
        assertEquals(1, m.counts.files)
        assertEquals(1, m.files.size)
        assertEquals(
            "SHADOW_LEARN_ARCHIVE/academic/AI.zip/Week 1/Lecture/l1.pdf",
            m.files[0].path
        )
    }

    @Test
    fun missingFormatIdRejected() {
        try {
            BackupFormat.parseManifest("{}")
            fail("must reject")
        } catch (e: ManifestException) {
            assertTrue(e.message!!.contains("format", ignoreCase = true))
        }
    }

    @Test
    fun unsupportedVersionRejected() {
        val bad = sampleManifest().replace("\"archiveVersion\": 1", "\"archiveVersion\": 99")
        try {
            BackupFormat.parseManifest(bad)
            fail("must reject")
        } catch (e: ManifestException) {
            assertTrue(e.message!!.contains("99"))
        }
    }

    @Test
    fun blankNamesRejected() {
        val bad = sampleManifest().replace("\"Semester 1\"", "\"  \"")
        try {
            BackupFormat.parseManifest(bad)
            fail("must reject")
        } catch (e: ManifestException) {
            assertTrue(e.message!!.contains("year/semester", ignoreCase = true))
        }
    }

    @Test
    fun badShaRejected() {
        val bad = sampleManifest().replace("a".repeat(64), "not-a-hash")
        try {
            BackupFormat.parseManifest(bad)
            fail("must reject")
        } catch (e: ManifestException) {
            assertTrue(e.message!!.contains("sha256", ignoreCase = true))
        }
    }

    @Test
    fun nonAcademicPathRejected() {
        // Note: org.json escapes '/' as '\/' in output.
        val bad = sampleManifest().replace("academic\\/", "evil/")
        try {
            BackupFormat.parseManifest(bad)
            fail("must reject")
        } catch (e: ManifestException) {
            assertTrue(e.message!!.contains("path", ignoreCase = true))
        }
    }

    @Test
    fun negativeCountRejected() {
        val bad = sampleManifest().replace("\"modules\": 1", "\"modules\": -1")
        try {
            BackupFormat.parseManifest(bad)
            fail("must reject")
        } catch (e: ManifestException) {
            assertTrue(e.message!!.contains("modules"))
        }
    }

    @Test
    fun garbageJsonRejected() {
        try {
            BackupFormat.parseManifest("this is not json{{{")
            fail("must reject")
        } catch (e: ManifestException) {
            assertTrue(e.message!!.contains("JSON", ignoreCase = true))
        }
    }
}
