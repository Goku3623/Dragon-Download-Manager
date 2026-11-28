package com.example.ui.screens

import android.content.Intent
import android.os.Environment
import android.webkit.MimeTypeMap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.data.downloader.ApkPackageInstaller
import com.example.data.filemanager.AndroidDataObbBridge
import com.example.data.filemanager.CompressionJobConfig
import com.example.data.filemanager.DragonFileManagerTaskEngine
import com.example.data.filemanager.UniversalArchiveEngine
import com.example.data.model.formatBytes
import com.example.data.repository.KernelRepository
import com.example.ui.theme.DangerRed
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarningOrange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private sealed class FloatingOverlaySheet {
    data class ItemActions(val item: FileItemEntry) : FloatingOverlaySheet()
    data class Rename(val item: FileItemEntry) : FloatingOverlaySheet()
    data object NewFolder : FloatingOverlaySheet()
    data class ConfirmDelete(val items: List<FileItemEntry>) : FloatingOverlaySheet()
    data class QuickTextView(val file: File, val content: String) : FloatingOverlaySheet()
    data class Properties(val item: FileItemEntry) : FloatingOverlaySheet()
}

/**
 * Self-contained Floating & Manually Resizable Dragon Archiver Window.
 *
 * Designed specifically for Android system overlay hosting (`TYPE_APPLICATION_OVERLAY`):
 * renders all action sheets, confirmations, rename/new-folder prompts, and quick file previews
 * strictly inside the floating window bounds (never invoking `android.app.Dialog`) so it runs
 * simultaneously over Dragon Download Manager and any third-party app without ever crashing.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FloatingDragonArchiverWindow(
    repository: KernelRepository,
    initialDirectoryPath: String?,
    isMinimized: Boolean,
    windowWidthDp: Int,
    windowHeightDp: Int,
    onDragWindow: (dxPx: Float, dyPx: Float) -> Unit,
    onResizeWindow: (dwPx: Float, dhPx: Float, anchorLeft: Boolean) -> Unit,
    onApplySizePreset: (presetIndex: Int) -> Unit,
    onToggleMinimize: () -> Unit,
    onInputFocusNeeded: (Boolean) -> Unit,
    onExpandToFullScreen: (activePath: String) -> Unit,
    onCloseFloating: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val internalRoot = remember { AndroidDataObbBridge.getExternalStorageRoot() }
    val romRoot = remember { AndroidDataObbBridge.getRomRootDir() }
    val ddmRoot = remember {
        val ext = Environment.getExternalStorageDirectory()
        val pubDdm = File(ext, "DDM")
        if (pubDdm.exists() || pubDdm.mkdirs()) pubDdm
        else File(context.getExternalFilesDir(null) ?: context.filesDir, "DDM").apply { mkdirs() }
    }
    val downloadsDir = remember {
        runCatching { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) }
            .getOrNull() ?: File(internalRoot, "Download")
    }
    val androidDataDir = remember { AndroidDataObbBridge.getAndroidDataDir() }
    val androidObbDir = remember { AndroidDataObbBridge.getAndroidObbDir() }

    var currentDir by remember {
        val start = initialDirectoryPath?.let { File(it) }?.takeIf { it.exists() && it.isDirectory }
            ?: DragonFileManagerTaskEngine.savedCurrentDir?.takeIf { it.exists() }
            ?: internalRoot
        mutableStateOf(start)
    }

    remember(context) {
        DragonFileManagerTaskEngine.ensurePreferencesLoaded(context)
    }

    var searchVisible by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showHidden by remember { mutableStateOf(DragonFileManagerTaskEngine.savedShowHiddenFiles) }
    var isGridView by remember {
        mutableStateOf(
            DragonFileManagerTaskEngine.savedViewStyle == FileManagerViewStyle.GRID_2 ||
                DragonFileManagerTaskEngine.savedViewStyle == FileManagerViewStyle.GRID_3
        )
    }
    var sortField by remember { mutableStateOf(DragonFileManagerTaskEngine.savedSortField) }
    var sortAscending by remember { mutableStateOf(DragonFileManagerTaskEngine.savedSortAscending) }

    LaunchedEffect(sortField, sortAscending) {
        DragonFileManagerTaskEngine.saveSortPreferences(context, sortField, sortAscending)
    }
    LaunchedEffect(showHidden) {
        DragonFileManagerTaskEngine.saveShowHiddenFiles(context, showHidden)
    }
    var opacityStep by remember { mutableIntStateOf(0) } // 0 = 1.0f, 1 = 0.90f, 2 = 0.78f
    var sizePresetIndex by remember { mutableIntStateOf(1) }
    var refreshTick by remember { mutableIntStateOf(0) }

    var selectedPaths by remember { mutableStateOf(setOf<String>()) }
    var clipboard by remember { mutableStateOf(DragonFileManagerTaskEngine.savedClipboard) }
    var activeSheet by remember { mutableStateOf<FloatingOverlaySheet?>(null) }

    val runningTask by DragonFileManagerTaskEngine.activeTaskFlow.collectAsState()
    val engineRefreshTick by DragonFileManagerTaskEngine.refreshTickFlow.collectAsState()
    val isMainRootGranted by repository.isRootGrantedFlow.collectAsState()

    val windowAlpha = when (opacityStep) {
        1 -> 0.90f
        2 -> 0.78f
        else -> 1.0f
    }

    // Sync shared current directory & clipboard with full-screen Dragon Archiver
    LaunchedEffect(currentDir) {
        DragonFileManagerTaskEngine.savedCurrentDir = currentDir
    }
    LaunchedEffect(clipboard) {
        DragonFileManagerTaskEngine.savedClipboard = clipboard
    }

    // Request IME keyboard focus ONLY when an inline text field is open; otherwise let background apps keep focus!
    val needsKeyboardFocus = !isMinimized && (
        searchVisible ||
            activeSheet is FloatingOverlaySheet.Rename ||
            activeSheet is FloatingOverlaySheet.NewFolder
        )
    LaunchedEffect(needsKeyboardFocus) {
        onInputFocusNeeded(needsKeyboardFocus)
    }

    // Instant cached directory loader + background IO sync
    val cacheKey = remember(currentDir.absolutePath, showHidden, sortField, sortAscending) {
        "float|${currentDir.absolutePath}|$showHidden|${sortField.name}|$sortAscending"
    }
    var entries by remember {
        mutableStateOf(DragonFileManagerTaskEngine.getCachedDirectoryItems(cacheKey).orEmpty())
    }
    var isLoading by remember { mutableStateOf(entries.isEmpty()) }

    LaunchedEffect(currentDir, showHidden, sortField, sortAscending, refreshTick, engineRefreshTick, isMainRootGranted) {
        val cached = DragonFileManagerTaskEngine.getCachedDirectoryItems(cacheKey)
        if (cached != null) {
            entries = cached
            isLoading = false
        } else {
            isLoading = true
        }
        val loaded = withContext(Dispatchers.IO) {
            val raw = AndroidDataObbBridge.listEntriesInDirectory(currentDir)
            val filtered = raw.filter { showHidden || !it.name.startsWith(".") }
            val mapped = filtered.map { e ->
                FileItemEntry(
                    file = e.file,
                    name = e.name,
                    isDirectory = e.isDirectory,
                    lengthBytes = e.lengthBytes,
                    lastModifiedMs = e.lastModifiedMs,
                    extension = if (e.isDirectory) "" else e.name.substringAfterLast('.', "").lowercase(Locale.US)
                )
            }
            val comparator = when (sortField) {
                FileManagerSortField.NAME -> compareBy<FileItemEntry> { !it.isDirectory }
                    .thenBy { it.name.lowercase(Locale.US) }
                FileManagerSortField.DATE -> compareBy<FileItemEntry> { !it.isDirectory }
                    .thenByDescending { it.lastModifiedMs }
                FileManagerSortField.SIZE -> compareBy<FileItemEntry> { !it.isDirectory }
                    .thenByDescending { it.lengthBytes }
                FileManagerSortField.TYPE -> compareBy<FileItemEntry> { !it.isDirectory }
                    .thenBy { it.extension }
                    .thenBy { it.name.lowercase(Locale.US) }
            }
            val sorted = mapped.sortedWith(comparator)
            if (sortAscending) sorted else {
                val dirs = sorted.filter { it.isDirectory }.reversed()
                val files = sorted.filter { !it.isDirectory }.reversed()
                dirs + files
            }
        }
        DragonFileManagerTaskEngine.putCachedDirectoryItems(cacheKey, loaded)
        entries = loaded
        isLoading = false
    }

    val visibleEntries = remember(entries, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) entries else entries.filter { it.name.contains(q, ignoreCase = true) }
    }

    // =========================================================================
    // MINIMIZED FLOATING BUBBLE STATE
    // =========================================================================
    if (isMinimized) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(3.dp)
                .clip(CircleShape)
                .background(Color(0xFF0B132B))
                .border(2.dp, NeonCyan.copy(alpha = 0.9f), CircleShape)
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        onDragWindow(dragAmount.x, dragAmount.y)
                    }
                }
                .clickable(onClick = onToggleMinimize)
                .testTag("floating_archiver_bubble"),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = remember(context) {
                    ImageRequest.Builder(context)
                        .data(R.drawable.ic_dragon_archiver_v2_clean)
                        .size(144, 144)
                        .crossfade(false)
                        .build()
                },
                contentDescription = "Restore Floating Dragon Archiver",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(3.dp)
                    .clip(CircleShape)
            )
        }
        return
    }

    // =========================================================================
    // EXPANDED RESIZABLE FLOATING WINDOW STATE
    // =========================================================================
    Box(
        modifier = Modifier
            .fillMaxSize()
            .alpha(windowAlpha)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.5.dp, NeonCyan.copy(alpha = 0.65f), RoundedCornerShape(18.dp))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 1. DRAGGABLE WINDOW TITLE BAR
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            onDragWindow(dragAmount.x, dragAmount.y)
                        }
                    }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = remember(context) {
                        ImageRequest.Builder(context)
                            .data(R.drawable.ic_dragon_archiver_v2_clean)
                            .size(96, 96)
                            .crossfade(false)
                            .build()
                    },
                    contentDescription = "Dragon Archiver Logo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .border(0.8.dp, NeonCyan.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                )
                Spacer(modifier = Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "DRAGON ARCHIVER",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.5.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = currentDir.absolutePath,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Opacity toggle
                IconButton(
                    onClick = { opacityStep = (opacityStep + 1) % 3 },
                    modifier = Modifier
                        .size(28.dp)
                        .testTag("floating_archiver_opacity_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Opacity,
                        contentDescription = "Window Opacity",
                        tint = if (opacityStep > 0) NeonCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
                // Minimize to floating bubble
                IconButton(
                    onClick = onToggleMinimize,
                    modifier = Modifier
                        .size(28.dp)
                        .testTag("floating_archiver_minimize_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Remove,
                        contentDescription = "Minimize to Bubble",
                        tint = WarningOrange,
                        modifier = Modifier.size(17.dp)
                    )
                }
                // Expand to Full Screen Dragon Archiver
                IconButton(
                    onClick = { onExpandToFullScreen(currentDir.absolutePath) },
                    modifier = Modifier
                        .size(28.dp)
                        .testTag("floating_archiver_expand_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.OpenInFull,
                        contentDescription = "Expand to Full Screen",
                        tint = SuccessGreen,
                        modifier = Modifier.size(16.dp)
                    )
                }
                // Close Floating Window
                IconButton(
                    onClick = onCloseFloating,
                    modifier = Modifier
                        .size(28.dp)
                        .testTag("floating_archiver_close_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Floating Window",
                        tint = DangerRed,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))

            // 2. INSTANT STORAGE CHIPS
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val quickRoots = listOf(
                    Triple("Internal", internalRoot, Icons.Default.Smartphone),
                    Triple("Root Folder", romRoot, Icons.Default.AdminPanelSettings),
                    Triple("DDM", ddmRoot, Icons.Default.CloudDownload),
                    Triple("Downloads", downloadsDir, Icons.Default.Download),
                    Triple("Android/data", androidDataDir, Icons.Default.Shield),
                    Triple("Android/obb", androidObbDir, Icons.Default.SportsEsports)
                )
                quickRoots.forEach { (label, dir, icon) ->
                    val active = currentDir.absolutePath == dir.absolutePath
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (active) NeonCyan.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(
                            0.8.dp,
                            if (active) NeonCyan else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                selectedPaths = emptySet()
                                currentDir = dir
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = label,
                                tint = if (active) NeonCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = label,
                                fontSize = 10.sp,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                color = if (active) NeonCyan else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            // 3. COMPACT ACTION TOOLBAR
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    IconButton(
                        onClick = {
                            val parent = currentDir.parentFile
                            if (parent != null) {
                                selectedPaths = emptySet()
                                currentDir = parent
                            }
                        },
                        enabled = currentDir.parentFile != null,
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Up Folder",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    IconButton(
                        onClick = {
                            searchVisible = !searchVisible
                            if (!searchVisible) searchQuery = ""
                        },
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(
                            imageVector = if (searchVisible) Icons.Default.SearchOff else Icons.Default.Search,
                            contentDescription = "Search",
                            tint = if (searchVisible) NeonCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    IconButton(
                        onClick = { isGridView = !isGridView },
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(
                            imageVector = if (isGridView) Icons.Default.ViewList else Icons.Default.GridView,
                            contentDescription = "Toggle Grid View",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    IconButton(
                        onClick = {
                            val fields = FileManagerSortField.entries
                            sortField = fields[(fields.indexOf(sortField) + 1) % fields.size]
                        },
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Sort,
                            contentDescription = "Cycle Sort",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    IconButton(
                        onClick = { showHidden = !showHidden },
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(
                            imageVector = if (showHidden) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = "Toggle Hidden Files",
                            tint = if (showHidden) NeonCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val activeClip = clipboard
                    if (activeClip != null && (activeClip.files.isNotEmpty() || activeClip.archiveEntryPaths.isNotEmpty())) {
                        Surface(
                            shape = RoundedCornerShape(7.dp),
                            color = SuccessGreen.copy(alpha = 0.2f),
                            border = BorderStroke(0.8.dp, SuccessGreen),
                            modifier = Modifier
                                .clip(RoundedCornerShape(7.dp))
                                .clickable {
                                    DragonFileManagerTaskEngine.launchClipboardPasteTask(
                                        context = context,
                                        clip = activeClip,
                                        targetDir = currentDir
                                    ) {
                                        if (activeClip.operation == ClipboardTransferOperation.CUT) {
                                            clipboard = null
                                        }
                                        refreshTick++
                                    }
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentPaste,
                                    contentDescription = "Paste",
                                    tint = SuccessGreen,
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    text = "Paste",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = SuccessGreen
                                )
                            }
                        }
                    }

                    IconButton(
                        onClick = { activeSheet = FloatingOverlaySheet.NewFolder },
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CreateNewFolder,
                            contentDescription = "New Folder",
                            tint = WarningOrange,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    IconButton(
                        onClick = {
                            DragonFileManagerTaskEngine.invalidateDirectorySnapshotCache(currentDir.absolutePath)
                            refreshTick++
                        },
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // Optional inline Search Bar
            AnimatedVisibility(visible = searchVisible) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Filter in ${currentDir.name}...", fontSize = 11.sp) },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontSize = 12.sp),
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(15.dp))
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }

            // Live Running File Task Bar (with Pause / Resume / Cancel)
            runningTask?.let { task ->
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${task.title} (${task.progressPercent}%)",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                IconButton(
                                    onClick = {
                                        if (task.isPaused) DragonFileManagerTaskEngine.resumeActiveTask(context)
                                        else DragonFileManagerTaskEngine.pauseActiveTask(context)
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = if (task.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                        contentDescription = if (task.isPaused) "Resume Task" else "Pause Task",
                                        tint = NeonCyan,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                                IconButton(
                                    onClick = { DragonFileManagerTaskEngine.cancelActiveTask(context) },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Cancel,
                                        contentDescription = "Cancel Task",
                                        tint = DangerRed,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                        if (task.isIndeterminate) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.5.dp))
                        } else {
                            LinearProgressIndicator(
                                progress = { (task.progressPercent / 100f).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(2.5.dp)
                            )
                        }
                    }
                }
            }

            // Multi-select compact action bar
            AnimatedVisibility(visible = selectedPaths.isNotEmpty()) {
                val selectedEntries = remember(visibleEntries, selectedPaths) {
                    visibleEntries.filter { it.selectionKey in selectedPaths }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(NeonCyan.copy(alpha = 0.14f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${selectedPaths.size} selected",
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = NeonCyan
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        IconButton(
                            onClick = {
                                clipboard = FileManagerClipboardState(
                                    files = selectedEntries.map { it.file },
                                    operation = ClipboardTransferOperation.COPY
                                )
                                selectedPaths = emptySet()
                                ApkPackageInstaller.showToastOnMain(context, "Copied ${selectedEntries.size} item(s)")
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = NeonCyan, modifier = Modifier.size(15.dp))
                        }
                        IconButton(
                            onClick = {
                                clipboard = FileManagerClipboardState(
                                    files = selectedEntries.map { it.file },
                                    operation = ClipboardTransferOperation.CUT
                                )
                                selectedPaths = emptySet()
                                ApkPackageInstaller.showToastOnMain(context, "Cut ${selectedEntries.size} item(s)")
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.ContentCut, contentDescription = "Cut", tint = WarningOrange, modifier = Modifier.size(15.dp))
                        }
                        IconButton(
                            onClick = {
                                val baseName = "Archive_${System.currentTimeMillis() % 10000}"
                                val outZip = File(currentDir, "$baseName.zip")
                                DragonFileManagerTaskEngine.launchUniversalCompressTask(
                                    context = context,
                                    sources = selectedEntries.map { it.file },
                                    outputArchive = outZip,
                                    config = CompressionJobConfig(archiveBaseName = baseName)
                                )
                                selectedPaths = emptySet()
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.FolderZip, contentDescription = "Compress ZIP", tint = SuccessGreen, modifier = Modifier.size(15.dp))
                        }
                        IconButton(
                            onClick = { activeSheet = FloatingOverlaySheet.ConfirmDelete(selectedEntries) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = DangerRed, modifier = Modifier.size(15.dp))
                        }
                        IconButton(
                            onClick = { selectedPaths = emptySet() },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Clear Selection", modifier = Modifier.size(15.dp))
                        }
                    }
                }
            }

            // 4. DIRECTORY LIST / GRID BODY
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                val handleEntryClick: (FileItemEntry) -> Unit = { entry ->
                    if (selectedPaths.isNotEmpty()) {
                        selectedPaths = if (entry.selectionKey in selectedPaths) {
                            selectedPaths - entry.selectionKey
                        } else {
                            selectedPaths + entry.selectionKey
                        }
                    } else if (entry.isDirectory) {
                        currentDir = entry.file
                    } else {
                        val ext = entry.extension
                        val textExts = setOf("txt", "log", "json", "xml", "md", "ini", "cfg", "conf", "prop", "sh", "kt", "java", "py", "html", "css", "js", "csv", "yaml", "yml", "sav")
                        if (ext in textExts && entry.lengthBytes <= 512 * 1024L) {
                            scope.launch {
                                val text = withContext(Dispatchers.IO) {
                                    runCatching { entry.file.readText() }.getOrDefault("Unable to read file.")
                                }
                                activeSheet = FloatingOverlaySheet.QuickTextView(entry.file, text)
                            }
                        } else {
                            activeSheet = FloatingOverlaySheet.ItemActions(entry)
                        }
                    }
                }

                val handleEntryLongClick: (FileItemEntry) -> Unit = { entry ->
                    selectedPaths = if (entry.selectionKey in selectedPaths) {
                        selectedPaths - entry.selectionKey
                    } else {
                        selectedPaths + entry.selectionKey
                    }
                }

                when {
                    isLoading -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp, color = NeonCyan)
                        }
                    }
                    visibleEntries.isEmpty() -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.FolderOff,
                                    contentDescription = "Empty Folder",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.size(32.dp)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Empty directory",
                                    fontSize = 11.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    isGridView -> {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 84.dp),
                            contentPadding = PaddingValues(6.dp),
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(visibleEntries, key = { it.selectionKey }) { item ->
                                val isSelected = item.selectionKey in selectedPaths
                                FloatingGridTile(
                                    item = item,
                                    isSelected = isSelected,
                                    onClick = { handleEntryClick(item) },
                                    onLongClick = { handleEntryLongClick(item) }
                                )
                            }
                        }
                    }
                    else -> {
                        LazyColumn(
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(visibleEntries, key = { it.selectionKey }) { item ->
                                val isSelected = item.selectionKey in selectedPaths
                                FloatingFileRow(
                                    item = item,
                                    isSelected = isSelected,
                                    onClick = { handleEntryClick(item) },
                                    onLongClick = { handleEntryLongClick(item) },
                                    onOpenActions = { activeSheet = FloatingOverlaySheet.ItemActions(item) }
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))

            // 5. BOTTOM STATUS + MANUAL RESIZE BAR (Bottom-Left Corner + Bottom Edge + Bottom-Right Corner)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(30.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Bottom-Left manual resize corner
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                onResizeWindow(dragAmount.x, dragAmount.y, true)
                            }
                        }
                        .testTag("floating_archiver_resize_bl"),
                    contentAlignment = Alignment.Center
                ) {
                    CornerResizeGrip(isLeft = true, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f))
                }

                // Bottom edge height resize + size preset chip + item count
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                onResizeWindow(0f, dragAmount.y, false)
                            }
                        }
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${visibleEntries.size} items • ${sortField.label}",
                        fontSize = 9.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = NeonCyan.copy(alpha = 0.14f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                sizePresetIndex = (sizePresetIndex + 1) % 3
                                onApplySizePreset(sizePresetIndex)
                            }
                            .testTag("floating_archiver_size_preset")
                    ) {
                        Text(
                            text = "${windowWidthDp}×${windowHeightDp}dp",
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = NeonCyan,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                // Bottom-Right primary 2D manual resize handle
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                onResizeWindow(dragAmount.x, dragAmount.y, false)
                            }
                        }
                        .testTag("floating_archiver_resize_br"),
                    contentAlignment = Alignment.Center
                ) {
                    CornerResizeGrip(isLeft = false, color = NeonCyan)
                }
            }
        }

        // Invisible Right-Edge horizontal resize strip (desktop-style edge dragging)
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(9.dp)
                .fillMaxHeight(0.74f)
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        onResizeWindow(dragAmount.x, 0f, false)
                    }
                }
        )

        // =====================================================================
        // IN-WINDOW COMPOSE SHEETS (Zero android.app.Dialog -> Zero BadTokenException!)
        // =====================================================================
        val sheet = activeSheet
        if (sheet != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.56f))
                    .clickable { activeSheet = null },
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .heightIn(max = 360.dp)
                        .clickable(enabled = false) {}
                ) {
                    when (sheet) {
                        is FloatingOverlaySheet.ItemActions -> {
                            val item = sheet.item
                            Column(
                                modifier = Modifier
                                    .padding(10.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = item.name,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (item.isDirectory) "Folder" else formatBytes(item.lengthBytes),
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

                                if (!item.isDirectory) {
                                    FloatingSheetRow(Icons.Default.OpenInNew, NeonCyan, "Open with System App") {
                                        activeSheet = null
                                        openFileExternallyFromOverlay(context, item.file)
                                    }
                                    if (UniversalArchiveEngine.isSupportedArchiveFile(item.file)) {
                                        FloatingSheetRow(Icons.Default.Unarchive, WarningOrange, "Extract Here") {
                                            activeSheet = null
                                            DragonFileManagerTaskEngine.launchExtractArchiveTask(
                                                context = context,
                                                archiveFile = item.file,
                                                targetDir = File(currentDir, item.file.nameWithoutExtension)
                                            )
                                        }
                                    }
                                }
                                FloatingSheetRow(Icons.Default.ContentCopy, NeonCyan, "Copy") {
                                    clipboard = FileManagerClipboardState(listOf(item.file), ClipboardTransferOperation.COPY)
                                    activeSheet = null
                                    ApkPackageInstaller.showToastOnMain(context, "Copied ${item.name}")
                                }
                                FloatingSheetRow(Icons.Default.ContentCut, WarningOrange, "Cut / Move") {
                                    clipboard = FileManagerClipboardState(listOf(item.file), ClipboardTransferOperation.CUT)
                                    activeSheet = null
                                    ApkPackageInstaller.showToastOnMain(context, "Cut ${item.name}")
                                }
                                FloatingSheetRow(Icons.Default.DriveFileRenameOutline, SuccessGreen, "Rename") {
                                    activeSheet = FloatingOverlaySheet.Rename(item)
                                }
                                FloatingSheetRow(Icons.Default.FolderZip, WarningOrange, "Compress to ZIP") {
                                    activeSheet = null
                                    val baseName = item.file.nameWithoutExtension.ifBlank { item.file.name }
                                    val outZip = File(currentDir, "$baseName.zip")
                                    DragonFileManagerTaskEngine.launchUniversalCompressTask(
                                        context = context,
                                        sources = listOf(item.file),
                                        outputArchive = outZip,
                                        config = CompressionJobConfig(archiveBaseName = baseName)
                                    )
                                }
                                if (!item.isDirectory) {
                                    FloatingSheetRow(Icons.Default.Share, NeonCyan, "Share File") {
                                        activeSheet = null
                                        shareFileFromOverlay(context, item.file)
                                    }
                                }
                                FloatingSheetRow(Icons.Default.Info, MaterialTheme.colorScheme.primary, "Properties") {
                                    activeSheet = FloatingOverlaySheet.Properties(item)
                                }
                                FloatingSheetRow(Icons.Default.OpenInFull, SuccessGreen, "Open in Full Dragon Archiver") {
                                    activeSheet = null
                                    onExpandToFullScreen(currentDir.absolutePath)
                                }
                                FloatingSheetRow(Icons.Default.Delete, DangerRed, "Delete") {
                                    activeSheet = FloatingOverlaySheet.ConfirmDelete(listOf(item))
                                }
                            }
                        }

                        is FloatingOverlaySheet.Rename -> {
                            var newName by remember(sheet.item) { mutableStateOf(sheet.item.name) }
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("Rename Item", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                OutlinedTextField(
                                    value = newName,
                                    onValueChange = { newName = it },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    TextButton(onClick = { activeSheet = null }) { Text("Cancel") }
                                    Button(
                                        enabled = newName.trim().isNotEmpty(),
                                        onClick = {
                                            val target = sheet.item.file
                                            val clean = newName.trim()
                                            activeSheet = null
                                            scope.launch(Dispatchers.IO) {
                                                val ok = AndroidDataObbBridge.renameFileOrDirectory(context, target, clean)
                                                DragonFileManagerTaskEngine.invalidateDirectorySnapshotCache(currentDir.absolutePath)
                                                if (ok) refreshTick++
                                                ApkPackageInstaller.showToastOnMain(
                                                    context,
                                                    if (ok) "Renamed to $clean" else "Rename failed"
                                                )
                                            }
                                        }
                                    ) { Text("Rename") }
                                }
                            }
                        }

                        is FloatingOverlaySheet.NewFolder -> {
                            var folderName by remember { mutableStateOf("") }
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("Create New Folder", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                OutlinedTextField(
                                    value = folderName,
                                    onValueChange = { folderName = it },
                                    placeholder = { Text("Folder name") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    TextButton(onClick = { activeSheet = null }) { Text("Cancel") }
                                    Button(
                                        enabled = folderName.trim().isNotEmpty(),
                                        onClick = {
                                            val clean = folderName.trim()
                                            activeSheet = null
                                            scope.launch(Dispatchers.IO) {
                                                val ok = AndroidDataObbBridge.createDirectory(context, File(currentDir, clean))
                                                DragonFileManagerTaskEngine.invalidateDirectorySnapshotCache(currentDir.absolutePath)
                                                if (ok) refreshTick++
                                                ApkPackageInstaller.showToastOnMain(
                                                    context,
                                                    if (ok) "Created $clean" else "Failed to create folder"
                                                )
                                            }
                                        }
                                    ) { Text("Create") }
                                }
                            }
                        }

                        is FloatingOverlaySheet.ConfirmDelete -> {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Delete ${sheet.items.size} item(s)?",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = DangerRed
                                )
                                Text(
                                    text = sheet.items.take(3).joinToString(", ") { it.name } +
                                        if (sheet.items.size > 3) " +${sheet.items.size - 3} more" else "",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    TextButton(onClick = { activeSheet = null }) { Text("Cancel") }
                                    Button(
                                        colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                                        onClick = {
                                            val toDelete = sheet.items
                                            activeSheet = null
                                            selectedPaths = emptySet()
                                            DragonFileManagerTaskEngine.launchMoveToTrashTask(
                                                context = context,
                                                itemsToDelete = toDelete,
                                                downloadManager = repository.downloadManager
                                            )
                                        }
                                    ) { Text("Move to Trash") }
                                }
                            }
                        }

                        is FloatingOverlaySheet.QuickTextView -> {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = sheet.file.name,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(onClick = { activeSheet = null }, modifier = Modifier.size(26.dp)) {
                                        Icon(Icons.Default.Close, contentDescription = "Close", modifier = Modifier.size(16.dp))
                                    }
                                }
                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                Box(
                                    modifier = Modifier
                                        .weight(1f, fill = false)
                                        .heightIn(max = 260.dp)
                                        .verticalScroll(rememberScrollState())
                                ) {
                                    Text(
                                        text = sheet.content,
                                        fontSize = 10.5.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }

                        is FloatingOverlaySheet.Properties -> {
                            val item = sheet.item
                            val dateText = remember(item.lastModifiedMs) {
                                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(item.lastModifiedMs))
                            }
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Text("Properties", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = NeonCyan)
                                Text("Name: ${item.name}", fontSize = 11.sp)
                                Text("Path: ${item.file.absolutePath}", fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                Text("Size: ${if (item.isDirectory) "Directory" else formatBytes(item.lengthBytes)}", fontSize = 11.sp)
                                Text("Modified: $dateText", fontSize = 11.sp)
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    TextButton(onClick = { activeSheet = null }) { Text("Close") }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Compact In-Window File Conflict Popup ("Copy and replace", "Keep both", "Skip")
        DragonFileConflictInWindowOverlay(context = context)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FloatingFileRow(
    item: FileItemEntry,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onOpenActions: () -> Unit
) {
    val (icon, tint) = floatingIconForEntry(item)
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (isSelected) NeonCyan.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerLow,
        border = if (isSelected) BorderStroke(1.dp, NeonCyan) else null,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(tint.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(17.dp))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (item.isDirectory) "Folder" else "${formatBytes(item.lengthBytes)} • ${item.extension.uppercase(Locale.US).ifBlank { "FILE" }}",
                    fontSize = 9.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            IconButton(onClick = onOpenActions, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "File Actions",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FloatingGridTile(
    item: FileItemEntry,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val (icon, tint) = floatingIconForEntry(item)
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (isSelected) NeonCyan.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceContainerLow,
        border = if (isSelected) BorderStroke(1.dp, NeonCyan) else null,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Column(
            modifier = Modifier.padding(7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
            Text(
                text = item.name,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun FloatingSheetRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    title: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Text(text = title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun CornerResizeGrip(isLeft: Boolean, color: Color) {
    Canvas(modifier = Modifier.size(16.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 2.dp.toPx()
        if (isLeft) {
            drawLine(color, Offset(2f, h * 0.35f), Offset(w * 0.65f, h - 2f), stroke, StrokeCap.Round)
            drawLine(color, Offset(2f, h * 0.7f), Offset(w * 0.3f, h - 2f), stroke, StrokeCap.Round)
        } else {
            drawLine(color, Offset(w * 0.35f, h - 2f), Offset(w - 2f, h * 0.35f), stroke, StrokeCap.Round)
            drawLine(color, Offset(w * 0.7f, h - 2f), Offset(w - 2f, h * 0.7f), stroke, StrokeCap.Round)
        }
    }
}

private fun floatingIconForEntry(item: FileItemEntry): Pair<androidx.compose.ui.graphics.vector.ImageVector, Color> {
    if (item.isDirectory) return Icons.Default.Folder to WarningOrange
    return when (item.extension) {
        "apk", "apks", "xapk", "apkm" -> Icons.Default.Android to SuccessGreen
        "zip", "7z", "rar", "tar", "gz", "xz", "bin" -> Icons.Default.FolderZip to WarningOrange
        "mp4", "mkv", "webm", "avi", "mov" -> Icons.Default.VideoFile to Color(0xFF38BDF8)
        "mp3", "flac", "wav", "m4a", "ogg" -> Icons.Default.AudioFile to Color(0xFFA855F7)
        "jpg", "jpeg", "png", "webp", "gif" -> Icons.Default.Image to Color(0xFFF472B6)
        "pdf", "txt", "xml", "json", "docx", "sav", "md" -> Icons.Default.Description to Color(0xFF60A5FA)
        else -> Icons.AutoMirrored.Filled.InsertDriveFile to NeonCyan
    }
}

private fun openFileExternallyFromOverlay(context: android.content.Context, file: File) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val mime = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase(Locale.US))
            ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }.onFailure {
        ApkPackageInstaller.showToastOnMain(context, "No app available to open ${file.name}")
    }
}

private fun shareFileFromOverlay(context: android.content.Context, file: File) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "Share via Dragon Archiver").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
