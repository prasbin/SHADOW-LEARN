package com.prasbin.shadowlearn.data.extract

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * Minimal, honest PDF text extractor — hand-rolled, zero dependencies.
 *
 * Supported subset (see docs/ARCHITECTURE.md for the full limitations):
 * - object parsing: indirect objects with `FlateDecode` (zlib) or unfiltered
 *   (`identity`) streams, `/Length`-bounded reads where present;
 * - text: `Tj`, `'`, `"`, `TJ` string literals and hex strings, escape
 *   sequences and nested parentheses;
 * - pages: `/Type /Catalog` → `/Pages` tree walk → `/Contents` references,
 *   each page keeping its 1-based number; a flat fallback (all decoded
 *   content streams in object order, no page reference) is used when the
 *   page tree cannot be resolved — never a fabricated page number;
 * - streams with unsupported filters (images etc.) are skipped; encrypted
 *   files or files with no extractable content stream fail loudly.
 *
 * Degraded: compressed object streams / xref tables (page mapping falls
 * back to the flat form), CJK CMaps without embedded ToUnicode (raw bytes
 * decoded as Latin-1).
 */
object PdfTextExtractor {

    private val OBJECT_HEADER = Regex("(?s)(?:^|\\n)(\\d+)\\s+(\\d+)\\s+obj")
    private val STREAM_MARK = Regex("(?s)stream(?:\\r?\\n)")
    private val ENDSTREAM = "endstream"

    fun extract(bytes: ByteArray): ExtractedDocument {
        if (bytes.size > ExtractionFormat.MAX_EXTRACT_BYTES) {
            throw ExtractionException("File too large for extraction (> ${ExtractionFormat.MAX_EXTRACT_BYTES} bytes).")
        }
        val iso = bytes.toString(Charsets.ISO_8859_1)

        // ---- 1. enumerate indirect object slots -----------------------------
        val slots = mutableListOf<Pair<Int, Int>>() // objNum -> absolute offset
        for (m in OBJECT_HEADER.findAll(iso)) {
            val num = m.groupValues[1].toInt()
            slots += num to m.range.first
        }
        if (slots.isEmpty()) throw ExtractionException("Not a valid PDF: no objects found.")

        // ---- 2. parse each object body (dict + optional stream) -------------
        val bodies = mutableMapOf<Int, String>()      // objNum -> dictionary text
        val streams = mutableMapOf<Int, ByteArray>()  // objNum -> decoded bytes
        var lastStreamEnd = -1
        for ((slot, obj) in slots.withIndex()) {
            val (num, start) = obj
            if (start < lastStreamEnd) continue // false positive inside a prior stream
            val end = if (slot + 1 < slots.size) slots[slot + 1].second else iso.length
            val body = iso.substring(start, end)

            val streamMatch = STREAM_MARK.find(body)
            val streamStart = streamMatch?.range?.last?.plus(1)
            val dictText = body.substring(0, streamStart ?: body.length)
            bodies[num] = dictText

            if (streamStart != null) {
                val data = readStreamData(body, streamStart, dictText)
                if (data != null) streams[num] = data
                // Everything up to the end of this body region is consumed.
                lastStreamEnd = start + body.length
            }
        }
        if (streams.isEmpty()) {
            throw ExtractionException("No extractable content streams (encrypted or unsupported structure).")
        }

        // ---- 3. page tree walk (best effort) --------------------------------
        val catalog = bodies.entries.firstOrNull { (_, d) -> "/Type\\s*/Catalog".toRegex().containsMatchIn(d) }
        val pagesNode = catalog
            ?.let { (num, dict) -> Regex("/Pages\\s+(\\d+)\\s+0\\s+R").find(dict)?.groupValues?.get(1)?.toInt() }

        var pageContents: List<List<Int>>? = null // page -> content obj refs
        if (pagesNode != null) {
            try {
                pageContents = walkPages(bodies, pagesNode, 0)
            } catch (_: Exception) {
                pageContents = null // degraded -> flat fallback
            }
            if (pageContents != null && pageContents.filter { it.isNotEmpty() }.isEmpty()) pageContents = null
        }

        // ---- 4. extract text per page (or flat fallback) --------------------
        if (pageContents != null && pageContents.isNotEmpty()) {
            val sections = pageContents.mapIndexed { page, refs ->
                val text = refs.joinToString(" ") { extractText(streams[it] ?: ByteArray(0)) }
                ExtractedSection(pageNumber = (page + 1).toLong(), text = text)
            }
            return ExtractedDocument(sections)
        }

        val flat = streams.entries.sortedBy { it.key }
            .joinToString(" ") { (_, bytes) -> extractText(bytes) }
        return ExtractedDocument(listOf(ExtractedSection(pageNumber = null, text = flat)))
    }

