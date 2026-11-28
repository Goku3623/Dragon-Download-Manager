package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
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
import java.io.File
import java.util.Locale

// =============================================================================
// 1. UNIVERSAL XML DECOMPILER, EDITOR & BINARY AXML RECOMPILER DIALOG
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DragonProXmlEditorDialog(
    context: Context,
    title: String,
    initialBytes: ByteArray,
    saveButtonLabel: String = "Save",
    allowBackupToggle: Boolean = false,
    onDismiss: () -> Unit,
    onSaveCompiledBytes: (compiledBytes: ByteArray, createBackup: Boolean) -> Unit
) {
    val scope = rememberCoroutineScope()
    val isOriginalBinaryAxml = remember(initialBytes) {
        DragonAxmlCompilerEngine.isBinaryAxml(initialBytes)
    }

    var isLoading by remember { mutableStateOf(true) }
    var xmlText by remember { mutableStateOf("") }
    var compileAsBinaryAxml by remember(isOriginalBinaryAxml) { mutableStateOf(isOriginalBinaryAxml) }
    var createBackup by remember { mutableStateOf(true) }
    var isSaving by remember { mutableStateOf(false) }
    var showFindBar by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var replaceQuery by remember { mutableStateOf("") }
    var statusBanner by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var wordWrap by remember { mutableStateOf(true) }
    var showTagsOutline by remember { mutableStateOf(false) }

    LaunchedEffect(initialBytes) {
        isLoading = true
        xmlText = withContext(Dispatchers.Default) {
            if (isOriginalBinaryAxml) {
                DragonAxmlCompilerEngine.decompileBinaryXml(initialBytes)
                    ?: String(initialBytes, Charsets.UTF_8)
            } else {
                String(initialBytes, Charsets.UTF_8).removePrefix("\uFEFF")
            }
        }
        isLoading = false
    }

    val extractedTags = remember(xmlText, showTagsOutline) {
        if (!showTagsOutline) emptyList()
        else Regex("<([a-zA-Z0-9_.:-]+)([^>]*)>")
            .findAll(xmlText)
            .take(200)
            .map { m ->
                val tag = m.groupValues[1]
                val attrsPreview = m.groupValues[2].replace(Regex("\\s+"), " ").trim().take(80)
                tag to attrsPreview
            }
            .toList()
    }

    Dialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .testTag("dragon_pro_xml_editor_dialog"),
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
                            IconButton(onClick = { if (!isSaving) onDismiss() }, modifier = Modifier.size(40.dp)) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close")
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = title,
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.Black,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Surface(
                                        color = if (isOriginalBinaryAxml) NeonCyan.copy(alpha = 0.18f) else SuccessGreen.copy(alpha = 0.18f),
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = if (isOriginalBinaryAxml) "BINARY AXML • DECOMPILED" else "PLAIN XML",
                                            fontSize = 8.5.sp,
                                            fontWeight = FontWeight.Black,
                                            color = if (isOriginalBinaryAxml) NeonCyan else SuccessGreen,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                        )
                                    }
                                    Text(
                                        text = "${xmlText.lines().size} lines",
                                        fontSize = 9.5.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Button(
                                enabled = !isLoading && !isSaving,
                                onClick = {
                                    scope.launch {
                                        isSaving = true
                                        statusBanner = null
                                        val result = withContext(Dispatchers.Default) {
                                            if (compileAsBinaryAxml) {
                                                DragonAxmlCompilerEngine.compileXmlToBinaryAxml(
                                                    xmlText = xmlText,
                                                    originalAxmlBytes = if (isOriginalBinaryAxml) initialBytes else null
                                                )
                                            } else {
                                                // Validate XML syntax softly; still allow saving plain XML
                                                Result.success(xmlText.toByteArray(Charsets.UTF_8))
                                            }
                                        }
                                        isSaving = false
                                        result.onSuccess { compiled ->
                                            onSaveCompiledBytes(compiled, createBackup)
                                        }.onFailure { err ->
                                            statusBanner = false to "XML Compile Error: ${err.message ?: "Invalid XML syntax"}"
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen, contentColor = Color.Black),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .height(34.dp)
                                    .testTag("xml_editor_save_btn")
                            ) {
                                if (isSaving) {
                                    CircularProgressIndicator(color = Color.Black, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                                } else {
                                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = if (compileAsBinaryAxml) "Compile & $saveButtonLabel" else saveButtonLabel,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Black
                                    )
                                }
                            }
                        }

                        // Action Toolbar
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            item {
                                FilterChip(
                                    selected = showFindBar,
                                    onClick = { showFindBar = !showFindBar },
                                    label = { Text("Find & Replace", fontSize = 10.5.sp) },
                                    leadingIcon = { Icon(Icons.Default.FindReplace, contentDescription = null, modifier = Modifier.size(14.dp)) }
                                )
                            }
                            item {
                                AssistChip(
                                    onClick = {
                                        val (pretty, err) = DragonDocumentEngine.formatXmlPretty(xmlText)
                                        if (pretty != null) {
                                            xmlText = pretty
                                            statusBanner = true to "XML formatted & validated successfully"
                                        } else {
                                            statusBanner = false to (err ?: "XML validation failed")
                                        }
                                    },
                                    label = { Text("Format / Validate", fontSize = 10.5.sp) },
                                    leadingIcon = { Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(14.dp)) }
                                )
                            }
                            item {
                                FilterChip(
                                    selected = compileAsBinaryAxml,
                                    onClick = { compileAsBinaryAxml = !compileAsBinaryAxml },
                                    label = { Text(if (compileAsBinaryAxml) "Output: Binary AXML" else "Output: Plain XML", fontSize = 10.5.sp) }
                                )
                            }
                            item {
                                FilterChip(
                                    selected = showTagsOutline,
                                    onClick = { showTagsOutline = !showTagsOutline },
                                    label = { Text("Tags Outline", fontSize = 10.5.sp) }
                                )
                            }
                            item {
                                FilterChip(
                                    selected = wordWrap,
                                    onClick = { wordWrap = !wordWrap },
                                    label = { Text("Wrap", fontSize = 10.5.sp) }
                                )
                            }
                            item {
                                AssistChip(
                                    onClick = {
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        cm.setPrimaryClip(ClipData.newPlainText(title, xmlText))
                                        ApkPackageInstaller.showToastOnMain(context, "Copied XML to clipboard")
                                    },
                                    label = { Text("Copy", fontSize = 10.5.sp) },
                                    leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp)) }
                                )
                            }
                            if (allowBackupToggle) {
                                item {
                                    FilterChip(
                                        selected = createBackup,
                                        onClick = { createBackup = !createBackup },
                                        label = { Text(".bak Backup", fontSize = 10.5.sp) }
                                    )
                                }
                            }
                        }

                        AnimatedVisibility(visible = showFindBar) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = findQuery,
                                    onValueChange = { findQuery = it },
                                    placeholder = { Text("Find...", fontSize = 11.sp) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = replaceQuery,
                                    onValueChange = { replaceQuery = it },
                                    placeholder = { Text("Replace...", fontSize = 11.sp) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                Button(
                                    enabled = findQuery.isNotEmpty(),
                                    onClick = {
                                        val count = xmlText.split(findQuery).size - 1
                                        if (count > 0) {
                                            xmlText = xmlText.replace(findQuery, replaceQuery)
                                            statusBanner = true to "Replaced $count occurrence(s)"
                                        } else {
                                            statusBanner = false to "No matches for '$findQuery'"
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text("All", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        statusBanner?.let { (ok, msg) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(if (ok) SuccessGreen.copy(alpha = 0.16f) else DangerRed.copy(alpha = 0.18f))
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = if (ok) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                                    contentDescription = null,
                                    tint = if (ok) SuccessGreen else DangerRed,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(msg, fontSize = 11.sp, color = if (ok) SuccessGreen else DangerRed, modifier = Modifier.weight(1f))
                                IconButton(onClick = { statusBanner = null }, modifier = Modifier.size(22.dp)) {
                                    Icon(Icons.Default.Close, contentDescription = "Dismiss", modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }
                }
            }
        ) { innerPadding ->
            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = NeonCyan)
                }
            } else if (showTagsOutline) {
                LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize().padding(innerPadding)
                ) {
                    itemsIndexed(extractedTags) { idx, (tag, preview) ->
                        Card(
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text("#${idx + 1} <$tag>", fontSize = 12.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, color = NeonCyan)
                                if (preview.isNotBlank()) {
                                    Text(preview, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                                }
                            }
                        }
                    }
                }
            } else {
                val vScroll = rememberScrollState()
                val hScroll = rememberScrollState()
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .background(Color(0xFF0B1120))
                        .verticalScroll(vScroll)
                        .then(if (!wordWrap) Modifier.horizontalScroll(hScroll) else Modifier)
                        .padding(12.dp)
                ) {
                    BasicTextField(
                        value = xmlText,
                        onValueChange = { xmlText = it },
                        textStyle = TextStyle(
                            color = Color(0xFFE2E8F0),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        ),
                        cursorBrush = SolidColor(NeonCyan),
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 520.dp)
                            .testTag("xml_editor_text_field")
                    )
                }
            }
        }
    }
}

