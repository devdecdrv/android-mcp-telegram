package com.androidmcptelegram.tools

import android.view.accessibility.AccessibilityNodeInfo

data class ScreenState(
    val tsvText: String,
    val screenshotBase64: String?,
    val nodeMap: Map<String, AccessibilityNodeInfo>
)

data class NodeResult(
    val nodeId: String,
    val text: String?,
    val contentDescription: String?,
    val resourceId: String?,
    val className: String?,
    val bounds: Map<String, Int>,
    val clickable: Boolean,
    val longClickable: Boolean,
    val scrollable: Boolean,
    val editable: Boolean,
    val enabled: Boolean
)
