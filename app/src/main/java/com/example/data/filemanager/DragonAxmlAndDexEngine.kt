package com.example.data.filemanager

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.Adler32

data class ApkComponentInfo(
    val tagType: String, // activity, service, receiver, provider
    val name: String,
    val exported: Boolean?,
    val enabled: Boolean?
)

data class ApkMetaDataEntry(
    val name: String,
    val value: String,
    val isStringValue: Boolean
)

data class ParsedApkManifest(
    val packageName: String,
    val versionName: String,
    val versionCode: Int,
    val minSdkVersion: Int,
    val targetSdkVersion: Int,
    val installLocation: Int, // -1=unspecified, 0=auto, 1=internalOnly, 2=preferExternal
    val appLabel: String,
    val appLabelIsResourceRef: Boolean,
    val debuggable: Boolean,
    val allowBackup: Boolean,
    val usesCleartextTraffic: Boolean,
    val extractNativeLibs: Boolean,
    val largeHeap: Boolean,
    val testOnly: Boolean,
    val permissions: List<String>,
    val components: List<ApkComponentInfo>,
    val metaData: List<ApkMetaDataEntry>,
    val decompiledXml: String,
    val stringCount: Int
)

data class ApkManifestEditSpec(
    val newAppLabel: String? = null,
    val newPackageName: String? = null,
    val newVersionName: String? = null,
    val newVersionCode: Int? = null,
    val newMinSdkVersion: Int? = null,
    val newTargetSdkVersion: Int? = null,
    val newInstallLocation: Int? = null,
    val debuggable: Boolean? = null,
    val allowBackup: Boolean? = null,
    val usesCleartextTraffic: Boolean? = null,
    val extractNativeLibs: Boolean? = null,
    val largeHeap: Boolean? = null,
    val stripTestOnly: Boolean = false,
    val removedPermissions: Set<String> = emptySet(),
    val addedPermissions: List<String> = emptyList(),
    val componentExportedOverrides: Map<String, Boolean> = emptyMap(),
    val componentEnabledOverrides: Map<String, Boolean> = emptyMap(),
    val metaDataValueOverrides: Map<String, String> = emptyMap(),
    val addedMetaData: Map<String, String> = emptyMap(),
    val removedMetaData: Set<String> = emptySet()
) {
    fun hasAnyChanges(): Boolean {
        return newAppLabel != null ||
            newPackageName != null ||
            newVersionName != null ||
            newVersionCode != null ||
            newMinSdkVersion != null ||
            newTargetSdkVersion != null ||
            newInstallLocation != null ||
            debuggable != null ||
            allowBackup != null ||
            usesCleartextTraffic != null ||
            extractNativeLibs != null ||
            largeHeap != null ||
            stripTestOnly ||
            removedPermissions.isNotEmpty() ||
            addedPermissions.isNotEmpty() ||
            componentExportedOverrides.isNotEmpty() ||
            componentEnabledOverrides.isNotEmpty() ||
            metaDataValueOverrides.isNotEmpty() ||
            addedMetaData.isNotEmpty() ||
            removedMetaData.isNotEmpty()
    }
}

data class ArscStringEntry(
    val index: Int,
    val value: String
)

data class DexStringEntry(
    val index: Int,
    val dataOffset: Int,
    val byteLength: Int,
    val value: String
)

data class DexInspectionSummary(
    val entryPath: String,
    val dexVersion: String,
    val fileSize: Int,
    val stringCount: Int,
    val typeCount: Int,
    val fieldCount: Int,
    val methodCount: Int,
    val classCount: Int,
    val sampleStrings: List<DexStringEntry>
)

/**
 * Pure-Kotlin Android Binary XML (AXML) Decompiler & Binary Rebuilder,
 * `resources.arsc` Global String Pool Editor, and Dalvik `classes*.dex` Inspector & Safe String Patcher.
 */
object DragonAxmlAndDexEngine {

    // Android ResChunk_header types
    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_TABLE_TYPE = 0x0002
    private const val RES_XML_TYPE = 0x0003
    private const val RES_XML_START_NAMESPACE_TYPE = 0x0100
    private const val RES_XML_END_NAMESPACE_TYPE = 0x0101
    private const val RES_XML_START_ELEMENT_TYPE = 0x0102
    private const val RES_XML_END_ELEMENT_TYPE = 0x0103
    private const val RES_XML_CDATA_TYPE = 0x0104
    private const val RES_XML_RESOURCE_MAP_TYPE = 0x0180

    // Res_value data types
    private const val TYPE_REFERENCE = 0x01
    private const val TYPE_STRING = 0x03
    private const val TYPE_FLOAT = 0x04
    private const val TYPE_INT_DEC = 0x10
    private const val TYPE_INT_HEX = 0x11
    private const val TYPE_INT_BOOLEAN = 0x12

    // Standard android:* resource IDs
    private const val ATTR_LABEL = 0x01010001
    private const val ATTR_NAME = 0x01010003
    private const val ATTR_DEBUGGABLE = 0x0101000f
    private const val ATTR_EXPORTED = 0x01010010
    private const val ATTR_ENABLED = 0x0101000e
    private const val ATTR_VALUE = 0x01010024
    private const val ATTR_MIN_SDK_VERSION = 0x0101020c
    private const val ATTR_VERSION_CODE = 0x0101021b
    private const val ATTR_VERSION_NAME = 0x0101021c
    private const val ATTR_TARGET_SDK_VERSION = 0x01010270
    private const val ATTR_TEST_ONLY = 0x01010272
    private const val ATTR_ALLOW_BACKUP = 0x01010280
    private const val ATTR_INSTALL_LOCATION = 0x010102b7
    private const val ATTR_LARGE_HEAP = 0x0101035a
    private const val ATTR_EXTRACT_NATIVE_LIBS = 0x010104ea
    private const val ATTR_USES_CLEARTEXT_TRAFFIC = 0x010104ec

    private const val ANDROID_NS_URI = "http://schemas.android.com/apk/res/android"

    private data class MutableAxmlAttr(
        var nsIdx: Int,
        var nameIdx: Int,
        var rawValueIdx: Int,
        var valueType: Int,
        var valueData: Int
    )

    private sealed class AxmlChunk {
        data class StartNamespace(val lineNumber: Int, val commentIdx: Int, val prefixIdx: Int, val uriIdx: Int) : AxmlChunk()
        data class EndNamespace(val lineNumber: Int, val commentIdx: Int, val prefixIdx: Int, val uriIdx: Int) : AxmlChunk()
        data class StartElement(
            val lineNumber: Int,
            val commentIdx: Int,
            var nsIdx: Int,
            var nameIdx: Int,
            var idIndex: Int,
            var classIndex: Int,
            var styleIndex: Int,
            val attrs: MutableList<MutableAxmlAttr>
        ) : AxmlChunk()
        data class EndElement(val lineNumber: Int, val commentIdx: Int, val nsIdx: Int, val nameIdx: Int) : AxmlChunk()
        data class RawChunk(val type: Int, val rawBytes: ByteArray) : AxmlChunk()
    }

