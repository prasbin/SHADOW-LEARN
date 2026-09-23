package com.prasbin.shadowlearn.data.extract

/**
 * Phase 4 document extraction core — pure Kotlin/JVM, no Android SDK, no
 * third-party extraction libraries (offline & honest rationale in
 * docs/ARCHITECTURE.md). Supported:
 *
 * - PDF:  hand-rolled FlateDecode + text-operator extraction with page-tree
 *         page numbering (degraded when object streams / compressed xrefs
 *         are present — page references fall back to null).
 * - DOCX / PPTX: ZIP + XML text-role extraction (`w:t` / `a:t`) with
 *         page-break-based DOCX pages and slide-based PPTX references.
 * - Plain text and text-like code/markup formats: UTF-8 decode.
 * - Legacy .doc / .ppt binary OLE: honest [ExtractionException] (no support).
 *
 * Each extractor either returns a document or throws [ExtractionException]
 * with a human-readable reason — never a guessed "certain" result.
 */
object ExtractionFormat {

    /** Extensions handled by the binary extractors. */
    val PDF_EXTENSIONS = setOf("pdf")
    val DOCX_EXTENSIONS = setOf("docx")
    val PPTX_EXTENSIONS = setOf("pptx")

    /** Text-decodable stored academic formats (incl. source code materials). */
    val TEXT_LIKE_EXTENSIONS: Set<String> = setOf(
        "txt", "md", "rtf", "csv", "json", "xml", "html", "htm", "css", "sql",
        "py", "java", "kt", "js", "ts", "c", "h", "cpp", "hpp", "ipynb"
    )

    /** Legacy binary formats with degraded/no support (documented honestly). */
    val UNSUPPORTED_LEGACY_EXTENSIONS = setOf("doc", "ppt")

    /** Hard cap per extracted file (guards memory on device). */
    const val MAX_EXTRACT_BYTES = 64L * 1024 * 1024

    enum class Kind { PDF, DOCX, PPTX, TEXT, UNSUPPORTED }

    fun kindFor(fileType: String): Kind = when (fileType.lowercase()) {
        in PDF_EXTENSIONS -> Kind.PDF
        in DOCX_EXTENSIONS -> Kind.DOCX
        in PPTX_EXTENSIONS -> Kind.PPTX
        in TEXT_LIKE_EXTENSIONS -> Kind.TEXT
        else -> Kind.UNSUPPORTED
    }

    /** Human-readable extractor name for [com.prasbin.shadowlearn.data.db.ExtractionMeta.format]. */
    fun extractorName(kind: Kind): String = when (kind) {
        Kind.PDF -> "pdf"
        Kind.DOCX -> "docx"
        Kind.PPTX -> "pptx"
        Kind.TEXT -> "txt"
        Kind.UNSUPPORTED -> "unsupported"
    }

    fun extract(kind: Kind, bytes: ByteArray): ExtractedDocument = when (kind) {
        Kind.PDF -> PdfTextExtractor.extract(bytes)
        Kind.DOCX -> OoxmlTextExtractor.extract(bytes, OoxmlTextExtractor.Kind.DOCX)
        Kind.PPTX -> OoxmlTextExtractor.extract(bytes, OoxmlTextExtractor.Kind.PPTX)
        Kind.TEXT -> PlainTextExtractor.extract(bytes)
        Kind.UNSUPPORTED -> throw ExtractionException(
            "Legacy .doc/.ppt binary format: not supported — no extractor (see docs/ARCHITECTURE.md)."
        )
    }
}

/** Raised whenever a file cannot be extracted; message is stored verbatim. */
class ExtractionException(message: String) : Exception(message)

/** One extractable unit of a document: page/slide reference + text. */
data class ExtractedSection(val pageNumber: Long?, val text: String)

/** Ordered result of an extractor: page/slide sections as produced. */
data class ExtractedDocument(val sections: List<ExtractedSection>) {
    /** Raw (pre-normalization) character count across all sections. */
    val charCount: Long = sections.sumOf { it.text.length.toLong() }
}