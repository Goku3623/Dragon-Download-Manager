package com.example.data.filemanager

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.Adler32

data class DexHeaderInfo(
    val dexVersion: String,
    val checksumHex: String,
    val sha1Hex: String,
    val fileSize: Int,
    val stringIdsSize: Int,
    val stringIdsOff: Int,
    val typeIdsSize: Int,
    val typeIdsOff: Int,
    val protoIdsSize: Int,
    val protoIdsOff: Int,
    val fieldIdsSize: Int,
    val fieldIdsOff: Int,
    val methodIdsSize: Int,
    val methodIdsOff: Int,
    val classDefsSize: Int,
    val classDefsOff: Int,
    val dataSize: Int,
    val dataOff: Int
)

data class DexFullStringItem(
    val index: Int,
    val itemOffset: Int, // start of ULEB128 length prefix
    val dataOffset: Int, // start of MUTF-8 bytes
    val byteLength: Int,
    val value: String
)

data class DexClassSummary(
    val classDefIndex: Int,
    val descriptor: String,      // e.g. Lcom/example/MainActivity;
    val fullJavaName: String,    // e.g. com.example.MainActivity
    val packageName: String,     // e.g. com.example
    val simpleName: String,      // e.g. MainActivity
    val superClassName: String,
    val accessFlags: Int,
    val sourceFileName: String,
    val classDataOff: Int
)

data class DexFieldDef(
    val name: String,
    val typeDescriptor: String,
    val javaType: String,
    val accessFlags: Int,
    val isStatic: Boolean
)

data class DexMethodDef(
    val methodIdx: Int,
    val name: String,
    val returnType: String,
    val parameterTypes: List<String>,
    val accessFlags: Int,
    val isDirect: Boolean,
    val codeOff: Int,
    val registersSize: Int,
    val insSize: Int,
    val outsSize: Int,
    val insnsFileOffset: Int,
    val insnsCodeUnitCount: Int
)

data class DexClassDetail(
    val summary: DexClassSummary,
    val interfaces: List<String>,
    val fields: List<DexFieldDef>,
    val methods: List<DexMethodDef>,
    val smaliCode: String,
    val javaPseudocode: String
)

data class ParsedDexFile(
    val name: String,
    val header: DexHeaderInfo,
    val strings: List<DexFullStringItem>,
    val typeDescriptors: List<String>,
    val classes: List<DexClassSummary>,
    val rawBytes: ByteArray
)

/**
 * Pure-Kotlin Professional Dalvik Executable (`.dex`) Decompiler,
 * Smali & Java-like Pseudocode Disassembler, Arbitrary-Length String Patcher,
 * and Method Dalvik Bytecode Editor with automatic SHA-1 & Adler32 recalculation.
 */
object DragonDexDecompilerEngine {

    fun isValidDex(bytes: ByteArray): Boolean {
        return bytes.size >= 112 &&
            bytes[0] == 'd'.code.toByte() &&
            bytes[1] == 'e'.code.toByte() &&
            bytes[2] == 'x'.code.toByte() &&
            bytes[3] == '\n'.code.toByte()
    }

    fun parseDex(name: String, dexBytes: ByteArray): ParsedDexFile? {
        if (!isValidDex(dexBytes)) return null
        val version = String(dexBytes, 4, 3, Charsets.US_ASCII)
        val checksum = readIntLE(dexBytes, 8)
        val sha1Hex = dexBytes.copyOfRange(12, 32).joinToString("") { "%02x".format(it) }
        val fileSize = readIntLE(dexBytes, 32)
        val stringIdsSize = readIntLE(dexBytes, 56).coerceAtLeast(0)
        val stringIdsOff = readIntLE(dexBytes, 60)
        val typeIdsSize = readIntLE(dexBytes, 64).coerceAtLeast(0)
        val typeIdsOff = readIntLE(dexBytes, 68)
        val protoIdsSize = readIntLE(dexBytes, 72).coerceAtLeast(0)
        val protoIdsOff = readIntLE(dexBytes, 76)
        val fieldIdsSize = readIntLE(dexBytes, 80).coerceAtLeast(0)
        val fieldIdsOff = readIntLE(dexBytes, 84)
        val methodIdsSize = readIntLE(dexBytes, 88).coerceAtLeast(0)
        val methodIdsOff = readIntLE(dexBytes, 92)
        val classDefsSize = readIntLE(dexBytes, 96).coerceAtLeast(0)
        val classDefsOff = readIntLE(dexBytes, 100)
        val dataSize = readIntLE(dexBytes, 104)
        val dataOff = readIntLE(dexBytes, 108)

        val header = DexHeaderInfo(
            dexVersion = version,
            checksumHex = "0x%08x".format(checksum),
            sha1Hex = sha1Hex,
            fileSize = fileSize,
            stringIdsSize = stringIdsSize,
            stringIdsOff = stringIdsOff,
            typeIdsSize = typeIdsSize,
            typeIdsOff = typeIdsOff,
            protoIdsSize = protoIdsSize,
            protoIdsOff = protoIdsOff,
            fieldIdsSize = fieldIdsSize,
            fieldIdsOff = fieldIdsOff,
            methodIdsSize = methodIdsSize,
            methodIdsOff = methodIdsOff,
            classDefsSize = classDefsSize,
            classDefsOff = classDefsOff,
            dataSize = dataSize,
            dataOff = dataOff
        )

        // 1. Parse all string_ids
        val strings = ArrayList<DexFullStringItem>(stringIdsSize)
        for (i in 0 until stringIdsSize) {
            val idPos = stringIdsOff + i * 4
            if (idPos + 4 > dexBytes.size) break
            val itemOff = readIntLE(dexBytes, idPos)
            if (itemOff <= 0 || itemOff >= dexBytes.size) {
                strings.add(DexFullStringItem(i, 0, 0, 0, ""))
                continue
            }
            var p = itemOff
            var shift = 0
            while (p < dexBytes.size && shift < 35) {
                val b = dexBytes[p++].toInt() and 0xFF
                if ((b and 0x80) == 0) break
                shift += 7
            }
            val strStart = p
            while (p < dexBytes.size && dexBytes[p] != 0.toByte()) p++
            val byteLen = p - strStart
            val text = if (byteLen > 0) {
                runCatching { String(dexBytes, strStart, byteLen, Charsets.UTF_8) }.getOrDefault("")
            } else ""
            strings.add(DexFullStringItem(i, itemOff, strStart, byteLen, text))
        }

        // 2. Parse type_ids
        val typeDescriptors = ArrayList<String>(typeIdsSize)
        for (i in 0 until typeIdsSize) {
            val pos = typeIdsOff + i * 4
            if (pos + 4 > dexBytes.size) break
            val strIdx = readIntLE(dexBytes, pos)
            typeDescriptors.add(strings.getOrNull(strIdx)?.value.orEmpty())
        }

        // 3. Parse class_defs
        val classes = ArrayList<DexClassSummary>(classDefsSize)
        for (i in 0 until classDefsSize) {
            val pos = classDefsOff + i * 32
            if (pos + 32 > dexBytes.size) break
            val classTypeIdx = readIntLE(dexBytes, pos)
            val accessFlags = readIntLE(dexBytes, pos + 4)
            val superTypeIdx = readIntLE(dexBytes, pos + 8)
            val sourceFileIdx = readIntLE(dexBytes, pos + 16)
            val classDataOff = readIntLE(dexBytes, pos + 24)

            val descriptor = typeDescriptors.getOrNull(classTypeIdx) ?: "LUnknown_$i;"
            val fullJava = descriptorToJavaType(descriptor)
            val pkg = if (fullJava.contains('.')) fullJava.substringBeforeLast('.') else "(default)"
            val simple = fullJava.substringAfterLast('.')
            val superName = if (superTypeIdx >= 0) descriptorToJavaType(typeDescriptors.getOrNull(superTypeIdx).orEmpty()) else ""
            val srcFile = if (sourceFileIdx in strings.indices) strings[sourceFileIdx].value else ""

            classes.add(
                DexClassSummary(
                    classDefIndex = i,
                    descriptor = descriptor,
                    fullJavaName = fullJava,
                    packageName = pkg,
                    simpleName = simple,
                    superClassName = superName,
                    accessFlags = accessFlags,
                    sourceFileName = srcFile,
                    classDataOff = classDataOff
                )
            )
        }

        return ParsedDexFile(
            name = name,
            header = header,
            strings = strings,
            typeDescriptors = typeDescriptors,
            classes = classes.sortedBy { it.fullJavaName },
            rawBytes = dexBytes
        )
    }

