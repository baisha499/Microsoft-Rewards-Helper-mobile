package com.baisha.MicrosoftRewardsHelper

import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

typealias CheckInLogger = (String) -> Unit

/**
 * 必应签到流程：
 * 1) 处于目标 App（不在则拉起）
 * 2) 点击左上角用户头像
 * 3) 点击 Microsoft Rewards
 * 4) 在“签入”页面里，点击 Day1~Day7 中第一个还没有变黄的那个
 */
object CheckInEngine {

    private const val STEP_TIMEOUT_MS = 20_000L
    private const val STEP_TIMEOUT_WITH_FALLBACK_MS = 6_000L
    private const val POLL_MS = 1_200L

    private val DAY_EN = Regex("day\\s*([1-7])", RegexOption.IGNORE_CASE)
    private val DAY_ZH = Regex("第\\s*([1-7])\\s*天")
    /** 奖励按钮上的文案，例如 "+10"、"+5" */
    private val REWARD_BTN = Regex("\\+\\d+")
    /** 每日活动区块标题 */
    private val DAILY_SECTION_KEYWORDS = listOf("每日活动", "每日", "活动", "Daily", "daily", "Daily activity")
    /** 今日积分区块关键词，用于定位"下方" */
    private val POINTS_KEYWORDS = listOf("今日积分", "今日", "积分", "Points", "points")
    /** 必应首页搜索框的提示文字 */
    private const val SEARCH_BOX_HINT = "探索国内新鲜事"

    enum class Region { NONE, TOP, BOTTOM }
    enum class Outcome { OK, ALREADY_DONE, FAILED }

    /**
     * 跑到第 maxStep 步为止（maxStep: 0=只进入目标 App，1=完成第1步，2=完成第2步，3=含签入）。
     * 取点时用来先进入目标界面。
     */
    suspend fun runSteps(
        context: Context,
        cfg: AppConfig,
        log: CheckInLogger,
        maxStep: Int = 3
    ): Boolean {
        if (!Shell.hasPermission()) {
            log("✗ Shizuku 未授权，无法执行")
            return false
        }
        if (UserShell.get(context) == null) {
            log("✗ Shizuku 用户服务启动失败，请确认 Shizuku 正在运行且已授权")
            return false
        }
        if (!Device.isScreenOn(context)) {
            log("✗ 屏幕未点亮，放弃本次执行")
            return false
        }

        val target = cfg.packages.firstOrNull()
        if (target == null) {
            log("✗ 未配置目标包名")
            return false
        }

        val size = Device.screenSize(context)
        if (size != null) log("分辨率：${size.x}x${size.y}")

        if (!waitForeground(context, cfg.packages)) {
            log("⇢ 尝试启动 $target")
            Device.launch(context, target)
            if (!waitForeground(context, cfg.packages, timeoutMs = 15_000)) {
                log("✗ 未能切换到目标应用")
                return false
            }
        }
        log("✓ 已处于目标应用：${Device.foregroundPackage(context)}")
        delay(1_500)
        if (maxStep < 1) return true

        // 第 1 步：左上角用户头像
        when (tapStep(context, "左上角头像", cfg.homeStep, size, Region.TOP, log)) {
            Outcome.FAILED -> {
                log("✗ 第 1 步失败：未找到左上角头像")
                return false
            }
            Outcome.ALREADY_DONE -> log("· 第 1 步：已在对应页面")
            Outcome.OK -> log("✓ 第 1 步：已点击左上角头像")
        }
        delay(1_800)
        if (maxStep < 2) return true

        // 第 2 步：Microsoft Rewards
        when (tapStep(context, "Microsoft Rewards", cfg.pointsStep, size, Region.NONE, log)) {
            Outcome.FAILED -> {
                log("✗ 第 2 步失败：未找到 Microsoft Rewards")
                return false
            }
            Outcome.ALREADY_DONE -> log("· 第 2 步：似乎已在 Rewards 页面")
            Outcome.OK -> log("✓ 第 2 步：已点击 Microsoft Rewards")
        }
        delay(2_800)

        // 第 3 步：签入页 + Day 卡片
        return signInDays(context, cfg, size, log)
    }

