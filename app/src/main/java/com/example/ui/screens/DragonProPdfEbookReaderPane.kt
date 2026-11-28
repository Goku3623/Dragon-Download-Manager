package com.example.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import com.example.data.downloader.ApkPackageInstaller
import com.example.data.filemanager.DragonPdfSearchEngine
import com.example.data.filemanager.PdfEditSessionPage
import com.example.data.filemanager.PdfNormalizedHighlightRect
import com.example.data.filemanager.PdfSearchPageResult
import com.example.data.filemanager.PdfTextAnnotation
import com.example.ui.theme.DangerRed
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarningOrange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

enum class PdfEbookTheme(
    val label: String,
    val canvasBg: Color,
    val chipColor: Color,
    val chipTextColor: Color
) {
    DAY("Day", Color(0xFF1F242D), Color(0xFFF8FAFC), Color(0xFF0F172A)),
    SEPIA("Sepia", Color(0xFF2A231B), Color(0xFFF4E7CE), Color(0xFF3E2C1B)),
    SOFT_DARK("Soft Dark", Color(0xFF17191E), Color(0xFF282C34), Color(0xFFE2E8F0)),
    AMOLED_NIGHT("AMOLED", Color(0xFF000000), Color(0xFF0A0A0A), Color(0xFFFFFFFF))
}

enum class PdfReadingLayout(val label: String) {
    CONTINUOUS_SCROLL("Continuous Scroll"),
    SINGLE_PAGE("Single Page")
}

enum class PdfPageSwipeEffect(val label: String) {
    BOOK_FLIP("3D Book Flip"),
    DEPTH_CURL("Depth Curl"),
    PAPER_SLIDE("Paper Slide")
}

private data class PdfBookmarkRecord(
    val pageIndex: Int,
    val label: String
)

/**
 * High-speed kept-open `PdfRenderer` + LRU bitmap cache session so large 500+ page
 * e-books render in ~10ms per page with zero stuttering and bounded memory usage.
 */
private class FastPdfRenderSession(pdfFile: File?) {
    private val lock = Any()
    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null

    // Full-page cache (up to ~36 MB) and compact thumbnail cache (up to ~8 MB)
    private val pageCache = object : LruCache<String, Bitmap>(36 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.allocationByteCount / 1024).coerceAtLeast(1)
    }
    private val thumbCache = object : LruCache<String, Bitmap>(8 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.allocationByteCount / 1024).coerceAtLeast(1)
    }

    init {
        if (pdfFile != null && pdfFile.exists() && pdfFile.length() > 0L) {
            runCatching {
                val fd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
                pfd = fd
                renderer = PdfRenderer(fd)
            }
        }
    }

    fun getCachedPage(pageIndex: Int, widthPx: Int, rotation: Int): Bitmap? {
        return pageCache.get("$pageIndex:$widthPx:$rotation")
    }

    fun getCachedThumb(pageIndex: Int, rotation: Int): Bitmap? {
        return thumbCache.get("t:$pageIndex:$rotation")
    }

    fun renderPage(pageIndex: Int, targetWidthPx: Int, rotationDegrees: Int, isThumbnail: Boolean = false): Bitmap? {
        val normRot = ((rotationDegrees % 360) + 360) % 360
        val cacheKey = if (isThumbnail) "t:$pageIndex:$normRot" else "$pageIndex:$targetWidthPx:$normRot"
        val cache = if (isThumbnail) thumbCache else pageCache
        cache.get(cacheKey)?.let { if (!it.isRecycled) return it }

        synchronized(lock) {
            cache.get(cacheKey)?.let { if (!it.isRecycled) return it }
            val r = renderer ?: return null
            if (pageIndex !in 0 until r.pageCount) return null
            return runCatching {
                r.openPage(pageIndex).use { page ->
                    val minW = if (isThumbnail) 140 else 480
                    val maxW = if (isThumbnail) 320 else 2200
                    val maxH = if (isThumbnail) 480 else 3200
                    val scale = targetWidthPx.toFloat() / page.width.coerceAtLeast(1).toFloat()
                    val w = (page.width * scale).toInt().coerceIn(minW, maxW)
                    val h = (page.height * scale).toInt().coerceIn(minW, maxH)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(AndroidColor.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val finalBmp = if (normRot != 0) {
                        val matrix = Matrix().apply { postRotate(normRot.toFloat()) }
                        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                        if (rotated != bmp) bmp.recycle()
                        rotated
                    } else {
                        bmp
                    }
                    cache.put(cacheKey, finalBmp)
                    finalBmp
                }
            }.getOrNull()
        }
    }

    fun close() {
        synchronized(lock) {
            runCatching { renderer?.close() }
            runCatching { pfd?.close() }
            renderer = null
            pfd = null
            pageCache.evictAll()
            thumbCache.evictAll()
        }
    }
}

private object PdfEbookReadingStore {
    private const val PREF_NAME = "dragon_pdf_ebook_reader_prefs"

    private fun docKey(file: File): String = "doc_${file.absolutePath.hashCode()}_${file.length()}"

    fun loadLastPage(context: Context, file: File, maxPages: Int): Int {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getInt("${docKey(file)}_page", 0)
        return saved.coerceIn(0, (maxPages - 1).coerceAtLeast(0))
    }

    fun saveLastPage(context: Context, file: File, pageIdx: Int) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt("${docKey(file)}_page", pageIdx).apply()
    }

    fun loadBookmarks(context: Context, file: File): List<PdfBookmarkRecord> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString("${docKey(file)}_bookmarks", "").orEmpty()
        if (raw.isBlank()) return emptyList()
        return raw.split("\n")
            .mapNotNull { line ->
                val idx = line.substringBefore('|', "").toIntOrNull() ?: return@mapNotNull null
                val label = line.substringAfter('|', "Bookmark")
                PdfBookmarkRecord(idx, label)
            }
            .distinctBy { it.pageIndex }
            .sortedBy { it.pageIndex }
    }

    fun saveBookmarks(context: Context, file: File, bookmarks: List<PdfBookmarkRecord>) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val serialized = bookmarks
            .distinctBy { it.pageIndex }
            .sortedBy { it.pageIndex }
            .joinToString("\n") { "${it.pageIndex}|${it.label.replace("\n", " ").replace("|", "-")}" }
        prefs.edit().putString("${docKey(file)}_bookmarks", serialized).apply()
    }

    fun loadTheme(context: Context): PdfEbookTheme {
        val name = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString("theme", PdfEbookTheme.DAY.name)
        return PdfEbookTheme.entries.firstOrNull { it.name == name } ?: PdfEbookTheme.DAY
    }

    fun saveTheme(context: Context, theme: PdfEbookTheme) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().putString("theme", theme.name).apply()
    }

    fun loadLayout(context: Context): PdfReadingLayout {
        val name = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString("layout", PdfReadingLayout.CONTINUOUS_SCROLL.name)
        return PdfReadingLayout.entries.firstOrNull { it.name == name } ?: PdfReadingLayout.CONTINUOUS_SCROLL
    }

    fun saveLayout(context: Context, layout: PdfReadingLayout) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().putString("layout", layout.name).apply()
    }

    fun loadKeepScreenOn(context: Context): Boolean {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getBoolean("keep_screen_on", true)
    }

    fun saveKeepScreenOn(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().putBoolean("keep_screen_on", enabled).apply()
    }

    fun loadDimmer(context: Context): Float {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getFloat("dimmer", 0f).coerceIn(0f, 0.65f)
    }

    fun saveDimmer(context: Context, dimmer: Float) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().putFloat("dimmer", dimmer.coerceIn(0f, 0.65f)).apply()
    }

    fun loadSwipeEffect(context: Context): PdfPageSwipeEffect {
        val name = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString("swipe_effect", PdfPageSwipeEffect.BOOK_FLIP.name)
        return PdfPageSwipeEffect.entries.firstOrNull { it.name == name } ?: PdfPageSwipeEffect.BOOK_FLIP
    }

    fun saveSwipeEffect(context: Context, effect: PdfPageSwipeEffect) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().putString("swipe_effect", effect.name).apply()
    }
}