    // =========================================================================
    // CLASS DECOMPILER (SMALI + JAVA-LIKE PSEUDOCODE)
    // =========================================================================

    fun decompileClass(dex: ParsedDexFile, summary: DexClassSummary): DexClassDetail {
        val bytes = dex.rawBytes
        val h = dex.header
        val defPos = h.classDefsOff + summary.classDefIndex * 32

        // Interfaces
        val interfaces = mutableListOf<String>()
        if (defPos + 16 <= bytes.size) {
            val interfacesOff = readIntLE(bytes, defPos + 12)
            if (interfacesOff > 0 && interfacesOff + 4 <= bytes.size) {
                val count = readIntLE(bytes, interfacesOff).coerceIn(0, 256)
                for (k in 0 until count) {
                    val tOff = interfacesOff + 4 + k * 2
                    if (tOff + 2 > bytes.size) break
                    val typeIdx = readShortLE(bytes, tOff)
                    dex.typeDescriptors.getOrNull(typeIdx)?.let { interfaces.add(it) }
                }
            }
        }

        val fields = mutableListOf<DexFieldDef>()
        val methods = mutableListOf<DexMethodDef>()

        if (summary.classDataOff > 0 && summary.classDataOff < bytes.size) {
            val cursor = IntArray(1) { summary.classDataOff }
            val staticFieldsSize = readUleb128(bytes, cursor)
            val instanceFieldsSize = readUleb128(bytes, cursor)
            val directMethodsSize = readUleb128(bytes, cursor)
            val virtualMethodsSize = readUleb128(bytes, cursor)

            fun readFields(count: Int, isStatic: Boolean) {
                var fieldIdx = 0
                for (k in 0 until count.coerceIn(0, 4096)) {
                    fieldIdx += readUleb128(bytes, cursor)
                    val access = readUleb128(bytes, cursor)
                    val fPos = h.fieldIdsOff + fieldIdx * 8
                    if (fPos + 8 <= bytes.size) {
                        val typeIdx = readShortLE(bytes, fPos + 2)
                        val nameIdx = readIntLE(bytes, fPos + 4)
                        val typeDesc = dex.typeDescriptors.getOrNull(typeIdx).orEmpty()
                        val fName = dex.strings.getOrNull(nameIdx)?.value ?: "field_$fieldIdx"
                        fields.add(DexFieldDef(fName, typeDesc, descriptorToJavaType(typeDesc), access, isStatic))
                    }
                }
            }

            fun readMethods(count: Int, isDirect: Boolean) {
                var methodIdx = 0
                for (k in 0 until count.coerceIn(0, 4096)) {
                    methodIdx += readUleb128(bytes, cursor)
                    val access = readUleb128(bytes, cursor)
                    val codeOff = readUleb128(bytes, cursor)
                    val mPos = h.methodIdsOff + methodIdx * 8
                    if (mPos + 8 <= bytes.size) {
                        val protoIdx = readShortLE(bytes, mPos + 2)
                        val nameIdx = readIntLE(bytes, mPos + 4)
                        val mName = dex.strings.getOrNull(nameIdx)?.value ?: "method_$methodIdx"
                        val (retType, paramTypes) = resolveProto(dex, protoIdx)

                        var regs = 0; var ins = 0; var outs = 0; var insnsOff = -1; var insnsCount = 0
                        if (codeOff > 0 && codeOff + 16 <= bytes.size) {
                            regs = readShortLE(bytes, codeOff)
                            ins = readShortLE(bytes, codeOff + 2)
                            outs = readShortLE(bytes, codeOff + 4)
                            insnsCount = readIntLE(bytes, codeOff + 12)
                            insnsOff = codeOff + 16
                        }
                        methods.add(
                            DexMethodDef(
                                methodIdx = methodIdx,
                                name = mName,
                                returnType = retType,
                                parameterTypes = paramTypes,
                                accessFlags = access,
                                isDirect = isDirect,
                                codeOff = codeOff,
                                registersSize = regs,
                                insSize = ins,
                                outsSize = outs,
                                insnsFileOffset = insnsOff,
                                insnsCodeUnitCount = insnsCount
                            )
                        )
                    }
                }
            }

            readFields(staticFieldsSize, true)
            readFields(instanceFieldsSize, false)
            readMethods(directMethodsSize, true)
            readMethods(virtualMethodsSize, false)
        }

        val smali = buildClassSmali(dex, summary, interfaces, fields, methods)
        val javaPseudo = buildClassJavaPseudocode(dex, summary, interfaces, fields, methods)
        return DexClassDetail(summary, interfaces, fields, methods, smali, javaPseudo)
    }

