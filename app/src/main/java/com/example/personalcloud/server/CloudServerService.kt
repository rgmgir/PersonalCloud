package com.example.personalcloud.server

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

class CloudServerService : Service() {
    companion object {
        const val ACTION_START = "START_SERVER"
        const val ACTION_STOP = "STOP_SERVER"
        const val EXTRA_PATHS = "ROOT_PATHS"
    }

    private var fileServer: FileServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val paths = intent.getStringArrayListExtra(EXTRA_PATHS) ?: arrayListOf("/storage/emulated/0")
                startServer(paths)
                startForeground(1, createNotification())
            }
            ACTION_STOP -> {
                stopServer()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startServer(paths: List<String>) {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PersonalCloud::ServerWakeLock")
            wakeLock?.acquire()
        }
        if (wifiLock == null) {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wifiManager.createWifiLock(3, "PersonalCloud::HighPerfWifiLock")
            wifiLock?.acquire()
        }

        if (fileServer == null) {
            fileServer = FileServer(applicationContext)
        }
        fileServer?.rootPaths = paths
        try {
            fileServer?.start()
        } catch (e: Exception) {
            android.util.Log.e("CloudServer", "Failed to start server", e)
        }
    }

    private fun stopServer() {
        fileServer?.stop()
        fileServer = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("server_channel", "Personal Cloud Server", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification() = NotificationCompat.Builder(this, "server_channel")
        .setContentTitle("Personal Cloud Server")
        .setContentText("Server running (High Performance Mode)")
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setOngoing(true)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        super.onDestroy()
        stopServer()
    }
}
