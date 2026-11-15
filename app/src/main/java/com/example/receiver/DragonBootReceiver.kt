package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.data.repository.KernelRepository
import com.example.service.DragonMonitorService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DragonBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val repo = KernelRepository.getInstance(context.applicationContext)
                    val dm = repo.downloadManager
                    dm.notificationController.syncTasks(dm.tasksFlow.value, force = true)
                } catch (_: Exception) {}
            }
        }
    }
}