    /** Recursively walks /Pages -> /Kids -> /Page, returning per-page content refs. */
    private fun walkPages(bodies: Map<Int, String>, nodeNum: Int, depth: Int): List<List<Int>> {
        if (depth > 64) throw IllegalStateException("pages tree too deep")
        val dict = bodies[nodeNum] ?: throw IllegalStateException("missing pages node $nodeNum")
        val kidsRaw = Regex("/Kids\\s*\\[([^\\]]*)\\]").find(dict)?.groupValues?.get(1)
            ?: throw IllegalStateException("pages node without kids")
        val refs = Regex("(\\d+)\\s+0\\s+R").findAll(kidsRaw)
            .map { it.groupValues[1].toInt() }.toList()
        if (refs.isEmpty()) throw IllegalStateException("no usable kids")
        val pages = mutableListOf<List<Int>>()
        for (r in refs) {
            val rdict = bodies[r] ?: continue
            when {
                "/Type\\s*/Page\\b".toRegex().containsMatchIn(rdict) -> {
                    val single = Regex("/Contents\\s+(\\d+)\\s+0\\s+R").find(rdict)
                        ?.groupValues?.get(1)?.toInt()?.let { listOf(it) }
                    val array = single
                        ?: Regex("/Contents\\s*\\[([^\\]]*)\\]").find(rdict)
                            ?.groupValues?.get(1)
                            ?.let { a -> Regex("(\\d+)\\s+0\\s+R").findAll(a).map { it.groupValues[1].toInt() }.toList() }
                    pages += array ?: emptyList()
                }
                "/Type\\s*/Pages\\b".toRegex().containsMatchIn(rdict) -> pages += walkPages(bodies, r, depth + 1)
                else -> Unit // unknown node type: skip
            }
        }
        if (pages.size > 10_000) throw IllegalStateException("too many pages")
        return pages
    }

    /** Returns decoded stream bytes honoring /Length and FlateDecode/identity. */
    private fun readStreamData(body: String, streamStart: Int, dictText: String): ByteArray? {
        val lengthRaw = Regex("/Length\\s+(\\d+)").find(dictText)?.groupValues?.get(1)?.toIntOrNull()
        val dataRange = if (lengthRaw != null && streamStart + lengthRaw <= body.length) {
            streamStart until (streamStart + lengthRaw)
        } else {
            val endIdx = body.indexOf(ENDSTREAM, startIndex = streamStart)
            if (endIdx < 0) return null
            streamStart until endIdx
        }
        if (dataRange.isEmpty()) return ByteArray(0)
        val raw = body.substring(dataRange).toByteArray(Charsets.ISO_8859_1)
        val filter = Regex("/Filter\\s*/(\\w+)").find(dictText)?.groupValues?.get(1)
        return when (filter) {
            null, "Identity", "identity" -> raw
            "FlateDecode", "Fl" -> inflate(raw) ?: ByteArray(0)
            else -> ByteArray(0) // image/misc filters carry no extractable text
        }
    }

    private fun inflate(data: ByteArray): ByteArray? {
        if (data.isEmpty()) return ByteArray(0)
        fun attempt(nowrap: Boolean): ByteArray? = try {
            val out = ByteArrayOutputStream()
            InflaterInputStream(ByteArrayInputStream(data), Inflater(nowrap)).use { it.copyTo(out) }
            out.toByteArray()
        } catch (_: Exception) {
            null
        }
        return attempt(false) ?: attempt(true)
    }

