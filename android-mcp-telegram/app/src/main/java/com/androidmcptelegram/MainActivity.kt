package com.androidmcptelegram

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import com.androidmcptelegram.databinding.ActivityMainBinding
import com.androidmcptelegram.service.McpService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var serverRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnToggle.setOnClickListener {
            if (serverRunning) stopServer() else startServer()
        }

        binding.btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun startServer() {
        val intent = Intent(this, McpService::class.java).apply {
            action = McpService.ACTION_START
        }
        startForegroundService(intent)
        serverRunning = true
        binding.btnToggle.text = "Stop Server"
        binding.tvStatus.text = "Starting tunnel…"
    }

    private fun stopServer() {
        val intent = Intent(this, McpService::class.java).apply {
            action = McpService.ACTION_STOP
        }
        startService(intent)
        serverRunning = false
        binding.btnToggle.text = "Start Server"
        binding.tvStatus.text = "Server stopped"
        binding.tvUrl.text = "—"
    }

    override fun onResume() {
        super.onResume()
        // Reflect actual service state
        serverRunning = McpService.isRunning
        binding.btnToggle.text = if (serverRunning) "Stop Server" else "Start Server"
        binding.tvStatus.text = if (serverRunning) "Server running" else "Server stopped"
        binding.tvUrl.text = McpService.currentTunnelUrl ?: "—"
    }
}