    private data class ParsedStringPool(
        val strings: MutableList<String>,
        val isUtf8: Boolean,
        val flags: Int,
        val styleCount: Int,
        val rawStyleSection: ByteArray,
        val rawStyleOffsets: IntArray
    )

    private data class AxmlDocument(
        val stringPool: ParsedStringPool,
        val resourceIds: MutableList<Int>,
        val chunks: MutableList<AxmlChunk>
    )

    // =========================================================================
    // 1. ANDROID BINARY XML (AXML) PARSER & DECOMPILER
    // =========================================================================

    fun parseManifest(axmlBytes: ByteArray, fallbackAppLabel: String = ""): ParsedApkManifest? {
        val doc = parseAxmlDocument(axmlBytes) ?: return null
        val strings = doc.stringPool.strings

        var packageName = ""
        var versionName = ""
        var versionCode = 1
        var minSdkVersion = 21
        var targetSdkVersion = 34
        var installLocation = -1
        var appLabel = fallbackAppLabel
        var appLabelIsRef = false
        var debuggable = false
        var allowBackup = true
        var usesCleartextTraffic = false
        var extractNativeLibs = true
        var largeHeap = false
        var testOnly = false

        val permissions = mutableListOf<String>()
        val components = mutableListOf<ApkComponentInfo>()
        val metaDataList = mutableListOf<ApkMetaDataEntry>()
        val xmlOut = StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        val nsPrefixByUri = LinkedHashMap<Int, String>()
        var indentLevel = 0

        fun getStr(idx: Int): String = if (idx in strings.indices) strings[idx] else ""
        fun getAttrResId(nameIdx: Int): Int = if (nameIdx in doc.resourceIds.indices) doc.resourceIds[nameIdx] else 0

        fun formatAttrValue(attr: MutableAxmlAttr): String {
            return when (attr.valueType) {
                TYPE_STRING -> getStr(if (attr.valueData in strings.indices) attr.valueData else attr.rawValueIdx)
                TYPE_INT_BOOLEAN -> if (attr.valueData != 0) "true" else "false"
                TYPE_INT_DEC -> attr.valueData.toString()
                TYPE_INT_HEX -> "0x${Integer.toHexString(attr.valueData)}"
                TYPE_REFERENCE -> "@0x${Integer.toHexString(attr.valueData)}"
                TYPE_FLOAT -> java.lang.Float.intBitsToFloat(attr.valueData).toString()
                else -> if (attr.rawValueIdx in strings.indices) getStr(attr.rawValueIdx) else "0x${Integer.toHexString(attr.valueData)}"
            }
        }

        for (chunk in doc.chunks) {
            when (chunk) {
                is AxmlChunk.StartNamespace -> {
                    nsPrefixByUri[chunk.uriIdx] = getStr(chunk.prefixIdx)
                }
                is AxmlChunk.StartElement -> {
                    val tagName = getStr(chunk.nameIdx)
                    val indent = "  ".repeat(indentLevel.coerceAtLeast(0))
                    xmlOut.append(indent).append('<').append(tagName)
                    if (indentLevel == 0) {
                        nsPrefixByUri.forEach { (uriIdx, prefix) ->
                            if (prefix.isNotBlank()) {
                                xmlOut.append(" xmlns:").append(prefix).append("=\"")
                                    .append(escapeXml(getStr(uriIdx))).append("\"")
                            }
                        }
                    }

                    // Structured metadata extraction
                    var compName = ""
                    var compExported: Boolean? = null
                    var compEnabled: Boolean? = null
                    var metaName = ""
                    var metaVal = ""
                    var metaIsStr = true

                    for (attr in chunk.attrs) {
                        val attrName = getStr(attr.nameIdx)
                        val attrResId = getAttrResId(attr.nameIdx)
                        val prefix = if (attr.nsIdx >= 0) nsPrefixByUri[attr.nsIdx] ?: "android" else ""
                        val fullAttrName = if (prefix.isNotEmpty()) "$prefix:$attrName" else attrName
                        val formattedVal = formatAttrValue(attr)
                        xmlOut.append('\n').append(indent).append("    ")
                            .append(fullAttrName).append("=\"").append(escapeXml(formattedVal)).append("\"")

                        when (tagName) {
                            "manifest" -> {
                                when {
                                    attrName == "package" && attr.nsIdx < 0 -> packageName = formattedVal
                                    attrResId == ATTR_VERSION_NAME || attrName == "versionName" -> versionName = formattedVal
                                    attrResId == ATTR_VERSION_CODE || attrName == "versionCode" -> versionCode = attr.valueData
                                    attrResId == ATTR_INSTALL_LOCATION || attrName == "installLocation" -> installLocation = attr.valueData
                                }
                            }
                            "uses-sdk" -> {
                                when {
                                    attrResId == ATTR_MIN_SDK_VERSION || attrName == "minSdkVersion" -> minSdkVersion = attr.valueData
                                    attrResId == ATTR_TARGET_SDK_VERSION || attrName == "targetSdkVersion" -> targetSdkVersion = attr.valueData
                                }
                            }
                            "uses-permission", "uses-permission-sdk-23" -> {
                                if (attrResId == ATTR_NAME || attrName == "name") {
                                    if (formattedVal.isNotBlank() && formattedVal !in permissions) {
                                        permissions.add(formattedVal)
                                    }
                                }
                            }
                            "application" -> {
                                when {
                                    attrResId == ATTR_LABEL || attrName == "label" -> {
                                        if (attr.valueType == TYPE_STRING) {
                                            appLabel = formattedVal
                                            appLabelIsRef = false
                                        } else {
                                            appLabelIsRef = true
                                            if (appLabel.isBlank()) appLabel = formattedVal
                                        }
                                    }
                                    attrResId == ATTR_DEBUGGABLE || attrName == "debuggable" -> debuggable = attr.valueData != 0
                                    attrResId == ATTR_ALLOW_BACKUP || attrName == "allowBackup" -> allowBackup = attr.valueData != 0
                                    attrResId == ATTR_USES_CLEARTEXT_TRAFFIC || attrName == "usesCleartextTraffic" -> usesCleartextTraffic = attr.valueData != 0
                                    attrResId == ATTR_EXTRACT_NATIVE_LIBS || attrName == "extractNativeLibs" -> extractNativeLibs = attr.valueData != 0
                                    attrResId == ATTR_LARGE_HEAP || attrName == "largeHeap" -> largeHeap = attr.valueData != 0
                                    attrResId == ATTR_TEST_ONLY || attrName == "testOnly" -> testOnly = attr.valueData != 0
                                }
                            }
                            "activity", "activity-alias", "service", "receiver", "provider" -> {
                                when {
                                    attrResId == ATTR_NAME || attrName == "name" -> compName = formattedVal
                                    attrResId == ATTR_EXPORTED || attrName == "exported" -> compExported = attr.valueData != 0
                                    attrResId == ATTR_ENABLED || attrName == "enabled" -> compEnabled = attr.valueData != 0
                                }
                            }
                            "meta-data" -> {
                                when {
                                    attrResId == ATTR_NAME || attrName == "name" -> metaName = formattedVal
                                    attrResId == ATTR_VALUE || attrName == "value" -> {
                                        metaVal = formattedVal
                                        metaIsStr = attr.valueType == TYPE_STRING
                                    }
                                }
                            }
                        }
                    }

                    if (tagName in setOf("activity", "activity-alias", "service", "receiver", "provider") && compName.isNotBlank()) {
                        components.add(ApkComponentInfo(tagName, compName, compExported, compEnabled))
                    }
                    if (tagName == "meta-data" && metaName.isNotBlank()) {
                        metaDataList.add(ApkMetaDataEntry(metaName, metaVal, metaIsStr))
                    }

                    xmlOut.append(">\n")
                    indentLevel++
                }
                is AxmlChunk.EndElement -> {
                    indentLevel = (indentLevel - 1).coerceAtLeast(0)
                    val tagName = getStr(chunk.nameIdx)
                    xmlOut.append("  ".repeat(indentLevel)).append("</").append(tagName).append(">\n")
                }
                else -> {}
            }
        }

        return ParsedApkManifest(
            packageName = packageName,
            versionName = versionName,
            versionCode = versionCode,
            minSdkVersion = minSdkVersion,
            targetSdkVersion = targetSdkVersion,
            installLocation = installLocation,
            appLabel = appLabel,
            appLabelIsResourceRef = appLabelIsRef,
            debuggable = debuggable,
            allowBackup = allowBackup,
            usesCleartextTraffic = usesCleartextTraffic,
            extractNativeLibs = extractNativeLibs,
            largeHeap = largeHeap,
            testOnly = testOnly,
            permissions = permissions,
            components = components,
            metaData = metaDataList,
            decompiledXml = xmlOut.toString(),
            stringCount = strings.size
        )
    }

