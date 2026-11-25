package com.networkcontrol.android.automation.engine

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import li.gkd.selector.FastQuery
import li.gkd.selector.NodeAdapter

/**
 * 將 Android `AccessibilityNodeInfo` 適配給 `gkd-selector`。
 *
 * 單次匹配期間快取 Rect 與節點鍵值，避免對同一節點重複透過 Binder 讀取系統屬性。
 */
class AccessibilityNodeAdapter(
    private val appId: String,
) : NodeAdapter<AccessibilityNodeInfo>() {

    private val rectCache = HashMap<AccessibilityNodeInfo, Rect>()
    private val vidPrefix = "$appId:id/"

    private fun boundsOf(node: AccessibilityNodeInfo): Rect {
        return rectCache.getOrPut(node) {
            Rect().also { node.getBoundsInScreen(it) }
        }
    }

    override fun getName(node: AccessibilityNodeInfo): String? =
        node.className?.toString()

    override fun getChildCount(node: AccessibilityNodeInfo): Int =
        node.childCount

    override fun getChild(node: AccessibilityNodeInfo, index: Int): AccessibilityNodeInfo? =
        runCatching { node.getChild(index) }.getOrNull()

    override fun getParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        runCatching { node.parent }.getOrNull()

    override fun getNodeKey(node: AccessibilityNodeInfo): Any = node

    @Suppress("DEPRECATION")
    override fun getAttr(target: Any, name: String): Any? {
        val node = target as? AccessibilityNodeInfo ?: return null
        return when (name) {
            "id" -> node.viewIdResourceName
            "vid" -> {
                val fullId = node.viewIdResourceName ?: return null
                if (fullId.startsWith(vidPrefix)) {
                    fullId.substring(vidPrefix.length)
                } else {
                    val idx = fullId.indexOf(":id/")
                    if (idx >= 0) fullId.substring(idx + 4) else fullId
                }
            }
            "name" -> node.className?.toString()
            "text" -> node.text?.toString()
            "desc" -> node.contentDescription?.toString()
            "clickable" -> node.isClickable
            "focusable" -> node.isFocusable
            "checkable" -> node.isCheckable
            "checked" -> node.isChecked
            "editable" -> node.isEditable
            "enabled" -> node.isEnabled
            "visibleToUser" -> node.isVisibleToUser
            "scrollable" -> node.isScrollable
            "longClickable" -> node.isLongClickable
            "selected" -> node.isSelected
            "childCount" -> node.childCount
            "left" -> boundsOf(node).left
            "top" -> boundsOf(node).top
            "right" -> boundsOf(node).right
            "bottom" -> boundsOf(node).bottom
            "width" -> boundsOf(node).width()
            "height" -> boundsOf(node).height()
            "index" -> computeIndexInParent(node)
            "depth" -> computeDepth(node)
            else -> null
        }
    }

    override fun getFastQueryDescendants(
        node: AccessibilityNodeInfo,
        fastQueryList: List<FastQuery>,
    ): Sequence<AccessibilityNodeInfo> = sequence {
        val seen = HashSet<AccessibilityNodeInfo>()
        for (query in fastQueryList) {
            val found: List<AccessibilityNodeInfo> = when (query) {
                is FastQuery.Id -> runCatching {
                    node.findAccessibilityNodeInfosByViewId(query.value)
                }.getOrDefault(emptyList())
                is FastQuery.Vid -> runCatching {
                    node.findAccessibilityNodeInfosByViewId("$vidPrefix${query.value}")
                }.getOrDefault(emptyList())
                is FastQuery.Text -> runCatching {
                    node.findAccessibilityNodeInfosByText(query.value)
                }.getOrDefault(emptyList())
            }
            for (candidate in found) {
                if (candidate != node && seen.add(candidate)) {
                    yield(candidate)
                }
            }
        }
    }

    private fun computeIndexInParent(node: AccessibilityNodeInfo): Int {
        val parent = getParent(node) ?: return 0
        val count = parent.childCount
        for (i in 0 until count) {
            if (getChild(parent, i) == node) return i
        }
        return 0
    }

    private fun computeDepth(node: AccessibilityNodeInfo): Int {
        var depth = 0
        var cur = getParent(node)
        while (cur != null && depth < 64) {
            depth++
            cur = getParent(cur)
        }
        return depth
    }
}
