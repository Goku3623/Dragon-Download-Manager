package com.example.data.filemanager

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

data class ArscTypedResourceEntry(
    val resourceId: Int,
    val resourceIdHex: String,
    val typeName: String,
    val entryName: String,
    val valueType: Int,
    val valueTypeLabel: String,
    val rawData: Int,
    val displayValue: String,
    val valueOffsetInArsc: Int,
    val stringPoolIndex: Int? = null
)

/**
 * Pure-Kotlin Universal Android Binary XML (AXML) Decompiler & Recompiler,
 * plus Full `resources.arsc` Typed Resource Table Inspector & Binary Patcher.
 *
 * Works on ANY Android binary XML (`AndroidManifest.xml`, `res/layout/` XMLs,
 * `res/drawable/` XMLs, `res/xml/` XMLs, `res/color/` XMLs, standalone binary `.xml` files).
 */
object DragonAxmlCompilerEngine {

    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_TABLE_TYPE = 0x0002
    private const val RES_XML_TYPE = 0x0003
    private const val RES_TABLE_PACKAGE_TYPE = 0x0200
    private const val RES_TABLE_TYPE_TYPE = 0x0201

    private const val RES_XML_START_NAMESPACE_TYPE = 0x0100
    private const val RES_XML_END_NAMESPACE_TYPE = 0x0101
    private const val RES_XML_START_ELEMENT_TYPE = 0x0102
    private const val RES_XML_END_ELEMENT_TYPE = 0x0103
    private const val RES_XML_CDATA_TYPE = 0x0104
    private const val RES_XML_RESOURCE_MAP_TYPE = 0x0180

    const val TYPE_NULL = 0x00
    const val TYPE_REFERENCE = 0x01
    const val TYPE_ATTRIBUTE = 0x02
    const val TYPE_STRING = 0x03
    const val TYPE_FLOAT = 0x04
    const val TYPE_DIMENSION = 0x05
    const val TYPE_FRACTION = 0x06
    const val TYPE_DYNAMIC_REFERENCE = 0x07
    const val TYPE_INT_DEC = 0x10
    const val TYPE_INT_HEX = 0x11
    const val TYPE_INT_BOOLEAN = 0x12
    const val TYPE_INT_COLOR_ARGB8 = 0x1C
    const val TYPE_INT_COLOR_RGB8 = 0x1D
    const val TYPE_INT_COLOR_ARGB4 = 0x1E
    const val TYPE_INT_COLOR_RGB4 = 0x1F

    private const val ANDROID_NS_URI = "http://schemas.android.com/apk/res/android"

    private val DIMENSION_UNITS = arrayOf("px", "dp", "sp", "pt", "in", "mm")
    private val FRACTION_UNITS = arrayOf("%", "%p")
    private val RADIX_MULTS = floatArrayOf(
        1.0f / (1 shl 0),
        1.0f / (1 shl 7),
        1.0f / (1 shl 15),
        1.0f / (1 shl 23)
    )

    /**
     * Lazy cache of every official `android.R.attr.*` name -> resource ID from the Android framework.
     */
    private val frameworkAndroidAttrIds: Map<String, Int> by lazy {
        val map = HashMap<String, Int>(2048)
        // Core manifest & layout attributes guaranteed even if reflection is restricted
        map["theme"] = 0x01010000
        map["label"] = 0x01010001
        map["icon"] = 0x01010002
        map["name"] = 0x01010003
        map["manageSpaceActivity"] = 0x01010004
        map["allowClearUserData"] = 0x01010005
        map["permission"] = 0x01010006
        map["readPermission"] = 0x01010007
        map["writePermission"] = 0x01010008
        map["protectionLevel"] = 0x01010009
        map["permissionGroup"] = 0x0101000a
        map["sharedUserId"] = 0x0101000b
        map["hasCode"] = 0x0101000c
        map["persistent"] = 0x0101000d
        map["enabled"] = 0x0101000e
        map["debuggable"] = 0x0101000f
        map["exported"] = 0x01010010
        map["process"] = 0x01010011
        map["taskAffinity"] = 0x01010012
        map["multiprocess"] = 0x01010013
        map["finishOnTaskLaunch"] = 0x01010014
        map["clearTaskOnLaunch"] = 0x01010015
        map["stateNotNeeded"] = 0x01010016
        map["excludeFromRecents"] = 0x01010017
        map["authorities"] = 0x01010018
        map["syncable"] = 0x01010019
        map["initOrder"] = 0x0101001a
        map["grantUriPermissions"] = 0x0101001b
        map["priority"] = 0x0101001c
        map["launchMode"] = 0x0101001d
        map["screenOrientation"] = 0x0101001e
        map["configChanges"] = 0x0101001f
        map["description"] = 0x01010020
        map["targetPackage"] = 0x01010021
        map["handleProfiling"] = 0x01010022
        map["functionalTest"] = 0x01010023
        map["value"] = 0x01010024
        map["resource"] = 0x01010025
        map["mimeType"] = 0x01010026
        map["scheme"] = 0x01010027
        map["host"] = 0x01010028
        map["port"] = 0x01010029
        map["path"] = 0x0101002a
        map["pathPrefix"] = 0x0101002b
        map["pathPattern"] = 0x0101002c
        map["action"] = 0x0101002d
        map["data"] = 0x0101002e
        map["targetClass"] = 0x0101002f
        map["id"] = 0x010100d0
        map["tag"] = 0x010100d1
        map["background"] = 0x010100d4
        map["padding"] = 0x010100d5
        map["paddingLeft"] = 0x010100d6
        map["paddingTop"] = 0x010100d7
        map["paddingRight"] = 0x010100d8
        map["paddingBottom"] = 0x010100d9
        map["visibility"] = 0x010100dc
        map["orientation"] = 0x010100c4
        map["gravity"] = 0x010100af
        map["layout_gravity"] = 0x010100b3
        map["layout_width"] = 0x010100f4
        map["layout_height"] = 0x010100f5
        map["layout_margin"] = 0x010100f6
        map["layout_marginLeft"] = 0x010100f7
        map["layout_marginTop"] = 0x010100f8
        map["layout_marginRight"] = 0x010100f9
        map["layout_marginBottom"] = 0x010100fa
        map["textSize"] = 0x01010095
        map["textStyle"] = 0x01010097
        map["textColor"] = 0x01010098
        map["text"] = 0x0101014f
        map["hint"] = 0x01010150
        map["src"] = 0x01010119
        map["scaleType"] = 0x0101011d
        map["width"] = 0x01010159
        map["height"] = 0x01010155
        map["color"] = 0x010101a5
        map["minSdkVersion"] = 0x0101020c
        map["versionCode"] = 0x0101021b
        map["versionName"] = 0x0101021c
        map["targetSdkVersion"] = 0x01010270
        map["testOnly"] = 0x01010272
        map["allowBackup"] = 0x01010280
        map["installLocation"] = 0x010102b7
        map["largeHeap"] = 0x0101035a
        map["supportsRtl"] = 0x010103af
        map["viewportWidth"] = 0x01010402
        map["viewportHeight"] = 0x01010403
        map["fillColor"] = 0x01010404
        map["pathData"] = 0x01010405
        map["strokeColor"] = 0x01010406
        map["strokeWidth"] = 0x01010407
        map["extractNativeLibs"] = 0x010104ea
        map["usesCleartextTraffic"] = 0x010104ec
        map["roundIcon"] = 0x0101052c
        map["appComponentFactory"] = 0x0101057a
        map["requestLegacyExternalStorage"] = 0x01010603
        map["compileSdkVersion"] = 0x01010572
        map["compileSdkVersionCodename"] = 0x01010573

        runCatching {
            val attrClass = android.R.attr::class.java
            for (field in attrClass.fields) {
                if (field.type == Int::class.javaPrimitiveType) {
                    map[field.name] = field.getInt(null)
                }
            }
        }
        map
    }

