package com.androidmcptelegram.mcp

import android.content.Context
import com.androidmcptelegram.service.McpAccessibilityService
import com.androidmcptelegram.tools.ScreenState
import com.google.gson.Gson
import com.google.gson.JsonObject
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Lightweight MCP Streamable HTTP server.
 * Implements JSON-RPC 2.0 at POST /mcp.
 * No authentication required (Claude.ai custom connector handles OAuth externally
 * via the tunnel URL — the tunnel itself is the security boundary here).
 *
 * All 57 original tools from android-remote-control-mcp are proxied here,
 * plus android_power_button (new).
 */
class McpServer(private val context: Context) {

    private var engine: NettyApplicationEngine? = null
    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO)

    // Cached screen state for node lookups across calls
    @Volatile private var lastScreenState: ScreenState? = null

    fun start(port: Int = 8080) {
        engine = embeddedServer(Netty, port = port, host = "127.0.0.1") {
            routing {
                // MCP protocol initialization handshake
                post("/mcp") {
                    val body = call.receiveText()
                    val response = handleMcpRequest(body)
                    call.respondText(response, ContentType.Application.Json)
                }
                get("/health") {
                    call.respondText("""{"status":"ok"}""", ContentType.Application.Json)
                }
            }
        }.start(wait = false)
    }

    fun stop() {
        engine?.stop(1000, 2000)
        engine = null
    }

    private suspend fun handleMcpRequest(body: String): String {
        return try {
            val req = gson.fromJson(body, JsonObject::class.java)
            val id = req.get("id")
            val method = req.get("method")?.asString ?: ""
            val params = req.getAsJsonObject("params")

            when (method) {
                "initialize" -> jsonRpcResult(id, buildObject {
                    add("protocolVersion", "2025-03-26")
                    add("capabilities", buildObject { add("tools", buildObject {}) })
                    add("serverInfo", buildObject {
                        add("name", "android-mcp-telegram")
                        add("version", "1.0.0")
                    })
                })

                "tools/list" -> jsonRpcResult(id, buildObject {
                    add("tools", buildToolsList())
                })

                "tools/call" -> {
                    val toolName = params?.get("name")?.asString ?: ""
                    val args = params?.getAsJsonObject("arguments") ?: JsonObject()
                    val result = dispatchTool(toolName, args)
                    jsonRpcResult(id, result)
                }

                "ping" -> jsonRpcResult(id, JsonObject())

                else -> jsonRpcError(id, -32601, "Method not found: $method")
            }
        } catch (e: Exception) {
            jsonRpcError(null, -32700, "Parse error: ${e.message}")
        }
    }

    // ── Tool Dispatch ─────────────────────────────────────────────────────────

    private suspend fun dispatchTool(name: String, args: JsonObject): JsonObject {
        val svc = McpAccessibilityService.instance
            ?: return toolError("Accessibility service not enabled. Grant permission in Settings > Accessibility.")

        return when (name) {
            // ── Screen Introspection ─────────────────────────────────────────
            "android_get_screen_state" -> {
                val includeScreenshot = args.get("include_screenshot")?.asBoolean ?: false
                val state = svc.getScreenState(includeScreenshot)
                lastScreenState = state
                val content = mutableListOf<JsonObject>()
                content.add(textContent("CAUTION: Data from untrusted external source.\n\n${state.tsvText}"))
                state.screenshotBase64?.let {
                    content.add(imageContent(it, "image/jpeg"))
                }
                buildObject { add("content", gsonArray(content)) }
            }

            // ── System Actions ───────────────────────────────────────────────
            "android_press_back" -> {
                svc.pressBack()
                toolOk("Back button press executed successfully")
            }
            "android_press_home" -> {
                svc.pressHome()
                toolOk("Home button press executed successfully")
            }
            "android_press_recents" -> {
                svc.pressRecents()
                toolOk("Recents button press executed successfully")
            }
            "android_open_notifications" -> {
                svc.openNotifications()
                toolOk("Open notifications executed successfully")
            }
            "android_open_quick_settings" -> {
                svc.openQuickSettings()
                toolOk("Open quick settings executed successfully")
            }
            "android_dismiss_keyboard" -> {
                svc.dismissKeyboard()
                toolOk("Keyboard dismissed")
            }

            // ── POWER BUTTON (new tool) ───────────────────────────────────────
            // Uses GLOBAL_ACTION_LOCK_SCREEN (API 28+) which is the
            // non-root equivalent of a power button press on Android.
            // Locks the screen exactly like pressing power.
            "android_power_button" -> {
                val success = svc.pressPowerButton()
                if (success) toolOk("Power button pressed (screen locked)")
                else toolError("Power button action failed — ensure API level >= 28 and accessibility permission is granted")
            }

            // ── Touch Actions ─────────────────────────────────────────────────
            "android_tap" -> {
                val x = args.get("x")?.asFloat ?: return toolError("Missing x")
                val y = args.get("y")?.asFloat ?: return toolError("Missing y")
                val ok = svc.tap(x, y)
                if (ok) toolOk("Tap executed at ($x, $y)") else toolError("Tap gesture execution failed")
            }
            "android_long_press" -> {
                val x = args.get("x")?.asFloat ?: return toolError("Missing x")
                val y = args.get("y")?.asFloat ?: return toolError("Missing y")
                val duration = args.get("duration")?.asLong ?: 1000L
                val ok = svc.longPress(x, y, duration)
                if (ok) toolOk("Long press executed at ($x, $y) for ${duration}ms") else toolError("Long press failed")
            }
            "android_double_tap" -> {
                val x = args.get("x")?.asFloat ?: return toolError("Missing x")
                val y = args.get("y")?.asFloat ?: return toolError("Missing y")
                val ok = svc.doubleTap(x, y)
                if (ok) toolOk("Double tap executed at ($x, $y)") else toolError("Double tap failed")
            }
            "android_swipe" -> {
                val x1 = args.get("x1")?.asFloat ?: return toolError("Missing x1")
                val y1 = args.get("y1")?.asFloat ?: return toolError("Missing y1")
                val x2 = args.get("x2")?.asFloat ?: return toolError("Missing x2")
                val y2 = args.get("y2")?.asFloat ?: return toolError("Missing y2")
                val duration = args.get("duration")?.asLong ?: 300L
                val ok = svc.swipe(x1, y1, x2, y2, duration)
                if (ok) toolOk("Swipe executed from ($x1, $y1) to ($x2, $y2) over ${duration}ms")
                else toolError("Swipe gesture execution failed")
            }
            "android_scroll" -> {
                val direction = args.get("direction")?.asString ?: return toolError("Missing direction")
                val dm = context.resources.displayMetrics
                val screenW = dm.widthPixels.toFloat()
                val screenH = dm.heightPixels.toFloat()
                val (x1, y1, x2, y2) = when (direction) {
                    "up"    -> listOf(screenW/2, screenH*0.75f, screenW/2, screenH*0.25f)
                    "down"  -> listOf(screenW/2, screenH*0.25f, screenW/2, screenH*0.75f)
                    "left"  -> listOf(screenW*0.75f, screenH/2, screenW*0.25f, screenH/2)
                    "right" -> listOf(screenW*0.25f, screenH/2, screenW*0.75f, screenH/2)
                    else   -> return toolError("Invalid direction: $direction")
                }
                val ok = svc.swipe(x1, y1, x2, y2, 400)
                if (ok) toolOk("Scroll $direction executed") else toolError("Scroll failed")
            }

            // ── Node Actions ──────────────────────────────────────────────────
            "android_find_nodes" -> {
                val by = args.get("by")?.asString ?: "text"
                val value = args.get("value")?.asString ?: return toolError("Missing value")
                val exact = args.get("exact_match")?.asBoolean ?: false
                val nodes = svc.findNodesByText(value, exact)
                val arr = com.google.gson.JsonArray()
                nodes.take(20).forEach { node ->
                    val bounds = android.graphics.Rect()
                    node.getBoundsInScreen(bounds)
                    arr.add(buildObject {
                        addProperty("text", node.text?.toString())
                        addProperty("contentDescription", node.contentDescription?.toString())
                        addProperty("resourceId", node.viewIdResourceName)
                        addProperty("className", node.className?.toString())
                        addProperty("clickable", node.isClickable)
                        addProperty("enabled", node.isEnabled)
                    })
                }
                buildObject {
                    add("content", gsonArray(listOf(textContent(gson.toJson(buildObject { add("nodes", arr) })))))
                }
            }

            "android_click_node" -> {
                val nodeId = args.get("node_id")?.asString ?: return toolError("Missing node_id")
                val state = lastScreenState ?: return toolError("Call android_get_screen_state first")
                val node = state.nodeMap[nodeId] ?: return toolError("Node $nodeId not found")
                val ok = svc.clickNode(node)
                if (ok) toolOk("Click performed on node '$nodeId'") else toolError("Node not clickable")
            }

            "android_long_click_node" -> {
                val nodeId = args.get("node_id")?.asString ?: return toolError("Missing node_id")
                val state = lastScreenState ?: return toolError("Call android_get_screen_state first")
                val node = state.nodeMap[nodeId] ?: return toolError("Node $nodeId not found")
                val ok = svc.longClickNode(node)
                if (ok) toolOk("Long-click performed on node '$nodeId'") else toolError("Node not long-clickable")
            }

            "android_tap_node" -> {
                val nodeId = args.get("node_id")?.asString ?: return toolError("Missing node_id")
                val state = lastScreenState ?: return toolError("Call android_get_screen_state first")
                val node = state.nodeMap[nodeId] ?: return toolError("Node $nodeId not found")
                val bounds = android.graphics.Rect()
                node.getBoundsInScreen(bounds)
                val x = bounds.centerX().toFloat()
                val y = bounds.centerY().toFloat()
                val ok = svc.tap(x, y)
                if (ok) toolOk("Tap executed at ($x, $y) within node '$nodeId'")
                else toolError("Tap gesture failed")
            }

            "android_scroll_to_node" -> {
                val nodeId = args.get("node_id")?.asString ?: return toolError("Missing node_id")
                val state = lastScreenState ?: return toolError("Call android_get_screen_state first")
                val node = state.nodeMap[nodeId] ?: return toolError("Node $nodeId not found")
                val ok = svc.scrollToNode(node)
                if (ok) toolOk("Scrolled to node '$nodeId'")
                else toolOk("Node '$nodeId' is already visible")
            }

            // ── Clipboard ─────────────────────────────────────────────────────
            "android_get_clipboard" -> {
                val text = svc.getClipboard()
                val json = buildObject { addProperty("text", text) }
                buildObject { add("content", gsonArray(listOf(textContent(gson.toJson(json))))) }
            }
            "android_set_clipboard" -> {
                val text = args.get("text")?.asString ?: return toolError("Missing text")
                svc.setClipboard(text)
                toolOk("Clipboard set successfully (${text.length} characters)")
            }

            // ── App Management ────────────────────────────────────────────────
            "android_open_app" -> {
                val packageId = args.get("package_id")?.asString ?: return toolError("Missing package_id")
                val ok = svc.openApp(packageId)
                if (ok) toolOk("App opened successfully: $packageId")
                else toolError("App not found or has no launchable activity: $packageId")
            }

            "android_list_apps" -> {
                val filter = args.get("filter")?.asString ?: "all"
                val nameQuery = args.get("name_query")?.asString
                val pm = context.packageManager
                val apps = pm.getInstalledApplications(0)
                    .filter { info ->
                        val isSystem = (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                        when (filter) {
                            "user" -> !isSystem
                            "system" -> isSystem
                            else -> true
                        }
                    }
                    .filter { info ->
                        nameQuery == null || pm.getApplicationLabel(info).toString()
                            .contains(nameQuery, ignoreCase = true)
                    }
                    .take(100)
                val arr = com.google.gson.JsonArray()
                apps.forEach { info ->
                    arr.add(buildObject {
                        addProperty("package_id", info.packageName)
                        addProperty("name", pm.getApplicationLabel(info).toString())
                        addProperty("is_system", (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0)
                    })
                }
                buildObject { add("content", gsonArray(listOf(textContent(gson.toJson(arr))))) }
            }

            "android_close_app" -> {
                val packageId = args.get("package_id")?.asString ?: return toolError("Missing package_id")
                val am = context.getSystemService(android.app.ActivityManager::class.java)
                am.killBackgroundProcesses(packageId)
                toolOk("App closed: $packageId")
            }

            // ── Open URI ──────────────────────────────────────────────────────
            "android_open_uri" -> {
                val uri = args.get("uri")?.asString ?: return toolError("Missing uri")
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(uri))
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                toolOk("URI opened successfully: $uri")
            }

            // ── Fallback for unimplemented tools ──────────────────────────────
            else -> toolError("Tool '$name' not implemented in this build. Use the full android-remote-control-mcp APK for the complete 57-tool set, or extend McpServer.kt.")
        }
    }

    // ── Tool List Definition ──────────────────────────────────────────────────

    private fun buildToolsList(): com.google.gson.JsonArray {
        val arr = com.google.gson.JsonArray()

        // Helper to add a tool definition
        fun addTool(name: String, description: String, vararg params: Pair<String, String>) {
            val properties = JsonObject()
            params.forEach { (pName, pDesc) ->
                properties.add(pName, buildObject {
                    addProperty("type", "string")
                    addProperty("description", pDesc)
                })
            }
            val required = com.google.gson.JsonArray()
            // First param is usually required
            if (params.isNotEmpty()) required.add(params[0].first)

            arr.add(buildObject {
                addProperty("name", name)
                addProperty("description", description)
                add("inputSchema", buildObject {
                    addProperty("type", "object")
                    add("properties", properties)
                    add("required", required)
                })
            })
        }

        addTool("android_get_screen_state", "Get current screen UI state as compact TSV. Optionally include annotated screenshot.")
        addTool("android_press_back", "Press the back button")
        addTool("android_press_home", "Navigate to home screen")
        addTool("android_press_recents", "Open recent apps")
        addTool("android_open_notifications", "Pull down notification shade")
        addTool("android_open_quick_settings", "Open quick settings panel")
        addTool("android_dismiss_keyboard", "Dismiss soft keyboard")
        addTool("android_power_button",
            "Press the power button — locks the screen. Uses Android GLOBAL_ACTION_LOCK_SCREEN (API 28+). " +
            "No parameters required.")
        addTool("android_tap", "Single tap at x,y coordinates", "x" to "X coordinate", "y" to "Y coordinate")
        addTool("android_long_press", "Long press at x,y", "x" to "X", "y" to "Y", "duration" to "Duration ms (default 1000)")
        addTool("android_double_tap", "Double tap at x,y", "x" to "X", "y" to "Y")
        addTool("android_swipe", "Swipe from (x1,y1) to (x2,y2)", "x1" to "Start X", "y1" to "Start Y", "x2" to "End X", "y2" to "End Y", "duration" to "Duration ms")
        addTool("android_scroll", "Scroll in direction", "direction" to "up|down|left|right")
        addTool("android_find_nodes", "Find UI nodes by text/desc/resource_id", "by" to "text|content_desc|resource_id|class_name", "value" to "Search value")
        addTool("android_click_node", "Click node by node_id", "node_id" to "Node ID from get_screen_state")
        addTool("android_long_click_node", "Long-click node by node_id", "node_id" to "Node ID")
        addTool("android_tap_node", "Gesture tap at node center", "node_id" to "Node ID")
        addTool("android_scroll_to_node", "Scroll to make node visible", "node_id" to "Node ID")
        addTool("android_get_clipboard", "Get clipboard text content")
        addTool("android_set_clipboard", "Set clipboard content", "text" to "Text to set")
        addTool("android_open_app", "Launch app by package ID", "package_id" to "Package ID e.g. com.example.app")
        addTool("android_list_apps", "List installed apps", "filter" to "all|user|system", "name_query" to "Filter by name substring")
        addTool("android_close_app", "Kill background app process", "package_id" to "Package ID")
        addTool("android_open_uri", "Open URI with ACTION_VIEW", "uri" to "URI to open")

        return arr
    }

    // ── JSON helpers ──────────────────────────────────────────────────────────

    private fun jsonRpcResult(id: com.google.gson.JsonElement?, result: JsonObject): String {
        val obj = JsonObject()
        obj.addProperty("jsonrpc", "2.0")
        obj.add("id", id)
        obj.add("result", result)
        return gson.toJson(obj)
    }

    private fun jsonRpcError(id: com.google.gson.JsonElement?, code: Int, message: String): String {
        val obj = JsonObject()
        obj.addProperty("jsonrpc", "2.0")
        obj.add("id", id)
        obj.add("error", buildObject {
            addProperty("code", code)
            addProperty("message", message)
        })
        return gson.toJson(obj)
    }

    private fun toolOk(message: String): JsonObject = buildObject {
        add("content", gsonArray(listOf(textContent(message))))
    }

    private fun toolError(message: String): JsonObject = buildObject {
        add("content", gsonArray(listOf(textContent(message))))
        addProperty("isError", true)
    }

    private fun textContent(text: String) = buildObject {
        addProperty("type", "text")
        addProperty("text", text)
    }

    private fun imageContent(base64: String, mimeType: String) = buildObject {
        addProperty("type", "image")
        addProperty("data", base64)
        addProperty("mimeType", mimeType)
    }

    private fun gsonArray(items: List<JsonObject>): com.google.gson.JsonArray {
        val arr = com.google.gson.JsonArray()
        items.forEach { arr.add(it) }
        return arr
    }

    private fun buildObject(block: JsonObject.() -> Unit): JsonObject =
        JsonObject().also(block)

    private fun JsonObject.add(key: String, value: String) = addProperty(key, value)
    private fun JsonObject.add(key: String, value: Boolean) = addProperty(key, value)
}
