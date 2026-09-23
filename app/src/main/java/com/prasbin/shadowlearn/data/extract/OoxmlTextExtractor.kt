package com.prasbin.shadowlearn.data.extract

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * OOXML (DOCX / PPTX) extractor. Reads the package ZIP and extracts the
 * text-role content into sections:
 *
 * - DOCX: `word/document.xml`; sections end on explicit page breaks
 *   (`<w:br w:type="page"/>`), each with a 1-based best-effort page number
 *   (DOCX page layout is rendering-dependent; page breaks are the honest
 *   structural proxy — documented in docs/ARCHITECTURE.md).
 * - PPTX: every `ppt/slides/slideN.xml` becomes one section with its slide
 *   number as the page reference; slides ordered numerically.
 *
 * Secure parsing: DOCTYPE is disallowed and external entities disabled
 * where the platform parser supports those JAXP features (always on the
 * JVM, best-effort on Android — its parser does not resolve external
 * entities by default). Structure is DOM-traversed in document order so
 * run order is preserved; run/paragraph breaks become whitespace/newlines.
 */
object OoxmlTextExtractor {

    enum class Kind { DOCX, PPTX }

    private const val DOCX_DOCUMENT_PART = "word/document.xml"
    private const val PPTX_SLIDE_PREFIX = "ppt/slides/slide"

    private val PPTX_SLIDE_NAME = Regex("ppt/slides/slide(\\d+)\\.xml")

    private const val MAX_XML_PART_BYTES = ExtractionFormat.MAX_EXTRACT_BYTES

    private fun builder(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            // XXE hardening is best-effort: JVM parsers support these JAXP
            // features; Android's built-in parser rejects some of them (it
            // does not resolve external entities by default anyway).
            setFeatureBestEffort("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeatureBestEffort("http://xml.org/sax/features/external-general-entities", false)
            setFeatureBestEffort("http://xml.org/sax/features/external-parameter-entities", false)
            runCatching { setExpandEntityReferences(false) }
        }

    private fun DocumentBuilderFactory.setFeatureBestEffort(feature: String, value: Boolean) {
        runCatching { setFeature(feature, value) }
    }

    fun extract(bytes: ByteArray, kind: Kind): ExtractedDocument {
        if (bytes.size > ExtractionFormat.MAX_EXTRACT_BYTES) {
            throw ExtractionException("File too large for extraction (> ${ExtractionFormat.MAX_EXTRACT_BYTES} bytes).")
        }
        return when (kind) {
            Kind.DOCX -> extractDocx(bytes)
            Kind.PPTX -> extractPptx(bytes)
        }
    }

    private fun extractDocx(bytes: ByteArray): ExtractedDocument {
        val xml = readPart(bytes, DOCX_DOCUMENT_PART, DOCX_DOCUMENT_PART)
            ?: throw ExtractionException("Not a valid DOCX: missing word/document.xml part.")
        val document = try {
            builder().newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        } catch (e: Exception) {
            throw ExtractionException("Invalid DOCX XML: ${e.message}")
        }
        val sections = mutableListOf<ExtractedSection>()
        var page = 1L
        val buf = StringBuilder()
        fun flush() {
            sections += ExtractedSection(pageNumber = page, text = buf.toString())
            buf.setLength(0)
        }
        fun pageBreak() {
            flush()
            page++
        }
        fun walk(node: Node?) {
            if (node == null || node.nodeType != Node.ELEMENT_NODE) return
            when (node.nodeName) {
                "w:t" -> buf.append(node.textContent)
                "w:br" -> if ((node as Element).getAttribute("w:type") == "page") pageBreak() else buf.append('\n')
                "w:cr" -> buf.append('\n')
                "w:tab" -> buf.append('\t')
                "w:p" -> {
                    node.childNodes.let { for (i in 0 until it.length) walk(it.item(i)) }
                    buf.append('\n')
                }
                else -> node.childNodes.let { for (i in 0 until it.length) walk(it.item(i)) }
            }
        }
        walk(document.documentElement)
        flush()
        return ExtractedDocument(sections)
    }

    private fun extractPptx(bytes: ByteArray): ExtractedDocument {
        val slides = mutableListOf<Pair<Long, String>>()
        var entry: ZipEntry? = null
        val input = ZipInputStream(ByteArrayInputStream(bytes))
        do {
            entry = input.nextEntry
            if (entry == null) break
            val m = PPTX_SLIDE_NAME.matchEntire(entry.name)
            if (m != null) {
                val xml = readEntry(input, entry.size, entry.name)
                slides += m.groupValues[1].toLong() to String(xml, Charsets.UTF_8)
            }
        } while (entry != null)
        input.close()

        if (slides.isEmpty()) {
            throw ExtractionException("Not a valid PPTX: no slides found.")
        }
        slides.sortBy { it.first }
        val sections = slides.map { (slideNo, xml) ->
            val document = try {
                builder().newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
            } catch (e: Exception) {
                throw ExtractionException("Invalid PPTX slide XML (slide$slideNo): ${e.message}")
            }
            val buf = StringBuilder()
            fun walk(node: Node?) {
                if (node == null || node.nodeType != Node.ELEMENT_NODE) return
                when (node.nodeName) {
                    "a:t" -> buf.append(node.textContent)
                    "a:br" -> buf.append('\n')
                    "a:tab" -> buf.append('\t')
                    "a:p" -> {
                        node.childNodes.let { for (i in 0 until it.length) walk(it.item(i)) }
                        buf.append('\n')
                    }
                    else -> node.childNodes.let { for (i in 0 until it.length) walk(it.item(i)) }
                }
            }
            walk(document.documentElement)
            ExtractedSection(pageNumber = slideNo, text = buf.toString())
        }
        return ExtractedDocument(sections)
    }

    /** Reads one named zip part fully, returning its decoded ISO string. */
    private fun readPart(bytes: ByteArray, name: String, label: String): String? {
        val input = ZipInputStream(ByteArrayInputStream(bytes))
        var entry = input.nextEntry
        var found: ByteArray? = null
        while (entry != null) {
            if (entry.name == name) {
                found = readEntry(input, entry.size, label)
                break
            }
            entry = input.nextEntry
        }
        input.close()
        return found?.toString(Charsets.UTF_8)
    }

    private fun readEntry(input: ZipInputStream, declaredSize: Long, label: String): ByteArray {
        if (declaredSize > MAX_XML_PART_BYTES) {
            throw ExtractionException("XML part too large for extraction ($label).")
        }
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var total = 0L
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            total += n
            if (total > MAX_XML_PART_BYTES) {
                throw ExtractionException("XML part too large for extraction ($label).")
            }
            out.write(chunk, 0, n)
        }
        return out.toByteArray()
    }
}