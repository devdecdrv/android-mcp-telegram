package com.androidmcptelegram.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val serviceIntent = Intent(context, McpService::class.java).apply {
                action = McpService.ACTION_START
            }
            context.startForegroundService(serviceIntent)
        }
    }
}
