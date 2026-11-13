package com.anglesgirl.labelscanner.export

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Xml
import com.anglesgirl.labelscanner.model.LabelResult
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlSerializer
import java.io.File
import java.io.FileOutputStream
import java.io.StringReader
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 导出工具：CSV / WMS 格式
 *
 * CSV（原有兼容）：标签字段 + 原始条码/OCR
 * WMS：库存导入模板 DATA01~DATA14（.xlsx）
 *   DATA01 库位           -> 固定 "STAGE"
 *   DATA02 卡板/托盘      -> trayCode
 *   DATA03 物料编码       -> materialCode
 *   DATA04 箱号           -> boxCode（旧数据/单条模式 boxCode 空 → 回退 serialNumber，
 *                            因为以前一箱一个 SN，箱号=SN；一箱多 SN 后真正分开）
 *   DATA05 数量           -> 1 (固定)
 *   DATA06 工厂           -> 固定 "6012"
 *   DATA07 库存地         -> 留空
 *   DATA08 生产日期-yyyymmdd -> productionDate
 *   DATA09 销售公司       -> 留空
 *   DATA10 销售订单|行号   -> 固定 "|"
 *   DATA11 供应商         -> 留空
 *   DATA12 特别加工指示书编号 -> 留空
 *   DATA13 WCS库位        -> 留空
 *   DATA14 SN码           -> serialNumber
 *
 * 单据导出（vc25 起）：不再打 ZIP，改成单个 .xlsx 多工作表——
 *   第 1 张 WMS导入（格式不变）+ 单据照片（图片嵌进工作表）
 *   + 附件表格（把附件 .xlsx 的表原样拷进来，共享字符串转内联）
 *   + 附件图片（每张嵌一张工作表）+ 附件说明（放不进工作表的文件只列名）。
 * 全程手写 OOXML，不引 Apache POI（Android 运行时依赖重，见 AGENTS.md）。
 */
object Exporter {

    private const val EXT_DIR = "Download/LabelScanner"
    private const val XLSX_MIME_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

    /** 原有 CSV 导出（保留兼容） */
    fun export(context: Context, records: List<LabelResult>): Uri? {
        val fileName = "labels_${System.currentTimeMillis()}.csv"
        return if (Build.VERSION.SDK_INT >= 29) {
            exportViaMediaStore(context, records, fileName, ::buildCsvRow)
        } else {
            exportViaLegacyDir(context, records, fileName, ::buildCsvRow)
        }
    }

    /** WMS 导出：库存导入模板 DATA01~DATA14，始终生成真实 .xlsx（单表）。 */
    fun exportWms(context: Context, records: List<LabelResult>): Uri? {
        val trayCode = records.firstOrNull()?.trayCode?.takeIf { it.isNotBlank() }
        val fileName = "${trayCode ?: "wms_import_${System.currentTimeMillis()}"}.xlsx"
        return if (Build.VERSION.SDK_INT >= 29) {
            exportXlsxViaMediaStore(context, fileName) { writeWmsWorkbook(it, records) }
        } else {
            exportXlsxViaLegacyDir(context, fileName) { writeWmsWorkbook(it, records) }
        }
    }

    /** 单据导出：单个 .xlsx 多工作表（WMS 表 + 单据照片 + 附件表格/图片 + 附件说明）。 */
    fun exportSlipWorkbook(
        context: Context,
        records: List<LabelResult>,
        fileName: String,
        slipNo: String,
        photoFile: File?,
        extraFiles: List<File> = emptyList(),
    ): Uri? {
        val base = fileName.removeSuffix(".xlsx").removeSuffix(".zip").trim().ifBlank { slipNo }
        val fullName = "$base.xlsx"
        return if (Build.VERSION.SDK_INT >= 29) {
            exportXlsxViaMediaStore(context, fullName) { writeSlipWorkbook(it, records, photoFile, extraFiles) }
        } else {
            exportXlsxViaLegacyDir(context, fullName) { writeSlipWorkbook(it, records, photoFile, extraFiles) }
        }
    }

    // ===== 多工作表工作簿 =====

    /** 一张输出工作表：表名 + 表 XML，带图时另有绘图部件。 */
    private data class BuiltSheet(
        val name: String,
        val xml: String,
        val drawing: DrawingPart? = null,
    )