    fun disassembleMethodSmali(dex: ParsedDexFile, method: DexMethodDef): String {
        if (method.insnsFileOffset < 0 || method.insnsCodeUnitCount <= 0) {
            return "    # abstract / native method (no bytecode)"
        }
        val bytes = dex.rawBytes
        val count = method.insnsCodeUnitCount.coerceAtMost(8192)
        if (method.insnsFileOffset + count * 2 > bytes.size) return "    # truncated bytecode"

        val units = IntArray(count) { i -> readShortLE(bytes, method.insnsFileOffset + i * 2) }
        val sb = StringBuilder()
        sb.appendLine("    .registers ${method.registersSize}  # ins=${method.insSize}, outs=${method.outsSize}")

        var pc = 0
        while (pc < units.size) {
            val unit = units[pc]
            val op = unit and 0xFF
            val (line, width) = decodeDalvikInstruction(dex, units, pc, op)
            sb.appendLine("    %04x: %s".format(pc, line))
            pc += width.coerceAtLeast(1)
        }
        return sb.toString().trimEnd()
    }

    private fun buildClassSmali(
        dex: ParsedDexFile,
        s: DexClassSummary,
        interfaces: List<String>,
        fields: List<DexFieldDef>,
        methods: List<DexMethodDef>
    ): String {
        val sb = StringBuilder()
        sb.appendLine(".class ${formatAccessFlags(s.accessFlags)}${s.descriptor}")
        if (s.superClassName.isNotBlank()) {
            sb.appendLine(".super ${javaToDescriptor(s.superClassName)}")
        }
        if (s.sourceFileName.isNotBlank()) {
            sb.appendLine(".source \"${s.sourceFileName}\"")
        }
        interfaces.forEach { sb.appendLine(".implements $it") }
        sb.appendLine()

        if (fields.isNotEmpty()) {
            sb.appendLine("# fields")
            fields.forEach { f ->
                sb.appendLine(".field ${formatAccessFlags(f.accessFlags)}${f.name}:${f.typeDescriptor}")
            }
            sb.appendLine()
        }

        methods.forEach { m ->
            val paramsStr = m.parameterTypes.joinToString("")
            sb.appendLine(".method ${formatAccessFlags(m.accessFlags)}${m.name}($paramsStr)${m.returnType}")
            sb.appendLine(disassembleMethodSmali(dex, m))
            sb.appendLine(".end method")
            sb.appendLine()
        }
        return sb.toString()
    }

    private fun buildClassJavaPseudocode(
        dex: ParsedDexFile,
        s: DexClassSummary,
        interfaces: List<String>,
        fields: List<DexFieldDef>,
        methods: List<DexMethodDef>
    ): String {
        val sb = StringBuilder()
        if (s.packageName != "(default)") {
            sb.appendLine("package ${s.packageName};")
            sb.appendLine()
        }
        val kindKw = when {
            (s.accessFlags and 0x0200) != 0 -> "interface"
            (s.accessFlags and 0x4000) != 0 -> "enum"
            else -> "class"
        }
        val extStr = if (s.superClassName.isNotBlank() && s.superClassName != "java.lang.Object") {
            " extends ${s.superClassName.substringAfterLast('.')}"
        } else ""
        val implStr = if (interfaces.isNotEmpty()) {
            " implements " + interfaces.joinToString(", ") { descriptorToJavaType(it).substringAfterLast('.') }
        } else ""

        sb.appendLine("${formatAccessFlags(s.accessFlags, includeInterface = false)}$kindKw ${s.simpleName}$extStr$implStr {")
        sb.appendLine()

        fields.forEach { f ->
            sb.appendLine("    ${formatAccessFlags(f.accessFlags)}${f.javaType.substringAfterLast('.')} ${f.name};")
        }
        if (fields.isNotEmpty()) sb.appendLine()

        methods.forEach { m ->
            val retJava = descriptorToJavaType(m.returnType).substringAfterLast('.')
            val paramsJava = m.parameterTypes.mapIndexed { idx, p ->
                "${descriptorToJavaType(p).substringAfterLast('.')} p$idx"
            }.joinToString(", ")
            val header = if (m.name == "<init>") {
                "${formatAccessFlags(m.accessFlags)}${s.simpleName}($paramsJava)"
            } else if (m.name == "<clinit>") {
                "static"
            } else {
                "${formatAccessFlags(m.accessFlags)}$retJava ${m.name}($paramsJava)"
            }
            if (m.insnsFileOffset < 0 || m.insnsCodeUnitCount <= 0) {
                sb.appendLine("    $header;")
            } else {
                sb.appendLine("    $header {")
                sb.appendLine(decompileMethodPseudocodeBody(dex, m))
                sb.appendLine("    }")
            }
            sb.appendLine()
        }
        sb.appendLine("}")
        return sb.toString()
    }

