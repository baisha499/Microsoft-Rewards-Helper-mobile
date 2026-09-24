package com.baisha.MicrosoftRewardsHelper

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.util.Locale

data class StepConfig(
    /** 匹配节点文本或 content-desc 的关键词，按优先级排列 */
    val keywords: List<String>,
    /** 关键词都找不到时的备选坐标，支持 "990,2270" 或 "9%,6%"（相对屏幕） */
    val fallbackRaw: String? = null
)

data class AppConfig(
    val packages: List<String>,
    val homeStep: StepConfig,
    val pointsStep: StepConfig,
    val checkinStep: StepConfig,
    val dayThreshold: Int,
    val pollSec: Int,
    /** 自动搜索：每次搜索后的等待时间（秒） */
    val searchWaitSec: Int,
    /** 每日活动：点击 +10 后的等待时间（秒） */
    val dailyWaitSec: Int,
    val oncePerDay: Boolean
) {
    companion object {
        const val DEFAULT_PACKAGES = "com.microsoft.bing"
        /** 第 1 步：左上角用户头像，直接用坐标，不做关键词匹配 */
        const val DEFAULT_HOME = ""
        const val DEFAULT_HOME_XY = "112,192"
        /** 第 2 步：Microsoft Rewards */
        const val DEFAULT_POINTS = "Microsoft Rewards|Rewards|微软奖励|奖励"
        /** 第 3 步：签入页 */
        const val DEFAULT_CHECKIN = "签入|签到|Check in|Check-in"
        const val DEFAULT_DAY_THRESHOLD = 22
        const val DEFAULT_POLL_SEC = 3
        const val DEFAULT_SEARCH_WAIT_SEC = 5
        const val DEFAULT_DAILY_WAIT_SEC = 5
    }
}

object Config {

    private const val PREF = "bing_checkin"
    private const val KEY_PACKAGES = "packages"
    private const val KEY_HOME = "home"
    private const val KEY_HOME_XY = "home_xy"
    private const val KEY_POINTS = "points"
    private const val KEY_POINTS_XY = "points_xy"
    private const val KEY_CHECKIN = "checkin"
    private const val KEY_CHECKIN_XY = "checkin_xy"
    private const val KEY_DAY_THRESHOLD = "day_threshold"
    private const val KEY_POLL = "poll_sec"
    private const val KEY_RELEASED = "automation_released"
    private const val KEY_SEARCH_WAIT = "search_wait_sec"
    private const val KEY_DAILY_WAIT = "daily_wait_sec"
    private const val KEY_ONCE = "once_per_day"
    private const val KEY_LAST_RUN = "last_run_date"
    private const val KEY_MONITOR = "monitor_enabled"
    private const val KEY_PICKED_FIELD = "picked_field"
    private const val KEY_PICKED_XY = "picked_xy"
    private const val KEY_SEARCH_COUNT_MODE = "search_count_mode"   // 5/10/20，-1 表示自定义
    private const val KEY_SEARCH_COUNT_CUSTOM = "search_count_custom"

    /** 已签到的状态关键词 */
    val DONE_KEYWORDS = listOf("已签入", "已签到", "已打卡", "已领取", "明日再来", "已经连续")

    fun splitKeywords(raw: String): List<String> =
        raw.split("|", "\n", "，", ",", "；", ";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    fun splitPackages(raw: String): List<String> =
        raw.split("\n", ",", "，", ";", "；", " ")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    fun load(context: Context): AppConfig {
        val p = prefs(context)
        fun get(key: String, def: String) = p.getString(key, def) ?: def
        return AppConfig(
            packages = splitPackages(get(KEY_PACKAGES, AppConfig.DEFAULT_PACKAGES)),
            homeStep = StepConfig(
                splitKeywords(get(KEY_HOME, AppConfig.DEFAULT_HOME)),
                get(KEY_HOME_XY, AppConfig.DEFAULT_HOME_XY).ifBlank { null }
            ),
            pointsStep = StepConfig(
                splitKeywords(get(KEY_POINTS, AppConfig.DEFAULT_POINTS)),
                get(KEY_POINTS_XY, "").ifBlank { null }
            ),
            checkinStep = StepConfig(
                splitKeywords(get(KEY_CHECKIN, AppConfig.DEFAULT_CHECKIN)),
                get(KEY_CHECKIN_XY, "").ifBlank { null }
            ),
            dayThreshold = p.getInt(KEY_DAY_THRESHOLD, AppConfig.DEFAULT_DAY_THRESHOLD).coerceIn(1, 200),
            pollSec = p.getInt(KEY_POLL, AppConfig.DEFAULT_POLL_SEC).coerceIn(1, 60),
            searchWaitSec = p.getInt(KEY_SEARCH_WAIT, AppConfig.DEFAULT_SEARCH_WAIT_SEC).coerceIn(1, 120),
            dailyWaitSec = p.getInt(KEY_DAILY_WAIT, AppConfig.DEFAULT_DAILY_WAIT_SEC).coerceIn(1, 120),
            oncePerDay = p.getBoolean(KEY_ONCE, true)
        )
    }

    fun save(context: Context, editorBlock: SharedPreferences.Editor.() -> Unit) {
        val editor = prefs(context).edit()
        editorBlock(editor)
        // 注意：这里必须是 Editor 的提交，写成 edit().apply { } 只会调用 Kotlin 的
        // 作用域函数 apply，改动不会落盘（历史 bug：设置点了保存却不生效）
        editor.commit()
    }

    /** 自动化是否处于「已释放」状态（跨进程重启保留） */
    fun automationReleased(context: Context): Boolean = prefs(context).getBoolean(KEY_RELEASED, false)

    fun setAutomationReleased(context: Context, released: Boolean) {
        prefs(context).edit().putBoolean(KEY_RELEASED, released).apply()
    }

    fun monitorEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_MONITOR, false)

