package com.prasbin.shadowlearn.data.extract

/**
 * Chunks an [ExtractedDocument] into deterministic, size-capped units for
 * the `document_chunks` table and FTS mirror.
 *
 * Granularity: one chunk per page/slide section created by the extractor
 * (PDF pages, PPTX slides, DOCX page breaks, one for text files). A single
 * section longer than [MAX_CHUNK_CHARS] is split on the nearest preceding
 * whitespace so words are never cut; page references are preserved on every
 * piece of a split section.
 *
 * All text is normalized identically here (whitespace collapsed, trimmed),
 * so what lands in chunks == what the FTS index sees. Deterministic order:
 * sections in document order, pieces in order, indices 0..n.
 */
object TextChunker {

    const val MAX_CHUNK_CHARS = 4096

    /** A chunk ready for persistence. */
    data class ChunkPiece(
        /** 0-based order within the file. */
        val index: Int,
        val pageNumber: Long?,
        val text: String
    )

    private val WHITESPACE = Regex("\\s+")

    fun normalize(text: String): String = WHITESPACE.replace(text, " ").trim()

    fun chunk(document: ExtractedDocument): List<ChunkPiece> {
        val out = mutableListOf<ChunkPiece>()
        var index = 0
        for (section in document.sections) {
            val text = normalize(section.text)
            if (text.isEmpty()) continue
            if (text.length <= MAX_CHUNK_CHARS) {
                out += ChunkPiece(index++, section.pageNumber, text)
            } else {
                var start = 0
                while (start < text.length) {
                    var end = (start + MAX_CHUNK_CHARS).coerceAtMost(text.length)
                    if (end < text.length) {
                        val boundary = text.lastIndexOf(' ', end - 1)
                        if (boundary > start) end = boundary
                    }
                    out += ChunkPiece(index++, section.pageNumber, text.substring(start, end).trim())
                    start = end
                }
            }
        }
        return out
    }
}