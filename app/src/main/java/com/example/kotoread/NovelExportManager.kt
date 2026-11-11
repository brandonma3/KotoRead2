package com.example.kotoread

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs

/**
 * 匯出雙語小說電子書格式選項
 */
enum class ExportFormat(val extension: String, val mimeType: String, val label: String) {
    EPUB("epub", "application/epub+zip", "EPUB 雙語電子書（相容各大閱讀器）"),
    TXT_DUAL("txt", "text/plain", "TXT 中日雙語段落對照檔"),
    TXT_ZH_ONLY("txt", "text/plain", "TXT 純中文譯文文字檔")
}

/**
 * Android 小說電子書生成器：
 * - 支援 Android 原生 Canvas 動態繪製輕小說風格漸層幾何封面（含書名、作者、章節數、裝飾幾何金線）
 * - 支援標準 EPUB 3.0 打包（遵循 OEBPS 規範、mimetype 無壓縮置頂、封底封面宣告、含中日雙語排版 CSS）
 * - 支援純文字 TXT 快速匯出（含作品中日文標題、作者、章節段落標籤）
 * - 自動建立 Android FileProvider 內容 URI 並啟動原生分享對話框 (ACTION_SEND)
 */
object NovelExportManager {

    /**
     * 匯出章節清單為檔案並觸發系統原生分享選單
     *
     * @param chapters 要匯出的章節清單（可單章或整部書架中所有已快取章節）
     */
    suspend fun exportAndShare(
        context: Context,
        seriesTitle: String,
        author: String,
        chapters: List<NovelChapter>,
        format: ExportFormat
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            if (chapters.isEmpty()) {
                throw IllegalArgumentException("沒有可匯出的章節內容")
            }

            val sanitizedTitle = seriesTitle.replace("[\\\\/:*?\"<>|]".toRegex(), "_").trim()
            val safeFileName = if (sanitizedTitle.isBlank()) "novel_export" else sanitizedTitle

            val exportDir = File(context.cacheDir, "exported_novels").apply { mkdirs() }
            val outputFile = File(exportDir, "${safeFileName}.${format.extension}")

            when (format) {
                ExportFormat.EPUB -> generateEpub(outputFile, seriesTitle, author, chapters)
                ExportFormat.TXT_DUAL -> generateTxt(outputFile, seriesTitle, author, chapters, isDual = true)
                ExportFormat.TXT_ZH_ONLY -> generateTxt(outputFile, seriesTitle, author, chapters, isDual = false)
            }

            // 在主線程啟動 Android 原生分享 Intent
            withContext(Dispatchers.Main) {
                shareExportedFile(context, outputFile, format.mimeType, seriesTitle)
            }

            outputFile
        }
    }

    /**
     * 使用 Android 原生 Canvas 動態繪製日系輕小說風格的精緻書籍封面：
     * - 依據書名雜湊自動挑選和諧的高級深邃漸層（深夜極光、星辰薄暮、魔導赤緋、幽暗翠玉）
     * - 繪製現代幾何裝飾環、日系方格襯線標記與古典邊框
     * - 利用 StaticLayout 自動處理長書名優雅換行與置中排版
     * - 輸出高解析度 1200 x 1800 (2:3 黃金比例) JPEG 圖檔位元組陣列
     */
    fun generateNovelCoverBitmapBytes(
        title: String,
        author: String,
        chapterCount: Int,
        siteName: String = "言ノ葉 Reader"
    ): ByteArray {
        val width = 1200
        val height = 1800
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 依書名雜湊挑選漸層配色主題
        val hash = abs(title.hashCode())
        val palettes = listOf(
            // 1. 深夜魔導（暗靛青 -> 暮夜紫）
            Triple(Color.rgb(15, 23, 42), Color.rgb(30, 27, 75), Color.rgb(79, 70, 229)),
            // 2. 星辰極光（黑曜岩 -> 祖母綠墨青）
            Triple(Color.rgb(6, 30, 36), Color.rgb(10, 47, 43), Color.rgb(13, 148, 136)),
            // 3. 緋紅術式（暗赤褐 -> 深紅酒）
            Triple(Color.rgb(38, 10, 19), Color.rgb(68, 15, 30), Color.rgb(225, 29, 72)),
            // 4. 黃昏星輝（深沉鐵灰 -> 幽藍）
            Triple(Color.rgb(19, 23, 34), Color.rgb(28, 38, 64), Color.rgb(59, 130, 246))
        )
        val chosenPalette = palettes[hash % palettes.size]

        // 1. 繪製精緻的雙層角度放射漸層背景
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, width.toFloat(), height.toFloat(),
                intArrayOf(chosenPalette.first, chosenPalette.second, chosenPalette.third),
                floatArrayOf(0.0f, 0.65f, 1.0f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // 2. 繪製裝飾性幾何光環與半透明弧線（日系輕小說封面標誌性符號）
        val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.argb(45, 255, 255, 255)
        }
        canvas.drawCircle(width * 0.78f, height * 0.28f, 320f, circlePaint)
        canvas.drawCircle(width * 0.78f, height * 0.28f, 340f, circlePaint.apply { strokeWidth = 1.5f })
        canvas.drawCircle(width * 0.22f, height * 0.72f, 260f, circlePaint)

        // 3. 繪製外圍燙金古典邊框
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            color = Color.argb(80, 255, 255, 255)
        }
        val margin = 64f
        canvas.drawRect(margin, margin, width - margin, height - margin, borderPaint)
        canvas.drawRect(margin + 12f, margin + 12f, width - margin - 12f, height - margin - 12f, borderPaint.apply {
            strokeWidth = 1.0f
            color = Color.argb(40, 255, 255, 255)
        })

        // 4. 頂部徽章與系列來源標記
        val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(55, 255, 255, 255)
        }
        val badgeRect = RectF(width / 2f - 240f, margin + 60f, width / 2f + 240f, margin + 120f)
        canvas.drawRoundRect(badgeRect, 28f, 28f, badgeBgPaint)

        val badgeTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(226, 232, 240)
            textSize = 28f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            letterSpacing = 0.15f
        }
        canvas.drawText(
            "✦ LIGHT NOVEL EDITION ✦",
            width / 2f,
            margin + 100f,
            badgeTextPaint
        )

        // 5. 中央主書名（透過 StaticLayout 自動計算最美換行與字距）
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 72f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            letterSpacing = 0.05f
            setShadowLayer(16f, 0f, 6f, Color.argb(160, 0, 0, 0))
        }

        val titleWidth = (width - margin * 2 - 120f).toInt()
        val titleLayout = StaticLayout.Builder.obtain(title, 0, title.length, titlePaint, titleWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(12f, 1.15f)
            .build()

        val titleY = height * 0.38f - (titleLayout.height / 2f)
        canvas.save()
        canvas.translate((width - titleWidth) / 2f, titleY)
        titleLayout.draw(canvas)
        canvas.restore()

        // 6. 書名下方分隔星芒與裝飾線
        val sepY = titleY + titleLayout.height + 60f
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = 2f
            color = Color.argb(120, 255, 255, 255)
        }
        canvas.drawLine(width / 2f - 180f, sepY, width / 2f - 40f, sepY, linePaint)
        canvas.drawLine(width / 2f + 40f, sepY, width / 2f + 180f, sepY, linePaint)

        val starPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(253, 224, 71) // 淡金星芒
            textSize = 32f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("◆", width / 2f, sepY + 10f, starPaint)

        // 7. 作者資訊
        val authorPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(226, 232, 240)
            textSize = 42f
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
            letterSpacing = 0.08f
            setShadowLayer(8f, 0f, 2f, Color.argb(120, 0, 0, 0))
        }
        val safeAuthor = if (author.isBlank()) "佚名" else author
        canvas.drawText("著：$safeAuthor", width / 2f, sepY + 80f, authorPaint)

        // 8. 底部資訊欄（章節數收錄與應用程式標章）
        val footerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(190, 203, 213, 225)
            textSize = 30f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
            letterSpacing = 0.06f
        }
        canvas.drawText(
            "全 $chapterCount 話收錄 • 中日雙語對照版",
            width / 2f,
            height - margin - 110f,
            footerPaint
        )

        val appMarkPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(120, 148, 163, 184)
            textSize = 24f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("KotoRead • $siteName", width / 2f, height - margin - 60f, appMarkPaint)

        // 輸出高畫質 JPEG 位元組流
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 92, outputStream)
        bitmap.recycle()
        return outputStream.toByteArray()
    }

    /**
     * 生成乾淨簡約的 TXT 文字檔
     */
    private fun generateTxt(
        targetFile: File,
        seriesTitle: String,
        author: String,
        chapters: List<NovelChapter>,
        isDual: Boolean
    ) {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val timestamp = sdf.format(Date())

        targetFile.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
            writer.write("================================================================\n")
            writer.write("書名：$seriesTitle\n")
            writer.write("作者：${author.ifBlank { "佚名" }}\n")
            writer.write("匯出日期：$timestamp\n")
            writer.write("格式：${if (isDual) "中日雙語對照" else "純中文譯文"}\n")
            writer.write("章節總數：${chapters.size} 話\n")
            writer.write("================================================================\n\n")

            chapters.forEachIndexed { chapIdx, chap ->
                val displayZhTitle = chap.chapterTitleZh.ifBlank { chap.chapterTitleJp }
                writer.write("----------------------------------------------------------------\n")
                writer.write("第 ${chapIdx + 1} 話：$displayZhTitle\n")
                if (isDual && chap.chapterTitleZh.isNotBlank()) {
                    writer.write("（日文原名：${chap.chapterTitleJp}）\n")
                }
                writer.write("來源網址：${chap.sourceUrl}\n")
                writer.write("----------------------------------------------------------------\n\n")

                chap.paragraphs.forEachIndexed { pIdx, p ->
                    val zhText = p.zhTranslated.ifBlank { p.jpPlain }
                    if (isDual) {
                        writer.write("[段落 ${pIdx + 1} 原文] ${p.jpPlain}\n")
                        writer.write("[段落 ${pIdx + 1} 譯文] $zhText\n\n")
                    } else {
                        writer.write("$zhText\n\n")
                    }
                }
                writer.write("\n")
            }
        }
    }

    /**
     * 依據 IDPF / W3C EPUB 3.0 規範打包完整 .epub 檔案
     *
     * 【EPUB 結構規範要點】
     * 1. 第一個 ZipEntry 必須是名為 "mimetype" 的檔案，內容為 "application/epub+zip"，
     *    且必須以 STORED（無壓縮）方式存入，不可加額外 header。
     * 2. OEBPS/Images/cover.jpg 動態利用 Canvas 繪製輕小說漸層封面並置入。
     * 3. OEBPS/cover.xhtml 渲染全屏封面頁，並於 content.opf 宣告 `properties="cover-image"`。
     * 4. META-INF/container.xml 指向 OEBPS/content.opf。
     * 5. OEBPS/toc.xhtml 與 toc.ncx 相容新舊 EPUB 閱讀器。
     * 6. OEBPS/chapter_*.xhtml 包含排版後的雙語 HTML（日文原文以縮小灰字折疊或對齊）。
     */
    private fun generateEpub(
        targetFile: File,
        seriesTitle: String,
        author: String,
        chapters: List<NovelChapter>
    ) {
        val bookId = "urn:uuid:" + UUID.nameUUIDFromBytes(seriesTitle.toByteArray())
        val safeAuthor = author.ifBlank { "佚名" }
        val sdfIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val dateIso = sdfIso.format(Date())

        // 1. 動態繪製封面 JPEG 圖片
        val coverBytes = generateNovelCoverBitmapBytes(
            title = seriesTitle,
            author = safeAuthor,
            chapterCount = chapters.size,
            siteName = chapters.firstOrNull()?.siteName ?: "言ノ葉 Reader"
        )

        ZipOutputStream(FileOutputStream(targetFile)).use { zip ->

            // 2. 寫入置頂且無壓縮的 mimetype 檔案
            val mimetypeBytes = "application/epub+zip".toByteArray(StandardCharsets.US_ASCII)
            val mimetypeEntry = ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mimetypeBytes.size.toLong()
                compressedSize = mimetypeBytes.size.toLong()
                crc = CRC32().apply { update(mimetypeBytes) }.value
            }
            zip.putNextEntry(mimetypeEntry)
            zip.write(mimetypeBytes)
            zip.closeEntry()

            // 3. 寫入 META-INF/container.xml
            val containerXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
            """.trimIndent()
            writeCompressedEntry(zip, "META-INF/container.xml", containerXml)

            // 4. 寫入封面圖片檔案 OEBPS/Images/cover.jpg
            writeBinaryEntry(zip, "OEBPS/Images/cover.jpg", coverBytes)

            // 5. 寫入獨立封面 XHTML 頁面 OEBPS/cover.xhtml
            val coverXhtml = """
                <?xml version="1.0" encoding="utf-8"?>
                <!DOCTYPE html>
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="zh-TW">
                <head>
                  <title>封面</title>
                  <style type="text/css">
                    body { margin: 0; padding: 0; text-align: center; background-color: #0f172a; }
                    img { max-width: 100%; max-height: 100vh; height: auto; object-fit: contain; }
                  </style>
                </head>
                <body>
                  <div epub:type="cover">
                    <img src="Images/cover.jpg" alt="Cover: ${escapeXml(seriesTitle)}"/>
                  </div>
                </body>
                </html>
            """.trimIndent()
            writeCompressedEntry(zip, "OEBPS/cover.xhtml", coverXhtml)

            // 6. 寫入 OEBPS/style.css（專為輕小說雙語閱讀調校之字體與間距）
            val css = """
                body {
                  font-family: serif;
                  line-height: 1.85;
                  margin: 5%;
                  color: #1a1a1a;
                }
                h1 {
                  font-size: 1.4em;
                  border-bottom: 2px solid #6366f1;
                  padding-bottom: 0.3em;
                  margin-top: 1em;
                  margin-bottom: 1.2em;
                  color: #1e1b4b;
                }
                .chapter-meta {
                  font-size: 0.8em;
                  color: #64748b;
                  margin-bottom: 1.5em;
                }
                .para-box {
                  margin-bottom: 1.2em;
                }
                .para-zh {
                  font-size: 1.05em;
                  font-weight: 500;
                  color: #0f172a;
                  margin-bottom: 0.25em;
                }
                .para-jp {
                  font-size: 0.85em;
                  color: #64748b;
                  border-left: 2px solid #cbd5e1;
                  padding-left: 0.6em;
                  line-height: 1.6;
                }
                ruby rt {
                  font-size: 0.6em;
                  color: #94a3b8;
                }
            """.trimIndent()
            writeCompressedEntry(zip, "OEBPS/style.css", css)

            // 7. 寫入各章節 XHTML 檔案
            chapters.forEachIndexed { index, chapter ->
                val displayTitle = chapter.chapterTitleZh.ifBlank { chapter.chapterTitleJp }
                val sb = StringBuilder()
                sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
                sb.append("<!DOCTYPE html>\n")
                sb.append("<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\" xml:lang=\"zh-TW\">\n")
                sb.append("<head>\n")
                sb.append("  <title>${escapeXml(displayTitle)}</title>\n")
                sb.append("  <link rel=\"stylesheet\" type=\"text/css\" href=\"style.css\"/>\n")
                sb.append("</head>\n<body>\n")
                sb.append("  <h1>${escapeXml(displayTitle)}</h1>\n")
                sb.append("  <div class=\"chapter-meta\">來源：${escapeXml(chapter.siteName)} • 作者：${escapeXml(safeAuthor)}</div>\n")

                chapter.paragraphs.forEach { p ->
                    val zh = p.zhTranslated.ifBlank { p.jpPlain }
                    sb.append("  <div class=\"para-box\">\n")
                    sb.append("    <div class=\"para-zh\">${escapeXml(zh)}</div>\n")
                    sb.append("    <div class=\"para-jp\">${escapeXml(p.jpWithRubyText)}</div>\n")
                    sb.append("  </div>\n")
                }

                sb.append("</body>\n</html>")
                writeCompressedEntry(zip, "OEBPS/chapter_${index + 1}.xhtml", sb.toString())
            }

            // 8. 寫入導覽檔 OEBPS/toc.xhtml (EPUB 3 Nav)
            val navSb = StringBuilder()
            navSb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
            navSb.append("<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\">\n")
            navSb.append("<head><title>目錄</title><link rel=\"stylesheet\" type=\"text/css\" href=\"style.css\"/></head>\n")
            navSb.append("<body>\n  <nav epub:type=\"toc\" id=\"toc\">\n    <h1>目錄</h1>\n    <ol>\n")
            chapters.forEachIndexed { i, c ->
                val title = c.chapterTitleZh.ifBlank { c.chapterTitleJp }
                navSb.append("      <li><a href=\"chapter_${i + 1}.xhtml\">${escapeXml(title)}</a></li>\n")
            }
            navSb.append("    </ol>\n  </nav>\n</body>\n</html>")
            writeCompressedEntry(zip, "OEBPS/toc.xhtml", navSb.toString())

            // 9. 寫入相容 EPUB 2 的 OEBPS/toc.ncx
            val ncxSb = StringBuilder()
            ncxSb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            ncxSb.append("<ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\">\n")
            ncxSb.append("  <head>\n    <meta name=\"dtb:uid\" content=\"$bookId\"/>\n  </head>\n")
            ncxSb.append("  <docTitle><text>${escapeXml(seriesTitle)}</text></docTitle>\n")
            ncxSb.append("  <navMap>\n")
            chapters.forEachIndexed { i, c ->
                val title = c.chapterTitleZh.ifBlank { c.chapterTitleJp }
                ncxSb.append("    <navPoint id=\"navPoint-${i + 1}\" playOrder=\"${i + 1}\">\n")
                ncxSb.append("      <navLabel><text>${escapeXml(title)}</text></navLabel>\n")
                ncxSb.append("      <content src=\"chapter_${i + 1}.xhtml\"/>\n")
                ncxSb.append("    </navPoint>\n")
            }
            ncxSb.append("  </navMap>\n</ncx>")
            writeCompressedEntry(zip, "OEBPS/toc.ncx", ncxSb.toString())

            // 10. 寫入核心清單檔 OEBPS/content.opf（正確宣告 cover-image 與 cover.xhtml）
            val opfSb = StringBuilder()
            opfSb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
            opfSb.append("<package xmlns=\"http://www.idpf.org/2007/opf\" unique-identifier=\"BookId\" version=\"3.0\">\n")
            opfSb.append("  <metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n")
            opfSb.append("    <dc:identifier id=\"BookId\">$bookId</dc:identifier>\n")
            opfSb.append("    <dc:title>${escapeXml(seriesTitle)}</dc:title>\n")
            opfSb.append("    <dc:creator>$safeAuthor</dc:creator>\n")
            opfSb.append("    <dc:language>zh-TW</dc:language>\n")
            opfSb.append("    <dc:language>ja</dc:language>\n")
            opfSb.append("    <meta property=\"dcterms:modified\">$dateIso</meta>\n")
            opfSb.append("    <meta name=\"cover\" content=\"cover_image\"/>\n")
            opfSb.append("  </metadata>\n")
            opfSb.append("  <manifest>\n")
            opfSb.append("    <item id=\"cover_page\" href=\"cover.xhtml\" media-type=\"application/xhtml+xml\"/>\n")
            opfSb.append("    <item id=\"cover_image\" href=\"Images/cover.jpg\" media-type=\"image/jpeg\" properties=\"cover-image\"/>\n")
            opfSb.append("    <item id=\"toc\" href=\"toc.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>\n")
            opfSb.append("    <item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>\n")
            opfSb.append("    <item id=\"css\" href=\"style.css\" media-type=\"text/css\"/>\n")
            chapters.forEachIndexed { i, _ ->
                opfSb.append("    <item id=\"chap_${i + 1}\" href=\"chapter_${i + 1}.xhtml\" media-type=\"application/xhtml+xml\"/>\n")
            }
            opfSb.append("  </manifest>\n")
            opfSb.append("  <spine toc=\"ncx\">\n")
            opfSb.append("    <itemref idref=\"cover_page\"/>\n")
            chapters.forEachIndexed { i, _ ->
                opfSb.append("    <itemref idref=\"chap_${i + 1}\"/>\n")
            }
            opfSb.append("  </spine>\n")
            opfSb.append("</package>")
            writeCompressedEntry(zip, "OEBPS/content.opf", opfSb.toString())
        }
    }

    private fun writeCompressedEntry(zip: ZipOutputStream, entryName: String, content: String) {
        val bytes = content.toByteArray(StandardCharsets.UTF_8)
        val entry = ZipEntry(entryName).apply {
            method = ZipEntry.DEFLATED
        }
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun writeBinaryEntry(zip: ZipOutputStream, entryName: String, bytes: ByteArray) {
        val entry = ZipEntry(entryName).apply {
            method = ZipEntry.DEFLATED
        }
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun escapeXml(str: String): String {
        return str
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    /**
     * 呼叫 Android 系統原生分享視窗 (ACTION_SEND)，將檔案傳至 Google Drive、讀墨、LINE、微信或檔案管理器
     */
    private fun shareExportedFile(context: Context, file: File, mimeType: String, bookTitle: String) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "小說匯出：《$bookTitle》")
            putExtra(Intent.EXTRA_TEXT, "這是在「言ノ葉 Reader」上完成日翻中與排版的小說電子書檔案：《$bookTitle》。")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(shareIntent, "匯出並分享小說電子書").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}