    /**
     * Applies structured metadata, flag, permission, component, and meta-data modifications
     * directly to binary `AndroidManifest.xml` (AXML) and rebuilds a valid Android binary XML file.
     */
    fun applyManifestEdits(axmlBytes: ByteArray, spec: ApkManifestEditSpec): ByteArray {
        if (!spec.hasAnyChanges()) return axmlBytes
        val doc = parseAxmlDocument(axmlBytes) ?: return axmlBytes
        val strings = doc.stringPool.strings
        val resIds = doc.resourceIds

        fun getStr(idx: Int): String = if (idx in strings.indices) strings[idx] else ""
        fun getAttrResId(nameIdx: Int): Int = if (nameIdx in resIds.indices) resIds[nameIdx] else 0

        // Ensure a plain string exists in the string pool (appends at end if missing; NEVER reorders existing indices!)
        fun getOrAddString(value: String): Int {
            val existing = strings.indexOf(value)
            if (existing >= 0) return existing
            strings.add(value)
            return strings.lastIndex
        }

        // Ensure an android:* attribute name exists at an index that has its resource ID mapped in resourceIds!
        fun getOrAddAndroidAttrName(attrName: String, attrResId: Int): Int {
            for (i in resIds.indices) {
                if (resIds[i] == attrResId) return i
            }
            // Insert before any non-resource-mapped strings? No — shifting string indices would break existing chunks!
            // Instead, Android's AXML parser allows extending the resource map array up to the new string index!
            val existingIdx = strings.indexOf(attrName)
            val targetIdx = if (existingIdx >= 0) existingIdx else {
                strings.add(attrName)
                strings.lastIndex
            }
            while (resIds.size <= targetIdx) {
                resIds.add(0)
            }
            resIds[targetIdx] = attrResId
            return targetIdx
        }

        // Locate Android namespace URI index
        var androidNsUriIdx = strings.indexOf(ANDROID_NS_URI)
        if (androidNsUriIdx < 0) {
            androidNsUriIdx = doc.chunks
                .filterIsInstance<AxmlChunk.StartNamespace>()
                .firstOrNull { getStr(it.prefixIdx) == "android" }
                ?.uriIdx ?: getOrAddString(ANDROID_NS_URI)
        }

        fun setOrAddAttr(
            elem: AxmlChunk.StartElement,
            nsIdx: Int,
            attrName: String,
            attrResId: Int,
            type: Int,
            rawValIdx: Int,
            data: Int
        ) {
            val existing = elem.attrs.firstOrNull { attr ->
                val rid = getAttrResId(attr.nameIdx)
                (attrResId != 0 && rid == attrResId) ||
                    (getStr(attr.nameIdx) == attrName && attr.nsIdx == nsIdx)
            }
            if (existing != null) {
                existing.rawValueIdx = rawValIdx
                existing.valueType = type
                existing.valueData = data
            } else {
                val nameIdx = if (attrResId != 0) getOrAddAndroidAttrName(attrName, attrResId) else getOrAddString(attrName)
                elem.attrs.add(
                    MutableAxmlAttr(
                        nsIdx = nsIdx,
                        nameIdx = nameIdx,
                        rawValueIdx = rawValIdx,
                        valueType = type,
                        valueData = data
                    )
                )
            }
        }

        fun removeAttr(elem: AxmlChunk.StartElement, attrName: String, attrResId: Int) {
            elem.attrs.removeAll { attr ->
                val rid = getAttrResId(attr.nameIdx)
                (attrResId != 0 && rid == attrResId) || getStr(attr.nameIdx) == attrName
            }
        }

        // Track uses-sdk & application presence in case we need to insert children
        var foundUsesSdk = false
        var manifestStartIdx = -1
        var applicationStartIdx = -1

        // First pass: remove unwanted <uses-permission> / <meta-data> blocks & apply element attribute edits
        val updatedChunks = mutableListOf<AxmlChunk>()
        var i = 0
        while (i < doc.chunks.size) {
            val chunk = doc.chunks[i]
            if (chunk is AxmlChunk.StartElement) {
                val tagName = getStr(chunk.nameIdx)

                // Check permission removal
                if ((tagName == "uses-permission" || tagName == "uses-permission-sdk-23") && spec.removedPermissions.isNotEmpty()) {
                    val permNameAttr = chunk.attrs.firstOrNull { getAttrResId(it.nameIdx) == ATTR_NAME || getStr(it.nameIdx) == "name" }
                    val permName = permNameAttr?.let {
                        if (it.valueType == TYPE_STRING) getStr(it.valueData) else getStr(it.rawValueIdx)
                    }.orEmpty()
                    if (permName in spec.removedPermissions) {
                        // Skip this StartElement and its matching EndElement
                        var depth = 1
                        i++
                        while (i < doc.chunks.size && depth > 0) {
                            when (doc.chunks[i]) {
                                is AxmlChunk.StartElement -> depth++
                                is AxmlChunk.EndElement -> depth--
                                else -> {}
                            }
                            i++
                        }
                        continue
                    }
                }

                // Check meta-data removal
                if (tagName == "meta-data" && spec.removedMetaData.isNotEmpty()) {
                    val mNameAttr = chunk.attrs.firstOrNull { getAttrResId(it.nameIdx) == ATTR_NAME || getStr(it.nameIdx) == "name" }
                    val mName = mNameAttr?.let {
                        if (it.valueType == TYPE_STRING) getStr(it.valueData) else getStr(it.rawValueIdx)
                    }.orEmpty()
                    if (mName in spec.removedMetaData) {
                        var depth = 1
                        i++
                        while (i < doc.chunks.size && depth > 0) {
                            when (doc.chunks[i]) {
                                is AxmlChunk.StartElement -> depth++
                                is AxmlChunk.EndElement -> depth--
                                else -> {}
                            }
                            i++
                        }
                        continue
                    }
                }

                when (tagName) {
                    "manifest" -> {
                        manifestStartIdx = updatedChunks.size
                        spec.newPackageName?.takeIf { it.isNotBlank() }?.let { pkg ->
                            val strIdx = getOrAddString(pkg)
                            setOrAddAttr(chunk, -1, "package", 0, TYPE_STRING, strIdx, strIdx)
                        }
                        spec.newVersionName?.let { verName ->
                            val strIdx = getOrAddString(verName)
                            setOrAddAttr(chunk, androidNsUriIdx, "versionName", ATTR_VERSION_NAME, TYPE_STRING, strIdx, strIdx)
                        }
                        spec.newVersionCode?.let { verCode ->
                            setOrAddAttr(chunk, androidNsUriIdx, "versionCode", ATTR_VERSION_CODE, TYPE_INT_DEC, -1, verCode)
                        }
                        spec.newInstallLocation?.let { loc ->
                            if (loc < 0) {
                                removeAttr(chunk, "installLocation", ATTR_INSTALL_LOCATION)
                            } else {
                                setOrAddAttr(chunk, androidNsUriIdx, "installLocation", ATTR_INSTALL_LOCATION, TYPE_INT_DEC, -1, loc)
                            }
                        }
                    }
                    "uses-sdk" -> {
                        foundUsesSdk = true
                        spec.newMinSdkVersion?.let { minSdk ->
                            setOrAddAttr(chunk, androidNsUriIdx, "minSdkVersion", ATTR_MIN_SDK_VERSION, TYPE_INT_DEC, -1, minSdk)
                        }
                        spec.newTargetSdkVersion?.let { targetSdk ->
                            setOrAddAttr(chunk, androidNsUriIdx, "targetSdkVersion", ATTR_TARGET_SDK_VERSION, TYPE_INT_DEC, -1, targetSdk)
                        }
                    }
                    "application" -> {
                        applicationStartIdx = updatedChunks.size
                        spec.newAppLabel?.let { label ->
                            val strIdx = getOrAddString(label)
                            setOrAddAttr(chunk, androidNsUriIdx, "label", ATTR_LABEL, TYPE_STRING, strIdx, strIdx)
                        }
                        spec.debuggable?.let { dbg ->
                            setOrAddAttr(chunk, androidNsUriIdx, "debuggable", ATTR_DEBUGGABLE, TYPE_INT_BOOLEAN, -1, if (dbg) -1 else 0)
                        }
                        spec.allowBackup?.let { backup ->
                            setOrAddAttr(chunk, androidNsUriIdx, "allowBackup", ATTR_ALLOW_BACKUP, TYPE_INT_BOOLEAN, -1, if (backup) -1 else 0)
                        }
                        spec.usesCleartextTraffic?.let { clear ->
                            setOrAddAttr(chunk, androidNsUriIdx, "usesCleartextTraffic", ATTR_USES_CLEARTEXT_TRAFFIC, TYPE_INT_BOOLEAN, -1, if (clear) -1 else 0)
                        }
                        spec.extractNativeLibs?.let { extLibs ->
                            setOrAddAttr(chunk, androidNsUriIdx, "extractNativeLibs", ATTR_EXTRACT_NATIVE_LIBS, TYPE_INT_BOOLEAN, -1, if (extLibs) -1 else 0)
                        }
                        spec.largeHeap?.let { lh ->
                            setOrAddAttr(chunk, androidNsUriIdx, "largeHeap", ATTR_LARGE_HEAP, TYPE_INT_BOOLEAN, -1, if (lh) -1 else 0)
                        }
                        if (spec.stripTestOnly) {
                            removeAttr(chunk, "testOnly", ATTR_TEST_ONLY)
                        }
                    }
                    "activity", "activity-alias", "service", "receiver", "provider" -> {
                        val compNameAttr = chunk.attrs.firstOrNull { getAttrResId(it.nameIdx) == ATTR_NAME || getStr(it.nameIdx) == "name" }
                        val compName = compNameAttr?.let {
                            if (it.valueType == TYPE_STRING) getStr(it.valueData) else getStr(it.rawValueIdx)
                        }.orEmpty()
                        spec.componentExportedOverrides[compName]?.let { exp ->
                            setOrAddAttr(chunk, androidNsUriIdx, "exported", ATTR_EXPORTED, TYPE_INT_BOOLEAN, -1, if (exp) -1 else 0)
                        }
                        spec.componentEnabledOverrides[compName]?.let { en ->
                            setOrAddAttr(chunk, androidNsUriIdx, "enabled", ATTR_ENABLED, TYPE_INT_BOOLEAN, -1, if (en) -1 else 0)
                        }
                    }
                    "meta-data" -> {
                        val nameAttr = chunk.attrs.firstOrNull { getAttrResId(it.nameIdx) == ATTR_NAME || getStr(it.nameIdx) == "name" }
                        val mName = nameAttr?.let {
                            if (it.valueType == TYPE_STRING) getStr(it.valueData) else getStr(it.rawValueIdx)
                        }.orEmpty()
                        spec.metaDataValueOverrides[mName]?.let { newVal ->
                            val strIdx = getOrAddString(newVal)
                            setOrAddAttr(chunk, androidNsUriIdx, "value", ATTR_VALUE, TYPE_STRING, strIdx, strIdx)
                        }
                    }
                }
            }
            updatedChunks.add(chunk)
            i++
        }

        // Insert newly added <meta-data> right after <application> StartElement!
        if (applicationStartIdx >= 0 && spec.addedMetaData.isNotEmpty()) {
            val metaTagIdx = getOrAddString("meta-data")
            val nameAttrIdx = getOrAddAndroidAttrName("name", ATTR_NAME)
            val valAttrIdx = getOrAddAndroidAttrName("value", ATTR_VALUE)
            val metaChunks = mutableListOf<AxmlChunk>()
            spec.addedMetaData.forEach { (mKey, mVal) ->
                val cleanKey = mKey.trim()
                if (cleanKey.isNotEmpty()) {
                    val keyStrIdx = getOrAddString(cleanKey)
                    val valStrIdx = getOrAddString(mVal)
                    metaChunks.add(
                        AxmlChunk.StartElement(
                            lineNumber = 1,
                            commentIdx = -1,
                            nsIdx = -1,
                            nameIdx = metaTagIdx,
                            idIndex = 0,
                            classIndex = 0,
                            styleIndex = 0,
                            attrs = mutableListOf(
                                MutableAxmlAttr(androidNsUriIdx, nameAttrIdx, keyStrIdx, TYPE_STRING, keyStrIdx),
                                MutableAxmlAttr(androidNsUriIdx, valAttrIdx, valStrIdx, TYPE_STRING, valStrIdx)
                            )
                        )
                    )
                    metaChunks.add(AxmlChunk.EndElement(1, -1, -1, metaTagIdx))
                }
            }
            if (metaChunks.isNotEmpty()) {
                updatedChunks.addAll(applicationStartIdx + 1, metaChunks)
            }
        }

        // Insert newly added <uses-permission> and/or missing <uses-sdk> right after <manifest> StartElement!
        if (manifestStartIdx >= 0) {
            val insertPos = manifestStartIdx + 1
            val toInsert = mutableListOf<AxmlChunk>()

            if (!foundUsesSdk && (spec.newMinSdkVersion != null || spec.newTargetSdkVersion != null)) {
                val usesSdkTagIdx = getOrAddString("uses-sdk")
                val attrs = mutableListOf<MutableAxmlAttr>()
                spec.newMinSdkVersion?.let { minSdk ->
                    attrs.add(MutableAxmlAttr(androidNsUriIdx, getOrAddAndroidAttrName("minSdkVersion", ATTR_MIN_SDK_VERSION), -1, TYPE_INT_DEC, minSdk))
                }
                spec.newTargetSdkVersion?.let { targetSdk ->
                    attrs.add(MutableAxmlAttr(androidNsUriIdx, getOrAddAndroidAttrName("targetSdkVersion", ATTR_TARGET_SDK_VERSION), -1, TYPE_INT_DEC, targetSdk))
                }
                toInsert.add(AxmlChunk.StartElement(1, -1, -1, usesSdkTagIdx, 0, 0, 0, attrs))
                toInsert.add(AxmlChunk.EndElement(1, -1, -1, usesSdkTagIdx))
            }

            if (spec.addedPermissions.isNotEmpty()) {
                val usesPermTagIdx = getOrAddString("uses-permission")
                val nameAttrIdx = getOrAddAndroidAttrName("name", ATTR_NAME)
                spec.addedPermissions.distinct().forEach { perm ->
                    val permClean = perm.trim()
                    if (permClean.isNotEmpty()) {
                        val permStrIdx = getOrAddString(permClean)
                        toInsert.add(
                            AxmlChunk.StartElement(
                                lineNumber = 1,
                                commentIdx = -1,
                                nsIdx = -1,
                                nameIdx = usesPermTagIdx,
                                idIndex = 0,
                                classIndex = 0,
                                styleIndex = 0,
                                attrs = mutableListOf(
                                    MutableAxmlAttr(
                                        nsIdx = androidNsUriIdx,
                                        nameIdx = nameAttrIdx,
                                        rawValueIdx = permStrIdx,
                                        valueType = TYPE_STRING,
                                        valueData = permStrIdx
                                    )
                                )
                            )
                        )
                        toInsert.add(AxmlChunk.EndElement(1, -1, -1, usesPermTagIdx))
                    }
                }
            }

            if (toInsert.isNotEmpty()) {
                updatedChunks.addAll(insertPos, toInsert)
            }
        }

        doc.chunks.clear()
        doc.chunks.addAll(updatedChunks)
        return serializeAxmlDocument(doc)
    }