    suspend fun runOnce(context: Context, cfg: AppConfig, log: CheckInLogger): Boolean {
        val ok = runSteps(context, cfg, log, maxStep = 3)
        // 签到流程结束后，无论成功与否，都返回本应用
        returnToAppOnFinish(context, log)
        return ok
    }

    // ---------- 每日活动（+10 任务） ----------

    /**
     * 完成每日活动：
     * 1) 进入目标 App → 点头像 → 点 Microsoft Rewards，到达奖励页
     * 2) 在"今日积分"下方找到"每日活动"区块里的第一个活动
     * 3) 依次点击其中的 +10 按钮（最多 3 个），每次点击后等 3 秒再返回
     * 4) 找不到 +10 按钮则提示"每日活动已完成"
     * 5) 无论哪种情况，完成后均返回本应用
     */
    suspend fun runDailyActivities(context: Context, cfg: AppConfig, log: CheckInLogger): Boolean {
        if (!Shell.hasPermission()) {
            log("✗ Shizuku 未授权，无法执行")
            return false
        }
        if (UserShell.get(context) == null) {
            log("✗ Shizuku 用户服务启动失败，请确认 Shizuku 正在运行且已授权")
            return false
        }
        if (!Device.isScreenOn(context)) {
            log("✗ 屏幕未点亮，放弃本次执行")
            return false
        }

        val target = cfg.packages.firstOrNull()
        if (target == null) {
            log("✗ 未配置目标包名")
            return false
        }

        val size = Device.screenSize(context)
        if (size != null) log("分辨率：${size.x}x${size.y}")

        // 进入目标应用
        if (!waitForeground(context, cfg.packages)) {
            log("⇢ 尝试启动 $target")
            Device.launch(context, target)
            if (!waitForeground(context, cfg.packages, timeoutMs = 15_000)) {
                log("✗ 未能切换到目标应用")
                return false
            }
        }
        log("✓ 已处于目标应用：${Device.foregroundPackage(context)}")
        delay(1_500)

        // 第 1 步：左上角用户头像
        when (tapStep(context, "左上角头像", cfg.homeStep, size, Region.TOP, log)) {
            Outcome.FAILED -> {
                log("✗ 未找到左上角头像")
                returnToAppOnFinish(context, log)
                return false
            }
            Outcome.ALREADY_DONE -> log("· 第 1 步：已在对应页面")
            Outcome.OK -> log("✓ 第 1 步：已点击左上角头像")
        }
        delay(1_800)

        // 第 2 步：Microsoft Rewards
        when (tapStep(context, "Microsoft Rewards", cfg.pointsStep, size, Region.NONE, log)) {
            Outcome.FAILED -> {
                log("✗ 未找到 Microsoft Rewards")
                returnToAppOnFinish(context, log)
                return false
            }
            Outcome.ALREADY_DONE -> log("· 第 2 步：似乎已在 Rewards 页面")
            Outcome.OK -> log("✓ 第 2 步：已点击 Microsoft Rewards")
        }
        delay(2_800)

        // 第 3 步：在奖励页查找每日活动中的 +10 按钮并依次点击
        val clicked = completeDailyTasks(context, size, log)
        if (clicked == 0) {
            log("✓ 未发现可点击的 +10 按钮，每日活动已完成")
        } else {
            log("✓ 每日活动完成，共点击 $clicked 个奖励按钮")
        }

        returnToAppOnFinish(context, log)
        return true
    }

