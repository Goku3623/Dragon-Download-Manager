package com.example.data.filemanager

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Metadata entry for a file or directory safely stored inside Dragon Archiver's Trash Bin.
 */
data class DragonTrashEntry(
    val id: String,
    val originalPath: String,
    val originalName: String,
    val trashedPath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val deletedAtMs: Long,
    val childCount: Int = 0
) {
    val originalParentPath: String
        get() = File(originalPath).parent ?: "/"
}

data class TrashRestoreOutcome(
    val success: Boolean,
    val restoredFile: File? = null,
    val errorMessage: String? = null
)

/**
 * Real device-backed Trash Bin vault for Dragon Archiver.
 *
 * Uses a hidden `.DragonArchiverTrash` vault on Internal Storage (with app-private fallback)
 * so trashing and restoring files on Internal Storage completes in O(1) via atomic `renameTo`,
 * while cross-volume / root / Android/data paths fall back seamlessly to
 * [AndroidDataObbBridge.copyFileOrDirectoryUniversal] + [AndroidDataObbBridge.deleteFileOrDirectory].
 */
object DragonTrashBinManager {

    private const val TRASH_DIR_NAME = ".DragonArchiverTrash"
    private const val ITEMS_SUBDIR_NAME = "items"
    private const val INDEX_FILE_NAME = "trash_index.json"

    private val lock = Any()
    private val _trashItemsFlow = MutableStateFlow<List<DragonTrashEntry>>(emptyList())
    val trashItemsFlow: StateFlow<List<DragonTrashEntry>> = _trashItemsFlow.asStateFlow()

    @Volatile
    private var loadedOnce = false

    private fun resolvePrimaryTrashVault(context: Context): File {
        val extRoot = AndroidDataObbBridge.getExternalStorageRoot()
        val primary = File(extRoot, TRASH_DIR_NAME)
        val primaryReady = runCatching {
            if (!primary.exists()) primary.mkdirs()
            primary.exists() && primary.isDirectory && primary.canWrite()
        }.getOrDefault(false)
        if (primaryReady) {
            ensureNoMediaMarker(primary)
            return primary
        }
        val fallback = File(context.applicationContext.filesDir, "dragon_trash_bin")
        runCatching { fallback.mkdirs() }
        ensureNoMediaMarker(fallback)
        return fallback
    }

    private fun resolveAllCandidateVaults(context: Context): List<File> {
        val extRoot = AndroidDataObbBridge.getExternalStorageRoot()
        return listOf(
            File(extRoot, TRASH_DIR_NAME),
            File(context.applicationContext.filesDir, "dragon_trash_bin")
        ).distinctBy { it.absolutePath }
    }

    private fun ensureNoMediaMarker(vaultDir: File) {
        runCatching {
            val noMedia = File(vaultDir, ".nomedia")
            if (!noMedia.exists()) noMedia.createNewFile()
        }
    }

    fun ensureLoaded(context: Context): List<DragonTrashEntry> {
        if (!loadedOnce) {
            return refreshTrash(context)
        }
        return _trashItemsFlow.value
    }

    fun refreshTrash(context: Context): List<DragonTrashEntry> {
        synchronized(lock) {
            val appCtx = context.applicationContext
            val merged = LinkedHashMap<String, DragonTrashEntry>()
            val primaryVault = resolvePrimaryTrashVault(appCtx)
            var dirty = false

            for (vault in resolveAllCandidateVaults(appCtx)) {
                val indexFile = File(vault, INDEX_FILE_NAME)
                if (!indexFile.exists()) continue
                val entries = readIndexFile(indexFile)
                for (entry in entries) {
                    val trashedFile = File(entry.trashedPath)
                    if (trashedFile.exists()) {
                        merged[entry.id] = entry
                    } else {
                        dirty = true
                    }
                }
            }

            val sorted = merged.values.sortedByDescending { it.deletedAtMs }
            if (dirty) {
                writeIndexFile(File(primaryVault, INDEX_FILE_NAME), sorted)
            }
            _trashItemsFlow.value = sorted
            loadedOnce = true
            return sorted
        }
    }

