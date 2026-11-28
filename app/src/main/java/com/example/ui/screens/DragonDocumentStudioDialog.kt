package com.example.ui.screens

import android.content.Context
import android.graphics.Color as AndroidColor
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
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
import com.example.data.filemanager.DocumentKind
import com.example.data.filemanager.DragonDocumentEngine
import com.example.data.filemanager.PdfEditSessionPage
import com.example.data.filemanager.PdfTextAnnotation
import com.example.data.filemanager.SavStringEntry
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

private enum class DocStudioTab(val label: String) {
    EDIT("Edit"),
    PREVIEW("Preview"),
    SAV_STRINGS("Strings & Values"),
    HEX("Hex")
}

/**
 * Full-Screen Universal Document Viewer & Editor Studio for Dragon Pro File Manager.
 *
 * Natively views & edits:
 * - `.txt`, `.xml`, `.json`, `.html`, `.md`, `.csv`, `.tsv`, `.ini`, `.cfg`, `.log`, `.yaml`, `.sql`, code files
 * - `.pdf` (multi-page viewer, zoom, night-invert, rotate/delete/add pages, text annotations, save/export PDF)
 * - `.sav` (auto-detects Text vs Binary `.sav`; supports Safe In-Place String/Int32 Patching, Hex Editor, & Raw Text with `.bak` backup)
 * - `.docx`, `.odt`, `.rtf`, `.xlsx`, `.pptx`
 * - Any arbitrary file in Text / Hex mode!
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DragonDocumentStudioDialog(
    context: Context,
    item: FileItemEntry,
    forceHexMode: Boolean = false,
    onDismiss: () -> Unit,
    onSaved: (File) -> Unit,
    onOpenWithExternalApp: (File) -> Unit
) {
    val scope = rememberCoroutineScope()

    var docKind by remember { mutableStateOf<DocumentKind?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var isSaving by remember { mutableStateOf(false) }
    var hasUnsavedChanges by remember { mutableStateOf(false) }
    var showDiscardConfirm by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showSaveAsDialog by remember { mutableStateOf(false) }

    // Text / XML / JSON / CSV / Office / Text-SAV state
    var textContent by remember { mutableStateOf("") }
    var undoStack by remember { mutableStateOf<List<String>>(emptyList()) }
    var encodingName by remember { mutableStateOf("UTF-8") }
    var isTruncated by remember { mutableStateOf(false) }
    var useLargeLogStreamMode by remember { mutableStateOf(false) }
    var fileTotalBytes by remember { mutableLongStateOf(item.lengthBytes) }
    var wordWrap by remember { mutableStateOf(true) }
    var editorFontSize by remember { mutableFloatStateOf(13f) }
    var showFindReplace by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var replaceQuery by remember { mutableStateOf("") }
    var validationBanner by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    // Binary .SAV / Hex state
    var binaryBytes by remember { mutableStateOf(ByteArray(0)) }
    var savStrings by remember { mutableStateOf<List<SavStringEntry>>(emptyList()) }
    var savSearchQuery by remember { mutableStateOf("") }
    var hexSearchQuery by remember { mutableStateOf("") }
    var createBackupOnSave by remember { mutableStateOf(true) }
    var editingSavEntry by remember { mutableStateOf<SavStringEntry?>(null) }
    var editingHexRowOffset by remember { mutableStateOf<Int?>(null) }

    // PDF E-Book Reader & Editor state
    var stagedPdfFile by remember { mutableStateOf<File?>(null) }
    var pdfPages by remember { mutableStateOf<List<PdfEditSessionPage>>(emptyList()) }
    var pdfAnnotations by remember { mutableStateOf<List<PdfTextAnnotation>>(emptyList()) }
    var currentPdfPageIdx by remember { mutableIntStateOf(0) }
    var immersivePdfFullscreen by remember { mutableStateOf(false) }
    var showAddPdfAnnotationDialog by remember { mutableStateOf(false) }
    var showAddPdfPageDialog by remember { mutableStateOf(false) }

    // Active tab
    var activeTab by remember { mutableStateOf(if (forceHexMode) DocStudioTab.HEX else DocStudioTab.EDIT) }

    // Cleanup staged PDF file on exit
    DisposableEffect(item.file.absolutePath) {
        onDispose {
            DragonDocumentEngine.cleanupStagedFile(stagedPdfFile, item.file)
        }
    }

    // Initial async document load
    LaunchedEffect(item.file.absolutePath, forceHexMode) {
        isLoading = true
        withContext(Dispatchers.IO) {
            val kind = if (forceHexMode) DocumentKind.SAVE_FILE_BINARY
            else DragonDocumentEngine.classifyDocument(context, item.file)
            docKind = kind

            when (kind) {
                DocumentKind.PDF -> {
                    val localPdf = DragonDocumentEngine.stageReadableLocalFile(context, item.file)
                    stagedPdfFile = localPdf
                    val pageCount = if (localPdf != null) DragonDocumentEngine.getPdfPageCount(localPdf) else 0
                    val loadedPages = if (pageCount > 0) {
                        (0 until pageCount).map { idx -> PdfEditSessionPage(originalPageIndex = idx) }
                    } else {
                        // Allow editing / creating a new empty PDF page if file was newly created empty
                        listOf(
                            PdfEditSessionPage(
                                originalPageIndex = null,
                                customPageTitle = item.file.nameWithoutExtension,
                                customPageBody = ""
                            )
                        )
                    }
                    pdfPages = loadedPages
                    currentPdfPageIdx = 0
                }
                DocumentKind.SAVE_FILE_BINARY -> {
                    val bytes = DragonDocumentEngine.readBinaryBytes(context, item.file)
                    binaryBytes = bytes
                    savStrings = DragonDocumentEngine.extractSavStringsAndValues(bytes)
                    textContent = String(bytes, Charsets.ISO_8859_1)
                    activeTab = if (forceHexMode) DocStudioTab.HEX else DocStudioTab.SAV_STRINGS
                }
                DocumentKind.OFFICE_DOCX -> {
                    textContent = DragonDocumentEngine.readDocxPlainText(context, item.file)
                    activeTab = DocStudioTab.EDIT
                }
                DocumentKind.OFFICE_ODT -> {
                    textContent = DragonDocumentEngine.readOdtPlainText(context, item.file)
                    activeTab = DocStudioTab.EDIT
                }
                DocumentKind.OFFICE_XLSX_PPTX -> {
                    textContent = DragonDocumentEngine.readXlsxOrPptxPlainText(context, item.file)
                    activeTab = DocStudioTab.PREVIEW
                }
                DocumentKind.RTF -> {
                    val loaded = DragonDocumentEngine.readTextDocument(context, item.file, encodingName)
                    textContent = DragonDocumentEngine.stripRtfToPlainText(loaded.text)
                    isTruncated = loaded.isTruncated
                    activeTab = DocStudioTab.EDIT
                }
                else -> {
                    val totalBytes = com.example.data.filemanager.DragonLargeLogReader.getTotalFileBytes(context, item.file)
                    fileTotalBytes = totalBytes
                    if (com.example.data.filemanager.DragonLargeLogReader.shouldUseLargeLogViewer(item.extension, totalBytes)) {
                        useLargeLogStreamMode = true
                        isTruncated = false
                        activeTab = DocStudioTab.EDIT
                    } else {
                        useLargeLogStreamMode = false
                        val loaded = DragonDocumentEngine.readTextDocument(context, item.file, encodingName)
                        textContent = loaded.text
                        encodingName = loaded.detectedEncoding
                        isTruncated = loaded.isTruncated
                        binaryBytes = DragonDocumentEngine.readBinaryBytes(context, item.file, maxBytes = 256 * 1024)
                        activeTab = DocStudioTab.EDIT
                    }
                }
            }
        }
        isLoading = false
    }

    // PDF rendering is handled inside DragonProPdfEbookReaderPane via FastPdfRenderSession (kept-open PdfRenderer + LRU cache)

    fun performSave(targetFile: File, closeAfter: Boolean = false) {
        if (isSaving) return
        scope.launch {
            isSaving = true
            val ok = withContext(Dispatchers.IO) {
                when (docKind) {
                    DocumentKind.PDF -> DragonDocumentEngine.exportEditedPdf(
                        context = context,
                        sourceLocalPdf = stagedPdfFile,
                        outputPdfFile = targetFile,
                        pages = pdfPages,
                        annotations = pdfAnnotations
                    )
                    DocumentKind.SAVE_FILE_BINARY -> {
                        val bytesToSave = if (activeTab == DocStudioTab.EDIT) {
                            textContent.toByteArray(Charsets.ISO_8859_1)
                        } else {
                            binaryBytes
                        }
                        DragonDocumentEngine.writeBinaryBytes(
                            context = context,
                            file = targetFile,
                            bytes = bytesToSave,
                            createBackup = createBackupOnSave && targetFile.absolutePath == item.file.absolutePath
                        )
                    }
                    DocumentKind.OFFICE_DOCX -> DragonDocumentEngine.saveDocxPlainText(
                        context = context,
                        targetFile = targetFile,
                        updatedText = textContent
                    )
                    DocumentKind.OFFICE_ODT -> DragonDocumentEngine.saveOdtPlainText(
                        context = context,
                        targetFile = targetFile,
                        updatedText = textContent
                    )
                    DocumentKind.RTF -> DragonDocumentEngine.writeTextDocument(
                        context = context,
                        file = targetFile,
                        text = DragonDocumentEngine.wrapPlainTextAsRtf(textContent),
                        charsetName = "UTF-8"
                    )
                    DocumentKind.OFFICE_XLSX_PPTX -> {
                        val outFile = if (targetFile.extension.equals("txt", true)) targetFile
                        else File(targetFile.parentFile, "${targetFile.nameWithoutExtension}_extracted.txt")
                        DragonDocumentEngine.writeTextDocument(context, outFile, textContent, "UTF-8")
                    }
                    else -> DragonDocumentEngine.writeTextDocument(
                        context = context,
                        file = targetFile,
                        text = textContent,
                        charsetName = encodingName,
                        createBackup = docKind == DocumentKind.SAVE_FILE_TEXT && createBackupOnSave
                    )
                }
            }
            isSaving = false
            if (ok) {
                hasUnsavedChanges = false
                ApkPackageInstaller.showToastOnMain(context, "Saved ${targetFile.name}")
                onSaved(targetFile)
                if (closeAfter) onDismiss()
            } else {
                ApkPackageInstaller.showToastOnMain(context, "Failed to save ${targetFile.name}. Check storage or Root permissions.")
            }
        }
    }

    Dialog(
        onDismissRequest = {
            if (hasUnsavedChanges) showDiscardConfirm = true else onDismiss()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .testTag("dragon_document_studio_dialog"),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .let { base ->
                        if (docKind == DocumentKind.PDF && immersivePdfFullscreen) base
                        else base.windowInsetsPadding(WindowInsets.systemBars)
                    }
            ) {
                // Top Studio App Bar (auto-hides during immersive fullscreen PDF ebook reading)
                AnimatedVisibility(visible = !(docKind == DocumentKind.PDF && immersivePdfFullscreen)) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 2.dp
                    ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        IconButton(
                            onClick = {
                                if (hasUnsavedChanges) showDiscardConfirm = true else onDismiss()
                            },
                            modifier = Modifier.testTag("doc_studio_close_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Close document"
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = item.name,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                if (hasUnsavedChanges) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(WarningOrange)
                                    )
                                }
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Surface(
                                    color = NeonCyan.copy(alpha = 0.16f),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = docKind?.badgeLabel ?: item.extension.uppercase(Locale.US).ifBlank { "DOC" },
                                        fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = NeonCyan,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                    )
                                }
                                Text(
                                    text = formatBytes(item.lengthBytes),
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Save button (hidden in large streaming log view to prevent accidental truncated overwrites)
                        if (!useLargeLogStreamMode) {
                            Button(
                                onClick = { performSave(item.file) },
                                enabled = !isLoading && !isSaving,
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .heightIn(min = 38.dp)
                                    .testTag("doc_studio_save_button")
                            ) {
                                if (isSaving) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                } else {
                                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                                Spacer(Modifier.width(5.dp))
                                Text("Save", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Box {
                            IconButton(
                                onClick = { showOverflowMenu = true },
                                modifier = Modifier.testTag("doc_studio_more_button")
                            ) {
                                Icon(Icons.Default.MoreVert, contentDescription = "Document options")
                            }
                            DropdownMenu(
                                expanded = showOverflowMenu,
                                onDismissRequest = { showOverflowMenu = false }
                            ) {
                                if (!useLargeLogStreamMode) {
                                    DropdownMenuItem(
                                        text = { Text("Save Copy As...") },
                                        leadingIcon = { Icon(Icons.Default.SaveAs, contentDescription = null) },
                                        onClick = {
                                            showOverflowMenu = false
                                            showSaveAsDialog = true
                                        }
                                    )
                                }
                                if (!useLargeLogStreamMode && docKind != DocumentKind.PDF && docKind != DocumentKind.SAVE_FILE_BINARY) {
                                    DropdownMenuItem(
                                        text = { Text("Export as PDF (.pdf)") },
                                        leadingIcon = { Icon(Icons.Default.PictureAsPdf, contentDescription = null, tint = DangerRed) },
                                        onClick = {
                                            showOverflowMenu = false
                                            scope.launch {
                                                val outPdf = File(item.file.parentFile, "${item.file.nameWithoutExtension}.pdf")
                                                val ok = withContext(Dispatchers.IO) {
                                                    DragonDocumentEngine.createPdfFromPlainText(
                                                        context = context,
                                                        outputPdfFile = outPdf,
                                                        title = item.name,
                                                        bodyText = textContent
                                                    )
                                                }
                                                if (ok) {
                                                    ApkPackageInstaller.showToastOnMain(context, "Exported ${outPdf.name}")
                                                    onSaved(outPdf)
                                                } else {
                                                    ApkPackageInstaller.showToastOnMain(context, "PDF export failed")
                                                }
                                            }
                                        }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Open with External App") },
                                    leadingIcon = { Icon(Icons.Default.OpenInNew, contentDescription = null) },
                                    onClick = {
                                        showOverflowMenu = false
                                        onOpenWithExternalApp(item.file)
                                    }
                                )
                            }
                        }
                    }
                }
                }

                if (!(docKind == DocumentKind.PDF && immersivePdfFullscreen)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                }

                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(color = NeonCyan)
                            Text(
                                text = "Opening ${item.name}...",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    when (docKind) {
                        DocumentKind.PDF -> {
                            DragonProPdfEbookReaderPane(
                                context = context,
                                originalFile = item.file,
                                stagedPdfFile = stagedPdfFile,
                                pages = pdfPages,
                                annotations = pdfAnnotations,
                                currentPageIdx = currentPdfPageIdx,
                                immersiveFullscreen = immersivePdfFullscreen,
                                onToggleImmersive = { immersivePdfFullscreen = !immersivePdfFullscreen },
                                onSelectPage = { currentPdfPageIdx = it.coerceIn(0, (pdfPages.size - 1).coerceAtLeast(0)) },
                                onRotatePage = {
                                    pdfPages = pdfPages.mapIndexed { idx, p ->
                                        if (idx == currentPdfPageIdx) p.copy(rotationDegrees = (p.rotationDegrees + 90) % 360)
                                        else p
                                    }
                                    hasUnsavedChanges = true
                                },
                                onAddAnnotation = { showAddPdfAnnotationDialog = true },
                                onDeleteAnnotation = { annId ->
                                    pdfAnnotations = pdfAnnotations.filterNot { it.id == annId }
                                    hasUnsavedChanges = true
                                },
                                onAddNewPage = { showAddPdfPageDialog = true },
                                onDeleteCurrentPage = {
                                    if (pdfPages.size > 1) {
                                        val removedIdx = currentPdfPageIdx
                                        pdfPages = pdfPages.filterIndexed { idx, _ -> idx != removedIdx }
                                        pdfAnnotations = pdfAnnotations
                                            .filterNot { it.pageIndex == removedIdx }
                                            .map { ann ->
                                                if (ann.pageIndex > removedIdx) ann.copy(pageIndex = ann.pageIndex - 1) else ann
                                            }
                                        currentPdfPageIdx = removedIdx.coerceAtMost(pdfPages.lastIndex)
                                        hasUnsavedChanges = true
                                    } else {
                                        ApkPackageInstaller.showToastOnMain(context, "PDF must have at least 1 page")
                                    }
                                },
                                onEditCustomPageBody = { newBody ->
                                    pdfPages = pdfPages.mapIndexed { idx, p ->
                                        if (idx == currentPdfPageIdx) p.copy(customPageBody = newBody) else p
                                    }
                                    hasUnsavedChanges = true
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                            )
                        }

                        DocumentKind.SAVE_FILE_BINARY -> {
                            BinarySavAndHexEditorPane(
                                bytes = binaryBytes,
                                savStrings = savStrings,
                                activeTab = activeTab,
                                savSearchQuery = savSearchQuery,
                                hexSearchQuery = hexSearchQuery,
                                rawTextContent = textContent,
                                createBackup = createBackupOnSave,
                                onTabSelect = { activeTab = it },
                                onSavSearchChange = { savSearchQuery = it },
                                onHexSearchChange = { hexSearchQuery = it },
                                onToggleBackup = { createBackupOnSave = it },
                                onClickEditSavEntry = { editingSavEntry = it },
                                onClickEditHexRow = { editingHexRowOffset = it },
                                onRawTextChange = {
                                    textContent = it
                                    hasUnsavedChanges = true
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                            )
                        }

                        else -> {
                            if (useLargeLogStreamMode) {
                                DragonLargeLogViewerPane(
                                    context = context,
                                    file = item.file,
                                    encodingName = encodingName,
                                    canSwitchToRawEditor = fileTotalBytes <= 256L * 1024L,
                                    onSwitchToRawEditor = {
                                        scope.launch {
                                            isLoading = true
                                            withContext(Dispatchers.IO) {
                                                val loaded = DragonDocumentEngine.readTextDocument(context, item.file, encodingName)
                                                textContent = loaded.text
                                                encodingName = loaded.detectedEncoding
                                                isTruncated = loaded.isTruncated
                                            }
                                            useLargeLogStreamMode = false
                                            isLoading = false
                                        }
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                )
                            } else {
                                TextCodeAndOfficeEditorPane(
                                docKind = docKind ?: DocumentKind.TEXT_OR_CODE,
                                fileExtension = item.extension.lowercase(Locale.US),
                                textContent = textContent,
                                binaryBytes = binaryBytes,
                                activeTab = activeTab,
                                encodingName = encodingName,
                                isTruncated = isTruncated,
                                wordWrap = wordWrap,
                                fontSize = editorFontSize,
                                canUndo = undoStack.isNotEmpty(),
                                showFindReplace = showFindReplace,
                                findQuery = findQuery,
                                replaceQuery = replaceQuery,
                                validationBanner = validationBanner,
                                createBackup = createBackupOnSave,
                                onTabSelect = { activeTab = it },
                                onTextChange = { updated ->
                                    if (undoStack.lastOrNull() != textContent) {
                                        undoStack = (undoStack + textContent).takeLast(20)
                                    }
                                    textContent = updated
                                    hasUnsavedChanges = true
                                    validationBanner = null
                                },
                                onUndo = {
                                    if (undoStack.isNotEmpty()) {
                                        textContent = undoStack.last()
                                        undoStack = undoStack.dropLast(1)
                                        hasUnsavedChanges = true
                                    }
                                },
                                onToggleFindReplace = { showFindReplace = !showFindReplace },
                                onFindQueryChange = { findQuery = it },
                                onReplaceQueryChange = { replaceQuery = it },
                                onReplaceAll = {
                                    if (findQuery.isNotEmpty()) {
                                        val count = textContent.windowed(findQuery.length, 1).count {
                                            it.equals(findQuery, ignoreCase = false)
                                        }
                                        if (count > 0) {
                                            undoStack = (undoStack + textContent).takeLast(20)
                                            textContent = textContent.replace(findQuery, replaceQuery)
                                            hasUnsavedChanges = true
                                            ApkPackageInstaller.showToastOnMain(context, "Replaced $count occurrence(s)")
                                        } else {
                                            ApkPackageInstaller.showToastOnMain(context, "No exact matches found")
                                        }
                                    }
                                },
                                onFormatAndValidate = {
                                    when (docKind) {
                                        DocumentKind.XML_OR_HTML -> {
                                            val (formatted, err) = DragonDocumentEngine.formatXmlPretty(textContent)
                                            if (formatted != null) {
                                                undoStack = (undoStack + textContent).takeLast(20)
                                                textContent = formatted
                                                hasUnsavedChanges = true
                                                validationBanner = true to "Valid XML • Formatted cleanly"
                                            } else {
                                                validationBanner = false to (err ?: "XML syntax error")
                                            }
                                        }
                                        DocumentKind.JSON -> {
                                            val (formatted, err) = DragonDocumentEngine.formatJsonPretty(textContent)
                                            if (formatted != null) {
                                                undoStack = (undoStack + textContent).takeLast(20)
                                                textContent = formatted
                                                hasUnsavedChanges = true
                                                validationBanner = true to "Valid JSON • Pretty-printed"
                                            } else {
                                                validationBanner = false to (err ?: "JSON syntax error")
                                            }
                                        }
                                        else -> Unit
                                    }
                                },
                                onToggleWordWrap = { wordWrap = !wordWrap },
                                onFontSizeChange = { editorFontSize = it.coerceIn(9f, 22f) },
                                onCycleEncoding = {
                                    val encodings = listOf("UTF-8", "UTF-16LE", "ISO-8859-1")
                                    val next = encodings[(encodings.indexOf(encodingName) + 1) % encodings.size]
                                    encodingName = next
                                    scope.launch {
                                        val reloaded = withContext(Dispatchers.IO) {
                                            DragonDocumentEngine.readTextDocument(context, item.file, next)
                                        }
                                        textContent = reloaded.text
                                        hasUnsavedChanges = false
                                        ApkPackageInstaller.showToastOnMain(context, "Reloaded as $next")
                                    }
                                },
                                onToggleBackup = { createBackupOnSave = it },
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                            )
                            }
                        }
                    }
                }
            }
        }
    }

    // Edit Binary .SAV String / Int32 Entry Dialog
    editingSavEntry?.let { entry ->
        SavEntryPatchDialog(
            entry = entry,
            onDismiss = { editingSavEntry = null },
            onApplyStringPatch = { newStr ->
                val patched = DragonDocumentEngine.patchSavStringInPlace(binaryBytes, entry, newStr)
                binaryBytes = patched
                savStrings = DragonDocumentEngine.extractSavStringsAndValues(patched)
                textContent = String(patched, Charsets.ISO_8859_1)
                hasUnsavedChanges = true
                editingSavEntry = null
            },
            onApplyInt32Patch = { intOffset, newIntVal ->
                val patched = DragonDocumentEngine.patchSavInt32LE(binaryBytes, intOffset, newIntVal)
                binaryBytes = patched
                savStrings = DragonDocumentEngine.extractSavStringsAndValues(patched)
                hasUnsavedChanges = true
                editingSavEntry = null
            }
        )
    }

    // Edit Hex Row Dialog
    editingHexRowOffset?.let { rowOffset ->
        val rowLen = minOf(8, (binaryBytes.size - rowOffset).coerceAtLeast(0))
        val initialHex = (0 until rowLen).joinToString(" ") { idx ->
            String.format(Locale.US, "%02X", binaryBytes[rowOffset + idx].toInt() and 0xFF)
        }
        HexRowEditDialog(
            offset = rowOffset,
            initialHex = initialHex,
            onDismiss = { editingHexRowOffset = null },
            onApply = { updatedHex ->
                val (patched, err) = DragonDocumentEngine.patchHexRow(binaryBytes, rowOffset, updatedHex)
                if (patched != null) {
                    binaryBytes = patched
                    savStrings = DragonDocumentEngine.extractSavStringsAndValues(patched)
                    textContent = String(patched, Charsets.ISO_8859_1)
                    hasUnsavedChanges = true
                    editingHexRowOffset = null
                } else {
                    ApkPackageInstaller.showToastOnMain(context, err ?: "Invalid hex input")
                }
            }
        )
    }

    // Add PDF Text Annotation Dialog
    if (showAddPdfAnnotationDialog) {
        AddPdfAnnotationDialog(
            pageNumber = currentPdfPageIdx + 1,
            onDismiss = { showAddPdfAnnotationDialog = false },
            onAdd = { text, yFraction, sizeSp, colorArgb ->
                pdfAnnotations = pdfAnnotations + PdfTextAnnotation(
                    pageIndex = currentPdfPageIdx,
                    text = text,
                    xFraction = 0.08f,
                    yFraction = yFraction,
                    fontSizeSp = sizeSp,
                    colorArgb = colorArgb
                )
                hasUnsavedChanges = true
                showAddPdfAnnotationDialog = false
            }
        )
    }

    // Add New PDF Text Page Dialog
    if (showAddPdfPageDialog) {
        AddPdfTextPageDialog(
            onDismiss = { showAddPdfPageDialog = false },
            onAddPage = { pageTitle, pageBody ->
                pdfPages = pdfPages + PdfEditSessionPage(
                    originalPageIndex = null,
                    customPageTitle = pageTitle,
                    customPageBody = pageBody
                )
                currentPdfPageIdx = pdfPages.lastIndex
                hasUnsavedChanges = true
                showAddPdfPageDialog = false
            }
        )
    }

    // Save Copy As Dialog
    if (showSaveAsDialog) {
        var copyName by remember {
            mutableStateOf("${item.file.nameWithoutExtension}_edited.${item.extension.ifBlank { "txt" }}")
        }
        AlertDialog(
            onDismissRequest = { showSaveAsDialog = false },
            title = { Text("Save Copy As", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = copyName,
                    onValueChange = { copyName = it },
                    label = { Text("File name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val clean = copyName.trim().replace('/', '_')
                        if (clean.isNotEmpty()) {
                            showSaveAsDialog = false
                            performSave(File(item.file.parentFile, clean))
                        }
                    }
                ) {
                    Text("Save Copy")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveAsDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Unsaved Changes Confirmation
    if (showDiscardConfirm) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text("Unsaved Changes", fontWeight = FontWeight.Bold) },
            text = { Text("Save your edits to ${item.name} before closing?") },
            confirmButton = {
                Button(
                    onClick = {
                        showDiscardConfirm = false
                        performSave(item.file, closeAfter = true)
                    }
                ) {
                    Text("Save & Close")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(
                        onClick = {
                            showDiscardConfirm = false
                            onDismiss()
                        }
                    ) {
                        Text("Discard", color = DangerRed)
                    }
                    TextButton(onClick = { showDiscardConfirm = false }) {
                        Text("Cancel")
                    }
                }
            }
        )
    }
}

// =============================================================================
// BINARY .SAV & HEX EDITOR PANE
// =============================================================================

@Composable
private fun BinarySavAndHexEditorPane(
    bytes: ByteArray,
    savStrings: List<SavStringEntry>,
    activeTab: DocStudioTab,
    savSearchQuery: String,
    hexSearchQuery: String,
    rawTextContent: String,
    createBackup: Boolean,
    onTabSelect: (DocStudioTab) -> Unit,
    onSavSearchChange: (String) -> Unit,
    onHexSearchChange: (String) -> Unit,
    onToggleBackup: (Boolean) -> Unit,
    onClickEditSavEntry: (SavStringEntry) -> Unit,
    onClickEditHexRow: (Int) -> Unit,
    onRawTextChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        // Tab Switcher + Auto-Backup chip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf(DocStudioTab.SAV_STRINGS, DocStudioTab.HEX, DocStudioTab.EDIT).forEach { tab ->
                FilterChip(
                    selected = activeTab == tab,
                    onClick = { onTabSelect(tab) },
                    label = {
                        Text(
                            text = if (tab == DocStudioTab.EDIT) "Raw Text" else tab.label,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                )
            }
            FilterChip(
                selected = createBackup,
                onClick = { onToggleBackup(!createBackup) },
                leadingIcon = {
                    Icon(
                        imageVector = if (createBackup) Icons.Default.VerifiedUser else Icons.Default.Shield,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                },
                label = { Text("Auto .bak", fontSize = 10.5.sp) }
            )
        }

        when (activeTab) {
            DocStudioTab.SAV_STRINGS -> {
                OutlinedTextField(
                    value = savSearchQuery,
                    onValueChange = onSavSearchChange,
                    placeholder = { Text("Filter .sav keys / strings (e.g., FPS, Coins, Player)...", fontSize = 12.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                )
                val filtered = remember(savStrings, savSearchQuery) {
                    val q = savSearchQuery.trim().lowercase(Locale.US)
                    if (q.isEmpty()) savStrings
                    else savStrings.filter {
                        it.text.lowercase(Locale.US).contains(q) ||
                            (it.trailingInt32?.toString()?.contains(q) == true)
                    }
                }
                if (filtered.isEmpty()) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "No readable ASCII entries matched. Switch to Hex Editor to edit raw bytes.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(20.dp)
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filtered, key = { it.offset }) { entry ->
                            Card(
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onClickEditSavEntry(entry) }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text(
                                            text = entry.text,
                                            fontSize = 12.5.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(
                                                text = String.format(Locale.US, "Offset 0x%06X • %dB", entry.offset, entry.length),
                                                fontSize = 9.5.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            entry.trailingInt32?.let { iv ->
                                                Text(
                                                    text = "Int32: $iv",
                                                    fontSize = 9.5.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontWeight = FontWeight.Bold,
                                                    color = WarningOrange
                                                )
                                            }
                                        }
                                    }
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = "Patch value",
                                        tint = NeonCyan,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            DocStudioTab.HEX -> {
                HexInspectorEditorList(
                    bytes = bytes,
                    searchQuery = hexSearchQuery,
                    onSearchChange = onHexSearchChange,
                    onClickRow = onClickEditHexRow,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
            }

            else -> {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(12.dp)
                ) {
                    OutlinedTextField(
                        value = rawTextContent,
                        onValueChange = onRawTextChange,
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

// =============================================================================
// TEXT, XML, JSON, CSV, MARKDOWN, HTML & OFFICE EDITOR PANE
// =============================================================================

@Composable
private fun TextCodeAndOfficeEditorPane(
    docKind: DocumentKind,
    fileExtension: String,
    textContent: String,
    binaryBytes: ByteArray,
    activeTab: DocStudioTab,
    encodingName: String,
    isTruncated: Boolean,
    wordWrap: Boolean,
    fontSize: Float,
    canUndo: Boolean,
    showFindReplace: Boolean,
    findQuery: String,
    replaceQuery: String,
    validationBanner: Pair<Boolean, String>?,
    createBackup: Boolean,
    onTabSelect: (DocStudioTab) -> Unit,
    onTextChange: (String) -> Unit,
    onUndo: () -> Unit,
    onToggleFindReplace: () -> Unit,
    onFindQueryChange: (String) -> Unit,
    onReplaceQueryChange: (String) -> Unit,
    onReplaceAll: () -> Unit,
    onFormatAndValidate: () -> Unit,
    onToggleWordWrap: () -> Unit,
    onFontSizeChange: (Float) -> Unit,
    onCycleEncoding: () -> Unit,
    onToggleBackup: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val supportsPreview = docKind in setOf(
        DocumentKind.CSV_OR_TSV,
        DocumentKind.MARKDOWN,
        DocumentKind.XML_OR_HTML,
        DocumentKind.OFFICE_DOCX,
        DocumentKind.OFFICE_ODT,
        DocumentKind.OFFICE_XLSX_PPTX,
        DocumentKind.RTF
    )
    val supportsFormat = docKind == DocumentKind.XML_OR_HTML || docKind == DocumentKind.JSON
    val lineCount = remember(textContent) { if (textContent.isEmpty()) 0 else textContent.count { it == '\n' } + 1 }

    Column(modifier = modifier) {
        // Compact Utility Toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (supportsPreview) {
                FilterChip(
                    selected = activeTab == DocStudioTab.EDIT,
                    onClick = { onTabSelect(DocStudioTab.EDIT) },
                    label = { Text("Edit", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                )
                FilterChip(
                    selected = activeTab == DocStudioTab.PREVIEW,
                    onClick = { onTabSelect(DocStudioTab.PREVIEW) },
                    label = { Text("Preview", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                )
            }
            if (binaryBytes.isNotEmpty() && docKind != DocumentKind.OFFICE_DOCX && docKind != DocumentKind.OFFICE_ODT) {
                FilterChip(
                    selected = activeTab == DocStudioTab.HEX,
                    onClick = { onTabSelect(DocStudioTab.HEX) },
                    label = { Text("Hex", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                )
            }
            if (activeTab == DocStudioTab.EDIT) {
                DocToolChip(icon = Icons.Default.FindReplace, label = "Find", tint = NeonCyan, onClick = onToggleFindReplace)
                if (supportsFormat) {
                    DocToolChip(
                        icon = Icons.Default.AutoFixHigh,
                        label = if (docKind == DocumentKind.JSON) "Format JSON" else "Format XML",
                        tint = SuccessGreen,
                        onClick = onFormatAndValidate
                    )
                }
                if (canUndo) {
                    DocToolChip(icon = Icons.AutoMirrored.Filled.Undo, label = "Undo", tint = WarningOrange, onClick = onUndo)
                }
                DocToolChip(
                    icon = Icons.Default.WrapText,
                    label = if (wordWrap) "Wrap: ON" else "Wrap: OFF",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = onToggleWordWrap
                )
                DocToolChip(icon = Icons.Default.TextDecrease, label = "A-", tint = MaterialTheme.colorScheme.onSurfaceVariant, onClick = { onFontSizeChange(fontSize - 1.5f) })
                DocToolChip(icon = Icons.Default.TextIncrease, label = "A+", tint = MaterialTheme.colorScheme.onSurfaceVariant, onClick = { onFontSizeChange(fontSize + 1.5f) })
                if (docKind != DocumentKind.OFFICE_DOCX && docKind != DocumentKind.OFFICE_ODT) {
                    DocToolChip(icon = Icons.Default.Translate, label = encodingName, tint = NeonCyan, onClick = onCycleEncoding)
                }
                if (docKind == DocumentKind.SAVE_FILE_TEXT) {
                    DocToolChip(
                        icon = Icons.Default.Shield,
                        label = if (createBackup) ".bak ON" else ".bak OFF",
                        tint = if (createBackup) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                        onClick = { onToggleBackup(!createBackup) }
                    )
                }
            }
        }

        // Optional Find & Replace Bar
        if (showFindReplace && activeTab == DocStudioTab.EDIT) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = findQuery,
                        onValueChange = onFindQueryChange,
                        placeholder = { Text("Find", fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = replaceQuery,
                        onValueChange = onReplaceQueryChange,
                        placeholder = { Text("Replace", fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    FilledTonalButton(
                        onClick = onReplaceAll,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("All", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // XML/JSON Validation Banner
        validationBanner?.let { (isValid, message) ->
            val accent = if (isValid) SuccessGreen else DangerRed
            Surface(
                color = accent.copy(alpha = 0.14f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = message,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                )
            }
        }

        if (isTruncated) {
            Surface(color = WarningOrange.copy(alpha = 0.16f), modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Large file notice: showing first 2 MB to keep editor responsive.",
                    fontSize = 10.5.sp,
                    color = WarningOrange,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        }

        // Main Content Area
        when (activeTab) {
            DocStudioTab.PREVIEW -> {
                if (docKind == DocumentKind.CSV_OR_TSV) {
                    val delim = if (fileExtension == "tsv") '\t' else ','
                    val rows = remember(textContent, delim) { DragonDocumentEngine.parseCsvRows(textContent, delim) }
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        itemsIndexed(rows) { rowIdx, cells ->
                            Row(
                                modifier = Modifier
                                    .background(
                                        if (rowIdx == 0) NeonCyan.copy(alpha = 0.16f)
                                        else if (rowIdx % 2 == 0) MaterialTheme.colorScheme.surface
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                                        RoundedCornerShape(6.dp)
                                    )
                                    .padding(vertical = 6.dp, horizontal = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                cells.forEach { cell ->
                                    Text(
                                        text = cell,
                                        fontSize = 11.5.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = if (rowIdx == 0) FontWeight.ExtraBold else FontWeight.Normal,
                                        color = if (rowIdx == 0) NeonCyan else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.widthIn(min = 90.dp, max = 220.dp),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp)
                    ) {
                        val renderedText = remember(textContent, docKind) {
                            if (docKind == DocumentKind.XML_OR_HTML && (fileExtension == "html" || fileExtension == "htm")) {
                                textContent.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s{2,}"), "\n").trim()
                            } else {
                                textContent
                            }
                        }
                        Text(
                            text = renderedText.ifBlank { "(Empty document)" },
                            fontSize = (fontSize + 1f).sp,
                            lineHeight = (fontSize + 7f).sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            DocStudioTab.HEX -> {
                var localHexSearch by remember { mutableStateOf("") }
                HexInspectorEditorList(
                    bytes = binaryBytes,
                    searchQuery = localHexSearch,
                    onSearchChange = { localHexSearch = it },
                    onClickRow = {},
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
            }

            else -> {
                val vScroll = rememberScrollState()
                val hScroll = rememberScrollState()
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f))
                        .verticalScroll(vScroll)
                        .then(if (!wordWrap) Modifier.horizontalScroll(hScroll) else Modifier)
                        .padding(12.dp)
                ) {
                    BasicTextField(
                        value = textContent,
                        onValueChange = onTextChange,
                        textStyle = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = fontSize.sp,
                            lineHeight = (fontSize + 5f).sp,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(NeonCyan),
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 420.dp)
                            .testTag("doc_studio_text_editor_field")
                    )
                }
            }
        }

        // Bottom Live Status Bar
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$lineCount lines • ${textContent.length} chars • $encodingName",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (docKind == DocumentKind.OFFICE_DOCX || docKind == DocumentKind.OFFICE_ODT) {
                        "Office Container"
                    } else if (docKind == DocumentKind.SAVE_FILE_TEXT) {
                        "Text .SAV"
                    } else {
                        fileExtension.uppercase(Locale.US).ifBlank { "TXT" }
                    },
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = NeonCyan
                )
            }
        }
    }
}

@Composable
private fun HexInspectorEditorList(
    bytes: ByteArray,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onClickRow: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val rowCount = remember(bytes.size) { (bytes.size + 7) / 8 }
    val matchingOffsets = remember(bytes, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) {
            (0 until minOf(rowCount, 4096)).map { it * 8 }
        } else {
            val qLower = q.lowercase(Locale.US)
            val matches = mutableListOf<Int>()
            for (r in 0 until minOf(rowCount, 16384)) {
                val off = r * 8
                val len = minOf(8, bytes.size - off)
                val hexPart = (0 until len).joinToString(" ") { idx ->
                    String.format(Locale.US, "%02x", bytes[off + idx].toInt() and 0xFF)
                }
                val asciiPart = (0 until len).map { idx ->
                    val b = bytes[off + idx].toInt() and 0xFF
                    if (b in 0x20..0x7E) b.toChar() else '.'
                }.joinToString("").lowercase(Locale.US)
                if (hexPart.contains(qLower) || asciiPart.contains(qLower)) {
                    matches.add(off)
                }
                if (matches.size >= 500) break
            }
            matches
        }
    }

    Column(modifier = modifier) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            placeholder = { Text("Search Hex (e.g., 47 56) or ASCII...", fontSize = 12.sp) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
        )
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            items(matchingOffsets, key = { it }) { offset ->
                val len = minOf(8, bytes.size - offset)
                val hexStr = (0 until len).joinToString(" ") { idx ->
                    String.format(Locale.US, "%02X", bytes[offset + idx].toInt() and 0xFF)
                }.padEnd(23, ' ')
                val asciiStr = (0 until len).map { idx ->
                    val b = bytes[offset + idx].toInt() and 0xFF
                    if (b in 0x20..0x7E) b.toChar() else '.'
                }.joinToString("")

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable { onClickRow(offset) }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = String.format(Locale.US, "%06X", offset),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = NeonCyan
                    )
                    Text(
                        text = hexStr,
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                        maxLines = 1
                    )
                    Text(
                        text = asciiStr,
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = WarningOrange
                    )
                }
            }
        }
    }
}

@Composable
private fun DocToolChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(9.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(imageVector = icon, contentDescription = label, tint = tint, modifier = Modifier.size(14.dp))
            Text(text = label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun SavEntryPatchDialog(
    entry: SavStringEntry,
    onDismiss: () -> Unit,
    onApplyStringPatch: (String) -> Unit,
    onApplyInt32Patch: (Int, Int) -> Unit
) {
    var editedText by remember { mutableStateOf(entry.text) }
    var editedIntText by remember { mutableStateOf(entry.trailingInt32?.toString().orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = String.format(Locale.US, "Edit .SAV @ 0x%06X", entry.offset),
                fontSize = 15.sp,
                fontWeight = FontWeight.ExtraBold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Safe In-Place String Patch (max ${entry.length} chars; shorter strings are NUL-padded so binary offsets never shift):",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = editedText,
                    onValueChange = { if (it.length <= entry.length) editedText = it },
                    label = { Text("ASCII Value (${editedText.length}/${entry.length})") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (entry.trailingIntOffset != null && entry.trailingInt32 != null) {
                    HorizontalDivider()
                    Text(
                        text = String.format(Locale.US, "Adjacent Int32 @ 0x%06X:", entry.trailingIntOffset),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = WarningOrange
                    )
                    OutlinedTextField(
                        value = editedIntText,
                        onValueChange = { editedIntText = it },
                        label = { Text("32-bit Integer Value") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (entry.trailingIntOffset != null && editedIntText.toIntOrNull() != null &&
                    editedIntText.toIntOrNull() != entry.trailingInt32
                ) {
                    FilledTonalButton(
                        onClick = {
                            editedIntText.toIntOrNull()?.let { onApplyInt32Patch(entry.trailingIntOffset, it) }
                        }
                    ) {
                        Text("Patch Int32", fontSize = 11.5.sp)
                    }
                }
                Button(onClick = { onApplyStringPatch(editedText) }) {
                    Text("Patch String", fontSize = 11.5.sp)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun HexRowEditDialog(
    offset: Int,
    initialHex: String,
    onDismiss: () -> Unit,
    onApply: (String) -> Unit
) {
    var hexInput by remember { mutableStateOf(initialHex) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(String.format(Locale.US, "Edit Hex @ 0x%06X", offset), fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = hexInput,
                onValueChange = { hexInput = it },
                label = { Text("Hex Bytes (space-separated)") },
                textStyle = TextStyle(fontFamily = FontFamily.Monospace),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = { onApply(hexInput) }) { Text("Apply Bytes") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun AddPdfAnnotationDialog(
    pageNumber: Int,
    onDismiss: () -> Unit,
    onAdd: (text: String, yFraction: Float, sizeSp: Float, colorArgb: Int) -> Unit
) {
    var noteText by remember { mutableStateOf("") }
    var yFraction by remember { mutableFloatStateOf(0.12f) }
    var fontSize by remember { mutableFloatStateOf(16f) }
    var selectedColor by remember { mutableIntStateOf(AndroidColor.parseColor("#DC2626")) }

    val colorChoices = listOf(
        "Red" to AndroidColor.parseColor("#DC2626"),
        "Blue" to AndroidColor.parseColor("#2563EB"),
        "Black" to AndroidColor.parseColor("#111827"),
        "Green" to AndroidColor.parseColor("#16A34A")
    )
    val positions = listOf("Top" to 0.08f, "Upper" to 0.28f, "Middle" to 0.48f, "Lower" to 0.68f, "Bottom" to 0.86f)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Text Note to Page $pageNumber", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text("Annotation / Text") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Vertical Position", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    positions.forEach { (label, frac) ->
                        FilterChip(
                            selected = yFraction == frac,
                            onClick = { yFraction = frac },
                            label = { Text(label, fontSize = 10.5.sp) }
                        )
                    }
                }
                Text("Text Color", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    colorChoices.forEach { (label, argb) ->
                        FilterChip(
                            selected = selectedColor == argb,
                            onClick = { selectedColor = argb },
                            label = { Text(label, fontSize = 10.5.sp) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = noteText.isNotBlank(),
                onClick = { onAdd(noteText.trim(), yFraction, fontSize, selectedColor) }
            ) {
                Text("Add to Page")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun AddPdfTextPageDialog(
    onDismiss: () -> Unit,
    onAddPage: (title: String, body: String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add New Text Page to PDF", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Page Heading (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text("Page Content") },
                    minLines = 5,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                enabled = title.isNotBlank() || body.isNotBlank(),
                onClick = { onAddPage(title.trim(), body) }
            ) {
                Text("Insert Page")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
