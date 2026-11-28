package com.example.ui.screens

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.downloader.ApkPackageInstaller
import com.example.data.filemanager.DragonPrivateVaultManager
import com.example.data.filemanager.DragonVaultEntry
import com.example.ui.haptics.LocalDragonHaptics
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

private val VaultPurple = Color(0xFFA855F7)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DragonPrivateVaultDialog(
    context: Context,
    currentDir: File,
    currentDirItems: List<FileItemEntry>,
    pendingItemsToVault: List<FileItemEntry> = emptyList(),
    onClearPendingItemsToVault: () -> Unit = {},
    onLockItemsInVault: (List<FileItemEntry>) -> Unit,
    onPreviewDecryptedVaultFile: (File, DragonVaultEntry) -> Unit,
    onVaultMutated: () -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val haptics = LocalDragonHaptics.current

    val vaultItems by DragonPrivateVaultManager.vaultItemsFlow.collectAsState()
    val isPasswordConfigured by DragonPrivateVaultManager.isPasswordConfiguredFlow.collectAsState()
    val isVaultUnlocked by DragonPrivateVaultManager.isVaultUnlockedFlow.collectAsState()

    var isLoading by remember { mutableStateOf(true) }
    var isBusy by remember { mutableStateOf(false) }
    var busyLabel by remember { mutableStateOf("") }

    // Password setup / unlock states
    var setupPasswordInput by remember { mutableStateOf("") }
    var setupConfirmInput by remember { mutableStateOf("") }
    var setupHintInput by remember { mutableStateOf("") }
    var unlockPasswordInput by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var showHintBanner by remember { mutableStateOf(false) }
    var authErrorText by remember { mutableStateOf<String?>(null) }

    // Unlocked Vault browser states
    var searchQuery by remember { mutableStateOf("") }
    var isSearchOpen by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var pendingRestoreItems by remember { mutableStateOf<List<DragonVaultEntry>?>(null) }
    var pendingPermanentDelete by remember { mutableStateOf<List<DragonVaultEntry>?>(null) }
    var showChangePasswordDialog by remember { mutableStateOf(false) }
    var showAddFromFolderPicker by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            DragonPrivateVaultManager.ensureLoaded(context)
            DragonPrivateVaultManager.refresh(context)
        }
        isLoading = false
    }

    // Automatically process pendingItemsToVault once password is set up and vault is unlocked
    LaunchedEffect(isPasswordConfigured, isVaultUnlocked, pendingItemsToVault) {
        if (isPasswordConfigured && isVaultUnlocked && pendingItemsToVault.isNotEmpty()) {
            val toLock = pendingItemsToVault
            onClearPendingItemsToVault()
            onLockItemsInVault(toLock)
        }
    }

    LaunchedEffect(vaultItems) {
        val validIds = vaultItems.map { it.id }.toSet()
        selectedIds = selectedIds.intersect(validIds)
    }

    val filteredItems = remember(vaultItems, searchQuery) {
        val q = searchQuery.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) vaultItems
        else vaultItems.filter {
            it.originalName.lowercase(Locale.ROOT).contains(q) ||
                it.originalPath.lowercase(Locale.ROOT).contains(q)
        }
    }

    val totalVaultBytes = remember(vaultItems) { vaultItems.sumOf { it.sizeBytes } }
    val selectedItems = remember(vaultItems, selectedIds) {
        vaultItems.filter { it.id in selectedIds }
    }
    val selectedBytes = remember(selectedItems) { selectedItems.sumOf { it.sizeBytes } }
    val isSelectionMode = selectedIds.isNotEmpty()
    val allVisibleSelected = filteredItems.isNotEmpty() && filteredItems.all { it.id in selectedIds }

    fun runRestore(targets: List<DragonVaultEntry>, targetDirOverride: File?) {
        if (targets.isEmpty() || isBusy) return
        haptics.confirm()
        isBusy = true
        busyLabel = if (targetDirOverride != null) {
            "Decrypting & restoring ${targets.size} item(s) to ${targetDirOverride.name.ifBlank { "/" }}..."
        } else {
            "Decrypting & restoring ${targets.size} item(s) to original path..."
        }
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                DragonPrivateVaultManager.restoreFromVault(
                    context = context,
                    entries = targets,
                    overrideTargetDir = targetDirOverride
                )
            }
            selectedIds = selectedIds - targets.map { it.id }.toSet()
            isBusy = false
            busyLabel = ""
            if (outcome.restoredCount > 0) {
                onVaultMutated()
            }
            val msg = if (outcome.restoredCount > 0) {
                if (targetDirOverride != null) {
                    "Restored ${outcome.restoredCount} item(s) to ${targetDirOverride.name.ifBlank { "current folder" }}"
                } else {
                    "Restored ${outcome.restoredCount} item(s) to original location"
                }
            } else {
                "Could not restore selected item(s)"
            }
            ApkPackageInstaller.showToastOnMain(context, msg)
        }
    }

    fun runPermanentDelete(targets: List<DragonVaultEntry>) {
        if (targets.isEmpty() || isBusy) return
        haptics.warning()
        isBusy = true
        busyLabel = "Permanently erasing ${targets.size} encrypted item(s)..."
        scope.launch {
            val removed = withContext(Dispatchers.IO) {
                DragonPrivateVaultManager.deletePermanentlyFromVault(context, targets)
            }
            selectedIds = selectedIds - targets.map { it.id }.toSet()
            isBusy = false
            busyLabel = ""
            onVaultMutated()
            ApkPackageInstaller.showToastOnMain(
                context,
                "Permanently deleted $removed item(s) from Private Vault"
            )
        }
    }

    fun runPreviewEntry(entry: DragonVaultEntry) {
        if (entry.isDirectory) {
            ApkPackageInstaller.showToastOnMain(
                context,
                "Folder '${entry.originalName}' (${entry.itemCount} items) — Tap Restore to unpack folder"
            )
            return
        }
        if (isBusy) return
        haptics.tap()
        isBusy = true
        busyLabel = "Decrypting ${entry.originalName} for secure preview..."
        scope.launch {
            val previewFile = withContext(Dispatchers.IO) {
                DragonPrivateVaultManager.decryptToPreviewTempFile(context, entry)
            }
            isBusy = false
            busyLabel = ""
            if (previewFile != null && previewFile.exists()) {
                onPreviewDecryptedVaultFile(previewFile, entry)
            } else {
                ApkPackageInstaller.showToastOnMain(context, "Unable to decrypt ${entry.originalName}")
            }
        }
    }

    Dialog(
        onDismissRequest = { if (!isBusy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.88f)
                .widthIn(max = 560.dp)
                .testTag("dragon_private_vault_dialog"),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.2.dp, VaultPurple.copy(alpha = 0.55f))
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(VaultPurple.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isVaultUnlocked && isPasswordConfigured) {
                                Icons.Default.LockOpen
                            } else {
                                Icons.Default.EnhancedEncryption
                            },
                            contentDescription = "Private Vault",
                            tint = VaultPurple,
                            modifier = Modifier.size(21.dp)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = when {
                                !isPasswordConfigured -> "SETUP PRIVATE VAULT"
                                !isVaultUnlocked -> "PRIVATE VAULT (LOCKED)"
                                isSelectionMode -> "${selectedIds.size} SELECTED IN VAULT"
                                else -> "PRIVATE VAULT (UNLOCKED)"
                            },
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.6.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = when {
                                !isPasswordConfigured -> "Set password to encrypt & hide private files"
                                !isVaultUnlocked -> "${vaultItems.size} item(s) • Enter password to view & unlock"
                                isSelectionMode -> "${formatTrashBytes(selectedBytes)} selected • Restore or delete"
                                vaultItems.isEmpty() -> "0 items • AES-256 password-protected vault"
                                else -> "${vaultItems.size} item(s) • ${formatTrashBytes(totalVaultBytes)} AES-256 encrypted"
                            },
                            fontSize = 10.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (isPasswordConfigured && isVaultUnlocked) {
                        if (vaultItems.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    haptics.tap()
                                    isSearchOpen = !isSearchOpen
                                    if (!isSearchOpen) searchQuery = ""
                                },
                                modifier = Modifier.testTag("vault_search_toggle_btn")
                            ) {
                                Icon(
                                    imageVector = if (isSearchOpen) Icons.Default.SearchOff else Icons.Default.Search,
                                    contentDescription = "Search Private Vault",
                                    tint = if (isSearchOpen) NeonCyan else MaterialTheme.colorScheme.onSurface
                                )
                            }
                            IconButton(
                                onClick = {
                                    haptics.tap()
                                    selectedIds = if (allVisibleSelected) {
                                        emptySet()
                                    } else {
                                        filteredItems.map { it.id }.toSet()
                                    }
                                },
                                modifier = Modifier.testTag("vault_select_all_btn")
                            ) {
                                Icon(
                                    imageVector = if (allVisibleSelected) Icons.Default.Deselect else Icons.Default.SelectAll,
                                    contentDescription = if (allVisibleSelected) "Deselect All" else "Select All",
                                    tint = if (allVisibleSelected) NeonCyan else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        IconButton(
                            onClick = {
                                haptics.confirm()
                                selectedIds = emptySet()
                                unlockPasswordInput = ""
                                DragonPrivateVaultManager.lockVault(context)
                                ApkPackageInstaller.showToastOnMain(context, "Private Vault locked")
                            },
                            modifier = Modifier.testTag("vault_lock_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Lock Private Vault",
                                tint = WarningOrange
                            )
                        }
                    }

                    IconButton(
                        onClick = { if (!isBusy) onDismiss() },
                        modifier = Modifier.testTag("vault_close_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close Private Vault",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))

                when {
                    isLoading -> {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = VaultPurple)
                        }
                    }

                    // 1. FIRST-TIME PASSWORD SETUP SCREEN
                    !isPasswordConfigured -> {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(VaultPurple.copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Security,
                                    contentDescription = null,
                                    tint = VaultPurple,
                                    modifier = Modifier.size(34.dp)
                                )
                            }
                            Spacer(Modifier.height(14.dp))
                            Text(
                                text = "Create Your Private Vault Password",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = if (pendingItemsToVault.isNotEmpty()) {
                                    "Set a password to encrypt and lock ${pendingItemsToVault.size} selected item(s) inside your Private Vault."
                                } else {
                                    "Files stored in Private Vault are encrypted with AES-256 and hidden from other apps. Only someone with your password can unlock and view them."
                                },
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                lineHeight = 16.sp
                            )
                            Spacer(Modifier.height(18.dp))

                            OutlinedTextField(
                                value = setupPasswordInput,
                                onValueChange = {
                                    setupPasswordInput = it
                                    authErrorText = null
                                },
                                label = { Text("New Vault Password") },
                                placeholder = { Text("Enter password (min 4 characters)") },
                                singleLine = true,
                                visualTransformation = if (passwordVisible) {
                                    VisualTransformation.None
                                } else {
                                    PasswordVisualTransformation()
                                },
                                trailingIcon = {
                                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                        Icon(
                                            imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = "Toggle password visibility"
                                        )
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("vault_setup_password_input")
                            )

                            Spacer(Modifier.height(10.dp))

                            OutlinedTextField(
                                value = setupConfirmInput,
                                onValueChange = {
                                    setupConfirmInput = it
                                    authErrorText = null
                                },
                                label = { Text("Confirm Vault Password") },
                                placeholder = { Text("Re-enter password") },
                                singleLine = true,
                                visualTransformation = if (passwordVisible) {
                                    VisualTransformation.None
                                } else {
                                    PasswordVisualTransformation()
                                },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("vault_setup_confirm_input")
                            )

                            Spacer(Modifier.height(10.dp))

                            OutlinedTextField(
                                value = setupHintInput,
                                onValueChange = { setupHintInput = it },
                                label = { Text("Password Hint (Optional)") },
                                placeholder = { Text("Optional reminder hint") },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("vault_setup_hint_input")
                            )

                            authErrorText?.let { err ->
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = err,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = DangerRed
                                )
                            }

                            Spacer(Modifier.height(18.dp))

                            Button(
                                onClick = {
                                    val p1 = setupPasswordInput.trim()
                                    val p2 = setupConfirmInput.trim()
                                    when {
                                        p1.length < 4 -> {
                                            haptics.warning()
                                            authErrorText = "Password must be at least 4 characters"
                                        }
                                        p1 != p2 -> {
                                            haptics.warning()
                                            authErrorText = "Passwords do not match"
                                        }
                                        else -> {
                                            haptics.confirm()
                                            val ok = DragonPrivateVaultManager.setupPassword(
                                                context = context,
                                                password = p1,
                                                hint = setupHintInput
                                            )
                                            if (ok) {
                                                setupPasswordInput = ""
                                                setupConfirmInput = ""
                                                authErrorText = null
                                                ApkPackageInstaller.showToastOnMain(
                                                    context,
                                                    "Private Vault password configured & unlocked"
                                                )
                                            } else {
                                                authErrorText = "Could not configure password"
                                            }
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = VaultPurple,
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("vault_setup_submit_btn")
                            ) {
                                Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = if (pendingItemsToVault.isNotEmpty()) {
                                        "Create Password & Lock ${pendingItemsToVault.size} Item(s)"
                                    } else {
                                        "Create Password & Open Vault"
                                    },
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }
                    }

                    // 2. PASSWORD UNLOCK SCREEN
                    !isVaultUnlocked -> {
                        val storedHint = remember(isPasswordConfigured) {
                            DragonPrivateVaultManager.getPasswordHint(context)
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(22.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(68.dp)
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(VaultPurple.copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = "Locked Private Vault",
                                    tint = VaultPurple,
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                            Spacer(Modifier.height(14.dp))
                            Text(
                                text = "Unlock Private Vault",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(5.dp))
                            Text(
                                text = "${vaultItems.size} encrypted item(s) (${formatTrashBytes(totalVaultBytes)}) protected by password",
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(18.dp))

                            OutlinedTextField(
                                value = unlockPasswordInput,
                                onValueChange = {
                                    unlockPasswordInput = it
                                    authErrorText = null
                                },
                                label = { Text("Vault Password") },
                                placeholder = { Text("Enter your Private Vault password") },
                                singleLine = true,
                                visualTransformation = if (passwordVisible) {
                                    VisualTransformation.None
                                } else {
                                    PasswordVisualTransformation()
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Key, contentDescription = null, tint = VaultPurple)
                                },
                                trailingIcon = {
                                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                        Icon(
                                            imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = "Toggle password visibility"
                                        )
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("vault_unlock_password_input")
                            )

                            authErrorText?.let { err ->
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = err,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = DangerRed
                                )
                            }

                            if (storedHint.isNotBlank()) {
                                Spacer(Modifier.height(8.dp))
                                TextButton(onClick = { showHintBanner = !showHintBanner }) {
                                    Icon(
                                        imageVector = Icons.Default.HelpOutline,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(Modifier.width(5.dp))
                                    Text(
                                        text = if (showHintBanner) "Hint: $storedHint" else "Show Password Hint",
                                        fontSize = 11.5.sp
                                    )
                                }
                            }

                            Spacer(Modifier.height(16.dp))

                            Button(
                                onClick = {
                                    val pwd = unlockPasswordInput.trim()
                                    if (pwd.isEmpty()) {
                                        haptics.warning()
                                        authErrorText = "Please enter your password"
                                    } else {
                                        val unlocked = DragonPrivateVaultManager.unlockVault(context, pwd)
                                        if (unlocked) {
                                            haptics.confirm()
                                            unlockPasswordInput = ""
                                            authErrorText = null
                                        } else {
                                            haptics.warning()
                                            authErrorText = "Incorrect password. Please try again."
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = VaultPurple,
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("vault_unlock_submit_btn")
                            ) {
                                Icon(Icons.Default.LockOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "Unlock Private Vault",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }
                    }

                    // 3. UNLOCKED PRIVATE VAULT MANAGER
                    else -> {
                        // Optional Search Bar
                        AnimatedVisibility(visible = isSearchOpen && vaultItems.isNotEmpty()) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                singleLine = true,
                                placeholder = { Text("Search private files or paths...", fontSize = 12.sp) },
                                leadingIcon = {
                                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                                },
                                trailingIcon = {
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(onClick = { searchQuery = "" }) {
                                            Icon(Icons.Default.Close, contentDescription = "Clear search", modifier = Modifier.size(16.dp))
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                                    .testTag("vault_search_input")
                            )
                        }

                        // Quick Actions Toolbar: + Add Files from Current Folder & Change Password
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(9.dp),
                                color = VaultPurple.copy(alpha = 0.16f),
                                border = BorderStroke(0.9.dp, VaultPurple.copy(alpha = 0.55f)),
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(9.dp))
                                    .clickable(enabled = !isBusy) {
                                        haptics.tap()
                                        showAddFromFolderPicker = true
                                    }
                                    .testTag("vault_add_from_current_dir_btn")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.AddModerator,
                                        contentDescription = "Add Files to Vault",
                                        tint = VaultPurple,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(Modifier.width(5.dp))
                                    Text(
                                        text = "+ Add Files (${currentDir.name.ifBlank { "/" }})",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = VaultPurple,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Surface(
                                shape = RoundedCornerShape(9.dp),
                                color = NeonCyan.copy(alpha = 0.13f),
                                border = BorderStroke(0.9.dp, NeonCyan.copy(alpha = 0.45f)),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(9.dp))
                                    .clickable(enabled = !isBusy) {
                                        haptics.tap()
                                        showChangePasswordDialog = true
                                    }
                                    .testTag("vault_change_password_btn")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Password,
                                        contentDescription = "Change Vault Password",
                                        tint = NeonCyan,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Text(
                                        text = "Password",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = NeonCyan
                                    )
                                }
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                        if (isBusy) {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = VaultPurple
                            )
                            if (busyLabel.isNotBlank()) {
                                Text(
                                    text = busyLabel,
                                    fontSize = 11.sp,
                                    color = VaultPurple,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                )
                            }
                        }

                        // Vault Items List
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        ) {
                            if (filteredItems.isEmpty()) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(28.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(64.dp)
                                            .clip(CircleShape)
                                            .background(VaultPurple.copy(alpha = 0.14f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.EnhancedEncryption,
                                            contentDescription = "Private Vault Empty",
                                            tint = VaultPurple,
                                            modifier = Modifier.size(34.dp)
                                        )
                                    }
                                    Spacer(Modifier.height(14.dp))
                                    Text(
                                        text = if (searchQuery.isNotBlank()) "No matching vault files" else "Private Vault is Empty",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        text = if (searchQuery.isNotBlank()) {
                                            "Try another file name or path."
                                        } else {
                                            "Tap '+ Add Files' above or long-press any file/folder in File Manager and choose 'Lock in Private Vault' to encrypt and hide it with your password."
                                        },
                                        fontSize = 11.5.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center,
                                        lineHeight = 16.sp
                                    )
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(7.dp)
                                ) {
                                    items(filteredItems, key = { it.id }) { entry ->
                                        val isSelected = entry.id in selectedIds
                                        PrivateVaultItemCard(
                                            entry = entry,
                                            isSelected = isSelected,
                                            isSelectionMode = isSelectionMode,
                                            enabled = !isBusy,
                                            onToggleSelect = {
                                                haptics.tap()
                                                selectedIds = if (isSelected) {
                                                    selectedIds - entry.id
                                                } else {
                                                    selectedIds + entry.id
                                                }
                                            },
                                            onOpenPreview = { runPreviewEntry(entry) },
                                            onRestoreOriginal = {
                                                haptics.tap()
                                                pendingRestoreItems = listOf(entry)
                                            },
                                            onDeleteForever = {
                                                haptics.tap()
                                                pendingPermanentDelete = listOf(entry)
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // Multi-Selection Batch Action Bar
                        AnimatedVisibility(visible = isSelectionMode) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                tonalElevation = 4.dp,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column {
                                    HorizontalDivider(color = VaultPurple.copy(alpha = 0.45f))
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 10.dp, vertical = 9.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = {
                                                haptics.tap()
                                                pendingRestoreItems = selectedItems
                                            },
                                            enabled = !isBusy && selectedItems.isNotEmpty(),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = SuccessGreen,
                                                contentColor = Color.Black
                                            ),
                                            shape = RoundedCornerShape(11.dp),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 9.dp),
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("vault_batch_restore_original_btn")
                                        ) {
                                            Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                text = "Restore (${selectedItems.size})",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                maxLines = 1
                                            )
                                        }

                                        Button(
                                            onClick = {
                                                haptics.tap()
                                                pendingPermanentDelete = selectedItems
                                            },
                                            enabled = !isBusy && selectedItems.isNotEmpty(),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = DangerRed,
                                                contentColor = Color.White
                                            ),
                                            shape = RoundedCornerShape(11.dp),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 9.dp),
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("vault_batch_delete_btn")
                                        ) {
                                            Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                text = "Delete (${selectedItems.size})",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                maxLines = 1
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
    }

    // Add Files from Current Directory Picker Modal
    if (showAddFromFolderPicker) {
        AddFilesToVaultPickerDialog(
            currentDir = currentDir,
            availableItems = currentDirItems,
            onDismiss = { showAddFromFolderPicker = false },
            onConfirmAdd = { chosen ->
                showAddFromFolderPicker = false
                if (chosen.isNotEmpty()) {
                    onLockItemsInVault(chosen)
                }
            }
        )
    }

    // Change Vault Password Dialog
    if (showChangePasswordDialog) {
        ChangeVaultPasswordDialog(
            context = context,
            onDismiss = { showChangePasswordDialog = false }
        )
    }

    // Compact Yes/No Restore Confirmation Popup
    pendingRestoreItems?.let { targets ->
        val bytes = targets.sumOf { it.sizeBytes }
        CompactVaultYesNoConfirmPopup(
            icon = Icons.Default.Restore,
            accentColor = SuccessGreen,
            title = if (targets.size == 1) "Restore from Private Vault?" else "Restore ${targets.size} Items?",
            message = if (targets.size == 1) {
                "Decrypt and restore \"${targets.first().originalName}\" (${formatTrashBytes(bytes)}) to its original location?"
            } else {
                "Decrypt and restore ${targets.size} selected items (${formatTrashBytes(bytes)}) to their original locations?"
            },
            yesContentColor = Color.Black,
            testTagPrefix = "vault_confirm_restore",
            onYes = {
                val toRestore = targets
                pendingRestoreItems = null
                runRestore(toRestore, null)
            },
            onNo = { pendingRestoreItems = null }
        )
    }

    // Compact Yes/No Permanent Delete Confirmation Popup
    pendingPermanentDelete?.let { targets ->
        val bytes = targets.sumOf { it.sizeBytes }
        CompactVaultYesNoConfirmPopup(
            icon = Icons.Default.DeleteForever,
            accentColor = DangerRed,
            title = if (targets.size == 1) "Delete from Private Vault?" else "Delete ${targets.size} Items?",
            message = if (targets.size == 1) {
                "Permanently delete \"${targets.first().originalName}\" (${formatTrashBytes(bytes)}) from Private Vault?"
            } else {
                "Permanently delete ${targets.size} selected items (${formatTrashBytes(bytes)}) from Private Vault?"
            },
            yesContentColor = Color.White,
            testTagPrefix = "vault_confirm_delete",
            onYes = {
                val toDelete = targets
                pendingPermanentDelete = null
                runPermanentDelete(toDelete)
            },
            onNo = { pendingPermanentDelete = null }
        )
    }
}

@Composable
private fun CompactVaultYesNoConfirmPopup(
    icon: ImageVector,
    accentColor: Color,
    title: String,
    message: String,
    yesContentColor: Color,
    testTagPrefix: String,
    onYes: () -> Unit,
    onNo: () -> Unit
) {
    Dialog(onDismissRequest = onNo) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, accentColor.copy(alpha = 0.55f)),
            modifier = Modifier
                .widthIn(max = 310.dp)
                .fillMaxWidth()
                .testTag("${testTagPrefix}_dialog")
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(accentColor.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Text(
                        text = title,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Text(
                    text = message,
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 15.5.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onNo,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp)
                            .testTag("${testTagPrefix}_no_btn")
                    ) {
                        Text(
                            text = "No",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Button(
                        onClick = onYes,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = accentColor,
                            contentColor = yesContentColor
                        ),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp)
                            .testTag("${testTagPrefix}_yes_btn")
                    ) {
                        Text(
                            text = "Yes",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PrivateVaultItemCard(
    entry: DragonVaultEntry,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    enabled: Boolean,
    onToggleSelect: () -> Unit,
    onOpenPreview: () -> Unit,
    onRestoreOriginal: () -> Unit,
    onDeleteForever: () -> Unit
) {
    val (icon, tint) = remember(entry.originalName, entry.isDirectory) {
        resolveVaultItemIconAndColor(entry)
    }
    val addedLabel = remember(entry.addedAtMs) {
        SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(entry.addedAtMs))
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (isSelected) VaultPurple.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            width = if (isSelected) 1.2.dp else 0.7.dp,
            color = if (isSelected) VaultPurple else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(
                enabled = enabled,
                onClick = {
                    if (isSelectionMode) onToggleSelect() else onOpenPreview()
                },
                onLongClick = { onToggleSelect() }
            )
            .testTag("vault_item_${entry.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSelected) VaultPurple else tint.copy(alpha = 0.16f))
                    .clickable(enabled = enabled, onClick = onToggleSelect),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isSelected) Icons.Default.Check else icon,
                    contentDescription = if (isSelected) "Selected" else entry.originalName,
                    tint = if (isSelected) Color.White else tint,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = VaultPurple.copy(alpha = 0.22f)
                    ) {
                        Text(
                            text = "AES-256",
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.ExtraBold,
                            color = VaultPurple,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                    Text(
                        text = entry.originalName,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = "Origin: ${entry.originalPath}",
                    fontSize = 9.8.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                val sizeLabel = if (entry.isDirectory) {
                    "${entry.itemCount} item(s) • ${formatTrashBytes(entry.sizeBytes)}"
                } else {
                    formatTrashBytes(entry.sizeBytes)
                }
                Text(
                    text = "$sizeLabel • Locked $addedLabel",
                    fontSize = 9.8.sp,
                    color = NeonCyan.copy(alpha = 0.88f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (!isSelectionMode) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    if (!entry.isDirectory) {
                        IconButton(
                            onClick = onOpenPreview,
                            enabled = enabled,
                            modifier = Modifier
                                .size(34.dp)
                                .testTag("vault_open_${entry.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Visibility,
                                contentDescription = "Open ${entry.originalName}",
                                tint = NeonCyan,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    IconButton(
                        onClick = onRestoreOriginal,
                        enabled = enabled,
                        modifier = Modifier
                            .size(34.dp)
                            .testTag("vault_restore_original_${entry.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Restore,
                            contentDescription = "Restore ${entry.originalName} to original path",
                            tint = SuccessGreen,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                    IconButton(
                        onClick = onDeleteForever,
                        enabled = enabled,
                        modifier = Modifier
                            .size(34.dp)
                            .testTag("vault_delete_${entry.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteForever,
                            contentDescription = "Delete ${entry.originalName} permanently",
                            tint = DangerRed,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            } else {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelect() },
                    enabled = enabled
                )
            }
        }
    }
}

@Composable
private fun AddFilesToVaultPickerDialog(
    currentDir: File,
    availableItems: List<FileItemEntry>,
    onDismiss: () -> Unit,
    onConfirmAdd: (List<FileItemEntry>) -> Unit
) {
    var selectedPaths by remember { mutableStateOf(setOf<String>()) }
    val allSelected = availableItems.isNotEmpty() && selectedPaths.size == availableItems.size

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, VaultPurple.copy(alpha = 0.55f)),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 500.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Add Files to Private Vault",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = "Folder: ${currentDir.absolutePath}",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (availableItems.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                selectedPaths = if (allSelected) {
                                    emptySet()
                                } else {
                                    availableItems.map { it.selectionKey }.toSet()
                                }
                            }
                        ) {
                            Text(if (allSelected) "None" else "All", fontSize = 11.5.sp)
                        }
                    }
                }

                HorizontalDivider()

                if (availableItems.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No files in current folder (${currentDir.name})",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        items(availableItems, key = { it.selectionKey }) { item ->
                            val isSel = item.selectionKey in selectedPaths
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSel) VaultPurple.copy(alpha = 0.15f)
                                else MaterialTheme.colorScheme.surfaceContainerLow,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        selectedPaths = if (isSel) {
                                            selectedPaths - item.selectionKey
                                        } else {
                                            selectedPaths + item.selectionKey
                                        }
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Checkbox(
                                        checked = isSel,
                                        onCheckedChange = {
                                            selectedPaths = if (isSel) {
                                                selectedPaths - item.selectionKey
                                            } else {
                                                selectedPaths + item.selectionKey
                                            }
                                        }
                                    )
                                    Icon(
                                        imageVector = resolveFileEntryIcon(item),
                                        contentDescription = null,
                                        tint = resolveFileEntryColor(item),
                                        modifier = Modifier.size(19.dp)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.name,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = if (item.isDirectory) "Folder" else formatTrashBytes(item.lengthBytes),
                                            fontSize = 9.5.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

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
                            val chosen = availableItems.filter { it.selectionKey in selectedPaths }
                            onConfirmAdd(chosen)
                        },
                        enabled = selectedPaths.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = VaultPurple,
                            contentColor = Color.White
                        )
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Lock in Vault (${selectedPaths.size})", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChangeVaultPasswordDialog(
    context: Context,
    onDismiss: () -> Unit
) {
    val haptics = LocalDragonHaptics.current
    var currentPwd by remember { mutableStateOf("") }
    var newPwd by remember { mutableStateOf("") }
    var confirmPwd by remember { mutableStateOf("") }
    var newHint by remember { mutableStateOf(DragonPrivateVaultManager.getPasswordHint(context)) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Change Private Vault Password", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = currentPwd,
                    onValueChange = {
                        currentPwd = it
                        errorMsg = null
                    },
                    label = { Text("Current Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = newPwd,
                    onValueChange = {
                        newPwd = it
                        errorMsg = null
                    },
                    label = { Text("New Password (min 4 chars)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = confirmPwd,
                    onValueChange = {
                        confirmPwd = it
                        errorMsg = null
                    },
                    label = { Text("Confirm New Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = newHint,
                    onValueChange = { newHint = it },
                    label = { Text("Password Hint (Optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                errorMsg?.let { err ->
                    Text(
                        text = err,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = DangerRed
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val n1 = newPwd.trim()
                    val n2 = confirmPwd.trim()
                    when {
                        n1.length < 4 -> {
                            haptics.warning()
                            errorMsg = "New password must be at least 4 characters"
                        }
                        n1 != n2 -> {
                            haptics.warning()
                            errorMsg = "New passwords do not match"
                        }
                        else -> {
                            val ok = DragonPrivateVaultManager.changePassword(
                                context = context,
                                oldPassword = currentPwd,
                                newPassword = n1,
                                newHint = newHint
                            )
                            if (ok) {
                                haptics.confirm()
                                ApkPackageInstaller.showToastOnMain(context, "Private Vault password updated")
                                onDismiss()
                            } else {
                                haptics.warning()
                                errorMsg = "Current password is incorrect"
                            }
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = VaultPurple, contentColor = Color.White)
            ) {
                Text("Update Password", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private fun resolveVaultItemIconAndColor(entry: DragonVaultEntry): Pair<ImageVector, Color> {
    if (entry.isDirectory) return Icons.Default.Folder to WarningOrange
    val ext = entry.extension.ifBlank { entry.originalName.substringAfterLast('.', "") }.lowercase(Locale.ROOT)
    return when (ext) {
        "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic" -> Icons.Default.Image to Color(0xFF4FC3F7)
        "mp4", "mkv", "avi", "mov", "webm", "3gp", "ts" -> Icons.Default.Movie to Color(0xFFFF7043)
        "mp3", "m4a", "flac", "wav", "ogg", "opus", "aac" -> Icons.Default.MusicNote to Color(0xFFBA68C8)
        "zip", "rar", "7z", "tar", "gz", "xz", "bz2", "zst" -> Icons.Default.FolderZip to WarningOrange
        "apk", "apks", "xapk", "apkm" -> Icons.Default.Android to SuccessGreen
        "pdf", "epub", "doc", "docx", "txt", "md", "json", "xml" -> Icons.Default.Description to NeonCyan
        else -> Icons.Default.InsertDriveFile to Color(0xFF90A4AE)
    }
}
