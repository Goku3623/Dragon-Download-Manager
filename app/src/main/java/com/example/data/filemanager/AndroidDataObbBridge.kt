package com.example.data.filemanager

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.example.data.root.RootHelper
import kotlinx.coroutines.CancellationException
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Universal Internal Storage & Root-Aware File Engine for Dragon File Manager.
 *
 * Provides full read/write/browse/copy/move/delete/create access across Internal Storage
 * (`/storage/emulated/0`, `/sdcard`, and subdirectories including `Download`, `DCIM`, `Pictures`,
 * `Movies`, `Music`, `Documents`, `Android/media`, `Android/data`, `Android/obb`)
 * **both with Root access AND without Root access**:
 *
 * 1. **Without Root (Non-Root Mode)**:
 *    - Checks and requests `MANAGE_EXTERNAL_STORAGE` ("All Files Access" on Android 11+) or
 *      `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE` (on Android 10 and below).
 *    - Combines direct `java.io.File` I/O, non-root user shell (`sh -c`) fallback, standard internal
 *      storage directory discovery (`Download`, `DDM`, `DCIM`, `Pictures`, `Movies`, `Music`,
 *      `Documents`, `Android`, etc.), persisted SAF (Storage Access Framework) `DocumentsContract`
 *      tree grants for `Android/data` & `Android/obb`, and `PackageManager` installed package folder
 *      discovery inside `Android/data` & `Android/obb`.
 *
 * 2. **With Root (Root Mode)**:
 *    - Uses privileged root file operations when enabled by the user for `/`, `/data`, `/system`,
 *      `Android/data`, and `Android/obb`, alongside standard Android All Files Access for direct I/O.
 */
object AndroidDataObbBridge {

    private const val PREFS_NAME = "dragon_storage_access_prefs"
    private const val KEY_SAF_TREE_URIS = "persisted_saf_tree_uris"
    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    val STANDARD_INTERNAL_STORAGE_FOLDERS: List<String> = listOf(
        "Download",
        "DDM",
        "DCIM",
        "Pictures",
        "Movies",
        "Music",
        "Documents",
        "Android",
        "Alarms",
        "Notifications",
        "Podcasts",
        "Ringtones",
        "Audiobooks",
        "Recordings"
    )

    @Volatile
    var isRootFileManagerEnabled: Boolean = RootHelper.isRootAvailable

    @Volatile
    private var hasSyncedRootStoragePerms: Boolean = false

    private val directoryEntriesCache = ConcurrentHashMap<String, List<RootDirEntry>>()

    fun getCachedEntriesInDirectory(dir: File): List<RootDirEntry>? {
        return directoryEntriesCache[dir.absolutePath]
    }

    fun invalidateDirectoryCache(dirPath: String? = null) {
        if (dirPath == null) {
            directoryEntriesCache.clear()
        } else {
            directoryEntriesCache.remove(dirPath)
        }
    }

    data class RootDirEntry(
        val name: String,
        val file: File,
        val isDirectory: Boolean,
        val lengthBytes: Long,
        val lastModifiedMs: Long
    )

    private fun shellQuote(path: String): String {
        return "'" + path.replace("'", "'\\''") + "'"
    }

    fun isRootActive(): Boolean {
        if (RootHelper.isRootAvailable) {
            isRootFileManagerEnabled = true
            return true
        }
        return isRootFileManagerEnabled && RootHelper.isRootAvailable
    }