    /**
     * 在 Rewards 页面依次点击每日活动中的奖励按钮。
     * 每点击一个后等待 3 秒再返回，然后重新扫描下一个。
     * @return 实际点击的按钮数量
     */
    private suspend fun completeDailyTasks(
        context: Context,
        size: Point?,
        log: CheckInLogger
    ): Int {
        // 先下滑一段，让"每日活动"区块的两个任务文字都显示出来再识别
        scrollUntilDailyTasksVisible(context, size, log)

        var clicked = 0
        val maxClicks = 3

        while (clicked < maxClicks) {
            val nodes = Device.nodes(context)
            if (nodes == null) {
                log("  · 未取到界面，重试中")
                delay(POLL_MS)
                continue
            }

            val button = findFirstRewardButton(nodes)
            if (button == null) {
                log("  · 当前没有可点击的 +10 按钮")
                break
            }

            clicked++
            log("→ 点击第 $clicked/$maxClicks 个奖励按钮「${button.label}」 (${button.centerX}, ${button.centerY})")
            Device.tap(context, button.centerX, button.centerY)

            log("  · 等待 3 秒")
            delay(3_000)

            log("  · 返回奖励页")
            Device.back(context)
            delay(1_800)
        }

        return clicked
    }

    /**
     * 下滑页面，直到"每日活动"区块的两个任务文字都显示在屏幕上。
     * 最多下滑 6 次，避免页面本身不够长时死循环。
     */
    private suspend fun scrollUntilDailyTasksVisible(
        context: Context,
        size: Point?,
        log: CheckInLogger
    ) {
        val maxScrolls = 6
        val w = size?.x ?: 1080
        val h = size?.y ?: 2400

        repeat(maxScrolls) { i ->
            val nodes = Device.nodes(context)
            if (nodes != null && dailyTasksFullyVisible(nodes, h)) {
                log("✓ 每日活动的 2 个任务文字都已显示在屏幕上${if (i > 0) "（下滑 $i 次）" else ""}")
                return
            }
            log("⇢ 下滑一次，让每日活动的两个任务都显示出来…")
            Device.swipe(context, w / 2, (h * 0.70f).toInt(), w / 2, (h * 0.40f).toInt(), 500)
            delay(1_500)
        }
        log("· 已下滑 $maxScrolls 次仍未确认两个任务完全可见，继续尝试识别")
    }

    /**
     * 判断"每日活动"区块的 2 个任务文字是否都已可见：
     * 找到"今日积分"下方的"每日活动"标题，统计标题下方的文字行数（按 Y 聚类），
     * 至少 2 行即认为两个任务都显示出来了。
     */
    private fun dailyTasksFullyVisible(nodes: List<UiNode>, screenH: Int): Boolean {
        val pointsNode = nodes.firstOrNull { node ->
            POINTS_KEYWORDS.any { node.label.contains(it, ignoreCase = true) }
        }
        val pointsBottom = pointsNode?.bounds?.bottom ?: 0
        val sectionNode = nodes.firstOrNull { node ->
            node.centerY > pointsBottom &&
                DAILY_SECTION_KEYWORDS.any { node.label.contains(it, ignoreCase = true) }
        } ?: return false
        val sectionBottom = sectionNode.bounds.bottom

        // 标题下方的文字节点按 Y 聚类成行，行距超过屏幕高度 3.5% 视为新一行的任务
        val rowGap = (screenH * 0.035f).toInt().coerceAtLeast(60)
        val texts = nodes.filter { it.centerY > sectionBottom && it.label.isNotBlank() }
            .sortedBy { it.centerY }
        if (texts.isEmpty()) return false

        var rows = 0
        var lastY = Int.MIN_VALUE
        texts.forEach { node ->
            if (lastY == Int.MIN_VALUE || node.centerY - lastY > rowGap) rows++
            lastY = node.centerY
        }
        return rows >= 2
    }

    /**
     * 查找"今日积分"下方"每日活动"区块中的第一个奖励按钮（+N 文案）。
     * 按 Y 坐标从小到大排序，取最靠上的一个。
     */
    private fun findFirstRewardButton(nodes: List<UiNode>): UiNode? {
        // 定位"今日积分"区块的底部 Y
        val pointsNode = nodes.firstOrNull { node ->
            POINTS_KEYWORDS.any { node.label.contains(it, ignoreCase = true) }
        }
        val pointsBottom = pointsNode?.bounds?.bottom ?: 0

        // 定位"每日活动"区块标题的底部 Y（在今日积分下方）
        val sectionNode = nodes.firstOrNull { node ->
            node.centerY > pointsBottom &&
                DAILY_SECTION_KEYWORDS.any { node.label.contains(it, ignoreCase = true) }
        }
        val sectionBottom = sectionNode?.bounds?.bottom ?: pointsBottom

        // 筛选奖励按钮：文案含 +N 且在每日活动区块下方
        val buttons = nodes.filter { node ->
            REWARD_BTN.containsMatchIn(node.label) && node.centerY > sectionBottom
        }.sortedBy { it.centerY }

        return buttons.firstOrNull()
    }