    private fun decompileMethodPseudocodeBody(dex: ParsedDexFile, m: DexMethodDef): String {
        val bytes = dex.rawBytes
        val count = m.insnsCodeUnitCount.coerceAtMost(2048)
        if (m.insnsFileOffset < 0 || m.insnsFileOffset + count * 2 > bytes.size) return "        // empty"
        val units = IntArray(count) { i -> readShortLE(bytes, m.insnsFileOffset + i * 2) }
        val regVals = HashMap<Int, String>()
        val lines = mutableListOf<String>()
        var lastResult = "res"
        var pc = 0

        while (pc < units.size) {
            val u0 = units[pc]
            val op = u0 and 0xFF
            val (_, width) = decodeDalvikInstruction(dex, units, pc, op)
            when (op) {
                0x0e -> lines.add("        return;")
                0x0f, 0x10, 0x11 -> {
                    val vA = (u0 ushr 8) and 0xFF
                    lines.add("        return ${regVals[vA] ?: "v$vA"};")
                }
                0x12 -> { // const/4
                    val vA = (u0 ushr 8) and 0xF
                    val rawLit = (u0 ushr 12) and 0xF
                    val lit = if (rawLit >= 8) rawLit - 16 else rawLit
                    regVals[vA] = lit.toString()
                    lines.add("        int v$vA = $lit;")
                }
                0x13 -> if (pc + 1 < units.size) { // const/16
                    val vA = (u0 ushr 8) and 0xFF
                    val lit = units[pc + 1].toShort().toInt()
                    regVals[vA] = lit.toString()
                    lines.add("        int v$vA = $lit;")
                }
                0x14 -> if (pc + 2 < units.size) { // const
                    val vA = (u0 ushr 8) and 0xFF
                    val lit = (units[pc + 1] and 0xFFFF) or ((units[pc + 2] and 0xFFFF) shl 16)
                    regVals[vA] = lit.toString()
                    lines.add("        int v$vA = $lit;")
                }
                0x1a -> if (pc + 1 < units.size) { // const-string
                    val vA = (u0 ushr 8) and 0xFF
                    val str = dex.strings.getOrNull(units[pc + 1])?.value.orEmpty()
                    val quoted = "\"${escapeJavaString(str)}\""
                    regVals[vA] = quoted
                    lines.add("        String v$vA = $quoted;")
                }
                0x1b -> if (pc + 2 < units.size) { // const-string/jumbo
                    val vA = (u0 ushr 8) and 0xFF
                    val strIdx = (units[pc + 1] and 0xFFFF) or ((units[pc + 2] and 0xFFFF) shl 16)
                    val str = dex.strings.getOrNull(strIdx)?.value.orEmpty()
                    val quoted = "\"${escapeJavaString(str)}\""
                    regVals[vA] = quoted
                    lines.add("        String v$vA = $quoted;")
                }
                0x22 -> if (pc + 1 < units.size) { // new-instance
                    val vA = (u0 ushr 8) and 0xFF
                    val typeName = descriptorToJavaType(dex.typeDescriptors.getOrNull(units[pc + 1]).orEmpty()).substringAfterLast('.')
                    regVals[vA] = "new $typeName()"
                    lines.add("        $typeName v$vA = new $typeName();")
                }
                0x0a, 0x0b, 0x0c -> { // move-result*
                    val vA = (u0 ushr 8) and 0xFF
                    regVals[vA] = lastResult
                    lines.add("        var v$vA = $lastResult;")
                }
                in 0x52..0x58 -> if (pc + 1 < units.size) { // iget*
                    val vA = (u0 ushr 8) and 0xF
                    val vB = (u0 ushr 12) and 0xF
                    val fName = resolveFieldShort(dex, units[pc + 1])
                    lines.add("        var v$vA = v$vB.$fName;")
                }
                in 0x59..0x5f -> if (pc + 1 < units.size) { // iput*
                    val vA = (u0 ushr 8) and 0xF
                    val vB = (u0 ushr 12) and 0xF
                    val fName = resolveFieldShort(dex, units[pc + 1])
                    lines.add("        v$vB.$fName = ${regVals[vA] ?: "v$vA"};")
                }
                in 0x60..0x66 -> if (pc + 1 < units.size) { // sget*
                    val vA = (u0 ushr 8) and 0xFF
                    val fName = resolveFieldFull(dex, units[pc + 1])
                    lines.add("        var v$vA = $fName;")
                }
                in 0x67..0x6d -> if (pc + 1 < units.size) { // sput*
                    val vA = (u0 ushr 8) and 0xFF
                    val fName = resolveFieldFull(dex, units[pc + 1])
                    lines.add("        $fName = ${regVals[vA] ?: "v$vA"};")
                }
                in 0x6e..0x72 -> if (pc + 2 < units.size) { // invoke-*
                    val argCount = (u0 ushr 12) and 0xF
                    val methodIdx = units[pc + 1]
                    val argsWord = units[pc + 2]
                    val regs = extract35cArgs(u0, argsWord, argCount)
                    val (cls, mName) = resolveMethodClassAndName(dex, methodIdx)
                    val callExpr = if (op == 0x71) {
                        val argsStr = regs.joinToString(", ") { regVals[it] ?: "v$it" }
                        "$cls.$mName($argsStr)"
                    } else {
                        val recv = regs.firstOrNull()?.let { "v$it" } ?: "this"
                        val argsStr = regs.drop(1).joinToString(", ") { regVals[it] ?: "v$it" }
                        "$recv.$mName($argsStr)"
                    }
                    lastResult = callExpr
                    lines.add("        $callExpr;")
                }
                in 0x32..0x3d -> if (pc + 1 < units.size) { // if-*
                    val target = pc + units[pc + 1].toShort().toInt()
                    val (decoded, _) = decodeDalvikInstruction(dex, units, pc, op)
                    lines.add("        // $decoded -> label_%04x".format(target))
                }
                0x27 -> {
                    val vA = (u0 ushr 8) and 0xFF
                    lines.add("        throw v$vA;")
                }
            }
            pc += width.coerceAtLeast(1)
        }
        return if (lines.isEmpty()) "        // ${m.insnsCodeUnitCount} Dalvik code units (view Smali tab for full disassembly)"
        else lines.joinToString("\n")
    }

    // =========================================================================
    // REAL DEX EDITING: ARBITRARY-LENGTH STRINGS + METHOD BYTECODE + RE-CHECKSUM
    // =========================================================================

    /**
     * Patches any number of DEX strings — supporting BOTH shorter/equal strings (in-place) AND
     * longer strings (appends new MUTF-8 `string_data_item` to DEX data section, updates `string_ids[idx]`,
     * updates `header.file_size` and `header.data_size`, and recalculates SHA-1 + Adler32!).
     */
    fun patchDexStringsSmart(
        dexBytes: ByteArray,
        patchesByStringIndex: Map<Int, String>
    ): ByteArray {
        if (patchesByStringIndex.isEmpty() || !isValidDex(dexBytes)) return dexBytes
        val stringIdsSize = readIntLE(dexBytes, 56)
        val stringIdsOff = readIntLE(dexBytes, 60)
        val dataOff = readIntLE(dexBytes, 108)

        val base = dexBytes.copyOf()
        val appended = ByteArrayOutputStream()
        var modified = false

        patchesByStringIndex.forEach { (strIdx, newText) ->
            if (strIdx !in 0 until stringIdsSize) return@forEach
            val idPos = stringIdsOff + strIdx * 4
            if (idPos + 4 > base.size) return@forEach
            val oldItemOff = readIntLE(base, idPos)
            if (oldItemOff <= 0 || oldItemOff >= base.size) return@forEach

            // Measure existing string_data_item total byte size (uleb128 + utf8 + null)
            var p = oldItemOff
            var shift = 0
            while (p < base.size && shift < 35) {
                val b = base[p++].toInt() and 0xFF
                if ((b and 0x80) == 0) break
                shift += 7
            }
            while (p < base.size && base[p] != 0.toByte()) p++
            val oldTotalItemBytes = (p - oldItemOff) + 1 // including null terminator

            val newItemBytes = encodeMutf8StringDataItem(newText)
            if (newItemBytes.size <= oldTotalItemBytes && oldItemOff + newItemBytes.size <= base.size) {
                // Fast in-place patch (exact null-terminated MUTF-8 item)
                System.arraycopy(newItemBytes, 0, base, oldItemOff, newItemBytes.size)
                modified = true
            } else {
                // Arbitrary length: append new string_data_item at end of file & repoint string_ids[strIdx]
                val newItemOffset = base.size + appended.size()
                appended.write(newItemBytes)
                writeIntLE(base, idPos, newItemOffset)
                modified = true
            }
        }

        if (!modified) return dexBytes

        val finalBytes = if (appended.size() > 0) {
            while ((base.size + appended.size()) % 4 != 0) appended.write(0)
            val combined = ByteArray(base.size + appended.size())
            System.arraycopy(base, 0, combined, 0, base.size)
            val appArr = appended.toByteArray()
            System.arraycopy(appArr, 0, combined, base.size, appArr.size)
            // Update header file_size (offset 32) and data_size (offset 104)
            writeIntLE(combined, 32, combined.size)
            writeIntLE(combined, 104, (combined.size - dataOff).coerceAtLeast(0))
            combined
        } else {
            base
        }

        return recalculateDexChecksums(finalBytes)
    }