    /**
     * Returns true if [bytes] begins with a valid Android Binary XML (`RES_XML_TYPE = 0x0003`) chunk header.
     */
    fun isBinaryAxml(bytes: ByteArray): Boolean {
        if (bytes.size < 16) return false
        val rootType = readShortLE(bytes, 0)
        val rootHeaderSize = readShortLE(bytes, 2)
        val rootSize = readIntLE(bytes, 4)
        return rootType == RES_XML_TYPE && rootHeaderSize in 8..64 && rootSize in 16..bytes.size
    }

    // =========================================================================
    // 1. UNIVERSAL BINARY AXML DECOMPILER
    // =========================================================================

    fun decompileBinaryXml(axmlBytes: ByteArray): String? {
        if (!isBinaryAxml(axmlBytes)) return null
        val rootHeaderSize = readShortLE(axmlBytes, 2)
        val rootSize = readIntLE(axmlBytes, 4)

        var strings: List<String> = emptyList()
        val nsPrefixByUriIdx = LinkedHashMap<Int, String>()
        val pendingNsDecls = LinkedHashMap<String, String>() // prefix -> uri
        val xmlOut = StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        var indentLevel = 0

        fun getStr(idx: Int): String = if (idx in strings.indices) strings[idx] else ""

        var offset = rootHeaderSize
        while (offset + 8 <= rootSize) {
            val chunkType = readShortLE(axmlBytes, offset)
            val headerSize = readShortLE(axmlBytes, offset + 2)
            val chunkSize = readIntLE(axmlBytes, offset + 4)
            if (chunkSize < 8 || offset + chunkSize > axmlBytes.size) break

            when (chunkType) {
                RES_STRING_POOL_TYPE -> {
                    strings = parseStringPoolOnly(axmlBytes, offset, headerSize, chunkSize) ?: return null
                }
                RES_XML_START_NAMESPACE_TYPE -> {
                    val prefixIdx = readIntLE(axmlBytes, offset + headerSize)
                    val uriIdx = readIntLE(axmlBytes, offset + headerSize + 4)
                    val prefix = getStr(prefixIdx)
                    val uri = getStr(uriIdx)
                    if (uriIdx >= 0) nsPrefixByUriIdx[uriIdx] = prefix
                    if (prefix.isNotBlank() && uri.isNotBlank()) {
                        pendingNsDecls[prefix] = uri
                    }
                }
                RES_XML_START_ELEMENT_TYPE -> {
                    val body = offset + headerSize
                    val nsIdx = readIntLE(axmlBytes, body)
                    val nameIdx = readIntLE(axmlBytes, body + 4)
                    val attrStart = readShortLE(axmlBytes, body + 8)
                    val attrSize = readShortLE(axmlBytes, body + 10).coerceAtLeast(20)
                    val attrCount = readShortLE(axmlBytes, body + 12)

                    val tagPrefix = if (nsIdx >= 0) nsPrefixByUriIdx[nsIdx].orEmpty() else ""
                    val rawTagName = getStr(nameIdx)
                    val tagName = if (tagPrefix.isNotEmpty()) "$tagPrefix:$rawTagName" else rawTagName
                    val indent = "  ".repeat(indentLevel.coerceAtLeast(0))

                    xmlOut.append(indent).append('<').append(tagName)
                    if (pendingNsDecls.isNotEmpty()) {
                        pendingNsDecls.forEach { (pfx, uri) ->
                            xmlOut.append("\n").append(indent).append("    xmlns:")
                                .append(pfx).append("=\"").append(escapeXml(uri)).append("\"")
                        }
                        pendingNsDecls.clear()
                    }

                    var attrPos = body + attrStart
                    for (a in 0 until attrCount) {
                        if (attrPos + 20 > offset + chunkSize) break
                        val aNs = readIntLE(axmlBytes, attrPos)
                        val aName = readIntLE(axmlBytes, attrPos + 4)
                        val aRawVal = readIntLE(axmlBytes, attrPos + 8)
                        val aType = axmlBytes[attrPos + 15].toInt() and 0xFF
                        val aData = readIntLE(axmlBytes, attrPos + 16)

                        val attrName = getStr(aName)
                        val prefix = if (aNs >= 0) nsPrefixByUriIdx[aNs] ?: "android" else ""
                        val fullAttr = if (prefix.isNotEmpty()) "$prefix:$attrName" else attrName
                        val formattedVal = formatTypedValue(aType, aData, aRawVal, strings)

                        xmlOut.append('\n').append(indent).append("    ")
                            .append(fullAttr).append("=\"").append(escapeXml(formattedVal)).append("\"")
                        attrPos += attrSize
                    }
                    xmlOut.append(">\n")
                    indentLevel++
                }
                RES_XML_CDATA_TYPE -> {
                    val body = offset + headerSize
                    val dataStrIdx = readIntLE(axmlBytes, body)
                    val text = getStr(dataStrIdx)
                    if (text.isNotEmpty()) {
                        xmlOut.append("  ".repeat(indentLevel.coerceAtLeast(0)))
                            .append(escapeXml(text)).append('\n')
                    }
                }
                RES_XML_END_ELEMENT_TYPE -> {
                    indentLevel = (indentLevel - 1).coerceAtLeast(0)
                    val body = offset + headerSize
                    val nsIdx = readIntLE(axmlBytes, body)
                    val nameIdx = readIntLE(axmlBytes, body + 4)
                    val tagPrefix = if (nsIdx >= 0) nsPrefixByUriIdx[nsIdx].orEmpty() else ""
                    val rawTagName = getStr(nameIdx)
                    val tagName = if (tagPrefix.isNotEmpty()) "$tagPrefix:$rawTagName" else rawTagName
                    xmlOut.append("  ".repeat(indentLevel)).append("</").append(tagName).append(">\n")
                }
            }
            offset += chunkSize
        }

        return xmlOut.toString()
    }