    /** Extracts text tokens (Tj/'/"/TJ) from decoded content bytes. */
    private fun extractText(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val s = bytes.toString(Charsets.ISO_8859_1)
        val sb = StringBuilder()
        var pendingString: StringBuilder? = null
        var pendingArray: List<String> = emptyList()
        var i = 0
        val n = s.length

        while (i < n) {
            while (i < n && s[i] <= ' ') i++
            if (i >= n) break
            when (val c = s[i]) {
                '(' -> {
                    val (lit, next) = readLiteral(s, i)
                    pendingString = lit
                    pendingArray = emptyList()
                    i = next
                }
                '<' -> {
                    val nextGt = s.indexOf('>', i)
                    if (nextGt < 0) { i++; continue }
                    val inner = s.substring(i + 1, nextGt)
                    if (inner.startsWith("<")) {
                        val close = s.indexOf(">>", i + 1)
                        if (close < 0) { i++; continue }
                        i = close + 2
                    } else if (inner.isBlank() || inner.all { it in "0123456789abcdefABCDEF \t\r\n" }) {
                        pendingString = StringBuilder(hexToString(inner))
                        pendingArray = emptyList()
                        i = nextGt + 1
                    } else {
                        val close = s.indexOf(">>", i + 1)
                        if (close < 0) { i++; continue }
                        i = close + 2
                    }
                }
                '[' -> {
                    val close = findArrayEnd(s, i)
                    if (close < 0) { i++; continue }
                    pendingArray = parseArrayStrings(s, i, close)
                    pendingString = null
                    i = close + 1
                }
                '/', '\\' -> {
                    i++
                    while (i < n && s[i] > ' ' && s[i] != '/' && s[i] != '[' && s[i] != '(') i++
                }
                else -> {
                    val start = i
                    while (i < n && s[i] > ' ') i++
                    val token = s.substring(start, i)
                    when (token) {
                        "Tj", "'", "\"" -> pendingString?.let {
                            sb.append(it).append(' ')
                            pendingString = null
                        }
                        "TJ" -> if (pendingArray.isNotEmpty()) {
                            sb.append(pendingArray.joinToString("")).append(' ')
                            pendingArray = emptyList()
                        }
                    }
                }
            }
        }
        return sb.toString().trim()
    }

    private fun readLiteral(s: String, start: Int): Pair<StringBuilder, Int> {
        val out = StringBuilder()
        var i = start + 1
        var depth = 1
        while (i < s.length) {
            when (val c = s[i]) {
                '\\' -> {
                    i++
                    if (i >= s.length) break
                    when (val e = s[i]) {
                        'n' -> out.append('\n'); 'r' -> out.append('\r'); 't' -> out.append('\t')
                        'b' -> out.append('\b'); 'f' -> out.append('\u000C')
                        '(' -> out.append('('); ')' -> out.append(')'); '\\' -> out.append('\\')
                        '\r' -> { if (i + 1 < s.length && s[i + 1] == '\n') i++ }
                        '\n' -> Unit
                        in '0'..'7' -> {
                            var code = e - '0'
                            var k = 0
                            while (k < 2 && i + 1 < s.length && s[i + 1] in '0'..'7') {
                                i++
                                code = code * 8 + (s[i] - '0')
                                k++
                            }
                            out.append(code.toChar())
                        }
                        else -> out.append(e)
                    }
                    i++
                }
                '(' -> { depth++; out.append('('); i++ }
                ')' -> {
                    depth--
                    if (depth == 0) return out to (i + 1)
                    out.append(')')
                    i++
                }
                '\r' -> { if (i + 1 < s.length && s[i + 1] == '\n') i++; i++ }
                '\n' -> i++
                else -> { out.append(c); i++ }
            }
        }
        return out to i
    }

    private fun hexToString(hex: String): String {
        val clean = hex.filterNot { it == ' ' || it == '\t' || it == '\r' || it == '\n' }
        val sb = StringBuilder()
        var i = 0
        while (i + 1 < clean.length) {
            val hi = clean[i].digitToIntOrNull(16) ?: break
            val lo = clean[i + 1].digitToIntOrNull(16) ?: break
            sb.append(((hi shl 4) or lo).toChar())
            i += 2
        }
        return sb.toString()
    }

    private fun findArrayEnd(s: String, start: Int): Int {
        var depth = 1
        var i = start + 1
        while (i < s.length) {
            when (s[i]) {
                '[' -> depth++
                ']' -> { depth--; if (depth == 0) return i }
                '(' -> i = skipLiteral(s, i)
                '<' -> { val e = s.indexOf('>', i); if (e < 0) return -1; i = e }
            }
            i++
        }
        return -1
    }

    private fun skipLiteral(s: String, start: Int): Int {
        var i = start + 1
        var depth = 1
        while (i < s.length) {
            when (s[i]) {
                '\\' -> i++
                '(' -> depth++
                ')' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        return i
    }

    private fun parseArrayStrings(s: String, start: Int, end: Int): List<String> {
        val items = mutableListOf<String>()
        var i = start + 1
        while (i < end) {
            while (i < end && s[i] <= ' ') i++
            if (i >= end) break
            when (s[i]) {
                '(' -> {
                    val (lit, next) = readLiteral(s, i)
                    items += lit.toString()
                    i = next
                }
                '<' -> {
                    val e = s.indexOf('>', i)
                    if (e < 0 || e > end) break
                    val inner = s.substring(i + 1, e)
                    if (!inner.startsWith("<") && (inner.isBlank() || inner.all { it in "0123456789abcdefABCDEF \t\r\n" })) {
                        items += hexToString(inner)
                    }
                    i = e + 1
                }
                else -> while (i < end && s[i] > ' ') i++
            }
        }
        return items
    }
}