package com.baisha.MicrosoftRewardsHelper

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Point
import java.io.File

/** 常用设备状态查询 / 操作，全部走 Shizuku 用户服务里的 shell */
object Device {

    private val PKG_ACTIVITY = Regex("([a-zA-Z0-9_.\$]+)/[.a-zA-Z0-9_\$]+")

    /** 当前前台包名，失败返回 null */
    fun foregroundPackage(context: Context): String? {
        val cmd =
            "dumpsys activity activities 2>/dev/null | grep -E 'mResumedActivity|topResumedActivity|mFocusedActivity' | head -5; " +
                "dumpsys window 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp' | head -5"
        val out = Shell.exec(context, cmd, 10_000).out
        for (line in out.lineSequence()) {
            val pkg = extractPkg(line) ?: continue
            if (pkg.startsWith("android") || pkg.startsWith("com.android.systemui")) continue
            return pkg
        }
        return null
    }

    private fun extractPkg(line: String): String? {
        val m = PKG_ACTIVITY.find(line) ?: return null
        val pkg = m.groupValues[1]
        if (!pkg.contains('.')) return null
        return pkg
    }

    /** 屏幕是否点亮 */
    fun isScreenOn(context: Context): Boolean {
        val out = Shell.exec(context, "dumpsys power 2>/dev/null | grep -E 'mWakefulness=|Display Power: state' | head -4", 8_000).out
        return out.contains("Awake") || out.contains("state=ON")
    }

    /** 屏幕分辨率 */
    fun screenSize(context: Context): Point? {
        val out = Shell.exec(context, "wm size 2>/dev/null | head -3", 8_000).out
        val m = Regex("(\\d+)x(\\d+)").find(out) ?: return null
        return Point(m.groupValues[1].toInt(), m.groupValues[2].toInt())
    }

    /** 点击：优先无障碍手势，回退 shell input tap */
    fun tap(context: Context, x: Int, y: Int) {
        if (A11y.connected() && A11y.tap(x, y)) return
        Shell.exec(context, "input tap $x $y", 8_000)
    }

    /** 滑动：优先无障碍手势，回退 shell input swipe */
    fun swipe(context: Context, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 400) {
        if (A11y.connected() && A11y.swipe(x1, y1, x2, y2, durationMs)) return
        Shell.exec(context, "input swipe $x1 $y1 $x2 $y2 $durationMs", 10_000)
    }

    /** 返回键：优先无障碍，回退 shell input keyevent */
    fun back(context: Context) {
        val svc = BingAccessibilityService.instance
        if (svc != null) {
            runCatching { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) }
            return
        }
        Shell.exec(context, "input keyevent 4", 8_000)
    }

    /** 把本应用拉回前台 */
    fun returnToApp(context: Context) {
        val intent = runCatching {
            context.packageManager.getLaunchIntentForPackage(context.packageName)
        }.getOrNull()
        if (intent != null) {
            intent.addFlags(
                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
            runCatching { context.startActivity(intent) }
            return
        }
        Shell.exec(context, "am start -n ${context.packageName}/.MainActivity", 8_000)
    }

    /** 启动目标应用：先试系统方式，不行再走 shell */
    fun launch(context: Context, pkg: String): Boolean {
        val intent = runCatching { context.packageManager.getLaunchIntentForPackage(pkg) }.getOrNull()
        if (intent != null) {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
            runCatching {
                context.startActivity(intent)
                return true
            }
        }
        val resolved = Shell.exec(context, "cmd package resolve-activity --brief $pkg 2>/dev/null | tail -1", 8_000)
            .out.trim()
        if (resolved.contains("/") && !resolved.startsWith("No") && !resolved.contains("Exception")) {
            val comp = if (resolved.startsWith("/")) "$pkg$resolved" else resolved
            if (comp.startsWith("$pkg/")) {
                Shell.exec(context, "am start -n $comp --activity-clear-top", 10_000)
                return true
            }
        }
        val r = Shell.exec(context, "monkey -p $pkg -c android.intent.category.LAUNCHER 1", 15_000)
        return !r.out.contains("No activities found")
    }

    /** 取当前界面的可见节点：优先无障碍，回退 uiautomator dump */
    fun nodes(context: Context): List<UiNode>? {
        if (A11y.connected()) {
            val a11yNodes = A11y.nodes()
            if (a11yNodes.isNotEmpty()) return a11yNodes
        }
        val svc = UserShell.get(context) ?: return null
        val summary = try {
            svc.dumpNodes(25_000)
        } catch (_: Throwable) {
            null
        } ?: return null
        if (summary.isEmpty()) return null
        return NodeSummary.parse(summary)
    }

    /** 对若干区域截图取色 */
    fun analyze(
        context: Context,
        rects: List<android.graphics.Rect>,
        refW: Int,
        refH: Int
    ): ScreenAnalyzer.Result? {
        val proxy = UserShell.get(context) ?: return null
        val text = try {
            proxy.analyze(RectSpec.format(rects), refW, refH)
        } catch (_: Throwable) {
            null
        } ?: return null
        if (text.isBlank()) return null
        return ScreenAnalyzer.parse(text)
    }

    /**
     * 截屏保存到应用可读的缓存目录（shell 进程写，应用进程读，用于 OCR 裁剪）。
     * @return 成功时返回该截图文件
     */
    fun captureToCache(context: Context): File? {
        val dir = context.externalCacheDir ?: return null
        val file = File(dir, "ocr/screen.png")
        val proxy = UserShell.get(context) ?: return null
        val out = try {
            proxy.capture(file.absolutePath, 20_000)
        } catch (_: Throwable) {
            null
        }
        if (out.isNullOrBlank() || !file.exists() || file.length() <= 0L) return null
        return file
    }

    /** 排查 uiautomator 取不到界面的原因，返回若干条 (标题, 输出) */
    fun diagnose(context: Context): List<Pair<String, String>> {
        val proxy = UserShell.get(context)
            ?: return listOf("用户服务" to "未连接 Shizuku 用户服务")
        fun run(cmd: String) = try {
            proxy.execAll(cmd, 15_000)?.trim() ?: "(null)"
        } catch (e: Throwable) {
            "异常：${e.message}"
        }
        val out = mutableListOf<Pair<String, String>>()
        out += "无障碍通道" to A11y.status(context)
        out += "无障碍节点数" to A11y.nodes().size.toString()
        out += "身份" to run("id")
        out += "系统版本" to run("getprop ro.build.version.sdk")
        out += "uiautomator 位置" to run("command -v uiautomator; ls -l /system/bin/uiautomator 2>&1 | head -2")
        out += "dump 直接输出" to run("uiautomator dump 2>&1 | head -c 300")
        out += "dump 到文件" to run("rm -f /data/local/tmp/bing_diag.xml; uiautomator dump /data/local/tmp/bing_diag.xml 2>&1 | head -c 300")
        out += "文件内容" to run("ls -l /data/local/tmp/bing_diag.xml 2>&1; head -c 200 /data/local/tmp/bing_diag.xml 2>&1")
        out += "compressed" to run("rm -f /data/local/tmp/bing_diag2.xml; uiautomator dump --compressed /data/local/tmp/bing_diag2.xml 2>&1 | head -c 200")
        out += "解析节点数" to (nodes(context)?.size?.toString() ?: "null")
        return out
    }

    /** 已安装包名列表 */
    fun installedPackages(context: Context): List<String> {
        val out = Shell.exec(context, "pm list packages 2>/dev/null", 20_000).out
        return out.lineSequence()
            .map { it.removePrefix("package:").trim() }
            .filter { it.isNotEmpty() }
            .toList()
    }
}
