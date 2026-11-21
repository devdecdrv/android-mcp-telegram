package com.androidmcptelegram.tunnel

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Manages a Cloudflare Quick Tunnel (cloudflared trycloudflare.com).
 *
 * Strategy:
 *  1. Copy bundled cloudflared binary from assets to app's internal files dir.
 *  2. chmod +x and exec it with `--url http://127.0.0.1:8080`.
 *  3. Parse stdout/stderr for the *.trycloudflare.com URL.
 *  4. Call [onUrlReady] callback, which notifies Telegram.
 *
 * NOTE: The cloudflared ARM64 binary must be placed in:
 *   app/src/main/assets/cloudflared-arm64
 *   app/src/main/assets/cloudflared-x86_64
 * Download from https://github.com/cloudflare/cloudflared/releases
 * and rename accordingly.
 */
class CloudflareTunnel(
    private val context: Context,
    private val port: Int = 8080,
    private val onUrlReady: (String) -> Unit,
    private val onStopped: () -> Unit
) {
    companion object {
        private const val TAG = "CloudflareTunnel"
        private val URL_REGEX = Regex("""https://[a-z0-9-]+\.trycloudflare\.com""")
    }

    @Volatile private var process: Process? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true

        Thread {
            try {
                val binary = extractBinary() ?: run {
                    Log.e(TAG, "cloudflared binary not found in assets")
                    onStopped()
                    return@Thread
                }

                val cmd = arrayOf(binary.absolutePath, "tunnel", "--url", "http://127.0.0.1:$port")
                val proc = Runtime.getRuntime().exec(cmd)
                process = proc

                // Parse output for URL
                proc.errorStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        Log.d(TAG, line)
                        val match = URL_REGEX.find(line)
                        if (match != null) {
                            val url = match.value
                            Log.i(TAG, "Tunnel URL: $url")
                            onUrlReady(url)
                        }
                        if (!running) break
                    }
                }

                proc.waitFor()
            } catch (e: Exception) {
                Log.e(TAG, "Tunnel error: ${e.message}")
            } finally {
                running = false
                onStopped()
            }
        }.also { it.isDaemon = true }.start()
    }

    fun stop() {
        running = false
        process?.destroy()
        process = null
    }

    private fun extractBinary(): File? {
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: return null
        val assetName = when {
            abi.startsWith("arm64") -> "cloudflared-arm64"
            abi.startsWith("x86_64") -> "cloudflared-x86_64"
            abi.startsWith("armeabi") -> "cloudflared-arm"
            else -> return null
        }

        val outFile = File(context.filesDir, "cloudflared")
        return try {
            context.assets.open(assetName).use { input ->
                outFile.outputStream().use { output -> input.copyTo(output) }
            }
            outFile.setExecutable(true)
            outFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract binary '$assetName': ${e.message}")
            null
        }
    }
}