private fun buildThemeColorFilter(theme: PdfEbookTheme): ColorFilter? {
    return when (theme) {
        PdfEbookTheme.DAY -> null
        PdfEbookTheme.SEPIA -> ColorFilter.colorMatrix(
            ColorMatrix(
                floatArrayOf(
                    0.96f, 0f, 0f, 0f, 4f,
                    0f, 0.90f, 0f, 0f, 2f,
                    0f, 0f, 0.78f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
        PdfEbookTheme.SOFT_DARK -> ColorFilter.colorMatrix(
            ColorMatrix(
                floatArrayOf(
                    -0.84f, 0f, 0f, 0f, 232f,
                    0f, -0.84f, 0f, 0f, 234f,
                    0f, 0f, -0.82f, 0f, 236f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
        PdfEbookTheme.AMOLED_NIGHT -> ColorFilter.colorMatrix(
            ColorMatrix(
                floatArrayOf(
                    -1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f,
                    0f, 0f, -1f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
    }
}

/**
 * Professional E-Book PDF Reader & Editor Studio Pane.
 *
 * Features:
 * - Continuous vertical scroll (60fps kept-open PdfRenderer + LRU page cache + prefetch) AND Single-Page Book Flip mode
 * - Pinch-to-zoom (1x..3.5x), double-tap smart zoom (1x ↔ 2.1x), and smooth drag pan
 * - 4 eye-care reading themes (Day, Sepia Parchment, Soft Dark, AMOLED Night) + Bedtime Dimmer overlay
 * - Immersive tap-to-hide fullscreen reading mode + Keep Screen Awake toggle
 * - Automatic last-read page resume + Persistent Bookmarks + Visual 3-Column Page Thumbnail Grid + Go-to-Page jump
 * - Collapsible PDF Edit Tools bar (Rotate, Text Annotations, Add Page, Delete Page)
 */
@Composable
fun DragonProPdfEbookReaderPane(
    context: Context,
    originalFile: File,
    stagedPdfFile: File?,
    pages: List<PdfEditSessionPage>,
    annotations: List<PdfTextAnnotation>,
    currentPageIdx: Int,
    immersiveFullscreen: Boolean,
    onToggleImmersive: () -> Unit,
    onSelectPage: (Int) -> Unit,
    onRotatePage: () -> Unit,
    onAddAnnotation: () -> Unit,
    onDeleteAnnotation: (Long) -> Unit,
    onAddNewPage: () -> Unit,
    onDeleteCurrentPage: () -> Unit,
    onEditCustomPageBody: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val view = LocalView.current

    // Reader preferences (persisted)
    var theme by remember { mutableStateOf(PdfEbookReadingStore.loadTheme(context)) }
    var layoutMode by remember { mutableStateOf(PdfEbookReadingStore.loadLayout(context)) }
    var swipeEffect by remember { mutableStateOf(PdfEbookReadingStore.loadSwipeEffect(context)) }
    var keepScreenOn by remember { mutableStateOf(PdfEbookReadingStore.loadKeepScreenOn(context)) }
    var dimmerAlpha by remember { mutableFloatStateOf(PdfEbookReadingStore.loadDimmer(context)) }
    var bookmarks by remember(originalFile.absolutePath) {
        mutableStateOf(PdfEbookReadingStore.loadBookmarks(context, originalFile))
    }

    // Zoom & pan state
    var zoomScale by remember { mutableFloatStateOf(1.0f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }

    // UI sheets / toolbars
    var showEditTools by remember { mutableStateOf(false) }
    var showSearchBar by remember { mutableStateOf(false) }
    var showSearchResultsList by remember { mutableStateOf(false) }
    var showReaderSettingsDialog by remember { mutableStateOf(false) }
    var showBookmarksAndGridDialog by remember { mutableStateOf(false) }
    var showJumpToPageDialog by remember { mutableStateOf(false) }
    var showBookmarkNameDialog by remember { mutableStateOf(false) }

    // In-PDF Search state
    var searchQuery by remember { mutableStateOf("") }
    var searchMatchCase by remember { mutableStateOf(false) }
    var isSearchingPdf by remember { mutableStateOf(false) }
    var searchScanProgress by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<PdfSearchPageResult>>(emptyList()) }
    var activeSearchResultIdx by remember { mutableIntStateOf(0) }
    var searchJob by remember { mutableStateOf<Job?>(null) }

    // Keep screen awake while reading ebooks
    DisposableEffect(view, keepScreenOn) {
        val prev = view.keepScreenOn
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = prev }
    }

    // Fast kept-open PdfRenderer session
    val renderSession = remember(stagedPdfFile?.absolutePath) {
        FastPdfRenderSession(stagedPdfFile)
    }
    DisposableEffect(renderSession) {
        onDispose { renderSession.close() }
    }

    // Restore last-read page once when opening an ebook
    var hasRestoredLastPage by remember(originalFile.absolutePath) { mutableStateOf(false) }
    val scrollListState = rememberLazyListState()
    val bookPagerState = rememberPagerState(
        initialPage = currentPageIdx.coerceIn(0, (pages.size - 1).coerceAtLeast(0)),
        pageCount = { pages.size.coerceAtLeast(1) }
    )

    LaunchedEffect(pages.size, originalFile.absolutePath) {
        if (!hasRestoredLastPage && pages.isNotEmpty()) {
            hasRestoredLastPage = true
            val restored = PdfEbookReadingStore.loadLastPage(context, originalFile, pages.size)
            if (restored > 0) {
                onSelectPage(restored)
                scrollListState.scrollToItem(restored)
                bookPagerState.scrollToPage(restored)
                ApkPackageInstaller.showToastOnMain(context, "Resumed reading at page ${restored + 1} of ${pages.size}")
            }
        }
    }

    // Persist reading position whenever currentPageIdx changes
    LaunchedEffect(currentPageIdx, pages.size) {
        if (hasRestoredLastPage && pages.isNotEmpty()) {
            PdfEbookReadingStore.saveLastPage(context, originalFile, currentPageIdx)
        }
    }

    // Sync bookPagerState -> currentPageIdx when user swipes pages in Single-Page Book Mode
    LaunchedEffect(bookPagerState.currentPage, layoutMode, pages.size) {
        if (layoutMode == PdfReadingLayout.SINGLE_PAGE && pages.isNotEmpty()) {
            val clamped = bookPagerState.currentPage.coerceIn(0, pages.lastIndex)
            if (clamped != currentPageIdx) {
                onSelectPage(clamped)
                zoomScale = 1.0f
                panOffset = Offset.Zero
            }
        }
    }

    // Keep pager aligned whenever switching into Single-Page Mode or when external page state changes
    LaunchedEffect(layoutMode) {
        if (layoutMode == PdfReadingLayout.SINGLE_PAGE && pages.isNotEmpty()) {
            val clamped = currentPageIdx.coerceIn(0, pages.lastIndex)
            if (bookPagerState.currentPage != clamped) {
                bookPagerState.scrollToPage(clamped)
            }
        } else if (layoutMode == PdfReadingLayout.CONTINUOUS_SCROLL && pages.isNotEmpty()) {
            scrollListState.scrollToItem(currentPageIdx.coerceIn(0, pages.lastIndex))
        }
    }

    // Sync visible page index while user scrolls in Continuous Scroll mode
    val visibleScrollPage by remember {
        derivedStateOf {
            val firstIdx = scrollListState.firstVisibleItemIndex
            val firstOffset = scrollListState.firstVisibleItemScrollOffset
            if (firstOffset > 260 && firstIdx + 1 < pages.size) firstIdx + 1 else firstIdx
        }
    }
    LaunchedEffect(visibleScrollPage, layoutMode) {
        if (layoutMode == PdfReadingLayout.CONTINUOUS_SCROLL && pages.isNotEmpty()) {
            val clamped = visibleScrollPage.coerceIn(0, pages.lastIndex)
            if (clamped != currentPageIdx) {
                onSelectPage(clamped)
            }
        }
    }

    fun jumpToPage(targetIdx: Int, animateBookFlip: Boolean = false) {
        if (pages.isEmpty()) return
        val clamped = targetIdx.coerceIn(0, pages.lastIndex)
        onSelectPage(clamped)
        zoomScale = 1.0f
        panOffset = Offset.Zero
        if (layoutMode == PdfReadingLayout.CONTINUOUS_SCROLL) {
            scope.launch { scrollListState.scrollToItem(clamped) }
        } else {
            scope.launch {
                val distance = abs(clamped - bookPagerState.currentPage)
                if (animateBookFlip && distance in 1..2) {
                    bookPagerState.animateScrollToPage(
                        page = clamped,
                        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing)
                    )
                } else {
                    bookPagerState.scrollToPage(clamped)
                }
            }
        }
    }

    fun runPdfSearch(queryText: String, caseSensitive: Boolean) {
        searchJob?.cancel()
        val trimmed = queryText.trim()
        if (trimmed.isEmpty()) {
            isSearchingPdf = false
            searchScanProgress = ""
            searchResults = emptyList()
            activeSearchResultIdx = 0
            return
        }
        searchJob = scope.launch {
            isSearchingPdf = true
            searchScanProgress = "Scanning..."
            val found = withContext(Dispatchers.IO) {
                DragonPdfSearchEngine.searchPdf(
                    pdfFile = stagedPdfFile,
                    pages = pages,
                    annotations = annotations,
                    query = trimmed,
                    matchCase = caseSensitive,
                    onProgress = { scanned, total ->
                        searchScanProgress = "$scanned/$total"
                    }
                )
            }
            searchResults = found
            isSearchingPdf = false
            searchScanProgress = ""
            if (found.isNotEmpty()) {
                // Jump to the first match on or after current page, otherwise wrap to first match
                val firstAtOrAfter = found.indexOfFirst { it.pageIndex >= currentPageIdx }.takeIf { it >= 0 } ?: 0
                activeSearchResultIdx = firstAtOrAfter
                jumpToPage(found[firstAtOrAfter].pageIndex, animateBookFlip = true)
            } else {
                activeSearchResultIdx = 0
                ApkPackageInstaller.showToastOnMain(context, "No matches found for \"$trimmed\"")
            }
        }
    }

    fun navigateSearchResult(delta: Int) {
        if (searchResults.isEmpty()) return
        val nextIdx = (activeSearchResultIdx + delta + searchResults.size) % searchResults.size
        activeSearchResultIdx = nextIdx
        jumpToPage(searchResults[nextIdx].pageIndex, animateBookFlip = true)
    }

    val colorFilter = remember(theme) { buildThemeColorFilter(theme) }
    val totalPages = pages.size.coerceAtLeast(1)
    val progressPercent = (((currentPageIdx + 1).toFloat() / totalPages.toFloat()) * 100f).roundToInt().coerceIn(0, 100)
    val isCurrentPageBookmarked = bookmarks.any { it.pageIndex == currentPageIdx }
    val searchResultsByPage = remember(searchResults) {
        searchResults.associateBy { it.pageIndex }
    }

    Box(
        modifier = modifier
            .background(theme.canvasBg)
            .testTag("pro_pdf_ebook_reader_pane")
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Compact Top E-Book Controls Bar (hides cleanly in Immersive Fullscreen mode)
            AnimatedVisibility(
                visible = !immersiveFullscreen,
                enter = fadeIn() + slideInVertically { -it },
                exit = fadeOut() + slideOutVertically { -it }
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    tonalElevation = 2.dp
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 6.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            // Reading Mode Switcher (Continuous vs Single Page Book Swipe)
                            FilledTonalIconButton(
                                onClick = {
                                    val next = if (layoutMode == PdfReadingLayout.CONTINUOUS_SCROLL) {
                                        PdfReadingLayout.SINGLE_PAGE
                                    } else {
                                        PdfReadingLayout.CONTINUOUS_SCROLL
                                    }
                                    layoutMode = next
                                    PdfEbookReadingStore.saveLayout(context, next)
                                    zoomScale = 1.0f
                                    panOffset = Offset.Zero
                                    ApkPackageInstaller.showToastOnMain(
                                        context,
                                        if (next == PdfReadingLayout.SINGLE_PAGE) "Book Swipe Mode (${swipeEffect.label})"
                                        else "Continuous Scroll Mode"
                                    )
                                },
                                modifier = Modifier
                                    .size(34.dp)
                                    .testTag("pdf_layout_mode_btn")
                            ) {
                                Icon(
                                    imageVector = if (layoutMode == PdfReadingLayout.CONTINUOUS_SCROLL) {
                                        Icons.Default.ViewDay
                                    } else {
                                        Icons.AutoMirrored.Filled.MenuBook
                                    },
                                    contentDescription = "Switch Scroll / Single Page Book Swipe",
                                    tint = NeonCyan,
                                    modifier = Modifier.size(17.dp)
                                )
                            }

                            // Scrollable Theme quick selector pills so action buttons never crowd
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                PdfEbookTheme.entries.forEach { t ->
                                    val selected = theme == t
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = t.chipColor,
                                        border = BorderStroke(
                                            width = if (selected) 2.dp else 1.dp,
                                            color = if (selected) NeonCyan else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                        ),
                                        modifier = Modifier
                                            .height(26.dp)
                                            .clickable {
                                                theme = t
                                                PdfEbookReadingStore.saveTheme(context, t)
                                            }
                                    ) {
                                        Box(
                                            modifier = Modifier.padding(horizontal = 6.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = t.label,
                                                fontSize = 9.sp,
                                                fontWeight = if (selected) FontWeight.Black else FontWeight.SemiBold,
                                                color = t.chipTextColor,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                            }

                            // In-PDF Text Search Toggle
                            IconButton(
                                onClick = {
                                    showSearchBar = !showSearchBar
                                    if (!showSearchBar) {
                                        showSearchResultsList = false
                                    }
                                },
                                modifier = Modifier
                                    .size(34.dp)
                                    .testTag("pdf_search_toggle_btn")
                            ) {
                                Icon(
                                    imageVector = if (showSearchBar) Icons.Default.SearchOff else Icons.Default.Search,
                                    contentDescription = "Search in PDF",
                                    tint = if (showSearchBar || searchResults.isNotEmpty()) NeonCyan else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(19.dp)
                                )
                            }

                            // Dedicated Jump to Page Button
                            IconButton(
                                onClick = { showJumpToPageDialog = true },
                                modifier = Modifier
                                    .size(34.dp)
                                    .testTag("pdf_jump_to_page_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Shortcut,
                                    contentDescription = "Jump to Page",
                                    tint = WarningOrange,
                                    modifier = Modifier.size(19.dp)
                                )
                            }

                            // Bookmark Toggle
                            IconButton(
                                onClick = {
                                    if (isCurrentPageBookmarked) {
                                        val next = bookmarks.filterNot { it.pageIndex == currentPageIdx }
                                        bookmarks = next
                                        PdfEbookReadingStore.saveBookmarks(context, originalFile, next)
                                        ApkPackageInstaller.showToastOnMain(context, "Removed bookmark on page ${currentPageIdx + 1}")
                                    } else {
                                        showBookmarkNameDialog = true
                                    }
                                },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    imageVector = if (isCurrentPageBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                    contentDescription = "Bookmark Page",
                                    tint = if (isCurrentPageBookmarked) WarningOrange else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(19.dp)
                                )
                            }

                            // Bookmarks & Thumbnail Grid
                            IconButton(
                                onClick = { showBookmarksAndGridDialog = true },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    Icons.Default.GridView,
                                    contentDescription = "Page Grid & Bookmarks",
                                    tint = NeonCyan,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            // Edit Tools Toggle
                            IconButton(
                                onClick = { showEditTools = !showEditTools },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    Icons.Default.EditNote,
                                    contentDescription = "PDF Edit Tools",
                                    tint = if (showEditTools) SuccessGreen else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(19.dp)
                                )
                            }

                            // Reader Display Settings (Swipe Effect, Dimmer, Keep Awake, Zoom)
                            IconButton(
                                onClick = { showReaderSettingsDialog = true },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    Icons.Default.Tune,
                                    contentDescription = "Reader Settings",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        // Collapsible Pro In-PDF Search Bar + Expandable Results Drawer
                        AnimatedVisibility(visible = showSearchBar) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f))
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    OutlinedTextField(
                                        value = searchQuery,
                                        onValueChange = { searchQuery = it },
                                        placeholder = { Text("Search text in PDF...", fontSize = 11.5.sp) },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                        keyboardActions = KeyboardActions(
                                            onSearch = { runPdfSearch(searchQuery, searchMatchCase) }
                                        ),
                                        trailingIcon = {
                                            if (searchQuery.isNotEmpty()) {
                                                IconButton(
                                                    onClick = {
                                                        searchQuery = ""
                                                        searchResults = emptyList()
                                                        activeSearchResultIdx = 0
                                                        showSearchResultsList = false
                                                    },
                                                    modifier = Modifier.size(28.dp)
                                                ) {
                                                    Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                                                }
                                            }
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("pdf_search_input")
                                    )

                                    // Match Case Toggle (Aa)
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (searchMatchCase) NeonCyan.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceVariant,
                                        border = BorderStroke(
                                            1.dp,
                                            if (searchMatchCase) NeonCyan else MaterialTheme.colorScheme.outlineVariant
                                        ),
                                        modifier = Modifier
                                            .height(36.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable {
                                                searchMatchCase = !searchMatchCase
                                                if (searchQuery.isNotBlank()) {
                                                    runPdfSearch(searchQuery, searchMatchCase)
                                                }
                                            }
                                    ) {
                                        Box(
                                            modifier = Modifier.padding(horizontal = 8.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "Aa",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Black,
                                                color = if (searchMatchCase) NeonCyan else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }

                                    // Execute Search Button
                                    FilledTonalButton(
                                        onClick = { runPdfSearch(searchQuery, searchMatchCase) },
                                        shape = RoundedCornerShape(9.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                        modifier = Modifier
                                            .height(36.dp)
                                            .testTag("pdf_search_go_btn")
                                    ) {
                                        if (isSearchingPdf) {
                                            CircularProgressIndicator(
                                                strokeWidth = 2.dp,
                                                color = NeonCyan,
                                                modifier = Modifier.size(15.dp)
                                            )
                                        } else {
                                            Text("Find", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }

                                // Match Navigator Bar (when searching or results exist)
                                if (isSearchingPdf || searchResults.isNotEmpty()) {
                                    val totalHits = remember(searchResults) { searchResults.sumOf { it.matchCount } }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            if (isSearchingPdf) {
                                                Text(
                                                    text = "Scanning pages $searchScanProgress…",
                                                    fontSize = 10.5.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = NeonCyan
                                                )
                                            } else {
                                                Surface(
                                                    shape = RoundedCornerShape(7.dp),
                                                    color = NeonCyan.copy(alpha = 0.16f),
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(7.dp))
                                                        .clickable { showSearchResultsList = !showSearchResultsList }
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                    ) {
                                                        Text(
                                                            text = "Match ${activeSearchResultIdx + 1}/${searchResults.size} ($totalHits hits)",
                                                            fontSize = 10.5.sp,
                                                            fontFamily = FontFamily.Monospace,
                                                            fontWeight = FontWeight.Bold,
                                                            color = NeonCyan
                                                        )
                                                        Icon(
                                                            imageVector = if (showSearchResultsList) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                                            contentDescription = "Toggle All Results",
                                                            tint = NeonCyan,
                                                            modifier = Modifier.size(15.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        if (searchResults.isNotEmpty()) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                                            ) {
                                                IconButton(
                                                    onClick = { navigateSearchResult(-1) },
                                                    modifier = Modifier
                                                        .size(30.dp)
                                                        .testTag("pdf_search_prev_btn")
                                                ) {
                                                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Previous Match", tint = NeonCyan)
                                                }
                                                IconButton(
                                                    onClick = { navigateSearchResult(1) },
                                                    modifier = Modifier
                                                        .size(30.dp)
                                                        .testTag("pdf_search_next_btn")
                                                ) {
                                                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Next Match", tint = NeonCyan)
                                                }
                                            }
                                        }
                                    }
                                }

                                // Expandable Search Results Snippet List
                                AnimatedVisibility(visible = showSearchResultsList && searchResults.isNotEmpty()) {
                                    LazyColumn(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 170.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        itemsIndexed(searchResults, key = { _, r -> r.pageIndex }) { idx, res ->
                                            val isCurrentHit = idx == activeSearchResultIdx
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = if (isCurrentHit) NeonCyan.copy(alpha = 0.16f)
                                                else MaterialTheme.colorScheme.surface,
                                                border = BorderStroke(
                                                    0.8.dp,
                                                    if (isCurrentHit) NeonCyan else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                                                ),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .clickable {
                                                        activeSearchResultIdx = idx
                                                        showSearchResultsList = false
                                                        jumpToPage(res.pageIndex, animateBookFlip = true)
                                                    }
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    Surface(
                                                        shape = RoundedCornerShape(6.dp),
                                                        color = WarningOrange.copy(alpha = 0.2f)
                                                    ) {
                                                        Text(
                                                            text = "p.${res.pageIndex + 1}",
                                                            fontSize = 10.sp,
                                                            fontFamily = FontFamily.Monospace,
                                                            fontWeight = FontWeight.Black,
                                                            color = WarningOrange,
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                    Text(
                                                        text = res.snippet,
                                                        fontSize = 10.5.sp,
                                                        color = MaterialTheme.colorScheme.onSurface,
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    Text(
                                                        text = "${res.matchCount}×",
                                                        fontSize = 9.5.sp,
                                                        fontFamily = FontFamily.Monospace,
                                                        fontWeight = FontWeight.Bold,
                                                        color = NeonCyan
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Collapsible PDF Edit Tools Strip (Rotate, Annotate, Add Page, Delete Page)
                        AnimatedVisibility(visible = showEditTools) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AssistChip(
                                    onClick = onRotatePage,
                                    leadingIcon = { Icon(Icons.Default.RotateRight, contentDescription = null, modifier = Modifier.size(15.dp)) },
                                    label = { Text("Rotate", fontSize = 10.5.sp) }
                                )
                                AssistChip(
                                    onClick = onAddAnnotation,
                                    leadingIcon = { Icon(Icons.Default.TextFields, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(15.dp)) },
                                    label = { Text("+Note", fontSize = 10.5.sp) }
                                )
                                AssistChip(
                                    onClick = onAddNewPage,
                                    leadingIcon = { Icon(Icons.Default.NoteAdd, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(15.dp)) },
                                    label = { Text("+Page", fontSize = 10.5.sp) }
                                )
                                AssistChip(
                                    onClick = onDeleteCurrentPage,
                                    leadingIcon = { Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = DangerRed, modifier = Modifier.size(15.dp)) },
                                    label = { Text("Del", fontSize = 10.5.sp, color = DangerRed) }
                                )
                            }
                        }
                    }
                }
            }

            // =================================================================
            // MAIN E-BOOK READING CANVAS (CONTINUOUS SCROLL OR 3D BOOK SWIPE)
            // =================================================================
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pointerInput(layoutMode) {
                        if (layoutMode == PdfReadingLayout.CONTINUOUS_SCROLL) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val nextScale = (zoomScale * zoom).coerceIn(1.0f, 3.5f)
                                zoomScale = nextScale
                                panOffset = if (nextScale > 1.02f) {
                                    Offset(
                                        x = (panOffset.x + pan.x).coerceIn(-650f * (nextScale - 1f), 650f * (nextScale - 1f)),
                                        y = 0f
                                    )
                                } else {
                                    Offset.Zero
                                }
                            }
                        } else {
                            // Single-Page Book Mode: ONLY intercept 2-finger pinch-zoom or zoomed panning,
                            // letting single-finger horizontal swipes flow smoothly to HorizontalPager!
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                do {
                                    val event = awaitPointerEvent()
                                    val pressedCount = event.changes.count { it.pressed }
                                    val isPinch = pressedCount >= 2
                                    val isZoomedPan = zoomScale > 1.05f && pressedCount == 1
                                    if (isPinch || isZoomedPan) {
                                        val zoomChange = event.calculateZoom()
                                        val panChange = event.calculatePan()
                                        val nextScale = (zoomScale * zoomChange).coerceIn(1.0f, 3.5f)
                                        zoomScale = nextScale
                                        panOffset = if (nextScale > 1.02f) {
                                            Offset(
                                                x = (panOffset.x + panChange.x).coerceIn(-650f * (nextScale - 1f), 650f * (nextScale - 1f)),
                                                y = (panOffset.y + panChange.y).coerceIn(-850f * (nextScale - 1f), 850f * (nextScale - 1f))
                                            )
                                        } else {
                                            Offset.Zero
                                        }
                                        event.changes.forEach { it.consume() }
                                    }
                                } while (event.changes.any { it.pressed })
                            }
                        }
                    }
                    .pointerInput(layoutMode, currentPageIdx, pages.size) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (zoomScale > 1.15f) {
                                    zoomScale = 1.0f
                                    panOffset = Offset.Zero
                                } else {
                                    zoomScale = 2.1f
                                    panOffset = Offset.Zero
                                }
                            },
                            onTap = { tapOffset ->
                                if (layoutMode == PdfReadingLayout.SINGLE_PAGE && zoomScale <= 1.05f) {
                                    val w = size.width.toFloat().coerceAtLeast(1f)
                                    when {
                                        tapOffset.x < w * 0.20f -> {
                                            if (currentPageIdx > 0) jumpToPage(currentPageIdx - 1, animateBookFlip = true)
                                        }
                                        tapOffset.x > w * 0.80f -> {
                                            if (currentPageIdx < pages.lastIndex) jumpToPage(currentPageIdx + 1, animateBookFlip = true)
                                        }
                                        else -> onToggleImmersive()
                                    }
                                } else {
                                    onToggleImmersive()
                                }
                            }
                        )
                    }
            ) {
                if (layoutMode == PdfReadingLayout.CONTINUOUS_SCROLL) {
                    LazyColumn(
                        state = scrollListState,
                        contentPadding = PaddingValues(
                            horizontal = if (immersiveFullscreen) 4.dp else 10.dp,
                            vertical = 10.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = zoomScale
                                scaleY = zoomScale
                                translationX = panOffset.x
                            }
                    ) {
                        itemsIndexed(pages, key = { idx, p -> "${idx}_${p.originalPageIndex}_${p.rotationDegrees}" }) { idx, pageSpec ->
                            val pageAnns = annotations.filter { it.pageIndex == idx }
                            val searchMatch = searchResultsByPage[idx]
                            ProPdfSinglePageCard(
                                pageIndex = idx,
                                totalPages = totalPages,
                                pageSpec = pageSpec,
                                pageAnnotations = pageAnns,
                                renderSession = renderSession,
                                targetWidthPx = if (zoomScale > 1.6f) 1600 else 1180,
                                colorFilter = colorFilter,
                                theme = theme,
                                isBookmarked = bookmarks.any { it.pageIndex == idx },
                                searchHighlightRects = searchMatch?.normalizedRects.orEmpty(),
                                searchHitBadgeCount = searchMatch?.matchCount ?: 0,
                                onDeleteAnnotation = onDeleteAnnotation,
                                onEditCustomPageBody = { updated ->
                                    if (idx == currentPageIdx) onEditCustomPageBody(updated)
                                }
                            )
                        }
                    }
                } else {
                    // Single-Page Book Reading Mode with 1:1 Finger-Tracked HorizontalPager & 3D Book-Flip Animation
                    HorizontalPager(
                        state = bookPagerState,
                        beyondViewportPageCount = 1,
                        userScrollEnabled = zoomScale <= 1.05f,
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("pdf_single_page_book_pager")
                    ) { pageIdx ->
                        val pageSpec = pages.getOrNull(pageIdx) ?: return@HorizontalPager
                        val pageAnns = annotations.filter { it.pageIndex == pageIdx }
                        val searchMatch = searchResultsByPage[pageIdx]
                        val pageOffset = ((bookPagerState.currentPage - pageIdx) + bookPagerState.currentPageOffsetFraction)
                        val clampedAbsOffset = abs(pageOffset).coerceIn(0f, 1f)
                        val pageZIndex = when (swipeEffect) {
                            PdfPageSwipeEffect.BOOK_FLIP, PdfPageSwipeEffect.PAPER_SLIDE -> {
                                // Turning/outgoing page floats cleanly ABOVE the incoming page underneath
                                if (pageOffset >= 0f) 2f - pageOffset else 0f
                            }
                            PdfPageSwipeEffect.DEPTH_CURL -> 1f - clampedAbsOffset
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .zIndex(pageZIndex)
                                .graphicsLayer {
                                    when (swipeEffect) {
                                        PdfPageSwipeEffect.BOOK_FLIP -> {
                                            cameraDistance = 14f * density
                                            if (pageOffset in 0f..1f) {
                                                // Outgoing page turning around the left book spine hinge!
                                                transformOrigin = TransformOrigin(0f, 0.5f)
                                                translationX = size.width * pageOffset
                                                rotationY = -102f * pageOffset
                                                val liftScale = 1f - 0.03f * pageOffset
                                                scaleX = liftScale * zoomScale
                                                scaleY = liftScale * zoomScale
                                                alpha = (1f - ((pageOffset - 0.72f).coerceAtLeast(0f) / 0.28f)).coerceIn(0f, 1f)
                                            } else if (pageOffset in -1f..0f) {
                                                // Incoming page sitting neatly underneath the turning sheet
                                                transformOrigin = TransformOrigin(0.5f, 0.5f)
                                                translationX = size.width * pageOffset
                                                val reveal = 1f + pageOffset // 0 -> 1
                                                val underScale = 0.955f + 0.045f * reveal
                                                scaleX = underScale * zoomScale
                                                scaleY = underScale * zoomScale
                                                alpha = (0.72f + 0.28f * reveal).coerceIn(0f, 1f)
                                            } else {
                                                alpha = 0f
                                            }
                                        }
                                        PdfPageSwipeEffect.DEPTH_CURL -> {
                                            cameraDistance = 16f * density
                                            val scale = 1f - 0.14f * clampedAbsOffset
                                            scaleX = scale * zoomScale
                                            scaleY = scale * zoomScale
                                            rotationY = (-24f * pageOffset).coerceIn(-30f, 30f)
                                            alpha = (1f - 0.45f * clampedAbsOffset).coerceIn(0.3f, 1f)
                                        }
                                        PdfPageSwipeEffect.PAPER_SLIDE -> {
                                            if (pageOffset in 0f..1f) {
                                                translationX = 0f
                                                scaleX = zoomScale
                                                scaleY = zoomScale
                                                alpha = 1f
                                            } else if (pageOffset in -1f..0f) {
                                                translationX = size.width * pageOffset
                                                val reveal = 1f + pageOffset
                                                val underScale = 0.94f + 0.06f * reveal
                                                scaleX = underScale * zoomScale
                                                scaleY = underScale * zoomScale
                                                alpha = (0.65f + 0.35f * reveal).coerceIn(0f, 1f)
                                            } else {
                                                alpha = 0f
                                            }
                                        }
                                    }
                                    if (zoomScale > 1.02f && pageIdx == bookPagerState.currentPage) {
                                        translationX += panOffset.x
                                        translationY += panOffset.y
                                    }
                                }
                                .padding(horizontal = if (immersiveFullscreen) 2.dp else 10.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                ProPdfSinglePageCard(
                                    pageIndex = pageIdx,
                                    totalPages = totalPages,
                                    pageSpec = pageSpec,
                                    pageAnnotations = pageAnns,
                                    renderSession = renderSession,
                                    targetWidthPx = if (zoomScale > 1.6f) 1800 else 1280,
                                    colorFilter = colorFilter,
                                    theme = theme,
                                    isBookmarked = bookmarks.any { it.pageIndex == pageIdx },
                                    searchHighlightRects = searchMatch?.normalizedRects.orEmpty(),
                                    searchHitBadgeCount = searchMatch?.matchCount ?: 0,
                                    onDeleteAnnotation = onDeleteAnnotation,
                                    onEditCustomPageBody = { updated ->
                                        if (pageIdx == currentPageIdx) onEditCustomPageBody(updated)
                                    }
                                )

                                // Dynamic Book Spine Fold & Page-Turn Cast Shadow Overlay during swipe
                                if (swipeEffect == PdfPageSwipeEffect.BOOK_FLIP && clampedAbsOffset > 0.01f) {
                                    if (pageOffset in 0f..1f) {
                                        // Subtle specular sheen + spine crease darkening on turning page
                                        Box(
                                            modifier = Modifier
                                                .matchParentSize()
                                                .background(
                                                    Brush.horizontalGradient(
                                                        colors = listOf(
                                                            Color.Black.copy(alpha = (0.28f * pageOffset).coerceIn(0f, 0.32f)),
                                                            Color.White.copy(alpha = (0.10f * pageOffset).coerceIn(0f, 0.12f)),
                                                            Color.Black.copy(alpha = (0.22f * pageOffset).coerceIn(0f, 0.25f))
                                                        )
                                                    )
                                                )
                                        )
                                    } else if (pageOffset in -1f..0f) {
                                        // Sweeping cast shadow on the underneath revealed page as top page lifts away
                                        val shadowStrength = (-pageOffset).coerceIn(0f, 1f)
                                        Box(
                                            modifier = Modifier
                                                .matchParentSize()
                                                .background(
                                                    Brush.horizontalGradient(
                                                        colors = listOf(
                                                            Color.Black.copy(alpha = 0.45f * shadowStrength),
                                                            Color.Black.copy(alpha = 0.24f * shadowStrength),
                                                            Color.Transparent
                                                        )
                                                    )
                                                )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Subtle floating reading progress pill when in Immersive Fullscreen mode
                if (immersiveFullscreen) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.58f),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .navigationBarsPadding()
                            .padding(end = 12.dp, bottom = 10.dp)
                            .clickable { onToggleImmersive() }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            if (isCurrentPageBookmarked) {
                                Icon(Icons.Default.Bookmark, contentDescription = null, tint = WarningOrange, modifier = Modifier.size(12.dp))
                            }
                            Text(
                                text = "${currentPageIdx + 1} / $totalPages  •  $progressPercent%",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = Color.White.copy(alpha = 0.88f)
                            )
                        }
                    }
                }

                // Zoom Reset Floating Pill when zoomed in
                if (zoomScale > 1.1f) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.6f)),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp)
                            .clickable {
                                zoomScale = 1.0f
                                panOffset = Offset.Zero
                            }
                    ) {
                        Text(
                            text = "${(zoomScale * 100).roundToInt()}%  ✕",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Black,
                            color = NeonCyan,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
            }

            // =================================================================
            // BOTTOM E-BOOK PROGRESS SCRUBBER & PAGE NAVIGATION BAR
            // =================================================================
            AnimatedVisibility(
                visible = !immersiveFullscreen,
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it }
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
                    tonalElevation = 6.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        if (pages.size > 1) {
                            Slider(
                                value = currentPageIdx.toFloat(),
                                onValueChange = { raw ->
                                    jumpToPage(raw.roundToInt())
                                },
                                valueRange = 0f..(pages.lastIndex.coerceAtLeast(1)).toFloat(),
                                colors = SliderDefaults.colors(
                                    thumbColor = NeonCyan,
                                    activeTrackColor = NeonCyan
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(26.dp)
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            IconButton(
                                onClick = { if (currentPageIdx > 0) jumpToPage(currentPageIdx - 1, animateBookFlip = true) },
                                enabled = currentPageIdx > 0,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Default.ChevronLeft, contentDescription = "Previous Page")
                            }

                            // Tappable Page Indicator → Jump to Page dialog
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { showJumpToPageDialog = true }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Text(
                                        text = "Page ${currentPageIdx + 1} of $totalPages",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "• $progressPercent%",
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = NeonCyan
                                    )
                                    Icon(
                                        imageVector = Icons.Default.Shortcut,
                                        contentDescription = "Jump to Page",
                                        tint = WarningOrange,
                                        modifier = Modifier.size(13.dp)
                                    )
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = onToggleImmersive,
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Fullscreen,
                                        contentDescription = "Fullscreen Reading Mode",
                                        tint = NeonCyan,
                                        modifier = Modifier.size(19.dp)
                                    )
                                }
                                IconButton(
                                    onClick = { if (currentPageIdx < pages.lastIndex) jumpToPage(currentPageIdx + 1, animateBookFlip = true) },
                                    enabled = currentPageIdx < pages.lastIndex,
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(Icons.Default.ChevronRight, contentDescription = "Next Page")
                                }
                            }
                        }
                    }
                }
            }
        }

        // Bedtime Eye-Care Dimmer Overlay (non-blocking visual tint)
        if (dimmerAlpha > 0.01f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = dimmerAlpha))
            )
        }
    }

    // =========================================================================
    // MODALS: JUMP TO PAGE, BOOKMARK NOTE, READER SETTINGS, BOOKMARKS & PAGE GRID
    // =========================================================================

    if (showJumpToPageDialog) {
        var pageInput by remember { mutableStateOf((currentPageIdx + 1).toString()) }
        val previewPage = (pageInput.toIntOrNull() ?: (currentPageIdx + 1)).coerceIn(1, totalPages)
        fun confirmJump() {
            jumpToPage(previewPage - 1, animateBookFlip = true)
            showJumpToPageDialog = false
        }
        AlertDialog(
            onDismissRequest = { showJumpToPageDialog = false },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Shortcut, contentDescription = null, tint = WarningOrange, modifier = Modifier.size(20.dp))
                    Text("Jump to Page", fontSize = 15.5.sp, fontWeight = FontWeight.Black)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = pageInput,
                        onValueChange = { raw -> pageInput = raw.filter { it.isDigit() }.take(6) },
                        label = { Text("Enter page number (1 – $totalPages)", fontSize = 11.5.sp) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Go
                        ),
                        keyboardActions = KeyboardActions(onGo = { confirmJump() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("pdf_jump_page_input")
                    )

                    if (totalPages > 1) {
                        Slider(
                            value = previewPage.toFloat(),
                            onValueChange = { v -> pageInput = v.roundToInt().coerceIn(1, totalPages).toString() },
                            valueRange = 1f..totalPages.toFloat(),
                            colors = SliderDefaults.colors(thumbColor = NeonCyan, activeTrackColor = NeonCyan)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            val presets = listOf(
                                "First" to 1,
                                "25%" to ((totalPages * 0.25f).roundToInt().coerceIn(1, totalPages)),
                                "50%" to ((totalPages * 0.50f).roundToInt().coerceIn(1, totalPages)),
                                "75%" to ((totalPages * 0.75f).roundToInt().coerceIn(1, totalPages)),
                                "Last" to totalPages
                            )
                            presets.forEach { (label, targetNum) ->
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (previewPage == targetNum) NeonCyan.copy(alpha = 0.22f)
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                    border = BorderStroke(
                                        0.8.dp,
                                        if (previewPage == targetNum) NeonCyan else MaterialTheme.colorScheme.outlineVariant
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { pageInput = targetNum.toString() }
                                ) {
                                    Text(
                                        text = label,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (previewPage == targetNum) NeonCyan else MaterialTheme.colorScheme.onSurface,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { confirmJump() },
                    modifier = Modifier.testTag("pdf_jump_confirm_btn")
                ) { Text("Jump to p. $previewPage") }
            },
            dismissButton = {
                TextButton(onClick = { showJumpToPageDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showBookmarkNameDialog) {
        var labelInput by remember { mutableStateOf("Page ${currentPageIdx + 1}") }
        AlertDialog(
            onDismissRequest = { showBookmarkNameDialog = false },
            title = { Text("Add E-Book Bookmark", fontSize = 15.sp, fontWeight = FontWeight.Black) },
            text = {
                OutlinedTextField(
                    value = labelInput,
                    onValueChange = { labelInput = it },
                    label = { Text("Chapter or note label", fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val next = (bookmarks.filterNot { it.pageIndex == currentPageIdx } +
                            PdfBookmarkRecord(currentPageIdx, labelInput.trim().ifBlank { "Page ${currentPageIdx + 1}" }))
                            .sortedBy { it.pageIndex }
                        bookmarks = next
                        PdfEbookReadingStore.saveBookmarks(context, originalFile, next)
                        showBookmarkNameDialog = false
                        ApkPackageInstaller.showToastOnMain(context, "Bookmarked page ${currentPageIdx + 1}")
                    }
                ) { Text("Save Bookmark") }
            },
            dismissButton = {
                TextButton(onClick = { showBookmarkNameDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showReaderSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showReaderSettingsDialog = false },
            title = { Text("E-Book Reader Settings", fontSize = 15.sp, fontWeight = FontWeight.Black) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Reading Layout", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = NeonCyan)
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        PdfReadingLayout.entries.forEachIndexed { idx, mode ->
                            SegmentedButton(
                                selected = layoutMode == mode,
                                onClick = {
                                    layoutMode = mode
                                    PdfEbookReadingStore.saveLayout(context, mode)
                                    zoomScale = 1.0f
                                    panOffset = Offset.Zero
                                },
                                shape = SegmentedButtonDefaults.itemShape(idx, PdfReadingLayout.entries.size),
                                label = { Text(mode.label, fontSize = 10.5.sp) }
                            )
                        }
                    }

                    Text("Book Page-Turn Animation (Single Page)", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = SuccessGreen)
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        PdfPageSwipeEffect.entries.forEachIndexed { idx, effect ->
                            SegmentedButton(
                                selected = swipeEffect == effect,
                                onClick = {
                                    swipeEffect = effect
                                    PdfEbookReadingStore.saveSwipeEffect(context, effect)
                                },
                                shape = SegmentedButtonDefaults.itemShape(idx, PdfPageSwipeEffect.entries.size),
                                label = { Text(effect.label, fontSize = 9.5.sp, maxLines = 1) }
                            )
                        }
                    }

                    Text(
                        text = "Eye-Care Screen Dimmer (${(dimmerAlpha * 100).roundToInt()}%)",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = WarningOrange
                    )
                    Slider(
                        value = dimmerAlpha,
                        onValueChange = {
                            dimmerAlpha = it
                            PdfEbookReadingStore.saveDimmer(context, it)
                        },
                        valueRange = 0f..0.65f
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Keep Screen Awake", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text("Prevent screen sleep while reading ebooks", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = keepScreenOn,
                            onCheckedChange = {
                                keepScreenOn = it
                                PdfEbookReadingStore.saveKeepScreenOn(context, it)
                            }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Zoom: ${(zoomScale * 100).roundToInt()}%", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                        OutlinedButton(
                            onClick = {
                                zoomScale = 1.0f
                                panOffset = Offset.Zero
                            }
                        ) { Text("Reset Fit-Width", fontSize = 11.sp) }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showReaderSettingsDialog = false }) { Text("Done") }
            }
        )
    }

    if (showBookmarksAndGridDialog) {
        var drawerTab by remember { mutableIntStateOf(0) } // 0 = Page Grid, 1 = Bookmarks
        Dialog(onDismissRequest = { showBookmarksAndGridDialog = false }) {
            Card(
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.4f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(520.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("E-BOOK NAVIGATOR", fontSize = 13.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, color = NeonCyan)
                        IconButton(onClick = { showBookmarksAndGridDialog = false }, modifier = Modifier.size(30.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Close", modifier = Modifier.size(18.dp))
                        }
                    }

                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                    ) {
                        SegmentedButton(
                            selected = drawerTab == 0,
                            onClick = { drawerTab = 0 },
                            shape = SegmentedButtonDefaults.itemShape(0, 2),
                            label = { Text("Pages ($totalPages)", fontSize = 11.sp) }
                        )
                        SegmentedButton(
                            selected = drawerTab == 1,
                            onClick = { drawerTab = 1 },
                            shape = SegmentedButtonDefaults.itemShape(1, 2),
                            label = { Text("Bookmarks (${bookmarks.size})", fontSize = 11.sp) }
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    if (drawerTab == 0) {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            contentPadding = PaddingValues(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            itemsIndexed(pages) { idx, spec ->
                                val isCurrent = idx == currentPageIdx
                                val isBm = bookmarks.any { it.pageIndex == idx }
                                PdfThumbnailGridTile(
                                    pageIndex = idx,
                                    pageSpec = spec,
                                    renderSession = renderSession,
                                    colorFilter = colorFilter,
                                    isCurrent = isCurrent,
                                    isBookmarked = isBm,
                                    onClick = {
                                        showBookmarksAndGridDialog = false
                                        jumpToPage(idx)
                                    }
                                )
                            }
                        }
                    } else {
                        if (bookmarks.isEmpty()) {
                            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(Icons.Default.BookmarkBorder, contentDescription = null, tint = WarningOrange, modifier = Modifier.size(36.dp))
                                    Text("No bookmarks yet", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    Text("Tap the bookmark icon while reading to save chapters", fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        } else {
                            LazyColumn(
                                contentPadding = PaddingValues(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                items(bookmarks, key = { it.pageIndex }) { bm ->
                                    Card(
                                        shape = RoundedCornerShape(10.dp),
                                        border = BorderStroke(1.dp, WarningOrange.copy(alpha = 0.45f)),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                showBookmarksAndGridDialog = false
                                                jumpToPage(bm.pageIndex)
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 12.dp, vertical = 9.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(Icons.Default.Bookmark, contentDescription = null, tint = WarningOrange, modifier = Modifier.size(18.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(bm.label, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text("Page ${bm.pageIndex + 1} of $totalPages", fontSize = 10.sp, color = NeonCyan)
                                            }
                                            IconButton(
                                                onClick = {
                                                    val next = bookmarks.filterNot { it.pageIndex == bm.pageIndex }
                                                    bookmarks = next
                                                    PdfEbookReadingStore.saveBookmarks(context, originalFile, next)
                                                },
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(Icons.Default.DeleteOutline, contentDescription = "Delete Bookmark", tint = DangerRed, modifier = Modifier.size(16.dp))
                                            }
                                        }
                                    }
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
private fun ProPdfSinglePageCard(
    pageIndex: Int,
    totalPages: Int,
    pageSpec: PdfEditSessionPage,
    pageAnnotations: List<PdfTextAnnotation>,
    renderSession: FastPdfRenderSession,
    targetWidthPx: Int,
    colorFilter: ColorFilter?,
    theme: PdfEbookTheme,
    isBookmarked: Boolean,
    searchHighlightRects: List<PdfNormalizedHighlightRect> = emptyList(),
    searchHitBadgeCount: Int = 0,
    onDeleteAnnotation: (Long) -> Unit,
    onEditCustomPageBody: (String) -> Unit
) {
    val origIdx = pageSpec.originalPageIndex
    var renderedBmp by remember(origIdx, pageSpec.rotationDegrees, targetWidthPx) {
        mutableStateOf(
            if (origIdx != null) renderSession.getCachedPage(origIdx, targetWidthPx, pageSpec.rotationDegrees) else null
        )
    }

    LaunchedEffect(origIdx, pageSpec.rotationDegrees, targetWidthPx) {
        if (origIdx != null) {
            if (renderedBmp == null || renderedBmp?.isRecycled == true) {
                renderedBmp = withContext(Dispatchers.IO) {
                    renderSession.renderPage(origIdx, targetWidthPx, pageSpec.rotationDegrees, isThumbnail = false)
                }
            }
            // Quietly prefetch next & previous pages for zero-flash book swiping and scrolling
            withContext(Dispatchers.IO) {
                if (origIdx + 1 < totalPages) {
                    renderSession.renderPage(origIdx + 1, targetWidthPx, 0, isThumbnail = false)
                }
                if (origIdx - 1 >= 0) {
                    renderSession.renderPage(origIdx - 1, targetWidthPx, 0, isThumbnail = false)
                }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = if (theme == PdfEbookTheme.AMOLED_NIGHT) Color.Black else Color.White,
            shadowElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 820.dp)
        ) {
            if (origIdx != null) {
                val bmp = renderedBmp
                if (bmp != null && !bmp.isRecycled) {
                    val aspect = bmp.width.toFloat() / bmp.height.coerceAtLeast(1).toFloat()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(aspect.coerceIn(0.4f, 2.5f))
                    ) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "PDF Page ${pageIndex + 1}",
                            contentScale = ContentScale.FillWidth,
                            colorFilter = colorFilter,
                            modifier = Modifier.fillMaxSize()
                        )
                        // On-page Search Match Bounding-Box Highlights (Android 15+ native bounds)
                        if (searchHighlightRects.isNotEmpty()) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val padX = 2.dp.toPx()
                                val padY = 1.5.dp.toPx()
                                val radius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
                                searchHighlightRects.forEach { rect ->
                                    val l = (rect.leftFrac * size.width - padX).coerceAtLeast(0f)
                                    val t = (rect.topFrac * size.height - padY).coerceAtLeast(0f)
                                    val r = (rect.rightFrac * size.width + padX).coerceAtMost(size.width)
                                    val b = (rect.bottomFrac * size.height + padY).coerceAtMost(size.height)
                                    if (r > l && b > t) {
                                        drawRoundRect(
                                            color = Color(0x66FACC15),
                                            topLeft = Offset(l, t),
                                            size = Size(r - l, b - t),
                                            cornerRadius = radius
                                        )
                                    }
                                }
                            }
                        }
                        // Search Match Indicator Pill when search matches exist on this page
                        if (searchHitBadgeCount > 0) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xCCF59E0B),
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(6.dp)
                            ) {
                                Text(
                                    text = "$searchHitBadgeCount match${if (searchHitBadgeCount > 1) "es" else ""}",
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.Black,
                                    color = Color.Black,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                )
                            }
                        }
                        // Overlay user text annotations
                        pageAnnotations.forEach { ann ->
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(
                                        start = (ann.xFraction * 220f).dp,
                                        top = (ann.yFraction * 320f).dp
                                    )
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xD90F172A))
                                    .clickable { onDeleteAnnotation(ann.id) }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "${ann.text} ✕",
                                    fontSize = ann.fontSizeSp.coerceIn(10f, 20f).sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(ann.colorArgb)
                                )
                            }
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(480.dp)
                            .background(theme.chipColor.copy(alpha = 0.25f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            CircularProgressIndicator(color = NeonCyan, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                            Text("Rendering page ${pageIndex + 1}...", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
                // Editable Custom Added Text Page
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(theme.chipColor)
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = pageSpec.customPageTitle?.ifBlank { "Custom Page ${pageIndex + 1}" } ?: "Custom Page ${pageIndex + 1}",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Black,
                        color = theme.chipTextColor
                    )
                    OutlinedTextField(
                        value = pageSpec.customPageBody.orEmpty(),
                        onValueChange = onEditCustomPageBody,
                        placeholder = { Text("Write text for this PDF page...") },
                        minLines = 10,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // Subtle footer caption beneath each page
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (isBookmarked) {
                Icon(Icons.Default.Bookmark, contentDescription = null, tint = WarningOrange, modifier = Modifier.size(12.dp))
            }
            Text(
                text = "Page ${pageIndex + 1} of $totalPages",
                fontSize = 9.5.sp,
                fontFamily = FontFamily.Monospace,
                color = Color.White.copy(alpha = 0.55f),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun PdfThumbnailGridTile(
    pageIndex: Int,
    pageSpec: PdfEditSessionPage,
    renderSession: FastPdfRenderSession,
    colorFilter: ColorFilter?,
    isCurrent: Boolean,
    isBookmarked: Boolean,
    onClick: () -> Unit
) {
    val origIdx = pageSpec.originalPageIndex
    var thumbBmp by remember(origIdx, pageSpec.rotationDegrees) {
        mutableStateOf(
            if (origIdx != null) renderSession.getCachedThumb(origIdx, pageSpec.rotationDegrees) else null
        )
    }

    LaunchedEffect(origIdx, pageSpec.rotationDegrees) {
        if (origIdx != null && (thumbBmp == null || thumbBmp?.isRecycled == true)) {
            thumbBmp = withContext(Dispatchers.IO) {
                renderSession.renderPage(origIdx, targetWidthPx = 220, rotationDegrees = pageSpec.rotationDegrees, isThumbnail = true)
            }
        }
    }

    Card(
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(
            width = if (isCurrent) 2.dp else 1.dp,
            color = if (isCurrent) NeonCyan else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Column(
            modifier = Modifier.padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(112.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                val bmp = thumbBmp
                if (bmp != null && !bmp.isRecycled) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Page ${pageIndex + 1}",
                        contentScale = ContentScale.Crop,
                        colorFilter = colorFilter,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(
                        text = "${pageIndex + 1}",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black,
                        color = Color.Gray
                    )
                }
                if (isBookmarked) {
                    Icon(
                        Icons.Default.Bookmark,
                        contentDescription = null,
                        tint = WarningOrange,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(3.dp)
                            .size(16.dp)
                    )
                }
            }
            Text(
                text = "p. ${pageIndex + 1}",
                fontSize = 10.sp,
                fontWeight = if (isCurrent) FontWeight.Black else FontWeight.Medium,
                color = if (isCurrent) NeonCyan else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
    }
}