    /**
     * Patches raw Dalvik bytecode bytes inside a method's `code_item` instruction array
     * (at [insnsFileOffset], max [insnsCodeUnitCount] * 2 bytes) and recomputes DEX SHA-1 & Adler32.
     */
    fun patchMethodBytecodeHex(
        dexBytes: ByteArray,
        method: DexMethodDef,
        hexCodeUnits: String
    ): Result<ByteArray> {
        return runCatching {
            if (method.insnsFileOffset < 112 || method.insnsCodeUnitCount <= 0) {
                throw IllegalArgumentException("This method has no editable bytecode.")
            }
            val cleaned = hexCodeUnits.trim().replace(Regex("[^0-9a-fA-F]"), "")
            if (cleaned.isEmpty() || cleaned.length % 4 != 0) {
                throw IllegalArgumentException("Dalvik bytecode must be 16-bit code units (multiple of 4 hex chars, e.g. 0E00).")
            }
            val newBytes = ByteArray(cleaned.length / 2) { i ->
                cleaned.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            val maxBytes = method.insnsCodeUnitCount * 2
            if (newBytes.size > maxBytes) {
                throw IllegalArgumentException("Bytecode (${newBytes.size} B) exceeds method slot ($maxBytes B).")
            }
            val copy = dexBytes.copyOf()
            // Write new bytecode and fill any remaining slots with Dalvik `nop` (0x0000)
            System.arraycopy(newBytes, 0, copy, method.insnsFileOffset, newBytes.size)
            for (i in newBytes.size until maxBytes) {
                copy[method.insnsFileOffset + i] = 0.toByte()
            }
            recalculateDexChecksums(copy)
        }
    }

    /**
     * Mini Smali Assembler: compiles simple edited Dalvik instructions (`nop`, `return-void`,
     * `const/4 v0, 0x1`, `const/16 v0, 100`, `return v0`, `return-object v0`, `const-string v0, "..."`, `move v0, v1`)
     * into little-endian Dalvik bytecode bytes!
     */
    fun assembleSimpleSmali(dex: ParsedDexFile, smaliLines: String): Result<ByteArray> {
        return runCatching {
            val out = ByteArrayOutputStream()
            fun emitUnit(u: Int) {
                out.write(u and 0xFF)
                out.write((u ushr 8) and 0xFF)
            }
            val stringIdxMap = dex.strings.associate { it.value to it.index }

            smaliLines.lines().forEach { raw ->
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty() || line.startsWith(".")) return@forEach
                val cleanLine = if (line.matches(Regex("^[0-9a-fA-F]{4}:\\s+.*"))) {
                    line.substringAfter(':').trim()
                } else line
                if (cleanLine.isEmpty()) return@forEach

                val tokens = cleanLine.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
                val op = tokens.first().lowercase()
                when (op) {
                    "nop" -> emitUnit(0x0000)
                    "return-void" -> emitUnit(0x000e)
                    "return" -> {
                        val vA = parseReg(tokens.getOrNull(1))
                        emitUnit(0x0f or ((vA and 0xFF) shl 8))
                    }
                    "return-wide" -> {
                        val vA = parseReg(tokens.getOrNull(1))
                        emitUnit(0x10 or ((vA and 0xFF) shl 8))
                    }
                    "return-object" -> {
                        val vA = parseReg(tokens.getOrNull(1))
                        emitUnit(0x11 or ((vA and 0xFF) shl 8))
                    }
                    "move" -> {
                        val vA = parseReg(tokens.getOrNull(1))
                        val vB = parseReg(tokens.getOrNull(2))
                        emitUnit(0x01 or ((vA and 0xF) shl 8) or ((vB and 0xF) shl 12))
                    }
                    "move-object" -> {
                        val vA = parseReg(tokens.getOrNull(1))
                        val vB = parseReg(tokens.getOrNull(2))
                        emitUnit(0x07 or ((vA and 0xF) shl 8) or ((vB and 0xF) shl 12))
                    }
                    "const/4" -> {
                        val vA = parseReg(tokens.getOrNull(1))
                        val lit = parseIntLiteral(tokens.getOrNull(2))
                        emitUnit(0x12 or ((vA and 0xF) shl 8) or ((lit and 0xF) shl 12))
                    }
                    "const/16" -> {
                        val vA = parseReg(tokens.getOrNull(1))
                        val lit = parseIntLiteral(tokens.getOrNull(2))
                        emitUnit(0x13 or ((vA and 0xFF) shl 8))
                        emitUnit(lit and 0xFFFF)
                    }
                    "const" -> {
                        val vA = parseReg(tokens.getOrNull(1))
                        val lit = parseIntLiteral(tokens.getOrNull(2))
                        emitUnit(0x14 or ((vA and 0xFF) shl 8))
                        emitUnit(lit and 0xFFFF)
                        emitUnit((lit ushr 16) and 0xFFFF)
                    }
                    "const-string" -> {
                        val vA = parseReg(tokens.getOrNull(1))
                        val firstQ = cleanLine.indexOf('"')
                        val lastQ = cleanLine.lastIndexOf('"')
                        if (firstQ < 0 || lastQ <= firstQ) throw IllegalArgumentException("const-string needs quoted string")
                        val strVal = cleanLine.substring(firstQ + 1, lastQ)
                        val idx = stringIdxMap[strVal]
                            ?: throw IllegalArgumentException("String \"$strVal\" not found in DEX string pool. Edit an existing string in Strings tab first.")
                        if (idx <= 0xFFFF) {
                            emitUnit(0x1a or ((vA and 0xFF) shl 8))
                            emitUnit(idx and 0xFFFF)
                        } else {
                            emitUnit(0x1b or ((vA and 0xFF) shl 8))
                            emitUnit(idx and 0xFFFF)
                            emitUnit((idx ushr 16) and 0xFFFF)
                        }
                    }
                    else -> throw IllegalArgumentException("Unsupported Smali mnemonic '$op'. Use Hex mode for arbitrary opcodes.")
                }
            }
            val arr = out.toByteArray()
            if (arr.isEmpty()) throw IllegalArgumentException("No instructions assembled.")
            arr
        }
    }

    fun readMethodBytecodeHex(dexBytes: ByteArray, method: DexMethodDef): String {
        if (method.insnsFileOffset < 0 || method.insnsCodeUnitCount <= 0) return ""
        val byteCount = (method.insnsCodeUnitCount * 2).coerceAtMost(dexBytes.size - method.insnsFileOffset)
        if (byteCount <= 0) return ""
        val sb = StringBuilder()
        for (i in 0 until byteCount) {
            sb.append("%02X".format(dexBytes[method.insnsFileOffset + i]))
            if (i % 2 == 1 && i < byteCount - 1) sb.append(' ')
        }
        return sb.toString()
    }

