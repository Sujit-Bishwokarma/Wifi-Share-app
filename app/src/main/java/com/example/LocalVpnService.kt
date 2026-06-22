package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException

class LocalVpnService : VpnService() {
    companion object {
        private const val TAG = "LocalVpnService"
        private const val CHANNEL_ID = "tether_vpn_channel"
        private const val NOTIFICATION_ID = 4821

        private val _isVpnRunning = MutableStateFlow(false)
        val isVpnRunning = _isVpnRunning.asStateFlow()

        private val _logs = MutableStateFlow<List<String>>(emptyList())
        val logs = _logs.asStateFlow()

        private val _totalBytesRouted = MutableStateFlow(0L)
        val totalBytesRouted = _totalBytesRouted.asStateFlow()

        fun incrementBytesRouted(bytes: Long) {
            _totalBytesRouted.value += bytes
        }

        fun resetBytesRouted() {
            _totalBytesRouted.value = 0L
        }

        fun addLog(msg: String) {
            val currentList = _logs.value.toMutableList()
            if (currentList.size > 200) {
                currentList.removeAt(0)
            }
            currentList.add(msg)
            _logs.value = currentList
        }

        fun clearLogs() {
            _logs.value = emptyList()
        }
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var packetRouter: LocalPacketRouter? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && "ACTION_STOP" == intent.action) {
            stopVpn()
            return START_NOT_STICKY
        }

        startVpn()
        return START_STICKY
    }

    private fun startVpn() {
        if (_isVpnRunning.value) {
            addLog("VPN already running.")
            return
        }
        resetBytesRouted()

        addLog("Establishing VPN Interception Tunnel...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        try {
            val builder = Builder()
                .setSession("Tether VPN Bypass Router")
                .addAddress("10.0.0.1", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .addDnsServer("1.1.1.1")
                .setMtu(1500)

            try {
                builder.addDisallowedApplication(this.packageName)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to exclude own package from VPN", e)
                addLog("Warning: Could not exclude app's own package from routing.")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }

            vpnInterface = builder.establish()
            val descriptor = vpnInterface

            if (descriptor == null) {
                addLog("ERROR: High-level VPN establish failed (descriptor is null). check VPN permissions.")
                stopVpn()
                return
            }

            packetRouter = LocalPacketRouter(descriptor) { logLine ->
                addLog(logLine)
            }
            packetRouter?.start()

            _isVpnRunning.value = true
            addLog("VPN Interception active at tun0 (Routing raw clients traffic)...")
        } catch (e: Exception) {
            addLog("VPN startup crashed: ${e.message}")
            Log.e(TAG, "VPN startup crashed", e)
            stopVpn()
        }
    }

    private fun stopVpn() {
        addLog("Stopping VPN Interface...")
        packetRouter?.stop()
        packetRouter = null

        try {
            vpnInterface?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing VPN Interface", e)
        }
        vpnInterface = null

        _isVpnRunning.value = false
        addLog("VPN Interception closed cleanly.")
        
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, LocalVpnService::class.java).apply {
            action = "ACTION_STOP"
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Tether Router Running")
            .setContentText("Intercepting & decapsulating Hotspot clients traffic...")
            .setSmallIcon(android.R.drawable.ic_dialog_info) // Standard robust public platform icon
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Router", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationHelper.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Tether Router Channels"
            val descriptionText = "VPN Active Routing status notifications"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}

// Inline fallback object for safe Category compilation across SDKs
object NotificationHelper {
    const val CATEGORY_SERVICE = "service"
}
