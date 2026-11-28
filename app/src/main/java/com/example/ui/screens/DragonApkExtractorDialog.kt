package com.example.ui.screens

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.data.downloader.ApkPackageInstaller
import com.example.data.filemanager.DragonApkStudioEngine
import com.example.data.filemanager.InstalledAppRecord
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

private enum class ApkExtractorFilter(val label: String) {
    USER("User Apps"),
    ALL("All Apps"),
    SYSTEM("System"),
    SPLIT("Split APKs")
}

private enum class ApkExtractorSort(val label: String) {
    NAME("Name"),
    SIZE("Size"),
    UPDATED("Updated")
}

/**
 * Full-Screen Professional APK Extractor for Dragon Pro File Manager.
 *
 * Extracts installed user/system apps (single `.apk` or full `.apks` split bundle)
 * directly into the auto-created `DDM/Extracted Apk` directory, with batch extraction,
 * search, filtering, and 1-tap handoff to APK Editor Pro.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DragonApkExtractorDialog(
    context: Context,
    ddmRootDir: File,
    onDismiss: () -> Unit,
    onOpenExtractedFolder: (File) -> Unit,
    onEditApkInStudio: (File) -> Unit,
    onShareFile: (File) -> Unit
) {
    val scope = rememberCoroutineScope()
    val extractedFolder = remember(ddmRootDir) {
        DragonApkStudioEngine.ensureExtractedApkFolder(ddmRootDir)
    }

    var allApps by remember { mutableStateOf<List<InstalledAppRecord>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf(ApkExtractorFilter.USER) }
    var selectedSort by remember { mutableStateOf(ApkExtractorSort.NAME) }
    var extractSplitAsBundle by remember { mutableStateOf(true) }
    var selectedPackages by remember { mutableStateOf<Set<String>>(emptySet()) }

    // Active extraction state
    var isExtracting by remember { mutableStateOf(false) }
    var extractStatusText by remember { mutableStateOf("") }
    var extractProgress by remember { mutableFloatStateOf(0f) }
    var lastExtractedFiles by remember { mutableStateOf<List<File>>(emptyList()) }

    LaunchedEffect(Unit) {
        isLoading = true
        allApps = withContext(Dispatchers.IO) {
            DragonApkStudioEngine.ensureExtractedApkFolder(ddmRootDir)
            DragonApkStudioEngine.loadInstalledApps(context)
        }
        isLoading = false
    }

    val filteredApps = remember(allApps, searchQuery, selectedFilter, selectedSort) {
        val q = searchQuery.trim().lowercase(Locale.US)
        allApps
            .filter { app ->
                val matchesFilter = when (selectedFilter) {
                    ApkExtractorFilter.USER -> !app.isSystemApp
                    ApkExtractorFilter.SYSTEM -> app.isSystemApp
                    ApkExtractorFilter.SPLIT -> app.isSplitApk
                    ApkExtractorFilter.ALL -> true
                }
                val matchesQuery = q.isEmpty() ||
                    app.appName.lowercase(Locale.US).contains(q) ||
                    app.packageName.lowercase(Locale.US).contains(q)
                matchesFilter && matchesQuery
            }
            .let { list ->
                when (selectedSort) {
                    ApkExtractorSort.NAME -> list.sortedBy { it.appName.lowercase(Locale.US) }
                    ApkExtractorSort.SIZE -> list.sortedByDescending { it.totalSizeBytes }
                    ApkExtractorSort.UPDATED -> list.sortedByDescending { it.lastUpdateTimeMs }
                }
            }
    }

    fun launchExtractApps(appsToExtract: List<InstalledAppRecord>) {
        if (appsToExtract.isEmpty() || isExtracting) return
        scope.launch {
            isExtracting = true
            extractProgress = 0f
            val extracted = mutableListOf<File>()
            appsToExtract.forEachIndexed { idx, app ->
                extractStatusText = "Extracting ${idx + 1}/${appsToExtract.size}: ${app.appName}..."
                val result = withContext(Dispatchers.IO) {
                    DragonApkStudioEngine.extractInstalledApp(
                        context = context,
                        app = app,
                        ddmRootDir = ddmRootDir,
                        extractSplitAsBundle = extractSplitAsBundle
                    ) { itemFrac ->
                        extractProgress = ((idx.toFloat() + itemFrac) / appsToExtract.size.toFloat()).coerceIn(0f, 1f)
                    }
                }
                if (result != null) {
                    extracted.add(result)
                }
            }
            isExtracting = false
            selectedPackages = emptySet()
            lastExtractedFiles = extracted
            if (extracted.isNotEmpty()) {
                ApkPackageInstaller.showToastOnMain(
                    context,
                    "Extracted ${extracted.size} app(s) to DDM/${DragonApkStudioEngine.EXTRACTED_APK_FOLDER_NAME}"
                )
            } else {
                ApkPackageInstaller.showToastOnMain(context, "Could not extract selected APK(s)")
            }
        }
    }

    BackHandler(enabled = true) {
        if (!isExtracting) onDismiss()
    }

    val navBarBottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(10f)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
            .testTag("apk_extractor_dialog"),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
            ) {
                    // Top Bar
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 4.dp,
                        shadowElevation = 4.dp
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            IconButton(
                                onClick = { if (!isExtracting) onDismiss() },
                                modifier = Modifier
                                    .size(40.dp)
                                    .testTag("apk_extractor_back_btn")
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "APK EXTRACTOR PRO",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = FontFamily.Monospace,
                                    color = NeonCyan
                                )
                                Text(
                                    text = "Saves to DDM/${DragonApkStudioEngine.EXTRACTED_APK_FOLDER_NAME} • ${filteredApps.size} app(s)",
                                    fontSize = 10.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            FilledTonalIconButton(
                                onClick = {
                                    onDismiss()
                                    onOpenExtractedFolder(extractedFolder)
                                },
                                modifier = Modifier.testTag("apk_extractor_open_folder_btn")
                            ) {
                                Icon(
                                    Icons.Default.FolderOpen,
                                    contentDescription = "Open Extracted Apk Folder",
                                    tint = WarningOrange,
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                        }

                        // Search Bar
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search installed apps or package name...", fontSize = 12.sp) },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("apk_extractor_search_input")
                        )

                        // Filter Chips + Sort Toggle
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            items(ApkExtractorFilter.entries) { filter ->
                                FilterChip(
                                    selected = selectedFilter == filter,
                                    onClick = { selectedFilter = filter },
                                    label = { Text(filter.label, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                                )
                            }
                            item {
                                AssistChip(
                                    onClick = {
                                        val nextIdx = (ApkExtractorSort.entries.indexOf(selectedSort) + 1) % ApkExtractorSort.entries.size
                                        selectedSort = ApkExtractorSort.entries[nextIdx]
                                    },
                                    leadingIcon = { Icon(Icons.Default.SwapVert, contentDescription = null, modifier = Modifier.size(15.dp)) },
                                    label = { Text("Sort: ${selectedSort.label}", fontSize = 11.sp) }
                                )
                            }
                        }

                        // Split APK Mode Switch Row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (extractSplitAsBundle) "Split Apps → Full .APKS Bundle" else "Split Apps → Base .APK Only",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (extractSplitAsBundle) "Packages base + all splits so it can be reinstalled cleanly" else "Extracts only base.apk (useful for quick APK editing)",
                                    fontSize = 9.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = extractSplitAsBundle,
                                onCheckedChange = { extractSplitAsBundle = it }
                            )
                        }
                    }
                    }

                    // Main Installed Apps List
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                if (isLoading) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(color = NeonCyan)
                        Text(
                            text = "Scanning installed apps...",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else if (filteredApps.isEmpty()) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Android,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                            modifier = Modifier.size(48.dp)
                        )
                        Text("No matching installed apps found", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    val hasBottomBar = selectedPackages.isNotEmpty() || lastExtractedFiles.isNotEmpty()
                    LazyColumn(
                        contentPadding = PaddingValues(
                            start = 12.dp,
                            end = 12.dp,
                            top = 10.dp,
                            bottom = if (hasBottomBar) 16.dp else navBarBottomPadding + 16.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(filteredApps, key = { it.packageName }) { app ->
                            val isSelected = app.packageName in selectedPackages
                            InstalledAppExtractorCard(
                                app = app,
                                isSelected = isSelected,
                                onToggleSelect = {
                                    selectedPackages = if (isSelected) {
                                        selectedPackages - app.packageName
                                    } else {
                                        selectedPackages + app.packageName
                                    }
                                },
                                onExtractNow = { launchExtractApps(listOf(app)) },
                                onOpenInEditor = {
                                    scope.launch {
                                        isExtracting = true
                                        extractStatusText = "Preparing ${app.appName} for APK Editor Pro..."
                                        extractProgress = 0.5f
                                        val staged = withContext(Dispatchers.IO) {
                                            DragonApkStudioEngine.stageInstalledAppForEditing(context, app)
                                        }
                                        isExtracting = false
                                        if (staged != null) {
                                            onDismiss()
                                            onEditApkInStudio(staged)
                                        } else {
                                            ApkPackageInstaller.showToastOnMain(context, "Could not load ${app.appName} APK")
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
                    }

                    // Bottom Action Bar — always docked inside visible safe bounds above navigation bar
                    AnimatedVisibility(
                        visible = selectedPackages.isNotEmpty() || lastExtractedFiles.isNotEmpty(),
                        enter = expandVertically(expandFrom = Alignment.Bottom) + fadeIn(),
                        exit = shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut()
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            tonalElevation = 8.dp,
                            shadowElevation = 12.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .windowInsetsPadding(WindowInsets.navigationBars)
                                    .imePadding()
                            ) {
                                HorizontalDivider(
                                    color = if (selectedPackages.isNotEmpty()) {
                                        NeonCyan.copy(alpha = 0.45f)
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                    },
                                    thickness = 1.dp
                                )
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (selectedPackages.isNotEmpty()) {
                                        val selectedSizeBytes = remember(allApps, selectedPackages) {
                                            allApps.filter { it.packageName in selectedPackages }.sumOf { it.totalSizeBytes }
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "${selectedPackages.size} selected • ${formatBytes(selectedSizeBytes)}",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = FontFamily.Monospace,
                                                color = NeonCyan
                                            )
                                            Text(
                                                text = "→ DDM/${DragonApkStudioEngine.EXTRACTED_APK_FOLDER_NAME}",
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            OutlinedButton(
                                                onClick = { selectedPackages = emptySet() },
                                                shape = RoundedCornerShape(12.dp),
                                                modifier = Modifier
                                                    .weight(0.36f)
                                                    .height(48.dp)
                                                    .testTag("apk_extractor_clear_selection_btn")
                                            ) {
                                                Text("Clear (${selectedPackages.size})", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                            }
                                            Button(
                                                onClick = {
                                                    val chosen = allApps.filter { it.packageName in selectedPackages }
                                                    launchExtractApps(chosen)
                                                },
                                                enabled = !isExtracting,
                                                shape = RoundedCornerShape(12.dp),
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = SuccessGreen,
                                                    contentColor = Color.Black
                                                ),
                                                modifier = Modifier
                                                    .weight(0.64f)
                                                    .height(48.dp)
                                                    .testTag("apk_extractor_batch_extract_btn")
                                            ) {
                                                Icon(
                                                    Icons.Default.DownloadForOffline,
                                                    contentDescription = "Extract Selected APKs",
                                                    modifier = Modifier.size(19.dp)
                                                )
                                                Spacer(Modifier.width(6.dp))
                                                Text(
                                                    text = "Extract ${selectedPackages.size} Selected",
                                                    fontWeight = FontWeight.ExtraBold,
                                                    fontSize = 13.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    } else if (lastExtractedFiles.isNotEmpty()) {
                                        val latest = lastExtractedFiles.last()
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(20.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "Saved to DDM/${DragonApkStudioEngine.EXTRACTED_APK_FOLDER_NAME}",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = SuccessGreen
                                                )
                                                Text(
                                                    text = if (lastExtractedFiles.size == 1) latest.name else "${lastExtractedFiles.size} APK files extracted",
                                                    fontSize = 10.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            if (latest.extension.equals("apk", true)) {
                                                FilledTonalButton(
                                                    onClick = {
                                                        onDismiss()
                                                        onEditApkInStudio(latest)
                                                    },
                                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                    modifier = Modifier.height(40.dp)
                                                ) {
                                                    Text("Edit", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                            OutlinedButton(
                                                onClick = {
                                                    onDismiss()
                                                    onOpenExtractedFolder(extractedFolder)
                                                },
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                modifier = Modifier.height(40.dp)
                                            ) {
                                                Text("Open Folder", fontSize = 11.sp)
                                            }
                                            IconButton(onClick = { onShareFile(latest) }) {
                                                Icon(Icons.Default.Share, contentDescription = "Share APK", tint = NeonCyan, modifier = Modifier.size(18.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (isExtracting) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.65f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.5f)),
                            modifier = Modifier.width(300.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "EXTRACTING APK",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = FontFamily.Monospace,
                                    color = NeonCyan
                                )
                                Text(
                                    text = extractStatusText,
                                    fontSize = 11.5.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                LinearProgressIndicator(
                                    progress = { extractProgress },
                                    color = SuccessGreen,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                )
                            }
                        }
                    }
                }
            }
        }
    }

@Composable
private fun InstalledAppExtractorCard(
    app: InstalledAppRecord,
    isSelected: Boolean,
    onToggleSelect: () -> Unit,
    onExtractNow: () -> Unit,
    onOpenInEditor: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) NeonCyan.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) NeonCyan else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggleSelect() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggleSelect() },
                modifier = Modifier.size(22.dp)
            )
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (app.iconBitmap != null) {
                    Image(
                        bitmap = app.iconBitmap.asImageBitmap(),
                        contentDescription = app.appName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(Icons.Default.Android, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(26.dp))
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        text = app.appName,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (app.isSplitApk) {
                        Surface(
                            color = WarningOrange.copy(alpha = 0.18f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "SPLIT (${app.splitCount + 1})",
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.Black,
                                color = WarningOrange,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                    if (app.isSystemApp) {
                        Surface(
                            color = DangerRed.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "SYS",
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.Black,
                                color = DangerRed,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
                Text(
                    text = app.packageName,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "v${app.versionName} (${app.versionCode}) • ${formatBytes(app.totalSizeBytes)} • SDK ${app.minSdk}→${app.targetSdk}",
                    fontSize = 9.5.sp,
                    color = NeonCyan.copy(alpha = 0.9f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                FilledTonalButton(
                    onClick = onExtractNow,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Extract", fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = onOpenInEditor,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Edit", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
