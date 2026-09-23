package com.prasbin.shadowlearn.data.extract

import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Real, byte-valid test fixtures for Phase 4 extractors — built entirely
 * from scratch (no copyrighted/sample assets). The PDF is a genuine
 * minimal PDF with FlateDecode streams; DOCX/PPTX are genuine OOXML zip
 * packages in the subset our extractors read.
 */
object Fixtures {

    fun txtBytes(text: String = "hello extraction world\nsecond line of text\n"): ByteArray =
        text.toByteArray(Charsets.UTF_8)

    fun txtLongBytes(): ByteArray =
        ("alpha bravo charlie ").repeat(2700).toByteArray(Charsets.UTF_8)

    fun whitespaceTxtBytes(): ByteArray = "\n\n   \n\t\n".toByteArray(Charsets.UTF_8)

    private fun flate(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        DeflaterOutputStream(out).use { it.write(text.toByteArray(Charsets.ISO_8859_1)) }
        return out.toByteArray()
    }

    /** Two-page PDF with FlateDecode content streams (Tj, TJ, hex string). */
    fun pdfPages(): ByteArray {
        val page1 = flate(
            "BT /F1 12 Tf 72 720 Td (Neural networks gradient descent) Tj " +
                "<6e657572616c> Tj 0 -24 Td (backpropagation) Tj ET"
        )
        val page2 = flate(
            "BT /F1 12 Tf 72 700 Td [(optimi) -20 (zation) ( algorithms)] TJ ET"
        )
        val ascii = StringBuilder()
            .append("%PDF-1.4\n")
            .append("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
            .append("2 0 obj\n<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>\nendobj\n")
            .append("3 0 obj\n<< /Type /Page /Parent 2 0 R /Contents 5 0 R >>\nendobj\n")
            .append("4 0 obj\n<< /Type /Page /Parent 2 0 R /Contents 6 0 R >>\nendobj\n")
            .append("5 0 obj\n<< /Length ${page1.size} /Filter /FlateDecode >>\nstream\n")
            .toString()
        val middle = byteArrayOf().concatAscii(ascii)
        val rest = StringBuilder()
            .append("\nendstream\nendobj\n")
            .append("6 0 obj\n<< /Length ${page2.size} /Filter /FlateDecode >>\nstream\n")
            .toString()
        val tail = byteArrayOf()
            .concatAscii(rest)
            .concat(page2)
            .concatAscii("\nendstream\nendobj\ntrailer\n<< /Root 1 0 R >>\n%%EOF")
        return middle.concat(page1).concat(tail)
    }

    /** PDF whose pages carry no text operators (scanned-image-like). */
    fun pdfNoText(): ByteArray {
        val content = flate("q Q")
        val ascii = StringBuilder()
            .append("%PDF-1.4\n")
            .append("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
            .append("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n")
            .append("3 0 obj\n<< /Type /Page /Parent 2 0 R /Contents 4 0 R >>\nendobj\n")
            .append("4 0 obj\n<< /Length ${content.size} /Filter /FlateDecode >>\nstream\n")
            .toString()
            .toByteArray(Charsets.ISO_8859_1)
        val tail = byteArrayOf()
            .concat(content)
            .concatAscii("\nendstream\nendobj\ntrailer\n<< /Root 1 0 R >>\n%%EOF")
        return ascii.concat(tail)
    }

    /** DOCX with three paragraphs; a page break separates paragraph 2 from page 3. */
    fun docxPages(): ByteArray {
        val xml =
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:body>
    <w:p><w:r><w:t>Alpha beta gamma</w:t></w:r></w:p>
    <w:p><w:r><w:t>Delta</w:t></w:r></w:p>
    <w:p><w:r><w:t>Epsilon zeta</w:t></w:r><w:r><w:br w:type="page"/></w:r></w:p>
    <w:p><w:r><w:t>Eta theta</w:t></w:r></w:p>
  </w:body>
</w:document>"""
        return zipOf("word/document.xml" to xml.toByteArray(Charsets.UTF_8))
    }

    fun docxMissingPartBytes(): ByteArray = zipOf("word/notHere.xml" to "<x/>".toByteArray())

    /** PPTX with two slides, each a couple of text runs. */
    fun pptxSlides(): ByteArray {
        val slide1 =
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
       xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
  <p:cSld><p:spTree>
    <p:sp><p:txBody>
      <a:p><a:r><a:t>Neural networks</a:t></a:r></a:p>
      <a:p><a:r><a:t>layer</a:t></a:r><a:r><a:t> one</a:t></a:r></a:p>
    </p:txBody></p:sp>
  </p:spTree></p:cSld>
</p:sld>"""
        val slide2 =
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
       xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
  <p:cSld><p:spTree>
    <p:sp><p:txBody>
      <a:p><a:r><a:t>backpropagation</a:t></a:r></a:p>
    </p:txBody></p:sp>
  </p:spTree></p:cSld>
</p:sld>"""
        return zipOf(
            "ppt/slides/slide1.xml" to slide1.toByteArray(Charsets.UTF_8),
            "ppt/slides/slide2.xml" to slide2.toByteArray(Charsets.UTF_8)
        )
    }

    fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            entries.forEach { (name, bytes) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun ByteArray.concatAscii(ascii: String): ByteArray = concat(ascii.toByteArray(Charsets.ISO_8859_1))

    private fun ByteArray.concat(other: ByteArray): ByteArray {
        val out = ByteArray(size + other.size)
        System.arraycopy(this, 0, out, 0, size)
        System.arraycopy(other, 0, out, size, other.size)
        return out
    }
}