    fun recalculateDexChecksums(dexBytes: ByteArray): ByteArray {
        if (dexBytes.size < 112) return dexBytes
        val out = dexBytes.copyOf()
        val sha1 = MessageDigest.getInstance("SHA-1")
        sha1.update(out, 32, out.size - 32)
        System.arraycopy(sha1.digest(), 0, out, 12, 20)

        val adler = Adler32()
        adler.update(out, 12, out.size - 12)
        writeIntLE(out, 8, adler.value.toInt())
        return out
    }

    // =========================================================================
    // DALVIK OPCODE DECODER
    // =========================================================================

    private fun decodeDalvikInstruction(
        dex: ParsedDexFile,
        units: IntArray,
        pc: Int,
        op: Int
    ): Pair<String, Int> {
        val u0 = units[pc]
        val vA4 = (u0 ushr 8) and 0xF
        val vB4 = (u0 ushr 12) and 0xF
        val vAA = (u0 ushr 8) and 0xFF
        fun u(delta: Int): Int = if (pc + delta < units.size) units[pc + delta] else 0

        return when (op) {
            0x00 -> {
                // Check payload pseudo-instructions (packed-switch, sparse-switch, fill-array-data)
                when ((u0 ushr 8) and 0xFF) {
                    0x01 -> {
                        val size = u(1)
                        ".packed-switch-data ($size entries)" to (4 + size * 2)
                    }
                    0x02 -> {
                        val size = u(1)
                        ".sparse-switch-data ($size entries)" to (2 + size * 4)
                    }
                    0x03 -> {
                        val elemW = u(1)
                        val elemC = (u(2) and 0xFFFF) or ((u(3) and 0xFFFF) shl 16)
                        val totalUnits = 4 + ((elemW * elemC + 1) / 2)
                        ".array-data (width=$elemW, count=$elemC)" to totalUnits.coerceIn(4, units.size - pc)
                    }
                    else -> "nop" to 1
                }
            }
            0x01 -> "move v$vA4, v$vB4" to 1
            0x02 -> "move/from16 v$vAA, v${u(1)}" to 2
            0x03 -> "move/16 v${u(1)}, v${u(2)}" to 3
            0x04 -> "move-wide v$vA4, v$vB4" to 1
            0x05 -> "move-wide/from16 v$vAA, v${u(1)}" to 2
            0x06 -> "move-wide/16 v${u(1)}, v${u(2)}" to 3
            0x07 -> "move-object v$vA4, v$vB4" to 1
            0x08 -> "move-object/from16 v$vAA, v${u(1)}" to 2
            0x09 -> "move-object/16 v${u(1)}, v${u(2)}" to 3
            0x0a -> "move-result v$vAA" to 1
            0x0b -> "move-result-wide v$vAA" to 1
            0x0c -> "move-result-object v$vAA" to 1
            0x0d -> "move-exception v$vAA" to 1
            0x0e -> "return-void" to 1
            0x0f -> "return v$vAA" to 1
            0x10 -> "return-wide v$vAA" to 1
            0x11 -> "return-object v$vAA" to 1
            0x12 -> {
                val lit = if (vB4 >= 8) vB4 - 16 else vB4
                "const/4 v$vA4, $lit" to 1
            }
            0x13 -> "const/16 v$vAA, ${u(1).toShort()}" to 2
            0x14 -> {
                val lit = (u(1) and 0xFFFF) or ((u(2) and 0xFFFF) shl 16)
                "const v$vAA, 0x${Integer.toHexString(lit)} # $lit" to 3
            }
            0x15 -> "const/high16 v$vAA, 0x${Integer.toHexString(u(1) shl 16)}" to 2
            0x16 -> "const-wide/16 v$vAA, ${u(1).toShort()}" to 2
            0x17 -> {
                val lit = (u(1) and 0xFFFF) or ((u(2) and 0xFFFF) shl 16)
                "const-wide/32 v$vAA, $lit" to 3
            }
            0x18 -> {
                val lo = (u(1).toLong() and 0xFFFFL) or ((u(2).toLong() and 0xFFFFL) shl 16)
                val hi = (u(3).toLong() and 0xFFFFL) or ((u(4).toLong() and 0xFFFFL) shl 16)
                val v = lo or (hi shl 32)
                "const-wide v$vAA, ${v}L" to 5
            }
            0x19 -> "const-wide/high16 v$vAA, 0x${java.lang.Long.toHexString(u(1).toLong() shl 48)}" to 2
            0x1a -> {
                val str = dex.strings.getOrNull(u(1))?.value.orEmpty()
                "const-string v$vAA, \"${escapeJavaString(str)}\"" to 2
            }
            0x1b -> {
                val idx = (u(1) and 0xFFFF) or ((u(2) and 0xFFFF) shl 16)
                val str = dex.strings.getOrNull(idx)?.value.orEmpty()
                "const-string/jumbo v$vAA, \"${escapeJavaString(str)}\"" to 3
            }
            0x1c -> "const-class v$vAA, ${dex.typeDescriptors.getOrNull(u(1)).orEmpty()}" to 2
            0x1d -> "monitor-enter v$vAA" to 1
            0x1e -> "monitor-exit v$vAA" to 1
            0x1f -> "check-cast v$vAA, ${dex.typeDescriptors.getOrNull(u(1)).orEmpty()}" to 2
            0x20 -> "instance-of v$vA4, v$vB4, ${dex.typeDescriptors.getOrNull(u(1)).orEmpty()}" to 2
            0x21 -> "array-length v$vA4, v$vB4" to 1
            0x22 -> "new-instance v$vAA, ${dex.typeDescriptors.getOrNull(u(1)).orEmpty()}" to 2
            0x23 -> "new-array v$vA4, v$vB4, ${dex.typeDescriptors.getOrNull(u(1)).orEmpty()}" to 2
            0x24 -> {
                val args = extract35cArgs(u0, u(2), vB4).joinToString(", ") { "v$it" }
                "filled-new-array {$args}, ${dex.typeDescriptors.getOrNull(u(1)).orEmpty()}" to 3
            }
            0x25 -> "filled-new-array/range {v${u(2)} .. v${u(2) + vAA - 1}}, ${dex.typeDescriptors.getOrNull(u(1)).orEmpty()}" to 3
            0x26 -> "fill-array-data v$vAA, +${((u(1) and 0xFFFF) or ((u(2) and 0xFFFF) shl 16))}" to 3
            0x27 -> "throw v$vAA" to 1
            0x28 -> "goto :%04x".format(pc + vAA.toByte().toInt()) to 1
            0x29 -> "goto/16 :%04x".format(pc + u(1).toShort().toInt()) to 2
            0x2a -> "goto/32 :%04x".format(pc + ((u(1) and 0xFFFF) or ((u(2) and 0xFFFF) shl 16))) to 3
            0x2b -> "packed-switch v$vAA, +${((u(1) and 0xFFFF) or ((u(2) and 0xFFFF) shl 16))}" to 3
            0x2c -> "sparse-switch v$vAA, +${((u(1) and 0xFFFF) or ((u(2) and 0xFFFF) shl 16))}" to 3
            in 0x2d..0x31 -> {
                val names = arrayOf("cmpl-float", "cmpg-float", "cmpl-double", "cmpg-double", "cmp-long")
                val vB = u(1) and 0xFF; val vC = (u(1) ushr 8) and 0xFF
                "${names[op - 0x2d]} v$vAA, v$vB, v$vC" to 2
            }
            in 0x32..0x37 -> {
                val names = arrayOf("if-eq", "if-ne", "if-lt", "if-ge", "if-gt", "if-le")
                "${names[op - 0x32]} v$vA4, v$vB4, :%04x".format(pc + u(1).toShort().toInt()) to 2
            }
            in 0x38..0x3d -> {
                val names = arrayOf("if-eqz", "if-nez", "if-ltz", "if-gez", "if-gtz", "if-lez")
                "${names[op - 0x38]} v$vAA, :%04x".format(pc + u(1).toShort().toInt()) to 2
            }
            in 0x44..0x51 -> {
                val names = arrayOf(
                    "aget", "aget-wide", "aget-object", "aget-boolean", "aget-byte", "aget-char", "aget-short",
                    "aput", "aput-wide", "aput-object", "aput-boolean", "aput-byte", "aput-char", "aput-short"
                )
                val vB = u(1) and 0xFF; val vC = (u(1) ushr 8) and 0xFF
                "${names[op - 0x44]} v$vAA, v$vB, v$vC" to 2
            }
            in 0x52..0x5f -> {
                val names = arrayOf(
                    "iget", "iget-wide", "iget-object", "iget-boolean", "iget-byte", "iget-char", "iget-short",
                    "iput", "iput-wide", "iput-object", "iput-boolean", "iput-byte", "iput-char", "iput-short"
                )
                "${names[op - 0x52]} v$vA4, v$vB4, ${resolveFieldSmali(dex, u(1))}" to 2
            }
            in 0x60..0x6d -> {
                val names = arrayOf(
                    "sget", "sget-wide", "sget-object", "sget-boolean", "sget-byte", "sget-char", "sget-short",
                    "sput", "sput-wide", "sput-object", "sput-boolean", "sput-byte", "sput-char", "sput-short"
                )
                "${names[op - 0x60]} v$vAA, ${resolveFieldSmali(dex, u(1))}" to 2
            }
            in 0x6e..0x72 -> {
                val names = arrayOf("invoke-virtual", "invoke-super", "invoke-direct", "invoke-static", "invoke-interface")
                val args = extract35cArgs(u0, u(2), vB4).joinToString(", ") { "v$it" }
                "${names[op - 0x6e]} {$args}, ${resolveMethodSmali(dex, u(1))}" to 3
            }
            in 0x74..0x78 -> {
                val names = arrayOf("invoke-virtual/range", "invoke-super/range", "invoke-direct/range", "invoke-static/range", "invoke-interface/range")
                val first = u(2)
                val last = (first + vAA - 1).coerceAtLeast(first)
                "${names[op - 0x74]} {v$first .. v$last}, ${resolveMethodSmali(dex, u(1))}" to 3
            }
            in 0x7b..0x8f -> "unop_0x%02x v$vA4, v$vB4".format(op) to 1
            in 0x90..0xaf -> {
                val vB = u(1) and 0xFF; val vC = (u(1) ushr 8) and 0xFF
                val opName = when (op) {
                    0x90 -> "add-int"; 0x91 -> "sub-int"; 0x92 -> "mul-int"; 0x93 -> "div-int"; 0x94 -> "rem-int"
                    0x95 -> "and-int"; 0x96 -> "or-int"; 0x97 -> "xor-int"; 0x98 -> "shl-int"; 0x99 -> "shr-int"
                    else -> "binop_0x%02x".format(op)
                }
                "$opName v$vAA, v$vB, v$vC" to 2
            }
            in 0xb0..0xcf -> {
                val opName = when (op) {
                    0xb0 -> "add-int/2addr"; 0xb1 -> "sub-int/2addr"; 0xb2 -> "mul-int/2addr"; 0xb3 -> "div-int/2addr"
                    else -> "binop2_0x%02x".format(op)
                }
                "$opName v$vA4, v$vB4" to 1
            }
            in 0xd0..0xd7 -> "binop_lit16_0x%02x v$vA4, v$vB4, ${u(1).toShort()}".format(op) to 2
            in 0xd8..0xe2 -> {
                val vB = u(1) and 0xFF
                val lit8 = ((u(1) ushr 8) and 0xFF).toByte().toInt()
                val opName = when (op) {
                    0xd8 -> "add-int/lit8"; 0xd9 -> "rsub-int/lit8"; 0xda -> "mul-int/lit8"
                    else -> "binop_lit8_0x%02x".format(op)
                }
                "$opName v$vAA, v$vB, $lit8" to 2
            }
            else -> ".word 0x%04x # op=0x%02x".format(u0, op) to 1
        }
    }