    /**
     * Moves a file or directory into the Trash Bin vault while recording its exact original location.
     * Prevents trashing the Trash Bin vault itself.
     */
    fun moveToTrash(context: Context, source: File): DragonTrashEntry? {
        val appCtx = context.applicationContext
        val vault = resolvePrimaryTrashVault(appCtx)
        val absSource = runCatching { source.canonicalPath }.getOrDefault(source.absolutePath)
        val absVault = runCatching { vault.canonicalPath }.getOrDefault(vault.absolutePath)
        if (absSource == absVault || absSource.startsWith("$absVault/")) {
            return null
        }

        val exists = AndroidDataObbBridge.existsUniversal(appCtx, source)
        if (!exists) return null

        val isDir = source.isDirectory
        val (computedSize, childCount) = computeSizeFast(source, isDir)
        val id = UUID.randomUUID().toString().replace("-", "").take(12)
        val safeName = source.name
            .replace('/', '_')
            .replace('\u0000', '_')
            .ifBlank { "item_$id" }

        val itemsDir = File(vault, ITEMS_SUBDIR_NAME).apply { mkdirs() }
        val destFile = File(itemsDir, "${id}__${safeName}")

        // 1. Try instant O(1) atomic rename on same filesystem first
        var moved = runCatching { source.renameTo(destFile) }.getOrDefault(false)

        // 2. Cross-filesystem or root/SAF fallback: copy universally then delete original
        if (!moved) {
            val copied = AndroidDataObbBridge.copyFileOrDirectoryUniversal(
                context = appCtx,
                source = source,
                dest = destFile,
                isDirectoryHint = isDir
            )
            if (copied && destFile.exists()) {
                val removed = AndroidDataObbBridge.deleteFileOrDirectory(appCtx, source)
                if (removed || !AndroidDataObbBridge.existsUniversal(appCtx, source)) {
                    moved = true
                } else {
                    // Rollback partial trash copy if original could not be deleted
                    runCatching { destFile.deleteRecursively() }
                    return null
                }
            } else {
                runCatching { destFile.deleteRecursively() }
                return null
            }
        }

        if (!moved || !destFile.exists()) return null

        val entry = DragonTrashEntry(
            id = id,
            originalPath = source.absolutePath,
            originalName = source.name.ifBlank { safeName },
            trashedPath = destFile.absolutePath,
            isDirectory = isDir,
            sizeBytes = computedSize,
            deletedAtMs = System.currentTimeMillis(),
            childCount = childCount
        )

        synchronized(lock) {
            val current = if (loadedOnce) _trashItemsFlow.value else refreshTrash(appCtx)
            val updated = (listOf(entry) + current.filterNot { it.id == entry.id })
                .sortedByDescending { it.deletedAtMs }
            writeIndexFile(File(vault, INDEX_FILE_NAME), updated)
            _trashItemsFlow.value = updated
            loadedOnce = true
        }

        source.parentFile?.absolutePath?.let {
            DragonFileManagerTaskEngine.invalidateDirectorySnapshotCache(it)
        }
        return entry
    }

