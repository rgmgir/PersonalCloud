package com.example.personalcloud.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences("PersonalCloud", Context.MODE_PRIVATE)
            val defaultPath = android.os.Environment.getExternalStorageDirectory().absolutePath
            val path1 = prefs.getString("path1", defaultPath) ?: defaultPath
            val path2 = prefs.getString("path2", "") ?: ""
            val path3 = prefs.getString("path3", "") ?: ""
            
            val finalPaths = listOf(path1, path2, path3).filter { it.isNotBlank() }.ifEmpty { listOf(defaultPath) }
            
            val serviceIntent = Intent(context, CloudServerService::class.java).apply {
                action = CloudServerService.ACTION_START
                putStringArrayListExtra(CloudServerService.EXTRA_PATHS, ArrayList(finalPaths))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}
