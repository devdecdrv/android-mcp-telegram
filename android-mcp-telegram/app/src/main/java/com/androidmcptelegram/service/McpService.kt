package com.androidmcptelegram.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.androidmcptelegram.MainActivity
import com.androidmcptelegram.McpApplication
import com.androidmcptelegram.R
import com.androidmcptelegram.mcp.McpServer
import com.androidmcptelegram.tunnel.CloudflareTunnel
import com.androidmcptelegram.tunnel.TelegramNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class McpService : Service() {

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP  = "ACTION_STOP"
        const val NOTIF_ID     = 1001

        @Volatile var isRunning = false
        @Volatile var currentTunnelUrl: String? = null
    }

    private var mcpServer: McpServer? = null
    private var tunnel: CloudflareTunnel? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startEverything()
            ACTION_STOP  -> stopEverything()
        }
        return START_NOT_STICKY
    }

    private fun startEverything() {
        startForeground(NOTIF_ID, buildNotification("Starting…"))
        isRunning = true

        // Start MCP HTTP server on 127.0.0.1:8080
        mcpServer = McpServer(applicationContext).also { it.start(8080) }

        // Start Cloudflare tunnel
        tunnel = CloudflareTunnel(
            context = applicationContext,
            port = 8080,
            onUrlReady = { url ->
                currentTunnelUrl = url
                updateNotification("Tunnel: $url/mcp")

                // Send URL to Telegram bot on IO thread
                scope.launch {
                    TelegramNotifier.notifyTunnelUrl(url)
                }
            },
            onStopped = {
                if (isRunning) {
                    updateNotification("Tunnel disconnected — restarting…")
                }
            }
        )
        tunnel?.start()
    }

    private fun stopEverything() {
        isRunning = false
        currentTunnelUrl = null
        tunnel?.stop()
        mcpServer?.stop()
        tunnel = null
        mcpServer = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, McpApplication.CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    override fun onDestroy() {
        super.onDestroy()
        stopEverything()
    }
}