    /** 绘图部件：绘图 XML + 绘图关系 + 媒体文件（图片原字节）。 */
    private data class DrawingPart(
        val index: Int,
        val xml: String,
        val rels: String,
        val mediaName: String,
        val mediaBytes: ByteArray,
        val mediaContentType: String,
    )

    /** 待嵌的图片（按魔数认格式，不信扩展名——相册图拷过来后缀可能是错的）。 */
    private data class ImageData(
        val displayName: String,
        val mediaName: String,
        val bytes: ByteArray,
        val contentType: String,
        val displayW: Int,
        val displayH: Int,
    )

    /** 收集全部工作表并写出一个 .xlsx。无照片无附件时与旧版单表输出等价。 */
    private fun writeSlipWorkbook(
        output: java.io.OutputStream,
        records: List<LabelResult>,
        photoFile: File?,
        extraFiles: List<File>,
    ) {
        val sheets = mutableListOf<BuiltSheet>()
        val usedNames = mutableSetOf<String>()
        val unembeddable = mutableListOf<Pair<String, String>>()
        var drawingCounter = 0
        var mediaCounter = 0

        // 第 1 张：WMS导入（格式不变）
        sheets.add(BuiltSheet(sanitizeSheetName("WMS导入", usedNames), buildSheetXml(records)))

        // 单据照片：嵌进一张工作表
        photoFile?.takeIf { it.exists() }?.let { photo ->
            val img = readImageForSheet(photo, ++mediaCounter)
            if (img != null) {
                drawingCounter++
                sheets.add(
                    BuiltSheet(
                        sanitizeSheetName("单据照片", usedNames),
                        buildPhotoSheetXml(),
                        DrawingPart(drawingCounter, buildDrawingXml(img), buildDrawingRels(img.mediaName), img.mediaName, img.bytes, img.contentType)
                    )
                )
            } else {
                unembeddable.add(photo.name to "单据照片无法读取，未能嵌入")
            }
        }

        // 单据附件
        for (file in extraFiles) {
            if (!file.exists()) continue
            val ext = file.name.substringAfterLast('.', "").lowercase()
            when {
                ext == "xlsx" || ext == "xlsm" -> {
                    val copied = runCatching { copyAttachedWorkbook(file) }.getOrNull()
                    if (copied.isNullOrEmpty()) {
                        unembeddable.add(file.name to "表格读取失败，未能拷入")
                    } else {
                        for ((origName, sheetXml) in copied) {
                            val base = if (copied.size == 1) file.name.substringBeforeLast('.')
                            else "${file.name.substringBeforeLast('.')}-$origName"
                            sheets.add(BuiltSheet(sanitizeSheetName(base, usedNames), sheetXml))
                        }
                    }
                }
                ext == "jpg" || ext == "jpeg" || ext == "png" -> {
                    val img = readImageForSheet(file, ++mediaCounter)
                    if (img != null) {
                        drawingCounter++
                        sheets.add(
                            BuiltSheet(
                                sanitizeSheetName(file.name.substringBeforeLast('.'), usedNames),
                                buildPhotoSheetXml(),
                                DrawingPart(drawingCounter, buildDrawingXml(img), buildDrawingRels(img.mediaName), img.mediaName, img.bytes, img.contentType)
                            )
                        )
                    } else {
                        unembeddable.add(file.name to "图片读取失败，未能嵌入")
                    }
                }
                else -> unembeddable.add(file.name to "此文件无法放入工作表")
            }
        }

        // 放不进工作表的文件：列名留档，不静默丢掉
        if (unembeddable.isNotEmpty()) {
            sheets.add(
                BuiltSheet(
                    sanitizeSheetName("附件说明", usedNames),
                    buildNotesSheetXml(unembeddable)
                )
            )
        }

        writeWorkbookZip(output, sheets)
    }

