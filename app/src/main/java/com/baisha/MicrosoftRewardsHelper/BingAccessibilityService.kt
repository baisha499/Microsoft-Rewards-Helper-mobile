package com.baisha.MicrosoftRewardsHelper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * 备用（也是更可靠）的取界面/点击通道：
 * 某些系统的 uiautomator 不可用，无法 dump 界面树，此时用无障碍服务读取窗口内容并派发点击。
 * 通过 Shizuku 授予 WRITE_SECURE_SETTINGS 后由本应用自行开启，不需要用户手动去设置里点。
 */
class BingAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: BingAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    fun collectNodes(): List<UiNode> {
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return emptyList()
        val out = ArrayList<UiNode>(128)
        walk(root, out)
        return out
    }

    private fun walk(node: AccessibilityNodeInfo?, out: ArrayList<UiNode>) {
        if (node == null) return
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        if ((text.isNotEmpty() || desc.isNotEmpty()) && rect.width() > 0 && rect.height() > 0) {
            out.add(UiNode(text, desc, Rect(rect)))
        }
        runCatching {
            for (i in 0 until node.childCount) {
                walk(node.getChild(i), out)
            }
        }
    }

    fun tapAt(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        return try {
            dispatchGesture(gesture, null, null)
        } catch (_: Throwable) {
            false
        }
    }

    fun swipeAt(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return try {
            dispatchGesture(gesture, null, null)
        } catch (_: Throwable) {
            false
        }
    }

    // ---------- 搜索框输入（自动搜索任务用） ----------

    private fun walkEditables(node: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        runCatching {
            if (node.isEditable && node.isEnabled) out.add(node)
            for (i in 0 until node.childCount) {
                walkEditables(node.getChild(i), out)
            }
        }
    }

    private fun editableCandidates(): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        runCatching { walkEditables(rootInActiveWindow, out) }
        return out
    }

    /** 当前界面是否存在可编辑输入框 */
    fun hasEditable(): Boolean = editableCandidates().isNotEmpty()

    /** 把整段文字写入搜索输入框（优先已聚焦的那个），成功返回 true */
    fun setFieldText(text: String): Boolean {
        val candidates = editableCandidates()
        val target = candidates.firstOrNull { it.isFocused } ?: candidates.firstOrNull()
            ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return runCatching {
            target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }.getOrDefault(false)
    }

    /** 点击输入框使其获得焦点，已聚焦则直接返回 true */
    fun focusEditable(): Boolean {
        val candidates = editableCandidates()
        val target = candidates.firstOrNull { it.isFocused } ?: candidates.firstOrNull()
            ?: return false
        if (target.isFocused) return true
        val r = Rect()
        runCatching { target.getBoundsInScreen(r) }
        return tapAt(r.centerX(), r.centerY())
    }

    private fun collectAll(node: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        out.add(node)
        runCatching {
            for (i in 0 until node.childCount) {
                collectAll(node.getChild(i), out)
            }
        }
    }

    /** 点击输入法键盘上的"搜索"按钮；找不到返回 false（调用方可回退回车键） */
    fun clickImeSearch(): Boolean {
        return try {
            for (window in windows) {
                if (window.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
                val nodes = ArrayList<AccessibilityNodeInfo>()
                collectAll(window.root, nodes)
                for (n in nodes) {
                    val t = n.text?.toString() ?: ""
                    val d = n.contentDescription?.toString() ?: ""
                    val hit = t.contains("搜索") || d.contains("搜索") ||
                        t.equals("search", true) || d.equals("search", true)
                    if (!hit) continue
                    if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                    // 有的按键本体不可点击，点击其父容器
                    val parent = runCatching { n.parent }.getOrNull()
                    if (parent != null && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                }
            }
            false
        } catch (_: Throwable) {
            false
        }
    }
}
