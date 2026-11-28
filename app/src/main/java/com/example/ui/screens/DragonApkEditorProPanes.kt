package com.example.ui.screens

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
// TAB 3: PROFESSIONAL RESOURCES.ARSC EDITOR (TYPED RESOURCES + STRING POOL)
// =============================================================================

@Composable
internal fun ApkArscStringsPane(
    project: ApkEditorProject,
    edits: ApkStagedEdits,
    onUpdateEdits: (ApkStagedEdits) -> Unit
) {
    var mode by remember { mutableIntStateOf(if (project.arscTypedResources.isNotEmpty()) 0 else 1) } // 0=Typed, 1=String Pool
    var searchQuery by remember { mutableStateOf("") }
    var selectedTypeFilter by remember { mutableStateOf("All") }
    var showModifiedOnly by remember { mutableStateOf(false) }
    var includeResPaths by remember { mutableStateOf(false) }

    var editingStringEntry by remember { mutableStateOf<ArscStringEntry?>(null) }
    var editingTypedEntry by remember { mutableStateOf<ArscTypedResourceEntry?>(null) }

    val availableTypes = remember(project.arscTypedResources) {
        val counts = project.arscTypedResources.groupingBy { it.typeName }.eachCount()
        listOf("All") + counts.entries.sortedByDescending { it.value }.map { it.key }.take(12)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = mode == 0,
                onClick = { mode = 0 },
                label = { Text("Typed ARSC (${project.arscTypedResources.size})", fontSize = 10.5.sp, fontWeight = FontWeight.Bold) }
            )
            FilterChip(
                selected = mode == 1,
                onClick = { mode = 1 },
                label = { Text("String Pool (${project.arscStrings.size})", fontSize = 10.5.sp, fontWeight = FontWeight.Bold) }
            )
            Spacer(Modifier.weight(1f))
            FilterChip(
                selected = showModifiedOnly,
                onClick = { showModifiedOnly = !showModifiedOnly },
                label = {
                    val c = edits.arscStringPatches.size + edits.arscTypedValuePatches.size
                    Text("Edited ($c)", fontSize = 10.sp)
                }
            )
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = {
                Text(
                    if (mode == 0) "Search colors, strings, bools, integers, dimens, res IDs..."
                    else "Search ${project.arscStrings.size} resources.arsc strings...",
                    fontSize = 11.sp
                )
            },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp)) },
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
        )

        val q = searchQuery.trim().lowercase(Locale.US)

        if (mode == 0) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(availableTypes) { typeName ->
                    FilterChip(
                        selected = selectedTypeFilter == typeName,
                        onClick = { selectedTypeFilter = typeName },
                        label = { Text(typeName, fontSize = 10.sp) }
                    )
                }
            }

            val filteredTyped = remember(
                project.arscTypedResources, q, selectedTypeFilter, showModifiedOnly,
                edits.arscTypedValuePatches, edits.arscStringPatches
            ) {
                project.arscTypedResources
                    .asSequence()
                    .filter { selectedTypeFilter == "All" || it.typeName == selectedTypeFilter }
                    .filter { entry ->
                        val isPatched = edits.arscTypedValuePatches.containsKey(entry.valueOffsetInArsc) ||
                            (entry.stringPoolIndex != null && edits.arscStringPatches.containsKey(entry.stringPoolIndex))
                        if (showModifiedOnly && !isPatched) return@filter false
                        q.isEmpty() ||
                            entry.entryName.lowercase(Locale.US).contains(q) ||
                            entry.displayValue.lowercase(Locale.US).contains(q) ||
                            entry.resourceIdHex.lowercase(Locale.US).contains(q) ||
                            entry.typeName.lowercase(Locale.US).contains(q)
                    }
                    .take(400)
                    .toList()
            }

            LazyColumn(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(filteredTyped, key = { "${it.resourceId}_${it.valueOffsetInArsc}" }) { entry ->
                    val patchedRaw = edits.arscTypedValuePatches[entry.valueOffsetInArsc]
                    val patchedStr = entry.stringPoolIndex?.let { edits.arscStringPatches[it] }
                    val isModified = patchedRaw != null || patchedStr != null
                    val effectiveType = patchedRaw?.first ?: entry.valueType
                    val effectiveData = patchedRaw?.second ?: entry.rawData
                    val effectiveDisplay = when {
                        patchedStr != null -> patchedStr
                        patchedRaw != null -> DragonAxmlCompilerEngine.formatTypedValue(effectiveType, effectiveData, -1, emptyList())
                        else -> entry.displayValue
                    }
                    val isColor = effectiveType in setOf(
                        DragonAxmlCompilerEngine.TYPE_INT_COLOR_ARGB8,
                        DragonAxmlCompilerEngine.TYPE_INT_COLOR_RGB8,
                        DragonAxmlCompilerEngine.TYPE_INT_COLOR_ARGB4,
                        DragonAxmlCompilerEngine.TYPE_INT_COLOR_RGB4
                    )

                    Card(
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(
                            1.dp,
                            if (isModified) SuccessGreen.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (entry.stringPoolIndex != null) {
                                    val spIdx = entry.stringPoolIndex
                                    val origStr = project.arscStrings.getOrNull(spIdx)?.value ?: entry.displayValue
                                    editingStringEntry = ArscStringEntry(spIdx, origStr)
                                } else {
                                    editingTypedEntry = entry
                                }
                            }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (isColor) {
                                Box(
                                    modifier = Modifier
                                        .size(26.dp)
                                        .clip(CircleShape)
                                        .background(Color(effectiveData))
                                        .border(1.5.dp, Color.White.copy(alpha = 0.7f), CircleShape)
                                )
                            } else {
                                Surface(
                                    color = NeonCyan.copy(alpha = 0.16f),
                                    shape = RoundedCornerShape(5.dp)
                                ) {
                                    Text(
                                        text = entry.typeName.take(6).uppercase(Locale.US),
                                        fontSize = 8.5.sp,
                                        fontWeight = FontWeight.Black,
                                        color = NeonCyan,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 3.dp)
                                    )
                                }
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${entry.typeName}/${entry.entryName}",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isModified) SuccessGreen else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "$effectiveDisplay  •  ${entry.resourceIdHex}",
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Icon(Icons.Default.Edit, contentDescription = "Edit Resource", tint = NeonCyan, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = includeResPaths,
                    onClick = { includeResPaths = !includeResPaths },
                    label = { Text("Show res/ paths", fontSize = 10.sp) }
                )
            }
            val filtered = remember(project.arscStrings, q, showModifiedOnly, includeResPaths, edits.arscStringPatches) {
                project.arscStrings
                    .asSequence()
                    .filter { it.value.isNotBlank() && (includeResPaths || !it.value.startsWith("res/")) }
                    .filter { entry ->
                        if (showModifiedOnly && !edits.arscStringPatches.containsKey(entry.index)) return@filter false
                        val effective = edits.arscStringPatches[entry.index] ?: entry.value
                        q.isEmpty() || effective.lowercase(Locale.US).contains(q) || entry.value.lowercase(Locale.US).contains(q)
                    }
                    .take(450)
                    .toList()
            }

            LazyColumn(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(filtered, key = { it.index }) { entry ->
                    val patched = edits.arscStringPatches[entry.index]
                    val displayVal = patched ?: entry.value
                    Card(
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(
                            1.dp,
                            if (patched != null) SuccessGreen.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { editingStringEntry = entry }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("#${entry.index}", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = if (patched != null) SuccessGreen else NeonCyan)
                            Text(displayVal, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Icon(Icons.Default.Edit, contentDescription = "Edit String", tint = NeonCyan, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }

    // String Pool Edit Dialog
    editingStringEntry?.let { entry ->
        var newValue by remember(entry.index) { mutableStateOf(edits.arscStringPatches[entry.index] ?: entry.value) }
        AlertDialog(
            onDismissRequest = { editingStringEntry = null },
            title = { Text("Edit Resource String #${entry.index}", fontSize = 14.sp, fontWeight = FontWeight.ExtraBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Original: ${entry.value}", fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
                    OutlinedTextField(
                        value = newValue,
                        onValueChange = { newValue = it },
                        label = { Text("New String Value", fontSize = 11.sp) },
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val updated = edits.arscStringPatches.toMutableMap()
                        if (newValue == entry.value) updated.remove(entry.index) else updated[entry.index] = newValue
                        onUpdateEdits(edits.copy(arscStringPatches = updated))
                        editingStringEntry = null
                    }
                ) { Text("Stage Edit") }
            },
            dismissButton = { TextButton(onClick = { editingStringEntry = null }) { Text("Cancel") } }
        )
    }

    // Typed ARSC Value Edit Dialog (Colors, Booleans, Integers, Floats, Dimensions, References)
    editingTypedEntry?.let { entry ->
        val existingPatch = edits.arscTypedValuePatches[entry.valueOffsetInArsc]
        val initialVal = remember(entry.valueOffsetInArsc, existingPatch) {
            if (existingPatch != null) DragonAxmlCompilerEngine.formatTypedValue(existingPatch.first, existingPatch.second, -1, emptyList())
            else entry.displayValue
        }
        var valueInput by remember(entry.valueOffsetInArsc) { mutableStateOf(initialVal) }
        var errMsg by remember(entry.valueOffsetInArsc) { mutableStateOf<String?>(null) }

        val previewColor = remember(valueInput, entry.valueType) {
            val parsed = DragonAxmlCompilerEngine.parseEditedTypedResourceValue(entry.valueType, valueInput)
            if (parsed != null && parsed.first in setOf(
                    DragonAxmlCompilerEngine.TYPE_INT_COLOR_ARGB8,
                    DragonAxmlCompilerEngine.TYPE_INT_COLOR_RGB8
                )
            ) Color(parsed.second) else null
        }

        AlertDialog(
            onDismissRequest = { editingTypedEntry = null },
            title = { Text("Edit ${entry.typeName}/${entry.entryName}", fontSize = 14.sp, fontWeight = FontWeight.Black) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${entry.valueTypeLabel} • ${entry.resourceIdHex}", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = NeonCyan)
                    if (entry.valueType == DragonAxmlCompilerEngine.TYPE_INT_BOOLEAN) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Boolean Value: $valueInput", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Switch(
                                checked = valueInput.equals("true", true),
                                onCheckedChange = { valueInput = it.toString() }
                            )
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (previewColor != null) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(previewColor)
                                        .border(2.dp, Color.White, CircleShape)
                                )
                            }
                            OutlinedTextField(
                                value = valueInput,
                                onValueChange = { valueInput = it; errMsg = null },
                                label = { Text("Value (e.g. #FF00E5FF, 16dp, 42, true)", fontSize = 10.5.sp) },
                                isError = errMsg != null,
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    errMsg?.let { Text(it, fontSize = 10.5.sp, color = DangerRed) }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val parsed = DragonAxmlCompilerEngine.parseEditedTypedResourceValue(entry.valueType, valueInput)
                        if (parsed == null) {
                            errMsg = "Invalid ${entry.valueTypeLabel} value format"
                        } else {
                            val next = edits.arscTypedValuePatches.toMutableMap()
                            next[entry.valueOffsetInArsc] = parsed
                            onUpdateEdits(edits.copy(arscTypedValuePatches = next))
                            editingTypedEntry = null
                        }
                    }
                ) { Text("Stage Resource") }
            },
            dismissButton = { TextButton(onClick = { editingTypedEntry = null }) { Text("Cancel") } }
        )
    }
}

// =============================================================================
// TAB 4: PRO INTERNAL APK FILE EXPLORER — EDIT EVERYTHING INSIDE THE APK!
// =============================================================================

private enum class ApkFileFilterCategory(val label: String) {
    ALL("All"),
    XML("XML / AXML"),
    DEX("DEX"),
    SAV("SAV"),
    IMAGES("Images"),
    NATIVE("Native .so"),
    ASSETS("Assets"),
    MODIFIED("Modified")
}

@Composable
internal fun ApkInternalFilesPane(
    context: Context,
    ddmRootDir: File,
    project: ApkEditorProject,
    edits: ApkStagedEdits,
    onUpdateEdits: (ApkStagedEdits) -> Unit
) {
    val scope = rememberCoroutineScope()
    var currentVirtualFolder by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    var activeFilter by remember { mutableStateOf(ApkFileFilterCategory.ALL) }

    // Specialized editor launch states for EVERY file type inside the APK!
    var xmlEditorTarget by remember { mutableStateOf<Pair<String, ByteArray>?>(null) }
    var dexEditorTarget by remember { mutableStateOf<Pair<String, ByteArray>?>(null) }
    var savEditorTarget by remember { mutableStateOf<Pair<String, ByteArray>?>(null) }
    var imageEditorTarget by remember { mutableStateOf<Pair<String, ByteArray>?>(null) }
    var hexEditorTarget by remember { mutableStateOf<Pair<String, ByteArray>?>(null) }
    var codeEditorTarget by remember { mutableStateOf<Pair<String, String>?>(null) }

    var actionMenuPath by remember { mutableStateOf<String?>(null) }
    var renamingPath by remember { mutableStateOf<String?>(null) }
    var pendingReplaceTargetPath by remember { mutableStateOf<String?>(null) }
    var showAddNewFileDialog by remember { mutableStateOf(false) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        val targetPath = pendingReplaceTargetPath
        pendingReplaceTargetPath = null
        if (uri != null && targetPath != null) {
            scope.launch {
                val bytes = withContext(Dispatchers.IO) {
                    runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                }
                if (bytes != null) {
                    val nextMap = edits.replacedOrAddedFiles.toMutableMap()
                    nextMap[targetPath] = bytes
                    onUpdateEdits(edits.copy(replacedOrAddedFiles = nextMap, deletedEntryPaths = edits.deletedEntryPaths - targetPath))
                    ApkPackageInstaller.showToastOnMain(context, "Staged file: $targetPath (${formatBytes(bytes.size.toLong())})")
                }
            }
        }
    }

    suspend fun loadEntryCurrentBytes(path: String): ByteArray? {
        if (path == "AndroidManifest.xml" && edits.rawManifestAxmlOverride != null) return edits.rawManifestAxmlOverride
        edits.dexByteOverrides[path]?.let { return it }
        edits.replacedOrAddedFiles[path]?.let { return it }
        val origPath = edits.renamedEntryPaths.entries.firstOrNull { it.value == path }?.key ?: path
        return withContext(Dispatchers.IO) {
            DragonApkStudioEngine.readApkEntryBytes(project.sourceApkFile, origPath)
        }
    }

    fun openSmartEditorForEntry(path: String, forceHex: Boolean = false) {
        scope.launch {
            val bytes = loadEntryCurrentBytes(path) ?: return@launch
            if (forceHex) {
                hexEditorTarget = path to bytes
                return@launch
            }
            val ext = path.substringAfterLast('.', "").lowercase(Locale.US)
            when {
                ext == "xml" -> xmlEditorTarget = path to bytes
                ext == "dex" -> dexEditorTarget = path to bytes
                ext in DragonDocumentEngine.SAVE_FILE_EXTENSIONS -> savEditorTarget = path to bytes
                ext in setOf("png", "webp", "jpg", "jpeg", "gif") -> imageEditorTarget = path to bytes
                ext in setOf(
                    "txt", "json", "html", "htm", "js", "ts", "css", "properties", "prop",
                    "cfg", "ini", "mf", "sf", "md", "csv", "yaml", "yml", "svg", "smali",
                    "sql", "sh", "gradle", "pro", "glsl", "frag", "vert"
                ) -> codeEditorTarget = path to String(bytes, Charsets.UTF_8)
                else -> hexEditorTarget = path to bytes
            }
        }
    }

    // Combine original entries (applying renames) + newly added entries
    val allEntries = remember(project.zipEntries, edits.replacedOrAddedFiles, edits.dexByteOverrides, edits.renamedEntryPaths) {
        val map = LinkedHashMap<String, ApkZipFileEntry>()
        project.zipEntries.forEach { e ->
            val effectivePath = edits.renamedEntryPaths[e.path] ?: e.path
            map[effectivePath] = e.copy(path = effectivePath)
        }
        edits.replacedOrAddedFiles.forEach { (p, bytes) ->
            val existing = map[p]
            map[p] = ApkZipFileEntry(p, bytes.size.toLong(), bytes.size.toLong(), existing?.isStored ?: false)
        }
        edits.dexByteOverrides.forEach { (p, bytes) ->
            map[p] = ApkZipFileEntry(p, bytes.size.toLong(), bytes.size.toLong(), false)
        }
        map.values.toList()
    }

    val q = searchQuery.trim().lowercase(Locale.US)
    val isFlatMode = q.isNotEmpty() || activeFilter != ApkFileFilterCategory.ALL

    val visibleItems = remember(allEntries, currentVirtualFolder, q, activeFilter, edits) {
        fun matchesFilter(path: String): Boolean {
            val lower = path.lowercase(Locale.US)
            val ext = lower.substringAfterLast('.', "")
            return when (activeFilter) {
                ApkFileFilterCategory.ALL -> true
                ApkFileFilterCategory.XML -> ext == "xml"
                ApkFileFilterCategory.DEX -> ext == "dex"
                ApkFileFilterCategory.SAV -> ext in DragonDocumentEngine.SAVE_FILE_EXTENSIONS
                ApkFileFilterCategory.IMAGES -> ext in setOf("png", "webp", "jpg", "jpeg", "gif", "svg")
                ApkFileFilterCategory.NATIVE -> ext == "so"
                ApkFileFilterCategory.ASSETS -> lower.startsWith("assets/")
                ApkFileFilterCategory.MODIFIED -> edits.replacedOrAddedFiles.containsKey(path) ||
                    edits.dexByteOverrides.containsKey(path) ||
                    (path == "AndroidManifest.xml" && edits.rawManifestAxmlOverride != null) ||
                    path in edits.deletedEntryPaths ||
                    edits.renamedEntryPaths.containsValue(path) ||
                    edits.compressionOverrides.containsKey(path)
            }
        }

        if (isFlatMode) {
            allEntries
                .asSequence()
                .filter { matchesFilter(it.path) }
                .filter { q.isEmpty() || it.path.lowercase(Locale.US).contains(q) }
                .take(350)
                .map { Triple(it.path, false, it.sizeBytes) }
                .toList()
        } else {
            val prefix = if (currentVirtualFolder.isEmpty()) "" else "$currentVirtualFolder/"
            val folders = LinkedHashSet<String>()
            val files = mutableListOf<Triple<String, Boolean, Long>>()
            allEntries.forEach { entry ->
                if (!entry.path.startsWith(prefix)) return@forEach
                val remainder = entry.path.removePrefix(prefix)
                if (remainder.contains('/')) {
                    val subFolder = remainder.substringBefore('/')
                    folders.add(if (prefix.isEmpty()) subFolder else "$prefix$subFolder")
                } else {
                    files.add(Triple(entry.path, false, entry.sizeBytes))
                }
            }
            folders.sorted().map { Triple(it, true, 0L) } + files.sortedBy { it.first.lowercase(Locale.US) }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search & edit ANY of ${allEntries.size} APK files...", fontSize = 11.sp) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp)) },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f)
            )
            FilledTonalIconButton(onClick = { showAddNewFileDialog = true }) {
                Icon(Icons.Default.NoteAdd, contentDescription = "Add File to APK", tint = SuccessGreen)
            }
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(ApkFileFilterCategory.entries) { cat ->
                FilterChip(
                    selected = activeFilter == cat,
                    onClick = { activeFilter = cat },
                    label = { Text(cat.label, fontSize = 10.sp) }
                )
            }
        }

        if (!isFlatMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (currentVirtualFolder.isNotEmpty()) {
                    IconButton(
                        onClick = {
                            currentVirtualFolder = if (currentVirtualFolder.contains('/')) {
                                currentVirtualFolder.substringBeforeLast('/')
                            } else ""
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Up", tint = NeonCyan, modifier = Modifier.size(16.dp))
                    }
                }
                Text(
                    text = "/$currentVirtualFolder",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = NeonCyan,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        LazyColumn(
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(visibleItems, key = { "${it.second}_${it.first}" }) { (path, isDir, size) ->
                val isDeleted = path in edits.deletedEntryPaths
                val isModified = edits.replacedOrAddedFiles.containsKey(path) ||
                    edits.dexByteOverrides.containsKey(path) ||
                    (path == "AndroidManifest.xml" && edits.rawManifestAxmlOverride != null)
                val leafName = path.substringAfterLast('/')
                val ext = leafName.substringAfterLast('.', "").lowercase(Locale.US)
                val editorBadge = when {
                    isDir -> null
                    ext == "xml" -> "AXML / XML"
                    ext == "dex" -> "DEX PRO"
                    ext in DragonDocumentEngine.SAVE_FILE_EXTENSIONS -> "SAV PRO"
                    ext in setOf("png", "webp", "jpg", "jpeg", "gif") -> "IMAGE"
                    ext == "so" -> "NATIVE HEX"
                    else -> "EDIT / HEX"
                }

                Card(
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(
                        1.dp,
                        when {
                            isDeleted -> DangerRed.copy(alpha = 0.5f)
                            isModified -> SuccessGreen.copy(alpha = 0.65f)
                            else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        }
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (isDir) currentVirtualFolder = path
                            else openSmartEditorForEntry(path, forceHex = false)
                        }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 11.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = when {
                                isDir -> Icons.Default.Folder
                                ext == "xml" -> Icons.Default.Code
                                ext == "dex" -> Icons.Default.Memory
                                ext in setOf("png", "webp", "jpg", "jpeg", "gif") -> Icons.Default.Image
                                else -> Icons.Default.InsertDriveFile
                            },
                            contentDescription = null,
                            tint = when {
                                isDir -> WarningOrange
                                isModified -> SuccessGreen
                                ext == "dex" -> Color(0xFFA855F7)
                                ext in setOf("png", "webp", "jpg", "jpeg", "gif") -> Color(0xFFF472B6)
                                else -> NeonCyan
                            },
                            modifier = Modifier.size(20.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isFlatMode) path else leafName,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isDeleted) DangerRed else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (!isDir) {
                                val compOverride = edits.compressionOverrides[path]
                                Text(
                                    text = buildString {
                                        append(formatBytes(size))
                                        editorBadge?.let { append(" • $it") }
                                        if (compOverride != null) append(if (compOverride) " • STORED" else " • DEFLATED")
                                        if (isModified) append(" • EDITED")
                                        if (isDeleted) append(" • DELETED")
                                    },
                                    fontSize = 9.5.sp,
                                    color = if (isModified) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (!isDir) {
                            IconButton(
                                onClick = { actionMenuPath = path },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.MoreVert, contentDescription = "File Options", tint = NeonCyan, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    // Per-Entry Full Action Menu Dialog
    actionMenuPath?.let { path ->
        val isDeleted = path in edits.deletedEntryPaths
        val currentStored = edits.compressionOverrides[path]
            ?: allEntries.firstOrNull { it.path == path }?.isStored ?: false
        AlertDialog(
            onDismissRequest = { actionMenuPath = null },
            title = { Text(path.substringAfterLast('/'), fontSize = 14.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(path, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = NeonCyan)
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = { actionMenuPath = null; openSmartEditorForEntry(path, forceHex = false) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Decompile / Edit File", modifier = Modifier.fillMaxWidth()) }
                    TextButton(
                        onClick = { actionMenuPath = null; openSmartEditorForEntry(path, forceHex = true) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Open in Raw Hex & Strings Editor", modifier = Modifier.fillMaxWidth()) }
                    TextButton(
                        onClick = {
                            actionMenuPath = null
                            pendingReplaceTargetPath = path
                            filePickerLauncher.launch(arrayOf("*/*"))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Replace from Device Storage", modifier = Modifier.fillMaxWidth()) }
                    TextButton(
                        onClick = { actionMenuPath = null; renamingPath = path },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Rename / Move Path inside APK", modifier = Modifier.fillMaxWidth()) }
                    TextButton(
                        onClick = {
                            val nextComp = edits.compressionOverrides.toMutableMap()
                            nextComp[path] = !currentStored
                            onUpdateEdits(edits.copy(compressionOverrides = nextComp))
                            actionMenuPath = null
                            ApkPackageInstaller.showToastOnMain(context, "$path -> ${if (!currentStored) "STORED" else "DEFLATED"}")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Compression: ${if (currentStored) "STORED -> Switch to DEFLATED" else "DEFLATED -> Switch to STORED"}",
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    TextButton(
                        onClick = {
                            actionMenuPath = null
                            scope.launch {
                                val bytes = loadEntryCurrentBytes(path)
                                if (bytes != null) {
                                    val outDir = DragonApkStudioEngine.ensureExtractedApkFolder(ddmRootDir)
                                    val outFile = File(outDir, path.substringAfterLast('/'))
                                    val ok = withContext(Dispatchers.IO) {
                                        DragonDocumentEngine.writeBinaryBytes(context, outFile, bytes, createBackup = false)
                                    }
                                    ApkPackageInstaller.showToastOnMain(
                                        context,
                                        if (ok) "Extracted to DDM/${DragonApkStudioEngine.EXTRACTED_APK_FOLDER_NAME}/${outFile.name}" else "Failed to extract file"
                                    )
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Extract to Storage (DDM/Extracted Apk)", modifier = Modifier.fillMaxWidth()) }
                    TextButton(
                        onClick = {
                            val nextDel = if (isDeleted) edits.deletedEntryPaths - path else edits.deletedEntryPaths + path
                            onUpdateEdits(edits.copy(deletedEntryPaths = nextDel))
                            actionMenuPath = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (isDeleted) "Restore Deleted Entry" else "Delete from APK",
                            color = if (isDeleted) SuccessGreen else DangerRed,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { actionMenuPath = null }) { Text("Close") } }
        )
    }

    // Rename Entry Path Dialog
    renamingPath?.let { oldPath ->
        var newPathInput by remember(oldPath) { mutableStateOf(oldPath) }
        AlertDialog(
            onDismissRequest = { renamingPath = null },
            title = { Text("Rename / Move Internal Path", fontSize = 14.sp, fontWeight = FontWeight.Black) },
            text = {
                OutlinedTextField(
                    value = newPathInput,
                    onValueChange = { newPathInput = it },
                    label = { Text("New APK path", fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val clean = newPathInput.trim().trimStart('/')
                        if (clean.isNotEmpty() && clean != oldPath) {
                            val origKey = edits.renamedEntryPaths.entries.firstOrNull { it.value == oldPath }?.key ?: oldPath
                            val nextRenames = edits.renamedEntryPaths.toMutableMap()
                            nextRenames[origKey] = clean
                            onUpdateEdits(edits.copy(renamedEntryPaths = nextRenames))
                        }
                        renamingPath = null
                    }
                ) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renamingPath = null }) { Text("Cancel") } }
        )
    }

    // Embedded XML Decompiler/Recompiler for ANY .xml inside APK!
    xmlEditorTarget?.let { (path, bytes) ->
        DragonProXmlEditorDialog(
            context = context,
            title = path,
            initialBytes = bytes,
            saveButtonLabel = "Stage",
            allowBackupToggle = false,
            onDismiss = { xmlEditorTarget = null },
            onSaveCompiledBytes = { compiled, _ ->
                if (path == "AndroidManifest.xml") {
                    onUpdateEdits(edits.copy(rawManifestAxmlOverride = compiled))
                } else {
                    val next = edits.replacedOrAddedFiles.toMutableMap()
                    next[path] = compiled
                    onUpdateEdits(edits.copy(replacedOrAddedFiles = next))
                }
                xmlEditorTarget = null
                ApkPackageInstaller.showToastOnMain(context, "Staged $path (${formatBytes(compiled.size.toLong())})")
            }
        )
    }

    // Embedded DEX Decompiler/Editor for ANY .dex inside APK!
    dexEditorTarget?.let { (path, bytes) ->
        DragonDexEditorDialog(
            context = context,
            title = path,
            initialDexBytes = bytes,
            saveButtonLabel = "Stage DEX",
            allowBackupToggle = false,
            onDismiss = { dexEditorTarget = null },
            onSaveDexBytes = { patched, _ ->
                val next = edits.dexByteOverrides.toMutableMap()
                next[path] = patched
                onUpdateEdits(edits.copy(dexByteOverrides = next))
                dexEditorTarget = null
                ApkPackageInstaller.showToastOnMain(context, "Staged patched $path")
            }
        )
    }

    // Embedded .SAV Decompiler/Editor for ANY .sav inside APK!
    savEditorTarget?.let { (path, bytes) ->
        DragonProSavEditorDialog(
            context = context,
            title = path,
            initialBytes = bytes,
            saveButtonLabel = "Stage SAV",
            allowBackupToggle = false,
            onDismiss = { savEditorTarget = null },
            onSaveBytes = { outBytes, _ ->
                val next = edits.replacedOrAddedFiles.toMutableMap()
                next[path] = outBytes
                onUpdateEdits(edits.copy(replacedOrAddedFiles = next))
                savEditorTarget = null
                ApkPackageInstaller.showToastOnMain(context, "Staged $path")
            }
        )
    }

    // Embedded Image Viewer & Replacer
    imageEditorTarget?.let { (path, bytes) ->
        DragonApkImageViewerEditorDialog(
            context = context,
            entryPath = path,
            initialBytes = bytes,
            onDismiss = { imageEditorTarget = null },
            onStageReplacementBytes = { newImgBytes ->
                val next = edits.replacedOrAddedFiles.toMutableMap()
                next[path] = newImgBytes
                onUpdateEdits(edits.copy(replacedOrAddedFiles = next))
                ApkPackageInstaller.showToastOnMain(context, "Staged updated image: $path")
            },
            onExportToStorage = { imgBytes ->
                scope.launch {
                    val outDir = DragonApkStudioEngine.ensureExtractedApkFolder(ddmRootDir)
                    val outFile = File(outDir, path.substringAfterLast('/'))
                    val ok = withContext(Dispatchers.IO) {
                        DragonDocumentEngine.writeBinaryBytes(context, outFile, imgBytes, createBackup = false)
                    }
                    ApkPackageInstaller.showToastOnMain(context, if (ok) "Exported ${outFile.name}" else "Export failed")
                }
            }
        )
    }

    // Embedded Universal Hex & Binary Strings Editor
    hexEditorTarget?.let { (path, bytes) ->
        DragonUniversalHexAndBinaryEditorDialog(
            title = path,
            initialBytes = bytes,
            saveButtonLabel = "Stage Bytes",
            onDismiss = { hexEditorTarget = null },
            onSaveBytes = { patched ->
                val next = edits.replacedOrAddedFiles.toMutableMap()
                next[path] = patched
                onUpdateEdits(edits.copy(replacedOrAddedFiles = next))
                hexEditorTarget = null
                ApkPackageInstaller.showToastOnMain(context, "Staged binary edit: $path")
            }
        )
    }

    // Embedded Text/Code/JSON Editor with Find & Replace + JSON Format
    codeEditorTarget?.let { (path, initialText) ->
        var textVal by remember(path) { mutableStateOf(initialText) }
        var findQ by remember { mutableStateOf("") }
        var replaceQ by remember { mutableStateOf("") }
        var showFind by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { codeEditorTarget = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(path.substringAfterLast('/'), fontSize = 13.5.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    IconButton(onClick = { showFind = !showFind }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.FindReplace, contentDescription = "Find/Replace", tint = NeonCyan, modifier = Modifier.size(16.dp))
                    }
                    if (path.endsWith(".json", true)) {
                        IconButton(
                            onClick = { DragonDocumentEngine.formatJsonPretty(textVal).first?.let { textVal = it } },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.AutoFixHigh, contentDescription = "Format JSON", tint = SuccessGreen, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (showFind) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(value = findQ, onValueChange = { findQ = it }, placeholder = { Text("Find", fontSize = 10.sp) }, singleLine = true, modifier = Modifier.weight(1f))
                            OutlinedTextField(value = replaceQ, onValueChange = { replaceQ = it }, placeholder = { Text("Replace", fontSize = 10.sp) }, singleLine = true, modifier = Modifier.weight(1f))
                            TextButton(enabled = findQ.isNotEmpty(), onClick = { textVal = textVal.replace(findQ, replaceQ) }) { Text("All", fontSize = 10.sp) }
                        }
                    }
                    OutlinedTextField(
                        value = textVal,
                        onValueChange = { textVal = it },
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                        minLines = 10,
                        maxLines = 18,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val next = edits.replacedOrAddedFiles.toMutableMap()
                        next[path] = textVal.toByteArray(Charsets.UTF_8)
                        onUpdateEdits(edits.copy(replacedOrAddedFiles = next))
                        codeEditorTarget = null
                    }
                ) { Text("Stage File Edit") }
            },
            dismissButton = { TextButton(onClick = { codeEditorTarget = null }) { Text("Cancel") } }
        )
    }

    if (showAddNewFileDialog) {
        val defaultPrefix = if (currentVirtualFolder.isEmpty()) "assets/" else "$currentVirtualFolder/"
        var newEntryPath by remember { mutableStateOf("${defaultPrefix}custom_config.txt") }
        AlertDialog(
            onDismissRequest = { showAddNewFileDialog = false },
            title = { Text("Add File into APK", fontSize = 14.5.sp, fontWeight = FontWeight.Black) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newEntryPath,
                        onValueChange = { newEntryPath = it },
                        label = { Text("Internal APK path", fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Import any file (assets, .so, .dex, .xml, .sav, images) from storage or create a new text/XML file.", fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = {
                            val cleanPath = newEntryPath.trim().trimStart('/')
                            if (cleanPath.isNotEmpty()) {
                                pendingReplaceTargetPath = cleanPath
                                showAddNewFileDialog = false
                                filePickerLauncher.launch(arrayOf("*/*"))
                            }
                        }
                    ) { Text("Pick File") }
                    Button(
                        onClick = {
                            val cleanPath = newEntryPath.trim().trimStart('/')
                            if (cleanPath.isNotEmpty()) {
                                val next = edits.replacedOrAddedFiles.toMutableMap()
                                next[cleanPath] = ByteArray(0)
                                onUpdateEdits(edits.copy(replacedOrAddedFiles = next))
                                showAddNewFileDialog = false
                                codeEditorTarget = cleanPath to ""
                            }
                        }
                    ) { Text("Create Text") }
                }
            },
            dismissButton = { TextButton(onClick = { showAddNewFileDialog = false }) { Text("Cancel") } }
        )
    }
}

// =============================================================================
// TAB 5: PRO MULTI-DEX DECOMPILER, SMALI/JAVA & BYTECODE EDITOR
// =============================================================================

@Composable
internal fun ApkDexInspectorPane(
    context: Context,
    project: ApkEditorProject,
    edits: ApkStagedEdits,
    onUpdateEdits: (ApkStagedEdits) -> Unit
) {
    val scope = rememberCoroutineScope()
    var selectedDexIdx by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    var patchingEntry by remember { mutableStateOf<Pair<String, DexStringEntry>?>(null) }
    var fullDexEditorTarget by remember { mutableStateOf<Pair<String, ByteArray>?>(null) }

    val importDexLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val bytes = withContext(Dispatchers.IO) {
                    runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                }
                if (bytes != null && DragonDexDecompilerEngine.isValidDex(bytes)) {
                    val nextNum = project.dexSummaries.size + edits.dexByteOverrides.keys.count { it !in project.dexSummaries.map { d -> d.entryPath } } + 1
                    val newName = if (nextNum <= 1) "classes.dex" else "classes$nextNum.dex"
                    val next = edits.dexByteOverrides.toMutableMap()
                    next[newName] = bytes
                    onUpdateEdits(edits.copy(dexByteOverrides = next))
                    ApkPackageInstaller.showToastOnMain(context, "Imported $newName (${formatBytes(bytes.size.toLong())})")
                } else {
                    ApkPackageInstaller.showToastOnMain(context, "Selected file is not a valid .dex file")
                }
            }
        }
    }

    if (project.dexSummaries.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("No classes*.dex files found in this APK.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { importDexLauncher.launch(arrayOf("*/*")) }) {
                    Text("Import classes.dex")
                }
            }
        }
        return
    }

    val activeDex = project.dexSummaries[selectedDexIdx.coerceIn(0, project.dexSummaries.lastIndex)]
    val isFullOverridden = edits.dexByteOverrides.containsKey(activeDex.entryPath)
    val activePatches = edits.dexStringPatches[activeDex.entryPath].orEmpty()
    val q = searchQuery.trim().lowercase(Locale.US)
    val visibleStrings = remember(activeDex, q, activePatches) {
        activeDex.sampleStrings
            .filter { s ->
                val eff = activePatches[s.dataOffset]?.second ?: s.value
                q.isEmpty() || eff.lowercase(Locale.US).contains(q) || s.value.lowercase(Locale.US).contains(q)
            }
            .take(300)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(end = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(project.dexSummaries.indices.toList()) { idx ->
                    val d = project.dexSummaries[idx]
                    val edited = edits.dexByteOverrides.containsKey(d.entryPath) || edits.dexStringPatches.containsKey(d.entryPath)
                    FilterChip(
                        selected = idx == selectedDexIdx,
                        onClick = { selectedDexIdx = idx },
                        label = {
                            Text(
                                text = "${d.entryPath}${if (edited) " *" else ""}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    )
                }
            }
            FilledTonalIconButton(onClick = { importDexLauncher.launch(arrayOf("*/*")) }, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Default.Add, contentDescription = "Import DEX", tint = SuccessGreen, modifier = Modifier.size(18.dp))
            }
        }

        Card(
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, if (isFullOverridden) SuccessGreen.copy(alpha = 0.6f) else NeonCyan.copy(alpha = 0.35f)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
        ) {
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    ApkDexStatChip("Classes", activeDex.classCount.toString())
                    ApkDexStatChip("Methods", activeDex.methodCount.toString())
                    ApkDexStatChip("Fields", activeDex.fieldCount.toString())
                    ApkDexStatChip("Strings", activeDex.stringCount.toString())
                }
                Button(
                    onClick = {
                        scope.launch {
                            val dexBytes = edits.dexByteOverrides[activeDex.entryPath] ?: withContext(Dispatchers.IO) {
                                DragonApkStudioEngine.readApkEntryBytes(project.sourceApkFile, activeDex.entryPath)
                            }
                            if (dexBytes != null) {
                                fullDexEditorTarget = activeDex.entryPath to dexBytes
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp)
                        .testTag("apk_open_full_dex_editor_btn")
                ) {
                    Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (isFullOverridden) "Open Full DEX Editor (Classes / Smali / Bytecode) • EDITED"
                        else "Decompile & Edit ${activeDex.entryPath} (Classes / Smali / Bytecode)",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Quick-search strings in ${activeDex.entryPath}...", fontSize = 11.5.sp) },
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
            items(visibleStrings, key = { it.index }) { str ->
                val patched = activePatches[str.dataOffset]?.second
                Card(
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(
                        1.dp,
                        if (patched != null) SuccessGreen.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { patchingEntry = activeDex.entryPath to str }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(patched ?: str.value, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                text = "Offset 0x${Integer.toHexString(str.dataOffset)} • ${str.byteLength} bytes" + if (patched != null) " • PATCHED" else "",
                                fontSize = 9.sp,
                                color = if (patched != null) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(Icons.Default.Edit, contentDescription = "Patch DEX String", tint = NeonCyan, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }

    // Full DEX Decompiler & Editor Modal
    fullDexEditorTarget?.let { (dexPath, bytes) ->
        DragonDexEditorDialog(
            context = context,
            title = dexPath,
            initialDexBytes = bytes,
            saveButtonLabel = "Stage DEX",
            allowBackupToggle = false,
            onDismiss = { fullDexEditorTarget = null },
            onSaveDexBytes = { patched, _ ->
                val nextOverrides = edits.dexByteOverrides.toMutableMap()
                nextOverrides[dexPath] = patched
                onUpdateEdits(edits.copy(dexByteOverrides = nextOverrides))
                fullDexEditorTarget = null
                ApkPackageInstaller.showToastOnMain(context, "Staged full DEX edits for $dexPath")
            }
        )
    }

    // Quick Arbitrary-Length String Patcher Dialog (upgrades even quick taps to smart arbitrary-length patching!)
    patchingEntry?.let { (dexPath, entry) ->
        var textVal by remember(entry.dataOffset) {
            mutableStateOf(edits.dexStringPatches[dexPath]?.get(entry.dataOffset)?.second ?: entry.value)
        }
        val currentBytes = textVal.toByteArray(Charsets.UTF_8).size
        AlertDialog(
            onDismissRequest = { patchingEntry = null },
            title = { Text("Patch DEX String #${entry.index}", fontSize = 14.5.sp, fontWeight = FontWeight.Black) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Original (${entry.byteLength} B): ${entry.value}",
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = textVal,
                        onValueChange = { textVal = it },
                        label = { Text("Replacement ($currentBytes UTF-8 bytes)", fontSize = 11.sp) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = if (currentBytes > entry.byteLength) {
                            "Arbitrary-length mode: will safely append new MUTF-8 entry & recompute DEX SHA-1 + Adler32."
                        } else {
                            "Fast in-place patch with automatic DEX SHA-1 & Adler32 recalculation."
                        },
                        fontSize = 10.sp,
                        color = SuccessGreen
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (currentBytes > entry.byteLength) {
                            scope.launch {
                                val baseBytes = edits.dexByteOverrides[dexPath] ?: withContext(Dispatchers.IO) {
                                    DragonApkStudioEngine.readApkEntryBytes(project.sourceApkFile, dexPath)
                                }
                                if (baseBytes != null) {
                                    val patched = DragonDexDecompilerEngine.patchDexStringsSmart(baseBytes, mapOf(entry.index to textVal))
                                    val nextOverrides = edits.dexByteOverrides.toMutableMap()
                                    nextOverrides[dexPath] = patched
                                    onUpdateEdits(edits.copy(dexByteOverrides = nextOverrides))
                                    ApkPackageInstaller.showToastOnMain(context, "Staged arbitrary-length DEX string patch")
                                }
                                patchingEntry = null
                            }
                        } else {
                            val allDex = edits.dexStringPatches.toMutableMap()
                            val dexMap = allDex[dexPath]?.toMutableMap() ?: mutableMapOf()
                            if (textVal == entry.value) dexMap.remove(entry.dataOffset)
                            else dexMap[entry.dataOffset] = entry.byteLength to textVal
                            if (dexMap.isEmpty()) allDex.remove(dexPath) else allDex[dexPath] = dexMap
                            onUpdateEdits(edits.copy(dexStringPatches = allDex))
                            patchingEntry = null
                        }
                    }
                ) { Text("Stage Patch") }
            },
            dismissButton = { TextButton(onClick = { patchingEntry = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun ApkDexStatChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Black, color = NeonCyan)
        Text(label, fontSize = 9.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
