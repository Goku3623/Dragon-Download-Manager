package com.example

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.example.data.db.DragonDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DragonKernelApp : Application() {

    private val appInitScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
        appInitScope.launch {
            runCatching { DragonDatabase.getInstance(this@DragonKernelApp) }
            try {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                nm?.cancel(com.example.service.DragonMonitorService.NOTIFICATION_ID)
                nm?.cancel(com.example.service.DragonMonitorService.THERMAL_WARNING_NOTIFICATION_ID)
            } catch (_: Exception) {}
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val downloaderChannel = NotificationChannel(
                CHANNEL_DOWNLOADER,
                "DDM Download Control Panel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent live download progress, speed telemetry, smart retry status, and playback/pause controls"
                setShowBadge(true)
            }

            notificationManager.createNotificationChannel(downloaderChannel)
        }
    }

    companion object {
        const val CHANNEL_TELEMETRY = "dragon_kernel_telemetry"
        const val CHANNEL_THERMAL_ALERT = "dragon_thermal_alert"
        const val CHANNEL_DOWNLOADER = "dragon_ddm_downloads"
        lateinit var instance: DragonKernelApp
            private set
    }
}