    // =========================================================================
    // 2. UNIVERSAL XML -> BINARY AXML COMPILER
    // =========================================================================

    private data class OrigAttrHint(
        val resId: Int,
        val preferredType: Int
    )

    private sealed class CompiledEvent {
        data class StartNs(val line: Int, val prefix: String, val uri: String) : CompiledEvent()
        data class EndNs(val line: Int, val prefix: String, val uri: String) : CompiledEvent()
        data class StartTag(
            val line: Int,
            val nsUri: String?,
            val name: String,
            val attrs: List<CompiledAttr>
        ) : CompiledEvent()
        data class EndTag(val line: Int, val nsUri: String?, val name: String) : CompiledEvent()
        data class CData(val line: Int, val text: String) : CompiledEvent()
    }

    private data class CompiledAttr(
        val nsUri: String?,
        val name: String,
        val rawValue: String,
        val resId: Int,
        val valueType: Int,
        val valueData: Int,
        val stringVal: String?
    )

    /**
     * Compiles human-readable XML text into a valid Android Binary XML (AXML) byte array.
     *
     * When [originalAxmlBytes] is supplied, all existing attribute resource IDs and preferred
     * value types from the original compiled file are preserved alongside the full Android
     * `android.R.attr` registry so round-trip edits compile cleanly for Android's `AssetManager`.
     */
    fun compileXmlToBinaryAxml(
        xmlText: String,
        originalAxmlBytes: ByteArray? = null
    ): Result<ByteArray> {
        return runCatching {
            val origHints = if (originalAxmlBytes != null && isBinaryAxml(originalAxmlBytes)) {
                extractOriginalAttrHints(originalAxmlBytes)
            } else {
                emptyMap()
            }

            val parser = Xml.newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                setInput(StringReader(xmlText))
            }

            val events = mutableListOf<CompiledEvent>()
            val activeNsStack = ArrayDeque<List<Pair<String, String>>>()

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                val line = parser.lineNumber.coerceAtLeast(1)
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        val depth = parser.depth
                        val prevNsCount = if (depth > 1) parser.getNamespaceCount(depth - 1) else 0
                        val currNsCount = parser.getNamespaceCount(depth)
                        val addedHere = mutableListOf<Pair<String, String>>()
                        for (i in prevNsCount until currNsCount) {
                            val pfx = parser.getNamespacePrefix(i).orEmpty()
                            val uri = parser.getNamespaceUri(i).orEmpty()
                            if (pfx.isNotEmpty() && uri.isNotEmpty()) {
                                events.add(CompiledEvent.StartNs(line, pfx, uri))
                                addedHere.add(pfx to uri)
                            }
                        }
                        activeNsStack.addLast(addedHere)

                        val tagNs = parser.namespace.takeIf { it.isNotEmpty() }
                        val tagName = parser.name
                        val attrs = mutableListOf<CompiledAttr>()
                        for (i in 0 until parser.attributeCount) {
                            val aNs = parser.getAttributeNamespace(i).takeIf { it.isNotEmpty() }
                            val aName = parser.getAttributeName(i)
                            val aVal = parser.getAttributeValue(i)

                            val hintKey = "${aNs.orEmpty()}|$aName"
                            val hint = origHints[hintKey]
                            val resId = when {
                                hint != null && hint.resId != 0 -> hint.resId
                                aNs == ANDROID_NS_URI -> frameworkAndroidAttrIds[aName] ?: 0
                                else -> 0
                            }

                            val encoded = encodeAttributeValue(aVal, hint?.preferredType)
                            attrs.add(
                                CompiledAttr(
                                    nsUri = aNs,
                                    name = aName,
                                    rawValue = aVal,
                                    resId = resId,
                                    valueType = encoded.first,
                                    valueData = encoded.second,
                                    stringVal = encoded.third
                                )
                            )
                        }
                        // Android AXML expects attributes sorted by resource ID (non-zero resIds ascending, then 0)
                        val sortedAttrs = attrs.sortedWith(
                            compareBy<CompiledAttr> { if (it.resId == 0) 1 else 0 }
                                .thenBy { it.resId.toUInt() }
                        )
                        events.add(CompiledEvent.StartTag(line, tagNs, tagName, sortedAttrs))
                    }
                    XmlPullParser.TEXT -> {
                        val txt = parser.text
                        if (!txt.isNullOrBlank()) {
                            events.add(CompiledEvent.CData(line, txt.trim()))
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val tagNs = parser.namespace.takeIf { it.isNotEmpty() }
                        val tagName = parser.name
                        events.add(CompiledEvent.EndTag(line, tagNs, tagName))
                        val toClose = if (activeNsStack.isNotEmpty()) activeNsStack.removeLast() else emptyList()
                        for ((pfx, uri) in toClose.asReversed()) {
                            events.add(CompiledEvent.EndNs(line, pfx, uri))
                        }
                    }
                }
                eventType = parser.next()
            }

            if (events.none { it is CompiledEvent.StartTag }) {
                throw IllegalArgumentException("XML contains no root element.")
            }

            // Build string pool: resource-ID-mapped attribute names MUST occupy indices 0..N-1
            // so RES_XML_RESOURCE_MAP aligns 1:1 with the string pool!
            val resAttrNameToId = LinkedHashMap<String, Int>()
            events.forEach { ev ->
                if (ev is CompiledEvent.StartTag) {
                    ev.attrs.forEach { attr ->
                        if (attr.resId != 0) {
                            resAttrNameToId.putIfAbsent(attr.name, attr.resId)
                        }
                    }
                }
            }

            val stringList = mutableListOf<String>()
            val stringToIndex = HashMap<String, Int>()
            val resourceIds = mutableListOf<Int>()

            fun intern(s: String): Int {
                return stringToIndex.getOrPut(s) {
                    val idx = stringList.size
                    stringList.add(s)
                    idx
                }
            }

            // 1. Intern all resource-mapped attribute names first
            resAttrNameToId.forEach { (attrName, resId) ->
                intern(attrName)
                resourceIds.add(resId)
            }

            // 2. Intern all remaining strings (namespaces, tag names, attr names, string values)
            events.forEach { ev ->
                when (ev) {
                    is CompiledEvent.StartNs -> {
                        intern(ev.prefix)
                        intern(ev.uri)
                    }
                    is CompiledEvent.EndNs -> {
                        intern(ev.prefix)
                        intern(ev.uri)
                    }
                    is CompiledEvent.StartTag -> {
                        ev.nsUri?.let { intern(it) }
                        intern(ev.name)
                        ev.attrs.forEach { attr ->
                            attr.nsUri?.let { intern(it) }
                            intern(attr.name)
                            if (attr.valueType == TYPE_STRING) {
                                intern(attr.stringVal ?: attr.rawValue)
                            }
                        }
                    }
                    is CompiledEvent.EndTag -> {
                        ev.nsUri?.let { intern(it) }
                        intern(ev.name)
                    }
                    is CompiledEvent.CData -> {
                        intern(ev.text)
                    }
                }
            }

            val bodyOut = ByteArrayOutputStream()
            // Chunk 1: RES_STRING_POOL (UTF-16LE for universal compatibility with all Android versions)
            bodyOut.write(serializeUtf16StringPool(stringList))

            // Chunk 2: RES_XML_RESOURCE_MAP
            if (resourceIds.isNotEmpty()) {
                val mapSize = 8 + resourceIds.size * 4
                val mapBuf = ByteBuffer.allocate(mapSize).order(ByteOrder.LITTLE_ENDIAN)
                mapBuf.putShort(RES_XML_RESOURCE_MAP_TYPE.toShort())
                mapBuf.putShort(8.toShort())
                mapBuf.putInt(mapSize)
                resourceIds.forEach { mapBuf.putInt(it) }
                bodyOut.write(mapBuf.array())
            }

            // Chunk 3+: XML Tree Chunks
            for (ev in events) {
                when (ev) {
                    is CompiledEvent.StartNs -> {
                        val buf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                        buf.putShort(RES_XML_START_NAMESPACE_TYPE.toShort())
                        buf.putShort(16.toShort())
                        buf.putInt(24)
                        buf.putInt(ev.line)
                        buf.putInt(-1)
                        buf.putInt(intern(ev.prefix))
                        buf.putInt(intern(ev.uri))
                        bodyOut.write(buf.array())
                    }
                    is CompiledEvent.EndNs -> {
                        val buf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                        buf.putShort(RES_XML_END_NAMESPACE_TYPE.toShort())
                        buf.putShort(16.toShort())
                        buf.putInt(24)
                        buf.putInt(ev.line)
                        buf.putInt(-1)
                        buf.putInt(intern(ev.prefix))
                        buf.putInt(intern(ev.uri))
                        bodyOut.write(buf.array())
                    }
                    is CompiledEvent.StartTag -> {
                        var idIndex = 0
                        var classIndex = 0
                        var styleIndex = 0
                        ev.attrs.forEachIndexed { i, attr ->
                            if (attr.nsUri == null) {
                                when (attr.name) {
                                    "id" -> idIndex = i + 1
                                    "class" -> classIndex = i + 1
                                    "style" -> styleIndex = i + 1
                                }
                            }
                        }
                        val totalSize = 16 + 20 + ev.attrs.size * 20
                        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
                        buf.putShort(RES_XML_START_ELEMENT_TYPE.toShort())
                        buf.putShort(16.toShort())
                        buf.putInt(totalSize)
                        buf.putInt(ev.line)
                        buf.putInt(-1)
                        buf.putInt(ev.nsUri?.let { intern(it) } ?: -1)
                        buf.putInt(intern(ev.name))
                        buf.putShort(20.toShort())
                        buf.putShort(20.toShort())
                        buf.putShort(ev.attrs.size.toShort())
                        buf.putShort(idIndex.toShort())
                        buf.putShort(classIndex.toShort())
                        buf.putShort(styleIndex.toShort())

                        for (attr in ev.attrs) {
                            val nsIdx = attr.nsUri?.let { intern(it) } ?: -1
                            val nameIdx = intern(attr.name)
                            val rawValIdx = if (attr.valueType == TYPE_STRING) intern(attr.stringVal ?: attr.rawValue) else -1
                            val dataVal = if (attr.valueType == TYPE_STRING) rawValIdx else attr.valueData

                            buf.putInt(nsIdx)
                            buf.putInt(nameIdx)
                            buf.putInt(rawValIdx)
                            buf.putShort(8.toShort())
                            buf.put(0.toByte())
                            buf.put(attr.valueType.toByte())
                            buf.putInt(dataVal)
                        }
                        bodyOut.write(buf.array())
                    }
                    is CompiledEvent.CData -> {
                        val buf = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN)
                        buf.putShort(RES_XML_CDATA_TYPE.toShort())
                        buf.putShort(16.toShort())
                        buf.putInt(28)
                        buf.putInt(ev.line)
                        buf.putInt(-1)
                        buf.putInt(intern(ev.text))
                        buf.putShort(8.toShort())
                        buf.put(0.toByte())
                        buf.put(TYPE_NULL.toByte())
                        buf.putInt(0)
                        bodyOut.write(buf.array())
                    }
                    is CompiledEvent.EndTag -> {
                        val buf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                        buf.putShort(RES_XML_END_ELEMENT_TYPE.toShort())
                        buf.putShort(16.toShort())
                        buf.putInt(24)
                        buf.putInt(ev.line)
                        buf.putInt(-1)
                        buf.putInt(ev.nsUri?.let { intern(it) } ?: -1)
                        buf.putInt(intern(ev.name))
                        bodyOut.write(buf.array())
                    }
                }
            }

            val bodyBytes = bodyOut.toByteArray()
            val fullSize = 8 + bodyBytes.size
            val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            header.putShort(RES_XML_TYPE.toShort())
            header.putShort(8.toShort())
            header.putInt(fullSize)

            val out = ByteArrayOutputStream(fullSize)
            out.write(header.array())
            out.write(bodyBytes)
            out.toByteArray()
        }
    }

    private fun extractOriginalAttrHints(axmlBytes: ByteArray): Map<String, OrigAttrHint> {
        val hints = HashMap<String, OrigAttrHint>()
        val rootHeaderSize = readShortLE(axmlBytes, 2)
        val rootSize = readIntLE(axmlBytes, 4)
        var strings: List<String> = emptyList()
        val resIds = mutableListOf<Int>()

        var offset = rootHeaderSize
        while (offset + 8 <= rootSize) {
            val chunkType = readShortLE(axmlBytes, offset)
            val headerSize = readShortLE(axmlBytes, offset + 2)
            val chunkSize = readIntLE(axmlBytes, offset + 4)
            if (chunkSize < 8 || offset + chunkSize > axmlBytes.size) break
            when (chunkType) {
                RES_STRING_POOL_TYPE -> {
                    strings = parseStringPoolOnly(axmlBytes, offset, headerSize, chunkSize).orEmpty()
                }
                RES_XML_RESOURCE_MAP_TYPE -> {
                    var p = offset + headerSize
                    while (p + 4 <= offset + chunkSize) {
                        resIds.add(readIntLE(axmlBytes, p))
                        p += 4
                    }
                }
                RES_XML_START_ELEMENT_TYPE -> {
                    val body = offset + headerSize
                    val attrStart = readShortLE(axmlBytes, body + 8)
                    val attrSize = readShortLE(axmlBytes, body + 10).coerceAtLeast(20)
                    val attrCount = readShortLE(axmlBytes, body + 12)
                    var attrPos = body + attrStart
                    for (a in 0 until attrCount) {
                        if (attrPos + 20 > offset + chunkSize) break
                        val aNs = readIntLE(axmlBytes, attrPos)
                        val aName = readIntLE(axmlBytes, attrPos + 4)
                        val aType = axmlBytes[attrPos + 15].toInt() and 0xFF
                        val nsStr = if (aNs in strings.indices) strings[aNs] else ""
                        val nameStr = if (aName in strings.indices) strings[aName] else ""
                        val rid = if (aName in resIds.indices) resIds[aName] else 0
                        if (nameStr.isNotEmpty()) {
                            hints["$nsStr|$nameStr"] = OrigAttrHint(rid, aType)
                        }
                        attrPos += attrSize
                    }
                }
            }
            offset += chunkSize
        }
        return hints
    }

    /**
     * Encodes an XML attribute string value into `(Res_value.dataType, Res_value.data, optionalString)`.
     */
    private fun encodeAttributeValue(raw: String, preferredType: Int?): Triple<Int, Int, String?> {
        val trimmed = raw.trim()

        // Explicit @0x... reference or @null
        if (trimmed == "@null") return Triple(TYPE_REFERENCE, 0, null)
        if (trimmed.startsWith("@0x", ignoreCase = true)) {
            val parsed = trimmed.substring(3).toLongOrNull(16)
            if (parsed != null) return Triple(TYPE_REFERENCE, parsed.toInt(), null)
        }
        // Explicit ?0x... theme attribute reference
        if (trimmed.startsWith("?0x", ignoreCase = true)) {
            val parsed = trimmed.substring(3).toLongOrNull(16)
            if (parsed != null) return Triple(TYPE_ATTRIBUTE, parsed.toInt(), null)
        }
        // Color #AARRGGBB / #RRGGBB / #ARGB / #RGB
        if (trimmed.startsWith("#") && trimmed.length in listOf(4, 5, 7, 9)) {
            val hex = trimmed.substring(1)
            if (hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                val colorInt = parseColorHex(hex)
                val cType = when (hex.length) {
                    8 -> TYPE_INT_COLOR_ARGB8
                    6 -> TYPE_INT_COLOR_RGB8
                    4 -> TYPE_INT_COLOR_ARGB4
                    else -> TYPE_INT_COLOR_RGB4
                }
                return Triple(cType, colorInt, null)
            }
        }
        // Dimension (e.g. 16dp, 14sp, 2px, 1.5dp)
        parseDimensionValue(trimmed)?.let { dimBits ->
            return Triple(TYPE_DIMENSION, dimBits, null)
        }
        // Fraction (e.g. 50%, 100%p)
        parseFractionValue(trimmed)?.let { fracBits ->
            return Triple(TYPE_FRACTION, fracBits, null)
        }

        // If original type was strictly STRING, keep as string unless explicitly changed to a typed literal above
        if (preferredType == TYPE_STRING) {
            return Triple(TYPE_STRING, 0, raw)
        }

        // Booleans
        if (trimmed.equals("true", ignoreCase = true)) {
            return Triple(TYPE_INT_BOOLEAN, -1, null)
        }
        if (trimmed.equals("false", ignoreCase = true)) {
            return Triple(TYPE_INT_BOOLEAN, 0, null)
        }
        // Hex integers
        if (trimmed.startsWith("0x", ignoreCase = true)) {
            val parsed = trimmed.substring(2).toLongOrNull(16)
            if (parsed != null) return Triple(TYPE_INT_HEX, parsed.toInt(), null)
        }
        // Decimal integers (only if preferredType is numeric or string is strictly an integer without leading zeros)
        if (preferredType == TYPE_INT_DEC || preferredType == TYPE_INT_HEX ||
            (trimmed.matches(Regex("^-?(0|[1-9][0-9]{0,9})$")) && preferredType != TYPE_FLOAT)
        ) {
            val intVal = trimmed.toIntOrNull()
            if (intVal != null) {
                return Triple(if (preferredType == TYPE_INT_HEX) TYPE_INT_HEX else TYPE_INT_DEC, intVal, null)
            }
        }
        // Floats
        if (preferredType == TYPE_FLOAT || trimmed.matches(Regex("^-?[0-9]+\\.[0-9]+$"))) {
            val flt = trimmed.toFloatOrNull()
            if (flt != null) {
                return Triple(TYPE_FLOAT, java.lang.Float.floatToRawIntBits(flt), null)
            }
        }

        return Triple(TYPE_STRING, 0, raw)
    }

    private fun parseColorHex(hex: String): Int {
        val expanded = when (hex.length) {
            3 -> "FF${hex[0]}${hex[0]}${hex[1]}${hex[1]}${hex[2]}${hex[2]}"
            4 -> "${hex[0]}${hex[0]}${hex[1]}${hex[1]}${hex[2]}${hex[2]}${hex[3]}${hex[3]}"
            6 -> "FF$hex"
            else -> hex
        }
        return expanded.toLong(16).toInt()
    }

    private fun parseDimensionValue(s: String): Int? {
        val lower = s.lowercase(Locale.US)
        val unitIdx = when {
            lower.endsWith("dip") -> 1
            lower.endsWith("dp") -> 1
            lower.endsWith("sp") -> 2
            lower.endsWith("px") -> 0
            lower.endsWith("pt") -> 3
            lower.endsWith("in") -> 4
            lower.endsWith("mm") -> 5
            else -> return null
        }
        val numStr = when {
            lower.endsWith("dip") -> lower.dropLast(3)
            else -> lower.dropLast(2)
        }
        val value = numStr.toFloatOrNull() ?: return null
        return encodeComplexFloat(value, unitIdx)
    }

    private fun parseFractionValue(s: String): Int? {
        val unitIdx = when {
            s.endsWith("%p") -> 1
            s.endsWith("%") -> 0
            else -> return null
        }
        val numStr = if (unitIdx == 1) s.dropLast(2) else s.dropLast(1)
        val value = numStr.toFloatOrNull() ?: return null
        return encodeComplexFloat(value / 100f, unitIdx)
    }

    private fun encodeComplexFloat(value: Float, unitIdx: Int): Int {
        val absVal = kotlin.math.abs(value)
        val (radix, shift) = when {
            absVal == 0f -> 0 to 0
            absVal < 1.0f -> 3 to 23
            absVal < 256.0f -> 2 to 15
            absVal < 65536.0f -> 1 to 7
            else -> 0 to 0
        }
        val mantissa = kotlin.math.round(value * (1 shl shift)).toInt()
        return ((mantissa and 0xFFFFFF) shl 8) or ((radix and 0x3) shl 4) or (unitIdx and 0xF)
    }

    // =========================================================================
    // 3. RESOURCES.ARSC TYPED RESOURCE TABLE INSPECTOR & BINARY PATCHER
    // =========================================================================

    fun parseArscTypedResources(arscBytes: ByteArray, maxEntries: Int = 3000): List<ArscTypedResourceEntry> {
        if (arscBytes.size < 20) return emptyList()
        val rootType = readShortLE(arscBytes, 0)
        val rootHeaderSize = readShortLE(arscBytes, 2)
        val rootSize = readIntLE(arscBytes, 4).coerceAtMost(arscBytes.size)
        if (rootType != RES_TABLE_TYPE || rootHeaderSize < 12) return emptyList()

        var globalStrings: List<String> = emptyList()
        val results = mutableListOf<ArscTypedResourceEntry>()

        var offset = rootHeaderSize
        while (offset + 8 <= rootSize && results.size < maxEntries) {
            val chunkType = readShortLE(arscBytes, offset)
            val headerSize = readShortLE(arscBytes, offset + 2)
            val chunkSize = readIntLE(arscBytes, offset + 4)
            if (chunkSize < 8 || offset + chunkSize > rootSize) break

            when (chunkType) {
                RES_STRING_POOL_TYPE -> {
                    globalStrings = parseStringPoolOnly(arscBytes, offset, headerSize, chunkSize).orEmpty()
                }
                RES_TABLE_PACKAGE_TYPE -> {
                    parsePackageChunk(arscBytes, offset, headerSize, chunkSize, globalStrings, results, maxEntries)
                }
            }
            offset += chunkSize
        }

        return results
    }

    private fun parsePackageChunk(
        bytes: ByteArray,
        pkgOffset: Int,
        pkgHeaderSize: Int,
        pkgChunkSize: Int,
        globalStrings: List<String>,
        out: MutableList<ArscTypedResourceEntry>,
        maxEntries: Int
    ) {
        if (pkgHeaderSize < 284 || pkgOffset + pkgChunkSize > bytes.size) return
        val pkgId = readIntLE(bytes, pkgOffset + 8)
        val typeStringsRel = readIntLE(bytes, pkgOffset + 268)
        val keyStringsRel = readIntLE(bytes, pkgOffset + 276)

        val typeStrings = if (typeStringsRel > 0 && pkgOffset + typeStringsRel + 28 <= pkgOffset + pkgChunkSize) {
            val tsOff = pkgOffset + typeStringsRel
            parseStringPoolOnly(bytes, tsOff, readShortLE(bytes, tsOff + 2), readIntLE(bytes, tsOff + 4)).orEmpty()
        } else emptyList()

        val keyStrings = if (keyStringsRel > 0 && pkgOffset + keyStringsRel + 28 <= pkgOffset + pkgChunkSize) {
            val ksOff = pkgOffset + keyStringsRel
            parseStringPoolOnly(bytes, ksOff, readShortLE(bytes, ksOff + 2), readIntLE(bytes, ksOff + 4)).orEmpty()
        } else emptyList()

        val seenResIds = HashSet<Int>()
        var subOff = pkgOffset + pkgHeaderSize
        val pkgEnd = pkgOffset + pkgChunkSize
        while (subOff + 8 <= pkgEnd && out.size < maxEntries) {
            val subType = readShortLE(bytes, subOff)
            val subHeader = readShortLE(bytes, subOff + 2)
            val subSize = readIntLE(bytes, subOff + 4)
            if (subSize < 8 || subOff + subSize > pkgEnd) break

            if (subType == RES_TABLE_TYPE_TYPE && subHeader >= 20) {
                val typeId = bytes[subOff + 8].toInt() and 0xFF
                val flags = bytes[subOff + 9].toInt() and 0xFF
                val entryCount = readIntLE(bytes, subOff + 12)
                val entriesStart = readIntLE(bytes, subOff + 16)
                val isSparse = (flags and 0x01) != 0
                val isOffset16 = (flags and 0x02) != 0

                val typeName = typeStrings.getOrNull(typeId - 1) ?: "type_$typeId"
                if (!isSparse && entryCount in 1..65536) {
                    val offsetsBase = subOff + subHeader
                    val entriesBase = subOff + entriesStart
                    for (entryIdx in 0 until entryCount) {
                        if (out.size >= maxEntries) break
                        val relEntryOffset = if (isOffset16) {
                            val p = offsetsBase + entryIdx * 2
                            if (p + 2 > subOff + subSize) break
                            val raw16 = readShortLE(bytes, p)
                            if (raw16 == 0xFFFF) -1 else raw16 * 4
                        } else {
                            val p = offsetsBase + entryIdx * 4
                            if (p + 4 > subOff + subSize) break
                            readIntLE(bytes, p)
                        }
                        if (relEntryOffset < 0) continue

                        val entryPos = entriesBase + relEntryOffset
                        if (entryPos + 8 > subOff + subSize) continue
                        val entrySize = readShortLE(bytes, entryPos)
                        val entryFlags = readShortLE(bytes, entryPos + 2)
                        val keyIdx = readIntLE(bytes, entryPos + 4)
                        val isComplex = (entryFlags and 0x0001) != 0
                        if (isComplex) continue

                        val valuePos = entryPos + entrySize
                        if (valuePos + 8 > subOff + subSize) continue
                        val resId = (pkgId shl 24) or (typeId shl 16) or entryIdx
                        if (!seenResIds.add(resId)) continue

                        val valType = bytes[valuePos + 3].toInt() and 0xFF
                        val valData = readIntLE(bytes, valuePos + 4)
                        val entryName = keyStrings.getOrNull(keyIdx) ?: "res_${Integer.toHexString(entryIdx)}"
                        val displayVal = formatTypedValue(valType, valData, -1, globalStrings)
                        val spIdx = if (valType == TYPE_STRING && valData in globalStrings.indices) valData else null

                        out.add(
                            ArscTypedResourceEntry(
                                resourceId = resId,
                                resourceIdHex = "0x%08x".format(resId),
                                typeName = typeName,
                                entryName = entryName,
                                valueType = valType,
                                valueTypeLabel = valueTypeLabel(valType),
                                rawData = valData,
                                displayValue = displayVal,
                                valueOffsetInArsc = valuePos,
                                stringPoolIndex = spIdx
                            )
                        )
                    }
                }
            }
            subOff += subSize
        }
    }

    /**
     * Patches primitive typed resource entries (colors, booleans, ints, floats, dimensions) in `resources.arsc`
     * at their exact `Res_value` byte offsets.
     * Note: Apply this BEFORE `patchArscStrings` because string pool resizing shifts offsets after the string pool!
     */
    fun patchArscTypedValues(
        arscBytes: ByteArray,
        patchesByOffset: Map<Int, Pair<Int, Int>>
    ): ByteArray {
        if (patchesByOffset.isEmpty()) return arscBytes
        val out = arscBytes.copyOf()
        patchesByOffset.forEach { (valuePos, pair) ->
            val (newType, newData) = pair
            if (valuePos >= 0 && valuePos + 8 <= out.size) {
                out[valuePos + 3] = (newType and 0xFF).toByte()
                writeIntLE(out, valuePos + 4, newData)
            }
        }
        return out
    }

    /**
     * Parses a user-edited string for a typed ARSC resource entry into `(valueType, rawData)`.
     */
    fun parseEditedTypedResourceValue(currentType: Int, userInput: String): Pair<Int, Int>? {
        val trimmed = userInput.trim()
        return when (currentType) {
            TYPE_INT_COLOR_ARGB8, TYPE_INT_COLOR_RGB8, TYPE_INT_COLOR_ARGB4, TYPE_INT_COLOR_RGB4 -> {
                val clean = trimmed.removePrefix("#")
                if (clean.length !in listOf(3, 4, 6, 8) || !clean.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                    return null
                }
                val colorInt = parseColorHex(clean)
                TYPE_INT_COLOR_ARGB8 to colorInt
            }
            TYPE_INT_BOOLEAN -> {
                when {
                    trimmed.equals("true", ignoreCase = true) || trimmed == "1" -> TYPE_INT_BOOLEAN to -1
                    trimmed.equals("false", ignoreCase = true) || trimmed == "0" -> TYPE_INT_BOOLEAN to 0
                    else -> null
                }
            }
            TYPE_INT_DEC -> {
                val n = trimmed.toIntOrNull() ?: return null
                TYPE_INT_DEC to n
            }
            TYPE_INT_HEX -> {
                val n = trimmed.removePrefix("0x").removePrefix("0X").toLongOrNull(16) ?: return null
                TYPE_INT_HEX to n.toInt()
            }
            TYPE_FLOAT -> {
                val f = trimmed.toFloatOrNull() ?: return null
                TYPE_FLOAT to java.lang.Float.floatToRawIntBits(f)
            }
            TYPE_DIMENSION -> {
                val bits = parseDimensionValue(trimmed) ?: return null
                TYPE_DIMENSION to bits
            }
            TYPE_FRACTION -> {
                val bits = parseFractionValue(trimmed) ?: return null
                TYPE_FRACTION to bits
            }
            TYPE_REFERENCE -> {
                val hex = trimmed.removePrefix("@").removePrefix("0x").removePrefix("0X")
                val n = hex.toLongOrNull(16) ?: return null
                TYPE_REFERENCE to n.toInt()
            }
            else -> {
                val n = if (trimmed.startsWith("0x", ignoreCase = true)) {
                    trimmed.substring(2).toLongOrNull(16)?.toInt()
                } else {
                    trimmed.toIntOrNull()
                } ?: return null
                currentType to n
            }
        }
    }

    // =========================================================================
    // SHARED FORMATTERS & STRING POOL HELPERS
    // =========================================================================

    fun formatTypedValue(type: Int, data: Int, rawValueIdx: Int, strings: List<String>): String {
        return when (type) {
            TYPE_STRING -> {
                val idx = if (data in strings.indices) data else rawValueIdx
                if (idx in strings.indices) strings[idx] else ""
            }
            TYPE_REFERENCE -> if (data == 0) "@null" else "@0x%08x".format(data)
            TYPE_ATTRIBUTE -> "?0x%08x".format(data)
            TYPE_FLOAT -> formatCleanFloat(java.lang.Float.intBitsToFloat(data))
            TYPE_DIMENSION -> {
                val value = complexToFloat(data)
                val unit = DIMENSION_UNITS.getOrElse(data and 0xF) { "px" }
                "${formatCleanFloat(value)}$unit"
            }
            TYPE_FRACTION -> {
                val value = complexToFloat(data) * 100f
                val unit = FRACTION_UNITS.getOrElse(data and 0xF) { "%" }
                "${formatCleanFloat(value)}$unit"
            }
            TYPE_INT_DEC -> data.toString()
            TYPE_INT_HEX -> "0x${Integer.toHexString(data)}"
            TYPE_INT_BOOLEAN -> if (data != 0) "true" else "false"
            TYPE_INT_COLOR_ARGB8, TYPE_INT_COLOR_ARGB4 -> "#%08X".format(data)
            TYPE_INT_COLOR_RGB8, TYPE_INT_COLOR_RGB4 -> "#%06X".format(data and 0xFFFFFF)
            else -> if (rawValueIdx in strings.indices) strings[rawValueIdx] else "0x${Integer.toHexString(data)}"
        }
    }

    private fun valueTypeLabel(type: Int): String = when (type) {
        TYPE_STRING -> "STRING"
        TYPE_REFERENCE -> "REF"
        TYPE_ATTRIBUTE -> "ATTR"
        TYPE_FLOAT -> "FLOAT"
        TYPE_DIMENSION -> "DIMEN"
        TYPE_FRACTION -> "FRAC"
        TYPE_INT_DEC -> "INT"
        TYPE_INT_HEX -> "HEX"
        TYPE_INT_BOOLEAN -> "BOOL"
        TYPE_INT_COLOR_ARGB8, TYPE_INT_COLOR_RGB8, TYPE_INT_COLOR_ARGB4, TYPE_INT_COLOR_RGB4 -> "COLOR"
        else -> "RAW"
    }

    private fun complexToFloat(complex: Int): Float {
        val mantissa = (complex and 0xFFFFFF00.toInt()) ushr 8
        // Sign-extend 24-bit mantissa
        val signedMantissa = if ((mantissa and 0x800000) != 0) mantissa or 0xFF000000.toInt() else mantissa
        val radix = (complex ushr 4) and 0x3
        return signedMantissa * RADIX_MULTS[radix]
    }

    private fun formatCleanFloat(f: Float): String {
        val rounded = kotlin.math.round(f * 1000f) / 1000f
        return if (rounded == rounded.toInt().toFloat()) rounded.toInt().toString() else rounded.toString()
    }

    private fun parseStringPoolOnly(
        bytes: ByteArray,
        offset: Int,
        headerSize: Int,
        chunkSize: Int
    ): List<String>? {
        if (headerSize < 28 || offset + chunkSize > bytes.size) return null
        val stringCount = readIntLE(bytes, offset + 8)
        val flags = readIntLE(bytes, offset + 16)
        val stringsStart = readIntLE(bytes, offset + 20)
        val isUtf8 = (flags and (1 shl 8)) != 0
        if (stringCount < 0 || stringCount > 600_000) return null

        val offsets = IntArray(stringCount)
        var cursor = offset + headerSize
        for (i in 0 until stringCount) {
            if (cursor + 4 > offset + chunkSize) return null
            offsets[i] = readIntLE(bytes, cursor)
            cursor += 4
        }

        val dataBase = offset + stringsStart
        val out = ArrayList<String>(stringCount)
        for (i in 0 until stringCount) {
            val strPos = dataBase + offsets[i]
            if (strPos < dataBase || strPos >= offset + chunkSize) {
                out.add("")
                continue
            }
            if (isUtf8) {
                var p = strPos
                val u16 = bytes[p++].toInt() and 0xFF
                if ((u16 and 0x80) != 0 && p < offset + chunkSize) p++
                var u8Len = if (p < offset + chunkSize) (bytes[p++].toInt() and 0xFF) else 0
                if ((u8Len and 0x80) != 0 && p < offset + chunkSize) {
                    u8Len = ((u8Len and 0x7F) shl 8) or (bytes[p++].toInt() and 0xFF)
                }
                val safeLen = u8Len.coerceIn(0, (offset + chunkSize - p).coerceAtLeast(0))
                out.add(String(bytes, p, safeLen, Charsets.UTF_8))
            } else {
                var p = strPos
                var charLen = if (p + 2 <= offset + chunkSize) readShortLE(bytes, p) else 0
                p += 2
                if ((charLen and 0x8000) != 0 && p + 2 <= offset + chunkSize) {
                    val low = readShortLE(bytes, p)
                    p += 2
                    charLen = ((charLen and 0x7FFF) shl 16) or low
                }
                val byteLen = (charLen * 2).coerceIn(0, (offset + chunkSize - p).coerceAtLeast(0))
                out.add(String(bytes, p, byteLen, Charsets.UTF_16LE))
            }
        }
        return out
    }

    private fun serializeUtf16StringPool(strings: List<String>): ByteArray {
        val dataOut = ByteArrayOutputStream()
        val offsets = IntArray(strings.size)
        for (i in strings.indices) {
            val s = strings[i]
            offsets[i] = dataOut.size()
            val len = s.length
            if (len >= 0x8000) {
                val hi = ((len ushr 16) and 0x7FFF) or 0x8000
                val lo = len and 0xFFFF
                dataOut.write(hi and 0xFF)
                dataOut.write((hi ushr 8) and 0xFF)
                dataOut.write(lo and 0xFF)
                dataOut.write((lo ushr 8) and 0xFF)
            } else {
                dataOut.write(len and 0xFF)
                dataOut.write((len ushr 8) and 0xFF)
            }
            dataOut.write(s.toByteArray(Charsets.UTF_16LE))
            dataOut.write(0)
            dataOut.write(0)
        }
        while (dataOut.size() % 4 != 0) dataOut.write(0)
        val dataBytes = dataOut.toByteArray()

        val headerSize = 28
        val stringsStart = headerSize + strings.size * 4
        val chunkSize = stringsStart + dataBytes.size
        val buf = ByteBuffer.allocate(chunkSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(RES_STRING_POOL_TYPE.toShort())
        buf.putShort(headerSize.toShort())
        buf.putInt(chunkSize)
        buf.putInt(strings.size)
        buf.putInt(0) // styleCount
        buf.putInt(0) // UTF-16LE flags
        buf.putInt(stringsStart)
        buf.putInt(0) // stylesStart
        offsets.forEach { buf.putInt(it) }
        buf.put(dataBytes)
        return buf.array()
    }

    private fun readShortLE(b: ByteArray, off: Int): Int {
        return (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
    }

    private fun readIntLE(b: ByteArray, off: Int): Int {
        return (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)
    }

    private fun writeIntLE(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v ushr 8) and 0xFF).toByte()
        b[off + 2] = ((v ushr 16) and 0xFF).toByte()
        b[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    private fun escapeXml(input: String): String {
        return input
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }
}
