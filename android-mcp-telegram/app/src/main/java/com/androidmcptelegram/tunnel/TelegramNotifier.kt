package com.androidmcptelegram.tunnel

import com.androidmcptelegram.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Sends the Cloudflare tunnel URL to the user's Telegram bot.
 *
 * Credentials come from BuildConfig (injected from local.properties at compile time).
 * They are NOT in strings.xml or any resource table — they live in bytecode only.
 * Release builds apply R8 obfuscation which further obscures field references.
 */
object TelegramNotifier {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Send a message to the configured Telegram chat.
     * Returns true on HTTP 200, false otherwise.
     */
    fun send(text: String): Boolean {
        val token = BuildConfig.TELEGRAM_BOT_TOKEN
        val chatId = BuildConfig.TELEGRAM_CHAT_ID

        if (token.isBlank() || token == "YOUR_BOT_TOKEN_HERE") return false
        if (chatId.isBlank() || chatId == "YOUR_CHAT_ID_HERE") return false

        val body = JSONObject().apply {
            put("chat_id", chatId)
            put("text", text)
            put("parse_mode", "HTML")
        }.toString()

        val request = Request.Builder()
            .url("https://api.telegram.org/bot$token/sendMessage")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Formats and sends the MCP URL announcement message.
     */
    fun notifyTunnelUrl(tunnelUrl: String) {
        val mcpUrl = "$tunnelUrl/mcp"
        val message = """
            🤖 <b>Android MCP Server Started</b>
            
            Tunnel URL ready:
            <code>$mcpUrl</code>
            
            Paste this in Claude.ai:
            Customize → Connectors → Add custom connector → paste URL above
        """.trimIndent()
        send(message)
    }
}
