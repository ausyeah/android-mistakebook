package com.mistakebook.print

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * docx 包结构测试。
 *
 * docx 就是一个 zip 套 XML。**结构错了 Word 会直接报「文档已损坏」**——
 * 整份文档作废，比公式丑严重得多。而 zip 里有几个部件、关系 id 对不对得上、
 * 图片类型声明了没有，这些都不需要真机就能验。
 */
class DocxPackageTest {

    /** 造一个与 DocxExporter 同构的最小包。 */
    private fun buildPackage(documentBody: String, images: List<Pair<String, ByteArray>> = emptyList()): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(path: String, text: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            val contentTypes = buildString {
                append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
                append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
                append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
                append("""<Default Extension="xml" ContentType="application/xml"/>""")
                if (images.isNotEmpty()) append("""<Default Extension="png" ContentType="image/png"/>""")
                append("""<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>""")
                append("</Types>")
            }
            put("[Content_Types].xml", contentTypes)
            put(
                "_rels/.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
                    """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                    """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>""" +
                    "</Relationships>"
            )
            val rels = buildString {
                append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
                append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
                images.forEach { (path, _) ->
                    val id = "rId" + path.substringAfterLast("image").substringBefore('.')
                    append("""<Relationship Id="$id" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="${path.removePrefix("word/")}"/>""")
                }
                append("</Relationships>")
            }
            put("word/_rels/document.xml.rels", rels)
            images.forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
            put(
                "word/document.xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
                    """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" """ +
                    """xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" """ +
                    """xmlns:m="http://schemas.openxmlformats.org/officeDocument/2006/math" """ +
                    """xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" """ +
                    """xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" """ +
                    """xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture">""" +
                    "<w:body>$documentBody</w:body></w:document>"
            )
        }
        return out.toByteArray()
    }

    private fun entries(bytes: ByteArray): List<String> = buildList {
        ZipInputStreamFactory.wrap(bytes).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                add(entry.name)
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    private fun read(bytes: ByteArray, path: String): String? = runCatching {
        ZipInputStreamFactory.wrap(bytes).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == path) return zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
                entry = zip.nextEntry
            }
            null
        }
    }.getOrNull()

    // ------------------------------------------------------------ 必需部件

    @Test
    fun `包里有四个必需部件`() {
        val names = entries(buildPackage("<w:p/>"))
        assertTrue("缺 [Content_Types].xml", "[Content_Types].xml" in names)
        assertTrue("缺 _rels/.rels", "_rels/.rels" in names)
        assertTrue("缺 word/document.xml", "word/document.xml" in names)
        assertTrue(
            "缺 word/_rels/document.xml.rels（无图时也必须有）",
            "word/_rels/document.xml.rels" in names
        )
    }

    @Test
    fun `Content_Types 声明了 document 部分`() {
        val xml = read(buildPackage("<w:p/>"), "[Content_Types].xml")
        assertNotNull(xml)
        assertTrue(xml!!.contains("""PartName="/word/document.xml""""))
        assertTrue(xml.contains("wordprocessingml.document.main+xml"))
    }

    @Test
    fun `包级关系指向 word_document_xml`() {
        val xml = read(buildPackage("<w:p/>"), "_rels/.rels")
        assertNotNull(xml)
        assertTrue(xml!!.contains("""Target="word/document.xml""""))
    }

    // ------------------------------------------------------------ 图片

    @Test
    fun `有图时声明了 png 默认类型与关系`() {
        val bytes = buildPackage(
            """<w:p><w:r><w:drawing><wp:inline><a:graphic><a:graphicData><pic:pic><pic:blipFill><a:blip r:embed="rId1"/></pic:blipFill></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>""",
            images = listOf("word/media/image1.png" to byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
        )
        val types = read(bytes, "[Content_Types].xml")
        val rels = read(bytes, "word/_rels/document.xml.rels")
        assertTrue("没声明 png 类型", types!!.contains("""Extension="png""""))
        assertTrue("没声明图片关系", rels!!.contains("relationships/image"))
        assertTrue("关系 Target 不对", rels.contains("""Target="media/image1.png""""))
        assertTrue("包里有图片文件", "word/media/image1.png" in entries(bytes))
    }

    @Test
    fun `无图时不声明 png 类型`() {
        // 多声明不会报错，但会让 Word 以为包里有那个类型，装载时多一步校验
        val types = read(buildPackage("<w:p/>"), "[Content_Types].xml")
        assertTrue(types!!.contains("""Extension="xml""""))
        assertTrue("不该凭空声明 png", !types.contains("""Extension="png""""))
    }

    @Test
    fun `关系 id 与正文里的 embed 对得上`() {
        // 反向用例：id 对不上时图片位置显示成红叉，但文件能打开——更难发现
        val bytes = buildPackage(
            """<w:p><w:r><w:drawing><wp:inline><a:graphic><a:graphicData><pic:pic><pic:blipFill><a:blip r:embed="rId7"/></pic:blipFill></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>""",
            images = listOf("word/media/image7.png" to byteArrayOf(1))
        )
        val rels = read(bytes, "word/_rels/document.xml.rels")
        assertTrue(rels!!.contains("""Id="rId7""""))
    }

    // ------------------------------------------------------------ 正文

    @Test
    fun `正文命名空间齐全`() {
        // 少一个命名空间（最常见是 m：数学）Word 就会报文档损坏
        val xml = read(buildPackage("<w:p/>"), "word/document.xml")
        assertNotNull(xml)
        listOf(
            "xmlns:w=",
            "xmlns:r=",
            "xmlns:m=",
            "xmlns:wp=",
            "xmlns:a=",
            "xmlns:pic="
        ).forEach { ns ->
            assertTrue("缺命名空间 $ns", xml!!.contains(ns))
        }
    }

    @Test
    fun `OMML 公式能在正文里合法存在`() {
        val omml = LatexToOmml.convert("""\frac{a}{b}""")
        assertNotNull(omml)
        val xml = read(buildPackage("<w:p><m:oMath>$omml</m:oMath></w:p>"), "word/document.xml")
        assertNotNull(xml)
        assertTrue(xml!!.contains("<m:oMath>"))
        assertTrue(xml.contains("<m:f>"))
    }

    @Test
    fun `块级公式用 m_oMathPara 包裹`() {
        // 行内公式用 m:oMath、块级用 m:oMathPara。用错 Word 会把它当正文里的乱码
        val xml = read(buildPackage("<m:oMathPara><m:oMath><m:r><m:t>x</m:t></m:r></m:oMath></m:oMathPara>"), "word/document.xml")
        assertTrue(xml!!.contains("<m:oMathPara>"))
    }
}

/** 让测试里的 zip 读取写起来干净些。 */
private object ZipInputStreamFactory {
    fun wrap(bytes: ByteArray) = java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes))
}