    /** 完成后返回本应用 */
    private suspend fun returnToAppOnFinish(context: Context, log: CheckInLogger) {
        log("⇢ 返回本应用")
        Device.returnToApp(context)
        delay(1_000)
    }

    // ---------- 自动搜索任务 ----------

    /**
     * 自动搜索任务：
     * 1) 进入目标 App，点击首页写着"探索国内新鲜事"的搜索框
     * 2) 从 assets 词典里随机抽取 count 个不重复的词
     * 3) 逐字输入搜索框（模仿真人打字）→ 点键盘上的"搜索" → 等 3 秒
     * 4) 点顶部搜索框（里面是刚搜的词）→ 清空 → 输入下一个词
     * 5) 达到次数后返回本应用
     */
    suspend fun runAutoSearch(context: Context, cfg: AppConfig, count: Int, log: CheckInLogger): Boolean {
        if (!Shell.hasPermission()) {
            log("✗ Shizuku 未授权，无法执行")
            return false
        }
        if (UserShell.get(context) == null) {
            log("✗ Shizuku 用户服务启动失败，请确认 Shizuku 正在运行且已授权")
            return false
        }
        if (!Device.isScreenOn(context)) {
            log("✗ 屏幕未点亮，放弃本次执行")
            return false
        }
        if (!A11y.connected()) {
            log("✗ 无障碍服务未连接，无法逐字输入")
            return false
        }

        val target = cfg.packages.firstOrNull()
        if (target == null) {
            log("✗ 未配置目标包名")
            return false
        }

        // 从词典随机抽词（互不重复）
        val words = pickSearchWords(context, count, log)
        if (words.isEmpty()) return false

        val size = Device.screenSize(context)
        if (size != null) log("分辨率：${size.x}x${size.y}")

        // 进入目标应用
        if (!waitForeground(context, cfg.packages)) {
            log("⇢ 尝试启动 $target")
            Device.launch(context, target)
            if (!waitForeground(context, cfg.packages, timeoutMs = 15_000)) {
                log("✗ 未能切换到目标应用")
                return false
            }
        }
        log("✓ 已处于目标应用：${Device.foregroundPackage(context)}")
        delay(1_500)

        // 第一次：点击首页搜索框
        if (!tapHomeSearchBox(context, log)) {
            returnToAppOnFinish(context, log)
            return false
        }

        var done = 0
        for (word in words) {
            currentCoroutineContext().ensureActive()
            done++
            log("→ 第 $done/${words.size} 次搜索：「$word」")

            if (!typeWord(context, word, log)) {
                log("✗ 无法向搜索框输入文字，中止")
                returnToAppOnFinish(context, log)
                return false
            }

            // 点键盘上的搜索按钮，找不到就用回车键触发
            if (!A11y.clickImeSearch()) {
                log("  · 键盘上没找到搜索按钮，改用回车键触发")
                Shell.exec(context, "input keyevent 66", 8_000)
            }

            log("  · 等待 3 秒")
            delay(3_000)

            // 下一次：点击顶部搜索框（里面是刚搜的词）
            if (done < words.size && !tapSearchBoxWithText(context, word, log)) {
                log("✗ 未找到顶部搜索框，中止")
                returnToAppOnFinish(context, log)
                return false
            }
        }

        log("✓ 自动搜索完成，共 $done 次")
        returnToAppOnFinish(context, log)
        return true
    }