    /** 把全部工作表（含绘图/媒体）写成一个 .xlsx 包。 */
    private fun writeWorkbookZip(output: java.io.OutputStream, sheets: List<BuiltSheet>) {
        ZipOutputStream(output).use { zip ->
            val hasJpg = sheets.any { it.drawing?.mediaContentType == "image/jpeg" }
            val hasPng = sheets.any { it.drawing?.mediaContentType == "image/png" }
            zip.writeEntry("[Content_Types].xml", buildString {
                append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
                append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
                append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
                if (hasJpg) append("<Default Extension=\"jpg\" ContentType=\"image/jpeg\"/>")
                if (hasPng) append("<Default Extension=\"png\" ContentType=\"image/png\"/>")
                append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
                sheets.forEachIndexed { i, _ ->
                    append("<Override PartName=\"/xl/worksheets/sheet${i + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>")
                }
                sheets.forEach { s ->
                    s.drawing?.let { d ->
                        append("<Override PartName=\"/xl/drawings/drawing${d.index}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.drawing+xml\"/>")
                    }
                }
                append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>")
                append("<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>")
                append("<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>")
                append("</Types>")
            })
            zip.writeEntry("_rels/.rels", """<?xml version="1.0" encoding="UTF-8"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
                <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/>
                </Relationships>""".trimIndent())
            zip.writeEntry("xl/workbook.xml", buildString {
                append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                append("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>")
                sheets.forEachIndexed { i, s ->
                    append("<sheet name=\"${xmlEscape(s.name)}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>")
                }
                append("</sheets></workbook>")
            })
            zip.writeEntry("xl/_rels/workbook.xml.rels", buildString {
                append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
                sheets.forEachIndexed { i, _ ->
                    append("<Relationship Id=\"rId${i + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${i + 1}.xml\"/>")
                }
                append("<Relationship Id=\"rId${sheets.size + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>")
                append("</Relationships>")
            })
            sheets.forEachIndexed { i, s ->
                val n = i + 1
                zip.writeEntry("xl/worksheets/sheet$n.xml", s.xml)
                s.drawing?.let { d ->
                    zip.writeEntry("xl/drawings/drawing${d.index}.xml", d.xml)
                    zip.writeEntry("xl/drawings/_rels/drawing${d.index}.xml.rels", d.rels)
                    zip.putNextEntry(ZipEntry("xl/media/${d.mediaName}"))
                    zip.write(d.mediaBytes)
                    zip.closeEntry()
                    zip.writeEntry("xl/worksheets/_rels/sheet$n.xml.rels", """<?xml version="1.0" encoding="UTF-8"?>
                        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                        <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/drawing" Target="../drawings/drawing${d.index}.xml"/>
                        </Relationships>""".trimIndent())
                }
            }
            zip.writeEntry("xl/styles.xml", XLSX_STYLES)
            zip.writeEntry("docProps/core.xml", XLSX_CORE)
            zip.writeEntry("docProps/app.xml", XLSX_APP)
        }
    }

    /** 只放一张图的工作表：空数据区 + 绘图引用。 */
    private fun buildPhotoSheetXml(): String = """<?xml version="1.0" encoding="UTF-8"?>
        <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
        <dimension ref="A1"/>
        <sheetViews><sheetView workbookViewId="0"/></sheetViews>
        <sheetData/>
        <drawing r:id="rId1"/>
        </worksheet>""".trimIndent()

    /** 绘图部件：一张图锚在 A1，显示尺寸按原图比例限幅（只影响显示，不改原图字节）。 */
    private fun buildDrawingXml(img: ImageData): String {
        val cx = img.displayW * 9525L
        val cy = img.displayH * 9525L
        return """<?xml version="1.0" encoding="UTF-8"?>
            <xdr:wsDr xmlns:xdr="http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
            <xdr:oneCellAnchor>
            <xdr:from><xdr:col>0</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>0</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from>
            <xdr:ext cx="$cx" cy="$cy"/>
            <xdr:pic>
            <xdr:nvPicPr><xdr:cNvPr id="1" name="${xmlEscape(img.displayName)}"/><xdr:cNvPicPr/></xdr:nvPicPr>
            <xdr:blipFill><a:blip r:embed="rId1"/><a:stretch><a:fillRect/></a:stretch></xdr:blipFill>
            <xdr:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="$cx" cy="$cy"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></xdr:spPr>
            </xdr:pic>
            <xdr:clientData/>
            </xdr:oneCellAnchor>
            </xdr:wsDr>""".trimIndent()
    }

    private fun buildDrawingRels(mediaName: String): String = """<?xml version="1.0" encoding="UTF-8"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
        <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="../media/$mediaName"/>
        </Relationships>""".trimIndent()

