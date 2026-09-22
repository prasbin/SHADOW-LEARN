package com.prasbin.shadowlearn.data.ingest

import com.prasbin.shadowlearn.data.db.ClassType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for format rules: extensions, week/class parsing, sanitizing. */
class ZipFormatTest {

    @Test
    fun extensions_recognized() {
        assertTrue(IngestFormat.isSupported("lecture.PDF"))
        assertTrue(IngestFormat.isSupported("slides.pptx"))
        assertTrue(IngestFormat.isSupported("old.ppt"))
        assertTrue(IngestFormat.isSupported("notes.docx"))
        assertTrue(IngestFormat.isSupported("readme.txt"))
        assertTrue(IngestFormat.isSupported("script.py"))
        assertTrue(IngestFormat.isSupported("data.ipynb"))
        assertTrue(IngestFormat.isZip("module.zip"))
        assertTrue(IngestFormat.isZip("MODULE.ZIP"))
    }

    @Test
    fun extensions_unsupported() {
        assertFalse(IngestFormat.isSupported("movie.mp4"))
        assertFalse(IngestFormat.isSupported("app.exe"))
        assertFalse(IngestFormat.isSupported("photo.jpg"))
        assertFalse(IngestFormat.isSupported("noextension"))
        assertFalse(IngestFormat.isSupported("archive.zip"))
        assertEquals("pdf", IngestFormat.extensionOf("A.PDF"))
        assertEquals("", IngestFormat.extensionOf("noext"))
    }

    @Test
    fun weekNumbers_parsed() {
        assertEquals(1, IngestFormat.parseWeekNumber("Week 1"))
        assertEquals(3, IngestFormat.parseWeekNumber("week_3"))
        assertEquals(12, IngestFormat.parseWeekNumber("WEEK-12"))
        assertEquals(2, IngestFormat.parseWeekNumber("Week 2.zip"))
        assertNull(IngestFormat.parseWeekNumber("Weekly"))
        assertNull(IngestFormat.parseWeekNumber("Week X"))
        assertNull(IngestFormat.parseWeekNumber("Lecture"))
        assertNull(IngestFormat.parseWeekNumber(""))
    }

    @Test
    fun classTypes_parsed() {
        assertEquals(ClassType.LECTURE, IngestFormat.parseClassType("Lecture"))
        assertEquals(ClassType.LECTURE, IngestFormat.parseClassType("lectures"))
        assertEquals(ClassType.TUTORIAL, IngestFormat.parseClassType("Tutorial"))
        assertEquals(ClassType.WORKSHOP, IngestFormat.parseClassType("WORKSHOP"))
        assertNull(IngestFormat.parseClassType("Week 1"))
        assertNull(IngestFormat.parseClassType("Lab"))
        assertNull(IngestFormat.parseClassType("Notes"))
    }

    @Test
    fun sanitize_neutralizesEscapes() {
        assertEquals("a/b/c.pdf", IngestFormat.sanitizeRelativePath("a/b/c.pdf"))
        assertEquals("a/c.pdf", IngestFormat.sanitizeRelativePath("a/../c.pdf"))
        assertEquals("c.pdf", IngestFormat.sanitizeRelativePath("/c.pdf"))
        assertEquals("unnamed", IngestFormat.sanitizeRelativePath(""))
        assertTrue(IngestFormat.sanitizeRelativePath("a/b:c?.pdf").contains("_"))
    }

    @Test
    fun unsafePaths_rejected() {
        assertTrue(ZipWalk.isUnsafePath("/abs/path.pdf"))
        assertTrue(ZipWalk.isUnsafePath("a/../../evil.pdf"))
        assertTrue(ZipWalk.isUnsafePath("C:/win.pdf"))
        assertFalse(ZipWalk.isUnsafePath("ModA/Week 1/l.pdf"))
    }
}
