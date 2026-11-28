package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.example.data.downloader.ApkPackageInstaller
import com.example.data.filemanager.DragonFileManagerTaskEngine
import com.example.data.filemanager.FileManagerTerminalStatus
import java.io.File

/**
 * Receives `PackageInstaller.Session` callbacks for `.apk`, `.apks`, `.xapk`, and `.apkm`
 * installations, updates, and downgrades (both signed and unsigned).
 *
 * Updates the persistent notification panel accurately (`COMPLETED` = 100%,
 * `CANCELLED` = 0% "CANCELLED", `FAILED` = 0% "FAILED"), handles V1+V2 auto-signing retry
 * if an unsigned/unaligned package slips through, and triggers the in-app resolution dialog
 * for version downgrades or developer signature conflicts.
 */
class ApkInstallerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_PACKAGE_INSTALL_STATUS) return

        val appContext = context.applicationContext
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val statusMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        val taskId = intent.getStringExtra(EXTRA_TASK_ID).orEmpty()
        val filename = intent.getStringExtra(EXTRA_FILENAME).orEmpty().ifBlank { "Package" }
        val packageNameExtra = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
        val wasAutoSigned = intent.getBooleanExtra(EXTRA_WAS_AUTO_SIGNED, false)
        val isDowngrade = intent.getBooleanExtra(EXTRA_IS_DOWNGRADE, false)
        val sourcePath = intent.getStringExtra(EXTRA_SOURCE_PATH).orEmpty()
        val publicUri = intent.getStringExtra(EXTRA_PUBLIC_URI).orEmpty()
        val fmTaskId = intent.getIntExtra(EXTRA_FM_TASK_ID, 0).takeIf { it > 0 }
            ?: taskId.takeIf { it.isNotBlank() }?.let { ApkPackageInstaller.getFmTaskIdForInstall(it) }
            ?: 0

        val registeredTask = taskId.takeIf { it.isNotBlank() }?.let {
            ApkPackageInstaller.getRegisteredTask(it)
        } ?: sourcePath.takeIf { it.isNotBlank() }?.let { path ->
            File(path).takeIf { it.exists() }?.let { ApkPackageInstaller.buildSyntheticTaskForFile(it) }
        }

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                if (fmTaskId > 0) {
                    DragonFileManagerTaskEngine.updatePackageInstallProgress(
                        context = appContext,
                        taskId = fmTaskId,
                        title = if (isDowngrade) "Confirm Downgrade" else "Confirm Installation",
                        subtitle = "Tap Install in system prompt",
                        currentFileName = filename,
                        progressPercent = 90,
                        isIndeterminate = true
                    )
                }
                val confirmIntent: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirmIntent != null) {
                    try {
                        confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(confirmIntent)
                    } catch (e: Exception) {
                        val errMsg = "Unable to open installer prompt for $filename: ${e.message}"
                        ApkPackageInstaller.notifyPackageInstallChanged(taskId.takeIf { it.isNotBlank() })
                        DragonFileManagerTaskEngine.finishPackageInstallTask(
                            context = appContext,
                            taskId = fmTaskId,
                            title = "Installation Failed",
                            summaryMessage = errMsg,
                            status = FileManagerTerminalStatus.FAILED
                        )
                        ApkPackageInstaller.showToastOnMain(appContext, errMsg)
                    }
                } else {
                    ApkPackageInstaller.notifyPackageInstallChanged(taskId.takeIf { it.isNotBlank() })
                    DragonFileManagerTaskEngine.finishPackageInstallTask(
                        context = appContext,
                        taskId = fmTaskId,
                        title = "Installation Failed",
                        summaryMessage = "System installer confirmation intent missing for $filename",
                        status = FileManagerTerminalStatus.FAILED
                    )
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                ApkPackageInstaller.notifyPackageInstallChanged(taskId.takeIf { it.isNotBlank() })
                val signedNote = if (wasAutoSigned) " (Auto-Signed V1+V2)" else ""
                val title = if (isDowngrade) "Downgrade Complete" else "Installation Complete"
                val verb = if (isDowngrade) "Downgraded" else "Installed / Updated"
                DragonFileManagerTaskEngine.finishPackageInstallTask(
                    context = appContext,
                    taskId = fmTaskId,
                    title = title,
                    summaryMessage = "$filename$signedNote",
                    status = FileManagerTerminalStatus.COMPLETED
                )
                ApkPackageInstaller.showToastOnMain(
                    appContext,
                    "$verb $filename$signedNote successfully"
                )
            }

            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                ApkPackageInstaller.notifyPackageInstallChanged(taskId.takeIf { it.isNotBlank() })
                DragonFileManagerTaskEngine.finishPackageInstallTask(
                    context = appContext,
                    taskId = fmTaskId,
                    title = "Installation Cancelled",
                    summaryMessage = "Cancelled by user • $filename",
                    status = FileManagerTerminalStatus.CANCELLED
                )
                ApkPackageInstaller.showToastOnMain(
                    appContext,
                    "Installation cancelled for $filename"
                )
            }

            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> {
                ApkPackageInstaller.notifyPackageInstallChanged(taskId.takeIf { it.isNotBlank() })
                val msg = "Incompatible package ($filename): CPU architecture or minimum Android SDK does not match this device."
                DragonFileManagerTaskEngine.finishPackageInstallTask(
                    context = appContext,
                    taskId = fmTaskId,
                    title = "Installation Failed",
                    summaryMessage = msg,
                    status = FileManagerTerminalStatus.FAILED
                )
                ApkPackageInstaller.showToastOnMain(appContext, msg)
            }

            PackageInstaller.STATUS_FAILURE_STORAGE -> {
                ApkPackageInstaller.notifyPackageInstallChanged(taskId.takeIf { it.isNotBlank() })
                val msg = "Insufficient storage space to install $filename"
                DragonFileManagerTaskEngine.finishPackageInstallTask(
                    context = appContext,
                    taskId = fmTaskId,
                    title = "Installation Failed",
                    summaryMessage = msg,
                    status = FileManagerTerminalStatus.FAILED
                )
                ApkPackageInstaller.showToastOnMain(appContext, msg)
            }

            PackageInstaller.STATUS_FAILURE_BLOCKED -> {
                ApkPackageInstaller.notifyPackageInstallChanged(taskId.takeIf { it.isNotBlank() })
                val msg = "Installation of $filename was blocked by device policy or Play Protect."
                DragonFileManagerTaskEngine.finishPackageInstallTask(
                    context = appContext,
                    taskId = fmTaskId,
                    title = "Installation Failed",
                    summaryMessage = msg,
                    status = FileManagerTerminalStatus.FAILED
                )
                ApkPackageInstaller.showToastOnMain(appContext, msg)
            }

            else -> {
                val lowerMsg = statusMessage.lowercase()
                val isDowngradeFailure = lowerMsg.contains("version_downgrade") ||
                    lowerMsg.contains("downgrade") ||
                    (isDowngrade && status == PackageInstaller.STATUS_FAILURE_INVALID)
                val isSignatureConflict = status == PackageInstaller.STATUS_FAILURE_CONFLICT ||
                    lowerMsg.contains("update_incompatible") ||
                    lowerMsg.contains("signatures_do_not_match") ||
                    lowerMsg.contains("inconsistent_certificates")

                if (isDowngradeFailure || isSignatureConflict) {
                    ApkPackageInstaller.notifyPackageInstallChanged(taskId.takeIf { it.isNotBlank() })
                    val reason = when {
                        isDowngradeFailure ->
                            "Android blocked direct version downgrade for $filename. Tap 'Uninstall & Downgrade' to replace cleanly."
                        else ->
                            "Signature mismatch for $filename: the installed app uses a different developer certificate. Tap 'Uninstall & Install' to replace it cleanly."
                    }
                    DragonFileManagerTaskEngine.finishPackageInstallTask(
                        context = appContext,
                        taskId = fmTaskId,
                        title = if (isDowngradeFailure) "Downgrade Blocked" else "Signature Conflict",
                        summaryMessage = reason,
                        status = FileManagerTerminalStatus.FAILED
                    )
                    ApkPackageInstaller.dispatchResolutionPromptFromReceiver(
                        context = appContext,
                        taskId = taskId,
                        packageNameHint = packageNameExtra,
                        sourcePath = sourcePath,
                        publicUri = publicUri,
                        filename = filename,
                        isDowngrade = isDowngradeFailure,
                        isSignatureConflict = isSignatureConflict,
                        wasAutoSigned = wasAutoSigned,
                        fmTaskId = fmTaskId,
                        reasonMessage = reason
                    )
                    ApkPackageInstaller.showToastOnMain(appContext, reason)
                } else {
                    val looksLikeSignatureOrAlignIssue = !wasAutoSigned && (
                        status == PackageInstaller.STATUS_FAILURE_INVALID ||
                            lowerMsg.contains("certificate") ||
                            lowerMsg.contains("signature") ||
                            lowerMsg.contains("no_certificates") ||
                            lowerMsg.contains("verify") ||
                            lowerMsg.contains("align") ||
                            lowerMsg.contains("resources.arsc") ||
                            lowerMsg.contains("parse")
                        )

                    if (looksLikeSignatureOrAlignIssue && registeredTask != null) {
                        DragonFileManagerTaskEngine.updatePackageInstallProgress(
                            context = appContext,
                            taskId = fmTaskId,
                            title = "Auto-Signing (V1+V2)",
                            subtitle = "Re-signing & retrying $filename...",
                            currentFileName = filename,
                            progressPercent = 45,
                            isIndeterminate = false
                        )
                        ApkPackageInstaller.showToastOnMain(
                            appContext,
                            "Auto-signing & zipaligning $filename (V1+V2 Scheme) and retrying install..."
                        )
                        ApkPackageInstaller.installOrUpdatePackage(
                            context = appContext,
                            task = registeredTask,
                            forceSign = true,
                            existingFmTaskId = fmTaskId,
                            onFeedback = { msg ->
                                ApkPackageInstaller.showToastOnMain(appContext, msg)
                            }
                        )
                    } else {
                        ApkPackageInstaller.notifyPackageInstallChanged(taskId.takeIf { it.isNotBlank() })
                        val detail = statusMessage.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
                        val errMsg = "Could not install $filename$detail"
                        DragonFileManagerTaskEngine.finishPackageInstallTask(
                            context = appContext,
                            taskId = fmTaskId,
                            title = "Installation Failed",
                            summaryMessage = errMsg,
                            status = FileManagerTerminalStatus.FAILED
                        )
                        ApkPackageInstaller.showToastOnMain(appContext, errMsg)
                    }
                }
            }
        }
        if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) {
            com.example.data.cache.DragonSmartCacheController.runSmartMaintenanceAsync(
                context = appContext,
                aggressive = false,
                force = true,
                preserveDownloaderWork = true
            )
        }
    }

    companion object {
        const val ACTION_PACKAGE_INSTALL_STATUS = "com.example.ddm.ACTION_PACKAGE_INSTALL_STATUS"
        const val EXTRA_TASK_ID = "extra_install_task_id"
        const val EXTRA_FM_TASK_ID = "extra_install_fm_task_id"
        const val EXTRA_FILENAME = "extra_install_filename"
        const val EXTRA_PACKAGE_NAME = "extra_install_package_name"
        const val EXTRA_WAS_AUTO_SIGNED = "extra_install_was_auto_signed"
        const val EXTRA_IS_DOWNGRADE = "extra_install_is_downgrade"
        const val EXTRA_SOURCE_PATH = "extra_install_source_path"
        const val EXTRA_PUBLIC_URI = "extra_install_public_uri"
        const val EXTRA_FORCE_SIGN = "extra_install_force_sign"
    }
}

