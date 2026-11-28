package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.os.StatFs
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.DragonArchiverLauncherActivity
import com.example.R
import com.example.data.downloader.ApkAutoSigner
import com.example.data.downloader.ApkPackageInstaller
import com.example.service.DragonFloatingArchiverService
import com.example.data.downloader.DragonDownloadManager
import com.example.data.filemanager.AndroidDataObbBridge
import com.example.data.filemanager.ArchiveInternalNode
import com.example.data.filemanager.CompressionJobConfig
import com.example.data.filemanager.CompressionLevelPreset
import com.example.data.filemanager.DragonApkStudioEngine
import com.example.data.filemanager.DragonDocumentEngine
import com.example.data.filemanager.DragonFileManagerTaskEngine
import com.example.data.filemanager.UniversalArchiveEngine
import com.example.data.filemanager.UniversalCompressionFormat
import com.example.data.model.DownloadFolderCategory
import com.example.data.model.DownloadStateStage
import com.example.data.model.DownloaderTask
import com.example.data.repository.KernelRepository
import com.example.ui.haptics.LocalDragonHaptics
import com.example.ui.theme.DangerRed
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarningOrange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

enum class FileManagerSortField(val label: String) {
    NAME("Name"),
    DATE("Date Modified"),
    SIZE("Size"),
    TYPE("Type / Extension")
}

enum class FileManagerFilterCategory(
    val label: String,
    val icon: ImageVector,
    val accent: Color
) {
    ALL("All Files", Icons.Default.FolderSpecial, NeonCyan),
    PACKAGES("APKs & Packages", Icons.Default.Android, SuccessGreen),
    VIDEOS("Videos", Icons.Default.VideoLibrary, Color(0xFF38BDF8)),
    AUDIO("Audio", Icons.Default.LibraryMusic, Color(0xFFA855F7)),
    ARCHIVES("Archives / ZIP", Icons.Default.FolderZip, WarningOrange),
    DOCUMENTS("Documents", Icons.Default.Description, Color(0xFF60A5FA)),
    IMAGES("Photos", Icons.Default.Image, Color(0xFFF472B6)),
    TORRENTS("Torrents", Icons.Default.Hub, Color(0xFF8B5CF6)),
    SUBTITLES("Subtitles", Icons.Default.Subtitles, Color(0xFF34D399))
}

enum class ClipboardTransferOperation {
    COPY,
    CUT
}

data class FileManagerClipboardState(
    val files: List<File> = emptyList(),
    val operation: ClipboardTransferOperation = ClipboardTransferOperation.COPY,
    val sourceArchiveFile: File? = null,
    val archiveEntryPaths: List<String> = emptyList()
)

data class FileItemEntry(
    val file: File,
    val name: String,
    val isDirectory: Boolean,
    val lengthBytes: Long,
    val lastModifiedMs: Long,
    val extension: String,
    val childCount: Int = 0,
    val linkedTask: DownloaderTask? = null,
    val archiveEntryPath: String? = null,
    val compressedSizeBytes: Long = 0L
) {
    val selectionKey: String
        get() = archiveEntryPath ?: file.absolutePath
}

