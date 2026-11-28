package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
import java.io.File
import java.util.Locale

private enum class ApkPickerTab(val label: String) {
    INSTALLED("Installed Apps"),
    DOWNLOADED("DDM & Downloads"),
    STORAGE("Browse Storage")
}

private enum class ApkEditorWorkspaceTab(val label: String) {
    COMMON("Common Edit"),
    MANIFEST("Manifest"),
    STRINGS("Resources"),
    FILES("Files"),
    DEX("DEX"),
    BUILD("Build & Sign")
}

private enum class ManifestSubMode(val label: String) {
    PERMISSIONS("Permissions"),
    COMPONENTS("Components"),
    XML("Decompiled XML")
}

/**
 * Full-Screen Professional APK Editor Studio for Dragon Pro File Manager.
 *
 * Can inspect, modify, repack, zipalign, and V1+V2 sign any installed, downloaded,
 * or stored `.apk` file with full binary AXML editing, `resources.arsc` string patching,
 * `classes*.dex` inspection & safe string patching, launcher icon replacement, ABI stripping,
 * and internal file/asset editing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DragonApkEditorStudioDialog(
    context: Context,
    ddmRootDir: File,
    initialApkFile: File? = null,
    onDismiss: () -> Unit,
    onInstallBuiltApk: (File) -> Unit,
    onOpenOutputFolder: (File) -> Unit,
    onShareBuiltApk: (File) -> Unit
) {
    val scope = rememberCoroutineScope()
    var activeApkFile by remember { mutableStateOf(initialApkFile) }
    var project by remember { mutableStateOf<ApkEditorProject?>(null) }
    var isLoadingProject by remember { mutableStateOf(initialApkFile != null) }
    var loadError by remember { mutableStateOf<String?>(null) }

    // Staged edits for active project
    var stagedEdits by remember { mutableStateOf(ApkStagedEdits()) }
    var activeTab by remember { mutableStateOf(ApkEditorWorkspaceTab.COMMON) }

    // Build output state
    var isBuilding by remember { mutableStateOf(false) }
    var buildResult by remember { mutableStateOf<ApkBuildResult?>(null) }
    var liveBuildLogs by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(activeApkFile?.absolutePath) {
        val target = activeApkFile ?: return@LaunchedEffect
        isLoadingProject = true
        loadError = null
        buildResult = null
        liveBuildLogs = emptyList()
        stagedEdits = ApkStagedEdits()
        val loaded = withContext(Dispatchers.IO) {
            DragonApkStudioEngine.loadApkProject(context, target)
        }
        if (loaded != null) {
            project = loaded
            activeTab = ApkEditorWorkspaceTab.COMMON
        } else {
            loadError = "Unable to parse ${target.name}. Ensure it is a valid Android APK archive."
        }
        isLoadingProject = false
    }

    Dialog(
        onDismissRequest = { if (!isBuilding) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        if (activeApkFile == null || (project == null && !isLoadingProject && loadError == null)) {
            ApkEditorSourcePickerScreen(
                context = context,
                ddmRootDir = ddmRootDir,
                onDismiss = onDismiss,
                onApkSelected = { selectedFile ->
                    activeApkFile = selectedFile
                }
            )
        } else {
            val currentProject = project
            val pendingCount = stagedEdits.totalPendingChangesCount()

            Scaffold(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("apk_editor_studio_dialog"),
                containerColor = MaterialTheme.colorScheme.background,
                contentWindowInsets = WindowInsets.safeDrawing,
                topBar = {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 4.dp,
                        shadowElevation = 4.dp
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                IconButton(
                                    onClick = {
                                        if (!isBuilding) {
                                            if (initialApkFile == null) {
                                                activeApkFile = null
                                                project = null
                                            } else {
                                                onDismiss()
                                            }
                                        }
                                    },
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = currentProject?.displayTitle ?: activeApkFile?.name ?: "APK Editor Pro",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Black,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        if (pendingCount > 0) {
                                            Surface(
                                                color = WarningOrange.copy(alpha = 0.2f),
                                                shape = RoundedCornerShape(6.dp)
                                            ) {
                                                Text(
                                                    text = "$pendingCount edit(s)",
                                                    fontSize = 9.5.sp,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    color = WarningOrange,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        text = currentProject?.manifest?.packageName ?: "Loading package...",
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = NeonCyan,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Button(
                                    onClick = { activeTab = ApkEditorWorkspaceTab.BUILD },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (pendingCount > 0) SuccessGreen else NeonCyan,
                                        contentColor = Color.Black
                                    ),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier
                                        .height(34.dp)
                                        .testTag("apk_editor_go_build_btn")
                                ) {
                                    Icon(Icons.Default.BuildCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Build", fontSize = 11.5.sp, fontWeight = FontWeight.Black)
                                }
                                IconButton(
                                    onClick = { if (!isBuilding) onDismiss() },
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Close Studio", modifier = Modifier.size(18.dp))
                                }
                            }

                            if (currentProject != null) {
                                ScrollableTabRow(
                                    selectedTabIndex = ApkEditorWorkspaceTab.entries.indexOf(activeTab),
                                    edgePadding = 10.dp,
                                    containerColor = Color.Transparent,
                                    contentColor = NeonCyan,
                                    divider = {}
                                ) {
                                    ApkEditorWorkspaceTab.entries.forEach { tab ->
                                        val badgeCount = when (tab) {
                                            ApkEditorWorkspaceTab.MANIFEST -> (if (stagedEdits.rawManifestAxmlOverride != null) 1 else 0) +
                                                stagedEdits.manifestSpec.removedPermissions.size +
                                                stagedEdits.manifestSpec.addedPermissions.size +
                                                stagedEdits.manifestSpec.componentExportedOverrides.size +
                                                stagedEdits.manifestSpec.componentEnabledOverrides.size +
                                                stagedEdits.manifestSpec.metaDataValueOverrides.size +
                                                stagedEdits.manifestSpec.addedMetaData.size +
                                                stagedEdits.manifestSpec.removedMetaData.size
                                            ApkEditorWorkspaceTab.STRINGS -> stagedEdits.arscStringPatches.size + stagedEdits.arscTypedValuePatches.size
                                            ApkEditorWorkspaceTab.FILES -> stagedEdits.replacedOrAddedFiles.size +
                                                stagedEdits.deletedEntryPaths.size +
                                                stagedEdits.renamedEntryPaths.size +
                                                stagedEdits.compressionOverrides.size
                                            ApkEditorWorkspaceTab.DEX -> stagedEdits.dexStringPatches.values.sumOf { it.size } + stagedEdits.dexByteOverrides.size
                                            ApkEditorWorkspaceTab.BUILD -> pendingCount
                                            else -> 0
                                        }
                                        Tab(
                                            selected = activeTab == tab,
                                            onClick = { activeTab = tab },
                                            text = {
                                                Text(
                                                    text = if (badgeCount > 0) "${tab.label} ($badgeCount)" else tab.label,
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
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    when {
                        isLoadingProject -> {
                            Column(
                                modifier = Modifier.align(Alignment.Center),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                CircularProgressIndicator(color = NeonCyan)
                                Text(
                                    text = "Decompiling AXML, resources.arsc & DEX headers...",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        loadError != null -> {
                            Column(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = DangerRed, modifier = Modifier.size(44.dp))
                                Text(loadError.orEmpty(), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                Button(onClick = { activeApkFile = null; loadError = null }) {
                                    Text("Pick Another APK")
                                }
                            }
                        }
                        currentProject != null -> {
                            when (activeTab) {
                                ApkEditorWorkspaceTab.COMMON -> {
                                    ApkCommonEditPane(
                                        context = context,
                                        project = currentProject,
                                        edits = stagedEdits,
                                        onUpdateEdits = { stagedEdits = it }
                                    )
                                }
                                ApkEditorWorkspaceTab.MANIFEST -> {
                                    ApkManifestEditorPane(
                                        context = context,
                                        project = currentProject,
                                        edits = stagedEdits,
                                        onUpdateEdits = { stagedEdits = it }
                                    )
                                }
                                ApkEditorWorkspaceTab.STRINGS -> {
                                    ApkArscStringsPane(
                                        project = currentProject,
                                        edits = stagedEdits,
                                        onUpdateEdits = { stagedEdits = it }
                                    )
                                }
                                ApkEditorWorkspaceTab.FILES -> {
                                    ApkInternalFilesPane(
                                        context = context,
                                        ddmRootDir = ddmRootDir,
                                        project = currentProject,
                                        edits = stagedEdits,
                                        onUpdateEdits = { stagedEdits = it }
                                    )
                                }
                                ApkEditorWorkspaceTab.DEX -> {
                                    ApkDexInspectorPane(
                                        context = context,
                                        project = currentProject,
                                        edits = stagedEdits,
                                        onUpdateEdits = { stagedEdits = it }
                                    )
                                }
                                ApkEditorWorkspaceTab.BUILD -> {
                                    ApkBuildAndSignPane(
                                        context = context,
                                        ddmRootDir = ddmRootDir,
                                        project = currentProject,
                                        edits = stagedEdits,
                                        isBuilding = isBuilding,
                                        buildLogs = liveBuildLogs,
                                        buildResult = buildResult,
                                        onStartBuild = { outputFile, signV1V2 ->
                                            scope.launch {
                                                isBuilding = true
                                                buildResult = null
                                                liveBuildLogs = listOf("Starting Dragon APK Builder...")
                                                val res = withContext(Dispatchers.IO) {
                                                    DragonApkStudioEngine.buildEditedApk(
                                                        context = context,
                                                        project = currentProject,
                                                        edits = stagedEdits,
                                                        outputFile = outputFile,
                                                        signWithV1AndV2 = signV1V2
                                                    ) { line ->
                                                        liveBuildLogs = liveBuildLogs + line
                                                    }
                                                }
                                                buildResult = res
                                                isBuilding = false
                                                ApkPackageInstaller.showToastOnMain(context, res.message)
                                            }
                                        },
                                        onInstallApk = onInstallBuiltApk,
                                        onOpenFolder = { folder ->
                                            onDismiss()
                                            onOpenOutputFolder(folder)
                                        },
                                        onShareApk = onShareBuiltApk
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// =============================================================================
// SOURCE PICKER HUB (INSTALLED APPS / DDM & DOWNLOADS / BROWSE STORAGE)
// =============================================================================

@Composable
private fun ApkEditorSourcePickerScreen(
    context: Context,
    ddmRootDir: File,
    onDismiss: () -> Unit,
    onApkSelected: (File) -> Unit
) {
    val scope = rememberCoroutineScope()
    var activePickerTab by remember { mutableStateOf(ApkPickerTab.INSTALLED) }
    var searchQuery by remember { mutableStateOf("") }
    var installedApps by remember { mutableStateOf<List<InstalledAppRecord>>(emptyList()) }
    var storedApks by remember { mutableStateOf<List<StoredApkRecord>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var stagingLabel by remember { mutableStateOf<String?>(null) }

    // Storage folder browser state
    var browseDir by remember {
        mutableStateOf(
            runCatching { Environment.getExternalStorageDirectory() }.getOrNull() ?: ddmRootDir
        )
    }

    LaunchedEffect(Unit) {
        isLoading = true
        val (apps, stored) = withContext(Dispatchers.IO) {
            val a = DragonApkStudioEngine.loadInstalledApps(context)
            val s = DragonApkStudioEngine.scanStoredAndDownloadedApks(context, ddmRootDir)
            a to s
        }
        installedApps = apps
        storedApks = stored
        isLoading = false
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("apk_editor_source_picker"),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 4.dp) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "APK EDITOR PRO",
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace,
                                color = NeonCyan
                            )
                            Text(
                                text = "Select any installed, downloaded, or stored APK to edit",
                                fontSize = 10.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        ApkPickerTab.entries.forEachIndexed { idx, tab ->
                            SegmentedButton(
                                selected = activePickerTab == tab,
                                onClick = { activePickerTab = tab },
                                shape = SegmentedButtonDefaults.itemShape(idx, ApkPickerTab.entries.size),
                                label = { Text(tab.label, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            )
                        }
                    }

                    if (activePickerTab != ApkPickerTab.STORAGE) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Filter by app name or package...", fontSize = 12.sp) },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val q = searchQuery.trim().lowercase(Locale.US)
            when (activePickerTab) {
                ApkPickerTab.INSTALLED -> {
                    val list = remember(installedApps, q) {
                        installedApps.filter {
                            q.isEmpty() || it.appName.lowercase(Locale.US).contains(q) || it.packageName.lowercase(Locale.US).contains(q)
                        }
                    }
                    if (isLoading) {
                        CircularProgressIndicator(color = NeonCyan, modifier = Modifier.align(Alignment.Center))
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(list, key = { it.packageName }) { app ->
                                Card(
                                    shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            scope.launch {
                                                stagingLabel = "Staging ${app.appName} APK..."
                                                val staged = withContext(Dispatchers.IO) {
                                                    DragonApkStudioEngine.stageInstalledAppForEditing(context, app)
                                                }
                                                stagingLabel = null
                                                if (staged != null) {
                                                    onApkSelected(staged)
                                                } else {
                                                    ApkPackageInstaller.showToastOnMain(context, "Could not read APK for ${app.appName}")
                                                }
                                            }
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(11.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(42.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(MaterialTheme.colorScheme.surfaceVariant),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (app.iconBitmap != null) {
                                                Image(
                                                    bitmap = app.iconBitmap.asImageBitmap(),
                                                    contentDescription = app.appName,
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                            } else {
                                                Icon(Icons.Default.Android, contentDescription = null, tint = SuccessGreen)
                                            }
                                        }
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(app.appName, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(app.packageName, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text("v${app.versionName} • ${formatBytes(app.totalSizeBytes)}", fontSize = 9.5.sp, color = NeonCyan)
                                        }
                                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = NeonCyan)
                                    }
                                }
                            }
                        }
                    }
                }

                ApkPickerTab.DOWNLOADED -> {
                    val list = remember(storedApks, q) {
                        storedApks.filter {
                            q.isEmpty() || it.appName.lowercase(Locale.US).contains(q) ||
                                it.file.name.lowercase(Locale.US).contains(q) ||
                                it.packageName.lowercase(Locale.US).contains(q)
                        }
                    }
                    if (isLoading) {
                        CircularProgressIndicator(color = NeonCyan, modifier = Modifier.align(Alignment.Center))
                    } else if (list.isEmpty()) {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, tint = WarningOrange, modifier = Modifier.size(44.dp))
                            Text("No .apk files found in DDM or Downloads", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text("Use Installed Apps or Browse Storage tab to pick any APK", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(list, key = { it.file.absolutePath }) { apk ->
                                Card(
                                    shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onApkSelected(apk.file) }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(11.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(42.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(MaterialTheme.colorScheme.surfaceVariant),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (apk.iconBitmap != null) {
                                                Image(bitmap = apk.iconBitmap.asImageBitmap(), contentDescription = apk.appName, modifier = Modifier.fillMaxSize())
                                            } else {
                                                Icon(Icons.Default.Android, contentDescription = null, tint = SuccessGreen)
                                            }
                                        }
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Text(apk.appName, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
                                                Surface(color = SuccessGreen.copy(alpha = 0.16f), shape = RoundedCornerShape(4.dp)) {
                                                    Text(apk.folderBadge, fontSize = 8.5.sp, fontWeight = FontWeight.Bold, color = SuccessGreen, modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
                                                }
                                            }
                                            Text(apk.file.name, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text("${apk.packageName} • v${apk.versionName} • ${formatBytes(apk.sizeBytes)}", fontSize = 9.5.sp, color = NeonCyan, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = NeonCyan)
                                    }
                                }
                            }
                        }
                    }
                }

                ApkPickerTab.STORAGE -> {
                    val dirChildren = remember(browseDir) {
                        val kids = browseDir.listFiles()?.toList().orEmpty()
                        kids.filter { it.isDirectory || it.extension.equals("apk", true) }
                            .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                    }
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            IconButton(
                                onClick = { browseDir.parentFile?.let { browseDir = it } },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.DriveFolderUpload, contentDescription = "Up", tint = NeonCyan)
                            }
                            Text(
                                text = browseDir.absolutePath,
                                fontSize = 10.5.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        LazyColumn(
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(dirChildren, key = { it.absolutePath }) { f ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.surface)
                                        .clickable {
                                            if (f.isDirectory) browseDir = f else onApkSelected(f)
                                        }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        imageVector = if (f.isDirectory) Icons.Default.Folder else Icons.Default.Android,
                                        contentDescription = null,
                                        tint = if (f.isDirectory) WarningOrange else SuccessGreen
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(f.name, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (!f.isDirectory) {
                                            Text(formatBytes(f.length()), fontSize = 10.sp, color = NeonCyan)
                                        }
                                    }
                                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }

            if (stagingLabel != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center
                ) {
                    Card(shape = RoundedCornerShape(14.dp)) {
                        Row(
                            modifier = Modifier.padding(18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(color = NeonCyan, modifier = Modifier.size(22.dp))
                            Text(stagingLabel.orEmpty(), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

// =============================================================================
// TAB 1: COMMON EDIT, ICON REPLACER, APPLICATION FLAGS & ABI STRIPPER
// =============================================================================

@Composable
private fun ApkCommonEditPane(
    context: Context,
    project: ApkEditorProject,
    edits: ApkStagedEdits,
    onUpdateEdits: (ApkStagedEdits) -> Unit
) {
    val m = project.manifest
    val spec = edits.manifestSpec

    var appLabelText by remember(project.sourceApkFile.absolutePath) { mutableStateOf(spec.newAppLabel ?: m.appLabel) }
    var packageNameText by remember(project.sourceApkFile.absolutePath) { mutableStateOf(spec.newPackageName ?: m.packageName) }
    var versionNameText by remember(project.sourceApkFile.absolutePath) { mutableStateOf(spec.newVersionName ?: m.versionName) }
    var versionCodeText by remember(project.sourceApkFile.absolutePath) { mutableStateOf((spec.newVersionCode ?: m.versionCode).toString()) }
    var minSdkText by remember(project.sourceApkFile.absolutePath) { mutableStateOf((spec.newMinSdkVersion ?: m.minSdkVersion).toString()) }
    var targetSdkText by remember(project.sourceApkFile.absolutePath) { mutableStateOf((spec.newTargetSdkVersion ?: m.targetSdkVersion).toString()) }

    val iconPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val bmp = BitmapFactory.decodeStream(stream)
                    if (bmp != null) {
                        onUpdateEdits(edits.copy(replacementIconBitmap = bmp))
                        ApkPackageInstaller.showToastOnMain(
                            context,
                            "Staged custom icon (${project.launcherIconEntryPaths.size} icon asset(s) will update on build)"
                        )
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Icon & Package Identity Card
        Card(
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.35f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val displayIcon = edits.replacementIconBitmap ?: project.iconBitmap
                Box(
                    modifier = Modifier
                        .size(62.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    if (displayIcon != null) {
                        Image(
                            bitmap = displayIcon.asImageBitmap(),
                            contentDescription = "App Icon",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(Icons.Default.Android, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(34.dp))
                    }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("App Launcher Icon", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                    Text(
                        text = if (edits.replacementIconBitmap != null) {
                            "Custom icon staged (${project.launcherIconEntryPaths.size} target assets)"
                        } else {
                            "${project.launcherIconEntryPaths.size} launcher icon asset(s) detected"
                        },
                        fontSize = 10.5.sp,
                        color = if (edits.replacementIconBitmap != null) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = {
                                iconPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Replace Icon", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        if (edits.replacementIconBitmap != null) {
                            OutlinedButton(
                                onClick = { onUpdateEdits(edits.copy(replacementIconBitmap = null)) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("Reset", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }

        // Core Manifest Metadata Card
        Card(
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "COMMON MANIFEST METADATA",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    color = NeonCyan
                )

                OutlinedTextField(
                    value = appLabelText,
                    onValueChange = { value ->
                        appLabelText = value
                        onUpdateEdits(
                            edits.copy(
                                manifestSpec = edits.manifestSpec.copy(
                                    newAppLabel = if (value != m.appLabel) value else null
                                )
                            )
                        )
                    },
                    label = { Text("App Name (android:label)", fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = packageNameText,
                    onValueChange = { value ->
                        packageNameText = value
                        onUpdateEdits(
                            edits.copy(
                                manifestSpec = edits.manifestSpec.copy(
                                    newPackageName = if (value.trim() != m.packageName) value.trim() else null
                                )
                            )
                        )
                    },
                    label = { Text("Package Name (Clone App)", fontSize = 11.sp) },
                    trailingIcon = {
                        TextButton(
                            onClick = {
                                val cloned = if (packageNameText.endsWith(".clone")) "${packageNameText}2" else "${packageNameText}.clone"
                                packageNameText = cloned
                                onUpdateEdits(edits.copy(manifestSpec = edits.manifestSpec.copy(newPackageName = cloned)))
                            }
                        ) {
                            Text("+Clone", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = NeonCyan)
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = versionNameText,
                        onValueChange = { value ->
                            versionNameText = value
                            onUpdateEdits(
                                edits.copy(
                                    manifestSpec = edits.manifestSpec.copy(
                                        newVersionName = if (value != m.versionName) value else null
                                    )
                                )
                            )
                        },
                        label = { Text("Version Name", fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = versionCodeText,
                        onValueChange = { value ->
                            val clean = value.filter { it.isDigit() }
                            versionCodeText = clean
                            val parsed = clean.toIntOrNull()
                            onUpdateEdits(
                                edits.copy(
                                    manifestSpec = edits.manifestSpec.copy(
                                        newVersionCode = if (parsed != null && parsed != m.versionCode) parsed else null
                                    )
                                )
                            )
                        },
                        label = { Text("Version Code", fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = minSdkText,
                        onValueChange = { value ->
                            val clean = value.filter { it.isDigit() }
                            minSdkText = clean
                            val parsed = clean.toIntOrNull()
                            onUpdateEdits(
                                edits.copy(
                                    manifestSpec = edits.manifestSpec.copy(
                                        newMinSdkVersion = if (parsed != null && parsed != m.minSdkVersion) parsed else null
                                    )
                                )
                            )
                        },
                        label = { Text("Min SDK", fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = targetSdkText,
                        onValueChange = { value ->
                            val clean = value.filter { it.isDigit() }
                            targetSdkText = clean
                            val parsed = clean.toIntOrNull()
                            onUpdateEdits(
                                edits.copy(
                                    manifestSpec = edits.manifestSpec.copy(
                                        newTargetSdkVersion = if (parsed != null && parsed != m.targetSdkVersion) parsed else null
                                    )
                                )
                            )
                        },
                        label = { Text("Target SDK", fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                // Install Location Chips
                Text("Install Location", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val currentLoc = spec.newInstallLocation ?: m.installLocation
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val locs = listOf(-1 to "Default", 0 to "Auto", 1 to "Internal Only", 2 to "Prefer External")
                    items(locs) { (locVal, locLabel) ->
                        FilterChip(
                            selected = currentLoc == locVal,
                            onClick = {
                                onUpdateEdits(
                                    edits.copy(
                                        manifestSpec = edits.manifestSpec.copy(
                                            newInstallLocation = if (locVal == m.installLocation) null else locVal
                                        )
                                    )
                                )
                            },
                            label = { Text(locLabel, fontSize = 10.5.sp) }
                        )
                    }
                }
            }
        }

        // Application Manifest Flags
        Card(
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "APPLICATION FLAGS (AXML)",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    color = WarningOrange
                )
                ApkFlagToggleRow(
                    title = "android:debuggable",
                    subtitle = "Enable ADB debugging & runtime inspection",
                    checked = spec.debuggable ?: m.debuggable,
                    onCheckedChange = { v ->
                        onUpdateEdits(edits.copy(manifestSpec = edits.manifestSpec.copy(debuggable = if (v == m.debuggable) null else v)))
                    }
                )
                ApkFlagToggleRow(
                    title = "android:allowBackup",
                    subtitle = "Allow ADB/cloud app data backup",
                    checked = spec.allowBackup ?: m.allowBackup,
                    onCheckedChange = { v ->
                        onUpdateEdits(edits.copy(manifestSpec = edits.manifestSpec.copy(allowBackup = if (v == m.allowBackup) null else v)))
                    }
                )
                ApkFlagToggleRow(
                    title = "android:usesCleartextTraffic",
                    subtitle = "Allow plain HTTP connections without SSL errors",
                    checked = spec.usesCleartextTraffic ?: m.usesCleartextTraffic,
                    onCheckedChange = { v ->
                        onUpdateEdits(edits.copy(manifestSpec = edits.manifestSpec.copy(usesCleartextTraffic = if (v == m.usesCleartextTraffic) null else v)))
                    }
                )
                ApkFlagToggleRow(
                    title = "android:extractNativeLibs",
                    subtitle = "Extract .so libraries to filesystem on install",
                    checked = spec.extractNativeLibs ?: m.extractNativeLibs,
                    onCheckedChange = { v ->
                        onUpdateEdits(edits.copy(manifestSpec = edits.manifestSpec.copy(extractNativeLibs = if (v == m.extractNativeLibs) null else v)))
                    }
                )
                ApkFlagToggleRow(
                    title = "android:largeHeap",
                    subtitle = "Request expanded Dalvik/ART heap memory",
                    checked = spec.largeHeap ?: m.largeHeap,
                    onCheckedChange = { v ->
                        onUpdateEdits(edits.copy(manifestSpec = edits.manifestSpec.copy(largeHeap = if (v == m.largeHeap) null else v)))
                    }
                )
                ApkFlagToggleRow(
                    title = "Strip android:testOnly Flag",
                    subtitle = if (m.testOnly) "Currently testOnly=true (blocks normal install!)" else "Remove testOnly attribute if present",
                    checked = spec.stripTestOnly,
                    onCheckedChange = { v ->
                        onUpdateEdits(edits.copy(manifestSpec = edits.manifestSpec.copy(stripTestOnly = v)))
                    }
                )
            }
        }

        // Native ABI Stripper & Signature Card
        Card(
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "NATIVE ABIs & SIGNATURE INFO",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    color = SuccessGreen
                )
                if (project.nativeAbis.isEmpty()) {
                    Text("Pure Dalvik/DEX APK (no native lib/*.so architectures)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("Tap an ABI below to strip it and shrink APK size:", fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(project.nativeAbis) { abi ->
                            val isStripped = abi in edits.strippedAbis
                            FilterChip(
                                selected = !isStripped,
                                onClick = {
                                    val next = if (isStripped) edits.strippedAbis - abi else edits.strippedAbis + abi
                                    onUpdateEdits(edits.copy(strippedAbis = next))
                                },
                                label = {
                                    Text(
                                        text = if (isStripped) "STRIP: $abi" else "KEEP: $abi",
                                        fontSize = 10.5.sp,
                                        color = if (isStripped) DangerRed else SuccessGreen
                                    )
                                }
                            )
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Text(
                    text = "Source Signature: ${if (project.isSignedValidly) "VALID V1/V2" else "UNSIGNED / MODIFIED"} • ${project.zipEntries.size} entries • ${formatBytes(project.sourceApkFile.length())}",
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (project.isSignedValidly) SuccessGreen else WarningOrange
                )
                if (project.signatureSha256.isNotBlank()) {
                    Text(
                        text = "SHA-256: ${project.signatureSha256}",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun ApkFlagToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Text(subtitle, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

// =============================================================================
// TAB 2: MANIFEST (PERMISSIONS MANAGER, COMPONENTS, META-DATA, DECOMPILED XML)
// =============================================================================

@Composable
private fun ApkManifestEditorPane(
    context: Context,
    project: ApkEditorProject,
    edits: ApkStagedEdits,
    onUpdateEdits: (ApkStagedEdits) -> Unit
) {
    var subMode by remember { mutableStateOf(ManifestSubMode.PERMISSIONS) }
    var searchQuery by remember { mutableStateOf("") }
    var showAddPermissionDialog by remember { mutableStateOf(false) }
    var editingMetaData by remember { mutableStateOf<ApkMetaDataEntry?>(null) }

    val spec = edits.manifestSpec
    val q = searchQuery.trim().lowercase(Locale.US)

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.weight(1f)) {
                ManifestSubMode.entries.forEachIndexed { idx, mode ->
                    SegmentedButton(
                        selected = subMode == mode,
                        onClick = { subMode = mode },
                        shape = SegmentedButtonDefaults.itemShape(idx, ManifestSubMode.entries.size),
                        label = { Text(mode.label, fontSize = 10.5.sp, maxLines = 1) }
                    )
                }
            }
            if (subMode == ManifestSubMode.PERMISSIONS) {
                FilledTonalIconButton(onClick = { showAddPermissionDialog = true }) {
                    Icon(Icons.Default.AddModerator, contentDescription = "Add Permission", tint = SuccessGreen)
                }
            }
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search ${subMode.label.lowercase(Locale.US)}...", fontSize = 11.5.sp) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp)) },
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
        )

        when (subMode) {
            ManifestSubMode.PERMISSIONS -> {
                val allPerms = remember(project.manifest.permissions, spec.addedPermissions) {
                    (project.manifest.permissions + spec.addedPermissions).distinct()
                }
                val visible = allPerms.filter { q.isEmpty() || it.lowercase(Locale.US).contains(q) }
                LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(visible, key = { it }) { perm ->
                        val isRemoved = perm in spec.removedPermissions
                        val isNewlyAdded = perm in spec.addedPermissions
                        val isDangerous = perm in DragonApkStudioEngine.DANGEROUS_PERMISSIONS
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = when {
                                    isRemoved -> DangerRed.copy(alpha = 0.08f)
                                    isNewlyAdded -> SuccessGreen.copy(alpha = 0.1f)
                                    else -> MaterialTheme.colorScheme.surface
                                }
                            ),
                            border = BorderStroke(
                                1.dp,
                                when {
                                    isRemoved -> DangerRed.copy(alpha = 0.45f)
                                    isNewlyAdded -> SuccessGreen.copy(alpha = 0.45f)
                                    else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                }
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = perm.substringAfterLast('.'),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = if (isRemoved) DangerRed else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = perm,
                                        fontSize = 9.5.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.padding(top = 2.dp)) {
                                        Surface(
                                            color = (if (isDangerous) WarningOrange else NeonCyan).copy(alpha = 0.16f),
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = if (isDangerous) "DANGEROUS" else "NORMAL",
                                                fontSize = 8.5.sp,
                                                fontWeight = FontWeight.Black,
                                                color = if (isDangerous) WarningOrange else NeonCyan,
                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                            )
                                        }
                                        if (isRemoved) {
                                            Text("REMOVED", fontSize = 8.5.sp, fontWeight = FontWeight.Black, color = DangerRed)
                                        } else if (isNewlyAdded) {
                                            Text("ADDED", fontSize = 8.5.sp, fontWeight = FontWeight.Black, color = SuccessGreen)
                                        }
                                    }
                                }
                                OutlinedButton(
                                    onClick = {
                                        if (isNewlyAdded) {
                                            onUpdateEdits(
                                                edits.copy(manifestSpec = spec.copy(addedPermissions = spec.addedPermissions - perm))
                                            )
                                        } else {
                                            val nextRemoved = if (isRemoved) spec.removedPermissions - perm else spec.removedPermissions + perm
                                            onUpdateEdits(
                                                edits.copy(manifestSpec = spec.copy(removedPermissions = nextRemoved))
                                            )
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                    modifier = Modifier.height(30.dp)
                                ) {
                                    Text(
                                        text = if (isRemoved) "Restore" else "Remove",
                                        fontSize = 10.5.sp,
                                        color = if (isRemoved) SuccessGreen else DangerRed
                                    )
                                }
                            }
                        }
                    }
                }
            }

            ManifestSubMode.COMPONENTS -> {
                var showAddMetaDialog by remember { mutableStateOf(false) }
                val filteredComps = project.manifest.components.filter {
                    q.isEmpty() || it.name.lowercase(Locale.US).contains(q) || it.tagType.contains(q)
                }
                val combinedMeta = remember(project.manifest.metaData, spec.addedMetaData) {
                    val existingNames = project.manifest.metaData.map { it.name }.toSet()
                    project.manifest.metaData + spec.addedMetaData
                        .filterKeys { it !in existingNames }
                        .map { (k, v) -> ApkMetaDataEntry(k, v, true) }
                }
                val filteredMeta = combinedMeta.filter {
                    q.isEmpty() || it.name.lowercase(Locale.US).contains(q) || it.value.lowercase(Locale.US).contains(q)
                }
                LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    item {
                        Text(
                            text = "COMPONENTS (${filteredComps.size}) — Toggle exported & enabled",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            color = NeonCyan
                        )
                    }
                    items(filteredComps, key = { "${it.tagType}_${it.name}" }) { comp ->
                        val effectiveExported = spec.componentExportedOverrides[comp.name] ?: (comp.exported ?: false)
                        val effectiveEnabled = spec.componentEnabledOverrides[comp.name] ?: (comp.enabled ?: true)
                        Card(
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = comp.tagType.uppercase(Locale.US),
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Black,
                                        color = WarningOrange
                                    )
                                    Text(
                                        text = comp.name,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("exported", fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Switch(
                                        checked = effectiveExported,
                                        onCheckedChange = { newVal ->
                                            val updatedMap = spec.componentExportedOverrides.toMutableMap()
                                            if (newVal == comp.exported) updatedMap.remove(comp.name) else updatedMap[comp.name] = newVal
                                            onUpdateEdits(edits.copy(manifestSpec = spec.copy(componentExportedOverrides = updatedMap)))
                                        }
                                    )
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("enabled", fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Switch(
                                        checked = effectiveEnabled,
                                        onCheckedChange = { newVal ->
                                            val updatedMap = spec.componentEnabledOverrides.toMutableMap()
                                            if (newVal == (comp.enabled ?: true)) updatedMap.remove(comp.name) else updatedMap[comp.name] = newVal
                                            onUpdateEdits(edits.copy(manifestSpec = spec.copy(componentEnabledOverrides = updatedMap)))
                                        }
                                    )
                                }
                            }
                        }
                    }

                    item {
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "META-DATA (${filteredMeta.size}) — Tap to Edit",
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace,
                                color = SuccessGreen
                            )
                            TextButton(onClick = { showAddMetaDialog = true }) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(3.dp))
                                Text("Add Meta", fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    items(filteredMeta, key = { "meta_${it.name}" }) { meta ->
                        val isRemoved = meta.name in spec.removedMetaData
                        val effectiveVal = spec.metaDataValueOverrides[meta.name] ?: spec.addedMetaData[meta.name] ?: meta.value
                        Card(
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(
                                1.dp,
                                if (isRemoved) DangerRed.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { if (!isRemoved) editingMetaData = meta }
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(meta.name, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = if (isRemoved) DangerRed else NeonCyan)
                                    Text(
                                        text = if (isRemoved) "(Removed)" else effectiveVal,
                                        fontSize = 10.5.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        if (spec.addedMetaData.containsKey(meta.name)) {
                                            val nextAdd = spec.addedMetaData.toMutableMap().apply { remove(meta.name) }
                                            onUpdateEdits(edits.copy(manifestSpec = spec.copy(addedMetaData = nextAdd)))
                                        } else {
                                            val nextRem = if (isRemoved) spec.removedMetaData - meta.name else spec.removedMetaData + meta.name
                                            onUpdateEdits(edits.copy(manifestSpec = spec.copy(removedMetaData = nextRem)))
                                        }
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isRemoved) Icons.Default.RestoreFromTrash else Icons.Default.DeleteOutline,
                                        contentDescription = "Toggle Remove Meta",
                                        tint = if (isRemoved) SuccessGreen else DangerRed,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                if (showAddMetaDialog) {
                    var newKey by remember { mutableStateOf("") }
                    var newVal by remember { mutableStateOf("") }
                    AlertDialog(
                        onDismissRequest = { showAddMetaDialog = false },
                        title = { Text("Add <meta-data>", fontSize = 14.5.sp, fontWeight = FontWeight.Black) },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(value = newKey, onValueChange = { newKey = it }, label = { Text("android:name", fontSize = 11.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(value = newVal, onValueChange = { newVal = it }, label = { Text("android:value", fontSize = 11.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            }
                        },
                        confirmButton = {
                            Button(
                                enabled = newKey.isNotBlank(),
                                onClick = {
                                    val nextAdd = spec.addedMetaData.toMutableMap()
                                    nextAdd[newKey.trim()] = newVal
                                    onUpdateEdits(edits.copy(manifestSpec = spec.copy(addedMetaData = nextAdd, removedMetaData = spec.removedMetaData - newKey.trim())))
                                    showAddMetaDialog = false
                                }
                            ) { Text("Add") }
                        },
                        dismissButton = { TextButton(onClick = { showAddMetaDialog = false }) { Text("Cancel") } }
                    )
                }
            }

            ManifestSubMode.XML -> {
                val scope = rememberCoroutineScope()
                var showManifestXmlEditor by remember { mutableStateOf(false) }
                var manifestEditorBytes by remember { mutableStateOf<ByteArray?>(null) }
                val effectiveXmlText = remember(project.manifest.decompiledXml, edits.rawManifestAxmlOverride) {
                    val overrideBytes = edits.rawManifestAxmlOverride
                    if (overrideBytes != null) {
                        DragonAxmlCompilerEngine.decompileBinaryXml(overrideBytes) ?: project.manifest.decompiledXml
                    } else {
                        project.manifest.decompiledXml
                    }
                }
                val xmlLines = remember(effectiveXmlText, q) {
                    val all = effectiveXmlText.lines()
                    if (q.isEmpty()) all else all.filter { it.lowercase(Locale.US).contains(q) }
                }
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "AndroidManifest.xml (${xmlLines.size} lines)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            if (edits.rawManifestAxmlOverride != null) {
                                Text(
                                    text = "RECOMPILED BINARY AXML STAGED",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Black,
                                    color = SuccessGreen
                                )
                            }
                        }
                        if (edits.rawManifestAxmlOverride != null) {
                            OutlinedButton(
                                onClick = { onUpdateEdits(edits.copy(rawManifestAxmlOverride = null)) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(30.dp)
                            ) {
                                Text("Revert", fontSize = 10.sp, color = DangerRed)
                            }
                        }
                        Button(
                            onClick = {
                                scope.launch {
                                    val bytes = edits.rawManifestAxmlOverride ?: withContext(Dispatchers.IO) {
                                        DragonApkStudioEngine.readApkEntryBytes(project.sourceApkFile, "AndroidManifest.xml")
                                    }
                                    if (bytes != null) {
                                        manifestEditorBytes = bytes
                                        showManifestXmlEditor = true
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            modifier = Modifier
                                .height(32.dp)
                                .testTag("edit_manifest_xml_btn")
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Edit XML", fontSize = 11.sp, fontWeight = FontWeight.Black)
                        }
                        IconButton(
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("AndroidManifest.xml", effectiveXmlText))
                                ApkPackageInstaller.showToastOnMain(context, "Copied decompiled AndroidManifest.xml")
                            },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy XML", modifier = Modifier.size(15.dp))
                        }
                    }
                    LazyColumn(
                        contentPadding = PaddingValues(12.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF0B1120))
                            .horizontalScroll(rememberScrollState())
                    ) {
                        items(xmlLines) { line ->
                            Text(
                                text = line,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = when {
                                    line.trimStart().startsWith("<") -> NeonCyan
                                    line.contains("android:") -> Color(0xFF93C5FD)
                                    else -> Color(0xFFE2E8F0)
                                }
                            )
                        }
                    }
                }

                val editorBytes = manifestEditorBytes
                if (showManifestXmlEditor && editorBytes != null) {
                    DragonProXmlEditorDialog(
                        context = context,
                        title = "AndroidManifest.xml",
                        initialBytes = editorBytes,
                        saveButtonLabel = "Stage AXML",
                        allowBackupToggle = false,
                        onDismiss = { showManifestXmlEditor = false },
                        onSaveCompiledBytes = { compiledAxml, _ ->
                            onUpdateEdits(edits.copy(rawManifestAxmlOverride = compiledAxml))
                            showManifestXmlEditor = false
                            ApkPackageInstaller.showToastOnMain(context, "Staged recompiled binary AndroidManifest.xml")
                        }
                    )
                }
            }
        }
    }

    if (showAddPermissionDialog) {
        var customPerm by remember { mutableStateOf("android.permission.") }
        AlertDialog(
            onDismissRequest = { showAddPermissionDialog = false },
            title = { Text("Add <uses-permission>", fontSize = 15.sp, fontWeight = FontWeight.Black) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = customPerm,
                        onValueChange = { customPerm = it },
                        label = { Text("Permission name", fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Quick Presets:", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = NeonCyan)
                    LazyColumn(modifier = Modifier.height(200.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(DragonApkStudioEngine.COMMON_ANDROID_PERMISSIONS) { preset ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { customPerm = preset }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(preset.substringAfterLast('.'), fontSize = 11.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                Icon(Icons.Default.Add, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = customPerm.trim()
                        if (trimmed.isNotEmpty()) {
                            val updatedAdded = (spec.addedPermissions + trimmed).distinct()
                            val updatedRemoved = spec.removedPermissions - trimmed
                            onUpdateEdits(
                                edits.copy(
                                    manifestSpec = spec.copy(
                                        addedPermissions = updatedAdded,
                                        removedPermissions = updatedRemoved
                                    )
                                )
                            )
                        }
                        showAddPermissionDialog = false
                    }
                ) {
                    Text("Add Permission")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddPermissionDialog = false }) { Text("Cancel") }
            }
        )
    }

    editingMetaData?.let { meta ->
        var valueText by remember(meta.name) { mutableStateOf(spec.metaDataValueOverrides[meta.name] ?: meta.value) }
        AlertDialog(
            onDismissRequest = { editingMetaData = null },
            title = { Text("Edit <meta-data>", fontSize = 14.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(meta.name, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = NeonCyan)
                    OutlinedTextField(
                        value = valueText,
                        onValueChange = { valueText = it },
                        label = { Text("android:value", fontSize = 11.sp) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val next = spec.metaDataValueOverrides.toMutableMap()
                        if (valueText == meta.value) next.remove(meta.name) else next[meta.name] = valueText
                        onUpdateEdits(edits.copy(manifestSpec = spec.copy(metaDataValueOverrides = next)))
                        editingMetaData = null
                    }
                ) { Text("Apply") }
            },
            dismissButton = {
                TextButton(onClick = { editingMetaData = null }) { Text("Cancel") }
            }
        )
    }
}

// Tabs 3 (Resources / ARSC), 4 (Full APK Files Explorer), and 5 (Full Multi-DEX Pro Editor)
// are cleanly modularized in DragonApkEditorProPanes.kt.

// =============================================================================
// TAB 6: BUILD, ZIPALIGN & V1+V2 AUTO-SIGN
// =============================================================================

@Composable
private fun ApkBuildAndSignPane(
    context: Context,
    ddmRootDir: File,
    project: ApkEditorProject,
    edits: ApkStagedEdits,
    isBuilding: Boolean,
    buildLogs: List<String>,
    buildResult: ApkBuildResult?,
    onStartBuild: (outputFile: File, signV1V2: Boolean) -> Unit,
    onInstallApk: (File) -> Unit,
    onOpenFolder: (File) -> Unit,
    onShareApk: (File) -> Unit
) {
    val defaultOutDir = remember(ddmRootDir) { DragonApkStudioEngine.ensureExtractedApkFolder(ddmRootDir) }
    var saveToExtractedApkDir by remember { mutableStateOf(true) }
    var signWithV1V2 by remember { mutableStateOf(true) }
    var outputFileName by remember(project.sourceApkFile.absolutePath) {
        val base = project.sourceApkFile.nameWithoutExtension
            .replace(Regex("_edited$"), "")
            .ifBlank { "EditedApp" }
        mutableStateOf("${base}_edited.apk")
    }

    val targetDir = if (saveToExtractedApkDir || project.sourceApkFile.absolutePath.contains(context.cacheDir.absolutePath)) {
        defaultOutDir
    } else {
        project.sourceApkFile.parentFile ?: defaultOutDir
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Staged Changes Summary
        Card(
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "STAGED MODIFICATIONS (${edits.totalPendingChangesCount()})",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    color = NeonCyan
                )
                val summaryLines = buildList {
                    if (edits.rawManifestAxmlOverride != null) add("• Recompiled binary AndroidManifest.xml (AXML) staged")
                    if (edits.manifestSpec.hasAnyChanges()) add("• Binary AndroidManifest.xml metadata / flags / permissions / components updated")
                    if (edits.arscTypedValuePatches.isNotEmpty()) add("• resources.arsc typed values (colors/ints/bools/dimens): ${edits.arscTypedValuePatches.size} patched")
                    if (edits.arscStringPatches.isNotEmpty()) add("• resources.arsc string pool: ${edits.arscStringPatches.size} string(s) patched")
                    if (edits.dexByteOverrides.isNotEmpty()) add("• Full DEX binary edits (classes/bytecode/strings): ${edits.dexByteOverrides.keys.joinToString(", ")}")
                    val dexCount = edits.dexStringPatches.values.sumOf { it.size }
                    if (dexCount > 0) add("• classes*.dex quick string patches: $dexCount string(s)")
                    if (edits.replacementIconBitmap != null) add("• Launcher Icon replacement staged (${project.launcherIconEntryPaths.size} assets)")
                    if (edits.replacedOrAddedFiles.isNotEmpty()) add("• Internal Files edited/replaced/added: ${edits.replacedOrAddedFiles.size}")
                    if (edits.renamedEntryPaths.isNotEmpty()) add("• Internal Files renamed/moved: ${edits.renamedEntryPaths.size}")
                    if (edits.compressionOverrides.isNotEmpty()) add("• Compression mode overrides: ${edits.compressionOverrides.size}")
                    if (edits.deletedEntryPaths.isNotEmpty()) add("• Internal Files deleted: ${edits.deletedEntryPaths.size}")
                    if (edits.strippedAbis.isNotEmpty()) add("• Stripped native ABIs: ${edits.strippedAbis.joinToString(", ")}")
                    if (isEmpty()) add("• No content edits yet — building will cleanly zipalign & V1+V2 re-sign the APK.")
                }
                summaryLines.forEach { line ->
                    Text(line, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
                }
                if (project.signatureSha256.isNotBlank()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    Text(
                        text = "SOURCE CERT SHA-256 (${if (project.isSignedValidly) "VALID" else "UNSIGNED"})",
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (project.isSignedValidly) SuccessGreen else WarningOrange
                    )
                    Text(
                        text = project.signatureSha256,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Output Configuration Card
        Card(
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("OUTPUT & SIGNING OPTIONS", fontSize = 11.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, color = WarningOrange)
                OutlinedTextField(
                    value = outputFileName,
                    onValueChange = { outputFileName = it },
                    label = { Text("Output APK Filename", fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                ApkFlagToggleRow(
                    title = "Save to DDM/${DragonApkStudioEngine.EXTRACTED_APK_FOLDER_NAME}",
                    subtitle = "Destination: ${targetDir.absolutePath}",
                    checked = saveToExtractedApkDir,
                    onCheckedChange = { saveToExtractedApkDir = it }
                )
                ApkFlagToggleRow(
                    title = "Zipalign + Auto-Sign (V1 JAR + V2 APK Sig Block)",
                    subtitle = "Required for installing edited APKs on Android 11–16+",
                    checked = signWithV1V2,
                    onCheckedChange = { signWithV1V2 = it }
                )
            }
        }

        Button(
            onClick = {
                val cleanName = outputFileName.trim().let { if (it.lowercase(Locale.US).endsWith(".apk")) it else "$it.apk" }
                onStartBuild(File(targetDir, cleanName), signWithV1V2)
            },
            enabled = !isBuilding,
            colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen, contentColor = Color.Black),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("apk_editor_build_sign_btn")
        ) {
            if (isBuilding) {
                CircularProgressIndicator(color = Color.Black, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Building, Zipaligning & Signing...", fontWeight = FontWeight.Black, fontSize = 13.sp)
            } else {
                Icon(Icons.Default.VerifiedUser, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("BUILD & SIGN APK", fontWeight = FontWeight.Black, fontSize = 13.5.sp)
            }
        }

        // Build Console Log
        if (buildLogs.isNotEmpty()) {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1120)),
                border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text("BUILD CONSOLE", fontSize = 9.5.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, color = NeonCyan)
                    buildLogs.forEach { line ->
                        Text(
                            text = "> $line",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = when {
                                line.startsWith("ERROR") -> DangerRed
                                line.startsWith("SUCCESS") -> SuccessGreen
                                else -> Color(0xFFCBD5E1)
                            }
                        )
                    }
                }
            }
        }

        // Post-build Action Card
        buildResult?.takeIf { it.success && it.outputFile != null }?.let { res ->
            val outFile = res.outputFile!!
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SuccessGreen.copy(alpha = 0.12f)),
                border = BorderStroke(1.5.dp, SuccessGreen),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = SuccessGreen)
                        Column(modifier = Modifier.weight(1f)) {
                            Text("APK Built & Ready!", fontSize = 13.5.sp, fontWeight = FontWeight.Black, color = SuccessGreen)
                            Text(outFile.absolutePath, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { onInstallApk(outFile) },
                            colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen, contentColor = Color.Black),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.InstallMobile, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Install APK", fontSize = 11.5.sp, fontWeight = FontWeight.Black)
                        }
                        OutlinedButton(
                            onClick = { onOpenFolder(outFile.parentFile ?: targetDir) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Open Folder", fontSize = 11.5.sp)
                        }
                        IconButton(onClick = { onShareApk(outFile) }) {
                            Icon(Icons.Default.Share, contentDescription = "Share APK", tint = NeonCyan)
                        }
                    }
                }
            }
        }
    }
}
