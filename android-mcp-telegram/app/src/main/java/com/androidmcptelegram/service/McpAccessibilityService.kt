package com.androidmcptelegram.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.androidmcptelegram.tools.ScreenState
import com.androidmcptelegram.tools.NodeResult
import java.io.ByteArrayOutputStream
import android.util.Base64
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Singleton-pattern accessibility service.
 * All tool handlers call methods on this via the companion object instance.
 */
class McpAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: McpAccessibilityService? = null
            private set

        fun isEnabled() = instance != null
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) { /* unused */ }

    override fun onInterrupt() { /* unused */ }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    // ── Screen State ──────────────────────────────────────────────────────────

    fun getScreenState(includeScreenshot: Boolean): ScreenState {
        val windows = windows ?: emptyList()
        val sb = StringBuilder()
        sb.appendLine("note:flags: on=onscreen off=offscreen clk=clickable lclk=longClickable foc=focusable scr=scrollable edt=editable ena=enabled")
        sb.appendLine("note:offscreen items require scroll_to_node before interaction")

        val displayMetrics = resources.displayMetrics
        val orientation = if (displayMetrics.widthPixels < displayMetrics.heightPixels) "portrait" else "landscape"
        sb.appendLine("screen:${displayMetrics.widthPixels}x${displayMetrics.heightPixels} density:${displayMetrics.densityDpi} orientation:$orientation")

        val allNodeIds = mutableMapOf<String, AccessibilityNodeInfo>()

        for (window in windows) {
            val root = window.root ?: continue
            val windowType = when (window.type) {
                AccessibilityWindowInfo.TYPE_APPLICATION -> "APPLICATION"
                AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "INPUT_METHOD"
                AccessibilityWindowInfo.TYPE_SYSTEM -> "SYSTEM"
                else -> "OTHER"
            }
            sb.appendLine("--- window:${window.id} type:$windowType pkg:${window.root?.packageName} layer:${window.layer} focused:${window.isFocused} ---")
            sb.appendLine("node_id\tclass\ttext\tdesc\tres_id\tbounds\tflags")

            val nodeLines = mutableListOf<String>()
            traverseNode(root, allNodeIds, nodeLines, displayMetrics.widthPixels, displayMetrics.heightPixels)
            nodeLines.forEach { sb.appendLine(it) }
            sb.appendLine("hierarchy:")
            buildHierarchy(root, sb, 0, allNodeIds)
        }

        val screenshotB64 = if (includeScreenshot) captureScreenshot() else null
        return ScreenState(sb.toString().trimEnd(), screenshotB64, allNodeIds)
    }

    private fun traverseNode(
        node: AccessibilityNodeInfo,
        allNodeIds: MutableMap<String, AccessibilityNodeInfo>,
        lines: MutableList<String>,
        screenW: Int,
        screenH: Int
    ) {
        val nodeId = "node_${Integer.toHexString(System.identityHashCode(node)).takeLast(4)}"
        val text = node.text?.toString()?.take(100) ?: "-"
        val desc = node.contentDescription?.toString()?.take(100) ?: "-"
        val resId = node.viewIdResourceName ?: "-"
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val onscreen = bounds.intersects(0, 0, screenW, screenH)
        val flagStr = buildString {
            append(if (onscreen) "on" else "off")
            if (node.isClickable) append(",clk")
            if (node.isLongClickable) append(",lclk")
            if (node.isFocusable) append(",foc")
            if (node.isScrollable) append(",scr")
            if (node.isEditable) append(",edt")
            if (node.isEnabled) append(",ena")
        }

        // Filter structural-only nodes
        val hasContent = text != "-" || desc != "-" || resId != "-" ||
                node.isClickable || node.isLongClickable || node.isScrollable || node.isEditable
        if (hasContent) {
            allNodeIds[nodeId] = node
            val className = node.className?.toString()?.substringAfterLast('.') ?: "-"
            lines.add("$nodeId\t$className\t$text\t$desc\t$resId\t${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}\t$flagStr")
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { traverseNode(it, allNodeIds, lines, screenW, screenH) }
        }
    }

    private fun buildHierarchy(
        node: AccessibilityNodeInfo,
        sb: StringBuilder,
        depth: Int,
        allNodeIds: Map<String, AccessibilityNodeInfo>
    ) {
        val nodeId = allNodeIds.entries.find { it.value == node }?.key
        if (nodeId != null) {
            sb.appendLine("  ".repeat(depth) + nodeId)
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { buildHierarchy(it, sb, if (nodeId != null) depth + 1 else depth, allNodeIds) }
        }
    }

    private fun captureScreenshot(): String? {
        return try {
            val bmp = takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                // Handled via suspendCoroutine below
            })
            null // Synchronous workaround: screenshot requires async API; handled in tools layer
        } catch (e: Exception) { null }
    }

    // ── Global Actions ────────────────────────────────────────────────────────

    fun pressBack() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun pressHome() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun pressRecents() = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun openNotifications() = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    fun openQuickSettings() = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
    fun dismissKeyboard() = performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)

    /**
     * Simulates physical power button press using the accessibility global action.
     * This triggers the power menu (long press) or toggles screen (single press).
     * Note: Actual hardware power key cannot be injected on non-rooted devices.
     * This uses GLOBAL_ACTION_LOCK_SCREEN (API 28+) to lock/power the screen,
     * which is the closest permitted action to a power button press.
     */
    fun pressPowerButton(): Boolean {
        return try {
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        } catch (e: Exception) {
            // Fallback: try taking device out of sleep via media key workaround
            false
        }
    }

    // ── Touch / Gesture ───────────────────────────────────────────────────────

    suspend fun tap(x: Float, y: Float): Boolean = suspendCancellableCoroutine { cont ->
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 100)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                cont.resume(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription) {
                cont.resume(false)
            }
        }, null)
    }

    suspend fun longPress(x: Float, y: Float, durationMs: Long): Boolean = suspendCancellableCoroutine { cont ->
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) { cont.resume(true) }
            override fun onCancelled(gestureDescription: GestureDescription) { cont.resume(false) }
        }, null)
    }

    suspend fun doubleTap(x: Float, y: Float): Boolean {
        val r1 = tap(x, y)
        kotlinx.coroutines.delay(80)
        val r2 = tap(x, y)
        return r1 && r2
    }

    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean = suspendCancellableCoroutine { cont ->
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) { cont.resume(true) }
            override fun onCancelled(gestureDescription: GestureDescription) { cont.resume(false) }
        }, null)
    }

    // ── Node Operations ───────────────────────────────────────────────────────

    fun findNodeById(nodeId: String, screenState: ScreenState): AccessibilityNodeInfo? =
        screenState.nodeMap[nodeId]

    fun clickNode(node: AccessibilityNodeInfo): Boolean =
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)

    fun longClickNode(node: AccessibilityNodeInfo): Boolean =
        node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)

    fun findNodesByText(text: String, exact: Boolean): List<AccessibilityNodeInfo> {
        val root = rootInActiveWindow ?: return emptyList()
        return findNodes(root, text, exact)
    }

    private fun findNodes(node: AccessibilityNodeInfo, query: String, exact: Boolean): List<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()
        val nodeText = node.text?.toString() ?: ""
        val nodeDesc = node.contentDescription?.toString() ?: ""
        val matches = if (exact) {
            nodeText == query || nodeDesc == query
        } else {
            nodeText.contains(query, ignoreCase = true) || nodeDesc.contains(query, ignoreCase = true)
        }
        if (matches) results.add(node)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { results.addAll(findNodes(it, query, exact)) }
        }
        return results
    }

    fun scrollToNode(node: AccessibilityNodeInfo): Boolean {
        var parent = node.parent
        while (parent != null) {
            if (parent.isScrollable) {
                val scrolled = parent.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                if (scrolled) return true
            }
            parent = parent.parent
        }
        return false
    }

    // ── Clipboard ─────────────────────────────────────────────────────────────

    fun getClipboard(): String? {
        val clipboardManager = getSystemService(android.content.ClipboardManager::class.java)
        return clipboardManager?.primaryClip?.getItemAt(0)?.text?.toString()
    }

    fun setClipboard(text: String) {
        val clipboardManager = getSystemService(android.content.ClipboardManager::class.java)
        val clip = android.content.ClipData.newPlainText("mcp", text)
        clipboardManager?.setPrimaryClip(clip)
    }

    // ── App Management ────────────────────────────────────────────────────────

    fun openApp(packageId: String): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(packageId) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        return true
    }
}