data class FileHashResult(
    val md5: String,
    val sha1: String,
    val sha256: String,
    val crc32: String
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DragonFileManagerScreen(
    repository: KernelRepository,
    onClose: () -> Unit,
    onOpenInSystemFileManager: () -> Unit = {},
    onExit: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val haptics = LocalDragonHaptics.current
    val scope = rememberCoroutineScope()
    val downloadManager = remember { repository.downloadManager }
    val tasks by downloadManager.tasksFlow.collectAsState()
    val clipboardManager = remember {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }

    // Resolve root storage & DDM directories without blocking the UI thread with mkdirs()
    val ddmRootDir = remember {
        resolveBestDdmRootDirectoryFast(context)
    }
    val publicDownloadsDir = remember {
        runCatching {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        }.getOrNull() ?: ddmRootDir.parentFile ?: ddmRootDir
    }
    val appPrivateDownloadsDir = remember {
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
    }
    val internalRootDir = remember {
        AndroidDataObbBridge.getExternalStorageRoot()
    }
    val romRootDir = remember {
        AndroidDataObbBridge.getRomRootDir()
    }
    val androidDataDir = remember(internalRootDir) { File(internalRootDir, "Android/data") }
    val androidObbDir = remember(internalRootDir) { File(internalRootDir, "Android/obb") }

    // Default directory when entering File Manager is Internal Storage (not DDM Root)
    var currentDir by remember {
        mutableStateOf(DragonFileManagerTaskEngine.savedCurrentDir ?: internalRootDir)
    }
    var navigationHistory by remember {
        mutableStateOf(DragonFileManagerTaskEngine.savedNavigationHistory)
    }
    // Virtual in-archive browsing state (allows viewing inside compressed files & copying without decompressing)
    var activeBrowsedArchive by remember { mutableStateOf<File?>(null) }
    var activeArchiveVirtualPath by remember { mutableStateOf("") }
    var nestedArchiveBackStack by remember { mutableStateOf<List<Pair<File, String>>>(emptyList()) }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    val runningEngineTask by DragonFileManagerTaskEngine.activeTaskFlow.collectAsState()
    val engineRefreshTick by DragonFileManagerTaskEngine.refreshTickFlow.collectAsState()
    val isMainAppRootGranted by repository.isRootGrantedFlow.collectAsState()
    var isRootModeEnabled by remember {
        mutableStateOf(AndroidDataObbBridge.isRootFileManagerEnabled || isMainAppRootGranted)
    }
    var hasFullStorageAccess by remember {
        mutableStateOf(AndroidDataObbBridge.hasFullInternalStorageAccess(context))
    }
    var safTreeGrantRevision by remember { mutableIntStateOf(0) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    val reloadStorageAccessState: () -> Unit = {
        val fullAccess = AndroidDataObbBridge.hasFullInternalStorageAccess(context)
        val rootActive = AndroidDataObbBridge.isRootActive()
        val changed = (fullAccess != hasFullStorageAccess) || (rootActive != isRootModeEnabled)
        hasFullStorageAccess = fullAccess
        isRootModeEnabled = rootActive
        if (changed) {
            AndroidDataObbBridge.invalidateDirectoryCache()
            DragonFileManagerTaskEngine.invalidateDirectorySnapshotCache(null)
            refreshTrigger++
        }
    }

    val allFilesAccessSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        AndroidDataObbBridge.invalidateDirectoryCache()
        DragonFileManagerTaskEngine.invalidateDirectorySnapshotCache(null)
        hasFullStorageAccess = AndroidDataObbBridge.hasFullInternalStorageAccess(context)
        refreshTrigger++
        if (hasFullStorageAccess) {
            ApkPackageInstaller.showToastOnMain(
                context,
                "Full Internal Storage Access enabled!"
            )
        }
    }

    val legacyStoragePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        AndroidDataObbBridge.invalidateDirectoryCache()
        DragonFileManagerTaskEngine.invalidateDirectorySnapshotCache(null)
        hasFullStorageAccess = AndroidDataObbBridge.hasFullInternalStorageAccess(context)
        refreshTrigger++
    }

    val safDocumentTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val treeUri = result.data?.data
        if (treeUri != null) {
            val saved = AndroidDataObbBridge.persistSafTreeUriGrant(context, treeUri)
            if (saved) {
                safTreeGrantRevision++
                DragonFileManagerTaskEngine.invalidateDirectorySnapshotCache(null)
                refreshTrigger++
                ApkPackageInstaller.showToastOnMain(
                    context,
                    "Folder access granted for ${currentDir.name} (Non-Root SAF active)"
                )
            }
        }
    }

    val requestFullInternalStorageAccess: () -> Unit = {
        scope.launch {
            val grantedViaRootOrExisting = withContext(Dispatchers.IO) {
                AndroidDataObbBridge.ensureAllFilesAccessWithOrWithoutRoot(context)
            }
            if (grantedViaRootOrExisting) {
                hasFullStorageAccess = true
                isRootModeEnabled = AndroidDataObbBridge.isRootActive()
                AndroidDataObbBridge.invalidateDirectoryCache()
                DragonFileManagerTaskEngine.invalidateDirectorySnapshotCache(null)
                refreshTrigger++
                ApkPackageInstaller.showToastOnMain(
                    context,
                    if (isRootModeEnabled) "Full Internal Storage & Root Access active!"
                    else "Full Internal Storage Access active!"
                )
            } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                runCatching {
                    allFilesAccessSettingsLauncher.launch(
                        AndroidDataObbBridge.buildManageAllFilesAccessIntent(context)
                    )
                }.onFailure {
                    AndroidDataObbBridge.openManageAllFilesAccessSettings(context)
                }
            } else {
                legacyStoragePermissionLauncher.launch(
                    arrayOf(
                        android.Manifest.permission.READ_EXTERNAL_STORAGE,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                    )
                )
            }
        }
    }

    val requestNonRootSafFolderGrant: (File) -> Unit = { targetFolder ->
        runCatching {
            safDocumentTreeLauncher.launch(
                AndroidDataObbBridge.buildSafDocumentTreeIntent(targetFolder)
            )
        }.onFailure {
            ApkPackageInstaller.showToastOnMain(
                context,
                "Could not launch system folder picker on this device"
            )
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                reloadStorageAccessState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Automatically ensure full internal storage access with or without root on launch
    LaunchedEffect(isMainAppRootGranted) {
        withContext(Dispatchers.IO) {
            val active = AndroidDataObbBridge.syncWithMainAppRootStatus(context, isMainAppRootGranted)
            val fullStorage = AndroidDataObbBridge.ensureAllFilesAccessWithOrWithoutRoot(context)
            if (active != isRootModeEnabled || fullStorage != hasFullStorageAccess) {
                isRootModeEnabled = active
                hasFullStorageAccess = fullStorage
                AndroidDataObbBridge.invalidateDirectoryCache()
                DragonFileManagerTaskEngine.invalidateDirectorySnapshotCache(null)
                refreshTrigger++
            }
        }
    }

    LaunchedEffect(currentDir) {
        DragonFileManagerTaskEngine.savedCurrentDir = currentDir
    }
    LaunchedEffect(navigationHistory) {
        DragonFileManagerTaskEngine.savedNavigationHistory = navigationHistory
    }

    // Hydrate persisted View Style, Sort, Hidden Files & Split-Pane preferences immediately on entry
    remember(context) {
        DragonFileManagerTaskEngine.ensurePreferencesLoaded(context)
    }

    var searchQuery by remember { mutableStateOf("") }
    var isSearchVisible by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf(FileManagerFilterCategory.ALL) }
    var sortField by remember { mutableStateOf(DragonFileManagerTaskEngine.savedSortField) }
    var sortAscending by remember { mutableStateOf(DragonFileManagerTaskEngine.savedSortAscending) }
    var viewStyle by remember { mutableStateOf(DragonFileManagerTaskEngine.savedViewStyle) }
    var showViewStyleDialog by remember { mutableStateOf(false) }
    val isSplitView = viewStyle == FileManagerViewStyle.SPLIT_DUAL_PANE
    var showHiddenFiles by remember { mutableStateOf(DragonFileManagerTaskEngine.savedShowHiddenFiles) }

    LaunchedEffect(viewStyle) {
        DragonFileManagerTaskEngine.saveViewStyle(context, viewStyle)
    }
    LaunchedEffect(sortField, sortAscending) {
        DragonFileManagerTaskEngine.saveSortPreferences(context, sortField, sortAscending)
    }
    LaunchedEffect(showHiddenFiles) {
        DragonFileManagerTaskEngine.saveShowHiddenFiles(context, showHiddenFiles)
    }

    // Independent Dual-Pane Split View state:
    // activeSplitPane (0 = Left, 1 = Right) drives all Dragon Archiver tools & dialogs;
    // inactivePaneState maintains the opposite split pane's completely separate directory,
    // back stack, archive session, selection, search, category, sort & hidden-file preferences.
    var activeSplitPane by remember { mutableIntStateOf(DragonFileManagerTaskEngine.savedActiveSplitPane) }
    var inactivePaneState by remember {
        mutableStateOf(
            SplitPaneStateSnapshot(
                currentDir = DragonFileManagerTaskEngine.savedSecondaryDir ?: internalRootDir,
                sortField = DragonFileManagerTaskEngine.savedSecondarySortField,
                sortAscending = DragonFileManagerTaskEngine.savedSecondarySortAscending,
                showHiddenFiles = DragonFileManagerTaskEngine.savedSecondaryShowHiddenFiles
            )
        )
    }
    LaunchedEffect(activeSplitPane) {
        DragonFileManagerTaskEngine.saveActiveSplitPane(context, activeSplitPane)
    }
    LaunchedEffect(inactivePaneState.currentDir) {
        DragonFileManagerTaskEngine.savedSecondaryDir = inactivePaneState.currentDir
    }
    LaunchedEffect(
        inactivePaneState.sortField,
        inactivePaneState.sortAscending,
        inactivePaneState.showHiddenFiles
    ) {
        DragonFileManagerTaskEngine.saveSecondaryPanePreferences(
            context = context,
            field = inactivePaneState.sortField,
            ascending = inactivePaneState.sortAscending,
            showHidden = inactivePaneState.showHiddenFiles
        )
    }
    var inactivePaneItems by remember { mutableStateOf<List<FileItemEntry>>(emptyList()) }
    var isLoadingInactivePane by remember { mutableStateOf(false) }

    // Multi-selection & Clipboard
    var selectedPaths by remember { mutableStateOf(setOf<String>()) }
    val isSelectionMode = selectedPaths.isNotEmpty()
    var fileClipboard by remember {
        mutableStateOf(DragonFileManagerTaskEngine.savedClipboard)
    }
    LaunchedEffect(fileClipboard) {
        DragonFileManagerTaskEngine.savedClipboard = fileClipboard
    }

    // Bookmarks / Favorites (pure File path construction; folders are created asynchronously on IO)
    var bookmarkedDirs by remember {
        mutableStateOf(
            listOfNotNull(
                ddmRootDir,
                File(ddmRootDir, "Program"),
                File(ddmRootDir, "Video"),
                File(ddmRootDir, "Audio"),
                File(ddmRootDir, "Compressed"),
                File(ddmRootDir, "Document"),
                publicDownloadsDir
            ).distinctBy { it.absolutePath }
        )
    }

    // Dialog states
    var showSortMenu by remember { mutableStateOf(false) }
    var showMoreOptionsMenu by remember { mutableStateOf(false) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var showNewTextFileDialog by remember { mutableStateOf(false) }
    var activeActionItem by remember { mutableStateOf<FileItemEntry?>(null) }
    var renameTargetItem by remember { mutableStateOf<FileItemEntry?>(null) }
    var deleteConfirmItems by remember { mutableStateOf<List<FileItemEntry>?>(null) }
    var propertiesInspectorItem by remember { mutableStateOf<FileItemEntry?>(null) }
    var archivePreviewItem by remember { mutableStateOf<FileItemEntry?>(null) }
    var textEditorItem by remember { mutableStateOf<FileItemEntry?>(null) }
    var textEditorForceHex by remember { mutableStateOf(false) }
    var showApkExtractorDialog by remember { mutableStateOf(false) }
    var showApkEditorStudio by remember { mutableStateOf(false) }
    var apkEditorInitialFile by remember { mutableStateOf<File?>(null) }
    var showCompressZipDialog by remember { mutableStateOf<List<File>?>(null) }
    var showBatchRenameDialog by remember { mutableStateOf<List<File>?>(null) }
    var showTrashBinDialog by remember { mutableStateOf(false) }
    var showPrivateVaultDialog by remember { mutableStateOf(false) }
    var pendingVaultItems by remember { mutableStateOf<List<FileItemEntry>>(emptyList()) }
    var busyOperationLabel by remember { mutableStateOf<String?>(null) }
    val trashItems by com.example.data.filemanager.DragonTrashBinManager.trashItemsFlow.collectAsState()
    val vaultItems by com.example.data.filemanager.DragonPrivateVaultManager.vaultItemsFlow.collectAsState()
    val isVaultUnlocked by com.example.data.filemanager.DragonPrivateVaultManager.isVaultUnlockedFlow.collectAsState()
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            com.example.data.filemanager.DragonTrashBinManager.ensureLoaded(context)
            com.example.data.filemanager.DragonPrivateVaultManager.ensureLoaded(context)
        }
    }

    val requestLockItemsInPrivateVault: (List<FileItemEntry>) -> Unit = { itemsToVault ->
        if (itemsToVault.isNotEmpty()) {
            if (!com.example.data.filemanager.DragonPrivateVaultManager.isPasswordConfigured(context)) {
                pendingVaultItems = itemsToVault
                showPrivateVaultDialog = true
                ApkPackageInstaller.showToastOnMain(
                    context,
                    "Create a Private Vault password to lock ${itemsToVault.size} item(s)"
                )
            } else {
                selectedPaths = emptySet()
                DragonFileManagerTaskEngine.launchMoveToPrivateVaultTask(
                    context = context,
                    itemsToVault = itemsToVault,
                    downloadManager = downloadManager
                ) {
                    refreshTrigger++
                }
            }
        }
    }

    // Only reload directory listings when a download actually completes (never on every progress tick!)
    val completedTasksSignature = remember(tasks) {
        tasks.count { it.stage == DownloadStateStage.COMPLETED }
    }

    // Sync completed tasks from DDM into physical DDM directory structure asynchronously on IO
    LaunchedEffect(completedTasksSignature, refreshTrigger, engineRefreshTick) {
        withContext(Dispatchers.IO) {
            appPrivateDownloadsDir.mkdirs()
            ensureDdmSubfoldersExist(ddmRootDir)
        }
    }

    // Load directory or virtual archive items with instant (0ms) cached swap + background revalidation
    val initialCacheKey = remember {
        buildDirectoryCacheKey(
            dir = currentDir,
            category = selectedCategory,
            sortField = sortField,
            sortAscending = sortAscending,
            showHidden = showHiddenFiles
        )
    }
    var directoryItems by remember {
        mutableStateOf(DragonFileManagerTaskEngine.getCachedDirectoryItems(initialCacheKey).orEmpty())
    }
    var isLoadingDir by remember { mutableStateOf(directoryItems.isEmpty()) }

    // Pre-warm ALL upper panel quick-access directories in parallel on IO so switching is instant (0ms)
    LaunchedEffect(
        ddmRootDir,
        publicDownloadsDir,
        appPrivateDownloadsDir,
        internalRootDir,
        romRootDir,
        bookmarkedDirs,
        isRootModeEnabled,
        hasFullStorageAccess,
        safTreeGrantRevision,
        sortField,
        sortAscending,
        showHiddenFiles,
        engineRefreshTick,
        completedTasksSignature
    ) {
        kotlinx.coroutines.delay(300L)
        val quickDirs = buildList {
            add(internalRootDir)
            add(romRootDir)
            add(ddmRootDir)
            add(publicDownloadsDir)
            add(appPrivateDownloadsDir)
            addAll(bookmarkedDirs)
        }.distinctBy { it.absolutePath }

        withContext(Dispatchers.IO) {
            val tasksSnapshot = tasks
            quickDirs.map { targetDir ->
                async {
                    val key = buildDirectoryCacheKey(
                        dir = targetDir,
                        category = FileManagerFilterCategory.ALL,
                        sortField = sortField,
                        sortAscending = sortAscending,
                        showHidden = showHiddenFiles
                    )
                    val entries = loadDirectoryEntries(
                        context = context,
                        currentDir = targetDir,
                        ddmRootDir = ddmRootDir,
                        appPrivateDir = appPrivateDownloadsDir,
                        tasks = tasksSnapshot,
                        searchQuery = "",
                        category = FileManagerFilterCategory.ALL,
                        sortField = sortField,
                        sortAscending = sortAscending,
                        showHidden = showHiddenFiles
                    )
                    DragonFileManagerTaskEngine.putCachedDirectoryItems(key, entries)
                }
            }.awaitAll()
        }
    }

    LaunchedEffect(
        currentDir,
        activeBrowsedArchive,
        activeArchiveVirtualPath,
        refreshTrigger,
        engineRefreshTick,
        searchQuery,
        selectedCategory,
        sortField,
        sortAscending,
        showHiddenFiles,
        completedTasksSignature,
        isRootModeEnabled,
        hasFullStorageAccess,
        safTreeGrantRevision
    ) {
        val browsedArchive = activeBrowsedArchive
        val cacheKey = if (browsedArchive == null && searchQuery.isBlank()) {
            buildDirectoryCacheKey(
                dir = currentDir,
                category = selectedCategory,
                sortField = sortField,
                sortAscending = sortAscending,
                showHidden = showHiddenFiles
            )
        } else null

        val cachedSnapshot = cacheKey?.let { DragonFileManagerTaskEngine.getCachedDirectoryItems(it) }
        if (cachedSnapshot != null) {
            // Instant UI swap from memory cache — zero loading flash!
            if (directoryItems != cachedSnapshot) {
                directoryItems = cachedSnapshot
            }
            isLoadingDir = false
        } else {
            isLoadingDir = true
        }

        val tasksSnapshot = tasks
        val loadedItems = withContext(Dispatchers.IO) {
            if (browsedArchive != null) {
                loadArchiveVirtualEntries(
                    context = context,
                    archiveFile = browsedArchive,
                    virtualDirPath = activeArchiveVirtualPath,
                    searchQuery = searchQuery,
                    category = selectedCategory,
                    sortField = sortField,
                    sortAscending = sortAscending,
                    showHidden = showHiddenFiles
                )
            } else {
                loadDirectoryEntries(
                    context = context,
                    currentDir = currentDir,
                    ddmRootDir = ddmRootDir,
                    appPrivateDir = appPrivateDownloadsDir,
                    tasks = tasksSnapshot,
                    searchQuery = searchQuery,
                    category = selectedCategory,
                    sortField = sortField,
                    sortAscending = sortAscending,
                    showHidden = showHiddenFiles
                )
            }
        }
        if (cacheKey != null) {
            DragonFileManagerTaskEngine.putCachedDirectoryItems(cacheKey, loadedItems)
        }
        if (directoryItems != loadedItems) {
            directoryItems = loadedItems
        }
        isLoadingDir = false
    }

    // Independent background loader for the inactive split pane (instant 0ms cache swap + IO refresh)
    LaunchedEffect(
        isSplitView,
        inactivePaneState.currentDir,
        inactivePaneState.activeBrowsedArchive,
        inactivePaneState.activeArchiveVirtualPath,
        inactivePaneState.searchQuery,
        inactivePaneState.selectedCategory,
        inactivePaneState.sortField,
        inactivePaneState.sortAscending,
        inactivePaneState.showHiddenFiles,
        refreshTrigger,
        engineRefreshTick,
        completedTasksSignature,
        isRootModeEnabled,
        hasFullStorageAccess,
        safTreeGrantRevision
    ) {
        if (!isSplitView) return@LaunchedEffect
        val pane = inactivePaneState
        val browsedArchive = pane.activeBrowsedArchive
        val cacheKey = if (browsedArchive == null && pane.searchQuery.isBlank()) {
            buildDirectoryCacheKey(
                dir = pane.currentDir,
                category = pane.selectedCategory,
                sortField = pane.sortField,
                sortAscending = pane.sortAscending,
                showHidden = pane.showHiddenFiles
            )
        } else null

        val cachedSnapshot = cacheKey?.let { DragonFileManagerTaskEngine.getCachedDirectoryItems(it) }
        if (cachedSnapshot != null) {
            if (inactivePaneItems != cachedSnapshot) {
                inactivePaneItems = cachedSnapshot
            }
            isLoadingInactivePane = false
        } else {
            isLoadingInactivePane = true
        }

        val tasksSnapshot = tasks
        val loadedItems = withContext(Dispatchers.IO) {
            if (browsedArchive != null) {
                loadArchiveVirtualEntries(
                    context = context,
                    archiveFile = browsedArchive,
                    virtualDirPath = pane.activeArchiveVirtualPath,
                    searchQuery = pane.searchQuery,
                    category = pane.selectedCategory,
                    sortField = pane.sortField,
                    sortAscending = pane.sortAscending,
                    showHidden = pane.showHiddenFiles
                )
            } else {
                loadDirectoryEntries(
                    context = context,
                    currentDir = pane.currentDir,
                    ddmRootDir = ddmRootDir,
                    appPrivateDir = appPrivateDownloadsDir,
                    tasks = tasksSnapshot,
                    searchQuery = pane.searchQuery,
                    category = pane.selectedCategory,
                    sortField = pane.sortField,
                    sortAscending = pane.sortAscending,
                    showHidden = pane.showHiddenFiles
                )
            }
        }
        if (cacheKey != null) {
            DragonFileManagerTaskEngine.putCachedDirectoryItems(cacheKey, loadedItems)
        }
        if (inactivePaneItems != loadedItems) {
            inactivePaneItems = loadedItems
        }
        isLoadingInactivePane = false
    }

    fun snapshotActivePane(): SplitPaneStateSnapshot = SplitPaneStateSnapshot(
        currentDir = currentDir,
        navigationHistory = navigationHistory,
        activeBrowsedArchive = activeBrowsedArchive,
        activeArchiveVirtualPath = activeArchiveVirtualPath,
        selectedPaths = selectedPaths,
        searchQuery = searchQuery,
        selectedCategory = selectedCategory,
        sortField = sortField,
        sortAscending = sortAscending,
        showHiddenFiles = showHiddenFiles
    )

    fun switchActiveSplitPane(targetPane: Int) {
        if (targetPane == activeSplitPane) return
        val prevActiveSnapshot = snapshotActivePane()
        val prevActiveItems = directoryItems
        val prevActiveLoading = isLoadingDir

        val nextActive = inactivePaneState
        val nextActiveItems = inactivePaneItems
        val nextActiveLoading = isLoadingInactivePane

        inactivePaneState = prevActiveSnapshot
        inactivePaneItems = prevActiveItems
        isLoadingInactivePane = prevActiveLoading

        currentDir = nextActive.currentDir
        navigationHistory = nextActive.navigationHistory
        activeBrowsedArchive = nextActive.activeBrowsedArchive
        activeArchiveVirtualPath = nextActive.activeArchiveVirtualPath
        selectedPaths = nextActive.selectedPaths
        searchQuery = nextActive.searchQuery
        selectedCategory = nextActive.selectedCategory
        sortField = nextActive.sortField
        sortAscending = nextActive.sortAscending
        showHiddenFiles = nextActive.showHiddenFiles
        directoryItems = nextActiveItems
        isLoadingDir = nextActiveLoading

        activeSplitPane = targetPane
    }

    fun navigateToDirectory(target: File, resetCategoryToAll: Boolean = false) {
        activeBrowsedArchive = null
        activeArchiveVirtualPath = ""
        nestedArchiveBackStack = emptyList()
        val effectiveCategory = if (resetCategoryToAll) FileManagerFilterCategory.ALL else selectedCategory
        if (resetCategoryToAll && selectedCategory != FileManagerFilterCategory.ALL) {
            selectedCategory = FileManagerFilterCategory.ALL
        }
        if (target.absolutePath == currentDir.absolutePath) return

        // Synchronous 0ms cache lookup so upper-panel & folder switches render on the very next frame!
        if (searchQuery.isBlank()) {
            val instantKey = buildDirectoryCacheKey(
                dir = target,
                category = effectiveCategory,
                sortField = sortField,
                sortAscending = sortAscending,
                showHidden = showHiddenFiles
            )
            DragonFileManagerTaskEngine.getCachedDirectoryItems(instantKey)?.let { cached ->
                directoryItems = cached
                isLoadingDir = false
            }
        }
        navigationHistory = navigationHistory + currentDir
        currentDir = target
        selectedPaths = emptySet()
    }

    fun enterCompressedArchiveBrowser(archiveFile: File) {
        val currentArchive = activeBrowsedArchive
        if (currentArchive != null && currentArchive.absolutePath != archiveFile.absolutePath) {
            nestedArchiveBackStack = nestedArchiveBackStack + (currentArchive to activeArchiveVirtualPath)
        }
        activeBrowsedArchive = archiveFile
        activeArchiveVirtualPath = ""
        selectedPaths = emptySet()
        if (selectedCategory != FileManagerFilterCategory.ALL) {
            selectedCategory = FileManagerFilterCategory.ALL
        }
    }

    fun handleItemPrimaryClick(item: FileItemEntry) {
        val archiveFile = activeBrowsedArchive
        val currentSelection = selectedPaths
        if (currentSelection.isNotEmpty()) {
            haptics.tap()
            selectedPaths = if (item.selectionKey in currentSelection) {
                currentSelection - item.selectionKey
            } else {
                currentSelection + item.selectionKey
            }
        } else if (archiveFile != null && item.archiveEntryPath != null) {
            haptics.tap()
            if (item.isDirectory) {
                activeArchiveVirtualPath = item.archiveEntryPath
            } else if (UniversalArchiveEngine.isSupportedArchiveFileName(item.name) && item.extension != "apk") {
                // Directly browse inside a nested archive or OTA payload.bin (via zero-copy CrAU_ZIP_LINK)!
                scope.launch {
                    busyOperationLabel = "Opening ${item.name}..."
                    val nestedPreview = withContext(Dispatchers.IO) {
                        UniversalArchiveEngine.extractSingleEntryToPreviewCache(
                            context = context,
                            archiveFile = archiveFile,
                            entryPath = item.archiveEntryPath
                        )
                    }
                    busyOperationLabel = null
                    if (nestedPreview != null && nestedPreview.exists()) {
                        enterCompressedArchiveBrowser(nestedPreview)
                    } else {
                        activeActionItem = item
                    }
                }
            } else if (item.extension == "img" || item.lengthBytes > 48L * 1024L * 1024L) {
                // For OTA partition .img files (boot.img, system.img, vendor.img) or large entries,
                // open the Pro Action Sheet directly so the user can 1-tap Extract or Copy/Paste without bloating cacheDir!
                activeActionItem = item
            } else {
                scope.launch {
                    busyOperationLabel = "Opening ${item.name} from archive..."
                    val previewFile = withContext(Dispatchers.IO) {
                        UniversalArchiveEngine.extractSingleEntryToPreviewCache(
                            context = context,
                            archiveFile = archiveFile,
                            entryPath = item.archiveEntryPath
                        )
                    }
                    busyOperationLabel = null
                    if (previewFile != null && previewFile.exists()) {
                        val tempEntry = item.copy(
                            file = previewFile,
                            archiveEntryPath = null
                        )
                        if (isEditableTextExtension(tempEntry.extension)) {
                            textEditorForceHex = false
                            textEditorItem = tempEntry
                        } else if (com.example.data.audio.DragonAudioPlayerEngine.isAudioExtension(tempEntry.extension)) {
                            com.example.data.audio.DragonAudioPlayerEngine.playFile(context, previewFile)
                        } else if (ApkPackageInstaller.isInstallableExtension(tempEntry.extension)) {
                            activeActionItem = item
                        } else {
                            openFileWithSystemViewer(context, previewFile)
                        }
                    } else {
                        activeActionItem = item
                    }
                }
            }
        } else if (item.isDirectory) {
            haptics.tap()
            navigateToDirectory(item.file)
        } else {
            haptics.tap()
            handlePrimaryFileTap(
                context = context,
                item = item,
                siblingAudioFiles = directoryItems
                    .filter { !it.isDirectory && com.example.data.audio.DragonAudioPlayerEngine.isAudioExtension(it.extension) }
                    .map { it.file },
                downloadManager = downloadManager,
                onBrowseInsideArchive = { enterCompressedArchiveBrowser(item.file) },
                onOpenArchiveInspector = { archivePreviewItem = item },
                onOpenTextEditor = {
                    textEditorForceHex = false
                    textEditorItem = item
                },
                onOpenActionSheet = { activeActionItem = item }
            )
        }
    }

    fun launchDirectTransferToOppositePane(
        itemsToTransfer: List<FileItemEntry>,
        operation: ClipboardTransferOperation
    ) {
        if (itemsToTransfer.isEmpty()) return
        val destDir = inactivePaneState.currentDir
        val browsingArchiveFile = activeBrowsedArchive
        val clip = if (browsingArchiveFile != null) {
            val entryPaths = itemsToTransfer.mapNotNull { it.archiveEntryPath }
            if (entryPaths.isEmpty()) return
            FileManagerClipboardState(
                files = emptyList(),
                operation = ClipboardTransferOperation.COPY,
                sourceArchiveFile = browsingArchiveFile,
                archiveEntryPaths = entryPaths
            )
        } else {
            FileManagerClipboardState(
                files = itemsToTransfer.map { it.file },
                operation = operation
            )
        }
        haptics.confirm()
        selectedPaths = emptySet()
        DragonFileManagerTaskEngine.launchClipboardPasteTask(
            context = context,
            clip = clip,
            targetDir = destDir,
            onCompleted = { count ->
                if (count > 0) {
                    DragonFileManagerTaskEngine.invalidateDirectorySnapshotCache(currentDir.absolutePath)
                    DragonFileManagerTaskEngine.invalidateDirectorySnapshotCache(destDir.absolutePath)
                    refreshTrigger++
                }
            }
        )
    }

    fun handleBackNavigation() {
        when {
            isSelectionMode -> {
                selectedPaths = emptySet()
            }
            activeBrowsedArchive != null -> {
                if (activeArchiveVirtualPath.isNotEmpty()) {
                    activeArchiveVirtualPath = activeArchiveVirtualPath
                        .trimEnd('/')
                        .substringBeforeLast('/', "")
                } else if (nestedArchiveBackStack.isNotEmpty()) {
                    val (parentArchive, parentVirtualPath) = nestedArchiveBackStack.last()
                    nestedArchiveBackStack = nestedArchiveBackStack.dropLast(1)
                    activeBrowsedArchive = parentArchive
                    activeArchiveVirtualPath = parentVirtualPath
                } else {
                    activeBrowsedArchive = null
                    activeArchiveVirtualPath = ""
                }
                selectedPaths = emptySet()
            }
            isSearchVisible && searchQuery.isNotBlank() -> {
                searchQuery = ""
                isSearchVisible = false
            }
            selectedCategory != FileManagerFilterCategory.ALL -> {
                selectedCategory = FileManagerFilterCategory.ALL
            }
            navigationHistory.isNotEmpty() -> {
                val previous = navigationHistory.last()
                navigationHistory = navigationHistory.dropLast(1)
                if (searchQuery.isBlank()) {
                    val backKey = buildDirectoryCacheKey(
                        dir = previous,
                        category = selectedCategory,
                        sortField = sortField,
                        sortAscending = sortAscending,
                        showHidden = showHiddenFiles
                    )
                    DragonFileManagerTaskEngine.getCachedDirectoryItems(backKey)?.let { cached ->
                        directoryItems = cached
                        isLoadingDir = false
                    }
                }
                currentDir = previous
            }
            else -> {
                onClose()
            }
        }
    }

    BackHandler(onBack = ::handleBackNavigation)

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag("dragon_file_manager_screen"),
        color = MaterialTheme.colorScheme.background
    ) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                FileManagerTopBar(
                    currentDir = currentDir,
                    isSelectionMode = isSelectionMode,
                    selectedCount = selectedPaths.size,
                    totalCount = directoryItems.size,
                    viewStyle = viewStyle,
                    activeSplitPaneLabel = if (isSplitView) {
                        if (activeSplitPane == 0) "L" else "R"
                    } else null,
                    isSearchVisible = isSearchVisible,
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    onToggleSearch = {
                        haptics.tap()
                        isSearchVisible = !isSearchVisible
                        if (!isSearchVisible) searchQuery = ""
                    },
                    onOpenViewStyleMenu = {
                        haptics.tap()
                        showViewStyleDialog = true
                    },
                    onOpenSortMenu = {
                        haptics.tap()
                        showSortMenu = true
                    },
                    onOpenMoreMenu = {
                        haptics.tap()
                        showMoreOptionsMenu = true
                    },
                    onSelectAll = {
                        haptics.tap()
                        selectedPaths = if (selectedPaths.size == directoryItems.size) {
                            emptySet()
                        } else {
                            directoryItems.map { it.selectionKey }.toSet()
                        }
                    },
                    onClearSelection = {
                        haptics.tap()
                        selectedPaths = emptySet()
                    },
                    onBack = {
                        haptics.tap()
                        handleBackNavigation()
                    },
                    onClose = {
                        haptics.tap()
                        onClose()
                    }
                )
            },
            bottomBar = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars)
                ) {
                    // Clipboard Paste Banner (only show when in a real storage folder, or allow exiting archive to paste)
                    fileClipboard?.let { clip ->
                        ClipboardPasteBar(
                            clipboardState = clip,
                            targetDir = currentDir,
                            isInsideArchive = activeBrowsedArchive != null,
                            onPasteHere = {
                                haptics.confirm()
                                if (activeBrowsedArchive != null) {
                                    // Exit virtual archive view first so user sees files pasted into currentDir
                                    activeBrowsedArchive = null
                                    activeArchiveVirtualPath = ""
                                }
                                val targetSnapshot = currentDir
                                DragonFileManagerTaskEngine.launchClipboardPasteTask(
                                    context = context,
                                    clip = clip,
                                    targetDir = targetSnapshot,
                                    onCompleted = { count ->
                                        if (count > 0) {
                                            fileClipboard = null
                                            selectedPaths = emptySet()
                                            refreshTrigger++
                                        }
                                    }
                                )
                            },
                            onCancelClipboard = {
                                haptics.tap()
                                fileClipboard = null
                            }
                        )
                    }

                    // Multi-Select Batch Action Toolbar
                    AnimatedVisibility(visible = isSelectionMode) {
                        val browsingArchiveFile = activeBrowsedArchive
                        val oppositeShortLabel = if (isSplitView) {
                            if (activeSplitPane == 0) "→ R" else "← L"
                        } else null
                        val oppositeDirName = if (isSplitView) {
                            inactivePaneState.currentDir.name.ifBlank { "/" }
                        } else null
                        MultiSelectBottomActionBar(
                            selectedCount = selectedPaths.size,
                            isInsideArchive = browsingArchiveFile != null,
                            oppositePaneShortLabel = oppositeShortLabel,
                            oppositePaneDirName = oppositeDirName,
                            onDirectCopyToOpposite = if (isSplitView) {
                                {
                                    val chosen = directoryItems.filter { it.selectionKey in selectedPaths }
                                    launchDirectTransferToOppositePane(chosen, ClipboardTransferOperation.COPY)
                                }
                            } else null,
                            onDirectMoveToOpposite = if (isSplitView && browsingArchiveFile == null) {
                                {
                                    val chosen = directoryItems.filter { it.selectionKey in selectedPaths }
                                    launchDirectTransferToOppositePane(chosen, ClipboardTransferOperation.CUT)
                                }
                            } else null,
                            onCopy = {
                                haptics.confirm()
                                if (browsingArchiveFile != null) {
                                    val entryPaths = directoryItems
                                        .filter { it.selectionKey in selectedPaths }
                                        .mapNotNull { it.archiveEntryPath }
                                    if (entryPaths.isNotEmpty()) {
                                        fileClipboard = FileManagerClipboardState(
                                            files = emptyList(),
                                            operation = ClipboardTransferOperation.COPY,
                                            sourceArchiveFile = browsingArchiveFile,
                                            archiveEntryPaths = entryPaths
                                        )
                                        selectedPaths = emptySet()
                                        ApkPackageInstaller.showToastOnMain(
                                            context,
                                            "Copied ${entryPaths.size} item(s) from inside ${browsingArchiveFile.name}. Exit archive or pick any folder and tap Paste!"
                                        )
                                    }
                                } else {
                                    val files = directoryItems
                                        .filter { it.selectionKey in selectedPaths }
                                        .map { it.file }
                                    if (files.isNotEmpty()) {
                                        fileClipboard = FileManagerClipboardState(files, ClipboardTransferOperation.COPY)
                                        selectedPaths = emptySet()
                                        ApkPackageInstaller.showToastOnMain(
                                            context,
                                            "${files.size} item(s) copied to clipboard. Navigate to destination and tap Paste."
                                        )
                                    }
                                }
                            },
                            onCut = {
                                if (browsingArchiveFile != null) {
                                    ApkPackageInstaller.showToastOnMain(
                                        context,
                                        "Use Copy to extract selected items directly from inside compressed archive"
                                    )
                                    return@MultiSelectBottomActionBar
                                }
                                haptics.confirm()
                                val files = directoryItems
                                    .filter { it.selectionKey in selectedPaths }
                                    .map { it.file }
                                if (files.isNotEmpty()) {
                                    fileClipboard = FileManagerClipboardState(files, ClipboardTransferOperation.CUT)
                                    selectedPaths = emptySet()
                                    ApkPackageInstaller.showToastOnMain(
                                        context,
                                        "${files.size} item(s) ready to move. Navigate to destination and tap Paste."
                                    )
                                }
                            },
                            onZip = {
                                if (browsingArchiveFile != null) {
                                    ApkPackageInstaller.showToastOnMain(
                                        context,
                                        "Exit archive view first to compress storage files"
                                    )
                                    return@MultiSelectBottomActionBar
                                }
                                haptics.tap()
                                val files = directoryItems
                                    .filter { it.selectionKey in selectedPaths }
                                    .map { it.file }
                                if (files.isNotEmpty()) {
                                    showCompressZipDialog = files
                                }
                            },
                            onBatchRename = {
                                if (browsingArchiveFile != null) return@MultiSelectBottomActionBar
                                haptics.tap()
                                val files = directoryItems
                                    .filter { it.selectionKey in selectedPaths }
                                    .map { it.file }
                                if (files.size == 1) {
                                    renameTargetItem = directoryItems.firstOrNull { it.selectionKey in selectedPaths }
                                } else if (files.isNotEmpty()) {
                                    showBatchRenameDialog = files
                                }
                            },
                            onShare = {
                                if (browsingArchiveFile != null) return@MultiSelectBottomActionBar
                                haptics.tap()
                                val selectedItems = directoryItems.filter {
                                    it.selectionKey in selectedPaths && !it.isDirectory
                                }
                                if (selectedItems.isNotEmpty()) {
                                    shareMultipleFiles(context, selectedItems.map { it.file })
                                } else {
                                    ApkPackageInstaller.showToastOnMain(context, "Select at least one file to share")
                                }
                            },
                            onMoveToVault = {
                                if (browsingArchiveFile != null) return@MultiSelectBottomActionBar
                                haptics.confirm()
                                val selectedItems = directoryItems.filter { it.selectionKey in selectedPaths }
                                if (selectedItems.isNotEmpty()) {
                                    requestLockItemsInPrivateVault(selectedItems)
                                }
                            },
                            onDelete = {
                                if (browsingArchiveFile != null) return@MultiSelectBottomActionBar
                                haptics.warning()
                                val selectedItems = directoryItems.filter { it.selectionKey in selectedPaths }
                                if (selectedItems.isNotEmpty()) {
                                    deleteConfirmItems = selectedItems
                                }
                            }
                        )
                    }
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (!isSplitView) {
                    // Quick Root / Storage Volume Switcher + Interactive Path Breadcrumbs
                    StorageRootsAndBreadcrumbsBar(
                        currentDir = currentDir,
                        ddmRootDir = ddmRootDir,
                        publicDownloadsDir = publicDownloadsDir,
                        appPrivateDir = appPrivateDownloadsDir,
                        internalRootDir = internalRootDir,
                        romRootDir = romRootDir,
                        isRootEnabled = isRootModeEnabled,
                        bookmarkedDirs = bookmarkedDirs,
                        onSelectDir = { dir ->
                            haptics.tap()
                            navigateToDirectory(dir, resetCategoryToAll = true)
                        },
                        onCreateFolder = {
                            haptics.tap()
                            if (activeBrowsedArchive != null) {
                                ApkPackageInstaller.showToastOnMain(context, "Exit compressed archive view first to create a folder")
                            } else {
                                showNewFolderDialog = true
                            }
                        },
                        onCreateTextFile = {
                            haptics.tap()
                            if (activeBrowsedArchive != null) {
                                ApkPackageInstaller.showToastOnMain(context, "Exit compressed archive view first to create a file")
                            } else {
                                showNewTextFileDialog = true
                            }
                        },
                        onRefresh = {
                            haptics.tap()
                            DragonFileManagerTaskEngine.invalidateDirectorySnapshotCache(currentDir.absolutePath)
                            refreshTrigger++
                            ApkPackageInstaller.showToastOnMain(context, "Refreshed directory")
                        }
                    )

                    // Virtual In-Archive Browser Banner when viewing inside a compressed file
                    activeBrowsedArchive?.let { archiveFile ->
                        InArchiveVirtualBrowserBanner(
                            archiveFile = archiveFile,
                            virtualPath = activeArchiveVirtualPath,
                            onNavigateUpInArchive = {
                                haptics.tap()
                                if (activeArchiveVirtualPath.isNotEmpty()) {
                                    activeArchiveVirtualPath = activeArchiveVirtualPath
                                        .trimEnd('/')
                                        .substringBeforeLast('/', "")
                                } else if (nestedArchiveBackStack.isNotEmpty()) {
                                    val (parentArchive, parentVirtualPath) = nestedArchiveBackStack.last()
                                    nestedArchiveBackStack = nestedArchiveBackStack.dropLast(1)
                                    activeBrowsedArchive = parentArchive
                                    activeArchiveVirtualPath = parentVirtualPath
                                } else {
                                    activeBrowsedArchive = null
                                    activeArchiveVirtualPath = ""
                                }
                                selectedPaths = emptySet()
                            },
                            onExitArchive = {
                                haptics.tap()
                                nestedArchiveBackStack = emptyList()
                                activeBrowsedArchive = null
                                activeArchiveVirtualPath = ""
                                selectedPaths = emptySet()
                            },
                            onDecompressEntireArchive = {
                                haptics.confirm()
                                archivePreviewItem = FileItemEntry(
                                    file = archiveFile,
                                    name = archiveFile.name,
                                    isDirectory = false,
                                    lengthBytes = archiveFile.length(),
                                    lastModifiedMs = archiveFile.lastModified(),
                                    extension = UniversalArchiveEngine.detectFormatLabel(archiveFile).lowercase(Locale.US)
                                )
                            }
                        )
                    }

                    // Smart Category Filter Bar
                    CategoryFilterChipsRow(
                        selectedCategory = selectedCategory,
                        onSelectCategory = { cat ->
                            haptics.tap()
                            selectedCategory = cat
                        }
                    )
                }

                // 1-Tap Full Internal Storage Access Banner (when neither All Files Access nor Root is active)
                if (!hasFullStorageAccess && !isRootModeEnabled) {
                    Surface(
                        color = WarningOrange.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, WarningOrange.copy(alpha = 0.55f)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 4.dp)
                            .testTag("file_manager_full_storage_access_banner")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.FolderSpecial,
                                contentDescription = "Full Internal Storage Access",
                                tint = WarningOrange,
                                modifier = Modifier.size(20.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Full Internal Storage Access (No Root Required)",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = WarningOrange
                                )
                                Text(
                                    text = "Grant All Files Access to read, write, copy, move & manage all folders in Internal Storage.",
                                    fontSize = 9.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Button(
                                onClick = {
                                    haptics.confirm()
                                    requestFullInternalStorageAccess()
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = WarningOrange,
                                    contentColor = Color.Black
                                ),
                                shape = RoundedCornerShape(9.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .height(32.dp)
                                    .testTag("grant_full_storage_access_btn")
                            ) {
                                Text("Grant Access", fontSize = 10.5.sp, fontWeight = FontWeight.ExtraBold)
                            }
                        }
                    }
                }

                // Non-Root Android/data & Android/obb SAF + Root Unlock Banner
                val isBrowsingDataOrObb = remember(currentDir.absolutePath) {
                    AndroidDataObbBridge.isAndroidDataOrObbPath(currentDir.absolutePath)
                }
                val hasSafForCurrentDir = remember(currentDir.absolutePath, safTreeGrantRevision) {
                    AndroidDataObbBridge.hasSafTreeGrantForDirectory(context, currentDir)
                }
                if (isBrowsingDataOrObb && !isRootModeEnabled && !hasSafForCurrentDir) {
                    Surface(
                        color = NeonCyan.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.45f)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 4.dp)
                            .testTag("file_manager_saf_data_obb_banner")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.LockOpen,
                                contentDescription = "Unlock Android/data & obb",
                                tint = NeonCyan,
                                modifier = Modifier.size(18.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Unlock ${currentDir.name} (Without Root or With Root)",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = NeonCyan
                                )
                                Text(
                                    text = "Use Non-Root SAF Folder Grant or enable Root Mode for full read/write access.",
                                    fontSize = 9.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            FilledTonalButton(
                                onClick = {
                                    haptics.confirm()
                                    requestNonRootSafFolderGrant(currentDir)
                                },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 9.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .height(30.dp)
                                    .testTag("saf_grant_data_obb_btn")
                            ) {
                                Text("No-Root Grant", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // Live Background Task & Persistent Material 3 Notification Progress Banner
                val activeTask = runningEngineTask
                if (activeTask != null || busyOperationLabel != null) {
                    val isPaused = activeTask?.isPaused == true
                    val bannerAccent = if (isPaused) WarningOrange else NeonCyan
                    Surface(
                        color = bannerAccent.copy(alpha = 0.13f),
                        border = BorderStroke(1.dp, bannerAccent.copy(alpha = 0.45f)),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 4.dp)
                            .testTag("file_manager_active_task_banner")
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                if (isPaused) {
                                    Icon(
                                        imageVector = Icons.Default.PauseCircle,
                                        contentDescription = "Task paused",
                                        tint = WarningOrange,
                                        modifier = Modifier.size(18.dp)
                                    )
                                } else {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = NeonCyan
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = activeTask?.title?.removePrefix("File Manager • ")
                                            ?: busyOperationLabel.orEmpty(),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = bannerAccent,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (activeTask != null) {
                                        val byteInfo = if (activeTask.totalBytes > 0L) {
                                            " • ${com.example.data.model.formatBytes(activeTask.processedBytes)} / ${com.example.data.model.formatBytes(activeTask.totalBytes)}"
                                        } else if (activeTask.processedBytes > 0L) {
                                            " • ${com.example.data.model.formatBytes(activeTask.processedBytes)}"
                                        } else ""
                                        val statusLabel = if (isPaused) "Paused" else "Running"
                                        Text(
                                            text = "$statusLabel • ${activeTask.currentFileName.ifBlank { activeTask.subtitle }}$byteInfo",
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                if (activeTask != null) {
                                    Surface(
                                        color = bannerAccent.copy(alpha = 0.18f),
                                        shape = RoundedCornerShape(999.dp)
                                    ) {
                                        Text(
                                            text = when {
                                                isPaused && !activeTask.isIndeterminate -> "PAUSED ${activeTask.progressPercent}%"
                                                isPaused -> "PAUSED"
                                                !activeTask.isIndeterminate -> "${activeTask.progressPercent}%"
                                                else -> "ACTIVE"
                                            },
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = bannerAccent,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                            }
                            if (activeTask != null) {
                                if (!activeTask.isIndeterminate) {
                                    LinearProgressIndicator(
                                        progress = { (activeTask.progressPercent / 100f).coerceIn(0f, 1f) },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(4.dp)
                                            .clip(RoundedCornerShape(2.dp)),
                                        color = bannerAccent,
                                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                                    )
                                } else if (!isPaused) {
                                    LinearProgressIndicator(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(4.dp)
                                            .clip(RoundedCornerShape(2.dp)),
                                        color = bannerAccent,
                                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                                    )
                                }

                                // Pause / Resume & Cancel controls
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    FilledTonalButton(
                                        onClick = {
                                            haptics.tap()
                                            DragonFileManagerTaskEngine.togglePauseActiveTask(context)
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.filledTonalButtonColors(
                                            containerColor = bannerAccent.copy(alpha = 0.22f),
                                            contentColor = bannerAccent
                                        ),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .heightIn(min = 36.dp)
                                            .testTag("file_manager_task_pause_resume_button")
                                    ) {
                                        Icon(
                                            imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                            contentDescription = if (isPaused) "Resume task" else "Pause task",
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(5.dp))
                                        Text(
                                            text = if (isPaused) "Resume" else "Pause",
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    FilledTonalButton(
                                        onClick = {
                                            haptics.tap()
                                            DragonFileManagerTaskEngine.cancelActiveTask(context)
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.filledTonalButtonColors(
                                            containerColor = DangerRed.copy(alpha = 0.20f),
                                            contentColor = DangerRed
                                        ),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .heightIn(min = 36.dp)
                                            .testTag("file_manager_task_cancel_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Cancel task",
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(5.dp))
                                        Text(
                                            text = "Cancel",
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Main File & Folder Content (All 6 View Styles including MT-Style Dual-Pane Split View)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    if (viewStyle == FileManagerViewStyle.SPLIT_DUAL_PANE) {
                        val activeSnapshot = snapshotActivePane()
                        val leftState = if (activeSplitPane == 0) activeSnapshot else inactivePaneState
                        val rightState = if (activeSplitPane == 1) activeSnapshot else inactivePaneState
                        val leftItems = if (activeSplitPane == 0) directoryItems else inactivePaneItems
                        val rightItems = if (activeSplitPane == 1) directoryItems else inactivePaneItems
                        val leftLoading = if (activeSplitPane == 0) isLoadingDir else isLoadingInactivePane
                        val rightLoading = if (activeSplitPane == 1) isLoadingDir else isLoadingInactivePane

                        DragonDualPaneSplitView(
                            activePaneIndex = activeSplitPane,
                            leftState = leftState,
                            rightState = rightState,
                            leftItems = leftItems,
                            rightItems = rightItems,
                            leftLoading = leftLoading,
                            rightLoading = rightLoading,
                            clipboard = fileClipboard,
                            internalRootDir = internalRootDir,
                            romRootDir = romRootDir,
                            ddmRootDir = ddmRootDir,
                            downloadsDir = publicDownloadsDir,
                            androidDataDir = androidDataDir,
                            androidObbDir = androidObbDir,
                            onActivatePane = { paneIndex ->
                                haptics.tap()
                                switchActiveSplitPane(paneIndex)
                            },
                            onSwapPanes = {
                                haptics.tap()
                                activeSplitPane = if (activeSplitPane == 0) 1 else 0
                            },
                            onSyncActivePathToOpposite = {
                                haptics.confirm()
                                inactivePaneState = inactivePaneState.copy(
                                    currentDir = currentDir,
                                    activeBrowsedArchive = activeBrowsedArchive,
                                    activeArchiveVirtualPath = activeArchiveVirtualPath,
                                    selectedPaths = emptySet()
                                )
                                inactivePaneItems = directoryItems
                                isLoadingInactivePane = false
                                ApkPackageInstaller.showToastOnMain(
                                    context,
                                    "Synced ${currentDir.name.ifBlank { "/" }} to both split panes"
                                )
                            },
                            onPaneNavigateDir = { paneIndex, targetDir ->
                                haptics.tap()
                                switchActiveSplitPane(paneIndex)
                                navigateToDirectory(targetDir, resetCategoryToAll = true)
                            },
                            onPaneNavigateUp = { paneIndex ->
                                haptics.tap()
                                switchActiveSplitPane(paneIndex)
                                if (activeBrowsedArchive != null) {
                                    if (activeArchiveVirtualPath.isNotEmpty()) {
                                        activeArchiveVirtualPath = activeArchiveVirtualPath
                                            .trimEnd('/')
                                            .substringBeforeLast('/', "")
                                    } else {
                                        activeBrowsedArchive = null
                                        activeArchiveVirtualPath = ""
                                    }
                                    selectedPaths = emptySet()
                                } else {
                                    currentDir.parentFile?.let { parent ->
                                        navigateToDirectory(parent)
                                    }
                                }
                            },
                            onPaneExitArchive = { paneIndex ->
                                haptics.tap()
                                switchActiveSplitPane(paneIndex)
                                activeBrowsedArchive = null
                                activeArchiveVirtualPath = ""
                                selectedPaths = emptySet()
                            },
                            onPaneItemClick = { paneIndex, item ->
                                if (paneIndex != activeSplitPane) {
                                    haptics.tap()
                                    switchActiveSplitPane(paneIndex)
                                } else {
                                    handleItemPrimaryClick(item)
                                }
                            },
                            onPaneItemLongClick = { paneIndex, item ->
                                haptics.confirm()
                                switchActiveSplitPane(paneIndex)
                                activeActionItem = item
                            },
                            onPaneToggleSelect = { paneIndex, item ->
                                haptics.tap()
                                switchActiveSplitPane(paneIndex)
                                val currentSel = selectedPaths
                                selectedPaths = if (item.selectionKey in currentSel) {
                                    currentSel - item.selectionKey
                                } else {
                                    currentSel + item.selectionKey
                                }
                            },
                            onPanePasteHere = { paneIndex ->
                                val clip = fileClipboard ?: return@DragonDualPaneSplitView
                                haptics.confirm()
                                switchActiveSplitPane(paneIndex)
                                if (activeBrowsedArchive != null) {
                                    activeBrowsedArchive = null
                                    activeArchiveVirtualPath = ""
                                }
                                val targetSnapshot = currentDir
                                DragonFileManagerTaskEngine.launchClipboardPasteTask(
                                    context = context,
                                    clip = clip,
                                    targetDir = targetSnapshot,
                                    onCompleted = { count ->
                                        if (count > 0) {
                                            fileClipboard = null
                                            selectedPaths = emptySet()
                                            DragonFileManagerTaskEngine.invalidateDirectorySnapshotCache(targetSnapshot.absolutePath)
                                            DragonFileManagerTaskEngine.invalidateDirectorySnapshotCache(inactivePaneState.currentDir.absolutePath)
                                            refreshTrigger++
                                        }
                                    }
                                )
                            },
                            onPaneCreateFolder = { paneIndex ->
                                haptics.tap()
                                switchActiveSplitPane(paneIndex)
                                if (activeBrowsedArchive != null) {
                                    ApkPackageInstaller.showToastOnMain(context, "Exit compressed archive view first to create a folder")
                                } else {
                                    showNewFolderDialog = true
                                }
                            },
                            onPaneCreateFile = { paneIndex ->
                                haptics.tap()
                                switchActiveSplitPane(paneIndex)
                                if (activeBrowsedArchive != null) {
                                    ApkPackageInstaller.showToastOnMain(context, "Exit compressed archive view first to create a file")
                                } else {
                                    showNewTextFileDialog = true
                                }
                            },
                            onPaneSelectAll = { paneIndex ->
                                haptics.tap()
                                switchActiveSplitPane(paneIndex)
                                selectedPaths = if (selectedPaths.size == directoryItems.size && directoryItems.isNotEmpty()) {
                                    emptySet()
                                } else {
                                    directoryItems.map { it.selectionKey }.toSet()
                                }
                            },
                            onPaneOpenSort = { paneIndex ->
                                haptics.tap()
                                switchActiveSplitPane(paneIndex)
                                showSortMenu = true
                            },
                            onPaneRefresh = { paneIndex ->
                                haptics.tap()
                                switchActiveSplitPane(paneIndex)
                                DragonFileManagerTaskEngine.invalidateDirectorySnapshotCache(currentDir.absolutePath)
                                refreshTrigger++
                            }
                        )
                    } else if (directoryItems.isEmpty() && !isLoadingDir) {
                        EmptyDirectoryPlaceholder(
                            searchQuery = searchQuery,
                            selectedCategory = selectedCategory,
                            currentDir = currentDir,
                            onCreateFolder = { showNewFolderDialog = true },
                            onCreateFile = { showNewTextFileDialog = true },
                            onResetFilters = {
                                searchQuery = ""
                                selectedCategory = FileManagerFilterCategory.ALL
                            }
                        )
                    } else {
                        when (viewStyle) {
                            FileManagerViewStyle.GRID_2 -> {
                                LazyVerticalGrid(
                                    columns = GridCells.Adaptive(minSize = 152.dp),
                                    contentPadding = PaddingValues(14.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .testTag("file_manager_grid")
                                ) {
                                    items(directoryItems, key = { it.selectionKey }) { item ->
                                        val isSelected = item.selectionKey in selectedPaths
                                        FileGridItemCard(
                                            item = item,
                                            isSelected = isSelected,
                                            onClick = { handleItemPrimaryClick(item) },
                                            onLongClick = {
                                                haptics.confirm()
                                                if (!isSelectionMode) {
                                                    activeActionItem = item
                                                } else {
                                                    selectedPaths = if (isSelected) {
                                                        selectedPaths - item.selectionKey
                                                    } else {
                                                        selectedPaths + item.selectionKey
                                                    }
                                                }
                                            },
                                            onSelectToggle = {
                                                haptics.tap()
                                                selectedPaths = if (isSelected) {
                                                    selectedPaths - item.selectionKey
                                                } else {
                                                    selectedPaths + item.selectionKey
                                                }
                                            },
                                            onOpenMenu = {
                                                haptics.tap()
                                                activeActionItem = item
                                            }
                                        )
                                    }
                                }
                            }
                            FileManagerViewStyle.GRID_3 -> {
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(3),
                                    contentPadding = PaddingValues(10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .testTag("file_manager_grid_3")
                                ) {
                                    items(directoryItems, key = { it.selectionKey }) { item ->
                                        val isSelected = item.selectionKey in selectedPaths
                                        CompactGridFileTile(
                                            item = item,
                                            isSelected = isSelected,
                                            context = context,
                                            onClick = { handleItemPrimaryClick(item) },
                                            onLongClick = {
                                                haptics.confirm()
                                                activeActionItem = item
                                            },
                                            onSelectToggle = {
                                                haptics.tap()
                                                selectedPaths = if (isSelected) {
                                                    selectedPaths - item.selectionKey
                                                } else {
                                                    selectedPaths + item.selectionKey
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                            FileManagerViewStyle.COMPACT_LIST -> {
                                LazyColumn(
                                    contentPadding = PaddingValues(vertical = 4.dp),
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .testTag("file_manager_compact_list")
                                ) {
                                    items(directoryItems, key = { it.selectionKey }) { item ->
                                        val isSelected = item.selectionKey in selectedPaths
                                        CompactFileListRow(
                                            item = item,
                                            isSelected = isSelected,
                                            onClick = { handleItemPrimaryClick(item) },
                                            onLongClick = {
                                                haptics.confirm()
                                                activeActionItem = item
                                            },
                                            onSelectToggle = {
                                                haptics.tap()
                                                selectedPaths = if (isSelected) {
                                                    selectedPaths - item.selectionKey
                                                } else {
                                                    selectedPaths + item.selectionKey
                                                }
                                            },
                                            onOpenMenu = {
                                                haptics.tap()
                                                activeActionItem = item
                                            }
                                        )
                                        HorizontalDivider(
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.22f),
                                            modifier = Modifier.padding(start = 56.dp)
                                        )
                                    }
                                }
                            }
                            FileManagerViewStyle.GALLERY_CARDS -> {
                                LazyColumn(
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(9.dp),
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .testTag("file_manager_gallery_cards")
                                ) {
                                    items(directoryItems, key = { it.selectionKey }) { item ->
                                        val isSelected = item.selectionKey in selectedPaths
                                        GalleryCardFileRow(
                                            item = item,
                                            isSelected = isSelected,
                                            context = context,
                                            onClick = { handleItemPrimaryClick(item) },
                                            onLongClick = {
                                                haptics.confirm()
                                                activeActionItem = item
                                            },
                                            onSelectToggle = {
                                                haptics.tap()
                                                selectedPaths = if (isSelected) {
                                                    selectedPaths - item.selectionKey
                                                } else {
                                                    selectedPaths + item.selectionKey
                                                }
                                            },
                                            onOpenMenu = {
                                                haptics.tap()
                                                activeActionItem = item
                                            }
                                        )
                                    }
                                }
                            }
                            else -> {
                                LazyColumn(
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .testTag("file_manager_list")
                                ) {
                                    items(directoryItems, key = { it.selectionKey }) { item ->
                                        val isSelected = item.selectionKey in selectedPaths
                                        FileListRowCard(
                                            item = item,
                                            isSelected = isSelected,
                                            context = context,
                                            onClick = { handleItemPrimaryClick(item) },
                                            onLongClick = {
                                                haptics.confirm()
                                                activeActionItem = item
                                            },
                                            onSelectToggle = {
                                                haptics.tap()
                                                selectedPaths = if (isSelected) {
                                                    selectedPaths - item.selectionKey
                                                } else {
                                                    selectedPaths + item.selectionKey
                                                }
                                            },
                                            onQuickInstallApk = { forceSign ->
                                                haptics.confirm()
                                                val task = item.linkedTask
                                                if (task != null) {
                                                    downloadManager.installOrUpdatePackage(context, task, forceSign = forceSign)
                                                } else {
                                                    DragonFileManagerTaskEngine.launchStandalonePackageInstallTask(
                                                        context = context,
                                                        file = item.file,
                                                        forceSign = forceSign
                                                    )
                                                }
                                            },
                                            onQuickExtractZip = {
                                                haptics.tap()
                                                val browsingArchive = activeBrowsedArchive
                                                if (browsingArchive != null && item.archiveEntryPath != null) {
                                                    if (UniversalArchiveEngine.isSupportedArchiveFileName(item.name)) {
                                                        scope.launch {
                                                            busyOperationLabel = "Inspecting ${item.name}..."
                                                            val previewFile = withContext(Dispatchers.IO) {
                                                                UniversalArchiveEngine.extractSingleEntryToPreviewCache(
                                                                    context = context,
                                                                    archiveFile = browsingArchive,
                                                                    entryPath = item.archiveEntryPath
                                                                )
                                                            }
                                                            busyOperationLabel = null
                                                            if (previewFile != null && previewFile.exists()) {
                                                                archivePreviewItem = item.copy(file = previewFile, archiveEntryPath = null)
                                                            }
                                                        }
                                                    } else {
                                                        DragonFileManagerTaskEngine.launchExtractArchiveTask(
                                                            context = context,
                                                            archiveFile = browsingArchive,
                                                            targetDir = currentDir,
                                                            selectedEntryPaths = listOf(item.archiveEntryPath)
                                                        )
                                                    }
                                                } else {
                                                    archivePreviewItem = item
                                                }
                                            },
                                            onOpenMenu = {
                                                haptics.tap()
                                                activeActionItem = item
                                            }
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

    // View Style Picker Dialog (6 Modes including Dual-Pane Split View)
    if (showViewStyleDialog) {
        FileManagerViewStyleDialog(
            currentStyle = viewStyle,
            onSelectStyle = { chosenStyle ->
                haptics.confirm()
                viewStyle = chosenStyle
                showViewStyleDialog = false
            },
            onDismiss = { showViewStyleDialog = false }
        )
    }

    // Sort Options Dialog
    if (showSortMenu) {
        FileManagerSortDialog(
            currentField = sortField,
            isAscending = sortAscending,
            onSelect = { field, asc ->
                sortField = field
                sortAscending = asc
                showSortMenu = false
            },
            onDismiss = { showSortMenu = false }
        )
    }

    // Top-right Advanced Options Dialog
    if (showMoreOptionsMenu) {
        var isAppDrawerShortcutEnabled by remember {
            mutableStateOf(DragonArchiverLauncherActivity.isAppDrawerShortcutEnabled(context))
        }
        val isFloatingActive by DragonFloatingArchiverService.isFloatingActiveFlow.collectAsState()

        val totalTrashBytes = remember(trashItems) { trashItems.sumOf { it.sizeBytes } }
        val totalVaultBytes = remember(vaultItems) { vaultItems.sumOf { it.sizeBytes } }
        FileManagerAdvancedMenuDialog(
            showHiddenFiles = showHiddenFiles,
            isRootEnabled = isRootModeEnabled,
            hasFullStorageAccess = hasFullStorageAccess,
            isFloatingActive = isFloatingActive,
            isAppDrawerShortcutEnabled = isAppDrawerShortcutEnabled,
            vaultItemCount = vaultItems.size,
            vaultTotalBytes = totalVaultBytes,
            isVaultUnlocked = isVaultUnlocked,
            trashItemCount = trashItems.size,
            trashTotalBytes = totalTrashBytes,
            onReturnedToDdm = {
                haptics.tap()
                showMoreOptionsMenu = false
                onClose()
            },
            onExit = {
                haptics.warning()
                showMoreOptionsMenu = false
                if (onExit != null) {
                    onExit()
                } else {
                    onClose()
                }
            },
            onFloatWindow = {
                haptics.confirm()
                showMoreOptionsMenu = false
                if (DragonFloatingArchiverService.canDrawOverlays(context)) {
                    DragonFloatingArchiverService.startFloating(context, currentDir.absolutePath)
                    onClose()
                } else {
                    ApkPackageInstaller.showToastOnMain(
                        context,
                        "Allow 'Display over other apps' permission, then tap Float Dragon Archiver again."
                    )
                    DragonFloatingArchiverService.openOverlayPermissionSettings(context)
                }
            },
            onRequestFullStorageAccess = {
                haptics.confirm()
                showMoreOptionsMenu = false
                requestFullInternalStorageAccess()
            },
            onToggleRootAccess = {
                haptics.confirm()
                showMoreOptionsMenu = false
                scope.launch {
                    val targetEnable = !isRootModeEnabled
                    val granted = withContext(Dispatchers.IO) {
                        AndroidDataObbBridge.toggleOrRequestRootInFileManager(context, targetEnable)
                    }
                    repository.checkRoot()
                    isRootModeEnabled = granted
                    hasFullStorageAccess = AndroidDataObbBridge.hasFullInternalStorageAccess(context)
                    refreshTrigger++
                    if (targetEnable) {
                        if (granted) {
                            ApkPackageInstaller.showToastOnMain(
                                context,
                                "Root Access Enabled! Full Internal Storage, Android/data & Android/obb permissions granted."
                            )
                        } else {
                            ApkPackageInstaller.showToastOnMain(
                                context,
                                "Root not granted. Using Non-Root Full Internal Storage Access mode."
                            )
                        }
                    } else {
                        ApkPackageInstaller.showToastOnMain(
                            context,
                            "Root Mode disabled • Non-Root Internal Storage access remains active"
                        )
                    }
                }
            },
            onToggleHiddenFiles = {
                showHiddenFiles = !showHiddenFiles
                showMoreOptionsMenu = false
            },
            onOpenPrivateVault = {
                haptics.tap()
                showMoreOptionsMenu = false
                showPrivateVaultDialog = true
            },
            onOpenTrashBin = {
                haptics.tap()
                showMoreOptionsMenu = false
                showTrashBinDialog = true
            },
            onCreateNewFolder = {
                showMoreOptionsMenu = false
                showNewFolderDialog = true
            },
            onCreateNewTextFile = {
                showMoreOptionsMenu = false
                showNewTextFileDialog = true
            },
            onSelectAll = {
                showMoreOptionsMenu = false
                selectedPaths = directoryItems.map { it.selectionKey }.toSet()
            },
            onOpenApkExtractor = {
                haptics.tap()
                showMoreOptionsMenu = false
                showApkExtractorDialog = true
            },
            onOpenApkEditor = {
                haptics.tap()
                showMoreOptionsMenu = false
                apkEditorInitialFile = null
                showApkEditorStudio = true
            },
            onOpenSystemFilesApp = {
                showMoreOptionsMenu = false
                onOpenInSystemFileManager()
            },
            onToggleAppDrawerShortcut = { enabled ->
                haptics.confirm()
                val updated = DragonArchiverLauncherActivity.setAppDrawerShortcutEnabled(context, enabled)
                isAppDrawerShortcutEnabled = updated
                ApkPackageInstaller.showToastOnMain(
                    context,
                    if (updated) "Dragon Archiver shortcut added to your App Drawer!"
                    else "Dragon Archiver shortcut removed from App Drawer"
                )
            },
            onDismiss = { showMoreOptionsMenu = false }
        )
    }

    // APK Extractor Studio (Extracts installed apps into DDM/Extracted Apk)
    if (showApkExtractorDialog) {
        DragonApkExtractorDialog(
            context = context,
            ddmRootDir = ddmRootDir,
            onDismiss = {
                showApkExtractorDialog = false
                refreshTrigger++
            },
            onOpenExtractedFolder = { extractedFolder ->
                showApkExtractorDialog = false
                DragonFileManagerTaskEngine.invalidateDirectorySnapshotCache(extractedFolder.absolutePath)
                navigateToDirectory(extractedFolder)
                refreshTrigger++
            },
            onEditApkInStudio = { apkFile ->
                showApkExtractorDialog = false
                apkEditorInitialFile = apkFile
                showApkEditorStudio = true
            },
            onShareFile = { file ->
                shareMultipleFiles(context, listOf(file))
            }
        )
    }

    // Professional APK Editor Studio (Installed, Downloaded & Stored APKs)
    if (showApkEditorStudio) {
        DragonApkEditorStudioDialog(
            context = context,
            ddmRootDir = ddmRootDir,
            initialApkFile = apkEditorInitialFile,
            onDismiss = {
                showApkEditorStudio = false
                apkEditorInitialFile = null
                refreshTrigger++
            },
            onInstallBuiltApk = { builtApk ->
                DragonFileManagerTaskEngine.launchStandalonePackageInstallTask(
                    context = context,
                    file = builtApk,
                    forceSign = false
                )
            },
            onOpenOutputFolder = { outFolder ->
                showApkEditorStudio = false
                apkEditorInitialFile = null
                DragonFileManagerTaskEngine.invalidateDirectorySnapshotCache(outFolder.absolutePath)
                navigateToDirectory(outFolder)
                refreshTrigger++
            },
            onShareBuiltApk = { builtApk ->
                shareMultipleFiles(context, listOf(builtApk))
            }
        )
    }

    // Create New Folder Dialog
    if (showNewFolderDialog) {
        CreateFolderOrFileDialog(
            title = "Create New Folder",
            placeholder = "Folder name (e.g. Modded APKs)",
            initialValue = "",
            confirmLabel = "Create Folder",
            onConfirm = { folderName ->
                showNewFolderDialog = false
                val clean = folderName.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
                if (clean.isNotBlank()) {
                    val target = File(currentDir, clean)
                    if (AndroidDataObbBridge.createDirectory(context, target)) {
                        DragonFileManagerTaskEngine.invalidateDirectorySnapshotCache(currentDir.absolutePath)
                        refreshTrigger++
                        ApkPackageInstaller.showToastOnMain(context, "Created folder: $clean")
                    } else if (!hasFullStorageAccess && !isRootModeEnabled) {
                        ApkPackageInstaller.showToastOnMain(
                            context,
                            "Grant Full Internal Storage Access to create folders in ${currentDir.name}"
                        )
                        requestFullInternalStorageAccess()
                    } else if (AndroidDataObbBridge.isAndroidDataOrObbPath(currentDir.absolutePath)) {
                        ApkPackageInstaller.showToastOnMain(
                            context,
                            "Select ${currentDir.name} in the system folder picker for Non-Root access, or enable Root"
                        )
                        requestNonRootSafFolderGrant(currentDir)
                    } else {
                        ApkPackageInstaller.showToastOnMain(context, "Could not create folder in this path")
                    }
                }
            },
            onDismiss = { showNewFolderDialog = false }
        )
    }

    // Create New File Dialog (supports .txt, .pdf, .xml, .json, .sav, .docx, .md, .csv, etc.)
    if (showNewTextFileDialog) {
        CreateFolderOrFileDialog(
            title = "Create New Document / File",
            placeholder = "e.g. notes.txt, doc.pdf, config.xml, save.sav, report.docx",
            initialValue = "notes.txt",
            confirmLabel = "Create & Open",
            onConfirm = { fileName ->
                showNewTextFileDialog = false
                val clean = fileName.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
                if (clean.isNotBlank()) {
                    val target = File(currentDir, clean)
                    val ext = clean.substringAfterLast('.', "").lowercase(Locale.US)
                    scope.launch {
                        val created = withContext(Dispatchers.IO) {
                            when (ext) {
                                "pdf" -> DragonDocumentEngine.createPdfFromPlainText(
                                    context = context,
                                    outputPdfFile = target,
                                    title = clean.substringBeforeLast('.'),
                                    bodyText = ""
                                )
                                "docx" -> DragonDocumentEngine.saveDocxPlainText(
                                    context = context,
                                    targetFile = target,
                                    updatedText = ""
                                )
                                else -> runCatching {
                                    val starter = when (ext) {
                                        "xml" -> "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<root>\n</root>\n"
                                        "json" -> "{\n  \n}\n"
                                        "html", "htm" -> "<!DOCTYPE html>\n<html>\n<head><title>Document</title></head>\n<body>\n</body>\n</html>\n"
                                        else -> ""
                                    }
                                    val out = AndroidDataObbBridge.openOutputStreamForFile(context, target, "text/plain")
                                    if (out != null) {
                                        out.use {
                                            if (starter.isNotEmpty()) {
                                                it.write(starter.toByteArray(Charsets.UTF_8))
                                            }
                                            it.flush()
                                        }
                                        true
                                    } else {
                                        false
                                    }
                                }.getOrDefault(false)
                            }
                        }
                        if (created) {
                            DragonFileManagerTaskEngine.invalidateDirectorySnapshotCache(currentDir.absolutePath)
                            refreshTrigger++
                            ApkPackageInstaller.showToastOnMain(context, "Created file: $clean")
                            if (DragonDocumentEngine.isSupportedDocumentExtension(ext)) {
                                textEditorForceHex = false
                                textEditorItem = FileItemEntry(
                                    file = target,
                                    name = target.name,
                                    isDirectory = false,
                                    lengthBytes = target.length(),
                                    lastModifiedMs = System.currentTimeMillis(),
                                    extension = ext,
                                    childCount = 0,
                                    linkedTask = null
                                )
                            }
                        } else if (!hasFullStorageAccess && !isRootModeEnabled) {
                            ApkPackageInstaller.showToastOnMain(
                                context,
                                "Grant Full Internal Storage Access to create files in ${currentDir.name}"
                            )
                            requestFullInternalStorageAccess()
                        } else if (AndroidDataObbBridge.isAndroidDataOrObbPath(currentDir.absolutePath)) {
                            ApkPackageInstaller.showToastOnMain(
                                context,
                                "Select ${currentDir.name} in the system folder picker for Non-Root access, or enable Root"
                            )
                            requestNonRootSafFolderGrant(currentDir)
                        } else {
                            ApkPackageInstaller.showToastOnMain(context, "Could not create file")
                        }
                    }
                }
            },
            onDismiss = { showNewTextFileDialog = false }
        )
    }

    // Single Item Pro Context Action Sheet
    activeActionItem?.let { item ->
        val browsingArchiveFile = activeBrowsedArchive
        val oppositePaneName = if (activeSplitPane == 0) "Right Pane" else "Left Pane"
        val oppositeFolderName = inactivePaneState.currentDir.name.ifBlank { "/" }
        FileItemProActionDialog(
            item = item,
            context = context,
            isInsideArchive = browsingArchiveFile != null,
            oppositePaneTargetLabel = if (isSplitView) "$oppositePaneName ($oppositeFolderName)" else null,
            onDirectCopyToOpposite = if (isSplitView) {
                {
                    activeActionItem = null
                    launchDirectTransferToOppositePane(listOf(item), ClipboardTransferOperation.COPY)
                }
            } else null,
            onDirectMoveToOpposite = if (isSplitView && browsingArchiveFile == null) {
                {
                    activeActionItem = null
                    launchDirectTransferToOppositePane(listOf(item), ClipboardTransferOperation.CUT)
                }
            } else null,
            onDismiss = { activeActionItem = null },
            onOpen = {
                activeActionItem = null
                if (browsingArchiveFile != null && item.archiveEntryPath != null) {
                    if (item.isDirectory) {
                        activeArchiveVirtualPath = item.archiveEntryPath
                    } else {
                        scope.launch {
                            busyOperationLabel = "Opening ${item.name} from archive..."
                            val previewFile = withContext(Dispatchers.IO) {
                                UniversalArchiveEngine.extractSingleEntryToPreviewCache(
                                    context = context,
                                    archiveFile = browsingArchiveFile,
                                    entryPath = item.archiveEntryPath
                                )
                            }
                            busyOperationLabel = null
                            if (previewFile != null && previewFile.exists()) {
                                val tempEntry = item.copy(file = previewFile, archiveEntryPath = null)
                                if (UniversalArchiveEngine.isSupportedArchiveFileName(item.name) && item.extension != "apk") {
                                    enterCompressedArchiveBrowser(previewFile)
                                } else if (com.example.data.audio.DragonAudioPlayerEngine.isAudioExtension(tempEntry.extension)) {
                                    com.example.data.audio.DragonAudioPlayerEngine.playFile(context, previewFile)
                                } else if (isEditableTextExtension(tempEntry.extension)) {
                                    textEditorForceHex = false
                                    textEditorItem = tempEntry
                                } else {
                                    openFileWithSystemViewer(context, previewFile)
                                }
                            } else {
                                ApkPackageInstaller.showToastOnMain(
                                    context,
                                    "Use Copy or Extract to unpack ${item.name} to a folder"
                                )
                            }
                        }
                    }
                } else if (item.isDirectory) {
                    navigateToDirectory(item.file)
                } else if (UniversalArchiveEngine.isSupportedArchiveFile(item.file) && item.extension != "apk") {
                    enterCompressedArchiveBrowser(item.file)
                } else if (com.example.data.audio.DragonAudioPlayerEngine.isAudioExtension(item.extension)) {
                    val siblingAudios = directoryItems
                        .filter { !it.isDirectory && com.example.data.audio.DragonAudioPlayerEngine.isAudioExtension(it.extension) }
                        .map { it.file }
                    com.example.data.audio.DragonAudioPlayerEngine.playFile(context, item.file, siblingAudios)
                } else if (isEditableTextExtension(item.extension)) {
                    textEditorForceHex = false
                    textEditorItem = item
                } else {
                    openFileWithSystemViewer(context, item.file)
                }
            },
            onBrowseInsideArchive = {
                activeActionItem = null
                if (browsingArchiveFile != null && item.archiveEntryPath != null) {
                    scope.launch {
                        busyOperationLabel = "Opening ${item.name}..."
                        val previewFile = withContext(Dispatchers.IO) {
                            UniversalArchiveEngine.extractSingleEntryToPreviewCache(
                                context = context,
                                archiveFile = browsingArchiveFile,
                                entryPath = item.archiveEntryPath
                            )
                        }
                        busyOperationLabel = null
                        if (previewFile != null && previewFile.exists()) {
                            enterCompressedArchiveBrowser(previewFile)
                        }
                    }
                } else if (UniversalArchiveEngine.isSupportedArchiveFile(item.file)) {
                    enterCompressedArchiveBrowser(item.file)
                }
            },
            onInstallPackage = { forceSign ->
                activeActionItem = null
                if (browsingArchiveFile != null && item.archiveEntryPath != null) {
                    scope.launch {
                        busyOperationLabel = "Preparing package ${item.name} from archive..."
                        val previewFile = withContext(Dispatchers.IO) {
                            UniversalArchiveEngine.extractSingleEntryToPreviewCache(
                                context = context,
                                archiveFile = browsingArchiveFile,
                                entryPath = item.archiveEntryPath
                            )
                        }
                        busyOperationLabel = null
                        if (previewFile != null && previewFile.exists()) {
                            DragonFileManagerTaskEngine.launchStandalonePackageInstallTask(
                                context = context,
                                file = previewFile,
                                forceSign = forceSign
                            )
                        }
                    }
                } else {
                    val task = item.linkedTask
                    if (task != null) {
                        downloadManager.installOrUpdatePackage(context, task, forceSign = forceSign)
                    } else {
                        DragonFileManagerTaskEngine.launchStandalonePackageInstallTask(
                            context = context,
                            file = item.file,
                            forceSign = forceSign
                        )
                    }
                }
            },
            onAutoSignStandaloneApk = {
                activeActionItem = null
                DragonFileManagerTaskEngine.launchStandaloneApkSignTask(
                    context = context,
                    itemFile = item.file,
                    extension = item.extension,
                    fallbackDir = currentDir
                )
            },
            onEditApkInStudio = {
                activeActionItem = null
                apkEditorInitialFile = item.file
                showApkEditorStudio = true
            },
            onInspectOrExtractArchive = {
                activeActionItem = null
                if (browsingArchiveFile != null && item.archiveEntryPath != null) {
                    if (UniversalArchiveEngine.isSupportedArchiveFileName(item.name)) {
                        scope.launch {
                            busyOperationLabel = "Inspecting ${item.name}..."
                            val previewFile = withContext(Dispatchers.IO) {
                                UniversalArchiveEngine.extractSingleEntryToPreviewCache(
                                    context = context,
                                    archiveFile = browsingArchiveFile,
                                    entryPath = item.archiveEntryPath
                                )
                            }
                            busyOperationLabel = null
                            if (previewFile != null && previewFile.exists()) {
                                archivePreviewItem = item.copy(file = previewFile, archiveEntryPath = null)
                            }
                        }
                    } else {
                        DragonFileManagerTaskEngine.launchExtractArchiveTask(
                            context = context,
                            archiveFile = browsingArchiveFile,
                            targetDir = currentDir,
                            selectedEntryPaths = listOf(item.archiveEntryPath)
                        )
                    }
                } else {
                    archivePreviewItem = item
                }
            },
            onCompressToZip = {
                activeActionItem = null
                showCompressZipDialog = listOf(item.file)
            },
            onEditText = {
                activeActionItem = null
                if (browsingArchiveFile != null && item.archiveEntryPath != null) {
                    scope.launch {
                        busyOperationLabel = "Loading ${item.name} from archive..."
                        val previewFile = withContext(Dispatchers.IO) {
                            UniversalArchiveEngine.extractSingleEntryToPreviewCache(
                                context = context,
                                archiveFile = browsingArchiveFile,
                                entryPath = item.archiveEntryPath
                            )
                        }
                        busyOperationLabel = null
                        if (previewFile != null && previewFile.exists()) {
                            textEditorForceHex = false
                            textEditorItem = item.copy(file = previewFile, archiveEntryPath = null)
                        }
                    }
                } else {
                    textEditorForceHex = false
                    textEditorItem = item
                }
            },
            onOpenHexEditor = {
                activeActionItem = null
                if (browsingArchiveFile != null && item.archiveEntryPath != null) {
                    scope.launch {
                        busyOperationLabel = "Loading ${item.name} in Hex Studio..."
                        val previewFile = withContext(Dispatchers.IO) {
                            UniversalArchiveEngine.extractSingleEntryToPreviewCache(
                                context = context,
                                archiveFile = browsingArchiveFile,
                                entryPath = item.archiveEntryPath
                            )
                        }
                        busyOperationLabel = null
                        if (previewFile != null && previewFile.exists()) {
                            textEditorForceHex = true
                            textEditorItem = item.copy(file = previewFile, archiveEntryPath = null)
                        }
                    }
                } else {
                    textEditorForceHex = true
                    textEditorItem = item
                }
            },
            onRename = {
                activeActionItem = null
                renameTargetItem = item
            },
            onCopy = {
                activeActionItem = null
                if (browsingArchiveFile != null && item.archiveEntryPath != null) {
                    fileClipboard = FileManagerClipboardState(
                        files = emptyList(),
                        operation = ClipboardTransferOperation.COPY,
                        sourceArchiveFile = browsingArchiveFile,
                        archiveEntryPaths = listOf(item.archiveEntryPath)
                    )
                    ApkPackageInstaller.showToastOnMain(
                        context,
                        "Copied '${item.name}' from inside ${browsingArchiveFile.name}. Navigate to any folder & tap Paste!"
                    )
                } else {
                    fileClipboard = FileManagerClipboardState(listOf(item.file), ClipboardTransferOperation.COPY)
                    ApkPackageInstaller.showToastOnMain(context, "Copied ${item.name}. Navigate to target folder & tap Paste.")
                }
            },
            onMove = {
                activeActionItem = null
                if (browsingArchiveFile != null && item.archiveEntryPath != null) {
                    fileClipboard = FileManagerClipboardState(
                        files = emptyList(),
                        operation = ClipboardTransferOperation.COPY,
                        sourceArchiveFile = browsingArchiveFile,
                        archiveEntryPaths = listOf(item.archiveEntryPath)
                    )
                    ApkPackageInstaller.showToastOnMain(
                        context,
                        "Copied '${item.name}' from archive. Navigate to target folder & tap Paste."
                    )
                } else {
                    fileClipboard = FileManagerClipboardState(listOf(item.file), ClipboardTransferOperation.CUT)
                    ApkPackageInstaller.showToastOnMain(context, "Cut ${item.name}. Navigate to target folder & tap Paste.")
                }
            },
            onDuplicate = {
                activeActionItem = null
                if (browsingArchiveFile == null) {
                    DragonFileManagerTaskEngine.launchDuplicateTask(
                        context = context,
                        sourceFile = item.file
                    )
                }
            },
            onCopyPath = {
                activeActionItem = null
                val pathStr = if (browsingArchiveFile != null && item.archiveEntryPath != null) {
                    "${browsingArchiveFile.absolutePath}:/${item.archiveEntryPath}"
                } else {
                    item.file.absolutePath
                }
                clipboardManager.setPrimaryClip(ClipData.newPlainText("File Path", pathStr))
                ApkPackageInstaller.showToastOnMain(context, "Copied path: $pathStr")
            },
            onShare = {
                activeActionItem = null
                if (!item.isDirectory && browsingArchiveFile == null) {
                    shareMultipleFiles(context, listOf(item.file))
                }
            },
            onShowPropertiesAndHashes = {
                activeActionItem = null
                propertiesInspectorItem = item
            },
            onMoveToPrivateVault = {
                activeActionItem = null
                if (browsingArchiveFile == null) {
                    requestLockItemsInPrivateVault(listOf(item))
                }
            },
            onDelete = {
                activeActionItem = null
                if (browsingArchiveFile == null) {
                    deleteConfirmItems = listOf(item)
                }
            }
        )
    }

    // Rename Dialog
    renameTargetItem?.let { target ->
        CreateFolderOrFileDialog(
            title = "Rename ${if (target.isDirectory) "Folder" else "File"}",
            placeholder = "Enter new name",
            initialValue = target.name,
            confirmLabel = "Rename",
            onConfirm = { newName ->
                renameTargetItem = null
                val clean = newName.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
                if (clean.isNotBlank() && clean != target.name) {
                    val ok = AndroidDataObbBridge.renameFileOrDirectory(context, target.file, clean)
                    refreshTrigger++
                    selectedPaths = emptySet()
                    ApkPackageInstaller.showToastOnMain(
                        context,
                        if (ok) "Renamed to $clean" else "Unable to rename ${target.name}"
                    )
                }
            },
            onDismiss = { renameTargetItem = null }
        )
    }

    // Batch Rename Dialog
    showBatchRenameDialog?.let { filesToRename ->
        BatchRenameDialog(
            files = filesToRename,
            onDismiss = { showBatchRenameDialog = null },
            onConfirmBatchRename = { prefix, startNumber ->
                showBatchRenameDialog = null
                selectedPaths = emptySet()
                DragonFileManagerTaskEngine.launchBatchRenameTask(
                    context = context,
                    filesToRename = filesToRename,
                    prefix = prefix,
                    startNumber = startNumber
                )
            }
        )
    }

    // Detailed Multi-Format Compression Dialog (18 formats + compression level + filters + options)
    showCompressZipDialog?.let { filesToCompress ->
        DetailedMultiFormatCompressionDialog(
            sources = filesToCompress,
            currentDir = currentDir,
            onDismiss = { showCompressZipDialog = null },
            onConfirmCompress = { outputArchiveFile, config ->
                showCompressZipDialog = null
                selectedPaths = emptySet()
                DragonFileManagerTaskEngine.launchUniversalCompressTask(
                    context = context,
                    sources = filesToCompress,
                    outputArchive = outputArchiveFile,
                    config = config
                )
            }
        )
    }

    // Universal Archive Inspector & Extractor Dialog (All Compressed Formats + Selective payload.bin Extraction)
    archivePreviewItem?.let { archiveItem ->
        ArchiveInspectorAndExtractorDialog(
            context = context,
            item = archiveItem,
            currentDir = currentDir,
            onDismiss = { archivePreviewItem = null },
            onBrowseInsideArchive = {
                archivePreviewItem = null
                enterCompressedArchiveBrowser(archiveItem.file)
            },
            onExtractEntries = { targetSubfolderName, selectedEntryPaths ->
                archivePreviewItem = null
                val outDir = File(currentDir, targetSubfolderName)
                DragonFileManagerTaskEngine.launchExtractArchiveTask(
                    context = context,
                    archiveFile = archiveItem.file,
                    targetDir = outDir,
                    selectedEntryPaths = selectedEntryPaths
                )
            },
            onCopyEntriesToClipboard = { selectedEntryPaths ->
                archivePreviewItem = null
                if (selectedEntryPaths.isNotEmpty()) {
                    fileClipboard = FileManagerClipboardState(
                        files = emptyList(),
                        operation = ClipboardTransferOperation.COPY,
                        sourceArchiveFile = archiveItem.file,
                        archiveEntryPaths = selectedEntryPaths
                    )
                    ApkPackageInstaller.showToastOnMain(
                        context,
                        "Copied ${selectedEntryPaths.size} item(s) from ${archiveItem.name} — navigate to any folder and tap Paste"
                    )
                }
            },
            onInstallBundleIfPackage = if (ApkPackageInstaller.isInstallableExtension(archiveItem.extension)) {
                { forceSign ->
                    archivePreviewItem = null
                    DragonFileManagerTaskEngine.launchStandalonePackageInstallTask(
                        context = context,
                        file = archiveItem.file,
                        forceSign = forceSign
                    )
                }
            } else null
        )
    }

    // Universal Built-in Decompiler, Packet Capture Studio & Document Editor Studio (CAP/PCAP/PCAPNG, AXML/XML, SAV, DEX, PDF, TXT, DOCX, Hex)
    textEditorItem?.let { textItem ->
        val extLower = textItem.extension.lowercase(Locale.US)
        val isPacketCapture = !textEditorForceHex && com.example.data.filemanager.DragonPcapEngine.isCaptureExtension(extLower)
        val isDedicatedDecompiler = !textEditorForceHex && (
            extLower == "dex" ||
                extLower == "xml" ||
                extLower in DragonDocumentEngine.SAVE_FILE_EXTENSIONS
            )
        if (isPacketCapture) {
            DragonPcapStudioDialog(
                context = context,
                item = textItem,
                onDismiss = {
                    textEditorItem = null
                    textEditorForceHex = false
                },
                onSaved = { savedFile ->
                    DragonFileManagerTaskEngine.invalidateDirectorySnapshotCache(savedFile.parentFile?.absolutePath)
                    refreshTrigger++
                },
                onOpenRawHex = {
                    textEditorForceHex = true
                }
            )
        } else if (isDedicatedDecompiler) {
            StandaloneBinaryFormatEditorHost(
                context = context,
                item = textItem,
                onDismiss = {
                    textEditorItem = null
                    textEditorForceHex = false
                },
                onSaved = { savedFile ->
                    DragonFileManagerTaskEngine.invalidateDirectorySnapshotCache(savedFile.parentFile?.absolutePath)
                    refreshTrigger++
                }
            )
        } else {
            DragonDocumentStudioDialog(
                context = context,
                item = textItem,
                forceHexMode = textEditorForceHex,
                onDismiss = {
                    textEditorItem = null
                    textEditorForceHex = false
                },
                onSaved = { savedFile ->
                    DragonFileManagerTaskEngine.invalidateDirectorySnapshotCache(savedFile.parentFile?.absolutePath)
                    refreshTrigger++
                },
                onOpenWithExternalApp = { externalFile ->
                    openFileWithSystemViewer(context, externalFile)
                }
            )
        }
    }

    // Deep Properties, Checksum (MD5 / SHA-1 / SHA-256 / CRC32) & APK Certificate Inspector Dialog
    propertiesInspectorItem?.let { propItem ->
        FileDeepPropertiesAndChecksumDialog(
            item = propItem,
            context = context,
            onDismiss = { propertiesInspectorItem = null },
            onCopyText = { label, value ->
                clipboardManager.setPrimaryClip(ClipData.newPlainText(label, value))
                ApkPackageInstaller.showToastOnMain(context, "Copied $label to clipboard")
            }
        )
    }

    // Private Vault Modal (Password-protected AES-256 encrypted storage)
    if (showPrivateVaultDialog) {
        DragonPrivateVaultDialog(
            context = context,
            currentDir = currentDir,
            currentDirItems = directoryItems.filter { it.archiveEntryPath == null },
            pendingItemsToVault = pendingVaultItems,
            onClearPendingItemsToVault = { pendingVaultItems = emptyList() },
            onLockItemsInVault = { chosen ->
                requestLockItemsInPrivateVault(chosen)
            },
            onPreviewDecryptedVaultFile = { previewFile, entry ->
                val ext = entry.extension.ifBlank { previewFile.extension }.lowercase(Locale.US)
                val tempEntry = FileItemEntry(
                    file = previewFile,
                    name = entry.originalName,
                    isDirectory = false,
                    lengthBytes = previewFile.length(),
                    lastModifiedMs = entry.addedAtMs,
                    extension = ext,
                    childCount = 0,
                    linkedTask = null
                )
                when {
                    com.example.data.audio.DragonAudioPlayerEngine.isAudioExtension(ext) -> {
                        com.example.data.audio.DragonAudioPlayerEngine.playFile(context, previewFile)
                    }
                    isEditableTextExtension(ext) -> {
                        textEditorForceHex = false
                        textEditorItem = tempEntry
                    }
                    UniversalArchiveEngine.isSupportedArchiveFile(previewFile) && ext != "apk" -> {
                        showPrivateVaultDialog = false
                        enterCompressedArchiveBrowser(previewFile)
                    }
                    ApkPackageInstaller.isInstallableExtension(ext) -> {
                        DragonFileManagerTaskEngine.launchStandalonePackageInstallTask(
                            context = context,
                            file = previewFile,
                            forceSign = false
                        )
                    }
                    else -> {
                        openFileWithSystemViewer(context, previewFile)
                    }
                }
            },
            onVaultMutated = { refreshTrigger++ },
            onDismiss = {
                pendingVaultItems = emptyList()
                showPrivateVaultDialog = false
            }
        )
    }

    // Trash Bin Modal (Restore deleted items to original location or delete permanently)
    if (showTrashBinDialog) {
        DragonTrashBinDialog(
            context = context,
            onTrashMutated = { refreshTrigger++ },
            onDismiss = { showTrashBinDialog = false }
        )
    }

    // Delete Confirmation Dialog (Move to Trash Bin by default, Delete Permanently, or Remove from DDM List)
    deleteConfirmItems?.let { itemsToDelete ->
        FileManagerDeleteConfirmDialog(
            items = itemsToDelete,
            onDismiss = { deleteConfirmItems = null },
            onMoveToTrash = {
                deleteConfirmItems = null
                selectedPaths = emptySet()
                DragonFileManagerTaskEngine.launchMoveToTrashTask(
                    context = context,
                    itemsToDelete = itemsToDelete,
                    downloadManager = downloadManager
                )
            },
            onDeletePermanently = {
                deleteConfirmItems = null
                selectedPaths = emptySet()
                DragonFileManagerTaskEngine.launchDeleteFromStorageTask(
                    context = context,
                    itemsToDelete = itemsToDelete,
                    downloadManager = downloadManager
                )
            },
            onDeleteFromDdmListOnly = if (itemsToDelete.any { it.linkedTask != null }) {
                {
                    deleteConfirmItems = null
                    itemsToDelete.mapNotNull { it.linkedTask }.forEach { task ->
                        downloadManager.deleteTaskFromListOnly(task.id)
                    }
                    selectedPaths = emptySet()
                    refreshTrigger++
                }
            } else null
        )
    }

    // Compact File Conflict Resolution Popup ("Copy and replace", "Keep both", "Skip")
    DragonFileConflictDialogHost(context = context)
}

@Composable
private fun FileManagerTopBar(
    currentDir: File,
    isSelectionMode: Boolean,
    selectedCount: Int,
    totalCount: Int,
    viewStyle: FileManagerViewStyle,
    activeSplitPaneLabel: String? = null,
    isSearchVisible: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onToggleSearch: () -> Unit,
    onOpenViewStyleMenu: () -> Unit,
    onOpenSortMenu: () -> Unit,
    onOpenMoreMenu: () -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    IconButton(
                        onClick = if (isSelectionMode) onClearSelection else onBack,
                        modifier = Modifier.testTag("file_manager_back_button")
                    ) {
                        Icon(
                            imageVector = if (isSelectionMode) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    if (!isSelectionMode) {
                        val logoContext = LocalContext.current
                        AsyncImage(
                            model = remember(logoContext) {
                                ImageRequest.Builder(logoContext)
                                    .data(R.drawable.ic_dragon_archiver_v2_clean)
                                    .size(96, 96)
                                    .crossfade(false)
                                    .build()
                            },
                            contentDescription = "Dragon Archiver Logo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(26.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .border(0.8.dp, NeonCyan.copy(alpha = 0.55f), RoundedCornerShape(7.dp))
                        )
                        Spacer(modifier = Modifier.width(7.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isSelectionMode) {
                                val panePrefix = if (activeSplitPaneLabel != null) "[$activeSplitPaneLabel] " else ""
                                "$panePrefix$selectedCount / $totalCount Selected"
                            } else {
                                "DRAGON ARCHIVER"
                            },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.6.sp,
                            color = if (isSelectionMode) NeonCyan else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (activeSplitPaneLabel != null) {
                                "[$activeSplitPaneLabel] ${currentDir.absolutePath}"
                            } else {
                                currentDir.absolutePath
                            },
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isSelectionMode) {
                        IconButton(
                            onClick = onSelectAll,
                            modifier = Modifier.testTag("file_manager_select_all_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.SelectAll,
                                contentDescription = "Select All",
                                tint = NeonCyan
                            )
                        }
                    } else {
                        IconButton(
                            onClick = onToggleSearch,
                            modifier = Modifier.testTag("file_manager_search_button")
                        ) {
                            Icon(
                                imageVector = if (isSearchVisible) Icons.Default.SearchOff else Icons.Default.Search,
                                contentDescription = "Search Files",
                                tint = if (isSearchVisible) NeonCyan else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = onOpenViewStyleMenu,
                            modifier = Modifier.testTag("file_manager_view_mode_button")
                        ) {
                            Icon(
                                imageVector = viewStyle.icon,
                                contentDescription = "View Style: ${viewStyle.label}",
                                tint = if (viewStyle == FileManagerViewStyle.SPLIT_DUAL_PANE) SuccessGreen
                                else if (viewStyle != FileManagerViewStyle.DETAILED_LIST) NeonCyan
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = onOpenSortMenu,
                            modifier = Modifier.testTag("file_manager_sort_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Sort,
                                contentDescription = "Sort Files",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = onOpenMoreMenu,
                            modifier = Modifier.testTag("file_manager_more_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "More File Manager Options",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier.testTag("file_manager_close_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close File Manager",
                                tint = DangerRed
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(visible = isSearchVisible) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    placeholder = {
                        Text(
                            text = "Search files, extensions (.apk, .xapk, .mp4, .zip)...",
                            fontSize = 12.sp
                        )
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null, tint = NeonCyan)
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { onSearchQueryChange("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear Search")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 14.dp, bottom = 8.dp)
                        .testTag("file_manager_search_input")
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                NeonCyan.copy(alpha = 0.45f),
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                Color.Transparent
                            )
                        )
                    )
            )
        }
    }
}

@Composable
private fun StorageRootsAndBreadcrumbsBar(
    currentDir: File,
    ddmRootDir: File,
    publicDownloadsDir: File,
    appPrivateDir: File,
    internalRootDir: File,
    romRootDir: File,
    isRootEnabled: Boolean,
    bookmarkedDirs: List<File>,
    onSelectDir: (File) -> Unit,
    onCreateFolder: () -> Unit,
    onCreateTextFile: () -> Unit,
    onRefresh: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Quick Storage Roots & Bookmarks Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val quickRoots = remember(
                ddmRootDir,
                publicDownloadsDir,
                appPrivateDir,
                internalRootDir,
                romRootDir,
                bookmarkedDirs
            ) {
                buildList {
                    add(Triple("Internal", internalRootDir, Icons.Default.PhoneAndroid))
                    add(Triple("Root Folder", romRootDir, Icons.Default.AdminPanelSettings))
                    add(Triple("DDM", ddmRootDir, Icons.Default.CloudDownload))
                    add(Triple("Downloads", publicDownloadsDir, Icons.Default.Download))
                    add(Triple("App Sandbox", appPrivateDir, Icons.Default.Security))
                    bookmarkedDirs.forEach { dir ->
                        if (none { it.second.absolutePath == dir.absolutePath }) {
                            add(Triple(dir.name.ifBlank { "Folder" }, dir, Icons.Default.Folder))
                        }
                    }
                }
            }

            quickRoots.forEach { (label, dir, icon) ->
                val isSelected = currentDir.absolutePath == dir.absolutePath
                val isRootChip = label == "Root Folder"
                val chipAccent = when {
                    isRootChip && isRootEnabled -> SuccessGreen
                    isRootChip -> WarningOrange
                    else -> MaterialTheme.colorScheme.primary
                }
                Surface(
                    shape = RoundedCornerShape(9.dp),
                    color = if (isSelected) chipAccent.copy(alpha = 0.2f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (isSelected) chipAccent
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                    ),
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .clickable { onSelectDir(dir) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = if (isSelected || isRootChip) chipAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = label,
                            fontSize = 10.5.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) chipAccent else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        // Breadcrumb Path + Quick Create Actions Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val segments = remember(currentDir) {
                buildBreadcrumbSegments(currentDir)
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                segments.forEachIndexed { idx, segFile ->
                    val isLast = idx == segments.lastIndex
                    Text(
                        text = segFile.name.ifBlank { "/" },
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = if (isLast) FontWeight.ExtraBold else FontWeight.Medium,
                        color = if (isLast) NeonCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onSelectDir(segFile) }
                            .padding(horizontal = 5.dp, vertical = 3.dp)
                    )
                    if (!isLast) {
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onCreateFolder)
                        .testTag("file_manager_new_folder_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CreateNewFolder,
                            contentDescription = "New Folder",
                            tint = WarningOrange,
                            modifier = Modifier.size(14.dp)
                        )
                        Text("Folder", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onCreateTextFile)
                        .testTag("file_manager_new_file_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.NoteAdd,
                            contentDescription = "New File",
                            tint = NeonCyan,
                            modifier = Modifier.size(14.dp)
                        )
                        Text("File", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }

                IconButton(
                    onClick = onRefresh,
                    modifier = Modifier.size(26.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryFilterChipsRow(
    selectedCategory: FileManagerFilterCategory,
    onSelectCategory: (FileManagerFilterCategory) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        FileManagerFilterCategory.entries.forEach { cat ->
            val isSelected = selectedCategory == cat
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (isSelected) cat.accent.copy(alpha = 0.18f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                border = BorderStroke(
                    width = 1.dp,
                    color = if (isSelected) cat.accent else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                ),
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onSelectCategory(cat) }
                    .testTag("file_manager_category_${cat.name.lowercase(Locale.US)}")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = cat.icon,
                        contentDescription = null,
                        tint = if (isSelected) cat.accent else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = cat.label,
                        fontSize = 10.5.sp,
                        fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                        color = if (isSelected) cat.accent else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileListRowCard(
    item: FileItemEntry,
    isSelected: Boolean,
    context: Context,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSelectToggle: () -> Unit,
    onQuickInstallApk: (Boolean) -> Unit,
    onQuickExtractZip: () -> Unit,
    onOpenMenu: () -> Unit
) {
    val isPackageFile = !item.isDirectory && item.archiveEntryPath == null && ApkPackageInstaller.isInstallableExtension(item.extension)
    val isArchiveFile = !item.isDirectory && (item.archiveEntryPath != null || UniversalArchiveEngine.isSupportedArchiveFile(item.file))
    val iconColor = resolveFileEntryColor(item)
    val dateFormatted = remember(item.lastModifiedMs) {
        formatFileTimestampFast(item.lastModifiedMs)
    }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                NeonCyan.copy(alpha = 0.14f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (isSelected) NeonCyan
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // File / Folder Icon Box (tapping toggles multi-selection)
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(
                        if (isSelected) NeonCyan else iconColor.copy(alpha = 0.15f)
                    )
                    .border(
                        width = 1.dp,
                        color = if (isSelected) NeonCyan else iconColor.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(11.dp)
                    )
                    .clickable(onClick = onSelectToggle),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isSelected) Icons.Default.Check else resolveFileEntryIcon(item),
                    contentDescription = item.name,
                    tint = if (isSelected) Color.Black else iconColor,
                    modifier = Modifier.size(21.dp)
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = item.name,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val sizeOrCountText = if (item.isDirectory) {
                        if (item.childCount >= 0) "${item.childCount} item(s)" else "Folder"
                    } else {
                        com.example.data.model.formatBytes(item.lengthBytes)
                    }
                    Text(
                        text = "$sizeOrCountText • $dateFormatted",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (!item.isDirectory && item.extension.isNotBlank()) {
                        val badgeLabel = remember(item.file.name, item.extension, item.archiveEntryPath) {
                            if (item.archiveEntryPath == null && UniversalArchiveEngine.isSupportedArchiveFile(item.file)) {
                                UniversalArchiveEngine.detectFormatLabel(item.file)
                            } else {
                                item.extension.uppercase(Locale.US)
                            }
                        }
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = iconColor.copy(alpha = 0.16f)
                        ) {
                            Text(
                                text = badgeLabel,
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = FontFamily.Monospace,
                                color = iconColor,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
            }

            // Compact Inline Action Buttons for Packages & Archives
            if (isPackageFile) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = SuccessGreen.copy(alpha = 0.18f),
                    border = BorderStroke(1.dp, SuccessGreen.copy(alpha = 0.35f)),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onQuickInstallApk(false) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.InstallMobile,
                            contentDescription = "Install, Update, or Downgrade",
                            tint = SuccessGreen,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = "Install",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = SuccessGreen,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            } else if (isArchiveFile) {
                IconButton(
                    onClick = onQuickExtractZip,
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Unarchive,
                        contentDescription = "Inspect / Extract Archive",
                        tint = WarningOrange,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            IconButton(
                onClick = onOpenMenu,
                modifier = Modifier.size(30.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "File Actions",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileGridItemCard(
    item: FileItemEntry,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSelectToggle: () -> Unit,
    onOpenMenu: () -> Unit
) {
    val iconColor = resolveFileEntryColor(item)
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) NeonCyan.copy(alpha = 0.14f)
            else MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (isSelected) NeonCyan
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(
                            if (isSelected) NeonCyan
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .clickable(onClick = onSelectToggle),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
                IconButton(
                    onClick = onOpenMenu,
                    modifier = Modifier.size(22.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Actions",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }

            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(iconColor.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = resolveFileEntryIcon(item),
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(26.dp)
                )
            }

            Text(
                text = item.name,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                textAlign = TextAlign.Center,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = if (item.isDirectory) {
                    if (item.childCount >= 0) "${item.childCount} item(s)" else "Folder"
                } else com.example.data.model.formatBytes(item.lengthBytes),
                fontSize = 9.5.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun InArchiveVirtualBrowserBanner(
    archiveFile: File,
    virtualPath: String,
    onNavigateUpInArchive: () -> Unit,
    onExitArchive: () -> Unit,
    onDecompressEntireArchive: () -> Unit
) {
    val badge = remember(archiveFile.name) {
        UniversalArchiveEngine.detectFormatLabel(archiveFile)
    }
    Surface(
        color = WarningOrange.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, WarningOrange.copy(alpha = 0.55f)),
        shape = RoundedCornerShape(11.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .testTag("in_archive_virtual_browser_banner")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(
                onClick = onNavigateUpInArchive,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Up inside archive",
                    tint = WarningOrange,
                    modifier = Modifier.size(17.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = WarningOrange.copy(alpha = 0.22f)
                    ) {
                        Text(
                            text = "INSIDE $badge",
                            fontSize = 8.5.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.ExtraBold,
                            color = WarningOrange,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                    Text(
                        text = archiveFile.name,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = if (virtualPath.isEmpty()) {
                        "Select or hold any file/folder to Copy & Paste without full decompress"
                    } else {
                        "Path: /$virtualPath"
                    },
                    fontSize = 9.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = WarningOrange.copy(alpha = 0.22f),
                border = BorderStroke(1.dp, WarningOrange.copy(alpha = 0.5f)),
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onDecompressEntireArchive)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Unarchive,
                        contentDescription = "Extract All",
                        tint = WarningOrange,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = "Extract All",
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = WarningOrange
                    )
                }
            }
            IconButton(
                onClick = onExitArchive,
                modifier = Modifier.size(26.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Exit Archive View",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun MultiSelectBottomActionBar(
    selectedCount: Int,
    isInsideArchive: Boolean = false,
    oppositePaneShortLabel: String? = null,
    oppositePaneDirName: String? = null,
    onDirectCopyToOpposite: (() -> Unit)? = null,
    onDirectMoveToOpposite: (() -> Unit)? = null,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onZip: () -> Unit,
    onBatchRename: () -> Unit,
    onShare: () -> Unit,
    onMoveToVault: () -> Unit = {},
    onDelete: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.35f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("file_manager_multiselect_bar")
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Instant 1-Tap Cross-Pane Copy / Move Strip when in Dual-Pane Split View
            if (oppositePaneShortLabel != null && onDirectCopyToOpposite != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SuccessGreen.copy(alpha = 0.08f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalButton(
                        onClick = onDirectCopyToOpposite,
                        shape = RoundedCornerShape(9.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = SuccessGreen.copy(alpha = 0.22f),
                            contentColor = SuccessGreen
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 34.dp)
                            .testTag("split_pane_direct_copy_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Direct Copy to Opposite Pane",
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = "Copy $oppositePaneShortLabel (${oppositePaneDirName.orEmpty()})",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (onDirectMoveToOpposite != null && !isInsideArchive) {
                        FilledTonalButton(
                            onClick = onDirectMoveToOpposite,
                            shape = RoundedCornerShape(9.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = WarningOrange.copy(alpha = 0.22f),
                                contentColor = WarningOrange
                            ),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp),
                            modifier = Modifier
                                .weight(0.85f)
                                .heightIn(min = 34.dp)
                                .testTag("split_pane_direct_move_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.DriveFileMove,
                                contentDescription = "Direct Move to Opposite Pane",
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                text = "Move $oppositePaneShortLabel",
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isInsideArchive) {
                    BottomBarActionButton(
                        icon = Icons.Default.ContentCopy,
                        label = "Copy $selectedCount from Archive (No Full Decompress)",
                        tint = NeonCyan,
                        onClick = onCopy
                    )
                } else {
                    BottomBarActionButton(Icons.Default.ContentCopy, "Copy", NeonCyan, onCopy)
                    BottomBarActionButton(Icons.Default.ContentCut, "Move", WarningOrange, onCut)
                    BottomBarActionButton(Icons.Default.FolderZip, "Compress ($selectedCount)", SuccessGreen, onZip)
                    BottomBarActionButton(Icons.Default.DriveFileRenameOutline, "Rename", MaterialTheme.colorScheme.primary, onBatchRename)
                    BottomBarActionButton(Icons.Default.Share, "Share", NeonCyan, onShare)
                    BottomBarActionButton(Icons.Default.EnhancedEncryption, "Vault", Color(0xFFA855F7), onMoveToVault)
                    BottomBarActionButton(Icons.Default.DeleteForever, "Delete", DangerRed, onDelete)
                }
            }
        }
    }
}

@Composable
private fun BottomBarActionButton(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(19.dp)
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun ClipboardPasteBar(
    clipboardState: FileManagerClipboardState,
    targetDir: File,
    isInsideArchive: Boolean = false,
    onPasteHere: () -> Unit,
    onCancelClipboard: () -> Unit
) {
    val isFromArchive = clipboardState.sourceArchiveFile != null && clipboardState.archiveEntryPaths.isNotEmpty()
    val count = if (isFromArchive) clipboardState.archiveEntryPaths.size else clipboardState.files.size
    val titleText = when {
        isFromArchive -> "ARCHIVE CLIPBOARD: COPY $count ITEM(S) FROM ${clipboardState.sourceArchiveFile?.name}"
        clipboardState.operation == ClipboardTransferOperation.COPY -> "CLIPBOARD: COPY $count ITEM(S)"
        else -> "CLIPBOARD: MOVE $count ITEM(S)"
    }
    Surface(
        color = WarningOrange.copy(alpha = 0.16f),
        border = BorderStroke(1.dp, WarningOrange.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = titleText,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.ExtraBold,
                    color = WarningOrange,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (isInsideArchive) {
                        "Tap Paste to extract directly into ${targetDir.name.ifBlank { "/" }}"
                    } else {
                        "Destination: ${targetDir.name.ifBlank { "/" }}"
                    },
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = onCancelClipboard) {
                    Text("Cancel", fontSize = 11.sp)
                }
                Button(
                    onClick = onPasteHere,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = WarningOrange,
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(9.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Paste Here", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
        }
    }
}

@Composable
private fun EmptyDirectoryPlaceholder(
    searchQuery: String,
    selectedCategory: FileManagerFilterCategory,
    currentDir: File,
    onCreateFolder: () -> Unit,
    onCreateFile: () -> Unit,
    onResetFilters: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(NeonCyan.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.FolderOpen,
                contentDescription = null,
                tint = NeonCyan,
                modifier = Modifier.size(34.dp)
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = if (searchQuery.isNotBlank() || selectedCategory != FileManagerFilterCategory.ALL) {
                "No Matching Files Found"
            } else {
                "Folder is Empty (${currentDir.name})"
            },
            fontSize = 14.sp,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (searchQuery.isNotBlank() || selectedCategory != FileManagerFilterCategory.ALL) {
                "Try clearing your search query or switching category filters."
            } else {
                "Downloaded files, APKs, videos, archives, and documents will appear here."
            },
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        if (searchQuery.isNotBlank() || selectedCategory != FileManagerFilterCategory.ALL) {
            OutlinedButton(onClick = onResetFilters) {
                Icon(Icons.Default.FilterAltOff, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Reset Filters", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onCreateFolder) {
                    Icon(Icons.Default.CreateNewFolder, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("New Folder", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(onClick = onCreateFile) {
                    Icon(Icons.Default.NoteAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("New File", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun FileItemProActionDialog(
    item: FileItemEntry,
    context: Context,
    isInsideArchive: Boolean = false,
    oppositePaneTargetLabel: String? = null,
    onDirectCopyToOpposite: (() -> Unit)? = null,
    onDirectMoveToOpposite: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onBrowseInsideArchive: () -> Unit = {},
    onInstallPackage: (Boolean) -> Unit,
    onAutoSignStandaloneApk: () -> Unit,
    onEditApkInStudio: () -> Unit = {},
    onInspectOrExtractArchive: () -> Unit,
    onCompressToZip: () -> Unit,
    onEditText: () -> Unit,
    onOpenHexEditor: () -> Unit = {},
    onRename: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onDuplicate: () -> Unit,
    onCopyPath: () -> Unit,
    onShare: () -> Unit,
    onShowPropertiesAndHashes: () -> Unit,
    onMoveToPrivateVault: () -> Unit = {},
    onDelete: () -> Unit
) {
    val isPackage = !item.isDirectory && ApkPackageInstaller.isInstallableExtension(item.extension)
    val isArchive = !item.isDirectory && UniversalArchiveEngine.isSupportedArchiveFileName(item.name)
    val isBinContainer = !item.isDirectory && (item.extension == "bin" || item.extension == "iso")
    val isTextEditable = !item.isDirectory && isEditableTextExtension(item.extension)
    val iconColor = resolveFileEntryColor(item)
    var pkgSummary by remember(item.file.absolutePath, isPackage, isInsideArchive) {
        mutableStateOf<ApkPackageInstaller.ApkPackageSummary?>(null)
    }
    LaunchedEffect(item.file.absolutePath, isPackage, isInsideArchive) {
        if (isPackage && !isInsideArchive) {
            pkgSummary = withContext(Dispatchers.IO) {
                ApkPackageInstaller.inspectStandaloneFilePackage(context, item.file)
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, iconColor.copy(alpha = 0.45f)),
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 350.dp)
                .testTag("file_manager_item_action_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(iconColor.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = resolveFileEntryIcon(item),
                            contentDescription = null,
                            tint = iconColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.name,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (item.isDirectory) {
                                if (item.childCount >= 0) "Folder • ${item.childCount} item(s)" else "Folder"
                            } else "${com.example.data.model.formatBytes(item.lengthBytes)} • ${item.extension.uppercase(Locale.US)}",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(26.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", modifier = Modifier.size(16.dp))
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                val isAudioItem = !item.isDirectory && com.example.data.audio.DragonAudioPlayerEngine.isAudioExtension(item.extension)
                val isApkFile = !item.isDirectory && item.extension.equals("apk", ignoreCase = true)
                val isPcapFile = !item.isDirectory && com.example.data.filemanager.DragonPcapEngine.isCaptureExtension(item.extension)
                ProActionSheetRow(
                    icon = when {
                        item.isDirectory -> Icons.Default.FolderOpen
                        isAudioItem -> Icons.Default.PlayArrow
                        isPcapFile -> Icons.Default.Router
                        else -> Icons.Default.OpenInNew
                    },
                    tint = if (isAudioItem) Color(0xFFA855F7) else NeonCyan,
                    title = when {
                        item.isDirectory -> "Open Folder"
                        isAudioItem -> "Play in Mini Audio Player"
                        isApkFile -> "Open / Install APK"
                        isPcapFile -> "Open / Decrypt & Edit ${item.extension.uppercase(Locale.US)}"
                        isArchive -> "Open / Browse Inside ${item.extension.uppercase(Locale.US)}"
                        else -> "Open / Launch File"
                    },
                    subtitle = when {
                        item.isDirectory -> "Browse directory contents"
                        isAudioItem -> "Play directly in compact floating audio panel"
                        isApkFile -> "Open or install APK package"
                        isPcapFile -> "Inspect packets, decrypt TLS/Wi-Fi/AES & edit frames"
                        isArchive -> "Inspect partitions or internal files without extracting"
                        else -> "Open or view directly"
                    },
                    onClick = onOpen
                )

                if (oppositePaneTargetLabel != null && onDirectCopyToOpposite != null) {
                    ProActionSheetRow(
                        icon = Icons.Default.ContentCopy,
                        tint = SuccessGreen,
                        title = "Copy to $oppositePaneTargetLabel",
                        subtitle = if (isInsideArchive) "Extract directly to opposite split pane" else "Instant 1-tap copy to opposite split pane",
                        onClick = onDirectCopyToOpposite
                    )
                }
                if (oppositePaneTargetLabel != null && onDirectMoveToOpposite != null && !isInsideArchive) {
                    ProActionSheetRow(
                        icon = Icons.Default.DriveFileMove,
                        tint = WarningOrange,
                        title = "Move to $oppositePaneTargetLabel",
                        subtitle = "Instant 1-tap move to opposite split pane",
                        onClick = onDirectMoveToOpposite
                    )
                }

                if (isArchive) {
                    ProActionSheetRow(
                        icon = if (isBinContainer) Icons.Default.Memory else Icons.Default.FolderZip,
                        tint = NeonCyan,
                        title = when {
                            isApkFile -> "View Inside APK"
                            isBinContainer -> "View Inside .${item.extension.uppercase(Locale.US)} (No Extract)"
                            else -> "View Inside Archive (No Decompress)"
                        },
                        subtitle = if (isBinContainer) {
                            "Browse partitions, ISO files & embedded payloads — Copy/Paste directly"
                        } else {
                            "Browse internal folders & copy/paste files directly"
                        },
                        onClick = onBrowseInsideArchive
                    )
                    ProActionSheetRow(
                        icon = Icons.Default.Unarchive,
                        tint = WarningOrange,
                        title = if (isBinContainer) {
                            "Extract .${item.extension.uppercase(Locale.US)} Container"
                        } else {
                            "Decompress / Extract Archive"
                        },
                        subtitle = "Select partitions or unpack entire ${UniversalArchiveEngine.detectFormatLabel(item.file)} to folder",
                        onClick = onInspectOrExtractArchive
                    )
                } else if (isInsideArchive && !item.isDirectory) {
                    ProActionSheetRow(
                        icon = Icons.Default.Unarchive,
                        tint = WarningOrange,
                        title = "Extract '${item.name}' to Current Folder",
                        subtitle = "Unpack ${com.example.data.model.formatBytes(item.lengthBytes)} directly into current directory",
                        onClick = onInspectOrExtractArchive
                    )
                }

                if (isPackage) {
                    val isDowngrade = pkgSummary?.isDowngrade == true
                    val isUpdate = pkgSummary?.isAlreadyInstalled == true && !isDowngrade
                    val actionWord = pkgSummary?.shortActionText ?: "Install / Update"
                    ProActionSheetRow(
                        icon = when {
                            isDowngrade -> Icons.Default.History
                            isUpdate -> Icons.Default.SystemUpdate
                            else -> Icons.Default.InstallMobile
                        },
                        tint = when {
                            isDowngrade -> WarningOrange
                            isUpdate -> NeonCyan
                            else -> SuccessGreen
                        },
                        title = "$actionWord Package",
                        subtitle = pkgSummary?.statusSummary ?: "Supports signed & unsigned APK, APKS, XAPK & APKM",
                        onClick = { onInstallPackage(false) }
                    )
                    ProActionSheetRow(
                        icon = Icons.Default.VerifiedUser,
                        tint = NeonCyan,
                        title = "Auto-Sign (V1+V2) & $actionWord",
                        subtitle = "Zipalign & sign signed/unsigned package & ${actionWord.lowercase(Locale.US)}",
                        onClick = { onInstallPackage(true) }
                    )
                    if (item.extension == "apk" && !isInsideArchive) {
                        ProActionSheetRow(
                            icon = Icons.Default.BuildCircle,
                            tint = NeonCyan,
                            title = "Edit in APK Editor Pro",
                            subtitle = "Edit Manifest, App Name, Package, Permissions, Icon, Strings & DEX",
                            onClick = onEditApkInStudio
                        )
                        ProActionSheetRow(
                            icon = Icons.Default.Key,
                            tint = WarningOrange,
                            title = "Export Signed APK Copy (_signed.apk)",
                            subtitle = "Generate standalone V1+V2 signed & zipaligned APK in folder",
                            onClick = onAutoSignStandaloneApk
                        )
                    }
                }

                if (isTextEditable) {
                    val extLower = item.extension.lowercase(Locale.US)
                    val isPcapCap = com.example.data.filemanager.DragonPcapEngine.isCaptureExtension(extLower)
                    val (editTitle, editSub) = when {
                        isPcapCap -> "Packet Capture Studio (.${extLower.uppercase(Locale.US)})" to "Read Packets, Decrypt TLS/Wi-Fi/AES & Edit Headers/Payload"
                        extLower == "dex" -> "Decompile & Edit .DEX" to "Browse Classes, Smali/Java, Patch Bytecode & Strings"
                        extLower in DragonDocumentEngine.SAVE_FILE_EXTENSIONS -> "Decompile & Edit .SAV" to "UE4/5 GVAS, Zlib/GZip/JSON Properties & Binary Patcher"
                        extLower == "xml" -> "Decompile & Edit .XML (AXML)" to "Auto-Decompile Binary AXML or Edit Plain XML & Recompile"
                        else -> "View / Edit Document" to "Built-in Studio (TXT, PDF, SAV, XML, DEX, DOCX, JSON, Hex)"
                    }
                    ProActionSheetRow(
                        icon = when {
                            isPcapCap -> Icons.Default.Router
                            extLower in setOf("dex", "xml") || extLower in DragonDocumentEngine.SAVE_FILE_EXTENSIONS -> Icons.Default.Code
                            else -> Icons.Default.EditNote
                        },
                        tint = NeonCyan,
                        title = editTitle,
                        subtitle = editSub,
                        onClick = onEditText
                    )
                    ProActionSheetRow(
                        icon = Icons.Default.DataObject,
                        tint = WarningOrange,
                        title = "Open in Raw Hex Editor",
                        subtitle = "Inspect & patch raw binary bytes directly",
                        onClick = onOpenHexEditor
                    )
                } else if (!item.isDirectory) {
                    ProActionSheetRow(
                        icon = Icons.Default.DataObject,
                        tint = NeonCyan,
                        title = "Open in Hex / Binary Editor",
                        subtitle = "Inspect strings, patch bytes & view hex directly",
                        onClick = onOpenHexEditor
                    )
                }

                ProActionSheetRow(
                    icon = Icons.Default.ContentCopy,
                    tint = NeonCyan,
                    title = if (isInsideArchive) "Copy from Archive (Without Decompressing)" else "Copy",
                    subtitle = if (isInsideArchive) "Stream this item directly to any target folder on Paste" else "Copy to another folder",
                    onClick = onCopy
                )

                if (!isInsideArchive) {
                    ProActionSheetRow(
                        icon = Icons.Default.ContentCut,
                        tint = WarningOrange,
                        title = "Move (Cut)",
                        subtitle = "Move to another folder",
                        onClick = onMove
                    )

                    ProActionSheetRow(
                        icon = Icons.Default.DriveFileRenameOutline,
                        tint = MaterialTheme.colorScheme.primary,
                        title = "Rename",
                        subtitle = "Change file or folder name",
                        onClick = onRename
                    )

                    ProActionSheetRow(
                        icon = Icons.Default.FileCopy,
                        tint = SuccessGreen,
                        title = "Duplicate Here",
                        subtitle = "Create an instant copy in current folder",
                        onClick = onDuplicate
                    )

                    ProActionSheetRow(
                        icon = Icons.Default.FolderZip,
                        tint = WarningOrange,
                        title = "Compress Archive (Multi-Format & Options)",
                        subtitle = "Pack into ZIP, 7Z, TAR.GZ, TAR.XZ, TAR.ZST, JAR, XAPK, etc.",
                        onClick = onCompressToZip
                    )

                    ProActionSheetRow(
                        icon = Icons.Default.Info,
                        tint = NeonCyan,
                        title = "Properties, Checksums & Signatures",
                        subtitle = "MD5, SHA-1, SHA-256, CRC32 & APK certificate inspector",
                        onClick = onShowPropertiesAndHashes
                    )
                }

                ProActionSheetRow(
                    icon = Icons.Default.Link,
                    tint = MaterialTheme.colorScheme.primary,
                    title = "Copy Full Storage Path",
                    subtitle = item.archiveEntryPath ?: item.file.absolutePath,
                    onClick = onCopyPath
                )

                if (!item.isDirectory && !isInsideArchive) {
                    ProActionSheetRow(
                        icon = Icons.Default.Share,
                        tint = NeonCyan,
                        title = "Share File",
                        subtitle = "Send via Android Share Sheet",
                        onClick = onShare
                    )
                }

                if (!isInsideArchive) {
                    ProActionSheetRow(
                        icon = Icons.Default.EnhancedEncryption,
                        tint = Color(0xFFA855F7),
                        title = "Lock in Private Vault",
                        subtitle = "Encrypt with AES-256 & hide with password in Private Vault",
                        onClick = onMoveToPrivateVault
                    )
                    ProActionSheetRow(
                        icon = Icons.Default.DeleteForever,
                        tint = DangerRed,
                        title = "Delete",
                        subtitle = "Delete from storage or remove from DDM list",
                        onClick = onDelete
                    )
                }
            }
        }
    }
}

@Composable
private fun ProActionSheetRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(tint.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = icon, contentDescription = title, tint = tint, modifier = Modifier.size(16.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    fontSize = 9.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun FileDeepPropertiesAndChecksumDialog(
    item: FileItemEntry,
    context: Context,
    onDismiss: () -> Unit,
    onCopyText: (String, String) -> Unit
) {
    var hashResult by remember { mutableStateOf<FileHashResult?>(null) }
    var isCalculatingHash by remember { mutableStateOf(false) }
    var folderStats by remember { mutableStateOf<Pair<Long, Int>?>(null) }

    val isPackage = !item.isDirectory && ApkPackageInstaller.isInstallableExtension(item.extension)
    var apkDetails by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(item.file.absolutePath) {
        withContext(Dispatchers.IO) {
            if (item.isDirectory) {
                folderStats = calculateDirectorySizeAndCount(item.file)
            } else {
                if (isPackage) {
                    val isUnsigned = ApkAutoSigner.isFileOrBundleUnsigned(
                        context = context,
                        file = item.file,
                        isSplitBundle = item.extension in setOf("apks", "xapk", "apkm")
                    )
                    apkDetails = if (isUnsigned) {
                        "Unsigned / Missing Certificate (DDM Auto-Sign V1+V2 Ready)"
                    } else {
                        "Validly Signed Package (V1/V2 Verified)"
                    }
                }
                if (item.lengthBytes <= 150L * 1024L * 1024L) {
                    isCalculatingHash = true
                    hashResult = computeFileChecksums(context, item.file)
                    isCalculatingHash = false
                }
            }
        }
    }

    val scope = rememberCoroutineScope()
    val modifiedDate = remember(item.lastModifiedMs) {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(item.lastModifiedMs))
    }
    val permissionsString = remember(item.file) {
        buildString {
            append(if (item.isDirectory) "d" else "-")
            append(if (item.file.canRead()) "r" else "-")
            append(if (item.file.canWrite()) "w" else "-")
            append(if (item.file.canExecute()) "x" else "-")
            if (item.file.isHidden) append(" (Hidden)")
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Verified, contentDescription = null, tint = NeonCyan)
                Text(
                    text = "Properties & Checksum Verifier",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                InspectorDetailCard("Name", item.name) { onCopyText("Name", item.name) }
                InspectorDetailCard("Path", item.file.absolutePath) { onCopyText("Path", item.file.absolutePath) }
                if (item.isDirectory) {
                    val (totalBytes, count) = folderStats ?: (0L to item.childCount.coerceAtLeast(0))
                    InspectorDetailCard(
                        "Folder Contents",
                        "${com.example.data.model.formatBytes(totalBytes)} ($totalBytes bytes) across $count files"
                    )
                } else {
                    InspectorDetailCard(
                        "Size",
                        "${com.example.data.model.formatBytes(item.lengthBytes)} (${item.lengthBytes} bytes)"
                    )
                    InspectorDetailCard(
                        "MIME Type",
                        resolveMimeTypeForFile(item.file)
                    )
                }
                InspectorDetailCard("Modified", modifiedDate)
                InspectorDetailCard("Permissions", permissionsString)

                apkDetails?.let { sigStatus ->
                    InspectorDetailCard("Package Signature Status", sigStatus)
                }

                if (!item.isDirectory) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    Text(
                        text = "CRYPTOGRAPHIC CHECKSUMS (MD5 / SHA-1 / SHA-256 / CRC32)",
                        fontSize = 9.5.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.ExtraBold,
                        color = NeonCyan
                    )

                    if (isCalculatingHash) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(vertical = 6.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text("Computing cryptographic hashes...", fontSize = 11.sp)
                        }
                    } else if (hashResult != null) {
                        val hashes = hashResult!!
                        InspectorDetailCard("MD5", hashes.md5) { onCopyText("MD5", hashes.md5) }
                        InspectorDetailCard("SHA-1", hashes.sha1) { onCopyText("SHA-1", hashes.sha1) }
                        InspectorDetailCard("SHA-256", hashes.sha256) { onCopyText("SHA-256", hashes.sha256) }
                        InspectorDetailCard("CRC32", hashes.crc32) { onCopyText("CRC32", hashes.crc32) }
                    } else {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    isCalculatingHash = true
                                    hashResult = withContext(Dispatchers.IO) { computeFileChecksums(context, item.file) }
                                    isCalculatingHash = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Calculate, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Compute MD5 / SHA-256 Checksums", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
private fun InspectorDetailCard(
    label: String,
    value: String,
    onCopy: (() -> Unit)? = null
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label.uppercase(Locale.US),
                    fontSize = 8.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.ExtraBold,
                    color = NeonCyan
                )
                Text(
                    text = value,
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            if (onCopy != null) {
                IconButton(onClick = onCopy, modifier = Modifier.size(26.dp)) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy $label",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ArchiveInspectorAndExtractorDialog(
    context: Context,
    item: FileItemEntry,
    currentDir: File,
    onDismiss: () -> Unit,
    onBrowseInsideArchive: () -> Unit,
    onExtractEntries: (String, List<String>?) -> Unit,
    onCopyEntriesToClipboard: (List<String>) -> Unit,
    onInstallBundleIfPackage: ((Boolean) -> Unit)?
) {
    var entriesList by remember { mutableStateOf<List<Pair<String, Long>>>(emptyList()) }
    var selectedEntryPaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isLoading by remember { mutableStateOf(true) }
    var targetFolderName by remember {
        mutableStateOf(
            UniversalArchiveEngine.stripArchiveExtension(item.name)
                .ifBlank { "extracted_${item.name}" }
        )
    }

    LaunchedEffect(item.file.absolutePath) {
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                UniversalArchiveEngine.buildCompleteVirtualTree(context, item.file)
                    .filter { !it.isDirectory }
                    .take(350)
                    .map { it.fullEntryPath to it.uncompressedSize.coerceAtLeast(0L) }
            }.getOrDefault(emptyList())
        }
        entriesList = loaded
        selectedEntryPaths = loaded.map { it.first }.toSet()
        isLoading = false
    }

    val hasImgPartitions = remember(entriesList) {
        entriesList.any { it.first.endsWith(".img", ignoreCase = true) }
    }
    val selectedTotalBytes = remember(entriesList, selectedEntryPaths) {
        entriesList.sumOf { (name, size) -> if (name in selectedEntryPaths) size else 0L }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.FolderZip, contentDescription = null, tint = WarningOrange)
                Column {
                    Text(
                        text = "Universal Archive Decompressor (${UniversalArchiveEngine.detectFormatLabel(item.file)})",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "${item.name} (${selectedEntryPaths.size}/${entriesList.size} selected • ${com.example.data.model.formatBytes(selectedTotalBytes)})",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = targetFolderName,
                    onValueChange = { targetFolderName = it },
                    label = { Text("Decompress into subfolder in ${currentDir.name.ifBlank { "/" }}", fontSize = 10.5.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(
                        onClick = onBrowseInsideArchive,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("View Inside", fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = {
                            val list = if (selectedEntryPaths.isNotEmpty()) {
                                selectedEntryPaths.toList()
                            } else {
                                entriesList.map { it.first }
                            }
                            onCopyEntriesToClipboard(list)
                        },
                        enabled = entriesList.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Copy Selected", fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                    }
                }

                if (onInstallBundleIfPackage != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilledTonalButton(
                            onClick = { onInstallBundleIfPackage(false) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.InstallMobile, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Install", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        FilledTonalButton(
                            onClick = { onInstallBundleIfPackage(true) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.VerifiedUser, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Sign & Install", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "SELECT FILES / PARTITIONS",
                        fontSize = 9.5.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.ExtraBold,
                        color = NeonCyan
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "All",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = NeonCyan,
                            modifier = Modifier.clickable {
                                selectedEntryPaths = entriesList.map { it.first }.toSet()
                            }
                        )
                        if (hasImgPartitions) {
                            Text(
                                text = "Boot/Core .IMG",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = WarningOrange,
                                modifier = Modifier.clickable {
                                    val coreImgs = setOf(
                                        "boot.img",
                                        "init_boot.img",
                                        "vendor_boot.img",
                                        "recovery.img",
                                        "dtbo.img",
                                        "vbmeta.img",
                                        "vbmeta_system.img",
                                        "vbmeta_vendor.img",
                                        "modem.img"
                                    )
                                    val matched = entriesList
                                        .map { it.first }
                                        .filter { path ->
                                            val base = path.substringAfterLast('/').lowercase(Locale.US)
                                            base in coreImgs
                                        }
                                        .toSet()
                                    selectedEntryPaths = if (matched.isNotEmpty()) matched else entriesList.map { it.first }.toSet()
                                }
                            )
                        }
                        Text(
                            text = "None",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable {
                                selectedEntryPaths = emptySet()
                            }
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 130.dp, max = 240.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                        .padding(6.dp)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    } else if (entriesList.isEmpty()) {
                        Text(
                            text = "Ready to decompress archive.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            items(entriesList) { (name, size) ->
                                val isChecked = name in selectedEntryPaths
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable {
                                            selectedEntryPaths = if (isChecked) {
                                                selectedEntryPaths - name
                                            } else {
                                                selectedEntryPaths + name
                                            }
                                        }
                                        .padding(horizontal = 4.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Checkbox(
                                            checked = isChecked,
                                            onCheckedChange = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = name,
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = if (isChecked) FontWeight.Bold else FontWeight.Normal,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = com.example.data.model.formatBytes(size),
                                        fontSize = 9.5.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = if (isChecked) NeonCyan else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = entriesList.isEmpty() || selectedEntryPaths.isNotEmpty(),
                onClick = {
                    val clean = targetFolderName.trim().ifBlank { "extracted" }
                    val filterList = if (entriesList.isNotEmpty() && selectedEntryPaths.size < entriesList.size) {
                        selectedEntryPaths.toList()
                    } else {
                        null
                    }
                    onExtractEntries(clean, filterList)
                }
            ) {
                Icon(Icons.Default.Unarchive, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                val buttonLabel = if (entriesList.isNotEmpty() && selectedEntryPaths.size < entriesList.size) {
                    "Extract Selected (${selectedEntryPaths.size})"
                } else {
                    "Decompress All"
                }
                Text(buttonLabel, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun DetailedMultiFormatCompressionDialog(
    sources: List<File>,
    currentDir: File,
    onDismiss: () -> Unit,
    onConfirmCompress: (File, CompressionJobConfig) -> Unit
) {
    val baseDefaultName = remember(sources) {
        if (sources.size == 1) {
            UniversalArchiveEngine.stripArchiveExtension(sources.first().name).ifBlank { "archive" }
        } else {
            "DDM_Archive_${System.currentTimeMillis() / 1000}"
        }
    }
    var selectedFormat by remember { mutableStateOf(UniversalCompressionFormat.ZIP) }
    var archiveBaseName by remember { mutableStateOf(baseDefaultName) }
    var selectedLevel by remember { mutableStateOf(CompressionLevelPreset.NORMAL) }
    var includeHiddenFiles by remember { mutableStateOf(true) }
    var preserveRootFolder by remember { mutableStateOf(sources.size > 1 || sources.firstOrNull()?.isDirectory == true) }
    var excludePatternCsv by remember { mutableStateOf("") }
    var archiveComment by remember { mutableStateOf("") }
    var deleteSourceAfter by remember { mutableStateOf(false) }

    val finalFileName = remember(archiveBaseName, selectedFormat) {
        val cleanBase = archiveBaseName.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "archive" }
        val suffix = selectedFormat.extensionSuffix
        if (cleanBase.endsWith(suffix, ignoreCase = true)) cleanBase else "$cleanBase$suffix"
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.45f)),
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 380.dp)
                .testTag("detailed_compression_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.FolderZip, contentDescription = null, tint = NeonCyan)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Advanced Multi-Format Compressor",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = "${sources.size} source item(s) • Output: $finalFileName",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = NeonCyan,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(26.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", modifier = Modifier.size(16.dp))
                    }
                }

                OutlinedTextField(
                    value = archiveBaseName,
                    onValueChange = { archiveBaseName = it },
                    label = { Text("Archive Name (extension ${selectedFormat.extensionSuffix} added automatically)", fontSize = 10.5.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = "ARCHIVE FORMAT (${UniversalCompressionFormat.entries.size} VARIETIES)",
                    fontSize = 9.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.ExtraBold,
                    color = WarningOrange
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    UniversalCompressionFormat.entries.forEach { fmt ->
                        val isSelected = selectedFormat == fmt
                        Surface(
                            shape = RoundedCornerShape(9.dp),
                            color = if (isSelected) WarningOrange.copy(alpha = 0.22f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) WarningOrange else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                            ),
                            modifier = Modifier
                                .clip(RoundedCornerShape(9.dp))
                                .clickable { selectedFormat = fmt }
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = fmt.label,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (isSelected) WarningOrange else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = fmt.description,
                                    fontSize = 8.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                Text(
                    text = "COMPRESSION LEVEL & RATIO",
                    fontSize = 9.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.ExtraBold,
                    color = NeonCyan
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CompressionLevelPreset.entries.forEach { preset ->
                        val isSelected = selectedLevel == preset
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedLevel = preset },
                            label = {
                                Text(preset.label, fontSize = 10.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium)
                            }
                        )
                    }
                }

                OutlinedTextField(
                    value = excludePatternCsv,
                    onValueChange = { excludePatternCsv = it },
                    label = { Text("Exclude Wildcard Patterns (e.g. *.tmp, *.log, *.bak)", fontSize = 10.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (selectedFormat in setOf(UniversalCompressionFormat.ZIP, UniversalCompressionFormat.JAR, UniversalCompressionFormat.APK)) {
                    OutlinedTextField(
                        value = archiveComment,
                        onValueChange = { archiveComment = it },
                        label = { Text("Archive Metadata Comment (Optional)", fontSize = 10.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                CompressionToggleOptionRow(
                    title = "Include Hidden Dot-Files (.nomedia)",
                    subtitle = "Pack files and folders starting with '.'",
                    checked = includeHiddenFiles,
                    onCheckedChange = { includeHiddenFiles = it }
                )

                CompressionToggleOptionRow(
                    title = "Preserve Root Folder Structure",
                    subtitle = "Keep top-level directory name inside archive",
                    checked = preserveRootFolder,
                    onCheckedChange = { preserveRootFolder = it }
                )

                CompressionToggleOptionRow(
                    title = "Delete Source Files After Compression",
                    subtitle = "Remove original files once archive is verified",
                    checked = deleteSourceAfter,
                    onCheckedChange = { deleteSourceAfter = it }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(Modifier.width(6.dp))
                    Button(
                        onClick = {
                            val outArchive = File(currentDir, finalFileName)
                            val cfg = CompressionJobConfig(
                                archiveBaseName = archiveBaseName.trim().ifBlank { "archive" },
                                format = selectedFormat,
                                levelPreset = selectedLevel,
                                includeHiddenFiles = includeHiddenFiles,
                                preserveRootFolderStructure = preserveRootFolder,
                                excludePatternCsv = excludePatternCsv,
                                archiveComment = archiveComment,
                                deleteSourceAfterCompression = deleteSourceAfter
                            )
                            onConfirmCompress(outArchive, cfg)
                        }
                    ) {
                        Icon(Icons.Default.FolderZip, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Compress (${selectedFormat.extensionSuffix.trimStart('.').uppercase(Locale.US)})", fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun CompressionToggleOptionRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, fontSize = 9.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun FileManagerSortDialog(
    currentField: FileManagerSortField,
    isAscending: Boolean,
    onSelect: (FileManagerSortField, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Sort Files & Folders", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FileManagerSortField.entries.forEach { field ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onSelect(field, isAscending) }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = field.label,
                            fontSize = 12.5.sp,
                            fontWeight = if (currentField == field) FontWeight.ExtraBold else FontWeight.Medium,
                            color = if (currentField == field) NeonCyan else MaterialTheme.colorScheme.onSurface
                        )
                        RadioButton(
                            selected = currentField == field,
                            onClick = { onSelect(field, isAscending) }
                        )
                    }
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = isAscending,
                        onClick = { onSelect(currentField, true) },
                        label = { Text("Ascending (A→Z / Small→Large)", fontSize = 10.5.sp) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = !isAscending,
                        onClick = { onSelect(currentField, false) },
                        label = { Text("Descending (Newest / Largest)", fontSize = 10.5.sp) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        }
    )
}

@Composable
private fun FileManagerAdvancedMenuDialog(
    showHiddenFiles: Boolean,
    isRootEnabled: Boolean,
    hasFullStorageAccess: Boolean,
    isFloatingActive: Boolean,
    isAppDrawerShortcutEnabled: Boolean,
    vaultItemCount: Int,
    vaultTotalBytes: Long,
    isVaultUnlocked: Boolean,
    trashItemCount: Int,
    trashTotalBytes: Long,
    onReturnedToDdm: () -> Unit,
    onExit: () -> Unit,
    onFloatWindow: () -> Unit,
    onRequestFullStorageAccess: () -> Unit,
    onToggleRootAccess: () -> Unit,
    onToggleHiddenFiles: () -> Unit,
    onOpenPrivateVault: () -> Unit,
    onOpenTrashBin: () -> Unit,
    onCreateNewFolder: () -> Unit,
    onCreateNewTextFile: () -> Unit,
    onSelectAll: () -> Unit,
    onOpenApkExtractor: () -> Unit,
    onOpenApkEditor: () -> Unit,
    onOpenSystemFilesApp: () -> Unit,
    onToggleAppDrawerShortcut: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .width(320.dp)
                .heightIn(max = 640.dp)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    text = "DRAGON ARCHIVER OPTIONS",
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.ExtraBold,
                    color = NeonCyan,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )

                // Always pinned at the top of the 3-dot menu
                ProActionSheetRow(
                    icon = Icons.Default.CloudDownload,
                    tint = SuccessGreen,
                    title = "Return to DDM",
                    subtitle = "Switch back to DDM • Running tasks keep going",
                    modifier = Modifier.testTag("fm_menu_return_ddm_button"),
                    onClick = onReturnedToDdm
                )
                ProActionSheetRow(
                    icon = Icons.AutoMirrored.Filled.ExitToApp,
                    tint = DangerRed,
                    title = "Exit File Manager",
                    subtitle = "Close file manager & return to app drawer",
                    modifier = Modifier.testTag("fm_menu_exit_button"),
                    onClick = onExit
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))

                // Scrollable middle tool options
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    ProActionSheetRow(
                        icon = Icons.Default.PictureInPictureAlt,
                        tint = NeonCyan,
                        title = if (isFloatingActive) "Float Dragon Archiver (Active)" else "Float Dragon Archiver",
                        subtitle = "Manually resizable floating window over DDM & other apps",
                        onClick = onFloatWindow
                    )
                    ProActionSheetRow(
                        icon = Icons.Default.Android,
                        tint = SuccessGreen,
                        title = "APK Extractor",
                        subtitle = "Extract installed apps into DDM/${DragonApkStudioEngine.EXTRACTED_APK_FOLDER_NAME}",
                        onClick = onOpenApkExtractor
                    )
                    ProActionSheetRow(
                        icon = Icons.Default.BuildCircle,
                        tint = NeonCyan,
                        title = "APK Editor Pro",
                        subtitle = "Edit installed, downloaded & stored APKs (AXML, Strings, Icon, DEX, Sign)",
                        onClick = onOpenApkEditor
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    ProActionSheetRow(
                        icon = Icons.Default.FolderSpecial,
                        tint = if (hasFullStorageAccess) SuccessGreen else WarningOrange,
                        title = if (hasFullStorageAccess) {
                            "Internal Storage Access: FULL"
                        } else {
                            "Grant Full Internal Storage Access"
                        },
                        subtitle = if (hasFullStorageAccess) {
                            if (isRootEnabled) "Active with Root & All Files Access"
                            else "Active without Root (All Files Access enabled)"
                        } else {
                            "Tap to enable full non-root read/write access to Internal Storage"
                        },
                        modifier = Modifier.testTag("fm_menu_full_storage_access_button"),
                        onClick = onRequestFullStorageAccess
                    )
                    ProActionSheetRow(
                        icon = Icons.Default.Security,
                        tint = if (isRootEnabled) SuccessGreen else WarningOrange,
                        title = if (isRootEnabled) "Root Option: ENABLED" else "Root Option (Android/data & obb)",
                        subtitle = if (isRootEnabled) {
                            "Android/data & obb copy/paste permission granted"
                        } else {
                            "Grant/enable Root for Android/data & obb copy/paste"
                        },
                        onClick = onToggleRootAccess
                    )
                    ProActionSheetRow(
                        icon = Icons.Default.CreateNewFolder,
                        tint = WarningOrange,
                        title = "New Folder",
                        subtitle = "Create directory in current path",
                        onClick = onCreateNewFolder
                    )
                    ProActionSheetRow(
                        icon = Icons.Default.NoteAdd,
                        tint = NeonCyan,
                        title = "New Document / File",
                        subtitle = "Create .txt, .pdf, .xml, .json, .sav, or .docx file",
                        onClick = onCreateNewTextFile
                    )
                    ProActionSheetRow(
                        icon = Icons.Default.SelectAll,
                        tint = SuccessGreen,
                        title = "Select All Items",
                        subtitle = "Enter batch operation mode",
                        onClick = onSelectAll
                    )
                    ProActionSheetRow(
                        icon = if (showHiddenFiles) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        tint = MaterialTheme.colorScheme.primary,
                        title = if (showHiddenFiles) "Hide Hidden Dot-Files" else "Show Hidden Dot-Files (.nomedia)",
                        subtitle = "Toggle visibility of files starting with '.'",
                        onClick = onToggleHiddenFiles
                    )
                    ProActionSheetRow(
                        icon = if (isVaultUnlocked) Icons.Default.LockOpen else Icons.Default.EnhancedEncryption,
                        tint = Color(0xFFA855F7),
                        title = if (vaultItemCount > 0) "Private Vault ($vaultItemCount)" else "Private Vault",
                        subtitle = if (vaultItemCount > 0) {
                            "$vaultItemCount encrypted item(s) • ${formatTrashBytes(vaultTotalBytes)} — Password-protected storage"
                        } else {
                            "Password-protected encrypted vault • Hide & lock private files"
                        },
                        modifier = Modifier.testTag("fm_menu_private_vault_button"),
                        onClick = onOpenPrivateVault
                    )
                    ProActionSheetRow(
                        icon = Icons.Default.DeleteSweep,
                        tint = if (trashItemCount > 0) DangerRed else WarningOrange,
                        title = if (trashItemCount > 0) "Trash Bin ($trashItemCount)" else "Trash Bin",
                        subtitle = if (trashItemCount > 0) {
                            "$trashItemCount deleted item(s) • ${formatTrashBytes(trashTotalBytes)} — Restore or empty"
                        } else {
                            "Restore accidentally deleted files to original location"
                        },
                        modifier = Modifier.testTag("fm_menu_trash_bin_button"),
                        onClick = onOpenTrashBin
                    )
                    val cacheState by com.example.data.cache.DragonSmartCacheController.cacheStateFlow.collectAsState()
                    val cacheCtx = LocalContext.current
                    LaunchedEffect(Unit) {
                        com.example.data.cache.DragonSmartCacheController.refreshSnapshotAsync(cacheCtx)
                    }
                    ProActionSheetRow(
                        icon = Icons.Default.CleaningServices,
                        tint = NeonCyan,
                        title = "Smart Clean App Cache (${com.example.data.cache.DragonSmartCacheController.formatBytes(cacheState.totalCacheBytes)})",
                        subtitle = "Auto-Quota: ${com.example.data.cache.DragonSmartCacheController.formatBytes(cacheState.maxBudgetBytes)} • Tap to trim staging & preview cache now",
                        onClick = {
                            com.example.data.cache.DragonSmartCacheController.runSmartMaintenanceAsync(
                                context = cacheCtx,
                                aggressive = true,
                                force = true
                            ) { freed ->
                                val freedStr = com.example.data.cache.DragonSmartCacheController.formatBytes(freed)
                                ApkPackageInstaller.showToastOnMain(
                                    cacheCtx,
                                    "Smart Cache Cleaned • Freed $freedStr"
                                )
                            }
                        }
                    )
                    ProActionSheetRow(
                        icon = Icons.Default.OpenInNew,
                        tint = WarningOrange,
                        title = "Open Android System Files App",
                        subtitle = "Launch external OS document viewer",
                        onClick = onOpenSystemFilesApp
                    )
                }

                // Pinned at the bottom: Dragon Archiver App Drawer Shortcut toggle with custom logo
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isAppDrawerShortcutEnabled) {
                        NeonCyan.copy(alpha = 0.12f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    },
                    border = BorderStroke(
                        0.9.dp,
                        if (isAppDrawerShortcutEnabled) NeonCyan.copy(alpha = 0.55f)
                        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onToggleAppDrawerShortcut(!isAppDrawerShortcutEnabled) }
                        .testTag("archiver_app_drawer_shortcut_row")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val shortcutLogoCtx = LocalContext.current
                        AsyncImage(
                            model = remember(shortcutLogoCtx) {
                                ImageRequest.Builder(shortcutLogoCtx)
                                    .data(R.drawable.ic_dragon_archiver_v2_clean)
                                    .size(112, 112)
                                    .crossfade(false)
                                    .build()
                            },
                            contentDescription = "Dragon Archiver App Drawer Icon",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .border(1.dp, NeonCyan.copy(alpha = 0.6f), RoundedCornerShape(9.dp))
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "App Drawer Shortcut",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (isAppDrawerShortcutEnabled) {
                                    "Enabled • Shown in App Drawer as Dragon Archiver"
                                } else {
                                    "Add Dragon Archiver icon to App Drawer"
                                },
                                fontSize = 10.sp,
                                color = if (isAppDrawerShortcutEnabled) NeonCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 13.sp
                            )
                        }
                        Switch(
                            checked = isAppDrawerShortcutEnabled,
                            onCheckedChange = onToggleAppDrawerShortcut,
                            modifier = Modifier.testTag("archiver_app_drawer_shortcut_switch")
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateFolderOrFileDialog(
    title: String,
    placeholder: String,
    initialValue: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var input by remember { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold) },
        text = {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text(placeholder, fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(input) },
                enabled = input.trim().isNotEmpty()
            ) {
                Text(confirmLabel, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun BatchRenameDialog(
    files: List<File>,
    onDismiss: () -> Unit,
    onConfirmBatchRename: (String, Int) -> Unit
) {
    var prefix by remember { mutableStateOf("DDM_File") }
    var startNumberText by remember { mutableStateOf("1") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Batch Rename (${files.size} Items)", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = prefix,
                    onValueChange = { prefix = it },
                    label = { Text("Base Name Prefix") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = startNumberText,
                    onValueChange = { startNumberText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Start Index Number") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                val startNum = startNumberText.toIntOrNull() ?: 1
                val sampleExt = files.firstOrNull()?.extension?.takeIf { it.isNotBlank() }?.let { ".$it" }.orEmpty()
                Text(
                    text = "Preview: ${prefix}_$startNum$sampleExt, ${prefix}_${startNum + 1}$sampleExt...",
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = NeonCyan
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val startNum = startNumberText.toIntOrNull() ?: 1
                    onConfirmBatchRename(prefix.trim().ifBlank { "File" }, startNum)
                }
            ) {
                Text("Rename All", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun FileManagerDeleteConfirmDialog(
    items: List<FileItemEntry>,
    onDismiss: () -> Unit,
    onMoveToTrash: () -> Unit,
    onDeletePermanently: () -> Unit,
    onDeleteFromDdmListOnly: (() -> Unit)?
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, WarningOrange.copy(alpha = 0.5f)),
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 340.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(WarningOrange.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = WarningOrange)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (items.size == 1) "Delete ${items.first().name}?"
                            else "Delete ${items.size} Selected Items?",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Deleted items can be stored safely in Trash Bin:",
                            fontSize = 10.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Primary Recommended Action: Move to Trash Bin
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = WarningOrange.copy(alpha = 0.14f),
                    border = BorderStroke(1.1.dp, WarningOrange.copy(alpha = 0.6f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onMoveToTrash)
                        .testTag("delete_dialog_move_to_trash_btn")
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Default.RestoreFromTrash, contentDescription = null, tint = WarningOrange)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Move to Trash Bin",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = WarningOrange
                            )
                            Text(
                                "Restore anytime to its exact original location",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (onDeleteFromDdmListOnly != null) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(onClick = onDeleteFromDdmListOnly)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Default.RemoveCircleOutline, contentDescription = null, tint = NeonCyan)
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Delete from list only", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    "Remove from download list • Keep file in storage",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = DangerRed.copy(alpha = 0.10f),
                    border = BorderStroke(1.dp, DangerRed.copy(alpha = 0.42f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onDeletePermanently)
                        .testTag("delete_dialog_permanent_btn")
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Default.DeleteForever, contentDescription = null, tint = DangerRed)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Delete permanently",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = DangerRed
                            )
                            Text(
                                "Skip Trash Bin and erase immediately from storage",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
}

private val fileTimestampFormatter = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue(): SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
}

internal fun formatFileTimestampFast(epochMs: Long): String {
    return fileTimestampFormatter.get()?.format(Date(epochMs)) ?: ""
}

private fun buildDirectoryCacheKey(
    dir: File,
    category: FileManagerFilterCategory,
    sortField: FileManagerSortField,
    sortAscending: Boolean,
    showHidden: Boolean
): String {
    return "${dir.absolutePath}|${category.ordinal}|${sortField.ordinal}|$sortAscending|$showHidden"
}

private fun resolveBestDdmRootDirectoryFast(context: Context): File {
    val publicDdm = runCatching {
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "DDM"
        )
    }.getOrNull()
    if (publicDdm != null) {
        return publicDdm
    }
    return File(
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir,
        "DDM"
    )
}

private fun resolveBestDdmRootDirectory(context: Context): File {
    val publicDdm = runCatching {
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "DDM"
        )
    }.getOrNull()

    if (publicDdm != null && (publicDdm.exists() || publicDdm.mkdirs()) && publicDdm.canRead()) {
        ensureDdmSubfoldersExist(publicDdm)
        return publicDdm
    }

    val appExternalDdm = File(
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir,
        "DDM"
    ).apply { mkdirs() }
    ensureDdmSubfoldersExist(appExternalDdm)
    return appExternalDdm
}

private fun ensureDdmSubfoldersExist(ddmRoot: File) {
    runCatching {
        ddmRoot.mkdirs()
        DownloadFolderCategory.entries.forEach { cat ->
            File(ddmRoot, cat.folderName).mkdirs()
        }
        DragonApkStudioEngine.ensureExtractedApkFolder(ddmRoot)
    }
}

private fun loadDirectoryEntries(
    context: Context,
    currentDir: File,
    ddmRootDir: File,
    appPrivateDir: File,
    tasks: List<DownloaderTask>,
    searchQuery: String,
    category: FileManagerFilterCategory,
    sortField: FileManagerSortField,
    sortAscending: Boolean,
    showHidden: Boolean
): List<FileItemEntry> {
    val tasksByPath = tasks.mapNotNull { t ->
        t.filePath?.takeIf { it.isNotBlank() }?.let { it to t }
    }.toMap()
    val tasksByName = tasks.associateBy { it.filename.lowercase(Locale.US) }

    val entriesByPath = LinkedHashMap<String, FileItemEntry>()
    val queryLower = searchQuery.trim().lowercase(Locale.US)
    val isCurrentDirProtected = AndroidDataObbBridge.isRootProtectedRomPath(currentDir.absolutePath)

    fun shouldIncludeEntry(name: String, isDir: Boolean, ext: String): Boolean {
        if (!showHidden && name.startsWith(".")) return false
        if (queryLower.isNotEmpty() && !name.lowercase(Locale.US).contains(queryLower)) {
            return false
        }
        if (category != FileManagerFilterCategory.ALL) {
            if (isDir) return false
            if (!matchesFilterCategory(ext, category)) return false
        }
        return true
    }

    fun addFileEntry(file: File, forceIsDir: Boolean? = null, sizeOverride: Long? = null, lastModOverride: Long? = null) {
        val isDir = forceIsDir ?: file.isDirectory
        val ext = if (isDir) "" else file.extension.lowercase(Locale.US)
        if (!shouldIncludeEntry(file.name, isDir, ext)) return

        // Fast child counting: NEVER spawn per-subfolder `su` shells!
        // Use lightweight `file.list()?.size` (bare String[] without allocating File[]) on normal paths only.
        val childCount = if (isDir) {
            if (isCurrentDirProtected || AndroidDataObbBridge.isRootProtectedRomPath(file.absolutePath)) {
                -1
            } else {
                runCatching { file.list()?.size ?: -1 }.getOrDefault(-1)
            }
        } else 0
        val linked = tasksByPath[file.absolutePath] ?: tasksByName[file.name.lowercase(Locale.US)]
        val lengthBytes = if (isDir) 0L else (sizeOverride ?: runCatching { file.length() }.getOrDefault(0L))
        val lastMod = (lastModOverride ?: runCatching { file.lastModified() }.getOrDefault(0L)).let {
            if (it > 0L) it else System.currentTimeMillis()
        }

        entriesByPath[file.absolutePath] = FileItemEntry(
            file = file,
            name = file.name,
            isDirectory = isDir,
            lengthBytes = lengthBytes,
            lastModifiedMs = lastMod,
            extension = ext,
            childCount = childCount,
            linkedTask = linked
        )
    }

    if (category != FileManagerFilterCategory.ALL || searchQuery.isNotBlank()) {
        val rawFiles = mutableListOf<File>()
        collectFilesRecursively(currentDir, rawFiles, showHidden, maxDepth = 4)
        if (currentDir.absolutePath == ddmRootDir.absolutePath) {
            tasks.filter { it.stage == DownloadStateStage.COMPLETED }.forEach { t ->
                val f = t.filePath?.let { File(it) }
                if (f != null && f.exists() && rawFiles.none { it.absolutePath == f.absolutePath }) {
                    rawFiles.add(f)
                }
            }
            collectFilesRecursively(appPrivateDir, rawFiles, showHidden, maxDepth = 3)
        }
        rawFiles.forEach { f ->
            if (f.exists()) addFileEntry(f)
        }
        AndroidDataObbBridge.listEntriesInDirectory(currentDir, context).forEach { entry ->
            addFileEntry(
                file = entry.file,
                forceIsDir = entry.isDirectory,
                sizeOverride = entry.lengthBytes,
                lastModOverride = entry.lastModifiedMs
            )
        }
    } else {
        val entries = AndroidDataObbBridge.listEntriesInDirectory(currentDir, context)
        if (entries.isNotEmpty()) {
            entries.forEach { entry ->
                addFileEntry(
                    file = entry.file,
                    forceIsDir = entry.isDirectory,
                    sizeOverride = entry.lengthBytes,
                    lastModOverride = entry.lastModifiedMs
                )
            }
        } else {
            currentDir.listFiles()?.forEach { child ->
                addFileEntry(child)
            }
        }

        // Also surface any completed DDM tasks belonging to this category folder
        val currentFolderCategory = DownloadFolderCategory.entries.firstOrNull {
            it.folderName.equals(currentDir.name, ignoreCase = true)
        }
        if (currentFolderCategory != null) {
            tasks.filter { it.stage == DownloadStateStage.COMPLETED }.forEach { task ->
                val taskCat = DragonDownloadManager.categoryForType(
                    extension = task.container,
                    mimeType = task.mimeType,
                    isAudioOnly = task.isAudioOnly,
                    isGenericFile = task.isGenericFile,
                    url = task.url,
                    platform = task.platform
                )
                if (taskCat == currentFolderCategory) {
                    val f = task.filePath?.let { File(it) }
                    if (f != null && f.exists() && !entriesByPath.containsKey(f.absolutePath)) {
                        addFileEntry(f)
                    }
                }
            }
        }
    }

    val comparator = Comparator<FileItemEntry> { a, b ->
        // Always keep folders grouped before files
        if (a.isDirectory != b.isDirectory) {
            return@Comparator if (a.isDirectory) -1 else 1
        }
        val rawCmp = when (sortField) {
            FileManagerSortField.NAME -> a.name.compareTo(b.name, ignoreCase = true)
            FileManagerSortField.DATE -> a.lastModifiedMs.compareTo(b.lastModifiedMs)
            FileManagerSortField.SIZE -> {
                if (a.isDirectory) a.childCount.compareTo(b.childCount)
                else a.lengthBytes.compareTo(b.lengthBytes)
            }
            FileManagerSortField.TYPE -> a.extension.compareTo(b.extension, ignoreCase = true)
        }
        if (sortAscending) rawCmp else -rawCmp
    }

    return entriesByPath.values.sortedWith(comparator)
}

private fun collectFilesRecursively(
    dir: File,
    out: MutableList<File>,
    showHidden: Boolean,
    maxDepth: Int,
    currentDepth: Int = 0
) {
    if (currentDepth > maxDepth) return
    val children = runCatching { dir.listFiles() }.getOrNull()
        ?: if (currentDepth == 0) AndroidDataObbBridge.listEntriesInDirectory(dir).map { it.file }.toTypedArray() else null
        ?: return
    for (child in children) {
        if (!showHidden && child.name.startsWith(".")) continue
        out.add(child)
        if (child.isDirectory) {
            collectFilesRecursively(child, out, showHidden, maxDepth, currentDepth + 1)
        }
    }
}

private fun matchesFilterCategory(ext: String, category: FileManagerFilterCategory): Boolean {
    val dotExt = if (ext.isNotEmpty()) ".$ext" else ""
    return when (category) {
        FileManagerFilterCategory.ALL -> true
        FileManagerFilterCategory.PACKAGES -> ext in setOf("apk", "apks", "xapk", "apkm", "aab", "obb", "exe", "msi", "deb", "rpm", "dmg")
        FileManagerFilterCategory.VIDEOS -> dotExt in com.example.data.model.MediaPlatform.VIDEO_EXTENSIONS
        FileManagerFilterCategory.AUDIO -> dotExt in com.example.data.model.MediaPlatform.AUDIO_EXTENSIONS
        FileManagerFilterCategory.ARCHIVES -> dotExt in com.example.data.model.MediaPlatform.ARCHIVE_EXTENSIONS || ext == "bin"
        FileManagerFilterCategory.DOCUMENTS -> dotExt in com.example.data.model.MediaPlatform.DOCUMENT_EXTENSIONS ||
            DragonDocumentEngine.isDocumentCategoryExtension(ext)
        FileManagerFilterCategory.IMAGES -> dotExt in com.example.data.model.MediaPlatform.IMAGE_AND_FONT_EXTENSIONS
        FileManagerFilterCategory.TORRENTS -> ext == "torrent"
        FileManagerFilterCategory.SUBTITLES -> dotExt in com.example.data.model.MediaPlatform.SUBTITLE_EXTENSIONS
    }
}

private fun loadArchiveVirtualEntries(
    context: Context,
    archiveFile: File,
    virtualDirPath: String,
    searchQuery: String,
    category: FileManagerFilterCategory,
    sortField: FileManagerSortField,
    sortAscending: Boolean,
    showHidden: Boolean
): List<FileItemEntry> {
    val queryLower = searchQuery.trim().lowercase(Locale.US)
    val nodes: List<ArchiveInternalNode> = if (queryLower.isNotEmpty() || category != FileManagerFilterCategory.ALL) {
        UniversalArchiveEngine.buildCompleteVirtualTree(context, archiveFile)
    } else {
        UniversalArchiveEngine.listVirtualDirectoryInsideArchive(context, archiveFile, virtualDirPath)
    }

    val filtered = nodes.mapNotNull { node ->
        if (!showHidden && node.name.startsWith(".")) return@mapNotNull null
        if (queryLower.isNotEmpty() && !node.name.lowercase(Locale.US).contains(queryLower)) {
            return@mapNotNull null
        }
        val ext = if (node.isDirectory) "" else node.name.substringAfterLast('.', "").lowercase(Locale.US)
        if (category != FileManagerFilterCategory.ALL) {
            if (node.isDirectory) return@mapNotNull null
            if (!matchesFilterCategory(ext, category)) return@mapNotNull null
        }
        FileItemEntry(
            file = File(archiveFile, node.fullEntryPath),
            name = node.name,
            isDirectory = node.isDirectory,
            lengthBytes = node.uncompressedSize,
            lastModifiedMs = node.lastModifiedMs,
            extension = ext,
            childCount = node.childCount,
            linkedTask = null,
            archiveEntryPath = node.fullEntryPath,
            compressedSizeBytes = node.compressedSize
        )
    }

    val comparator = Comparator<FileItemEntry> { a, b ->
        if (a.isDirectory != b.isDirectory) {
            return@Comparator if (a.isDirectory) -1 else 1
        }
        val rawCmp = when (sortField) {
            FileManagerSortField.NAME -> a.name.compareTo(b.name, ignoreCase = true)
            FileManagerSortField.DATE -> a.lastModifiedMs.compareTo(b.lastModifiedMs)
            FileManagerSortField.SIZE -> {
                if (a.isDirectory) a.childCount.compareTo(b.childCount)
                else a.lengthBytes.compareTo(b.lengthBytes)
            }
            FileManagerSortField.TYPE -> a.extension.compareTo(b.extension, ignoreCase = true)
        }
        if (sortAscending) rawCmp else -rawCmp
    }

    return filtered.sortedWith(comparator)
}

private fun buildBreadcrumbSegments(currentDir: File): List<File> {
    val list = mutableListOf<File>()
    var cursor: File? = currentDir
    while (cursor != null) {
        list.add(0, cursor)
        if (cursor.name.equals("0", ignoreCase = true) ||
            cursor.parentFile == null
        ) {
            break
        }
        cursor = cursor.parentFile
    }
    return list
}

internal fun resolveFileEntryIcon(item: FileItemEntry): ImageVector {
    if (item.isDirectory) {
        return when (item.name.lowercase(Locale.US)) {
            "program", "extracted apk" -> Icons.Default.Android
            "video" -> Icons.Default.VideoLibrary
            "audio" -> Icons.Default.LibraryMusic
            "compressed" -> Icons.Default.FolderZip
            "document" -> Icons.Default.Description
            "photo" -> Icons.Default.Image
            "torrent" -> Icons.Default.Hub
            "subtitle" -> Icons.Default.Subtitles
            else -> Icons.Default.Folder
        }
    }
    val ext = item.extension
    val dotExt = ".$ext"
    return when {
        ApkPackageInstaller.isInstallableExtension(ext) -> Icons.Default.Android
        ext == "bin" || ext == "iso" -> Icons.Default.Memory
        UniversalArchiveEngine.isSupportedArchiveFileName(item.name) -> Icons.Default.FolderZip
        dotExt in com.example.data.model.MediaPlatform.VIDEO_EXTENSIONS -> Icons.Default.Movie
        dotExt in com.example.data.model.MediaPlatform.AUDIO_EXTENSIONS -> Icons.Default.AudioFile
        dotExt in com.example.data.model.MediaPlatform.ARCHIVE_EXTENSIONS -> Icons.Default.FolderZip
        dotExt in com.example.data.model.MediaPlatform.IMAGE_EXTENSIONS -> Icons.Default.Image
        dotExt in com.example.data.model.MediaPlatform.SUBTITLE_EXTENSIONS -> Icons.Default.Subtitles
        ext == "pdf" -> Icons.Default.PictureAsPdf
        ext == "dex" -> Icons.Default.Memory
        com.example.data.filemanager.DragonPcapEngine.isCaptureExtension(ext) -> Icons.Default.Router
        ext in setOf("sav", "ue4sav", "es3", "srm", "dat") -> Icons.Default.SaveAs
        ext in setOf("xml", "json", "html", "htm", "yaml", "yml", "kt", "java", "py", "js", "ts", "sh", "sql", "css") -> Icons.Default.Code
        ext in setOf("csv", "tsv", "xlsx", "xls", "ods") -> Icons.Default.TableChart
        ext in setOf("docx", "doc", "odt", "rtf", "pptx", "ppt") -> Icons.Default.Article
        ext == "torrent" -> Icons.Default.Hub
        isEditableTextExtension(ext) -> Icons.Default.Description
        else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
}

internal fun resolveFileEntryColor(item: FileItemEntry): Color {
    if (item.isDirectory) {
        return when (item.name.lowercase(Locale.US)) {
            "program", "extracted apk" -> SuccessGreen
            "video" -> Color(0xFF38BDF8)
            "audio" -> Color(0xFFA855F7)
            "compressed" -> WarningOrange
            "document" -> Color(0xFF60A5FA)
            "photo" -> Color(0xFFF472B6)
            "torrent" -> Color(0xFF8B5CF6)
            "subtitle" -> Color(0xFF34D399)
            else -> WarningOrange
        }
    }
    val ext = item.extension
    val dotExt = ".$ext"
    return when {
        ApkPackageInstaller.isInstallableExtension(ext) -> SuccessGreen
        ext == "dex" -> Color(0xFFA855F7)
        com.example.data.filemanager.DragonPcapEngine.isCaptureExtension(ext) -> Color(0xFF06B6D4)
        ext == "bin" || ext == "iso" -> WarningOrange
        UniversalArchiveEngine.isSupportedArchiveFileName(item.name) -> WarningOrange
        dotExt in com.example.data.model.MediaPlatform.VIDEO_EXTENSIONS -> Color(0xFF38BDF8)
        dotExt in com.example.data.model.MediaPlatform.AUDIO_EXTENSIONS -> Color(0xFFA855F7)
        dotExt in com.example.data.model.MediaPlatform.ARCHIVE_EXTENSIONS -> WarningOrange
        dotExt in com.example.data.model.MediaPlatform.IMAGE_EXTENSIONS -> Color(0xFFF472B6)
        dotExt in com.example.data.model.MediaPlatform.SUBTITLE_EXTENSIONS -> Color(0xFF34D399)
        ext == "pdf" -> DangerRed
        ext in setOf("sav", "ue4sav", "es3", "srm", "dat") -> WarningOrange
        ext in setOf("csv", "tsv", "xlsx", "xls", "ods") -> SuccessGreen
        ext in setOf("docx", "doc", "odt", "rtf", "pptx", "ppt") -> Color(0xFF60A5FA)
        ext == "torrent" -> Color(0xFF8B5CF6)
        else -> NeonCyan
    }
}

private fun isEditableTextExtension(ext: String): Boolean {
    return DragonDocumentEngine.isSupportedDocumentExtension(ext) ||
        com.example.data.filemanager.DragonPcapEngine.isCaptureExtension(ext)
}

private fun handlePrimaryFileTap(
    context: Context,
    item: FileItemEntry,
    siblingAudioFiles: List<File>,
    downloadManager: DragonDownloadManager,
    onBrowseInsideArchive: () -> Unit,
    onOpenArchiveInspector: () -> Unit,
    onOpenTextEditor: () -> Unit,
    onOpenActionSheet: () -> Unit
) {
    val ext = item.extension
    when {
        ext == "apk" -> {
            // For standalone .apk, open the package action sheet (with Install, Auto-Sign & Install, or View Inside Archive)
            onOpenActionSheet()
        }
        UniversalArchiveEngine.isSupportedArchiveFile(item.file) -> {
            // Directly browse inside the compressed file without decompressing it!
            onBrowseInsideArchive()
        }
        ApkPackageInstaller.isInstallableExtension(ext) -> {
            onOpenActionSheet()
        }
        com.example.data.audio.DragonAudioPlayerEngine.isAudioExtension(ext) -> {
            com.example.data.audio.DragonAudioPlayerEngine.playFile(
                context = context,
                file = item.file,
                siblingAudioFiles = siblingAudioFiles
            )
        }
        isEditableTextExtension(ext) -> {
            onOpenTextEditor()
        }
        item.linkedTask != null -> {
            downloadManager.openDownloadedFile(context, item.linkedTask)
        }
        else -> {
            openFileWithSystemViewer(context, item.file)
        }
    }
}

private fun resolveMimeTypeForFile(file: File): String {
    val ext = file.extension.lowercase(Locale.US)
    if (ApkPackageInstaller.isInstallableExtension(ext)) {
        return ApkPackageInstaller.MIME_ANDROID_PACKAGE
    }
    if (com.example.data.filemanager.DragonPcapEngine.isCaptureExtension(ext)) {
        return "application/vnd.tcpdump.pcap"
    }
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
}

private fun openFileWithSystemViewer(context: Context, file: File) {
    try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val mime = resolveMimeTypeForFile(file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        ApkPackageInstaller.showToastOnMain(
            context,
            "No external app installed to open ${file.name}"
        )
    }
}

private fun shareMultipleFiles(context: Context, files: List<File>) {
    try {
        val uris = ArrayList(
            files.mapNotNull { f ->
                runCatching {
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
                }.getOrNull()
            }
        )
        if (uris.isEmpty()) return
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = resolveMimeTypeForFile(files.first())
                putExtra(Intent.EXTRA_STREAM, uris.first())
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        val chooser = Intent.createChooser(intent, "Share via Dragon Archiver").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    } catch (e: Exception) {
        ApkPackageInstaller.showToastOnMain(context, "Unable to share file(s): ${e.message}")
    }
}

private fun performClipboardPaste(
    context: Context,
    clip: FileManagerClipboardState,
    targetDir: File
): Int {
    AndroidDataObbBridge.createDirectory(context, targetDir)
    var successCount = 0
    clip.files.forEach { src ->
        if (!AndroidDataObbBridge.existsUniversal(context, src)) return@forEach
        val dest = resolveUniqueDestinationFile(context, targetDir, src.name)
        val ok = runCatching {
            val copied = AndroidDataObbBridge.copyFileOrDirectoryUniversal(context, src, dest)
            if (copied && clip.operation == ClipboardTransferOperation.CUT && src.absolutePath != dest.absolutePath) {
                AndroidDataObbBridge.deleteFileOrDirectory(context, src)
            }
            copied
        }.getOrDefault(false)
        if (ok) successCount++
    }
    return successCount
}

private fun duplicateFileOrFolder(context: Context, src: File): File? {
    if (!AndroidDataObbBridge.existsUniversal(context, src)) return null
    val parent = src.parentFile ?: return null
    val isDir = AndroidDataObbBridge.isDirectoryUniversal(context, src)
    val base = src.nameWithoutExtension.ifBlank { src.name }
    val ext = src.extension.takeIf { it.isNotBlank() && !isDir }?.let { ".$it" }.orEmpty()
    val dest = resolveUniqueDestinationFile(context, parent, "${base}_copy$ext")
    return runCatching {
        if (AndroidDataObbBridge.copyFileOrDirectoryUniversal(context, src, dest, isDir)) {
            dest
        } else null
    }.getOrNull()
}

private fun resolveUniqueDestinationFile(context: Context, dir: File, desiredName: String): File {
    var candidate = File(dir, desiredName)
    if (!AndroidDataObbBridge.existsUniversal(context, candidate)) return candidate
    val base = candidate.nameWithoutExtension.ifBlank { desiredName }
    val ext = candidate.extension.takeIf { it.isNotBlank() }?.let { ".$it" }.orEmpty()
    var index = 1
    while (AndroidDataObbBridge.existsUniversal(context, candidate)) {
        candidate = File(dir, "${base}_($index)$ext")
        index++
    }
    return candidate
}

private fun compressFilesToZipArchive(sources: List<File>, outputZip: File): Boolean {
    return try {
        outputZip.parentFile?.mkdirs()
        ZipOutputStream(BufferedOutputStream(FileOutputStream(outputZip))).use { zos ->
            fun addFileToZip(file: File, parentPath: String) {
                val entryPath = if (parentPath.isEmpty()) file.name else "$parentPath/${file.name}"
                if (file.isDirectory) {
                    file.listFiles()?.forEach { child ->
                        addFileToZip(child, entryPath)
                    }
                } else if (file.isFile && file.absolutePath != outputZip.absolutePath) {
                    val entry = ZipEntry(entryPath)
                    entry.time = file.lastModified()
                    zos.putNextEntry(entry)
                    BufferedInputStream(FileInputStream(file)).use { input ->
                        input.copyTo(zos, 64 * 1024)
                    }
                    zos.closeEntry()
                }
            }
            sources.forEach { src ->
                if (src.exists()) addFileToZip(src, "")
            }
        }
        outputZip.exists() && outputZip.length() > 0L
    } catch (_: Exception) {
        false
    }
}

private fun extractZipArchiveToDirectory(archiveFile: File, targetDir: File): Int {
    var count = 0
    try {
        targetDir.mkdirs()
        val canonicalTarget = targetDir.canonicalPath
        ZipFile(archiveFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val cleanName = entry.name.replace('\\', '/').trimStart('/')
                if (cleanName.isBlank()) continue
                val outFile = File(targetDir, cleanName)
                // Zip-Slip path traversal protection
                if (!outFile.canonicalPath.startsWith(canonicalTarget)) continue
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(outFile).use { out ->
                            input.copyTo(out, 64 * 1024)
                        }
                    }
                    count++
                }
            }
        }
    } catch (_: Exception) {}
    return count
}

private fun computeFileChecksums(context: Context, file: File): FileHashResult? {
    return try {
        val md5 = MessageDigest.getInstance("MD5")
        val sha1 = MessageDigest.getInstance("SHA-1")
        val sha256 = MessageDigest.getInstance("SHA-256")
        val crc32 = CRC32()
        val stream = AndroidDataObbBridge.openInputStreamForFile(context, file) ?: return null
        stream.use { input ->
            val buffer = ByteArray(64 * 1024)
            var read = input.read(buffer)
            while (read > 0) {
                md5.update(buffer, 0, read)
                sha1.update(buffer, 0, read)
                sha256.update(buffer, 0, read)
                crc32.update(buffer, 0, read)
                read = input.read(buffer)
            }
        }
        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
        FileHashResult(
            md5 = md5.digest().toHex(),
            sha1 = sha1.digest().toHex(),
            sha256 = sha256.digest().toHex(),
            crc32 = "%08x".format(crc32.value).uppercase(Locale.US)
        )
    } catch (_: Exception) {
        null
    }
}

private fun calculateDirectorySizeAndCount(dir: File): Pair<Long, Int> {
    var totalBytes = 0L
    var fileCount = 0
    fun walk(current: File, depth: Int) {
        if (depth > 6 || !current.exists()) return
        current.listFiles()?.forEach { child ->
            if (child.isFile) {
                totalBytes += child.length()
                fileCount++
            } else if (child.isDirectory) {
                walk(child, depth + 1)
            }
        }
    }
    walk(dir, 0)
    return totalBytes to fileCount
}

private fun cleanEmptySubfolders(rootDir: File): Int {
    var deleted = 0
    val protectedNames = DownloadFolderCategory.entries.map { it.folderName.lowercase(Locale.US) }.toSet() +
        DragonApkStudioEngine.EXTRACTED_APK_FOLDER_NAME.lowercase(Locale.US)
    rootDir.listFiles()?.forEach { child ->
        if (child.isDirectory) {
            child.listFiles()?.forEach { sub ->
                if (sub.isDirectory && (sub.listFiles()?.isEmpty() == true)) {
                    if (runCatching { sub.delete() }.getOrDefault(false)) deleted++
                }
            }
            if (child.name.lowercase(Locale.US) !in protectedNames && (child.listFiles()?.isEmpty() == true)) {
                if (runCatching { child.delete() }.getOrDefault(false)) deleted++
            }
        }
    }
    return deleted
}