    /** 从 assets 词典里随机抽 count 个不重复的词 */
    private fun pickSearchWords(context: Context, count: Int, log: CheckInLogger): List<String> {
        val raw = runCatching {
            context.assets.open("dictionary.txt").bufferedReader().use { it.readText() }
        }.getOrNull()
        if (raw == null) {
            log("✗ 读取词典 dictionary.txt 失败")
            return emptyList()
        }
        val words = raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            // 跳过 git 合并冲突标记行
            .filter { !it.startsWith("<<<<<<<") && !it.startsWith("=======") && !it.startsWith(">>>>>>>") }
            .distinct()
            .toList()
        log("词典共 ${words.size} 个词，本次抽取 $count 个")
        return words.shuffled().take(count)
    }

    /** 点击必应首页写着"探索国内新鲜事"的搜索框 */
    private suspend fun tapHomeSearchBox(context: Context, log: CheckInLogger): Boolean {
        val deadline = System.currentTimeMillis() + 12_000
        while (System.currentTimeMillis() < deadline) {
            val nodes = Device.nodes(context)
            val node = nodes?.let { topMostContaining(it, SEARCH_BOX_HINT) }
                ?: nodes?.let { topMostContaining(it, "探索") }
            if (node != null) {
                log("→ 点击搜索框「${node.label.take(20)}」 (${node.centerX}, ${node.centerY})")
                Device.tap(context, node.centerX, node.centerY)
                return true
            }
            delay(POLL_MS)
        }
        log("✗ 未找到写着「$SEARCH_BOX_HINT」的搜索框")
        return false
    }