    private fun parseAxmlDocument(bytes: ByteArray): AxmlDocument? {
        if (bytes.size < 16) return null
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val rootType = bb.short.toInt() and 0xFFFF
        val rootHeaderSize = bb.short.toInt() and 0xFFFF
        val rootSize = bb.int
        if (rootType != RES_XML_TYPE || rootHeaderSize < 8 || rootSize > bytes.size) return null

        var stringPool: ParsedStringPool? = null
        val resourceIds = mutableListOf<Int>()
        val chunks = mutableListOf<AxmlChunk>()

        var offset = rootHeaderSize
        while (offset + 8 <= rootSize) {
            val chunkType = (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
            val headerSize = (bytes[offset + 2].toInt() and 0xFF) or ((bytes[offset + 3].toInt() and 0xFF) shl 8)
            val chunkSize = readIntLE(bytes, offset + 4)
            if (chunkSize < 8 || offset + chunkSize > bytes.size) break

            when (chunkType) {
                RES_STRING_POOL_TYPE -> {
                    stringPool = parseStringPoolChunk(bytes, offset, headerSize, chunkSize)
                }
                RES_XML_RESOURCE_MAP_TYPE -> {
                    var pos = offset + headerSize
                    val end = offset + chunkSize
                    while (pos + 4 <= end) {
                        resourceIds.add(readIntLE(bytes, pos))
                        pos += 4
                    }
                }
                RES_XML_START_NAMESPACE_TYPE -> {
                    val lineNumber = readIntLE(bytes, offset + 8)
                    val commentIdx = readIntLE(bytes, offset + 12)
                    val prefixIdx = readIntLE(bytes, offset + headerSize)
                    val uriIdx = readIntLE(bytes, offset + headerSize + 4)
                    chunks.add(AxmlChunk.StartNamespace(lineNumber, commentIdx, prefixIdx, uriIdx))
                }
                RES_XML_END_NAMESPACE_TYPE -> {
                    val lineNumber = readIntLE(bytes, offset + 8)
                    val commentIdx = readIntLE(bytes, offset + 12)
                    val prefixIdx = readIntLE(bytes, offset + headerSize)
                    val uriIdx = readIntLE(bytes, offset + headerSize + 4)
                    chunks.add(AxmlChunk.EndNamespace(lineNumber, commentIdx, prefixIdx, uriIdx))
                }
                RES_XML_START_ELEMENT_TYPE -> {
                    val lineNumber = readIntLE(bytes, offset + 8)
                    val commentIdx = readIntLE(bytes, offset + 12)
                    val body = offset + headerSize
                    val nsIdx = readIntLE(bytes, body)
                    val nameIdx = readIntLE(bytes, body + 4)
                    val attrStart = readShortLE(bytes, body + 8)
                    val attrSize = readShortLE(bytes, body + 10).coerceAtLeast(20)
                    val attrCount = readShortLE(bytes, body + 12)
                    val idIndex = readShortLE(bytes, body + 14)
                    val classIndex = readShortLE(bytes, body + 16)
                    val styleIndex = readShortLE(bytes, body + 18)

                    val attrs = mutableListOf<MutableAxmlAttr>()
                    var attrPos = body + attrStart
                    for (a in 0 until attrCount) {
                        if (attrPos + 20 > offset + chunkSize) break
                        val aNs = readIntLE(bytes, attrPos)
                        val aName = readIntLE(bytes, attrPos + 4)
                        val aRawVal = readIntLE(bytes, attrPos + 8)
                        // Res_value: size(2), res0(1), dataType(1), data(4)
                        val aType = bytes[attrPos + 15].toInt() and 0xFF
                        val aData = readIntLE(bytes, attrPos + 16)
                        attrs.add(MutableAxmlAttr(aNs, aName, aRawVal, aType, aData))
                        attrPos += attrSize
                    }
                    chunks.add(AxmlChunk.StartElement(lineNumber, commentIdx, nsIdx, nameIdx, idIndex, classIndex, styleIndex, attrs))
                }
                RES_XML_END_ELEMENT_TYPE -> {
                    val lineNumber = readIntLE(bytes, offset + 8)
                    val commentIdx = readIntLE(bytes, offset + 12)
                    val body = offset + headerSize
                    val nsIdx = readIntLE(bytes, body)
                    val nameIdx = readIntLE(bytes, body + 4)
                    chunks.add(AxmlChunk.EndElement(lineNumber, commentIdx, nsIdx, nameIdx))
                }
                else -> {
                    chunks.add(AxmlChunk.RawChunk(chunkType, bytes.copyOfRange(offset, offset + chunkSize)))
                }
            }
            offset += chunkSize
        }

        val sp = stringPool ?: return null
        return AxmlDocument(sp, resourceIds, chunks)
    }

    private fun serializeAxmlDocument(doc: AxmlDocument): ByteArray {
        val bodyOut = ByteArrayOutputStream()

        // 1. String pool chunk
        bodyOut.write(serializeStringPoolChunk(doc.stringPool))

        // 2. Resource map chunk
        if (doc.resourceIds.isNotEmpty()) {
            val mapSize = 8 + doc.resourceIds.size * 4
            val mapBuf = ByteBuffer.allocate(mapSize).order(ByteOrder.LITTLE_ENDIAN)
            mapBuf.putShort(RES_XML_RESOURCE_MAP_TYPE.toShort())
            mapBuf.putShort(8.toShort())
            mapBuf.putInt(mapSize)
            doc.resourceIds.forEach { mapBuf.putInt(it) }
            bodyOut.write(mapBuf.array())
        }

        // 3. XML tree chunks
        for (chunk in doc.chunks) {
            when (chunk) {
                is AxmlChunk.StartNamespace -> {
                    val buf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                    buf.putShort(RES_XML_START_NAMESPACE_TYPE.toShort())
                    buf.putShort(16.toShort())
                    buf.putInt(24)
                    buf.putInt(chunk.lineNumber)
                    buf.putInt(chunk.commentIdx)
                    buf.putInt(chunk.prefixIdx)
                    buf.putInt(chunk.uriIdx)
                    bodyOut.write(buf.array())
                }
                is AxmlChunk.EndNamespace -> {
                    val buf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                    buf.putShort(RES_XML_END_NAMESPACE_TYPE.toShort())
                    buf.putShort(16.toShort())
                    buf.putInt(24)
                    buf.putInt(chunk.lineNumber)
                    buf.putInt(chunk.commentIdx)
                    buf.putInt(chunk.prefixIdx)
                    buf.putInt(chunk.uriIdx)
                    bodyOut.write(buf.array())
                }
                is AxmlChunk.StartElement -> {
                    val totalSize = 16 + 20 + chunk.attrs.size * 20
                    val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
                    buf.putShort(RES_XML_START_ELEMENT_TYPE.toShort())
                    buf.putShort(16.toShort())
                    buf.putInt(totalSize)
                    buf.putInt(chunk.lineNumber)
                    buf.putInt(chunk.commentIdx)
                    buf.putInt(chunk.nsIdx)
                    buf.putInt(chunk.nameIdx)
                    buf.putShort(20.toShort()) // attributeStart
                    buf.putShort(20.toShort()) // attributeSize
                    buf.putShort(chunk.attrs.size.toShort())
                    buf.putShort(chunk.idIndex.toShort())
                    buf.putShort(chunk.classIndex.toShort())
                    buf.putShort(chunk.styleIndex.toShort())
                    for (attr in chunk.attrs) {
                        buf.putInt(attr.nsIdx)
                        buf.putInt(attr.nameIdx)
                        buf.putInt(attr.rawValueIdx)
                        buf.putShort(8.toShort()) // Res_value.size
                        buf.put(0.toByte())       // Res_value.res0
                        buf.put(attr.valueType.toByte())
                        buf.putInt(attr.valueData)
                    }
                    bodyOut.write(buf.array())
                }
                is AxmlChunk.EndElement -> {
                    val buf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                    buf.putShort(RES_XML_END_ELEMENT_TYPE.toShort())
                    buf.putShort(16.toShort())
                    buf.putInt(24)
                    buf.putInt(chunk.lineNumber)
                    buf.putInt(chunk.commentIdx)
                    buf.putInt(chunk.nsIdx)
                    buf.putInt(chunk.nameIdx)
                    bodyOut.write(buf.array())
                }
                is AxmlChunk.RawChunk -> {
                    bodyOut.write(chunk.rawBytes)
                }
            }
        }

        val bodyBytes = bodyOut.toByteArray()
        val fullSize = 8 + bodyBytes.size
        val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        header.putShort(RES_XML_TYPE.toShort())
        header.putShort(8.toShort())
        header.putInt(fullSize)

        val finalOut = ByteArrayOutputStream(fullSize)
        finalOut.write(header.array())
        finalOut.write(bodyBytes)
        return finalOut.toByteArray()
    }

    // =========================================================================
    // 2. RESOURCES.ARSC GLOBAL STRING POOL READER & BINARY PATCHER
    // =========================================================================

    fun parseArscStrings(arscBytes: ByteArray): List<ArscStringEntry> {
        if (arscBytes.size < 20) return emptyList()
        val rootType = readShortLE(arscBytes, 0)
        val rootHeaderSize = readShortLE(arscBytes, 2)
        if (rootType != RES_TABLE_TYPE || rootHeaderSize < 12) return emptyList()

        val spOffset = rootHeaderSize
        if (spOffset + 28 > arscBytes.size) return emptyList()
        val spType = readShortLE(arscBytes, spOffset)
        val spHeaderSize = readShortLE(arscBytes, spOffset + 2)
        val spChunkSize = readIntLE(arscBytes, spOffset + 4)
        if (spType != RES_STRING_POOL_TYPE || spOffset + spChunkSize > arscBytes.size) return emptyList()

        val sp = parseStringPoolChunk(arscBytes, spOffset, spHeaderSize, spChunkSize) ?: return emptyList()
        return sp.strings.mapIndexed { idx, value -> ArscStringEntry(idx, value) }
    }

    /**
     * Rebuilds the global string pool inside `resources.arsc` with updated strings while keeping
     * all downstream package/type/config resource tables intact and updating the table root chunk size.
     */
    fun patchArscStrings(arscBytes: ByteArray, replacements: Map<Int, String>): ByteArray {
        if (replacements.isEmpty() || arscBytes.size < 20) return arscBytes
        val rootType = readShortLE(arscBytes, 0)
        val rootHeaderSize = readShortLE(arscBytes, 2)
        if (rootType != RES_TABLE_TYPE || rootHeaderSize < 12) return arscBytes

        val spOffset = rootHeaderSize
        val spType = readShortLE(arscBytes, spOffset)
        val spHeaderSize = readShortLE(arscBytes, spOffset + 2)
        val oldSpChunkSize = readIntLE(arscBytes, spOffset + 4)
        if (spType != RES_STRING_POOL_TYPE || spOffset + oldSpChunkSize > arscBytes.size) return arscBytes

        val sp = parseStringPoolChunk(arscBytes, spOffset, spHeaderSize, oldSpChunkSize) ?: return arscBytes
        var changed = false
        replacements.forEach { (idx, newText) ->
            if (idx in sp.strings.indices && sp.strings[idx] != newText) {
                sp.strings[idx] = newText
                changed = true
            }
        }
        if (!changed) return arscBytes

        val newSpBytes = serializeStringPoolChunk(sp)
        val tailStart = spOffset + oldSpChunkSize
        val newTotalSize = rootHeaderSize + newSpBytes.size + (arscBytes.size - tailStart)

        val out = ByteArrayOutputStream(newTotalSize)
        val newRootHeader = arscBytes.copyOfRange(0, rootHeaderSize)
        // Patch root chunk size at bytes 4..7
        writeIntLE(newRootHeader, 4, newTotalSize)
        out.write(newRootHeader)
        out.write(newSpBytes)
        out.write(arscBytes, tailStart, arscBytes.size - tailStart)
        return out.toByteArray()
    }

    // =========================================================================
    // 3. SHARED ANDROID RES_STRING_POOL PARSER & SERIALIZER (UTF-8 & UTF-16LE)
    // =========================================================================

    private fun parseStringPoolChunk(
        bytes: ByteArray,
        offset: Int,
        headerSize: Int,
        chunkSize: Int
    ): ParsedStringPool? {
        if (headerSize < 28 || offset + chunkSize > bytes.size) return null
        val stringCount = readIntLE(bytes, offset + 8)
        val styleCount = readIntLE(bytes, offset + 12)
        val flags = readIntLE(bytes, offset + 16)
        val stringsStart = readIntLE(bytes, offset + 20)
        val stylesStart = readIntLE(bytes, offset + 24)
        val isUtf8 = (flags and (1 shl 8)) != 0

        if (stringCount < 0 || stringCount > 500_000) return null
        val stringOffsets = IntArray(stringCount)
        var cursor = offset + headerSize
        for (i in 0 until stringCount) {
            if (cursor + 4 > offset + chunkSize) return null
            stringOffsets[i] = readIntLE(bytes, cursor)
            cursor += 4
        }

        val styleOffsets = IntArray(styleCount.coerceAtLeast(0))
        for (i in 0 until styleOffsets.size) {
            if (cursor + 4 > offset + chunkSize) break
            styleOffsets[i] = readIntLE(bytes, cursor)
            cursor += 4
        }

        val dataBase = offset + stringsStart
        val strings = ArrayList<String>(stringCount)
        for (i in 0 until stringCount) {
            val strPos = dataBase + stringOffsets[i]
            if (strPos < dataBase || strPos >= offset + chunkSize) {
                strings.add("")
                continue
            }
            if (isUtf8) {
                var p = strPos
                // Skip UTF-16 char length (1 or 2 bytes)
                val u16Len1 = bytes[p++].toInt() and 0xFF
                if ((u16Len1 and 0x80) != 0 && p < offset + chunkSize) p++
                // Read UTF-8 byte length (1 or 2 bytes)
                var u8Len = if (p < offset + chunkSize) (bytes[p++].toInt() and 0xFF) else 0
                if ((u8Len and 0x80) != 0 && p < offset + chunkSize) {
                    u8Len = ((u8Len and 0x7F) shl 8) or (bytes[p++].toInt() and 0xFF)
                }
                val safeLen = u8Len.coerceIn(0, (offset + chunkSize - p).coerceAtLeast(0))
                strings.add(String(bytes, p, safeLen, Charsets.UTF_8))
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
                strings.add(String(bytes, p, byteLen, Charsets.UTF_16LE))
            }
        }

        val rawStyles = if (styleCount > 0 && stylesStart > 0 && offset + stylesStart < offset + chunkSize) {
            bytes.copyOfRange(offset + stylesStart, offset + chunkSize)
        } else {
            ByteArray(0)
        }

        return ParsedStringPool(
            strings = strings,
            isUtf8 = isUtf8,
            flags = flags,
            styleCount = styleCount,
            rawStyleSection = rawStyles,
            rawStyleOffsets = styleOffsets
        )
    }

    private fun serializeStringPoolChunk(sp: ParsedStringPool): ByteArray {
        val stringDataOut = ByteArrayOutputStream()
        val stringOffsets = IntArray(sp.strings.size)

        for (i in sp.strings.indices) {
            val str = sp.strings[i]
            stringOffsets[i] = stringDataOut.size()
            if (sp.isUtf8) {
                val utf16Len = str.length
                val utf8Bytes = str.toByteArray(Charsets.UTF_8)
                val utf8Len = utf8Bytes.size
                writeUtf8PoolLen(stringDataOut, utf16Len)
                writeUtf8PoolLen(stringDataOut, utf8Len)
                stringDataOut.write(utf8Bytes)
                stringDataOut.write(0)
            } else {
                val utf16Len = str.length
                if (utf16Len >= 0x8000) {
                    val hi = ((utf16Len ushr 16) and 0x7FFF) or 0x8000
                    val lo = utf16Len and 0xFFFF
                    stringDataOut.write(hi and 0xFF)
                    stringDataOut.write((hi ushr 8) and 0xFF)
                    stringDataOut.write(lo and 0xFF)
                    stringDataOut.write((lo ushr 8) and 0xFF)
                } else {
                    stringDataOut.write(utf16Len and 0xFF)
                    stringDataOut.write((utf16Len ushr 8) and 0xFF)
                }
                stringDataOut.write(str.toByteArray(Charsets.UTF_16LE))
                stringDataOut.write(0)
                stringDataOut.write(0)
            }
        }
        // Pad string data to 4-byte boundary
        while (stringDataOut.size() % 4 != 0) {
            stringDataOut.write(0)
        }
        val stringDataBytes = stringDataOut.toByteArray()

        val headerSize = 28
        val offsetsSize = (sp.strings.size + sp.rawStyleOffsets.size) * 4
        val stringsStart = headerSize + offsetsSize
        val hasStyles = sp.styleCount > 0 && sp.rawStyleSection.isNotEmpty()
        val stylesStart = if (hasStyles) stringsStart + stringDataBytes.size else 0
        val paddedStyles = if (hasStyles) {
            val rem = sp.rawStyleSection.size % 4
            if (rem == 0) sp.rawStyleSection else sp.rawStyleSection + ByteArray(4 - rem)
        } else {
            ByteArray(0)
        }

        val chunkSize = stringsStart + stringDataBytes.size + paddedStyles.size
        // Clear SORTED_FLAG (bit 0) since edited strings may no longer be sorted
        val safeFlags = sp.flags and 0xFFFFFFFE.toInt()

        val buf = ByteBuffer.allocate(chunkSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(RES_STRING_POOL_TYPE.toShort())
        buf.putShort(headerSize.toShort())
        buf.putInt(chunkSize)
        buf.putInt(sp.strings.size)
        buf.putInt(sp.rawStyleOffsets.size)
        buf.putInt(safeFlags)
        buf.putInt(stringsStart)
        buf.putInt(stylesStart)
        stringOffsets.forEach { buf.putInt(it) }
        sp.rawStyleOffsets.forEach { buf.putInt(it) }
        buf.put(stringDataBytes)
        if (paddedStyles.isNotEmpty()) {
            buf.put(paddedStyles)
        }
        return buf.array()
    }

    private fun writeUtf8PoolLen(out: ByteArrayOutputStream, len: Int) {
        if (len >= 0x80) {
            out.write(((len ushr 8) and 0x7F) or 0x80)
            out.write(len and 0xFF)
        } else {
            out.write(len and 0x7F)
        }
    }

    // =========================================================================
    // 4. DALVIK DEX (classes*.dex) INSPECTOR & SAFE IN-PLACE STRING PATCHER
    // =========================================================================

    fun inspectDex(entryPath: String, dexBytes: ByteArray, maxStrings: Int = 2500): DexInspectionSummary? {
        if (dexBytes.size < 112) return null
        if (dexBytes[0] != 'd'.code.toByte() ||
            dexBytes[1] != 'e'.code.toByte() ||
            dexBytes[2] != 'x'.code.toByte() ||
            dexBytes[3] != '\n'.code.toByte()
        ) {
            return null
        }
        val dexVersion = String(dexBytes, 4, 3, Charsets.US_ASCII)
        val fileSize = readIntLE(dexBytes, 32)
        val stringIdsSize = readIntLE(dexBytes, 56).coerceAtLeast(0)
        val stringIdsOff = readIntLE(dexBytes, 60)
        val typeIdsSize = readIntLE(dexBytes, 64).coerceAtLeast(0)
        val fieldIdsSize = readIntLE(dexBytes, 80).coerceAtLeast(0)
        val methodIdsSize = readIntLE(dexBytes, 88).coerceAtLeast(0)
        val classDefsSize = readIntLE(dexBytes, 96).coerceAtLeast(0)

        val decodedStrings = mutableListOf<DexStringEntry>()
        val limit = minOf(stringIdsSize, maxStrings)
        for (idx in 0 until limit) {
            val idPos = stringIdsOff + idx * 4
            if (idPos + 4 > dexBytes.size) break
            val dataOff = readIntLE(dexBytes, idPos)
            if (dataOff <= 0 || dataOff >= dexBytes.size) continue
            // Skip ULEB128 utf16_size prefix
            var p = dataOff
            var shift = 0
            while (p < dexBytes.size && shift < 35) {
                val b = dexBytes[p++].toInt() and 0xFF
                if ((b and 0x80) == 0) break
                shift += 7
            }
            val strStart = p
            while (p < dexBytes.size && dexBytes[p] != 0.toByte() && (p - strStart) < 512) {
                p++
            }
            val byteLen = p - strStart
            if (byteLen > 0) {
                val text = runCatching { String(dexBytes, strStart, byteLen, Charsets.UTF_8) }.getOrDefault("")
                if (text.isNotBlank()) {
                    decodedStrings.add(DexStringEntry(idx, strStart, byteLen, text))
                }
            }
        }

        return DexInspectionSummary(
            entryPath = entryPath,
            dexVersion = dexVersion,
            fileSize = fileSize,
            stringCount = stringIdsSize,
            typeCount = typeIdsSize,
            fieldCount = fieldIdsSize,
            methodCount = methodIdsSize,
            classCount = classDefsSize,
            sampleStrings = decodedStrings
        )
    }

    /**
     * Safely patches strings in-place inside a `classes*.dex` file (preserving exact UTF-8 byte length
     * by padding with ASCII spaces or truncating to fit so no DEX offsets or ULEB128 headers break)
     * and recomputes the mandatory SHA-1 signature & Adler32 checksum!
     */
    fun patchDexStringsInPlace(dexBytes: ByteArray, patchesByDataOffset: Map<Int, Pair<Int, String>>): ByteArray {
        if (patchesByDataOffset.isEmpty() || dexBytes.size < 112) return dexBytes
        val out = dexBytes.copyOf()
        var modified = false

        patchesByDataOffset.forEach { (dataStartOffset, pair) ->
            val (origByteLen, replacementText) = pair
            if (dataStartOffset >= 112 && origByteLen > 0 && dataStartOffset + origByteLen <= out.size) {
                val rawNew = replacementText.toByteArray(Charsets.UTF_8)
                val padded = ByteArray(origByteLen) { ' '.code.toByte() }
                val copyLen = minOf(rawNew.size, origByteLen)
                System.arraycopy(rawNew, 0, padded, 0, copyLen)
                System.arraycopy(padded, 0, out, dataStartOffset, origByteLen)
                modified = true
            }
        }

        if (!modified) return dexBytes

        // 1. Recalculate DEX SHA-1 signature over bytes [32 .. EOF) -> store at [12 .. 31]
        val sha1 = MessageDigest.getInstance("SHA-1")
        sha1.update(out, 32, out.size - 32)
        val digest = sha1.digest()
        System.arraycopy(digest, 0, out, 12, 20)

        // 2. Recalculate DEX Adler32 checksum over bytes [12 .. EOF) -> store at [8 .. 11]
        val adler = Adler32()
        adler.update(out, 12, out.size - 12)
        writeIntLE(out, 8, adler.value.toInt())

        return out
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

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
