package com.prasbin.shadowlearn.data.ingest

import com.prasbin.shadowlearn.data.db.ClassType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for recursive walking + hierarchy planning.
 * ZIPs are built in memory with java.util.zip — the real walk/plan code runs.
 */
class ZipWalkTest {

    private fun staging(): File = Files.createTempDirectory("walktest").toFile()

    /** Builds a ZIP from path→bytes pairs (null bytes = directory entry). */
    private fun zipBytes(vararg entries: Pair<String, ByteArray?>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            for ((path, bytes) in entries) {
                val dir = bytes == null
                zos.putNextEntry(ZipEntry(if (dir && !path.endsWith('/')) "$path/" else path))
                if (bytes != null) zos.write(bytes)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun walk(bytes: ByteArray, name: String = "sem.zip"): ZipWalk.WalkResult =
        ZipWalk.walk(ByteArrayInputStream(bytes), name, staging())

    private fun planOf(result: ZipWalk.WalkResult, fallback: String = "Sem"): HierarchyPlan =
        buildPlan(ZipWalk.toPlanInput(result), fallback)

    private fun pdfBytes(seed: String) = "PDF-content-$seed".toByteArray()

    // A. Normal nested hierarchy ------------------------------------------------

    @Test
    fun normalNestedHierarchy() {
        val prog = zipBytes(
            "Week 1/Lecture/l1.pdf" to pdfBytes("a"),
            "Week 1/Tutorial/t1.pptx" to pdfBytes("b"),
            "Week 2/Workshop/w1.docx" to pdfBytes("c")
        )
        val ai = zipBytes("Week 1/Lecture/nn.pdf" to pdfBytes("d"))
        val top = zipBytes(
            "Programming.zip" to prog,
            "AI.zip" to ai
        )
        val plan = planOf(walk(top))
        assertEquals(setOf("Programming", "AI"), plan.modules.map { it.name }.toSet())
        val progFiles = plan.files.filter { it.moduleName == "Programming" }
        assertEquals(3, progFiles.size)
        val l1 = progFiles.first { it.entry.logicalPath.endsWith("l1.pdf") }
        assertEquals(1, l1.weekNumber)
        assertEquals(ClassType.LECTURE, l1.classType)
        assertEquals("Programming.zip/Week 1/Lecture/l1.pdf", l1.entry.logicalPath)
        val aiFile = plan.files.single { it.moduleName == "AI" }
        assertEquals(ClassType.LECTURE, aiFile.classType)
    }

    // B. Multiple nesting levels --------------------------------------------------

    @Test
    fun threeLevelsOfNesting() {
        val inner = zipBytes("Week 5/Lecture/deep.pdf" to pdfBytes("deep"))
        val mid = zipBytes("Inner.zip" to inner)
        val top = zipBytes("Mid.zip" to mid)
        val plan = planOf(walk(top))
        // Transparent containers: single module from the outermost zip.
        assertEquals(listOf("Mid"), plan.modules.map { it.name })
        val f = plan.files.single()
        assertEquals(5, f.weekNumber)
        assertEquals(ClassType.LECTURE, f.classType)
        assertEquals("Mid.zip/Inner.zip/Week 5/Lecture/deep.pdf", f.entry.logicalPath)
    }

    // C. Empty folders ------------------------------------------------------------

    @Test
    fun emptyFolders_createStructure() {
        val top = zipBytes(
            "EmptyMod/Week 9/" to null,
            "EmptyMod/Week 9/Lecture/" to null
        )
        val plan = planOf(walk(top))
        assertEquals(listOf("EmptyMod"), plan.modules.map { it.name })
        assertEquals(1, plan.weeks.size)
        assertEquals(9, plan.weeks.single().weekNumber)
        assertEquals(0, plan.files.size)
    }

    // D. Empty ZIP -----------------------------------------------------------------

    @Test
    fun emptyZip_reportsNoContent() {
        val result = walk(zipBytes())
        assertTrue(result.entries.isEmpty())
        assertTrue(result.dirPaths.isEmpty())
    }

    // E. Invalid / corrupt ZIP -------------------------------------------------------

    @Test
    fun corruptZip_doesNotThrow() {
        val result = walk("this is not a zip at all".toByteArray(), "bad.zip")
        assertTrue(result.entries.isEmpty())
        assertTrue(result.errors.isNotEmpty())
    }

    @Test
    fun corruptNestedZip_isolatesError() {
        val good = zipBytes("Week 1/Lecture/ok.pdf" to pdfBytes("ok"))
        val top = zipBytes(
            "Good.zip" to good,
            "Broken.zip" to "garbage-bytes".toByteArray()
        )
        val result = walk(top)
        val plan = planOf(result)
        assertEquals(1, plan.files.size)
        assertEquals("Good", plan.files.single().moduleName)
        assertTrue(result.errors.any { it.contains("Broken.zip") })
    }

    // F. Duplicate filenames ----------------------------------------------------------

    @Test
    fun duplicateFilenames_inDifferentWeeks() {
        val mod = zipBytes(
            "Week 1/Lecture/notes.pdf" to pdfBytes("w1"),
            "Week 2/Lecture/notes.pdf" to pdfBytes("w2")
        )
        val plan = planOf(walk(zipBytes("M.zip" to mod)))
        val notes = plan.files.filter { it.entry.logicalPath.endsWith("notes.pdf") }
        assertEquals(2, notes.size)
        assertEquals(
            setOf("M.zip/Week 1/Lecture/notes.pdf", "M.zip/Week 2/Lecture/notes.pdf"),
            notes.map { it.entry.logicalPath }.toSet()
        )
    }

    // G. Metadata passthrough ----------------------------------------------------------

    @Test
    fun metadata_sizesAndOrder() {
        val big = ByteArray(10_000) { it.toByte() }
        val mod = zipBytes("Week 1/Lecture/a.pdf" to big)
        val result = walk(zipBytes("M.zip" to mod))
        assertEquals(10_000, result.entries.single().size)
    }

    // H. Supported vs unsupported ------------------------------------------------------

    @Test
    fun mixedFileTypes_planKeepsAllFilteringIsRepositoryJob() {
        val mod = zipBytes(
            "Week 1/Lecture/a.pdf" to pdfBytes("a"),
            "Week 1/Lecture/b.mp4" to pdfBytes("b"),
            "Week 1/Lecture/c.py" to pdfBytes("c")
        )
        val plan = planOf(walk(zipBytes("M.zip" to mod)))
        // buildPlan is type-agnostic; IngestRepository applies STORED_EXTENSIONS.
        assertEquals(3, plan.files.size)
        assertTrue(IngestFormat.isSupported("a.pdf"))
        assertTrue(IngestFormat.isSupported("c.py"))
        assertTrue(!IngestFormat.isSupported("b.mp4"))
    }

    // Wrapper + root files ----------------------------------------------------------------

    @Test
    fun wrapperDirectory_stripped() {
        val top = zipBytes(
            "Semester1/Prog/Week 1/Lecture/l.pdf" to pdfBytes("x")
        )
        val plan = planOf(walk(top))
        assertEquals(listOf("Prog"), plan.modules.map { it.name })
    }

    @Test
    fun rootFiles_goToFallbackModuleWeekOne() {
        val top = zipBytes("loose.pdf" to pdfBytes("loose"))
        val plan = planOf(walk(top, "MySem.zip"), "MySem")
        assertEquals(listOf("MySem"), plan.modules.map { it.name })
        val f = plan.files.single()
        assertEquals(1, f.weekNumber)
        assertEquals(ClassType.OTHER, f.classType)
    }

    @Test
    fun unplacedClassDir_forcesWeekOne() {
        val top = zipBytes("ModA/Lecture/l.pdf" to pdfBytes("l"))
        val plan = planOf(walk(top))
        val f = plan.files.single()
        assertEquals("ModA", f.moduleName)
        assertEquals(1, f.weekNumber)
        assertEquals(ClassType.LECTURE, f.classType)
    }

    // I. Backslash-separated archives (Windows exporters; Android ZipFile
    //    on nested streams) -------------------------------------------------

    @Test
    fun windowsBackslashNames_normalizedToForwardSlashes() {
        val mod = zipBytes("Week 2\\Lecture\\l2.pdf" to pdfBytes("l2"))
        val top = zipBytes("Win.zip" to mod)
        val plan = planOf(walk(top))
        val f = plan.files.single()
        assertEquals("Win.zip/Week 2/Lecture/l2.pdf", f.entry.logicalPath)
        assertEquals("Win", f.moduleName)
        assertEquals(2, f.weekNumber)
        assertEquals(ClassType.LECTURE, f.classType)
    }

    @Test
    fun windowsBackslashTraversal_rejected() {
        val result = walk(zipBytes("..\\escape.txt" to pdfBytes("x")))
        assertTrue(result.entries.isEmpty())
        assertTrue(result.errors.any { it.contains("escape.txt") })
    }

    @Test
    fun windowsAbsoluteDrivePath_rejected() {
        val result = walk(zipBytes("C:\\docs\\x.pdf" to pdfBytes("x")))
        assertTrue(result.entries.isEmpty())
        assertTrue(result.errors.isNotEmpty())
    }
}