    /** 点击屏幕上方包含指定文字的搜索框（搜完一次后，顶部搜索框里是刚搜的词） */
    private suspend fun tapSearchBoxWithText(context: Context, text: String, log: CheckInLogger): Boolean {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val nodes = Device.nodes(context)
            val node = nodes?.let { topMostContaining(it, text) }
            if (node != null) {
                log("  → 点击顶部搜索框 (${node.centerX}, ${node.centerY})")
                Device.tap(context, node.centerX, node.centerY)
                return true
            }
            delay(POLL_MS)
        }
        log("  · 未找到包含「$text」的搜索框")
        return false
    }

    /** 取标签包含关键字的节点里最靠上的那个（搜索框在页面顶部） */
    private fun topMostContaining(nodes: List<UiNode>, keyword: String): UiNode? =
        nodes.filter { it.label.contains(keyword) }.minByOrNull { it.centerY }

    /**
     * 清空搜索框后逐字输入词语，模仿真人打字节奏。
     * 中文无法用 input text 输入，这里用无障碍 ACTION_SET_TEXT 逐字递增。
     */
    private suspend fun typeWord(context: Context, word: String, log: CheckInLogger): Boolean {
        // 等搜索输入框出现（点击搜索框后界面需要过渡）
        val deadline = System.currentTimeMillis() + 8_000
        while (!A11y.hasEditable()) {
            if (System.currentTimeMillis() >= deadline) {
                log("  · 未找到搜索输入框")
                return false
            }
            delay(500)
        }

        // 清空已有内容（第一次为空，后续是上一次搜的词）
        var cleared = A11y.setFieldText("")
        if (!cleared) {
            A11y.focusEditable()
            delay(500)
            cleared = A11y.setFieldText("")
        }
        if (!cleared) {
            log("  · 清空搜索框失败")
            return false
        }

        // 逐字输入，每字之间随机停顿
        val sb = StringBuilder()
        for (ch in word) {
            sb.append(ch)
            var ok = A11y.setFieldText(sb.toString())
            if (!ok) {
                delay(300)
                ok = A11y.setFieldText(sb.toString())
            }
            if (!ok) return false
            delay((80..220).random().toLong())
        }
        return true
    }

    // ---------- 第 3 步 ----------

    private suspend fun signInDays(
        context: Context,
        cfg: AppConfig,
        size: Point?,
        log: CheckInLogger
    ): Boolean {
        var nodes: List<UiNode>? = null
        val deadline = System.currentTimeMillis() + STEP_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val cur = Device.nodes(context)
            if (cur != null) {
                if (matchesAny(cur, cfg.checkinStep.keywords)) {
                    nodes = cur
                    break
                }
            }
            delay(POLL_MS)
        }
        val page = nodes
        if (page == null) {
            log("✗ 当前页面没有检测到“${cfg.checkinStep.keywords.joinToString("/")}”字样")
            return false
        }
        log("✓ 已进入签入页面")
        if (matchesAny(page, Config.DONE_KEYWORDS)) {
            log("✓ 页面提示已签入，无需重复操作")
            return true
        }

        val days = findDayNodes(page)
        if (days.isEmpty()) {
            log("✓ 签入页面未找到 Day1~Day7 卡片，今天已经签过到了，无需点击")
            return true
        }
        log("找到 Day 卡片：${days.map { it.first }.sorted().joinToString()}")

        val refW = page.maxOfOrNull { it.bounds.right } ?: size?.x ?: 1080
        val refH = page.maxOfOrNull { it.bounds.bottom } ?: size?.y ?: 2400
        val result = Device.analyze(context, days.map { it.second.bounds }, refW, refH)
        if (result == null) {
            log("✗ 截图取色失败")
            return false
        }

        val targetIndex = days.indexOfFirst { (index, node) ->
            val i = days.indexOfFirst { it.first == index }
            val score = result.samples.getOrNull(i)?.score ?: 0
            val yellow = score >= cfg.dayThreshold
            val s = result.samples.getOrNull(i)
            log("  Day$index  score=$score  rgb(${s?.r},${s?.g},${s?.b})  → ${if (yellow) "已变黄" else "未变黄"}")
            !yellow
        }

        if (targetIndex < 0) {
            log("✓ Day1~Day7 全部已变黄，本次无需点击")
            return true
        }

        val (index, node) = days[targetIndex]
        log("→ 点击 Day$index (${node.centerX}, ${node.centerY})")
        Device.tap(context, node.centerX, node.centerY)
        delay(2_000)

        // 复查：该 Day 是否变黄
        val again = Device.analyze(context, listOf(node.bounds), refW, refH)
        val scoreAfter = again?.samples?.firstOrNull()?.score ?: 0
        if (scoreAfter >= cfg.dayThreshold) {
            log("✓ 复查 Day$index score=$scoreAfter，已变黄，签到完成")
        } else {
            log("· 复查 Day$index score=$scoreAfter，未变黄（阈值 ${cfg.dayThreshold}），可能文案/布局有变化")
        }
        return true
    }

    /**
     * 找出 Day1~Day7 的节点，每天取面积最大的那个（通常是整张卡片）。
     * 仅匹配带 "day"/"第N天" 字样的节点；不做纯数字兜底，
     * 避免把 "搜索1次" 之类任务框里的数字误判为 Day 卡片。
     */
    private fun findDayNodes(nodes: List<UiNode>): List<Pair<Int, UiNode>> {
        val map = LinkedHashMap<Int, UiNode>()
        nodes.forEach { node ->
            val label = node.label
            if (label.isEmpty()) return@forEach
            val index = DAY_EN.find(label)?.groupValues?.get(1)?.toIntOrNull()
                ?: DAY_ZH.find(label)?.groupValues?.get(1)?.toIntOrNull()
                ?: return@forEach
            val prev = map[index]
            if (prev == null || area(node.bounds) > area(prev.bounds)) {
                map[index] = node
            }
        }
        return map.toSortedMap().map { it.key to it.value }
    }

    private fun area(r: Rect): Int = (r.width().coerceAtLeast(1)) * (r.height().coerceAtLeast(1))

    // ---------- 通用步骤 ----------

    private suspend fun tapStep(
        context: Context,
        label: String,
        step: StepConfig,
        size: Point?,
        region: Region,
        log: CheckInLogger
    ): Outcome {
        val fallback = resolveFallback(step, size)

        // 没有配置关键词时（例如第 1 步），直接用坐标点击，不做界面匹配
        if (step.keywords.isEmpty()) {
            if (fallback == null) {
                log("✗ $label：未配置坐标，无法点击")
                return Outcome.FAILED
            }
            log("  → $label：直接点击坐标 (${fallback.first}, ${fallback.second})")
            Device.tap(context, fallback.first, fallback.second)
            return Outcome.OK
        }

        val timeout = if (fallback != null) STEP_TIMEOUT_WITH_FALLBACK_MS else STEP_TIMEOUT_MS
        var deadline = System.currentTimeMillis() + timeout

        while (System.currentTimeMillis() < deadline) {
                val nodes = Device.nodes(context)
            if (nodes != null) {
                val node = pick(nodes, step.keywords, region, size)
                if (node != null) {
                                log("  → $label：命中“${node.label}”，点击 (${node.centerX}, ${node.centerY})")
                    Device.tap(context, node.centerX, node.centerY)
                    return Outcome.OK
                }
            } else {
                log("  · $label：uiautomator 未取到界面，重试中")
            }
            delay(POLL_MS)
        }

        if (fallback != null) {
            log("  → $label：关键词未命中，使用备选坐标 (${fallback.first}, ${fallback.second})")
            Device.tap(context, fallback.first, fallback.second)
            return Outcome.OK
        }
        return Outcome.FAILED
    }

    /** 支持 "990,2270" 绝对坐标，或 "9%,6%" 相对屏幕坐标 */
    private fun resolveFallback(step: StepConfig, size: Point?): Pair<Int, Int>? {
        val raw = step.fallbackRaw?.replace("，", ",")?.replace(" ", "") ?: return null
        val numeric = raw.replace("%", "")
        val parts = numeric.split(",", "x", "X").filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        val a = parts[0].toFloatOrNull() ?: return null
        val b = parts[1].toFloatOrNull() ?: return null
        return if (raw.contains("%")) {
            if (size == null) null else (size.x * a / 100f).toInt() to (size.y * b / 100f).toInt()
        } else {
            a.toInt() to b.toInt()
        }
    }

    /** 关键词选点：优先完全匹配 → 关键词顺序 → 位置 */
    private fun pick(
        nodes: List<UiNode>,
        keywords: List<String>,
        region: Region,
        size: Point?
    ): UiNode? {
        if (keywords.isEmpty()) return null

        var candidates = nodes.filter { node ->
            val label = node.label
            if (label.isEmpty()) false
            else keywords.any { label.contains(it, ignoreCase = true) }
        }
        if (candidates.isEmpty()) return null

        val notDone = candidates.filter { node ->
            !Config.DONE_KEYWORDS.any { node.label.contains(it, ignoreCase = true) }
        }
        if (notDone.isNotEmpty()) candidates = notDone

        if (size != null) {
            val scoped = when (region) {
                Region.TOP -> candidates.filter { it.centerY < size.y * 0.35f }
                Region.BOTTOM -> candidates.filter { it.centerY > size.y * 0.75f }
                Region.NONE -> candidates
            }
            if (scoped.isNotEmpty()) candidates = scoped
        }

        return candidates.minWith(
            compareBy<UiNode> { rankLabel(it, keywords) }
                .thenByDescending { it.label.length }
                .thenBy { if (region == Region.BOTTOM) -it.centerX else it.centerY }
        )
    }

    private fun rankLabel(node: UiNode, keywords: List<String>): Int {
        val exact = keywords.indexOfFirst { it.equals(node.label, ignoreCase = true) }
        if (exact >= 0) return -1
        val idx = keywords.indexOfFirst { node.label.contains(it, ignoreCase = true) }
        return if (idx >= 0) idx else keywords.size
    }

    private fun matchesAny(nodes: List<UiNode>, keywords: List<String>): Boolean =
        nodes.any { node ->
            val label = node.label
            label.isNotEmpty() && keywords.any { label.contains(it, ignoreCase = true) }
        }

    private suspend fun waitForeground(
        context: Context,
        targets: List<String>,
        timeoutMs: Long = 4_000
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val pkg = Device.foregroundPackage(context)
            if (pkg != null && targets.contains(pkg)) return true
            delay(700)
        }
        return false
    }
}
