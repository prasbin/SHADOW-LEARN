package com.prasbin.shadowlearn.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FileInfoTest {

    @Test
    fun formatBytes_units() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("512 B", formatBytes(512))
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("1.5 KB", formatBytes(1536))
        assertEquals("2.0 MB", formatBytes(2L * 1024 * 1024))
        assertEquals("unknown size", formatBytes(-1))
    }
}
