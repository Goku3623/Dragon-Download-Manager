package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.downloader.ApkPackageInstaller
import com.example.data.filemanager.*
import com.example.data.model.formatBytes
import com.example.ui.theme.DangerRed
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarningOrange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private enum class DexEditorTab(val label: String) {
    CLASSES("Classes & Smali"),
    STRINGS("Strings"),
    HEADER("Header & Hex")
}

private enum class ClassCodeMode(val label: String) {
    SMALI("Smali"),
    JAVA("Java Pseudo"),
    METHODS("Methods (Edit)")
}

/**
 * Full-Screen Professional Dalvik Executable (`.dex`) Decompiler & Editor Studio.
 *
 * Works BOTH for standalone `.dex` files from File Manager AND for any `classes*.dex`
 * inside an APK:
 * - Browse all Packages & Classes
 * - Decompile any Class into Smali Disassembly or Java-like Pseudocode
 * - Patch any Method's Dalvik Bytecode (via Mini Smali Assembler or Raw Hex)
 * - Edit ANY String with Arbitrary-Length Smart Patching (in-place or appended MUTF-8)
 * - Inspect DEX Header Telemetry & Live Hex Editor
 * - Automatically recalculates SHA-1 Signature & Adler32 Checksum on every edit!
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DragonDexEditorDialog(
    context: Context,
    title: String,
    initialDexBytes: ByteArray,
    saveButtonLabel: String = "Save DEX",
    allowBackupToggle: Boolean = false,
    onDismiss: () -> Unit,
    onSaveDexBytes: (patchedBytes: ByteArray, createBackup: Boolean) -> Unit
) {
    val scope = rememberCoroutineScope()
    var workingBytes by remember(initialDexBytes) { mutableStateOf(initialDexBytes) }
    var parsedDex by remember { mutableStateOf<ParsedDexFile?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var parseError by remember { mutableStateOf<String?>(null) }
    var activeTab by remember { mutableStateOf(DexEditorTab.CLASSES) }
    var createBackup by remember { mutableStateOf(true) }
    var editCount by remember { mutableIntStateOf(0) }

    // Classes & Decompiler state
    var classSearchQuery by remember { mutableStateOf("") }
    var selectedPackageFilter by remember { mutableStateOf<String?>(null) }
    var selectedClassDetail by remember { mutableStateOf<DexClassDetail?>(null) }
    var classCodeMode by remember { mutableStateOf(ClassCodeMode.SMALI) }
    var editingMethod by remember { mutableStateOf<DexMethodDef?>(null) }

    // Strings state
    var stringSearchQuery by remember { mutableStateOf("") }
    var editingStringItem by remember { mutableStateOf<DexFullStringItem?>(null) }

    // Hex editor state
    var editingHexOffset by remember { mutableStateOf<Int?>(null) }

    fun reloadDex(bytes: ByteArray, keepClassDescriptor: String? = null) {
        scope.launch {
            isLoading = true
            val parsed = withContext(Dispatchers.Default) {
                DragonDexDecompilerEngine.parseDex(title, bytes)
            }
            if (parsed == null) {
                parseError = "Not a valid Dalvik Executable (.dex) file."
            } else {
                parseError = null
                parsedDex = parsed
                if (keepClassDescriptor != null) {
                    val match = parsed.classes.firstOrNull { it.descriptor == keepClassDescriptor }
                    if (match != null) {
                        selectedClassDetail = withContext(Dispatchers.Default) {
                            DragonDexDecompilerEngine.decompileClass(parsed, match)
                        }
                    }
                }
            }
            isLoading = false
        }
    }

    LaunchedEffect(initialDexBytes) {
        reloadDex(initialDexBytes)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .testTag("dragon_dex_editor_dialog"),
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 4.dp, shadowElevation = 4.dp) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            IconButton(
                                onClick = {
                                    if (selectedClassDetail != null && activeTab == DexEditorTab.CLASSES) {
                                        selectedClassDetail = null
                                    } else {
                                        onDismiss()
                                    }
                                },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = selectedClassDetail?.summary?.simpleName ?: title,
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.Black,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (editCount > 0) {
                                        Surface(color = SuccessGreen.copy(alpha = 0.2f), shape = RoundedCornerShape(4.dp)) {
                                            Text(
                                                text = "$editCount edit(s)",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Black,
                                                color = SuccessGreen,
                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = parsedDex?.let {
                                        "DEX v${it.header.dexVersion} • ${it.classes.size} classes • ${it.strings.size} strings • ${formatBytes(workingBytes.size.toLong())}"
                                    } ?: "Parsing Dalvik DEX...",
                                    fontSize = 9.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = NeonCyan,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (allowBackupToggle) {
                                FilterChip(
                                    selected = createBackup,
                                    onClick = { createBackup = !createBackup },
                                    label = { Text(".bak", fontSize = 10.sp) }
                                )
                            }
                            Button(
                                enabled = parsedDex != null,
                                onClick = { onSaveDexBytes(workingBytes, createBackup) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (editCount > 0) SuccessGreen else NeonCyan,
                                    contentColor = Color.Black
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .height(34.dp)
                                    .testTag("dex_editor_save_btn")
                            ) {
                                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(saveButtonLabel, fontSize = 11.sp, fontWeight = FontWeight.Black)
                            }
                        }

                        TabRow(
                            selectedTabIndex = DexEditorTab.entries.indexOf(activeTab),
                            containerColor = Color.Transparent,
                            contentColor = NeonCyan
                        ) {
                            DexEditorTab.entries.forEach { tab ->
                                Tab(
                                    selected = activeTab == tab,
                                    onClick = { activeTab = tab },
                                    text = {
                                        Text(
                                            text = tab.label,
                                            fontSize = 11.5.sp,
                                            fontWeight = if (activeTab == tab) FontWeight.Black else FontWeight.Medium
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
        ) { innerPadding ->
            val dex = parsedDex
            when {
                isLoading -> {
                    Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator(color = NeonCyan)
                            Text("Decompiling DEX classes, methods & string pool...", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                parseError != null || dex == null -> {
                    Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                        Text(parseError ?: "Failed to parse DEX file.", color = DangerRed, fontWeight = FontWeight.Bold)
                    }
                }
                else -> {
                    Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                        when (activeTab) {
                            DexEditorTab.CLASSES -> {
                                val detail = selectedClassDetail
                                if (detail != null) {
                                    DexClassDetailViewer(
                                        context = context,
                                        detail = detail,
                                        codeMode = classCodeMode,
                                        onChangeCodeMode = { classCodeMode = it },
                                        onBackToClassList = { selectedClassDetail = null },
                                        onEditMethod = { editingMethod = it }
                                    )
                                } else {
                                    DexClassesBrowserPane(
                                        dex = dex,
                                        searchQuery = classSearchQuery,
                                        onSearchQueryChange = { classSearchQuery = it },
                                        selectedPackage = selectedPackageFilter,
                                        onSelectPackage = { selectedPackageFilter = it },
                                        onOpenClass = { summary ->
                                            scope.launch {
                                                val decompiled = withContext(Dispatchers.Default) {
                                                    DragonDexDecompilerEngine.decompileClass(dex, summary)
                                                }
                                                selectedClassDetail = decompiled
                                                classCodeMode = ClassCodeMode.SMALI
                                            }
                                        }
                                    )
                                }
                            }

                            DexEditorTab.STRINGS -> {
                                DexStringsEditorPane(
                                    dex = dex,
                                    searchQuery = stringSearchQuery,
                                    onSearchQueryChange = { stringSearchQuery = it },
                                    onTapString = { editingStringItem = it }
                                )
                            }

                            DexEditorTab.HEADER -> {
                                DexHeaderAndHexPane(
                                    dex = dex,
                                    workingBytes = workingBytes,
                                    onTapHexRow = { editingHexOffset = it }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Arbitrary-Length DEX String Editor Dialog
    editingStringItem?.let { item ->
        var newValue by remember(item.index) { mutableStateOf(item.value) }
        val newByteLen = newValue.toByteArray(Charsets.UTF_8).size
        val willAppend = newByteLen > item.byteLength
        AlertDialog(
            onDismissRequest = { editingStringItem = null },
            title = { Text("Edit DEX String #${item.index}", fontSize = 14.5.sp, fontWeight = FontWeight.Black) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Original (${item.byteLength} B @ 0x%X): ${item.value}".format(item.itemOffset),
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    OutlinedTextField(
                        value = newValue,
                        onValueChange = { newValue = it },
                        label = { Text("New String ($newByteLen UTF-8 bytes)", fontSize = 11.sp) },
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = if (willAppend) {
                            "Longer than original slot: will safely append new MUTF-8 item, repoint string_ids[${item.index}], and recompute SHA-1 & Adler32."
                        } else {
                            "Fits in-place: will patch directly & recompute DEX SHA-1 & Adler32."
                        },
                        fontSize = 10.sp,
                        color = SuccessGreen
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newValue != item.value) {
                            val patched = DragonDexDecompilerEngine.patchDexStringsSmart(
                                dexBytes = workingBytes,
                                patchesByStringIndex = mapOf(item.index to newValue)
                            )
                            workingBytes = patched
                            editCount++
                            reloadDex(patched, selectedClassDetail?.summary?.descriptor)
                        }
                        editingStringItem = null
                    }
                ) { Text("Apply Patch") }
            },
            dismissButton = {
                TextButton(onClick = { editingStringItem = null }) { Text("Cancel") }
            }
        )
    }

    // Method Dalvik Bytecode & Smali Patcher Dialog
    editingMethod?.let { method ->
        val dex = parsedDex
        if (dex != null) {
            var editMode by remember(method.methodIdx) { mutableIntStateOf(0) } // 0=Smali Mini-Assembler, 1=Raw Hex
            val initialHex = remember(method.methodIdx, workingBytes) {
                DragonDexDecompilerEngine.readMethodBytecodeHex(workingBytes, method)
            }
            val initialSmali = remember(method.methodIdx, dex) {
                DragonDexDecompilerEngine.disassembleMethodSmali(dex, method)
            }
            var smaliInput by remember(method.methodIdx) { mutableStateOf(initialSmali) }
            var hexInput by remember(method.methodIdx) { mutableStateOf(initialHex) }
            var errMsg by remember(method.methodIdx) { mutableStateOf<String?>(null) }

            AlertDialog(
                onDismissRequest = { editingMethod = null },
                title = {
                    Column {
                        Text("Edit Method Bytecode", fontSize = 14.5.sp, fontWeight = FontWeight.Black)
                        Text(
                            text = "${method.name}() • ${method.insnsCodeUnitCount} units (${method.insnsCodeUnitCount * 2} B)",
                            fontSize = 10.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = NeonCyan
                        )
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(
                                selected = editMode == 0,
                                onClick = { editMode = 0; errMsg = null },
                                label = { Text("Smali Assembler", fontSize = 10.5.sp) }
                            )
                            FilterChip(
                                selected = editMode == 1,
                                onClick = { editMode = 1; errMsg = null },
                                label = { Text("Raw Dalvik Hex", fontSize = 10.5.sp) }
                            )
                        }
                        if (editMode == 0) {
                            Text(
                                text = "Supports: nop, return-void, return v0, return-object v0, const/4 v0, 1, const/16 v0, 100, const v0, 0x..., move v0, v1, const-string v0, \"...\"",
                                fontSize = 9.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedTextField(
                                value = smaliInput,
                                onValueChange = { smaliInput = it; errMsg = null },
                                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                                minLines = 6,
                                maxLines = 12,
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            Text(
                                text = "16-bit little-endian Dalvik code units (remaining bytes auto-filled with nop 00 00):",
                                fontSize = 9.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedTextField(
                                value = hexInput,
                                onValueChange = { hexInput = it; errMsg = null },
                                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                                minLines = 5,
                                maxLines = 10,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        errMsg?.let { Text(it, fontSize = 10.5.sp, color = DangerRed) }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val patchRes = if (editMode == 0) {
                                DragonDexDecompilerEngine.assembleSimpleSmali(dex, smaliInput).mapCatching { assembled ->
                                    val asHex = assembled.joinToString("") { "%02X".format(it) }
                                    DragonDexDecompilerEngine.patchMethodBytecodeHex(workingBytes, method, asHex).getOrThrow()
                                }
                            } else {
                                DragonDexDecompilerEngine.patchMethodBytecodeHex(workingBytes, method, hexInput)
                            }
                            patchRes.onSuccess { patched ->
                                workingBytes = patched
                                editCount++
                                reloadDex(patched, selectedClassDetail?.summary?.descriptor)
                                editingMethod = null
                            }.onFailure { e ->
                                errMsg = e.message ?: "Failed to patch method bytecode"
                            }
                        }
                    ) { Text("Patch Method") }
                },
                dismissButton = { TextButton(onClick = { editingMethod = null }) { Text("Cancel") } }
            )
        }
    }

    // Raw DEX Hex Row Patcher Dialog
    editingHexOffset?.let { rowOff ->
        HexRowEditAlertDialog(
            bytes = workingBytes,
            rowOffset = rowOff,
            onDismiss = { editingHexOffset = null },
            onApplyBytes = { patched ->
                val rechecked = DragonDexDecompilerEngine.recalculateDexChecksums(patched)
                workingBytes = rechecked
                editCount++
                editingHexOffset = null
                reloadDex(rechecked, selectedClassDetail?.summary?.descriptor)
            }
        )
    }
}

@Composable
private fun DexClassesBrowserPane(
    dex: ParsedDexFile,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    selectedPackage: String?,
    onSelectPackage: (String?) -> Unit,
    onOpenClass: (DexClassSummary) -> Unit
) {
    val topPackages = remember(dex.classes) {
        dex.classes.groupingBy { it.packageName }.eachCount()
            .entries.sortedByDescending { it.value }
            .take(20)
            .map { it.key to it.value }
    }
    val q = searchQuery.trim().lowercase(Locale.US)
    val filteredClasses = remember(dex.classes, q, selectedPackage) {
        dex.classes
            .asSequence()
            .filter { selectedPackage == null || it.packageName == selectedPackage }
            .filter {
                q.isEmpty() ||
                    it.fullJavaName.lowercase(Locale.US).contains(q) ||
                    it.sourceFileName.lowercase(Locale.US).contains(q)
            }
            .take(400)
            .toList()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            placeholder = { Text("Search ${dex.classes.size} classes or packages...", fontSize = 11.5.sp) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp)) },
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        )

        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item {
                FilterChip(
                    selected = selectedPackage == null,
                    onClick = { onSelectPackage(null) },
                    label = { Text("All (${dex.classes.size})", fontSize = 10.sp) }
                )
            }
            items(topPackages) { (pkg, count) ->
                FilterChip(
                    selected = selectedPackage == pkg,
                    onClick = { onSelectPackage(if (selectedPackage == pkg) null else pkg) },
                    label = { Text("${pkg.takeLast(24)} ($count)", fontSize = 10.sp) }
                )
            }
        }

        LazyColumn(
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(filteredClasses, key = { it.classDefIndex }) { cls ->
                Card(
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenClass(cls) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 11.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Code,
                            contentDescription = null,
                            tint = NeonCyan,
                            modifier = Modifier.size(18.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = cls.simpleName,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = cls.packageName + if (cls.superClassName.isNotBlank()) " • extends ${cls.superClassName.substringAfterLast('.')}" else "",
                                fontSize = 9.5.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Icon(Icons.Default.ChevronRight, contentDescription = "Decompile Class", tint = NeonCyan, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun DexClassDetailViewer(
    context: Context,
    detail: DexClassDetail,
    codeMode: ClassCodeMode,
    onChangeCodeMode: (ClassCodeMode) -> Unit,
    onBackToClassList: () -> Unit,
    onEditMethod: (DexMethodDef) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TextButton(onClick = onBackToClassList, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(3.dp))
                Text("Classes", fontSize = 11.sp)
            }
            ClassCodeMode.entries.forEach { mode ->
                FilterChip(
                    selected = codeMode == mode,
                    onClick = { onChangeCodeMode(mode) },
                    label = { Text(mode.label, fontSize = 10.sp) }
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = {
                    val codeToCopy = if (codeMode == ClassCodeMode.JAVA) detail.javaPseudocode else detail.smaliCode
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText(detail.summary.simpleName, codeToCopy))
                    ApkPackageInstaller.showToastOnMain(context, "Copied decompiled code")
                },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy Code", tint = NeonCyan, modifier = Modifier.size(16.dp))
            }
        }

        when (codeMode) {
            ClassCodeMode.SMALI, ClassCodeMode.JAVA -> {
                val lines = remember(detail, codeMode) {
                    (if (codeMode == ClassCodeMode.JAVA) detail.javaPseudocode else detail.smaliCode).lines()
                }
                LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0B1120))
                        .horizontalScroll(rememberScrollState())
                ) {
                    items(lines) { line ->
                        val t = line.trimStart()
                        Text(
                            text = line,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = when {
                                t.startsWith(".") || t.startsWith("package ") || t.startsWith("public ") || t.startsWith("private ") -> NeonCyan
                                t.startsWith("#") || t.startsWith("//") -> Color(0xFF64748B)
                                t.contains("invoke-") || t.contains("return") -> WarningOrange
                                t.contains("\"") -> SuccessGreen
                                else -> Color(0xFFE2E8F0)
                            }
                        )
                    }
                }
            }

            ClassCodeMode.METHODS -> {
                LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (detail.methods.isEmpty()) {
                        item {
                            Text("No methods defined in this class.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    items(detail.methods, key = { it.methodIdx }) { method ->
                        val hasCode = method.insnsFileOffset > 0 && method.insnsCodeUnitCount > 0
                        val sig = "${method.name}(${method.parameterTypes.joinToString(", ") { DragonDexDecompilerEngine.descriptorToJavaType(it).substringAfterLast('.') }}): ${DragonDexDecompilerEngine.descriptorToJavaType(method.returnType).substringAfterLast('.')}"
                        Card(
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, if (hasCode) NeonCyan.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = hasCode) { onEditMethod(method) }
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(sig, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                    Text(
                                        text = if (hasCode) {
                                            "regs=${method.registersSize} • ${method.insnsCodeUnitCount} Dalvik units • offset 0x%X".format(method.insnsFileOffset)
                                        } else "abstract / native (no bytecode)",
                                        fontSize = 9.5.sp,
                                        color = if (hasCode) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (hasCode) {
                                    Icon(Icons.Default.Edit, contentDescription = "Edit Method Bytecode", tint = WarningOrange, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DexStringsEditorPane(
    dex: ParsedDexFile,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onTapString: (DexFullStringItem) -> Unit
) {
    val q = searchQuery.trim().lowercase(Locale.US)
    val filtered = remember(dex.strings, q) {
        dex.strings
            .asSequence()
            .filter { it.value.isNotBlank() }
            .filter { q.isEmpty() || it.value.lowercase(Locale.US).contains(q) }
            .take(500)
            .toList()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            placeholder = { Text("Search all ${dex.strings.size} DEX strings (supports arbitrary-length edits)...", fontSize = 11.sp) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp)) },
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        )
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(filtered, key = { it.index }) { item ->
                Card(
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onTapString(item) }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.value, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                text = "#${item.index} • offset 0x%X • ${item.byteLength} bytes".format(item.itemOffset),
                                fontSize = 9.sp,
                                color = NeonCyan
                            )
                        }
                        Icon(Icons.Default.Edit, contentDescription = "Edit String", tint = SuccessGreen, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun DexHeaderAndHexPane(
    dex: ParsedDexFile,
    workingBytes: ByteArray,
    onTapHexRow: (Int) -> Unit
) {
    var showHex by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(selected = !showHex, onClick = { showHex = false }, label = { Text("DEX Header Telemetry", fontSize = 11.sp) })
            FilterChip(selected = showHex, onClick = { showHex = true }, label = { Text("Raw Hex Patcher", fontSize = 11.sp) })
        }
        if (!showHex) {
            val h = dex.header
            val rows = listOf(
                "DEX Version" to "dex\\n${h.dexVersion}",
                "File Size" to "${h.fileSize} bytes (${formatBytes(h.fileSize.toLong())})",
                "Adler32 Checksum" to h.checksumHex,
                "SHA-1 Signature" to h.sha1Hex,
                "String IDs" to "${h.stringIdsSize} @ 0x%X".format(h.stringIdsOff),
                "Type IDs" to "${h.typeIdsSize} @ 0x%X".format(h.typeIdsOff),
                "Proto IDs" to "${h.protoIdsSize} @ 0x%X".format(h.protoIdsOff),
                "Field IDs" to "${h.fieldIdsSize} @ 0x%X".format(h.fieldIdsOff),
                "Method IDs" to "${h.methodIdsSize} @ 0x%X".format(h.methodIdsOff),
                "Class Defs" to "${h.classDefsSize} @ 0x%X".format(h.classDefsOff),
                "Data Section" to "${h.dataSize} bytes @ 0x%X".format(h.dataOff)
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                rows.forEach { (k, v) ->
                    Card(shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(k, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = NeonCyan)
                            Text(v, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        } else {
            LiveHexRowsList(bytes = workingBytes, onTapRowOffset = onTapHexRow)
        }
    }
}
