package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.filemanager.CustomPayloadCipherMode
import com.example.data.filemanager.DragonPcapEngine
import com.example.data.filemanager.PcapCaptureDocument
import com.example.data.filemanager.PcapDissection
import com.example.data.filemanager.PcapFileFormat
import com.example.data.filemanager.PcapPacketRecord
import com.example.data.downloader.ApkPackageInstaller
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

private enum class PcapStudioTab(val label: String) {
    PACKETS("Packets"),
    INSPECT_EDIT("Inspect & Edit"),
    DECRYPT("Decrypt"),
    SUMMARY("Summary")
}

private enum class PcapDecryptMode(val label: String) {
    TLS_KEYLOG("TLS / SSL"),
    WIFI_80211("Wi-Fi 802.11"),
    CUSTOM_PAYLOAD("AES / XOR")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DragonPcapStudioDialog(
    context: Context,
    item: FileItemEntry,
    onDismiss: () -> Unit,
    onSaved: (File) -> Unit,
    onOpenRawHex: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val clipboard = remember(context) {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }

    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var document by remember { mutableStateOf<PcapCaptureDocument?>(null) }
    var hasUnsavedChanges by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }

    var activeTab by remember { mutableStateOf(PcapStudioTab.PACKETS) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedProtoFilter by remember { mutableStateOf("ALL") }
    var selectedPacketIndex by remember { mutableStateOf<Int?>(null) }
    var activeStreamKey by remember { mutableStateOf<String?>(null) }
    var showSaveAsDialog by remember { mutableStateOf(false) }
    var showAddPacketDialog by remember { mutableStateOf(false) }

    // Decryption state
    var decryptMode by remember { mutableStateOf(PcapDecryptMode.TLS_KEYLOG) }
    var tlsKeyLogInput by remember { mutableStateOf("") }
    var wifiSsidInput by remember { mutableStateOf("") }
    var wifiPassphraseInput by remember { mutableStateOf("") }
    var wifiUseWep by remember { mutableStateOf(false) }
    var customCipherMode by remember { mutableStateOf(CustomPayloadCipherMode.AES_GCM) }
    var customKeyInput by remember { mutableStateOf("") }
    var customIvInput by remember { mutableStateOf("") }
    var customKeyIsHex by remember { mutableStateOf(true) }
    var customOnlySelectedPacket by remember { mutableStateOf(false) }
    var decryptStatusMessage by remember { mutableStateOf<String?>(null) }
    var decryptStatusSuccess by remember { mutableStateOf(false) }
    var isDecrypting by remember { mutableStateOf(false) }

    val keyLogPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val text = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    }.getOrNull()
                }
                if (!text.isNullOrBlank()) {
                    tlsKeyLogInput = text
                    ApkPackageInstaller.showToastOnMain(context, "Loaded SSL key log file")
                } else {
                    ApkPackageInstaller.showToastOnMain(context, "Could not read key log file")
                }
            }
        }
    }

    LaunchedEffect(item.file.absolutePath) {
        isLoading = true
        loadError = null
        val result = withContext(Dispatchers.IO) {
            runCatching { DragonPcapEngine.readCaptureFile(context, item.file) }
        }
        result.onSuccess { doc ->
            document = doc
            selectedPacketIndex = doc.packets.firstOrNull()?.index
            if (doc.embeddedKeyLogText.isNotBlank()) {
                tlsKeyLogInput = doc.embeddedKeyLogText
            }
            val detectedSsids = withContext(Dispatchers.Default) { DragonPcapEngine.detectWifiSsids(doc) }
            if (detectedSsids.isNotEmpty()) {
                wifiSsidInput = detectedSsids.first()
                if (doc.defaultLinkType == DragonPcapEngine.DLT_IEEE802_11 || doc.defaultLinkType == DragonPcapEngine.DLT_IEEE802_11_RADIO) {
                    decryptMode = PcapDecryptMode.WIFI_80211
                }
            }
        }.onFailure { err ->
            loadError = err.message ?: "Failed to parse packet capture"
        }
        isLoading = false
    }

    // Pre-dissect packets whenever document changes
    val dissectedEntries by produceState<List<Pair<PcapPacketRecord, PcapDissection>>>(
        initialValue = emptyList(),
        key1 = document
    ) {
        val doc = document
        if (doc == null) {
            value = emptyList()
        } else {
            value = withContext(Dispatchers.Default) {
                doc.packets.map { pkt -> pkt to DragonPcapEngine.dissectPacket(pkt) }
            }
        }
    }

    val filteredEntries = remember(dissectedEntries, searchQuery, selectedProtoFilter) {
        val q = searchQuery.trim().lowercase(Locale.US)
        dissectedEntries.filter { (pkt, d) ->
            val protoOk = when (selectedProtoFilter) {
                "ALL" -> true
                "DECRYPTED" -> pkt.decryptedBytes != null
                "ENCRYPTED" -> d.isEncrypted
                else -> d.protocol.uppercase(Locale.US).contains(selectedProtoFilter)
            }
            if (!protoOk) return@filter false
            if (q.isEmpty()) return@filter true
            d.protocol.lowercase(Locale.US).contains(q) ||
                d.srcAddress.lowercase(Locale.US).contains(q) ||
                d.dstAddress.lowercase(Locale.US).contains(q) ||
                d.info.lowercase(Locale.US).contains(q) ||
                pkt.comment.lowercase(Locale.US).contains(q) ||
                (pkt.decryptedTextPreview?.lowercase(Locale.US)?.contains(q) == true) ||
                (d.srcPort?.toString() == q) ||
                (d.dstPort?.toString() == q) ||
                ("#${pkt.index}" == q || pkt.index.toString() == q)
        }
    }

    val selectedEntry = remember(dissectedEntries, selectedPacketIndex) {
        dissectedEntries.firstOrNull { it.first.index == selectedPacketIndex }
            ?: dissectedEntries.firstOrNull()
    }

    fun updatePackets(newList: List<PcapPacketRecord>, keepIndex: Int? = selectedPacketIndex) {
        val doc = document ?: return
        val reindexed = newList.mapIndexed { idx, pkt -> pkt.copy(index = idx + 1) }
        document = doc.copy(packets = reindexed)
        selectedPacketIndex = keepIndex?.coerceIn(1, reindexed.size.coerceAtLeast(1))
            ?: reindexed.firstOrNull()?.index
        hasUnsavedChanges = true
    }

    fun saveCaptureToFile(targetFile: File, saveAsNg: Boolean, createBackup: Boolean, docToWrite: PcapCaptureDocument? = document) {
        val doc = docToWrite ?: return
        scope.launch {
            isSaving = true
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    DragonPcapEngine.writeCaptureFile(
                        context = context,
                        targetFile = targetFile,
                        document = doc,
                        saveAsPcapNg = saveAsNg,
                        createBackup = createBackup
                    )
                }.getOrDefault(false)
            }
            isSaving = false
            if (ok) {
                if (targetFile.absolutePath == item.file.absolutePath) {
                    hasUnsavedChanges = false
                }
                ApkPackageInstaller.showToastOnMain(context, "Saved ${targetFile.name}")
                onSaved(targetFile)
            } else {
                ApkPackageInstaller.showToastOnMain(context, "Failed to save ${targetFile.name}")
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .testTag("dragon_pcap_studio_dialog"),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
            ) {
                // Top Studio Header
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 3.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier.testTag("pcap_studio_close_btn")
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close Packet Studio")
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = item.name + if (hasUnsavedChanges) " *" else "",
                                        fontWeight = FontWeight.Black,
                                        fontSize = 14.5.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    document?.let { doc ->
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = NeonCyan.copy(alpha = 0.18f)
                                        ) {
                                            Text(
                                                text = doc.format.badgeLabel,
                                                fontSize = 9.sp,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = NeonCyan,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = document?.let {
                                        "${it.packets.size} packets • ${DragonPcapEngine.linkTypeName(it.defaultLinkType)} • ${formatBytes(it.fileSizeBytes)}"
                                    } ?: "Packet Capture Analyzer & Editor",
                                    fontSize = 10.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(
                                onClick = { showAddPacketDialog = true },
                                enabled = document != null,
                                modifier = Modifier.testTag("pcap_add_packet_btn")
                            ) {
                                Icon(Icons.Default.AddCircleOutline, contentDescription = "Add Packet", tint = NeonCyan)
                            }
                            IconButton(
                                onClick = { showSaveAsDialog = true },
                                enabled = document != null && !isSaving,
                                modifier = Modifier.testTag("pcap_save_btn")
                            ) {
                                Icon(
                                    Icons.Default.Save,
                                    contentDescription = "Save Capture",
                                    tint = if (hasUnsavedChanges) SuccessGreen else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        // 4 Primary Tabs
                        ScrollableTabRow(
                            selectedTabIndex = PcapStudioTab.entries.indexOf(activeTab),
                            edgePadding = 8.dp,
                            containerColor = Color.Transparent,
                            contentColor = NeonCyan,
                            divider = { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)) }
                        ) {
                            PcapStudioTab.entries.forEach { tab ->
                                Tab(
                                    selected = activeTab == tab,
                                    onClick = { activeTab = tab },
                                    modifier = Modifier.testTag("pcap_tab_${tab.name.lowercase(Locale.US)}"),
                                    text = {
                                        Text(
                                            text = tab.label,
                                            fontSize = 12.sp,
                                            fontWeight = if (activeTab == tab) FontWeight.ExtraBold else FontWeight.Medium
                                        )
                                    }
                                )
                            }
                        }
                    }
                }

                // Body
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        isLoading -> {
                            Column(
                                modifier = Modifier.align(Alignment.Center),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                CircularProgressIndicator(color = NeonCyan)
                                Text("Parsing capture frames...", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        loadError != null -> {
                            Column(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = DangerRed, modifier = Modifier.size(40.dp))
                                Text("Could not open capture file", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Text(loadError.orEmpty(), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                OutlinedButton(onClick = onOpenRawHex) {
                                    Icon(Icons.Default.DataObject, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Open in Raw Hex Editor")
                                }
                            }
                        }
                        document != null -> {
                            val doc = document!!
                            when (activeTab) {
                                PcapStudioTab.PACKETS -> {
                                    PcapPacketsListPane(
                                        document = doc,
                                        entries = filteredEntries,
                                        searchQuery = searchQuery,
                                        onSearchQueryChange = { searchQuery = it },
                                        selectedProtoFilter = selectedProtoFilter,
                                        onSelectProtoFilter = { selectedProtoFilter = it },
                                        selectedPacketIndex = selectedPacketIndex,
                                        onSelectPacket = { idx ->
                                            selectedPacketIndex = idx
                                            activeTab = PcapStudioTab.INSPECT_EDIT
                                        }
                                    )
                                }
                                PcapStudioTab.INSPECT_EDIT -> {
                                    PcapInspectAndEditPane(
                                        entry = selectedEntry,
                                        totalPackets = doc.packets.size,
                                        onNavigatePacket = { targetIdx ->
                                            selectedPacketIndex = targetIdx.coerceIn(1, doc.packets.size.coerceAtLeast(1))
                                        },
                                        onApplyEditedPacket = { edited ->
                                            val updated = doc.packets.map { if (it.index == edited.index) edited else it }
                                            updatePackets(updated, keepIndex = edited.index)
                                            ApkPackageInstaller.showToastOnMain(context, "Updated packet #${edited.index}")
                                        },
                                        onDuplicatePacket = { pkt ->
                                            val list = doc.packets.toMutableList()
                                            val insertAt = list.indexOfFirst { it.index == pkt.index }.coerceAtLeast(0) + 1
                                            list.add(insertAt, pkt.copy(rawData = pkt.rawData.copyOf()))
                                            updatePackets(list, keepIndex = insertAt + 1)
                                            ApkPackageInstaller.showToastOnMain(context, "Duplicated packet #${pkt.index}")
                                        },
                                        onDeletePacket = { pkt ->
                                            val remaining = doc.packets.filterNot { it.index == pkt.index }
                                            updatePackets(remaining, keepIndex = pkt.index.coerceAtMost(remaining.size.coerceAtLeast(1)))
                                            ApkPackageInstaller.showToastOnMain(context, "Deleted packet #${pkt.index}")
                                        },
                                        onFollowStream = { streamKey ->
                                            activeStreamKey = streamKey
                                            activeTab = PcapStudioTab.SUMMARY
                                        },
                                        onCopyText = { label, value ->
                                            clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
                                            ApkPackageInstaller.showToastOnMain(context, "Copied $label")
                                        }
                                    )
                                }
                                PcapStudioTab.DECRYPT -> {
                                    PcapDecryptPane(
                                        document = doc,
                                        selectedPacketIndex = selectedPacketIndex,
                                        decryptMode = decryptMode,
                                        onSelectDecryptMode = { decryptMode = it },
                                        tlsKeyLogInput = tlsKeyLogInput,
                                        onTlsKeyLogChange = { tlsKeyLogInput = it },
                                        onPickKeyLogFile = { keyLogPicker.launch(arrayOf("text/*", "*/*")) },
                                        wifiSsidInput = wifiSsidInput,
                                        onWifiSsidChange = { wifiSsidInput = it },
                                        wifiPassphraseInput = wifiPassphraseInput,
                                        onWifiPassphraseChange = { wifiPassphraseInput = it },
                                        wifiUseWep = wifiUseWep,
                                        onWifiUseWepChange = { wifiUseWep = it },
                                        customCipherMode = customCipherMode,
                                        onCustomCipherModeChange = { customCipherMode = it },
                                        customKeyInput = customKeyInput,
                                        onCustomKeyChange = { customKeyInput = it },
                                        customIvInput = customIvInput,
                                        onCustomIvChange = { customIvInput = it },
                                        customKeyIsHex = customKeyIsHex,
                                        onCustomKeyIsHexChange = { customKeyIsHex = it },
                                        customOnlySelectedPacket = customOnlySelectedPacket,
                                        onCustomOnlySelectedPacketChange = { customOnlySelectedPacket = it },
                                        isDecrypting = isDecrypting,
                                        statusMessage = decryptStatusMessage,
                                        statusSuccess = decryptStatusSuccess,
                                        onRunDecrypt = {
                                            scope.launch {
                                                isDecrypting = true
                                                val outcome = withContext(Dispatchers.Default) {
                                                    when (decryptMode) {
                                                        PcapDecryptMode.TLS_KEYLOG ->
                                                            DragonPcapEngine.decryptWithTlsKeyLog(doc, tlsKeyLogInput)
                                                        PcapDecryptMode.WIFI_80211 ->
                                                            if (wifiUseWep) {
                                                                DragonPcapEngine.decryptWifiWepWithKnownKey(doc, wifiPassphraseInput)
                                                            } else {
                                                                DragonPcapEngine.decryptWifiWpa2WithKnownPassphrase(doc, wifiSsidInput, wifiPassphraseInput)
                                                            }
                                                        PcapDecryptMode.CUSTOM_PAYLOAD ->
                                                            DragonPcapEngine.decryptCustomPayloads(
                                                                document = doc,
                                                                mode = customCipherMode,
                                                                keyText = customKeyInput,
                                                                ivText = customIvInput,
                                                                keyIsHex = customKeyIsHex,
                                                                targetPacketIndex = if (customOnlySelectedPacket) selectedPacketIndex else null
                                                            )
                                                    }
                                                }
                                                document = outcome.updatedDocument
                                                decryptStatusMessage = outcome.summaryMessage
                                                decryptStatusSuccess = outcome.decryptedCount > 0
                                                if (outcome.decryptedCount > 0) {
                                                    hasUnsavedChanges = true
                                                    selectedProtoFilter = "DECRYPTED"
                                                }
                                                isDecrypting = false
                                            }
                                        },
                                        onSaveDecryptedCopy = {
                                            val decDoc = DragonPcapEngine.buildDecryptedExportDocument(doc)
                                            val base = item.name.substringBeforeLast('.')
                                            val outFile = File(item.file.parentFile, "${base}_decrypted.pcapng")
                                            saveCaptureToFile(outFile, saveAsNg = true, createBackup = false, docToWrite = decDoc)
                                        },
                                        onViewDecryptedPackets = {
                                            selectedProtoFilter = "DECRYPTED"
                                            activeTab = PcapStudioTab.PACKETS
                                        }
                                    )
                                }
                                PcapStudioTab.SUMMARY -> {
                                    PcapSummaryAndStreamsPane(
                                        document = doc,
                                        entries = dissectedEntries,
                                        activeStreamKey = activeStreamKey,
                                        onSelectStreamKey = { activeStreamKey = it },
                                        onOpenRawHex = onOpenRawHex,
                                        onCopyText = { label, value ->
                                            clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
                                            ApkPackageInstaller.showToastOnMain(context, "Copied $label")
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

    if (showSaveAsDialog && document != null) {
        val doc = document!!
        var saveFilename by remember(item.name) { mutableStateOf(item.name) }
        var saveAsPcapNg by remember(doc.format, item.extension) {
            mutableStateOf(item.extension.equals("pcapng", ignoreCase = true) || doc.format == PcapFileFormat.PCAPNG)
        }
        var createBackup by remember { mutableStateOf(true) }

        AlertDialog(
            onDismissRequest = { showSaveAsDialog = false },
            title = { Text("Save Packet Capture", fontWeight = FontWeight.Black, fontSize = 16.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = saveFilename,
                        onValueChange = { saveFilename = it },
                        label = { Text("Output Filename (.cap / .pcap / .pcapng)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("pcap_save_filename_input")
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !saveAsPcapNg,
                            onClick = {
                                saveAsPcapNg = false
                                if (saveFilename.endsWith(".pcapng", ignoreCase = true)) {
                                    saveFilename = saveFilename.dropLast(7) + ".pcap"
                                }
                            },
                            label = { Text("Classic PCAP / CAP") }
                        )
                        FilterChip(
                            selected = saveAsPcapNg,
                            onClick = {
                                saveAsPcapNg = true
                                if (saveFilename.endsWith(".pcap", ignoreCase = true)) {
                                    saveFilename = saveFilename.dropLast(5) + ".pcapng"
                                } else if (saveFilename.endsWith(".cap", ignoreCase = true)) {
                                    saveFilename = saveFilename.dropLast(4) + ".pcapng"
                                }
                            },
                            label = { Text("PCAPNG (Comments & Keys)") }
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = createBackup, onCheckedChange = { createBackup = it })
                        Text("Create .bak backup before overwriting", fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val clean = saveFilename.trim().ifBlank { item.name }
                        val target = File(item.file.parentFile, clean)
                        showSaveAsDialog = false
                        saveCaptureToFile(target, saveAsNg = saveAsPcapNg, createBackup = createBackup)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                    modifier = Modifier.testTag("pcap_confirm_save_btn")
                ) {
                    Text("Save", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveAsDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showAddPacketDialog && document != null) {
        val doc = document!!
        var proto by remember { mutableStateOf("TCP") }
        var srcIp by remember { mutableStateOf("192.168.1.100") }
        var dstIp by remember { mutableStateOf("192.168.1.1") }
        var srcPort by remember { mutableStateOf("54321") }
        var dstPort by remember { mutableStateOf("80") }
        var payloadText by remember { mutableStateOf("GET / HTTP/1.1\r\nHost: example.com\r\n\r\n") }
        var comment by remember { mutableStateOf("Added via Dragon Packet Studio") }

        AlertDialog(
            onDismissRequest = { showAddPacketDialog = false },
            title = { Text("Insert New Packet", fontWeight = FontWeight.Black, fontSize = 16.sp) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = proto == "TCP", onClick = { proto = "TCP" }, label = { Text("TCP") })
                        FilterChip(selected = proto == "UDP", onClick = { proto = "UDP" }, label = { Text("UDP") })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = srcIp, onValueChange = { srcIp = it }, label = { Text("Src IP") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = dstIp, onValueChange = { dstIp = it }, label = { Text("Dst IP") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = srcPort, onValueChange = { srcPort = it }, label = { Text("Src Port") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = dstPort, onValueChange = { dstPort = it }, label = { Text("Dst Port") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                    OutlinedTextField(
                        value = payloadText,
                        onValueChange = { payloadText = it },
                        label = { Text("Payload (UTF-8)") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = comment,
                        onValueChange = { comment = it },
                        label = { Text("Packet Comment (PCAPNG)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val crafted = DragonPcapEngine.craftNewIpv4Packet(
                            index = doc.packets.size + 1,
                            protocol = proto,
                            srcIp = srcIp,
                            dstIp = dstIp,
                            srcPort = srcPort.toIntOrNull() ?: 12345,
                            dstPort = dstPort.toIntOrNull() ?: 80,
                            payloadBytes = payloadText.toByteArray(Charsets.UTF_8),
                            comment = comment
                        )
                        updatePackets(doc.packets + crafted, keepIndex = doc.packets.size + 1)
                        showAddPacketDialog = false
                        ApkPackageInstaller.showToastOnMain(context, "Added packet #${doc.packets.size + 1}")
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan)
                ) {
                    Text("Insert", color = Color(0xFF04131B), fontWeight = FontWeight.ExtraBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddPacketDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun PcapPacketsListPane(
    document: PcapCaptureDocument,
    entries: List<Pair<PcapPacketRecord, PcapDissection>>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    selectedProtoFilter: String,
    onSelectProtoFilter: (String) -> Unit,
    selectedPacketIndex: Int?,
    onSelectPacket: (Int) -> Unit
) {
    val firstTsMicros = remember(document) {
        val first = document.packets.firstOrNull()
        if (first != null) first.timestampSeconds * 1_000_000L + first.timestampMicros else 0L
    }
    val filterChips = listOf("ALL", "DECRYPTED", "ENCRYPTED", "HTTP", "TLS", "DNS", "TCP", "UDP", "802.11", "EAPOL", "ARP", "ICMP")

    Column(modifier = Modifier.fillMaxSize()) {
        document.warningMessage?.let { warn ->
            Surface(
                color = WarningOrange.copy(alpha = 0.14f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = warn,
                    fontSize = 11.sp,
                    color = WarningOrange,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            placeholder = { Text("Filter by IP, port, protocol, SNI, comment or text...", fontSize = 12.sp) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchQueryChange("") }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear filter", modifier = Modifier.size(16.dp))
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .testTag("pcap_filter_input")
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            filterChips.forEach { chip ->
                FilterChip(
                    selected = selectedProtoFilter == chip,
                    onClick = { onSelectProtoFilter(chip) },
                    label = { Text(chip, fontSize = 10.5.sp, fontWeight = FontWeight.Bold) }
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        if (entries.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (document.packets.isEmpty()) "No packets in capture file." else "No packets match current filter.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(entries, key = { it.first.index }) { (pkt, d) ->
                    val relSec = ((pkt.timestampSeconds * 1_000_000L + pkt.timestampMicros) - firstTsMicros) / 1_000_000.0
                    val protoColor = protocolBadgeColor(d.protocol, pkt.decryptedBytes != null, d.isEncrypted)
                    val isSelected = pkt.index == selectedPacketIndex

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) NeonCyan.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        border = BorderStroke(
                            1.dp,
                            if (pkt.decryptedBytes != null) SuccessGreen.copy(alpha = 0.55f)
                            else if (isSelected) NeonCyan.copy(alpha = 0.6f)
                            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectPacket(pkt.index) }
                            .testTag("pcap_packet_row_${pkt.index}")
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "#${pkt.index}",
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Surface(
                                    shape = RoundedCornerShape(5.dp),
                                    color = protoColor.copy(alpha = 0.2f)
                                ) {
                                    Text(
                                        text = d.protocol,
                                        fontSize = 9.5.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = protoColor,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                    )
                                }
                                if (pkt.decryptedBytes != null) {
                                    Surface(
                                        shape = RoundedCornerShape(5.dp),
                                        color = SuccessGreen.copy(alpha = 0.2f)
                                    ) {
                                        Text(
                                            text = "DECRYPTED",
                                            fontSize = 8.5.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = SuccessGreen,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                        )
                                    }
                                } else if (d.isEncrypted) {
                                    Icon(Icons.Default.Lock, contentDescription = "Encrypted", tint = WarningOrange, modifier = Modifier.size(13.dp))
                                }
                                Spacer(modifier = Modifier.weight(1f))
                                Text(
                                    text = String.format(Locale.US, "+%.3fs • %dB", relSec.coerceAtLeast(0.0), pkt.rawData.size),
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            val srcText = if (d.srcPort != null) "${d.srcAddress}:${d.srcPort}" else d.srcAddress
                            val dstText = if (d.dstPort != null) "${d.dstAddress}:${d.dstPort}" else d.dstAddress
                            Text(
                                text = "$srcText  →  $dstText",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = d.info,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (pkt.comment.isNotBlank()) {
                                Text(
                                    text = "Note: ${pkt.comment}",
                                    fontSize = 10.sp,
                                    color = NeonCyan,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PcapInspectAndEditPane(
    entry: Pair<PcapPacketRecord, PcapDissection>?,
    totalPackets: Int,
    onNavigatePacket: (Int) -> Unit,
    onApplyEditedPacket: (PcapPacketRecord) -> Unit,
    onDuplicatePacket: (PcapPacketRecord) -> Unit,
    onDeletePacket: (PcapPacketRecord) -> Unit,
    onFollowStream: (String) -> Unit,
    onCopyText: (String, String) -> Unit
) {
    if (entry == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Select a packet from the Packets tab to inspect or edit.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val (pkt, d) = entry

    var srcIpField by remember(pkt.index, pkt.rawData.contentHashCode()) { mutableStateOf(if (d.ipVersion == 4) d.srcAddress else "") }
    var dstIpField by remember(pkt.index, pkt.rawData.contentHashCode()) { mutableStateOf(if (d.ipVersion == 4) d.dstAddress else "") }
    var srcPortField by remember(pkt.index, pkt.rawData.contentHashCode()) { mutableStateOf(d.srcPort?.toString().orEmpty()) }
    var dstPortField by remember(pkt.index, pkt.rawData.contentHashCode()) { mutableStateOf(d.dstPort?.toString().orEmpty()) }
    var tsSecField by remember(pkt.index, pkt.timestampSeconds) { mutableStateOf(pkt.timestampSeconds.toString()) }
    var tsUsecField by remember(pkt.index, pkt.timestampMicros) { mutableStateOf(pkt.timestampMicros.toString()) }
    var commentField by remember(pkt.index, pkt.comment) { mutableStateOf(pkt.comment) }
    var autoFixChecksums by remember { mutableStateOf(true) }

    val initialPayloadText = remember(pkt.index, pkt.rawData.contentHashCode()) {
        if (d.payloadOffset >= 0 && d.payloadLength > 0 && d.payloadOffset + d.payloadLength <= pkt.rawData.size) {
            String(pkt.rawData, d.payloadOffset, d.payloadLength, Charsets.UTF_8)
        } else ""
    }
    var payloadTextField by remember(pkt.index, pkt.rawData.contentHashCode()) { mutableStateOf(initialPayloadText) }
    var rawHexField by remember(pkt.index, pkt.rawData.contentHashCode()) { mutableStateOf(DragonPcapEngine.bytesToSpacedHex(pkt.rawData)) }
    var hexEditError by remember(pkt.index) { mutableStateOf<String?>(null) }
    var showRawHexEditor by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Packet Navigation & Quick Actions
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { onNavigatePacket(pkt.index - 1) }, enabled = pkt.index > 1) {
                    Icon(Icons.Default.ChevronLeft, contentDescription = "Previous Packet")
                }
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Packet #${pkt.index} / $totalPackets", fontWeight = FontWeight.Black, fontSize = 13.sp)
                    Text("${d.protocol} • ${pkt.rawData.size} bytes", fontSize = 10.5.sp, color = NeonCyan)
                }
                IconButton(onClick = { onNavigatePacket(pkt.index + 1) }, enabled = pkt.index < totalPackets) {
                    Icon(Icons.Default.ChevronRight, contentDescription = "Next Packet")
                }
                IconButton(onClick = { onDuplicatePacket(pkt) }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Duplicate Packet", tint = NeonCyan, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = { onDeletePacket(pkt) }) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = "Delete Packet", tint = DangerRed, modifier = Modifier.size(18.dp))
                }
            }
        }

        if (d.streamKey != null) {
            OutlinedButton(
                onClick = { onFollowStream(d.streamKey) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Stream, contentDescription = null, modifier = Modifier.size(16.dp), tint = NeonCyan)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Follow ${d.protocol} Stream Conversation", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Decrypted Plaintext Highlight Box (if decrypted)
        if (!pkt.decryptedTextPreview.isNullOrBlank() || pkt.decryptedBytes != null) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = SuccessGreen.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, SuccessGreen.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.LockOpen, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Decrypted Payload (${pkt.decryptionMethod ?: "Authorized Key"})",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 12.sp,
                            color = SuccessGreen,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { onCopyText("Decrypted Payload", pkt.decryptedTextPreview.orEmpty()) }) {
                            Text("Copy", fontSize = 11.sp, color = SuccessGreen)
                        }
                    }
                    Text(
                        text = pkt.decryptedTextPreview.orEmpty().ifBlank {
                            pkt.decryptedBytes?.let { DragonPcapEngine.formatHexDump(it, 512) }.orEmpty()
                        },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
            }
        }

        // Protocol Layer Dissection Tree
        Text("Decoded Protocol Layers", fontWeight = FontWeight.Black, fontSize = 13.sp, color = NeonCyan)
        d.layers.forEach { layer ->
            var expanded by remember(pkt.index, layer.title) { mutableStateOf(true) }
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expanded = !expanded }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = NeonCyan,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(layer.title, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text(layer.summary, fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    AnimatedVisibility(visible = expanded) {
                        Column(
                            modifier = Modifier.padding(start = 28.dp, end = 10.dp, bottom = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            layer.fields.forEach { field ->
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = "${field.name}: ",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = field.value,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Structured Packet Editor
        Text("Edit Packet Headers, Comment & Payload", fontWeight = FontWeight.Black, fontSize = 13.sp, color = SuccessGreen)
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            border = BorderStroke(1.dp, SuccessGreen.copy(alpha = 0.35f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (d.ipVersion == 4) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = srcIpField,
                            onValueChange = { srcIpField = it },
                            label = { Text("Source IPv4") },
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("pcap_edit_src_ip")
                        )
                        OutlinedTextField(
                            value = dstIpField,
                            onValueChange = { dstIpField = it },
                            label = { Text("Dest IPv4") },
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("pcap_edit_dst_ip")
                        )
                    }
                }
                if (d.ipProtocol == 6 || d.ipProtocol == 17) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = srcPortField,
                            onValueChange = { srcPortField = it },
                            label = { Text("Src Port") },
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("pcap_edit_src_port")
                        )
                        OutlinedTextField(
                            value = dstPortField,
                            onValueChange = { dstPortField = it },
                            label = { Text("Dst Port") },
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("pcap_edit_dst_port")
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = tsSecField,
                        onValueChange = { tsSecField = it },
                        label = { Text("Timestamp (sec)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = tsUsecField,
                        onValueChange = { tsUsecField = it },
                        label = { Text("Micros (µs)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedTextField(
                    value = commentField,
                    onValueChange = { commentField = it },
                    label = { Text("Packet Comment (stored in PCAPNG)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("pcap_edit_comment")
                )
                OutlinedTextField(
                    value = payloadTextField,
                    onValueChange = { payloadTextField = it },
                    label = { Text("Application Payload (UTF-8 Text)") },
                    minLines = 3,
                    maxLines = 6,
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                    modifier = Modifier.fillMaxWidth().testTag("pcap_edit_payload")
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = autoFixChecksums, onCheckedChange = { autoFixChecksums = it })
                    Text("Auto-recalculate IPv4 / TCP / UDP lengths & checksums", fontSize = 11.5.sp)
                }
                Button(
                    onClick = {
                        var updated = DragonPcapEngine.editPacketNetworkFields(
                            packet = pkt,
                            newSrcIp = srcIpField.takeIf { it.isNotBlank() },
                            newDstIp = dstIpField.takeIf { it.isNotBlank() },
                            newSrcPort = srcPortField.toIntOrNull(),
                            newDstPort = dstPortField.toIntOrNull(),
                            newTimestampSeconds = tsSecField.toLongOrNull() ?: pkt.timestampSeconds,
                            newTimestampMicros = tsUsecField.toLongOrNull() ?: pkt.timestampMicros,
                            newComment = commentField,
                            recalculateChecksums = autoFixChecksums
                        )
                        if (payloadTextField != initialPayloadText) {
                            updated = DragonPcapEngine.replacePacketPayload(
                                packet = updated,
                                newPayload = payloadTextField.toByteArray(Charsets.UTF_8),
                                recalculateChecksums = autoFixChecksums
                            )
                        }
                        onApplyEditedPacket(updated)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().testTag("pcap_apply_header_payload_btn")
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Apply Changes to Packet #${pkt.index}", color = Color.White, fontWeight = FontWeight.ExtraBold)
                }
            }
        }

        // Raw Hex Dump & Direct Hex Patcher
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Raw Frame Hex & ASCII", fontWeight = FontWeight.Black, fontSize = 13.sp, color = WarningOrange, modifier = Modifier.weight(1f))
            TextButton(onClick = { showRawHexEditor = !showRawHexEditor }) {
                Text(if (showRawHexEditor) "View Hex Dump" else "Edit Raw Hex", fontSize = 11.5.sp, color = NeonCyan)
            }
            TextButton(onClick = { onCopyText("Packet #${pkt.index} Hex", DragonPcapEngine.bytesToSpacedHex(pkt.rawData)) }) {
                Text("Copy Hex", fontSize = 11.5.sp)
            }
        }

        if (showRawHexEditor) {
            OutlinedTextField(
                value = rawHexField,
                onValueChange = {
                    rawHexField = it
                    hexEditError = null
                },
                label = { Text("Editable Frame Hex Bytes (e.g. 00 11 22 ...)") },
                minLines = 4,
                maxLines = 10,
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                modifier = Modifier.fillMaxWidth().testTag("pcap_edit_raw_hex")
            )
            hexEditError?.let { err ->
                Text(err, color = DangerRed, fontSize = 11.sp)
            }
            Button(
                onClick = {
                    runCatching { DragonPcapEngine.hexToBytes(rawHexField) }
                        .onSuccess { newBytes ->
                            val patched = pkt.copy(rawData = newBytes, originalLength = newBytes.size)
                            if (autoFixChecksums) {
                                val dis = DragonPcapEngine.dissectPacket(patched)
                                if (dis.ipVersion == 4 && dis.ipHeaderOffset >= 0) {
                                    DragonPcapEngine.updateIpv4AndTransportLengthsAndChecksums(newBytes, dis.ipHeaderOffset)
                                }
                            }
                            onApplyEditedPacket(patched)
                        }
                        .onFailure { e -> hexEditError = e.message ?: "Invalid hex input" }
                },
                colors = ButtonDefaults.buttonColors(containerColor = WarningOrange),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().testTag("pcap_apply_raw_hex_btn")
            ) {
                Text("Apply Raw Hex Bytes", color = Color(0xFF1A0F00), fontWeight = FontWeight.ExtraBold)
            }
        } else {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFF0A0F1A),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = DragonPcapEngine.formatHexDump(pkt.rawData),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = Color(0xFF93C5FD),
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(10.dp)
                )
            }
        }
    }
}

@Composable
private fun PcapDecryptPane(
    document: PcapCaptureDocument,
    selectedPacketIndex: Int?,
    decryptMode: PcapDecryptMode,
    onSelectDecryptMode: (PcapDecryptMode) -> Unit,
    tlsKeyLogInput: String,
    onTlsKeyLogChange: (String) -> Unit,
    onPickKeyLogFile: () -> Unit,
    wifiSsidInput: String,
    onWifiSsidChange: (String) -> Unit,
    wifiPassphraseInput: String,
    onWifiPassphraseChange: (String) -> Unit,
    wifiUseWep: Boolean,
    onWifiUseWepChange: (Boolean) -> Unit,
    customCipherMode: CustomPayloadCipherMode,
    onCustomCipherModeChange: (CustomPayloadCipherMode) -> Unit,
    customKeyInput: String,
    onCustomKeyChange: (String) -> Unit,
    customIvInput: String,
    onCustomIvChange: (String) -> Unit,
    customKeyIsHex: Boolean,
    onCustomKeyIsHexChange: (Boolean) -> Unit,
    customOnlySelectedPacket: Boolean,
    onCustomOnlySelectedPacketChange: (Boolean) -> Unit,
    isDecrypting: Boolean,
    statusMessage: String?,
    statusSuccess: Boolean,
    onRunDecrypt: () -> Unit,
    onSaveDecryptedCopy: () -> Unit,
    onViewDecryptedPackets: () -> Unit
) {
    val detectedSsids = remember(document) { DragonPcapEngine.detectWifiSsids(document) }
    val eapolCount = remember(document) { DragonPcapEngine.countEapolHandshakes(document) }
    val decryptedTotal = remember(document) { document.packets.count { it.decryptedBytes != null } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Authorized Known-Key Decryption", fontWeight = FontWeight.Black, fontSize = 14.sp, color = NeonCyan)
        Text(
            text = "Supply your TLS SSLKEYLOGFILE, known Wi-Fi passphrase/key, or custom AES/XOR key to decrypt captured traffic for inspection.",
            fontSize = 11.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            PcapDecryptMode.entries.forEach { mode ->
                FilterChip(
                    selected = decryptMode == mode,
                    onClick = { onSelectDecryptMode(mode) },
                    modifier = Modifier.weight(1f).testTag("pcap_decrypt_mode_${mode.name.lowercase(Locale.US)}"),
                    label = {
                        Text(mode.label, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                )
            }
        }

        when (decryptMode) {
            PcapDecryptMode.TLS_KEYLOG -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("SSLKEYLOGFILE Secrets", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    OutlinedButton(onClick = onPickKeyLogFile, shape = RoundedCornerShape(8.dp)) {
                        Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Load .log File", fontSize = 11.sp)
                    }
                }
                OutlinedTextField(
                    value = tlsKeyLogInput,
                    onValueChange = onTlsKeyLogChange,
                    label = { Text("CLIENT_RANDOM / CLIENT_TRAFFIC_SECRET_0 / SERVER_TRAFFIC_SECRET_0") },
                    placeholder = { Text("CLIENT_RANDOM <64-hex> <96-hex>\nCLIENT_TRAFFIC_SECRET_0 <64-hex> <64-hex>", fontSize = 10.5.sp) },
                    minLines = 5,
                    maxLines = 8,
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 10.5.sp),
                    modifier = Modifier.fillMaxWidth().testTag("pcap_tls_keylog_input")
                )
            }
            PcapDecryptMode.WIFI_80211 -> {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("802.11 Capture Status", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = NeonCyan)
                        Text(
                            text = "Detected SSIDs: ${detectedSsids.ifEmpty { listOf("None in beacons") }.joinToString(", ")}",
                            fontSize = 11.sp
                        )
                        Text(
                            text = "EAPOL 4-Way Handshake Frames: $eapolCount",
                            fontSize = 11.sp,
                            color = if (eapolCount >= 2) SuccessGreen else WarningOrange
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !wifiUseWep,
                        onClick = { onWifiUseWepChange(false) },
                        label = { Text("WPA2-PSK (CCMP)") }
                    )
                    FilterChip(
                        selected = wifiUseWep,
                        onClick = { onWifiUseWepChange(true) },
                        label = { Text("WEP (RC4)") }
                    )
                }

                if (!wifiUseWep) {
                    OutlinedTextField(
                        value = wifiSsidInput,
                        onValueChange = onWifiSsidChange,
                        label = { Text("Network SSID") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("pcap_wifi_ssid_input")
                    )
                }
                OutlinedTextField(
                    value = wifiPassphraseInput,
                    onValueChange = onWifiPassphraseChange,
                    label = { Text(if (wifiUseWep) "Known WEP Key (Hex or ASCII)" else "Known Wi-Fi Passphrase (or 64-hex PMK)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("pcap_wifi_passphrase_input")
                )
            }
            PcapDecryptMode.CUSTOM_PAYLOAD -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CustomPayloadCipherMode.entries.forEach { m ->
                        FilterChip(
                            selected = customCipherMode == m,
                            onClick = { onCustomCipherModeChange(m) },
                            label = { Text(m.label, fontSize = 11.sp) }
                        )
                    }
                }
                OutlinedTextField(
                    value = customKeyInput,
                    onValueChange = onCustomKeyChange,
                    label = { Text(if (customKeyIsHex) "Key (Hex bytes)" else "Key (UTF-8 text)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("pcap_custom_key_input")
                )
                if (customCipherMode != CustomPayloadCipherMode.XOR_KEY) {
                    OutlinedTextField(
                        value = customIvInput,
                        onValueChange = onCustomIvChange,
                        label = { Text("IV / Nonce (optional — auto-reads prefix if blank)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("pcap_custom_iv_input")
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = customKeyIsHex, onCheckedChange = onCustomKeyIsHexChange)
                    Text("Key/IV entered as Hex", fontSize = 12.sp)
                }
                if (selectedPacketIndex != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = customOnlySelectedPacket, onCheckedChange = onCustomOnlySelectedPacketChange)
                        Text("Only decrypt selected packet (#$selectedPacketIndex)", fontSize = 12.sp)
                    }
                }
            }
        }

        Button(
            onClick = onRunDecrypt,
            enabled = !isDecrypting,
            colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("pcap_run_decrypt_btn")
        ) {
            if (isDecrypting) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color(0xFF04131B))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Decrypting...", color = Color(0xFF04131B), fontWeight = FontWeight.ExtraBold)
            } else {
                Icon(Icons.Default.LockOpen, contentDescription = null, tint = Color(0xFF04131B), modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Decrypt Capture Traffic", color = Color(0xFF04131B), fontWeight = FontWeight.ExtraBold)
            }
        }

        statusMessage?.let { msg ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = (if (statusSuccess) SuccessGreen else WarningOrange).copy(alpha = 0.14f),
                border = BorderStroke(1.dp, (if (statusSuccess) SuccessGreen else WarningOrange).copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = msg,
                    fontSize = 12.sp,
                    color = if (statusSuccess) SuccessGreen else WarningOrange,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }

        if (decryptedTotal > 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onViewDecryptedPackets,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("View $decryptedTotal Decrypted", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = onSaveDecryptedCopy,
                    colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                    modifier = Modifier.weight(1f).testTag("pcap_save_decrypted_copy_btn"),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Save Decrypted Copy", color = Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun PcapSummaryAndStreamsPane(
    document: PcapCaptureDocument,
    entries: List<Pair<PcapPacketRecord, PcapDissection>>,
    activeStreamKey: String?,
    onSelectStreamKey: (String?) -> Unit,
    onOpenRawHex: () -> Unit,
    onCopyText: (String, String) -> Unit
) {
    val protoCounts = remember(entries) {
        entries.groupingBy { it.second.protocol }.eachCount().entries.sortedByDescending { it.value }
    }
    val streams = remember(entries) {
        entries.mapNotNull { it.second.streamKey }.distinct()
    }
    val activeStreamText = remember(document, activeStreamKey) {
        activeStreamKey?.let { DragonPcapEngine.followStreamText(document, it) }.orEmpty()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Capture Overview", fontWeight = FontWeight.Black, fontSize = 13.5.sp, color = NeonCyan)
                Text("Format: ${document.format.badgeLabel} (v${document.versionMajor}.${document.versionMinor})", fontSize = 12.sp)
                Text("Packets: ${document.packets.size} • File Size: ${formatBytes(document.fileSizeBytes)}", fontSize = 12.sp)
                Text("Default Link Type: ${DragonPcapEngine.linkTypeName(document.defaultLinkType)} (${document.defaultLinkType})", fontSize = 12.sp)
                Text("Snap Length: ${document.snapLen} bytes • Interfaces: ${document.interfaces.size}", fontSize = 12.sp)
                if (document.sectionComment.isNotBlank()) {
                    Text("Section Comment: ${document.sectionComment}", fontSize = 11.5.sp, color = NeonCyan)
                }
                if (document.embeddedKeyLogText.isNotBlank()) {
                    Text("Embedded DSB Decryption Secrets: Present (${document.embeddedKeyLogText.lines().size} lines)", fontSize = 11.5.sp, color = SuccessGreen)
                }
            }
        }

        Text("Protocol Distribution", fontWeight = FontWeight.Black, fontSize = 13.sp, color = NeonCyan)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            protoCounts.forEach { (proto, count) ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = protocolBadgeColor(proto, false, false).copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, protocolBadgeColor(proto, false, false).copy(alpha = 0.4f))
                ) {
                    Text(
                        text = "$proto: $count",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = protocolBadgeColor(proto, false, false),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                    )
                }
            }
        }

        Text("Follow TCP / UDP Streams (${streams.size})", fontWeight = FontWeight.Black, fontSize = 13.sp, color = SuccessGreen)
        if (streams.isEmpty()) {
            Text("No TCP/UDP streams found in this capture.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            streams.take(25).forEach { key ->
                val selected = key == activeStreamKey
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (selected) NeonCyan.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectStreamKey(if (selected) null else key) }
                ) {
                    Text(
                        text = key,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }
            }
        }

        if (activeStreamKey != null) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFF0A0F1A),
                border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Stream: $activeStreamKey",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = NeonCyan,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        TextButton(onClick = { onCopyText("Stream $activeStreamKey", activeStreamText) }) {
                            Text("Copy", fontSize = 11.sp, color = SuccessGreen)
                        }
                    }
                    Text(
                        text = activeStreamText.ifBlank { "(No printable payload in this stream)" },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.5.sp,
                        color = Color(0xFFE2E8F0)
                    )
                }
            }
        }

        OutlinedButton(
            onClick = onOpenRawHex,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp)
        ) {
            Icon(Icons.Default.DataObject, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Open Entire File in Raw Hex Studio")
        }
    }
}

private fun protocolBadgeColor(protocol: String, isDecrypted: Boolean, isEncrypted: Boolean): Color {
    if (isDecrypted) return SuccessGreen
    val upper = protocol.uppercase(Locale.US)
    return when {
        upper.contains("HTTP") -> SuccessGreen
        upper.contains("TLS") || upper.contains("SSL") || upper.contains("SSH") -> Color(0xFFA855F7)
        upper.contains("DNS") -> Color(0xFF38BDF8)
        upper.contains("802.11") || upper.contains("EAPOL") -> WarningOrange
        upper.contains("ARP") || upper.contains("ICMP") -> Color(0xFFF472B6)
        upper.contains("UDP") -> Color(0xFF60A5FA)
        isEncrypted -> WarningOrange
        else -> NeonCyan
    }
}