    /**
     * Restores a trashed file or directory back to its exact previous original location.
     * Recreates missing parent directories automatically and disambiguates name collisions safely.
     */
    fun restoreItem(context: Context, entry: DragonTrashEntry): TrashRestoreOutcome {
        val appCtx = context.applicationContext
        val trashedFile = File(entry.trashedPath)
        if (!trashedFile.exists()) {
            removeEntryFromIndex(appCtx, setOf(entry.id))
            return TrashRestoreOutcome(false, errorMessage = "Trashed file is missing from vault")
        }

        val desiredFile = File(entry.originalPath)
        val parentDir = desiredFile.parentFile ?: AndroidDataObbBridge.getExternalStorageRoot()
        if (!parentDir.exists()) {
            runCatching { parentDir.mkdirs() }
        }

        val targetFile = resolveNonCollidingRestoreTarget(appCtx, desiredFile, entry.isDirectory)

        // 1. Instant O(1) atomic rename back to original folder
        var restored = runCatching { trashedFile.renameTo(targetFile) }.getOrDefault(false)

        // 2. Fallback for cross-volume / Android/data / root paths
        if (!restored) {
            val copied = AndroidDataObbBridge.copyFileOrDirectoryUniversal(
                context = appCtx,
                source = trashedFile,
                dest = targetFile,
                isDirectoryHint = entry.isDirectory
            )
            if (copied && AndroidDataObbBridge.existsUniversal(appCtx, targetFile)) {
                runCatching { trashedFile.deleteRecursively() }
                restored = true
            } else {
                return TrashRestoreOutcome(
                    false,
                    errorMessage = "Could not restore to ${parentDir.absolutePath}"
                )
            }
        }

        removeEntryFromIndex(appCtx, setOf(entry.id))
        targetFile.parentFile?.absolutePath?.let {
            DragonFileManagerTaskEngine.invalidateDirectorySnapshotCache(it)
        }
        return TrashRestoreOutcome(true, restoredFile = targetFile)
    }

    fun restoreItems(context: Context, entries: List<DragonTrashEntry>): Pair<Int, Int> {
        var restoredCount = 0
        var failedCount = 0
        entries.forEach { entry ->
            val outcome = restoreItem(context, entry)
            if (outcome.success) restoredCount++ else failedCount++
        }
        if (restoredCount > 0) {
            DragonFileManagerTaskEngine.notifyExternalDirectoryMutation()
        }
        return restoredCount to failedCount
    }

    /**
     * Permanently deletes the selected items from the Trash Bin vault, freeing storage space.
     */
    fun deletePermanently(context: Context, entries: List<DragonTrashEntry>): Int {
        val appCtx = context.applicationContext
        var deletedCount = 0
        val deletedIds = HashSet<String>()
        entries.forEach { entry ->
            val f = File(entry.trashedPath)
            val ok = if (!f.exists()) {
                true
            } else {
                runCatching { f.deleteRecursively() }.getOrDefault(false) ||
                    AndroidDataObbBridge.deleteFileOrDirectory(appCtx, f) ||
                    !f.exists()
            }
            if (ok) {
                deletedCount++
                deletedIds.add(entry.id)
            }
        }
        if (deletedIds.isNotEmpty()) {
            removeEntryFromIndex(appCtx, deletedIds)
        }
        return deletedCount
    }

    fun emptyTrash(context: Context): Int {
        val all = refreshTrash(context)
        val count = deletePermanently(context, all)
        // Clean any orphaned files left inside items/ directories
        for (vault in resolveAllCandidateVaults(context.applicationContext)) {
            val itemsDir = File(vault, ITEMS_SUBDIR_NAME)
            if (itemsDir.exists() && itemsDir.isDirectory) {
                itemsDir.listFiles()?.forEach { orphan ->
                    runCatching { orphan.deleteRecursively() }
                }
            }
        }
        return count
    }

    private fun removeEntryFromIndex(context: Context, idsToRemove: Set<String>) {
        synchronized(lock) {
            val appCtx = context.applicationContext
            val primaryVault = resolvePrimaryTrashVault(appCtx)
            val updated = _trashItemsFlow.value.filterNot { it.id in idsToRemove }
            writeIndexFile(File(primaryVault, INDEX_FILE_NAME), updated)
            // Keep secondary vault index clean if it existed
            for (vault in resolveAllCandidateVaults(appCtx)) {
                if (vault.absolutePath != primaryVault.absolutePath) {
                    val altIndex = File(vault, INDEX_FILE_NAME)
                    if (altIndex.exists()) {
                        val remaining = readIndexFile(altIndex).filterNot { it.id in idsToRemove }
                        writeIndexFile(altIndex, remaining)
                    }
                }
            }
            _trashItemsFlow.value = updated
            loadedOnce = true
        }
    }