    /**
     * Checks whether the app currently has full read/write access to Internal Storage
     * either via Non-Root OS permission (`Environment.isExternalStorageManager()` on API 30+,
     * or `READ/WRITE_EXTERNAL_STORAGE` on API <= 29) OR via Root access.
     */
    fun hasFullInternalStorageAccess(context: Context): Boolean {
        if (isRootActive()) return true
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { Environment.isExternalStorageManager() }.getOrDefault(false) ||
                canDirectlyReadAndWriteInternalRoot()
        } else {
            val readGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            val writeGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            (readGranted && writeGranted) || canDirectlyReadAndWriteInternalRoot()
        }
    }

    private fun canDirectlyReadAndWriteInternalRoot(): Boolean {
        val root = getExternalStorageRoot()
        return runCatching {
            root.exists() && root.canRead() && root.list() != null
        }.getOrDefault(false)
    }

    /**
     * Ensures full internal storage access with or without root:
     * - Returns `true` if full internal storage access is active (via non-root All Files Access or Root).
     */
    fun ensureAllFilesAccessWithOrWithoutRoot(context: Context): Boolean {
        if (hasFullInternalStorageAccess(context)) {
            if (RootHelper.isRootAvailable && !hasSyncedRootStoragePerms) {
                hasSyncedRootStoragePerms = true
                RootHelper.grantStorageAndDataObbRootPermissionsBlocking(context.packageName)
            }
            return true
        }
        if (RootHelper.isRootAvailable || RootHelper.checkOrRequestRootBlocking()) {
            isRootFileManagerEnabled = true
            hasSyncedRootStoragePerms = true
            RootHelper.grantStorageAndDataObbRootPermissionsBlocking(context.packageName)
            invalidateDirectoryCache()
            return true
        }
        return hasFullInternalStorageAccess(context)
    }

    /**
     * Builds the system Settings intent to grant "All Files Access" (`MANAGE_EXTERNAL_STORAGE`)
     * on non-rooted Android 11+ devices, with fallback to general All Files Access or App Details.
     */
    fun buildManageAllFilesAccessIntent(context: Context): Intent {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val perAppIntent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            if (perAppIntent.resolveActivity(context.packageManager) != null) {
                return perAppIntent
            }
            val globalIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            if (globalIntent.resolveActivity(context.packageManager) != null) {
                return globalIntent
            }
        }
        return Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        )
    }

    fun openManageAllFilesAccessSettings(context: Context): Boolean {
        return try {
            val intent = buildManageAllFilesAccessIntent(context).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            try {
                val fallback = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * Builds a Storage Access Framework (`ACTION_OPEN_DOCUMENT_TREE`) intent pre-targeted at
     * `Android/data`, `Android/obb`, or Internal Storage root for non-root access to restricted folders.
     */
    fun buildSafDocumentTreeIntent(targetDir: File): Intent {
        val docId = toExternalStorageDocumentId(targetDir)
        val initialUri = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, docId)
        return Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
            }
        }
    }

    fun persistSafTreeUriGrant(context: Context, treeUri: Uri): Boolean {
        return try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching {
                context.contentResolver.takePersistableUriPermission(treeUri, flags)
            }
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val existing = prefs.getStringSet(KEY_SAF_TREE_URIS, emptySet())?.toMutableSet() ?: mutableSetOf()
            existing.add(treeUri.toString())
            prefs.edit().putStringSet(KEY_SAF_TREE_URIS, existing).apply()
            invalidateDirectoryCache()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun hasSafTreeGrantForDirectory(context: Context, dir: File): Boolean {
        return resolveSafMatchingTree(context, dir) != null
    }

    private data class SafTreeMatch(
        val treeUri: Uri,
        val treeDocId: String,
        val targetDocId: String
    )

    private fun toExternalStorageDocumentId(file: File): String {
        val normPath = normalizeStoragePath(file.absolutePath)
        val extRoot = normalizeStoragePath(getExternalStorageRoot().absolutePath)
        val rel = when {
            normPath.equals(extRoot, ignoreCase = true) -> ""
            normPath.startsWith("$extRoot/", ignoreCase = true) -> normPath.substring(extRoot.length + 1)
            normPath.equals("/sdcard", ignoreCase = true) -> ""
            normPath.startsWith("/sdcard/", ignoreCase = true) -> normPath.removePrefix("/sdcard/")
            else -> normPath.trimStart('/')
        }.trim('/')
        return if (rel.isEmpty()) "primary:" else "primary:$rel"
    }

    private fun resolveSafMatchingTree(context: Context, targetFile: File): SafTreeMatch? {
        if (!isInternalStoragePath(targetFile.absolutePath)) return null
        val targetDocId = toExternalStorageDocumentId(targetFile)
        val persistedUris = LinkedHashSet<Uri>()
        runCatching {
            context.contentResolver.persistedUriPermissions
                .filter { it.isReadPermission }
                .forEach { persistedUris.add(it.uri) }
        }
        runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getStringSet(KEY_SAF_TREE_URIS, emptySet())?.forEach { raw ->
                runCatching { Uri.parse(raw) }.getOrNull()?.let { persistedUris.add(it) }
            }
        }
        for (treeUri in persistedUris) {
            val treeDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: continue
            if (targetDocId.equals(treeDocId, ignoreCase = true) ||
                targetDocId.startsWith("$treeDocId/", ignoreCase = true) ||
                (treeDocId == "primary:" && targetDocId.startsWith("primary:", ignoreCase = true))
            ) {
                return SafTreeMatch(
                    treeUri = treeUri,
                    treeDocId = treeDocId,
                    targetDocId = targetDocId
                )
            }
        }
        return null
    }

    private fun listViaSafDocumentTree(context: Context, dir: File): List<RootDirEntry> {
        val match = resolveSafMatchingTree(context, dir) ?: return emptyList()
        val childrenUri = runCatching {
            DocumentsContract.buildChildDocumentsUriUsingTree(match.treeUri, match.targetDocId)
        }.getOrNull() ?: return emptyList()

        val entries = ArrayList<RootDirEntry>()
        val nowMs = System.currentTimeMillis()
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(childrenUri, projection, null, null, null)
            if (cursor != null) {
                val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val modIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                while (cursor.moveToNext()) {
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx) else null
                    if (name.isNullOrBlank()) continue
                    val mime = if (mimeIdx >= 0) cursor.getString(mimeIdx) else ""
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    val size = if (!isDir && sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else 0L
                    val lastMod = if (modIdx >= 0 && !cursor.isNull(modIdx)) cursor.getLong(modIdx) else nowMs
                    entries.add(
                        RootDirEntry(
                            name = name,
                            file = File(dir, name),
                            isDirectory = isDir,
                            lengthBytes = size.coerceAtLeast(0L),
                            lastModifiedMs = if (lastMod > 0L) lastMod else nowMs
                        )
                    )
                }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { cursor?.close() }
        }
        return entries
    }

    /**
     * Checks if the main app has already been granted root permission by the Root Manager,
     * and if so, automatically enables Root Mode and grants `MANAGE_EXTERNAL_STORAGE` + `Android/data` & `Android/obb` access.
     */
    fun syncWithMainAppRootStatus(context: Context, mainAppRootGranted: Boolean): Boolean {
        val granted = if (mainAppRootGranted || RootHelper.isRootAvailable) {
            true
        } else {
            RootHelper.checkOrRequestRootBlocking()
        }
        if (granted) {
            isRootFileManagerEnabled = true
            if (!hasSyncedRootStoragePerms) {
                hasSyncedRootStoragePerms = true
                RootHelper.grantStorageAndDataObbRootPermissionsBlocking(context.packageName)
            }
        }
        return granted && isRootFileManagerEnabled
    }

    /**
     * Toggles or requests Root Access from the File Manager 3-dot menu.
     */
    fun toggleOrRequestRootInFileManager(context: Context, enable: Boolean): Boolean {
        if (!enable) {
            isRootFileManagerEnabled = false
            invalidateDirectoryCache()
            return false
        }
        val granted = RootHelper.checkOrRequestRootBlocking()
        if (granted) {
            isRootFileManagerEnabled = true
            hasSyncedRootStoragePerms = true
            RootHelper.grantStorageAndDataObbRootPermissionsBlocking(context.packageName)
            invalidateDirectoryCache()
            return true
        }
        isRootFileManagerEnabled = false
        return false
    }

    fun getExternalStorageRoot(): File {
        @Suppress("DEPRECATION")
        val envRoot = runCatching { Environment.getExternalStorageDirectory() }.getOrNull()
        if (envRoot != null) return envRoot
        val emulatedZero = File("/storage/emulated/0")
        if (emulatedZero.exists()) return emulatedZero
        val sdcard = File("/sdcard")
        return if (sdcard.exists()) sdcard else emulatedZero
    }

    fun getRomRootDir(): File = File("/")
    fun getAndroidDir(): File = File(getExternalStorageRoot(), "Android")
    fun getAndroidDataDir(): File = File(getAndroidDir(), "data")
    fun getAndroidObbDir(): File = File(getAndroidDir(), "obb")
    fun getAndroidMediaDir(): File = File(getAndroidDir(), "media")

    private fun normalizeStoragePath(path: String): String {
        val clean = path.replace('\\', '/').trimEnd('/').ifEmpty { "/" }
        val extRoot = runCatching {
            @Suppress("DEPRECATION")
            Environment.getExternalStorageDirectory()?.absolutePath?.replace('\\', '/')?.trimEnd('/')
        }.getOrNull() ?: "/storage/emulated/0"
        return when {
            clean.equals("/sdcard", ignoreCase = true) -> extRoot
            clean.startsWith("/sdcard/", ignoreCase = true) -> extRoot + clean.removePrefix("/sdcard")
            clean.equals("/storage/self/primary", ignoreCase = true) -> extRoot
            clean.startsWith("/storage/self/primary/", ignoreCase = true) -> extRoot + clean.removePrefix("/storage/self/primary")
            else -> clean
        }
    }

    fun isInternalStorageRootPath(path: String): Boolean {
        val norm = normalizeStoragePath(path)
        val extRoot = normalizeStoragePath(getExternalStorageRoot().absolutePath)
        return norm.equals(extRoot, ignoreCase = true)
    }

    fun isInternalStoragePath(path: String): Boolean {
        val norm = normalizeStoragePath(path)
        val extRoot = normalizeStoragePath(getExternalStorageRoot().absolutePath)
        return norm.equals(extRoot, ignoreCase = true) ||
            norm.startsWith("$extRoot/", ignoreCase = true)
    }

    fun isAndroidDataOrObbPath(path: String): Boolean {
        val norm = normalizeStoragePath(path)
        return norm.contains("/Android/data", ignoreCase = true) ||
            norm.contains("/Android/obb", ignoreCase = true)
    }

    fun isRootProtectedRomPath(path: String): Boolean {
        val norm = normalizeStoragePath(path)
        if (isAndroidDataOrObbPath(norm)) return true
        // Any path outside public internal storage (/storage/emulated/0) and app sandbox is a ROM root path
        return !isInternalStoragePath(norm)
    }

    private fun buildRemountPrefixIfNeeded(targetPath: String): String {
        val norm = targetPath.replace('\\', '/')
        return if (norm.startsWith("/system") || norm.startsWith("/vendor") || norm.startsWith("/product") || norm == "/") {
            "mount -o rw,remount / 2>/dev/null; mount -o rw,remount /system 2>/dev/null; mount -o rw,remount /vendor 2>/dev/null; "
        } else {
            ""
        }
    }

    /**
     * Lists directory entries across Internal Storage and ROM Root both with and without Root access:
     * 1. When Root is active and browsing a protected path (`Android/data`, `Android/obb`, or `/` ROM root),
     *    uses `su -c ls -1pAL` and merges with direct `File.listFiles()`.
     * 2. For Internal Storage (with or without Root):
     *    - Combines direct `dir.listFiles()`, non-root user shell (`sh -c ls -1pAL`) fallback, and
     *      persisted SAF `DocumentsContract` tree entries (for non-root `Android/data` & `Android/obb`).
     *    - On Internal Storage root (`/storage/emulated/0`), also synthesizes/merges accessible standard
     *      internal storage folders (`Download`, `DDM`, `DCIM`, `Pictures`, `Movies`, `Music`, `Documents`, `Android`, etc.)
     *      so users can always navigate Internal Storage even before toggling permissions.
     *    - On `Android/data` and `Android/obb` without root, also discovers installed app package directories
     *      so existing app folders are visible and navigable.
     */
    fun listEntriesInDirectory(dir: File, context: Context? = null): List<RootDirEntry> {
        val rootActive = isRootActive()
        val isProtected = isRootProtectedRomPath(dir.absolutePath)
        val isInternalRoot = isInternalStorageRootPath(dir.absolutePath)
        val mergedByName = LinkedHashMap<String, RootDirEntry>()
        val nowMs = System.currentTimeMillis()

        fun mergeEntries(entries: Iterable<RootDirEntry>) {
            for (entry in entries) {
                if (entry.name.isBlank()) continue
                val existing = mergedByName[entry.name]
                if (existing == null || (!existing.isDirectory && entry.isDirectory) || (existing.lengthBytes == 0L && entry.lengthBytes > 0L)) {
                    mergedByName[entry.name] = entry
                }
            }
        }

        // 1. Direct Java File.listFiles() (fastest path across /storage/emulated/0 with All Files Access or Root appops)
        val directList = runCatching { dir.listFiles() }.getOrNull()
        if (!directList.isNullOrEmpty()) {
            val directEntries = ArrayList<RootDirEntry>(directList.size)
            for (f in directList) {
                val isDir = f.isDirectory
                directEntries.add(
                    RootDirEntry(
                        name = f.name,
                        file = f,
                        isDirectory = isDir,
                        lengthBytes = if (isDir) 0L else f.length(),
                        lastModifiedMs = f.lastModified().let { if (it > 0L) it else nowMs }
                    )
                )
            }
            mergeEntries(directEntries)
        }

        // 2. When Root is active and either browsing a protected path or directList was empty/restricted, merge root shell listing
        if (rootActive && (isProtected || mergedByName.isEmpty())) {
            mergeEntries(listViaShell(dir, useRoot = true))
        }

        // 3. Non-root user shell (`sh -c`) fallback when directList was empty or browsing Android/data, Android/obb, or Android/media
        if (mergedByName.isEmpty() || (isAndroidDataOrObbPath(dir.absolutePath) && !rootActive)) {
            mergeEntries(listViaShell(dir, useRoot = false))
        }

        // 4. Non-root SAF (Storage Access Framework) DocumentTree fallback if user granted SAF access to Android/data or Android/obb
        if (context != null && (mergedByName.isEmpty() || isAndroidDataOrObbPath(dir.absolutePath))) {
            mergeEntries(listViaSafDocumentTree(context, dir))
        }

        // 5. Standard Internal Storage folder discovery on /storage/emulated/0 so top-level folders are always reachable
        if (isInternalRoot) {
            val canonicalRoot = getExternalStorageRoot()
            for (folderName in STANDARD_INTERNAL_STORAGE_FOLDERS) {
                if (mergedByName.containsKey(folderName)) continue
                val candidate = File(canonicalRoot, folderName)
                val existsOrStandard = runCatching { candidate.exists() || candidate.isDirectory }.getOrDefault(false) ||
                    folderName in setOf("Download", "DDM", "DCIM", "Pictures", "Movies", "Music", "Documents", "Android")
                if (existsOrStandard) {
                    val lastMod = runCatching { candidate.lastModified() }.getOrDefault(0L)
                    mergedByName[folderName] = RootDirEntry(
                        name = folderName,
                        file = candidate,
                        isDirectory = true,
                        lengthBytes = 0L,
                        lastModifiedMs = if (lastMod > 0L) lastMod else nowMs
                    )
                }
            }
        }

        // 6. Ensure Android/data, Android/obb, and Android/media are always visible inside /storage/emulated/0/Android
        val normPath = normalizeStoragePath(dir.absolutePath)
        val normAndroidDir = normalizeStoragePath(getAndroidDir().absolutePath)
        if (normPath.equals(normAndroidDir, ignoreCase = true)) {
            for (subName in listOf("data", "obb", "media")) {
                if (!mergedByName.containsKey(subName)) {
                    val subFile = File(dir, subName)
                    val lastMod = runCatching { subFile.lastModified() }.getOrDefault(0L)
                    mergedByName[subName] = RootDirEntry(
                        name = subName,
                        file = subFile,
                        isDirectory = true,
                        lengthBytes = 0L,
                        lastModifiedMs = if (lastMod > 0L) lastMod else nowMs
                    )
                }
            }
        }

        // 7. Non-root package folder discovery inside Android/data and Android/obb via PackageManager & own sandbox
        if (context != null && !rootActive) {
            val normDataDir = normalizeStoragePath(getAndroidDataDir().absolutePath)
            val normObbDir = normalizeStoragePath(getAndroidObbDir().absolutePath)
            if (normPath.equals(normDataDir, ignoreCase = true) || normPath.equals(normObbDir, ignoreCase = true)) {
                // Always include our own app's external data/obb directory which is 100% accessible without root
                val ownPkgDir = File(dir, context.packageName)
                if (!mergedByName.containsKey(context.packageName)) {
                    runCatching {
                        if (normPath.equals(normDataDir, ignoreCase = true)) {
                            context.getExternalFilesDir(null)
                        } else {
                            context.obbDir
                        }
                    }
                    if (runCatching { ownPkgDir.exists() }.getOrDefault(false)) {
                        mergedByName[context.packageName] = RootDirEntry(
                            name = context.packageName,
                            file = ownPkgDir,
                            isDirectory = true,
                            lengthBytes = 0L,
                            lastModifiedMs = ownPkgDir.lastModified().let { if (it > 0L) it else nowMs }
                        )
                    }
                }
                // Probe installed packages via stat/File.exists() or PackageManager
                runCatching {
                    val installedApps = context.packageManager.getInstalledApplications(0)
                    for (appInfo in installedApps) {
                        val pkg = appInfo.packageName ?: continue
                        if (mergedByName.containsKey(pkg)) continue
                        val pkgFolder = File(dir, pkg)
                        if (runCatching { pkgFolder.exists() || pkgFolder.isDirectory }.getOrDefault(false)) {
                            val lastMod = runCatching { pkgFolder.lastModified() }.getOrDefault(0L)
                            mergedByName[pkg] = RootDirEntry(
                                name = pkg,
                                file = pkgFolder,
                                isDirectory = true,
                                lengthBytes = 0L,
                                lastModifiedMs = if (lastMod > 0L) lastMod else nowMs
                            )
                        }
                    }
                }
            }
        }

        val finalEntries = mergedByName.values.toList()
        directoryEntriesCache[dir.absolutePath] = finalEntries
        return finalEntries
    }

    private fun listViaShell(dir: File, useRoot: Boolean): List<RootDirEntry> {
        val qDir = shellQuote(dir.absolutePath)
        val cmd = "ls -1pAL $qDir 2>/dev/null || ls -1pA $qDir 2>/dev/null"
        val res = if (useRoot) {
            RootHelper.runRootShellCommand(cmd, timeoutMs = 6_000L)
        } else {
            RootHelper.runUserShellCommand(cmd, timeoutMs = 4_000L)
        }
        if (res.exitCode != 0 || res.output.isBlank()) return emptyList()

        val nowMs = System.currentTimeMillis()
        val dirPath = dir.absolutePath
        val isRootSlash = dirPath == "/"
        val lines = res.output.lines()
        val entries = ArrayList<RootDirEntry>(lines.size)
        for (raw in lines) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed == "./" || trimmed == "../") continue
            if (trimmed.startsWith("ls:") || trimmed.contains("Permission denied", ignoreCase = true)) continue
            val isDir = trimmed.endsWith("/")
            val cleanName = trimmed.removeSuffix("/").removeSuffix("@").removeSuffix("*")
            if (cleanName.isEmpty()) continue
            val childFile = if (isRootSlash) File("/$cleanName") else File(dir, cleanName)
            val length = if (!isDir) {
                runCatching { childFile.length() }.getOrDefault(0L)
            } else 0L
            val lastMod = runCatching { childFile.lastModified() }.getOrDefault(0L)
            entries.add(
                RootDirEntry(
                    name = cleanName,
                    file = childFile,
                    isDirectory = isDir,
                    lengthBytes = length,
                    lastModifiedMs = if (lastMod > 0L) lastMod else nowMs
                )
            )
        }
        return entries
    }

    fun existsUniversal(context: Context, file: File): Boolean {
        if (runCatching { file.exists() }.getOrDefault(false)) return true
        val match = resolveSafMatchingTree(context, file)
        if (match != null) {
            val docUri = runCatching {
                DocumentsContract.buildDocumentUriUsingTree(match.treeUri, match.targetDocId)
            }.getOrNull()
            if (docUri != null && querySafDocumentExists(context, docUri)) {
                return true
            }
        }
        val qPath = shellQuote(file.absolutePath)
        val userRes = RootHelper.runUserShellCommand("[ -e $qPath ] && echo 1 || echo 0", timeoutMs = 3_000L)
        if (userRes.output.trim() == "1") return true
        if (!isRootActive()) return false
        val rootRes = RootHelper.runRootShellCommand("[ -e $qPath ] && echo 1 || echo 0", timeoutMs = 4_000L)
        return rootRes.output.trim() == "1"
    }

    private fun querySafDocumentExists(context: Context, docUri: Uri): Boolean {
        var cursor: Cursor? = null
        return try {
            cursor = context.contentResolver.query(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null,
                null,
                null
            )
            cursor != null && cursor.moveToFirst()
        } catch (_: Exception) {
            false
        } finally {
            runCatching { cursor?.close() }
        }
    }

    fun isDirectoryUniversal(context: Context, file: File): Boolean {
        if (runCatching { file.exists() && file.canRead() }.getOrDefault(false)) return file.isDirectory
        val match = resolveSafMatchingTree(context, file)
        if (match != null) {
            val docUri = runCatching {
                DocumentsContract.buildDocumentUriUsingTree(match.treeUri, match.targetDocId)
            }.getOrNull()
            if (docUri != null) {
                var cursor: Cursor? = null
                try {
                    cursor = context.contentResolver.query(
                        docUri,
                        arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE),
                        null,
                        null,
                        null
                    )
                    if (cursor != null && cursor.moveToFirst()) {
                        return cursor.getString(0) == DocumentsContract.Document.MIME_TYPE_DIR
                    }
                } catch (_: Exception) {
                } finally {
                    runCatching { cursor?.close() }
                }
            }
        }
        val qPath = shellQuote(file.absolutePath)
        val userRes = RootHelper.runUserShellCommand("[ -d $qPath ] && echo 1 || echo 0", timeoutMs = 3_000L)
        if (userRes.output.trim() == "1") return true
        if (!isRootActive()) return file.isDirectory
        val res = RootHelper.runRootShellCommand("[ -d $qPath ] && echo 1 || echo 0", timeoutMs = 4_000L)
        return res.output.trim() == "1"
    }

    fun estimateTotalBytesUniversal(context: Context, file: File, depth: Int = 0): Long {
        if (depth > 8) return 0L
        if (runCatching { file.exists() && file.canRead() }.getOrDefault(false)) {
            return if (file.isFile) {
                file.length().coerceAtLeast(0L)
            } else {
                file.listFiles()?.sumOf { estimateTotalBytesUniversal(context, it, depth + 1) } ?: 0L
            }
        }
        val qPath = shellQuote(file.absolutePath)
        val userRes = RootHelper.runUserShellCommand("du -sb $qPath 2>/dev/null | awk '{print \$1}'", timeoutMs = 3_500L)
        val userParsed = userRes.output.lineSequence().firstOrNull()?.trim()?.toLongOrNull()
        if (userParsed != null && userParsed > 0L) return userParsed

        if (isRootActive()) {
            val res = RootHelper.runRootShellCommand("du -sb $qPath 2>/dev/null | awk '{print \$1}'", timeoutMs = 5_000L)
            val parsed = res.output.lineSequence().firstOrNull()?.trim()?.toLongOrNull()
            if (parsed != null && parsed >= 0L) return parsed
        }
        return 0L
    }

    fun createDirectory(context: Context, targetDir: File): Boolean {
        // 1. Direct Java File.mkdirs()
        if (runCatching { targetDir.mkdirs() || targetDir.isDirectory }.getOrDefault(false) &&
            runCatching { targetDir.exists() }.getOrDefault(false)
        ) {
            invalidateDirectoryCache(targetDir.parentFile?.absolutePath)
            return true
        }
        // 2. Non-Root SAF DocumentContract directory creation
        val parentFile = targetDir.parentFile
        if (parentFile != null) {
            val parentMatch = resolveSafMatchingTree(context, parentFile)
            if (parentMatch != null) {
                val parentDocUri = runCatching {
                    DocumentsContract.buildDocumentUriUsingTree(parentMatch.treeUri, parentMatch.targetDocId)
                }.getOrNull()
                if (parentDocUri != null) {
                    val createdUri = runCatching {
                        DocumentsContract.createDocument(
                            context.contentResolver,
                            parentDocUri,
                            DocumentsContract.Document.MIME_TYPE_DIR,
                            targetDir.name
                        )
                    }.getOrNull()
                    if (createdUri != null) {
                        invalidateDirectoryCache(parentFile.absolutePath)
                        return true
                    }
                }
            }
        }
        // 3. Non-root user shell (`sh -c mkdir -p`)
        val qPath = shellQuote(targetDir.absolutePath)
        val userRes = RootHelper.runUserShellCommand("mkdir -p $qPath 2>/dev/null", timeoutMs = 4_000L)
        if (userRes.exitCode == 0 && runCatching { targetDir.exists() }.getOrDefault(false)) {
            invalidateDirectoryCache(targetDir.parentFile?.absolutePath)
            return true
        }
        // 4. Root shell (`su -c`) fallback
        if (!isRootActive()) return false
        val remount = buildRemountPrefixIfNeeded(targetDir.absolutePath)
        val res = RootHelper.runRootShellCommand("${remount}mkdir -p $qPath && chmod 775 $qPath 2>/dev/null", timeoutMs = 6_000L)
        val ok = res.exitCode == 0 || existsUniversal(context, targetDir)
        if (ok) invalidateDirectoryCache(targetDir.parentFile?.absolutePath)
        return ok
    }

    fun createEmptyFile(context: Context, targetFile: File): Boolean {
        // 1. Direct Java File creation
        val stdCreated = runCatching {
            targetFile.parentFile?.mkdirs()
            if (!targetFile.exists()) targetFile.createNewFile() else true
        }.getOrDefault(false)
        if (stdCreated) {
            invalidateDirectoryCache(targetFile.parentFile?.absolutePath)
            return true
        }
        // 2. Non-Root SAF DocumentContract file creation
        val parentFile = targetFile.parentFile
        if (parentFile != null) {
            val parentMatch = resolveSafMatchingTree(context, parentFile)
            if (parentMatch != null) {
                val parentDocUri = runCatching {
                    DocumentsContract.buildDocumentUriUsingTree(parentMatch.treeUri, parentMatch.targetDocId)
                }.getOrNull()
                if (parentDocUri != null) {
                    val createdUri = runCatching {
                        DocumentsContract.createDocument(
                            context.contentResolver,
                            parentDocUri,
                            "application/octet-stream",
                            targetFile.name
                        )
                    }.getOrNull()
                    if (createdUri != null) {
                        invalidateDirectoryCache(parentFile.absolutePath)
                        return true
                    }
                }
            }
        }
        // 3. Non-root user shell (`sh -c`)
        val qParent = shellQuote(targetFile.parentFile?.absolutePath ?: "/")
        val qFile = shellQuote(targetFile.absolutePath)
        val userRes = RootHelper.runUserShellCommand("mkdir -p $qParent && touch $qFile 2>/dev/null", timeoutMs = 4_000L)
        if (userRes.exitCode == 0 && runCatching { targetFile.exists() }.getOrDefault(false)) {
            invalidateDirectoryCache(targetFile.parentFile?.absolutePath)
            return true
        }
        // 4. Root shell (`su -c`) fallback
        if (!isRootActive()) return false
        val remount = buildRemountPrefixIfNeeded(targetFile.absolutePath)
        val res = RootHelper.runRootShellCommand("${remount}mkdir -p $qParent && touch $qFile && chmod 664 $qFile 2>/dev/null", timeoutMs = 6_000L)
        val ok = res.exitCode == 0 || existsUniversal(context, targetFile)
        if (ok) invalidateDirectoryCache(targetFile.parentFile?.absolutePath)
        return ok
    }

    fun deleteFileOrDirectory(context: Context, target: File): Boolean {
        val parentPath = target.parentFile?.absolutePath
        // 1. Direct Java File deletion
        if (runCatching {
                if (target.isDirectory) target.deleteRecursively() else target.delete()
            }.getOrDefault(false) && !target.exists()
        ) {
            invalidateDirectoryCache(parentPath)
            return true
        }
        // 2. Non-Root SAF DocumentContract deletion
        val match = resolveSafMatchingTree(context, target)
        if (match != null) {
            val docUri = runCatching {
                DocumentsContract.buildDocumentUriUsingTree(match.treeUri, match.targetDocId)
            }.getOrNull()
            if (docUri != null && runCatching { DocumentsContract.deleteDocument(context.contentResolver, docUri) }.getOrDefault(false)) {
                invalidateDirectoryCache(parentPath)
                return true
            }
        }
        // 3. Non-root user shell (`sh -c rm -rf`)
        val qPath = shellQuote(target.absolutePath)
        val userRes = RootHelper.runUserShellCommand("rm -rf $qPath 2>/dev/null", timeoutMs = 8_000L)
        if (userRes.exitCode == 0 && !runCatching { target.exists() }.getOrDefault(true)) {
            invalidateDirectoryCache(parentPath)
            return true
        }
        // 4. Root shell (`su -c`) fallback
        if (!isRootActive()) return false
        val remount = buildRemountPrefixIfNeeded(target.absolutePath)
        val res = RootHelper.runRootShellCommand("${remount}rm -rf $qPath", timeoutMs = 15_000L)
        if (res.exitCode == 0) invalidateDirectoryCache(parentPath)
        return res.exitCode == 0
    }

    fun renameFileOrDirectory(context: Context, target: File, newName: String): Boolean {
        val parent = target.parentFile ?: return false
        val dest = File(parent, newName)
        // 1. Direct Java File.renameTo
        if (runCatching { target.renameTo(dest) }.getOrDefault(false)) {
            invalidateDirectoryCache(parent.absolutePath)
            return true
        }
        // 2. Non-Root SAF DocumentContract rename
        val match = resolveSafMatchingTree(context, target)
        if (match != null) {
            val docUri = runCatching {
                DocumentsContract.buildDocumentUriUsingTree(match.treeUri, match.targetDocId)
            }.getOrNull()
            if (docUri != null && runCatching {
                    DocumentsContract.renameDocument(context.contentResolver, docUri, newName) != null
                }.getOrDefault(false)
            ) {
                invalidateDirectoryCache(parent.absolutePath)
                return true
            }
        }
        // 3. Non-root user shell (`sh -c mv -f`)
        val qSrc = shellQuote(target.absolutePath)
        val qDest = shellQuote(dest.absolutePath)
        val userRes = RootHelper.runUserShellCommand("mv -f $qSrc $qDest 2>/dev/null", timeoutMs = 5_000L)
        if (userRes.exitCode == 0 && runCatching { dest.exists() }.getOrDefault(false)) {
            invalidateDirectoryCache(parent.absolutePath)
            return true
        }
        // 4. Root shell (`su -c`) fallback
        if (!isRootActive()) return false
        val remount = buildRemountPrefixIfNeeded(target.absolutePath)
        val res = RootHelper.runRootShellCommand("${remount}mv -f $qSrc $qDest", timeoutMs = 8_000L)
        if (res.exitCode == 0) invalidateDirectoryCache(parent.absolutePath)
        return res.exitCode == 0
    }

    fun copyFileOrDirectoryUniversal(
        context: Context,
        source: File,
        dest: File,
        isDirectoryHint: Boolean? = null,
        onBytesCopied: ((deltaBytes: Long, currentFileName: String) -> Unit)? = null
    ): Boolean {
        DragonFileManagerTaskEngine.checkPauseOrCancel()
        val involvesRestrictedPath = isRootProtectedRomPath(source.absolutePath) || isRootProtectedRomPath(dest.absolutePath)

        // 1. Try direct Java File / universal stream I/O first
        val directSuccess = try {
            val isDir = isDirectoryHint ?: isDirectoryUniversal(context, source)
            if (isDir) {
                if (!createDirectory(context, dest)) {
                    false
                } else {
                    val children = source.listFiles()?.toList()
                        ?: listEntriesInDirectory(source, context).map { it.file }
                    var allOk = true
                    for (child in children) {
                        DragonFileManagerTaskEngine.checkPauseOrCancel()
                        val childDest = File(dest, child.name)
                        if (!copyFileOrDirectoryUniversal(context, child, childDest, child.isDirectory, onBytesCopied)) {
                            allOk = false
                            break
                        }
                    }
                    allOk
                }
            } else {
                val inStream = openInputStreamForFile(context, source)
                val outStream = if (inStream != null) openOutputStreamForFile(context, dest) else null
                if (inStream == null || outStream == null) {
                    runCatching { inStream?.close() }
                    runCatching { outStream?.close() }
                    false
                } else {
                    inStream.use { input ->
                        outStream.use { output ->
                            val buffer = ByteArray(128 * 1024)
                            var read = input.read(buffer)
                            while (read > 0) {
                                DragonFileManagerTaskEngine.checkPauseOrCancel()
                                output.write(buffer, 0, read)
                                onBytesCopied?.invoke(read.toLong(), source.name)
                                read = input.read(buffer)
                            }
                            output.flush()
                        }
                    }
                    existsUniversal(context, dest)
                }
            }
        } catch (ce: CancellationException) {
            if (!(isDirectoryHint ?: source.isDirectory)) {
                runCatching { dest.delete() }
            }
            throw ce
        } catch (_: Exception) {
            false
        }

        if (directSuccess) {
            invalidateDirectoryCache(dest.parentFile?.absolutePath)
            return true
        }

        DragonFileManagerTaskEngine.checkPauseOrCancel()

        // 2. Non-root user shell (`sh -c cp -rf`) fallback
        val qParent = shellQuote(dest.parentFile?.absolutePath ?: "/")
        val qSrc = shellQuote(source.absolutePath)
        val qDest = shellQuote(dest.absolutePath)
        val userCmd = "mkdir -p $qParent && cp -rf $qSrc $qDest 2>/dev/null"
        val userRes = RootHelper.runUserShellCommand(userCmd, timeoutMs = 45_000L)
        if (userRes.exitCode == 0 && runCatching { dest.exists() }.getOrDefault(false)) {
            val estBytes = estimateTotalBytesUniversal(context, dest)
            if (estBytes > 0L) onBytesCopied?.invoke(estBytes, source.name)
            invalidateDirectoryCache(dest.parentFile?.absolutePath)
            return true
        }

        // 3. Root shell (`su -c cp -rf`) fallback when Root is enabled or available
        if (isRootActive() || (involvesRestrictedPath && RootHelper.checkOrRequestRootBlocking().also { if (it) isRootFileManagerEnabled = true })) {
            val estBytes = estimateTotalBytesUniversal(context, source)
            val remount = buildRemountPrefixIfNeeded(dest.absolutePath)
            val cmd = "${remount}mkdir -p $qParent && cp -rf $qSrc $qDest"
            val res = RootHelper.runRootShellCommand(cmd, timeoutMs = 120_000L)
            DragonFileManagerTaskEngine.checkPauseOrCancel()
            if (res.exitCode == 0) {
                if (estBytes > 0L) {
                    onBytesCopied?.invoke(estBytes, source.name)
                }
                invalidateDirectoryCache(dest.parentFile?.absolutePath)
                return true
            }
        }

        return false
    }

    fun openInputStreamForFile(context: Context, file: File): InputStream? {
        // 1. Direct FileInputStream
        runCatching {
            if (file.exists() && file.canRead()) {
                return BufferedInputStream(FileInputStream(file), 128 * 1024)
            }
        }
        // 2. Non-Root SAF DocumentContract ContentResolver InputStream
        val match = resolveSafMatchingTree(context, file)
        if (match != null) {
            val docUri = runCatching {
                DocumentsContract.buildDocumentUriUsingTree(match.treeUri, match.targetDocId)
            }.getOrNull()
            if (docUri != null) {
                val safStream = runCatching { context.contentResolver.openInputStream(docUri) }.getOrNull()
                if (safStream != null) {
                    return BufferedInputStream(safStream, 128 * 1024)
                }
            }
        }
        // 3. Non-root user shell (`sh -c cat`) stream
        val qFile = shellQuote(file.absolutePath)
        runCatching {
            val check = RootHelper.runUserShellCommand("[ -r $qFile ] && echo 1 || echo 0", timeoutMs = 2_500L)
            if (check.output.trim() == "1") {
                val proc = ProcessBuilder("sh", "-c", "cat $qFile").start()
                return BufferedInputStream(proc.inputStream, 128 * 1024)
            }
        }
        // 4. Root shell (`su -c cat`) fallback
        if (isRootActive()) {
            runCatching {
                val tempStage = File(context.cacheDir, "root_read_${System.nanoTime()}_${file.name}")
                val qTemp = shellQuote(tempStage.absolutePath)
                val myUid = android.os.Process.myUid()
                val cpRes = RootHelper.runRootShellCommand(
                    "cp -f $qFile $qTemp && chown $myUid:$myUid $qTemp 2>/dev/null; chmod 600 $qTemp 2>/dev/null",
                    timeoutMs = 25_000L
                )
                if (cpRes.exitCode == 0 && tempStage.exists() && tempStage.canRead()) {
                    return object : BufferedInputStream(FileInputStream(tempStage), 128 * 1024) {
                        override fun close() {
                            try {
                                super.close()
                            } finally {
                                runCatching { tempStage.delete() }
                            }
                        }
                    }
                }
            }
            val res = RootHelper.runRootShellCommand("cat $qFile", timeoutMs = 15_000L)
            if (res.exitCode == 0) {
                return ByteArrayInputStream(res.output.toByteArray(Charsets.UTF_8))
            }
        }
        return null
    }

    fun openOutputStreamForFile(
        context: Context,
        targetFile: File,
        mimeType: String = "application/octet-stream"
    ): OutputStream? {
        // 1. Direct FileOutputStream
        if (!isRootProtectedRomPath(targetFile.absolutePath)) {
            runCatching {
                targetFile.parentFile?.mkdirs()
                return BufferedOutputStream(FileOutputStream(targetFile), 128 * 1024)
            }
        } else {
            runCatching {
                targetFile.parentFile?.mkdirs()
                if (targetFile.parentFile?.canWrite() == true || (targetFile.exists() && targetFile.canWrite())) {
                    return BufferedOutputStream(FileOutputStream(targetFile), 128 * 1024)
                }
            }
        }
        // 2. Non-Root SAF DocumentContract ContentResolver OutputStream
        val match = resolveSafMatchingTree(context, targetFile)
        if (match != null) {
            val existingDocUri = runCatching {
                DocumentsContract.buildDocumentUriUsingTree(match.treeUri, match.targetDocId)
            }.getOrNull()
            val targetDocUri = if (existingDocUri != null && querySafDocumentExists(context, existingDocUri)) {
                existingDocUri
            } else {
                val parentFile = targetFile.parentFile
                val parentMatch = if (parentFile != null) resolveSafMatchingTree(context, parentFile) else null
                val parentDocUri = parentMatch?.let {
                    runCatching { DocumentsContract.buildDocumentUriUsingTree(it.treeUri, it.targetDocId) }.getOrNull()
                }
                if (parentDocUri != null) {
                    runCatching {
                        DocumentsContract.createDocument(
                            context.contentResolver,
                            parentDocUri,
                            mimeType.ifBlank { "application/octet-stream" },
                            targetFile.name
                        )
                    }.getOrNull()
                } else null
            }
            if (targetDocUri != null) {
                val safOut = runCatching { context.contentResolver.openOutputStream(targetDocUri, "wt") }.getOrNull()
                    ?: runCatching { context.contentResolver.openOutputStream(targetDocUri) }.getOrNull()
                if (safOut != null) {
                    return BufferedOutputStream(safOut, 128 * 1024)
                }
            }
        }
        // 3. Stage via cache file and flush via non-root shell (`sh -c`) or root shell (`su -c`)
        val tempFile = File(context.cacheDir, "stage_out_${System.nanoTime()}_${targetFile.name}")
        return try {
            val fileOut = BufferedOutputStream(FileOutputStream(tempFile), 128 * 1024)
            object : OutputStream() {
                private var closed = false
                override fun write(b: Int) {
                    fileOut.write(b)
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    fileOut.write(b, off, len)
                }

                override fun flush() {
                    fileOut.flush()
                }

                override fun close() {
                    if (closed) return
                    closed = true
                    runCatching { fileOut.flush() }
                    runCatching { fileOut.close() }
                    runCatching {
                        val qTemp = shellQuote(tempFile.absolutePath)
                        val qParent = shellQuote(targetFile.parentFile?.absolutePath ?: "/")
                        val qDest = shellQuote(targetFile.absolutePath)
                        val userMoved = RootHelper.runUserShellCommand(
                            "mkdir -p $qParent && cp -f $qTemp $qDest 2>/dev/null",
                            timeoutMs = 15_000L
                        )
                        if (userMoved.exitCode != 0 && isRootActive()) {
                            val remount = buildRemountPrefixIfNeeded(targetFile.absolutePath)
                            RootHelper.runRootShellCommand(
                                "${remount}mkdir -p $qParent && cp -f $qTemp $qDest",
                                timeoutMs = 20_000L
                            )
                        }
                        tempFile.delete()
                        invalidateDirectoryCache(targetFile.parentFile?.absolutePath)
                    }
                }
            }
        } catch (_: Exception) {
            runCatching { tempFile.delete() }
            null
        }
    }
}