// =============================================================================
// 2. UNIVERSAL .SAV DECOMPILER, STRUCTURED PROPERTY EDITOR & RECOMPILER DIALOG
// =============================================================================

private enum class SavEditorTab(val label: String) {
    FIELDS("Properties"),
    CODE("Decompiled"),
    HEX("Hex Editor")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DragonProSavEditorDialog(
    context: Context,
    title: String,
    initialBytes: ByteArray,
    saveButtonLabel: String = "Save",
    allowBackupToggle: Boolean = false,
    onDismiss: () -> Unit,
    onSaveBytes: (bytes: ByteArray, createBackup: Boolean) -> Unit
) {
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(true) }
    var savResult by remember { mutableStateOf<SavDecompileResult?>(null) }
    var editableCodeText by remember { mutableStateOf("") }
    var workingBytes by remember { mutableStateOf(initialBytes) }
    var activeTab by remember { mutableStateOf(SavEditorTab.FIELDS) }
    var searchQuery by remember { mutableStateOf("") }
    var createBackup by remember { mutableStateOf(true) }
    var hasChanges by remember { mutableStateOf(false) }
    var editingField by remember { mutableStateOf<SavDecompiledField?>(null) }
    var editingHexOffset by remember { mutableStateOf<Int?>(null) }
    var errorBanner by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(initialBytes) {
        isLoading = true
        val parsed = withContext(Dispatchers.Default) {
            DragonSavDecompilerEngine.decompileSav(initialBytes)
        }
        savResult = parsed
        editableCodeText = parsed.decompiledText
        workingBytes = parsed.rawBytes
        activeTab = if (parsed.fields.isNotEmpty()) SavEditorTab.FIELDS else SavEditorTab.CODE
        isLoading = false
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .testTag("dragon_pro_sav_editor_dialog"),
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
                            IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close")
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(title, fontSize = 13.5.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    savResult?.let { res ->
                                        Surface(color = WarningOrange.copy(alpha = 0.2f), shape = RoundedCornerShape(4.dp)) {
                                            Text(
                                                text = res.format.badgeLabel,
                                                fontSize = 8.5.sp,
                                                fontWeight = FontWeight.Black,
                                                color = WarningOrange,
                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                            )
                                        }
                                        Text(
                                            text = "${formatBytes(workingBytes.size.toLong())} • ${res.fields.size} field(s)",
                                            fontSize = 9.5.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            if (allowBackupToggle) {
                                FilterChip(
                                    selected = createBackup,
                                    onClick = { createBackup = !createBackup },
                                    label = { Text(".bak", fontSize = 10.sp) }
                                )
                            }
                            Button(
                                enabled = !isLoading,
                                onClick = {
                                    val res = savResult ?: return@Button
                                    scope.launch {
                                        errorBanner = null
                                        val finalBytesRes = withContext(Dispatchers.Default) {
                                            if (activeTab == SavEditorTab.CODE && res.format != SavFormatKind.BINARY_STRUCTURED) {
                                                DragonSavDecompilerEngine.recompileFromEditedText(res, editableCodeText)
                                            } else {
                                                Result.success(workingBytes)
                                            }
                                        }
                                        finalBytesRes.onSuccess { outBytes ->
                                            onSaveBytes(outBytes, createBackup)
                                        }.onFailure { err ->
                                            errorBanner = "Recompile Error: ${err.message}"
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (hasChanges) SuccessGreen else NeonCyan,
                                    contentColor = Color.Black
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .height(34.dp)
                                    .testTag("sav_editor_save_btn")
                            ) {
                                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(saveButtonLabel, fontSize = 11.sp, fontWeight = FontWeight.Black)
                            }
                        }

                        TabRow(
                            selectedTabIndex = SavEditorTab.entries.indexOf(activeTab),
                            containerColor = Color.Transparent,
                            contentColor = WarningOrange
                        ) {
                            SavEditorTab.entries.forEach { tab ->
                                Tab(
                                    selected = activeTab == tab,
                                    onClick = { activeTab = tab },
                                    text = { Text(tab.label, fontSize = 11.5.sp, fontWeight = if (activeTab == tab) FontWeight.Black else FontWeight.Medium) }
                                )
                            }
                        }

                        errorBanner?.let { msg ->
                            Text(
                                text = msg,
                                fontSize = 11.sp,
                                color = DangerRed,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(DangerRed.copy(alpha = 0.14f))
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            val res = savResult
            if (isLoading || res == null) {
                Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = WarningOrange)
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    // Header metadata pills
                    if (res.headerSummary.isNotEmpty()) {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(res.headerSummary) { (k, v) ->
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = "$k: $v",
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }

                    when (activeTab) {
                        SavEditorTab.FIELDS -> {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = { Text("Search ${res.fields.size} save properties & values...", fontSize = 11.5.sp) },
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                singleLine = true,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                            val q = searchQuery.trim().lowercase(Locale.US)
                            val filtered = remember(res.fields, q) {
                                if (q.isEmpty()) res.fields
                                else res.fields.filter {
                                    it.name.lowercase(Locale.US).contains(q) || it.value.lowercase(Locale.US).contains(q)
                                }
                            }
                            if (filtered.isEmpty()) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("No structured fields matched. Switch to Decompiled or Hex tab.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            } else {
                                LazyColumn(
                                    contentPadding = PaddingValues(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    items(filtered, key = { it.id }) { field ->
                                        val isEditable = field.kind != SavFieldValueKind.BYTES_HEX
                                        Card(
                                            shape = RoundedCornerShape(10.dp),
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable(enabled = isEditable) { editingField = field }
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 11.dp, vertical = 9.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                                        Text(
                                                            text = field.name,
                                                            fontSize = 11.5.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = NeonCyan,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                            modifier = Modifier.weight(1f, fill = false)
                                                        )
                                                        Surface(
                                                            color = WarningOrange.copy(alpha = 0.16f),
                                                            shape = RoundedCornerShape(4.dp)
                                                        ) {
                                                            Text(
                                                                text = field.typeLabel,
                                                                fontSize = 8.5.sp,
                                                                fontFamily = FontFamily.Monospace,
                                                                color = WarningOrange,
                                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                            )
                                                        }
                                                    }
                                                    Text(
                                                        text = field.value,
                                                        fontSize = 12.sp,
                                                        fontFamily = FontFamily.Monospace,
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                                if (isEditable) {
                                                    Icon(Icons.Default.Edit, contentDescription = "Edit Field", tint = SuccessGreen, modifier = Modifier.size(16.dp))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        SavEditorTab.CODE -> {
                            val isReadOnlyCode = res.format == SavFormatKind.BINARY_STRUCTURED
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color(0xFF0B1120))
                                    .verticalScroll(rememberScrollState())
                                    .padding(12.dp)
                            ) {
                                BasicTextField(
                                    value = editableCodeText,
                                    onValueChange = {
                                        if (!isReadOnlyCode) {
                                            editableCodeText = it
                                            hasChanges = true
                                        }
                                    },
                                    readOnly = isReadOnlyCode,
                                    textStyle = TextStyle(
                                        color = Color(0xFFE2E8F0),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.5.sp,
                                        lineHeight = 17.sp
                                    ),
                                    cursorBrush = SolidColor(WarningOrange),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .defaultMinSize(minHeight = 480.dp)
                                )
                            }
                        }

                        SavEditorTab.HEX -> {
                            LiveHexRowsList(
                                bytes = workingBytes,
                                onTapRowOffset = { offset -> editingHexOffset = offset }
                            )
                        }
                    }
                }
            }
        }
    }

    // Edit structured .sav field dialog
    editingField?.let { field ->
        var valueInput by remember(field.id) { mutableStateOf(field.value) }
        var localErr by remember(field.id) { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { editingField = null },
            title = { Text("Edit ${field.typeLabel}", fontSize = 14.sp, fontWeight = FontWeight.Black) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(field.name, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = NeonCyan)
                    if (field.kind == SavFieldValueKind.BOOLEAN) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Value: $valueInput", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Switch(
                                checked = valueInput.equals("true", ignoreCase = true),
                                onCheckedChange = { valueInput = it.toString() }
                            )
                        }
                    } else {
                        OutlinedTextField(
                            value = valueInput,
                            onValueChange = { valueInput = it; localErr = null },
                            label = {
                                Text(
                                    text = if (field.maxByteLength > 0 && field.kind == SavFieldValueKind.STRING) {
                                        "Value (max ${field.maxByteLength} bytes in-place)"
                                    } else "New Value",
                                    fontSize = 11.sp
                                )
                            },
                            isError = localErr != null,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    localErr?.let { Text(it, fontSize = 10.5.sp, color = DangerRed) }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val currentRes = savResult ?: return@Button
                        val updatedRes = DragonSavDecompilerEngine.applyFieldEdit(currentRes, field, valueInput)
                        updatedRes.onSuccess { next ->
                            savResult = next
                            workingBytes = next.rawBytes
                            editableCodeText = next.decompiledText
                            hasChanges = true
                            editingField = null
                        }.onFailure { err ->
                            localErr = err.message ?: "Invalid value"
                        }
                    }
                ) { Text("Apply") }
            },
            dismissButton = {
                TextButton(onClick = { editingField = null }) { Text("Cancel") }
            }
        )
    }

    // Hex Row Patcher Dialog
    editingHexOffset?.let { rowOff ->
        HexRowEditAlertDialog(
            bytes = workingBytes,
            rowOffset = rowOff,
            onDismiss = { editingHexOffset = null },
            onApplyBytes = { patched ->
                workingBytes = patched
                hasChanges = true
                editingHexOffset = null
                scope.launch {
                    val reparsed = withContext(Dispatchers.Default) {
                        DragonSavDecompilerEngine.decompileSav(patched)
                    }
                    savResult = reparsed
                    editableCodeText = reparsed.decompiledText
                }
            }
        )
    }
}

// =============================================================================
// 3. UNIVERSAL BINARY / HEX / STRINGS EDITOR (FOR .SO, .BIN, .DAT, .ARSC, ETC.)
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DragonUniversalHexAndBinaryEditorDialog(
    title: String,
    initialBytes: ByteArray,
    saveButtonLabel: String = "Stage Changes",
    onDismiss: () -> Unit,
    onSaveBytes: (ByteArray) -> Unit
) {
    var workingBytes by remember(initialBytes) { mutableStateOf(initialBytes) }
    var activeSubTab by remember { mutableIntStateOf(0) } // 0=Hex, 1=Extracted Strings
    var searchQuery by remember { mutableStateOf("") }
    var editingHexOffset by remember { mutableStateOf<Int?>(null) }
    var editingStringEntry by remember { mutableStateOf<SavStringEntry?>(null) }
    var hasChanges by remember { mutableStateOf(false) }

    val extractedStrings = remember(workingBytes, activeSubTab) {
        if (activeSubTab != 1) emptyList()
        else DragonDocumentEngine.extractSavStringsAndValues(workingBytes, maxEntries = 600)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .testTag("universal_hex_binary_editor_dialog"),
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 4.dp) {
                    Column(modifier = Modifier.fillMaxWidth().statusBarsPadding()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close")
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(title, fontSize = 13.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    text = "BINARY HEX EDITOR • ${formatBytes(workingBytes.size.toLong())}",
                                    fontSize = 9.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = NeonCyan
                                )
                            }
                            Button(
                                onClick = { onSaveBytes(workingBytes) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (hasChanges) SuccessGreen else NeonCyan,
                                    contentColor = Color.Black
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Text(saveButtonLabel, fontSize = 11.sp, fontWeight = FontWeight.Black)
                            }
                        }
                        TabRow(selectedTabIndex = activeSubTab, containerColor = Color.Transparent, contentColor = NeonCyan) {
                            Tab(selected = activeSubTab == 0, onClick = { activeSubTab = 0 }, text = { Text("Hex & ASCII", fontSize = 11.5.sp) })
                            Tab(selected = activeSubTab == 1, onClick = { activeSubTab = 1 }, text = { Text("Strings Patcher", fontSize = 11.5.sp) })
                        }
                    }
                }
            }
        ) { innerPadding ->
            Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                if (activeSubTab == 0) {
                    LiveHexRowsList(
                        bytes = workingBytes,
                        onTapRowOffset = { editingHexOffset = it }
                    )
                } else {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search embedded ASCII strings...", fontSize = 11.5.sp) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                    val q = searchQuery.trim().lowercase(Locale.US)
                    val filtered = remember(extractedStrings, q) {
                        if (q.isEmpty()) extractedStrings
                        else extractedStrings.filter { it.text.lowercase(Locale.US).contains(q) }
                    }
                    LazyColumn(
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(filtered, key = { it.offset }) { entry ->
                            Card(
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { editingStringEntry = entry }
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(entry.text, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text("0x%06X • %d bytes".format(entry.offset, entry.length), fontSize = 9.sp, color = NeonCyan)
                                    }
                                    Icon(Icons.Default.Edit, contentDescription = "Patch", tint = NeonCyan, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    editingHexOffset?.let { off ->
        HexRowEditAlertDialog(
            bytes = workingBytes,
            rowOffset = off,
            onDismiss = { editingHexOffset = null },
            onApplyBytes = { patched ->
                workingBytes = patched
                hasChanges = true
                editingHexOffset = null
            }
        )
    }

    editingStringEntry?.let { entry ->
        var textVal by remember(entry.offset) { mutableStateOf(entry.text) }
        AlertDialog(
            onDismissRequest = { editingStringEntry = null },
            title = { Text("In-Place Binary String Patch", fontSize = 14.sp, fontWeight = FontWeight.Black) },
            text = {
                OutlinedTextField(
                    value = textVal,
                    onValueChange = { textVal = it },
                    label = { Text("New Text (max ${entry.length} bytes)", fontSize = 11.sp) },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        workingBytes = DragonDocumentEngine.patchSavStringInPlace(workingBytes, entry, textVal)
                        hasChanges = true
                        editingStringEntry = null
                    }
                ) { Text("Patch") }
            },
            dismissButton = { TextButton(onClick = { editingStringEntry = null }) { Text("Cancel") } }
        )
    }
}

// =============================================================================
// 4. APK IMAGE VIEWER & REPLACER DIALOG (.PNG, .WEBP, .JPG, .GIF)
// =============================================================================

@Composable
fun DragonApkImageViewerEditorDialog(
    context: Context,
    entryPath: String,
    initialBytes: ByteArray,
    onDismiss: () -> Unit,
    onStageReplacementBytes: (ByteArray) -> Unit,
    onExportToStorage: (ByteArray) -> Unit
) {
    val scope = rememberCoroutineScope()
    var currentBytes by remember(initialBytes) { mutableStateOf(initialBytes) }
    val bitmap = remember(currentBytes) {
        runCatching { BitmapFactory.decodeByteArray(currentBytes, 0, currentBytes.size) }.getOrNull()
    }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val bytes = withContext(Dispatchers.IO) {
                    runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                }
                if (bytes != null) currentBytes = bytes
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val bytes = withContext(Dispatchers.IO) {
                    runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                }
                if (bytes != null) currentBytes = bytes
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(entryPath.substringAfterLast('/'), fontSize = 14.5.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = buildString {
                        append(formatBytes(currentBytes.size.toLong()))
                        if (bitmap != null) append(" • ${bitmap.width} × ${bitmap.height} px")
                    },
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = NeonCyan
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF0F172A)),
                    contentAlignment = Alignment.Center
                ) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = entryPath,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().padding(12.dp)
                        )
                    } else {
                        Text("Unable to decode image preview", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Gallery", fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = { filePicker.launch(arrayOf("image/*")) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("File", fontSize = 11.sp)
                    }
                }
                TextButton(onClick = { onExportToStorage(currentBytes) }) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Export Image to DDM/Extracted Apk", fontSize = 11.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onStageReplacementBytes(currentBytes)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen, contentColor = Color.Black)
            ) { Text("Stage Image") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

// =============================================================================
// SHARED LIVE HEX VIEWER & ROW EDITOR COMPONENTS
// =============================================================================

@Composable
internal fun LiveHexRowsList(
    bytes: ByteArray,
    onTapRowOffset: (Int) -> Unit
) {
    val bytesPerRow = 16
    val maxDisplayBytes = minOf(bytes.size, 256 * 1024) // Fast scrolling up to 256 KB
    val rowCount = (maxDisplayBytes + bytesPerRow - 1) / bytesPerRow

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0B1120))) {
        Text(
            text = "Tap any row to edit hex bytes (${formatBytes(bytes.size.toLong())}${if (bytes.size > maxDisplayBytes) ", showing first 256 KB" else ""})",
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = NeonCyan,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(rowCount) { rowIdx ->
                val off = rowIdx * bytesPerRow
                val end = minOf(off + bytesPerRow, maxDisplayBytes)
                val hexPart = buildString {
                    for (i in off until end) {
                        append("%02X ".format(bytes[i]))
                    }
                }.padEnd(bytesPerRow * 3, ' ')
                val asciiPart = buildString {
                    for (i in off until end) {
                        val v = bytes[i].toInt() and 0xFF
                        append(if (v in 0x20..0x7E) v.toChar() else '.')
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { onTapRowOffset(off) }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("%06X".format(off), fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = WarningOrange)
                    Text(hexPart, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Color(0xFFE2E8F0), modifier = Modifier.weight(1f))
                    Text(asciiPart, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = SuccessGreen)
                }
            }
        }
    }
}

@Composable
internal fun HexRowEditAlertDialog(
    bytes: ByteArray,
    rowOffset: Int,
    onDismiss: () -> Unit,
    onApplyBytes: (ByteArray) -> Unit
) {
    val end = minOf(rowOffset + 16, bytes.size)
    val initialHex = remember(rowOffset, bytes) {
        (rowOffset until end).joinToString(" ") { "%02X".format(bytes[it]) }
    }
    var hexInput by remember(rowOffset) { mutableStateOf(initialHex) }
    var errText by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Hex @ 0x%06X".format(rowOffset), fontSize = 14.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = hexInput,
                    onValueChange = { hexInput = it; errText = null },
                    label = { Text("Hex Bytes (space-separated)", fontSize = 11.sp) },
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    isError = errText != null,
                    modifier = Modifier.fillMaxWidth()
                )
                errText?.let { Text(it, fontSize = 10.5.sp, color = DangerRed) }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val (patched, err) = DragonDocumentEngine.patchHexRow(bytes, rowOffset, hexInput)
                    if (patched != null) onApplyBytes(patched)
                    else errText = err ?: "Invalid hex input"
                }
            ) { Text("Patch Bytes") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Standalone file host for File Manager taps on `.xml` (Binary AXML or Plain XML),
 * `.sav` (UE4/5 GVAS, Zlib/GZip/Base64, JSON/XML/INI, Binary), and `.dex` files.
 */
@Composable
fun StandaloneBinaryFormatEditorHost(
    context: Context,
    item: FileItemEntry,
    onDismiss: () -> Unit,
    onSaved: (File) -> Unit
) {
    val scope = rememberCoroutineScope()
    var loadedBytes by remember(item.file.absolutePath) { mutableStateOf<ByteArray?>(null) }
    var loadFailed by remember(item.file.absolutePath) { mutableStateOf(false) }

    LaunchedEffect(item.file.absolutePath) {
        val bytes = withContext(Dispatchers.IO) {
            DragonDocumentEngine.readBinaryBytes(
                context = context,
                file = item.file,
                maxBytes = 48 * 1024 * 1024
            )
        }
        if (bytes.isEmpty() && item.lengthBytes > 0L) {
            loadFailed = true
        } else {
            loadedBytes = bytes
        }
    }

    fun persistBytes(outBytes: ByteArray, createBackup: Boolean) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                DragonDocumentEngine.writeBinaryBytes(
                    context = context,
                    file = item.file,
                    bytes = outBytes,
                    createBackup = createBackup
                )
            }
            if (ok) {
                ApkPackageInstaller.showToastOnMain(context, "Saved ${item.name} (${formatBytes(outBytes.size.toLong())})")
                onSaved(item.file)
                onDismiss()
            } else {
                ApkPackageInstaller.showToastOnMain(context, "Failed to write ${item.name}. Check storage permissions.")
            }
        }
    }

    val bytes = loadedBytes
    when {
        loadFailed -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Cannot Read File", fontWeight = FontWeight.Black) },
                text = { Text("Unable to read ${item.name}. Ensure storage access is granted.") },
                confirmButton = { Button(onClick = onDismiss) { Text("OK") } }
            )
        }
        bytes == null -> {
            Dialog(onDismissRequest = onDismiss) {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                    Row(
                        modifier = Modifier.padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(color = NeonCyan, modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                        Text("Loading ${item.name}...", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        else -> {
            val extLower = item.extension.lowercase(Locale.US)
            when {
                extLower == "dex" -> {
                    DragonDexEditorDialog(
                        context = context,
                        title = item.name,
                        initialDexBytes = bytes,
                        saveButtonLabel = "Save DEX",
                        allowBackupToggle = true,
                        onDismiss = onDismiss,
                        onSaveDexBytes = { patched, backup -> persistBytes(patched, backup) }
                    )
                }
                extLower == "xml" -> {
                    DragonProXmlEditorDialog(
                        context = context,
                        title = item.name,
                        initialBytes = bytes,
                        saveButtonLabel = "Save",
                        allowBackupToggle = true,
                        onDismiss = onDismiss,
                        onSaveCompiledBytes = { compiled, backup -> persistBytes(compiled, backup) }
                    )
                }
                else -> {
                    DragonProSavEditorDialog(
                        context = context,
                        title = item.name,
                        initialBytes = bytes,
                        saveButtonLabel = "Save SAV",
                        allowBackupToggle = true,
                        onDismiss = onDismiss,
                        onSaveBytes = { outBytes, backup -> persistBytes(outBytes, backup) }
                    )
                }
            }
        }
    }
}