    // =========================================================================
    // RESOLVERS & HELPERS
    // =========================================================================

    private fun extract35cArgs(u0: Int, u2: Int, count: Int): List<Int> {
        val list = mutableListOf<Int>()
        if (count >= 1) list.add(u2 and 0xF)
        if (count >= 2) list.add((u2 ushr 4) and 0xF)
        if (count >= 3) list.add((u2 ushr 8) and 0xF)
        if (count >= 4) list.add((u2 ushr 12) and 0xF)
        if (count >= 5) list.add((u0 ushr 8) and 0xF)
        return list
    }

    private fun resolveProto(dex: ParsedDexFile, protoIdx: Int): Pair<String, List<String>> {
        val pos = dex.header.protoIdsOff + protoIdx * 12
        if (pos + 12 > dex.rawBytes.size) return "V" to emptyList()
        val retTypeIdx = readIntLE(dex.rawBytes, pos + 4)
        val paramsOff = readIntLE(dex.rawBytes, pos + 8)
        val retType = dex.typeDescriptors.getOrNull(retTypeIdx) ?: "V"
        val params = mutableListOf<String>()
        if (paramsOff > 0 && paramsOff + 4 <= dex.rawBytes.size) {
            val count = readIntLE(dex.rawBytes, paramsOff).coerceIn(0, 128)
            for (i in 0 until count) {
                val pOff = paramsOff + 4 + i * 2
                if (pOff + 2 > dex.rawBytes.size) break
                val tIdx = readShortLE(dex.rawBytes, pOff)
                params.add(dex.typeDescriptors.getOrNull(tIdx) ?: "Ljava/lang/Object;")
            }
        }
        return retType to params
    }