    fun setMonitorEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_MONITOR, enabled).apply()
    }

    /** 自动搜索次数档位：5/10/20，-1 表示自定义 */
    fun searchCountMode(context: Context): Int = prefs(context).getInt(KEY_SEARCH_COUNT_MODE, 20)

    /** 自动搜索自定义次数（档位为自定义时生效） */
    fun searchCountCustom(context: Context): Int = prefs(context).getInt(KEY_SEARCH_COUNT_CUSTOM, 20)

    fun setSearchCountMode(context: Context, mode: Int) {
        prefs(context).edit().putInt(KEY_SEARCH_COUNT_MODE, mode).apply()
    }

    fun setSearchCountCustom(context: Context, count: Int) {
        prefs(context).edit().putInt(KEY_SEARCH_COUNT_CUSTOM, count).apply()
    }

    /** 本次自动搜索要执行的次数 */
    fun searchCount(context: Context): Int {
        val mode = searchCountMode(context)
        return if (mode == -1) searchCountCustom(context) else mode
    }

    /** 今天是否已经执行过（仅当“每天只执行一次”开启时用于拦截） */
    fun alreadyRanToday(context: Context): Boolean {
        val date = prefs(context).getString(KEY_LAST_RUN, "") ?: ""
        return date == LocalDate.now().toString()
    }

    fun markRanToday(context: Context) {
        prefs(context).edit().putString(KEY_LAST_RUN, LocalDate.now().toString()).apply()
    }

    /** 悬浮窗取点结果暂存 */
    fun setPicked(context: Context, field: String, xy: String) {
        prefs(context).edit()
            .putString(KEY_PICKED_FIELD, field)
            .putString(KEY_PICKED_XY, xy)
            .apply()
    }

    /** 取出并清除取点结果 */
    fun takePicked(context: Context): Pair<String, String>? {
        val p = prefs(context)
        val field = p.getString(KEY_PICKED_FIELD, null) ?: return null
        val xy = p.getString(KEY_PICKED_XY, null) ?: run {
            p.edit().remove(KEY_PICKED_FIELD).apply()
            return null
        }
        p.edit().remove(KEY_PICKED_FIELD).remove(KEY_PICKED_XY).apply()
        return field to xy
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    // ---------- 备份 / 导入（用户配置属于应用数据，不在缓存中） ----------

    private const val BACKUP_APP_TAG = "MicrosoftRewardsHelper"
    private const val BACKUP_VERSION = 1

    /** 把全部配置序列化为 JSON 文本，供 SAF 导出 */
    fun exportConfigText(context: Context): String {
        val data = JSONObject()
        prefs(context).all.forEach { (key, value) ->
            when (value) {
                is Boolean -> data.put(key, value)
                is Int -> data.put(key, value)
                is Long -> data.put(key, value)
                is Float -> data.put(key, value.toDouble())
                is String -> data.put(key, value)
                is Set<*> -> data.put(key, JSONArray(value.map { it.toString() }))
                null -> data.put(key, null as String?)
                else -> data.put(key, value.toString())
            }
        }
        return JSONObject()
            .put("app", BACKUP_APP_TAG)
            .put("version", BACKUP_VERSION)
            .put("exported_at", System.currentTimeMillis())
            .put("prefs", data)
            .toString(2)
    }

    /**
     * 从备份 JSON 文本恢复配置。
     * @return true 成功；false 文件格式不对
     */
    fun importConfigText(context: Context, text: String): Boolean {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return false
        if (root.optString("app") != BACKUP_APP_TAG) return false
        val data = root.optJSONObject("prefs") ?: return false
        val editor = prefs(context).edit()
        val keys = data.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            when (val value = data.get(key)) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Double -> editor.putFloat(key, value.toFloat())
                is String -> editor.putString(key, value)
                is JSONArray -> {
                    val set = buildSet { repeat(value.length()) { i -> add(value.getString(i)) } }
                    editor.putStringSet(key, set)
                }
                JSONObject.NULL -> editor.remove(key)
            }
        }
        editor.apply()
        return true
    }

    // ---------- 缓存管理（只动 cache 目录，绝不碰 SharedPreferences 与 files） ----------

    /** 应用当前缓存大小（内部缓存 + 外部缓存），字节 */
    fun cacheSize(context: Context): Long {
        var total = dirSize(context.cacheDir)
        context.externalCacheDirs.forEach { dir -> total += dirSize(dir) }
        return total
    }

    /** 清空缓存目录内容，保留目录本身；用户配置（SharedPreferences）不受影响 */
    fun clearCache(context: Context) {
        sequence {
            yield(context.cacheDir)
            context.externalCacheDirs.forEach { yield(it) }
        }.filterNotNull().distinct().forEach { dir ->
            dir.listFiles()?.forEach { it.deleteRecursively() }
        }
    }

    private fun dirSize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        var sum = 0L
        dir.listFiles()?.forEach { sum += dirSize(it) }
        return sum
    }

    /** 缓存大小的可读文本 */
    fun formatSize(bytes: Long): String = when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}
