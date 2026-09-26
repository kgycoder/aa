package com.kgycoder.lmarena.work

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * lm_arena_cli/computer.py 가 Windows에서 SendInput + UI Automation으로 하던 일을
 * 안드로이드 공식 API인 AccessibilityService로 옮긴 것.
 *
 * - 화면 어디든 탭/드래그/스크롤: dispatchGesture (Win32 SendInput에 대응, 시스템 전역으로 동작)
 * - 현재 포그라운드 앱의 UI 트리 관찰: rootInActiveWindow (Win32 UI Automation 덤프에 대응)
 * - 포커스된 입력창에 문자 넣기: ACTION_SET_TEXT (SendInput의 유니코드 키 입력보다 오히려 더 안정적)
 *
 * 사용자가 설정 > 접근성에서 이 서비스를 켜야만 동작한다(Work 모드를 켤 때 앱이 안내한다).
 */
class WorkAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: WorkAccessibilityService? = null
        private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun screenSize(): Pair<Int, Int> {
        val dm = resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    // ── 제스처 ────────────────────────────────────────────────────
    private suspend fun runGesture(gesture: GestureDescription): Boolean = suspendCancellableCoroutine { cont ->
        mainHandler.post {
            try {
                val ok = dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                }, null)
                if (!ok && cont.isActive) cont.resume(false)
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(false)
            }
        }
    }

    suspend fun tap(x: Int, y: Int, clicks: Int = 1): Boolean {
        var ok = true
        repeat(clicks) {
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val stroke = GestureDescription.StrokeDescription(path, 0, 60)
            ok = runGesture(GestureDescription.Builder().addStroke(stroke).build()) && ok
            if (clicks > 1) kotlinx.coroutines.delay(90)
        }
        return ok
    }

    suspend fun longPress(x: Int, y: Int, durationMs: Long = 550): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        return runGesture(GestureDescription.Builder().addStroke(stroke).build())
    }

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300): Boolean {
        val path = Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        return runGesture(GestureDescription.Builder().addStroke(stroke).build())
    }

    suspend fun scrollAt(x: Int, y: Int, dy: Int): Boolean {
        val duration = 260L
        return swipe(x, y, x, (y - dy).coerceIn(0, screenSize().second), duration)
    }

    // ── UI 트리 관찰 ─────────────────────────────────────────────
    fun dumpTree(maxNodes: Int = 160, maxDepth: Int = 10): JSONObject? {
        val root = rootInActiveWindow ?: return null
        val count = intArrayOf(0)
        val result = dumpNode(root, 0, maxDepth, maxNodes, count)
        root.recycle()
        return result
    }

    private fun dumpNode(node: AccessibilityNodeInfo, depth: Int, maxDepth: Int, maxNodes: Int, count: IntArray): JSONObject? {
        if (count[0] >= maxNodes) return null
        count[0]++
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val name = (node.text?.toString() ?: node.contentDescription?.toString() ?: "").let {
            if (it.length > 80) it.substring(0, 80) else it
        }
        val obj = JSONObject()
            .put("n", name)
            .put("t", node.className?.toString() ?: "")
            .put("x", bounds.left).put("y", bounds.top)
            .put("w", bounds.width()).put("h", bounds.height())
            .put("clickable", node.isClickable)
            .put("editable", node.isEditable)
            .put("focused", node.isFocused)
            .put("id", node.viewIdResourceName ?: "")
        if (depth < maxDepth) {
            val children = JSONArray()
            for (i in 0 until node.childCount) {
                if (count[0] >= maxNodes) break
                val child = node.getChild(i) ?: continue
                if (child.isVisibleToUser) {
                    val d = dumpNode(child, depth + 1, maxDepth, maxNodes, count)
                    if (d != null) children.put(d)
                }
                child.recycle()
            }
            if (children.length() > 0) obj.put("c", children)
        }
        return obj
    }

    fun foregroundPackage(): String? = rootInActiveWindow?.packageName?.toString()

    // ── 입력 ─────────────────────────────────────────────────────
    fun typeIntoFocused(text: String): Boolean {
        val node = findFocusedEditable() ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        node.recycle()
        return ok
    }

    private fun findFocusedEditable(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        root.recycle()
        return focused
    }

    fun clickByName(name: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = findByText(root, name)
        val ok = target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
        target?.let { if (it !== root) it.recycle() }
        root.recycle()
        return ok
    }

    private fun findByText(node: AccessibilityNodeInfo, needle: String, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 25) return null
        val text = node.text?.toString() ?: node.contentDescription?.toString()
        if (text != null && text.contains(needle, ignoreCase = true)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val hit = findByText(child, needle, depth + 1)
            if (hit != null) {
                if (hit !== child) child.recycle()
                return hit
            }
            child.recycle()
        }
        return null
    }

    fun globalAction(action: Int): Boolean = performGlobalAction(action)

    fun getClipboard(): String {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip ?: return ""
        if (clip.itemCount == 0) return ""
        return clip.getItemAt(0).coerceToText(this).toString()
    }

    fun setClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("lm-arena-work", text))
    }
}