    private fun resolveFieldSmali(dex: ParsedDexFile, fieldIdx: Int): String {
        val pos = dex.header.fieldIdsOff + fieldIdx * 8
        if (pos + 8 > dex.rawBytes.size) return "field@$fieldIdx"
        val cls = dex.typeDescriptors.getOrNull(readShortLE(dex.rawBytes, pos)).orEmpty()
        val typ = dex.typeDescriptors.getOrNull(readShortLE(dex.rawBytes, pos + 2)).orEmpty()
        val name = dex.strings.getOrNull(readIntLE(dex.rawBytes, pos + 4))?.value.orEmpty()
        return "$cls->$name:$typ"
    }

    private fun resolveFieldShort(dex: ParsedDexFile, fieldIdx: Int): String {
        val pos = dex.header.fieldIdsOff + fieldIdx * 8
        if (pos + 8 > dex.rawBytes.size) return "f$fieldIdx"
        return dex.strings.getOrNull(readIntLE(dex.rawBytes, pos + 4))?.value ?: "f$fieldIdx"
    }

    private fun resolveFieldFull(dex: ParsedDexFile, fieldIdx: Int): String {
        val pos = dex.header.fieldIdsOff + fieldIdx * 8
        if (pos + 8 > dex.rawBytes.size) return "f$fieldIdx"
        val cls = descriptorToJavaType(dex.typeDescriptors.getOrNull(readShortLE(dex.rawBytes, pos)).orEmpty()).substringAfterLast('.')
        val name = dex.strings.getOrNull(readIntLE(dex.rawBytes, pos + 4))?.value ?: "f$fieldIdx"
        return "$cls.$name"
    }

    private fun resolveMethodSmali(dex: ParsedDexFile, methodIdx: Int): String {
        val pos = dex.header.methodIdsOff + methodIdx * 8
        if (pos + 8 > dex.rawBytes.size) return "method@$methodIdx"
        val cls = dex.typeDescriptors.getOrNull(readShortLE(dex.rawBytes, pos)).orEmpty()
        val protoIdx = readShortLE(dex.rawBytes, pos + 2)
        val name = dex.strings.getOrNull(readIntLE(dex.rawBytes, pos + 4))?.value.orEmpty()
        val (ret, params) = resolveProto(dex, protoIdx)
        return "$cls->$name(${params.joinToString("")})$ret"
    }

    private fun resolveMethodClassAndName(dex: ParsedDexFile, methodIdx: Int): Pair<String, String> {
        val pos = dex.header.methodIdsOff + methodIdx * 8
        if (pos + 8 > dex.rawBytes.size) return "Obj" to "m$methodIdx"
        val cls = descriptorToJavaType(dex.typeDescriptors.getOrNull(readShortLE(dex.rawBytes, pos)).orEmpty()).substringAfterLast('.')
        val name = dex.strings.getOrNull(readIntLE(dex.rawBytes, pos + 4))?.value ?: "m$methodIdx"
        return cls to name
    }

    fun descriptorToJavaType(desc: String): String {
        if (desc.isEmpty()) return "void"
        var arrCount = 0
        var i = 0
        while (i < desc.length && desc[i] == '[') {
            arrCount++
            i++
        }
        val base = when (desc.getOrNull(i)) {
            'V' -> "void"
            'Z' -> "boolean"
            'B' -> "byte"
            'S' -> "short"
            'C' -> "char"
            'I' -> "int"
            'J' -> "long"
            'F' -> "float"
            'D' -> "double"
            'L' -> desc.substring(i + 1).removeSuffix(";").replace('/', '.')
            else -> desc
        }
        return base + "[]".repeat(arrCount)
    }

    private fun javaToDescriptor(javaName: String): String {
        return "L${javaName.replace('.', '/')};"
    }

    private fun formatAccessFlags(flags: Int, includeInterface: Boolean = true): String {
        val sb = StringBuilder()
        if ((flags and 0x0001) != 0) sb.append("public ")
        if ((flags and 0x0002) != 0) sb.append("private ")
        if ((flags and 0x0004) != 0) sb.append("protected ")
        if ((flags and 0x0008) != 0) sb.append("static ")
        if ((flags and 0x0010) != 0) sb.append("final ")
        if ((flags and 0x0020) != 0) sb.append("synchronized ")
        if ((flags and 0x0100) != 0) sb.append("native ")
        if (includeInterface && (flags and 0x0200) != 0) sb.append("interface ")
        if ((flags and 0x0400) != 0 && (flags and 0x0200) == 0) sb.append("abstract ")
        return sb.toString()
    }

    private fun encodeMutf8StringDataItem(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        writeUleb128(out, text.length)
        out.write(text.toByteArray(Charsets.UTF_8))
        out.write(0)
        return out.toByteArray()
    }

    private fun writeUleb128(out: ByteArrayOutputStream, value: Int) {
        var v = value
        do {
            var b = v and 0x7F
            v = v ushr 7
            if (v != 0) b = b or 0x80
            out.write(b)
        } while (v != 0)
    }

    private fun readUleb128(bytes: ByteArray, cursor: IntArray): Int {
        var result = 0
        var shift = 0
        var pos = cursor[0]
        while (pos < bytes.size && shift < 35) {
            val b = bytes[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0) break
            shift += 7
        }
        cursor[0] = pos
        return result
    }

    private fun parseReg(token: String?): Int {
        if (token.isNullOrBlank()) throw IllegalArgumentException("Missing register (e.g. v0)")
        val clean = token.trim().lowercase()
        if (!clean.startsWith("v") && !clean.startsWith("p")) {
            throw IllegalArgumentException("Invalid register '$token'")
        }
        return clean.substring(1).toInt()
    }

    private fun parseIntLiteral(token: String?): Int {
        if (token.isNullOrBlank()) throw IllegalArgumentException("Missing integer literal")
        val t = token.trim()
        return when {
            t.startsWith("-0x", true) -> -t.substring(3).toLong(16).toInt()
            t.startsWith("0x", true) -> t.substring(2).toLong(16).toInt()
            else -> t.toInt()
        }
    }

    private fun escapeJavaString(s: String): String {
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    private fun readShortLE(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun readIntLE(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun writeIntLE(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v ushr 8) and 0xFF).toByte()
        b[off + 2] = ((v ushr 16) and 0xFF).toByte()
        b[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }
}
