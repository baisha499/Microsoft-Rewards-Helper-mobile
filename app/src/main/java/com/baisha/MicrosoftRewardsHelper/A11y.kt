package com.baisha.MicrosoftRewardsHelper

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
        return on && list.split(':').any { it.equals(SERVICE, true) }
    }

    /**
     * 用 Shizuku 授予 WRITE_SECURE_SETTINGS 并写入 enabled_accessibility_services，
     * 无需用户手动到设置里点。
     */
    fun enable(context: Context): Boolean {
        if (!Shell.hasPermission()) return false
        val cmds = listOf(
            "pm grant $PKG android.permission.WRITE_SECURE_SETTINGS",
            "settings put secure enabled_accessibility_services $SERVICE",
            "settings put secure accessibility_enabled 1"
        )
        cmds.forEach { Shell.exec(context, it, 8_000) }
        return enabledInSettings(context)
    }

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
