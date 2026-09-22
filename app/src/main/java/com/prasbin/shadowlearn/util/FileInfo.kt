package com.prasbin.shadowlearn.util

/** Pure helper for the SAF file-picker display. Covered by unit test. */
fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "unknown size"
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024.0
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (value >= 100) "%d %s".format(value.toLong(), units[unit])
    else "%.1f %s".format(value, units[unit])
}