    /** 按魔数认图（jpg/png），读显示尺寸并限幅到最长边 1200px；认不出返回 null。 */
    private fun readImageForSheet(file: File, mediaIndex: Int): ImageData? {
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (bytes.size < 8) return null
        val (ext, contentType) = when {
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() ->
                "jpg" to "image/jpeg"
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() ->
                "png" to "image/png"
            else -> return null
        }
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        var w = opts.outWidth
        var h = opts.outHeight
        if (w <= 0 || h <= 0) { w = 1000; h = 1000 }
        val maxSide = 1200
        if (maxOf(w, h) > maxSide) {
            val scale = maxSide.toDouble() / maxOf(w, h)
            w = (w * scale).toInt().coerceAtLeast(1)
            h = (h * scale).toInt().coerceAtLeast(1)
        }
        return ImageData(file.name, "image$mediaIndex.$ext", bytes, contentType, w, h)
    }

    /** 附件说明表：文件名 + 原因（两列）。 */
    private fun buildNotesSheetXml(notes: List<Pair<String, String>>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        append("<cols><col min=\"1\" max=\"1\" width=\"48\" customWidth=\"1\"/><col min=\"2\" max=\"2\" width=\"36\" customWidth=\"1\"/></cols><sheetData>")
        appendXmlRow(1, arrayOf("附件", "说明"))
        notes.forEachIndexed { index, (name, reason) ->
            appendXmlRow(index + 2, arrayOf(name, reason))
        }
        append("</sheetData></worksheet>")
    }

    /** 表名清洗：Excel 禁止 []:*?/\、最长 31 字、重名加序号。 */
    private fun sanitizeSheetName(raw: String, used: MutableSet<String>): String {
        var name = raw.replace(Regex("[\\[\\]:*?/\\\\]"), "_").trim()
        if (name.isEmpty()) name = "附件"
        if (name.length > 31) name = name.take(31)
        var candidate = name
        var n = 2
        while (!used.add(candidate)) {
            val suffix = "_$n"
            candidate = name.take(31 - suffix.length) + suffix
            n++
        }
        return candidate
    }

    // ===== 附件 .xlsx 拷表（读源文件 → 转内联字符串 → 原样拷入） =====

