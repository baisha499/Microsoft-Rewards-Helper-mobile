package com.baisha.MicrosoftRewardsHelper

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.TextUtils

/**
 * 无障碍通道的开关与调用。
 * uiautomator 在部分系统上完全不可用，这时靠它取界面、点击。
 */
object A11y {

    private const val SERVICE = "com.baisha.MicrosoftRewardsHelper/.BingAccessibilityService"
    private const val PKG = "com.baisha.MicrosoftRewardsHelper"

    /** 服务是否真的在运行（能取到 rootInActiveWindow） */
    fun connected(): Boolean = BingAccessibilityService.instance != null

    /** 系统设置里是否已把本服务列入并开启 */
    fun enabledInSettings(context: Context): Boolean {
        val list = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val on = Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0
        ) == 1
        if (!on) return false
        // 系统存的组件名是完整形式 pkg/pkg.cls，而 SERVICE 常量是缩写 pkg/.cls，
        // 直接 equals 会恒不匹配导致"服务已连接却显示未开启"。用 ComponentName 规范比较。
        val target = ComponentName.unflattenFromString(SERVICE) ?: return false
        return list.split(':').any { entry ->
            runCatching {
                val c = ComponentName.unflattenFromString(entry)
                c != null && c.packageName == target.packageName && c.className == target.className
            }.getOrDefault(false)
        }
    }

    /**
     * 用 Shizuku 授予 WRITE_SECURE_SETTINGS，把本服务**追加**到已启用的无障碍服务列表
     * （不能整列表覆盖，否则会把别的无障碍服务清掉，有些 ROM 检测到被篡改还会立刻还原），
     * 再打开总开关。
     * @return (设置里是否已生效, 排查用的详细信息)
     */
    fun enable(context: Context): Pair<Boolean, String> {
        if (!Shell.hasPermission()) return false to "没有 Shizuku 权限"
        val detail = StringBuilder()
        val grant = Shell.exec(context, "pm grant $PKG android.permission.WRITE_SECURE_SETTINGS", 8_000)
        detail.append("pm grant → out=${grant.out.trim()} err=${grant.err.trim()}\n")
        // 授权失败继续写也没用，直接告诉用户原因，别再往下走
        if (grant.err.trim().isNotEmpty() && grant.err.trim().contains("not", ignoreCase = true)) {
            val already = Shell.exec(context, "pm list permissions -g $PKG android.permission.WRITE_SECURE_SETTINGS 2>/dev/null", 8_000)
            detail.append("已授权检查 → out=${already.out.trim()}\n")
        }

        val before = readEnabledServices(context)
        detail.append("写入前：$before\n")
        val list = when {
            before.isBlank() || before == "null" -> SERVICE
            before.split(":").any { it.equals(SERVICE, true) } -> before
            else -> "$before:$SERVICE"
        }
        val put = Shell.exec(context, "settings put secure enabled_accessibility_services $list", 8_000)
        val putOn = Shell.exec(context, "settings put secure accessibility_enabled 1", 8_000)
        detail.append("写入值：$list\n")
        detail.append("put 服务 → err=${put.err.trim()}；put 总开关 → err=${putOn.err.trim()}\n")

        val after = readEnabledServices(context)
        val ok = enabledInSettings(context)
        detail.append("写入后：$after\n")
        detail.append("设置里已开启=$ok，服务已连接=${connected()}\n")
        return ok to detail.toString()
    }

    private fun readEnabledServices(context: Context): String =
        Shell.exec(context, "settings get secure enabled_accessibility_services", 8_000).out.trim()

    fun disable(context: Context) {
        if (!Shell.hasPermission()) return
        Shell.exec(context, "settings put secure enabled_accessibility_services ''", 8_000)
        Shell.exec(context, "settings put secure accessibility_enabled 0", 8_000)
    }

    fun nodes(): List<UiNode> {
        val svc = BingAccessibilityService.instance ?: return emptyList()
        return runCatching { svc.collectNodes() }.getOrDefault(emptyList())
    }

    fun tap(x: Int, y: Int): Boolean {
        val svc = BingAccessibilityService.instance ?: return false
        return svc.tapAt(x, y)
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
        val svc = BingAccessibilityService.instance ?: return false
        return svc.swipeAt(x1, y1, x2, y2, durationMs)
    }

    /** 当前界面是否存在可编辑输入框 */
    fun hasEditable(): Boolean {
        val svc = BingAccessibilityService.instance ?: return false
        return svc.hasEditable()
    }

    /** 把整段文字写入搜索输入框（自动搜索任务逐字输入用） */
    fun setFieldText(text: String): Boolean {
        val svc = BingAccessibilityService.instance ?: return false
        return svc.setFieldText(text)
    }

    /** 点击输入框使其获得焦点 */
    fun focusEditable(): Boolean {
        val svc = BingAccessibilityService.instance ?: return false
        return svc.focusEditable()
    }

    /** 点击输入法键盘上的"搜索"按钮 */
    fun clickImeSearch(): Boolean {
        val svc = BingAccessibilityService.instance ?: return false
        return svc.clickImeSearch()
    }

    fun status(context: Context): String =
        "设置中已开启=${enabledInSettings(context)}，服务已连接=${connected()}"
}
