package com.prasbin.shadowlearn.data.extract

/**
 * Plain-text extractor for [ExtractionFormat.textLikeExtensions]: decodes
 * UTF-8 (malformed input decoded with replacement, never an error) and
 * produces a single page-less section. Used for txt, md, source code,
 * markup and data formats.
 */
object PlainTextExtractor {

    fun extract(bytes: ByteArray): ExtractedDocument {
        if (bytes.size > ExtractionFormat.MAX_EXTRACT_BYTES) {
            throw ExtractionException("File too large for extraction (> ${ExtractionFormat.MAX_EXTRACT_BYTES} bytes).")
        }
        val text = bytes.toString(Charsets.UTF_8)
        return ExtractedDocument(listOf(ExtractedSection(pageNumber = null, text = text)))
    }
}