    /** 把附件 .xlsx 的每张表拷出来：返回（表名，转换后的表 XML）。读失败抛异常，由调用方记入附件说明。 */
    private fun copyAttachedWorkbook(file: File): List<Pair<String, String>> {
        val zip = ZipFile(file)
        try {
            val sharedStrings = parseSharedStrings(zip)
            val sheets = parseWorkbookSheets(zip)
            val out = mutableListOf<Pair<String, String>>()
            for ((name, partPath) in sheets) {
                val entry = zip.getEntry(partPath) ?: continue
                val sheetXml = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).readText()
                out.add(name to transformSheetXml(sheetXml, sharedStrings))
            }
            return out
        } finally {
            zip.close()
        }
    }

    /** 读共享字符串表：每条 <si> 内全部 <t> 拼起来（富文本多段也拼成一条）。 */
    private fun parseSharedStrings(zip: ZipFile): List<String> {
        val entry = zip.getEntry("xl/sharedStrings.xml") ?: return emptyList()
        val list = mutableListOf<String>()
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(zip.getInputStream(entry).bufferedReader(Charsets.UTF_8))
        var inSi = false
        var current: StringBuilder? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "si" -> { inSi = true; current = StringBuilder() }
                        "t" -> if (inSi) current?.append(parser.nextText())
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "si") {
                        list.add(current?.toString() ?: "")
                        inSi = false
                        current = null
                    }
                }
            }
            event = parser.next()
        }
        return list
    }

    /** 读源工作簿的表清单：返回（表名，表部件在 zip 里的路径），按源文件顺序。 */
    private fun parseWorkbookSheets(zip: ZipFile): List<Pair<String, String>> {
        val rels = mutableMapOf<String, String>()
        zip.getEntry("xl/_rels/workbook.xml.rels")?.let { entry ->
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setInput(zip.getInputStream(entry).bufferedReader(Charsets.UTF_8))
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "Relationship") {
                    val id = parser.getAttributeValue(null, "Id")
                    val target = parser.getAttributeValue(null, "Target")
                    if (id != null && target != null) rels[id] = target
                }
                event = parser.next()
            }
        }
        val sheets = mutableListOf<Pair<String, String>>()
        zip.getEntry("xl/workbook.xml")?.let { entry ->
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setInput(zip.getInputStream(entry).bufferedReader(Charsets.UTF_8))
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "sheet") {
                    val name = parser.getAttributeValue(null, "name") ?: ""
                    val rId = parser.getAttributeValue(
                        "http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id"
                    ) ?: parser.getAttributeValue(null, "id")
                    val target = rId?.let { rels[it] } ?: ""
                    if (name.isNotEmpty() && target.isNotEmpty()) {
                        val path = when {
                            target.startsWith("/") -> target.removePrefix("/")
                            target.startsWith("xl/") -> target
                            else -> "xl/$target"
                        }
                        sheets.add(name to path)
                    }
                }
                event = parser.next()
            }
        }
        return sheets
    }

    /** 单元格事件（流式拷表时整格缓冲用）。 */
    private sealed class Ev {
        data class Start(val name: String, val attrs: List<Pair<String, String>>) : Ev()
        data class Text(val text: String) : Ev()
        data class End(val name: String) : Ev()
    }

    private fun readAttrs(parser: XmlPullParser): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for (i in 0 until parser.attributeCount) {
            out.add(parser.getAttributeName(i) to parser.getAttributeValue(i))
        }
        return out
    }

    /**
     * 转换一张源表 XML，使其能独立放进新工作簿：
     * - 共享字符串（t="s"）按索引查表，转成内联字符串；
     * - 公式（t="str" 的缓存值同理）只留缓存值，公式本身丢弃（跨表引用在新簿里会断）；
     * - 样式引用（s / style 属性）全部去掉——样式表不拷，留着会指向不存在的样式；
     * - 绘图、超链接、条件格式、数据验证、表格部件等引用外部部件的元素整棵丢掉。
     * 其余（列宽、合并单元格、页面设置等）原样保留。
     */
    private fun transformSheetXml(sheetXml: String, sharedStrings: List<String>): String {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(StringReader(sheetXml))
        val writer = StringWriter()
        val serializer = Xml.newSerializer()
        serializer.setOutput(writer)
        val skipNames = setOf(
            "drawing", "legacyDrawing", "legacyDrawingHF", "oleObjects", "controls",
            "hyperlinks", "conditionalFormatting", "dataValidations", "tableParts", "extLst", "picture"
        )
        var skipDepth = 0
        var rootDone = false
        var cellEvents: MutableList<Ev>? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_DOCUMENT -> serializer.startDocument("UTF-8", true)
                XmlPullParser.START_TAG -> {
                    val name = parser.name
                    if (skipDepth > 0) {
                        skipDepth++
                    } else if (skipNames.contains(name)) {
                        skipDepth = 1
                    } else if (cellEvents != null) {
                        cellEvents!!.add(Ev.Start(name, readAttrs(parser)))
                    } else if (name == "c") {
                        cellEvents = mutableListOf(Ev.Start(name, readAttrs(parser)))
                    } else {
                        if (!rootDone) {
                            val ns = parser.namespace
                            if (ns.isNotEmpty()) serializer.setPrefix("", ns)
                            serializer.setPrefix("r", "http://schemas.openxmlformats.org/officeDocument/2006/relationships")
                            rootDone = true
                        }
                        serializer.startTag(parser.namespace, name)
                        for (i in 0 until parser.attributeCount) {
                            val attrName = parser.getAttributeName(i)
                            if (attrName == "s" || attrName == "style") continue
                            serializer.attribute(parser.getAttributeNamespace(i), attrName, parser.getAttributeValue(i))
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text
                    if (skipDepth > 0) {
                        // 丢弃
                    } else if (cellEvents != null) {
                        cellEvents!!.add(Ev.Text(text))
                    } else {
                        serializer.text(text)
                    }
                }
                XmlPullParser.END_TAG -> {
                    val name = parser.name
                    if (skipDepth > 0) {
                        skipDepth--
                    } else if (cellEvents != null) {
                        cellEvents!!.add(Ev.End(name))
                        if (name == "c") {
                            emitCell(serializer, cellEvents!!, sharedStrings)
                            cellEvents = null
                        }
                    } else {
                        serializer.endTag(parser.namespace, name)
                    }
                }
            }
            event = parser.next()
        }
        serializer.endDocument()
        return writer.toString()
    }

    /** 把缓冲的一格写出：共享字符串/公式缓存值转内联，其余值原样，空格式格只留引用。 */
    private fun emitCell(serializer: XmlSerializer, events: List<Ev>, sharedStrings: List<String>) {
        val start = events.firstOrNull() as? Ev.Start ?: return
        val ref = start.attrs.firstOrNull { it.first == "r" }?.second.orEmpty()
        val type = start.attrs.firstOrNull { it.first == "t" }?.second.orEmpty()
        var valueText: String? = null
        var inlineText: StringBuilder? = null
        var formulaText: String? = null
        var i = 1
        while (i < events.size) {
            val ev = events[i]
            if (ev is Ev.Start && ev.name == "v") {
                val sb = StringBuilder()
                var j = i + 1
                while (j < events.size) {
                    val e2 = events[j]
                    if (e2 is Ev.Text) sb.append(e2.text)
                    else if (e2 is Ev.End && e2.name == "v") break
                    j++
                }
                valueText = sb.toString()
                i = j + 1
            } else if (ev is Ev.Start && ev.name == "f") {
                // 公式文本先收下；有缓存值时丢弃公式只留值，无缓存值时才保留公式让 Excel 现算
                val sb = StringBuilder()
                var j = i + 1
                while (j < events.size) {
                    val e2 = events[j]
                    if (e2 is Ev.Text) sb.append(e2.text)
                    else if (e2 is Ev.End && e2.name == "f") break
                    j++
                }
                formulaText = sb.toString()
                i = j + 1
            } else if (ev is Ev.Start && ev.name == "is") {
                inlineText = StringBuilder()
                var depth = 0
                var j = i
                while (j < events.size) {
                    val e2 = events[j]
                    if (e2 is Ev.Start) {
                        depth++
                        if (e2.name == "t") {
                            var k = j + 1
                            while (k < events.size) {
                                val e3 = events[k]
                                if (e3 is Ev.Text) inlineText.append(e3.text)
                                else if (e3 is Ev.End && e3.name == "t") break
                                k++
                            }
                        }
                    } else if (e2 is Ev.End) {
                        depth--
                        if (depth == 0) break
                    }
                    j++
                }
                i = j + 1
            } else {
                i++
            }
        }
        serializer.startTag(null, "c")
        if (ref.isNotEmpty()) serializer.attribute(null, "r", ref)
        when {
            inlineText != null -> {
                serializer.attribute(null, "t", "inlineStr")
                serializer.startTag(null, "is")
                serializer.startTag(null, "t")
                serializer.text(inlineText.toString())
                serializer.endTag(null, "t")
                serializer.endTag(null, "is")
            }
            type == "s" -> {
                val idx = valueText?.toIntOrNull()
                val resolved = if (idx != null && idx in sharedStrings.indices) sharedStrings[idx] else ""
                serializer.attribute(null, "t", "inlineStr")
                serializer.startTag(null, "is")
                serializer.startTag(null, "t")
                serializer.text(resolved)
                serializer.endTag(null, "t")
                serializer.endTag(null, "is")
            }
            type == "str" -> {
                serializer.attribute(null, "t", "inlineStr")
                serializer.startTag(null, "is")
                serializer.startTag(null, "t")
                serializer.text(valueText ?: "")
                serializer.endTag(null, "t")
                serializer.endTag(null, "is")
            }
            valueText != null -> {
                if (type.isNotEmpty()) serializer.attribute(null, "t", type)
                serializer.startTag(null, "v")
                serializer.text(valueText)
                serializer.endTag(null, "v")
            }
            formulaText != null -> {
                // 无缓存值（某些工具生成的表不写缓存值）：保留公式让 Excel 打开时现算
                serializer.startTag(null, "f")
                serializer.text(formulaText)
                serializer.endTag(null, "f")
            }
            else -> {
                // 空单元格：只留 <c r="…"/>
            }
        }
        serializer.endTag(null, "c")
    }

    private val WMS_DATA_HEADERS = arrayOf(
        "DATA01", "DATA02", "DATA03", "DATA04", "DATA05",
        "DATA06", "DATA07", "DATA08", "DATA09", "DATA10",
        "DATA11", "DATA12", "DATA13", "DATA14"
    )

    private val WMS_LABEL_HEADERS = arrayOf(
        "库位", "卡板/托盘", "物料编码", "箱号", "数量", "工厂", "库存地",
        "生产日期", "销售公司", "销售订单|行号", "供应商", "特别加工指示书编号",
        "WCS库位", "SN码"
    )

    private fun buildCsvRow(r: LabelResult): String {
        return "\"${r.barcodes.joinToString("|")}\",\"${r.ocrText}\",\"${r.supplier}\",\"${r.serialNumber}\",\"${r.materialCode}\",${r.quantity},\"${r.productionDate}\",\"${r.ean69}\",\"${r.model}\",\"${r.color}\",\"${r.tonerModel}\""
    }

    private fun buildWmsRow(r: LabelResult): Array<String> {
        val trayCode = r.trayCode.ifBlank { "" }
        // 箱号：一箱多 SN 后 boxCode 独立；旧数据 boxCode 空 → 回退 SN（一箱一 SN 时箱号=SN）
        val boxCode = r.boxCode.ifBlank { r.serialNumber }
        return arrayOf(
            "STAGE",              // DATA01 库位
            trayCode,             // DATA02 卡板/托盘
            r.materialCode,       // DATA03 物料编码
            boxCode,              // DATA04 箱号
            "1",                  // DATA05 数量
            "6012",               // DATA06 工厂（固定）
            "",                   // DATA07 库存地
            r.productionDate,     // DATA08 生产日期-yyyymmdd
            "",                   // DATA09 销售公司
            "|",                  // DATA10 销售订单|行号
            "",                   // DATA11 供应商
            "",                   // DATA12 特别加工指示书编号
            "",                   // DATA13 WCS库位
            r.serialNumber        // DATA14 SN码
        )
    }

    // ===== Excel (.xlsx) 导出：手写 OOXML 工作簿，避免 Apache POI 在 Android 上的运行时依赖 =====

    private fun exportXlsxViaMediaStore(
        context: Context,
        fileName: String,
        write: (java.io.OutputStream) -> Unit,
    ): Uri? = runCatching {
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        context.contentResolver.delete(
            collection,
            "${MediaStore.Downloads.DISPLAY_NAME} = ?",
            arrayOf(fileName)
        )
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, XLSX_MIME_TYPE)
            put(MediaStore.Downloads.RELATIVE_PATH, EXT_DIR)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(collection, values)
            ?: error("无法创建导出文件")
        context.contentResolver.openOutputStream(uri)?.use { write(it) }
            ?: error("无法打开导出文件")
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        context.contentResolver.update(uri, values, null, null)
        uri
    }.getOrNull()

    private fun exportXlsxViaLegacyDir(
        context: Context,
        fileName: String,
        write: (java.io.OutputStream) -> Unit,
    ): Uri? = runCatching {
        val dir = File(context.getExternalFilesDir(null), "LabelScanner").apply { mkdirs() }
        val file = File(dir, fileName)
        FileOutputStream(file).use { write(it) }
        androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
    }.getOrNull()

    /** 单表 WMS 工作簿（无照片无附件时与旧版输出等价）。 */
    private fun writeWmsWorkbook(output: java.io.OutputStream, records: List<LabelResult>) {
        writeSlipWorkbook(output, records, null, emptyList())
    }

    /** 最小可用样式表。 */
    private val XLSX_STYLES = """<?xml version="1.0" encoding="UTF-8"?>
        <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
        <fonts count="1"><font><sz val="11"/><name val="Calibri"/></font></fonts>
        <fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>
        <borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
        <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
        <cellXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/></cellXfs>
        <cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
        </styleSheet>""".trimIndent()

    private val XLSX_CORE = """<?xml version="1.0" encoding="UTF-8"?>
        <cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
        <dc:title>WMS导入</dc:title><dc:creator>LabelScanner</dc:creator>
        <cp:lastModifiedBy>LabelScanner</dc:lastModifiedBy>
        <dcterms:created xsi:type="dcterms:W3CDTF">2026-01-01T00:00:00Z</dcterms:created>
        <dcterms:modified xsi:type="dcterms:W3CDTF">2026-01-01T00:00:00Z</dcterms:modified>
        </cp:coreProperties>""".trimIndent()

    private val XLSX_APP = """<?xml version="1.0" encoding="UTF-8"?>
        <Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties" xmlns:vt="http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes">
        <Application>LabelScanner</Application>
        </Properties>""".trimIndent()

    private fun ZipOutputStream.writeEntry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(StandardCharsets.UTF_8))
        closeEntry()
    }

    private fun buildSheetXml(records: List<LabelResult>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        append("<cols>")
        for (column in 1..15) append("<col min=\"$column\" max=\"$column\" width=\"18\" customWidth=\"1\"/>")
        append("</cols><sheetData>")
        appendXmlRow(1, arrayOf("1") + WMS_DATA_HEADERS)
        appendXmlRow(2, arrayOf("") + WMS_LABEL_HEADERS)
        records.forEachIndexed { index, record -> appendXmlRow(index + 3, arrayOf("") + buildWmsRow(record)) }
        append("</sheetData></worksheet>")
    }

    private fun StringBuilder.appendXmlRow(rowNumber: Int, values: Array<String>) {
        append("<row r=\"$rowNumber\">")
        values.forEachIndexed { column, value ->
            if (value.isNotEmpty()) {
                val ref = "${excelColumn(column + 1)}$rowNumber"
                append("<c r=\"$ref\" t=\"inlineStr\"><is><t>")
                append(xmlEscape(value))
                append("</t></is></c>")
            }
        }
        append("</row>")
    }

    private fun excelColumn(index: Int): String {
        var value = index
        val out = StringBuilder()
        while (value > 0) {
            value--
            out.append(('A'.code + value % 26).toChar())
            value /= 26
        }
        return out.reverse().toString()
    }

    private fun xmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    /** API 29+：MediaStore.Downloads（免权限，公共可见，卸载不删） */
    private fun exportViaMediaStore(
        context: Context,
        records: List<LabelResult>,
        fileName: String,
        rowBuilder: (LabelResult) -> String,
    ): Uri? {
        try {
            val header = if (fileName.startsWith("wms_")) {
                "DATA01,DATA02,DATA03,DATA04,DATA05,DATA06,DATA07,DATA08,DATA09,DATA10,DATA11,DATA12,DATA13,DATA14"
            } else {
                "条码,OCR,供应商,序列号,物料编码,数量,生产日期,69码,型号,颜色,硒鼓"
            }

            val csv = buildString {
                appendLine(header)
                for (r in records) appendLine(rowBuilder(r))
            }

            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            // 先删同名
            context.contentResolver.delete(
                collection,
                "${MediaStore.Downloads.DISPLAY_NAME} = ?",
                arrayOf(fileName)
            )
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(MediaStore.Downloads.RELATIVE_PATH, EXT_DIR)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(collection, values)
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray(StandardCharsets.UTF_8)) }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
            }
            return uri
        } catch (e: Exception) {
            return null
        }
    }

    /** API 26-28：外部文件目录（需要 WRITE_EXTERNAL_STORAGE 权限，卸载删） */
    private fun exportViaLegacyDir(
        context: Context,
        records: List<LabelResult>,
        fileName: String,
        rowBuilder: (LabelResult) -> String,
    ): Uri? {
        try {
            val dir = File(context.getExternalFilesDir(null), "LabelScanner").apply { mkdirs() }
            val file = File(dir, fileName)

            val header = if (fileName.startsWith("wms_")) {
                "DATA01,DATA02,DATA03,DATA04,DATA05,DATA06,DATA07,DATA08,DATA09,DATA10,DATA11,DATA12,DATA13,DATA14"
            } else {
                "条码,OCR,供应商,序列号,物料编码,数量,生产日期,69码,型号,颜色,硒鼓"
            }

            val csv = buildString {
                appendLine(header)
                for (r in records) appendLine(rowBuilder(r))
            }
            file.writeText(csv, StandardCharsets.UTF_8)
            return Uri.fromFile(file)
        } catch (e: Exception) {
            return null
        }
    }
}