    private fun resolveNonCollidingRestoreTarget(
        context: Context,
        desired: File,
        isDir: Boolean
    ): File {
        if (!AndroidDataObbBridge.existsUniversal(context, desired)) return desired
        val parent = desired.parentFile ?: return desired
        val name = desired.name
        val dotIdx = if (!isDir) name.lastIndexOf('.') else -1
        val base = if (dotIdx > 0) name.substring(0, dotIdx) else name
        val ext = if (dotIdx > 0) name.substring(dotIdx) else ""
        var counter = 1
        while (counter <= 500) {
            val suffix = if (counter == 1) " (restored)" else " (restored $counter)"
            val candidate = File(parent, "$base$suffix$ext")
            if (!AndroidDataObbBridge.existsUniversal(context, candidate)) {
                return candidate
            }
            counter++
        }
        return File(parent, "${base}_${System.currentTimeMillis()}$ext")
    }

    private fun computeSizeFast(file: File, isDir: Boolean): Pair<Long, Int> {
        if (!isDir) {
            return runCatching { file.length().coerceAtLeast(0L) }.getOrDefault(0L) to 0
        }
        var totalBytes = 0L
        var directChildren = 0
        var scanned = 0
        val queue = ArrayDeque<File>()
        runCatching {
            val top = file.listFiles() ?: emptyArray()
            directChildren = top.size
            top.forEach { queue.addLast(it) }
            while (queue.isNotEmpty() && scanned < 2500) {
                val cur = queue.removeFirst()
                scanned++
                if (cur.isDirectory) {
                    cur.listFiles()?.forEach { queue.addLast(it) }
                } else {
                    totalBytes += cur.length().coerceAtLeast(0L)
                }
            }
        }
        return totalBytes to directChildren
    }

    private fun readIndexFile(indexFile: File): List<DragonTrashEntry> {
        return runCatching {
            val raw = indexFile.readText(Charsets.UTF_8)
            if (raw.isBlank()) return emptyList()
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
                    val originalPath = obj.optString("originalPath").takeIf { it.isNotBlank() } ?: continue
                    val trashedPath = obj.optString("trashedPath").takeIf { it.isNotBlank() } ?: continue
                    add(
                        DragonTrashEntry(
                            id = id,
                            originalPath = originalPath,
                            originalName = obj.optString("originalName").ifBlank { File(originalPath).name },
                            trashedPath = trashedPath,
                            isDirectory = obj.optBoolean("isDirectory", false),
                            sizeBytes = obj.optLong("sizeBytes", 0L),
                            deletedAtMs = obj.optLong("deletedAtMs", System.currentTimeMillis()),
                            childCount = obj.optInt("childCount", 0)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun writeIndexFile(indexFile: File, entries: List<DragonTrashEntry>) {
        runCatching {
            indexFile.parentFile?.mkdirs()
            val arr = JSONArray()
            entries.forEach { e ->
                arr.put(
                    JSONObject().apply {
                        put("id", e.id)
                        put("originalPath", e.originalPath)
                        put("originalName", e.originalName)
                        put("trashedPath", e.trashedPath)
                        put("isDirectory", e.isDirectory)
                        put("sizeBytes", e.sizeBytes)
                        put("deletedAtMs", e.deletedAtMs)
                        put("childCount", e.childCount)
                    }
                )
            }
            val tmp = File(indexFile.parentFile, "${INDEX_FILE_NAME}.tmp")
            tmp.writeText(arr.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(indexFile)) {
                indexFile.writeText(arr.toString(), Charsets.UTF_8)
                tmp.delete()
            }
        }
    }
}
