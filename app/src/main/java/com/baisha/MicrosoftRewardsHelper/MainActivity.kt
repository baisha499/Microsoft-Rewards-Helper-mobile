package com.baisha.MicrosoftRewardsHelper

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.text.method.ScrollingMovementMethod
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PICKED = "picked_xy"
        const val EXTRA_PICKED_FIELD = "picked_field"
    }

    private val requestPermissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                log("✓ Shizuku 授权成功")
            } else {
                log("✗ Shizuku 授权被拒绝（requestCode=$requestCode）")
            }
            refreshShizukuStatus()
        }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { refreshShizukuStatus() }
    private val binderDeadListener = Shizuku.OnBinderDeadListener { refreshShizukuStatus() }

    private lateinit var tvStatus: TextView
    private lateinit var tvA11y: TextView
    private lateinit var tvForeground: TextView
    private lateinit var tvLog: TextView
    private lateinit var btnRun: MaterialButton
    private lateinit var btnDailyActivities: MaterialButton
    private lateinit var btnAutoSearch: MaterialButton
    private lateinit var tilSearchCount: TextInputLayout
    private lateinit var spSearchCount: MaterialAutoCompleteTextView
    private lateinit var btnCancel: MaterialButton
    private lateinit var btnRelease: MaterialButton
    private lateinit var btnDetectForeground: MaterialButton
    private lateinit var btnScanNodes: MaterialButton
    private lateinit var btnDiagnose: MaterialButton
    private lateinit var swMonitor: MaterialSwitch
    private lateinit var swOncePerDay: MaterialSwitch

    private var running = false
    private var runJob: kotlinx.coroutines.Job? = null
    private var lastA11yConnected: Boolean? = null
    private var lastReleased: Boolean? = null
    private val statusTicker = android.os.Handler(android.os.Looper.getMainLooper())
    private val tick: Runnable = object : Runnable {
        override fun run() {
            refreshShizukuStatus()
            statusTicker.postDelayed(this, 2_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationIcon(null)

        Shizuku.addRequestPermissionResultListener(requestPermissionListener)
        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)

        bindViews()
        bindActions()

        // 上次若处于「已释放」，本次启动继续保持，直到用户再点一次按钮
        val releasedOnBoot = Config.automationReleased(this)
        UserShell.setReleasedFlag(releasedOnBoot)
        if (releasedOnBoot) {
            log("· 自动化当前处于「已释放」状态，点“恢复自动化”后才会重新绑定 Shizuku")
            lifecycleScope.launch(Dispatchers.IO) { UserShell.release(this@MainActivity) }
        }

        swMonitor.isChecked = Config.monitorEnabled(this)
        swOncePerDay.isChecked = Config.load(this).oncePerDay

        askNotificationPermission()

        log("准备就绪。先申请 Shizuku 权限，再点“开始执行每日签到”；右上角齿轮可改配置。")
        // Shizuku 的 binder 是异步送达的，启动后连续刷新几秒状态
        scheduleStatusRefresh()
        handlePickResult(intent)    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePickResult(intent)
    }

    override fun onResume() {
        super.onResume()
        handlePickResult(intent)
        startStatusTicker()
    }

    override fun onPause() {
        super.onPause()
        stopStatusTicker()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(requestPermissionListener)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        statusTicker.removeCallbacks(tick)
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_settings) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun bindViews() {
        tvStatus = findViewById(R.id.tvShizukuStatus)
        tvA11y = findViewById(R.id.tvA11y)
        tvForeground = findViewById(R.id.tvForeground)
        tvLog = findViewById(R.id.tvLog)
        btnRun = findViewById(R.id.btnRunNow)
        btnDailyActivities = findViewById(R.id.btnDailyActivities)
        btnAutoSearch = findViewById(R.id.btnAutoSearch)
        tilSearchCount = findViewById(R.id.tilSearchCount)
        spSearchCount = findViewById(R.id.spSearchCount)
        btnCancel = findViewById(R.id.btnCancel)
        btnRelease = findViewById(R.id.btnRelease)
        btnDetectForeground = findViewById(R.id.btnDetectForeground)
        btnScanNodes = findViewById(R.id.btnScanNodes)
        btnDiagnose = findViewById(R.id.btnDiagnose)
        swMonitor = findViewById(R.id.swMonitor)
        swOncePerDay = findViewById(R.id.swOncePerDay)
        tvLog.movementMethod = ScrollingMovementMethod()
        // 让日志区域自己消费滑动事件，不被外层 ScrollView 抢走
        tvLog.setOnTouchListener { v, event ->
            v.parent?.requestDisallowInterceptTouchEvent(true)
            if (event.action == android.view.MotionEvent.ACTION_UP) v.performClick()
            false
        }
    }

    private fun bindActions() {
        findViewById<MaterialButton>(R.id.btnRequestShizuku).setOnClickListener { requestShizuku() }
        findViewById<MaterialButton>(R.id.btnOpenShizuku).setOnClickListener { openShizuku() }
        findViewById<MaterialButton>(R.id.btnReconnect).setOnClickListener { reconnectShizuku() }
        findViewById<MaterialButton>(R.id.btnEnableA11y).setOnClickListener { enableA11y() }
        findViewById<MaterialButton>(R.id.btnOpenA11y).setOnClickListener {
            startActivity(
                Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        findViewById<MaterialButton>(R.id.btnDetectForeground).setOnClickListener { detectForeground() }
        findViewById<MaterialButton>(R.id.btnScanNodes).setOnClickListener { scanNodes() }
        findViewById<MaterialButton>(R.id.btnDiagnose).setOnClickListener { diagnoseDump() }
        findViewById<MaterialButton>(R.id.btnClearLog).setOnClickListener { tvLog.text = "" }

        btnRelease.setOnClickListener { toggleRelease() }
        btnRun.setOnClickListener { runNow() }
        btnDailyActivities.setOnClickListener { runDailyActivities() }
        btnAutoSearch.setOnClickListener { runAutoSearch() }
        setupSearchCountDropdown()
        btnCancel.setOnClickListener {
            runJob?.cancel()
            log("✗ 已请求取消")
        }

        swMonitor.setOnCheckedChangeListener { _, checked ->
            Config.setMonitorEnabled(this, checked)
            if (checked) {
                MonitorService.start(this)
                log("后台监听已开启")
            } else {
                MonitorService.stop(this)
                log("后台监听已关闭")
            }
        }
        swOncePerDay.setOnCheckedChangeListener { _, checked ->
            Config.save(this) { putBoolean("once_per_day", checked) }
        }
    }

    // ---------- 悬浮窗取点（结果回填） ----------

    /** 取点完成后回到主界面，坐标转发给签到配置页自动填入 */
    private fun handlePickResult(src: Intent?): Boolean {
        val inlineXy = src?.getStringExtra(EXTRA_PICKED)
        val inlineField = src?.getStringExtra(EXTRA_PICKED_FIELD)
        src?.removeExtra(EXTRA_PICKED)
        src?.removeExtra(EXTRA_PICKED_FIELD)
        // 后台起 Activity 可能被系统拦截，此时结果还在 prefs 里，下次打开也要能取到
        val stored = Config.takePicked(this)
        val field = inlineField ?: stored?.first ?: return false
        val xy = inlineXy ?: stored?.second ?: return false
        log("✓ 取到坐标 $xy（字段：$field），已自动填入配置")
        val intent = Intent(this, StepConfigActivity::class.java)
            .putExtra(StepConfigActivity.EXTRA_PICKED, xy)
            .putExtra(StepConfigActivity.EXTRA_PICKED_FIELD, field)
        startActivity(intent)
        return true
    }

    // ---------- Shizuku ----------

    private fun scheduleStatusRefresh() {
        refreshShizukuStatus()
        for (i in 1..12) {
            statusTicker.postDelayed(tick, i * 500L)
        }
    }

    private fun refreshShizukuStatus() {
        val binderAlive = Shell.isBinderAlive()
        val granted = Shell.hasPermission()
        val installed = isPackageInstalled("moe.shizuku.privileged.api")
        val version = runCatching { if (binderAlive) Shizuku.getVersion() else -1 }.getOrDefault(-1)
        tvStatus.text = buildString {
            append("客户端：").append(if (installed) "已安装" else "未检测到").append('\n')
            append("服务：").append(if (binderAlive) "已连接（API $version）" else if (installed) "等待响应…（binder 未送达）" else "未运行").append('\n')
            append("授权：").append(if (granted) "已授权" else "未授权")
            append('\n').append("自动化：").append(
                if (UserShell.isReleased()) "已释放（不执行任何 shell）" else "就绪"
            )
            if (!binderAlive && installed) {
                append('\n').append("提示：Shizuku 只在冷启动时投递 binder，点下方“重新连接”")
            }
        }
        tvA11y.text = "无障碍取界面：${A11y.status(this)}"
        refreshReleaseButton()
        refreshFunctionButtons()
    }

    /**
     * 释放 / 恢复自动化：解绑（或下次自动重新绑定）Shizuku 用户服务。
     * 解绑是 Binder 调用，放到 IO 线程做，不占主线程、不阻塞 Shizuku 那边。
     */
    private fun toggleRelease() {
        val next = !UserShell.isReleased()
        Config.setAutomationReleased(this, next)
        log(
            if (next) "⇢ 已释放自动化：解绑 Shizuku 用户服务，期间不执行任何 shell"
            else "⇢ 已恢复自动化：下次用到时重新绑定 Shizuku 用户服务"
        )
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { UserShell.setReleased(this@MainActivity, next) }
            refreshShizukuStatus()
        }
    }

    private fun refreshReleaseButton() {
        btnRelease.text = if (UserShell.isReleased()) "恢复自动化（重新绑定 Shizuku）" else "释放自动化（解绑 Shizuku）"
    }

    /** 无障碍未连接、或自动化已释放时禁用下方全部功能按钮，状态变化时记录日志 */
    private fun refreshFunctionButtons() {
        val released = UserShell.isReleased()
        if (lastReleased != null && lastReleased != released) {
            log(
                if (released) "✗ 自动化已释放，下方功能已禁用"
                else "✓ 自动化已恢复，下方功能已解锁"
            )
        }
        lastReleased = released
        val a11yOk = A11y.connected() && !released
        if (lastA11yConnected != null && lastA11yConnected != a11yOk) {
            log(
                if (a11yOk) "✓ 无障碍已连接，下方功能已解锁"
                else "✗ 无障碍未启用，下方功能已禁用"
            )
        }
        lastA11yConnected = a11yOk

        btnRun.isEnabled = a11yOk && !running
        btnDailyActivities.isEnabled = a11yOk && !running
        btnAutoSearch.isEnabled = a11yOk && !running
        tilSearchCount.isEnabled = a11yOk
        btnCancel.isEnabled = a11yOk && running
        btnDetectForeground.isEnabled = a11yOk
        btnScanNodes.isEnabled = a11yOk
        btnDiagnose.isEnabled = a11yOk
        // 监听开关只受无障碍状态约束：释放期间监听仍在跑，但会自动跳过（见 MonitorService）
        swMonitor.isEnabled = A11y.connected()
        swOncePerDay.isEnabled = A11y.connected()
    }

    /** 通过 Shizuku 写入系统设置，直接启用本应用的无障碍服务 */
    private fun enableA11y() {
        if (!Shell.hasPermission()) {
            Toast.makeText(this, "需要先获得 Shizuku 授权", Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { A11y.enable(this@MainActivity) }
            Toast.makeText(
                this@MainActivity,
                if (ok) "已写入无障碍设置，若仍未生效请到系统无障碍设置里打开本应用" else "写入失败，可用下方按钮手动开启",
                Toast.LENGTH_LONG
            ).show()
            refreshShizukuStatus()
        }
    }

    /** 页面可见时持续刷新状态 */
    private fun startStatusTicker() {
        statusTicker.removeCallbacks(tick)
        statusTicker.postDelayed(tick, 0)
    }

    private fun stopStatusTicker() {
        statusTicker.removeCallbacks(tick)
    }

    private fun isPackageInstalled(pkg: String): Boolean = try {
        packageManager.getApplicationInfo(pkg, 0)
        true
    } catch (_: Throwable) {
        false
    }

    private fun requestShizuku() {
        refreshShizukuStatus()
        if (!isPackageInstalled("moe.shizuku.privileged.api")) {
            log("✗ 未在系统中检测到 Shizuku，请先安装 Shizuku")
            return
        }
        if (Shell.hasPermission()) {
            log("· Shizuku 已授权，无需重复申请")
            return
        }
        if (Shell.isBinderAlive()) {
            requestShizukuPermission()
            return
        }
        // binder 是异步送达的，这里只等待，不主动跳转 Shizuku
        log("· 等待 Shizuku 服务连接…（不会跳转；若无响应请在 Shizuku 里点『启动』后重试）")
        lifecycleScope.launch {
            val connected = withContext(Dispatchers.IO) {
                repeat(24) {
                    if (Shell.isBinderAlive()) return@withContext true
                    delay(500)
                }
                false
            }
            if (connected) {
                requestShizukuPermission()
            } else {
                log("✗ 仍未连上 Shizuku 服务：请先打开 Shizuku 启动服务，再回来点申请")
            }
            refreshShizukuStatus()
        }
    }

    private fun requestShizukuPermission() {
        if (Shizuku.getVersion() < 11) {
            log("✗ Shizuku 版本过低，请升级到 11 以上")
            return
        }
        Shizuku.requestPermission(0)
        log("已发起 Shizuku 授权请求，请在弹出的授权窗口中选择允许")
    }

    /**
     * Shizuku 只在 App 进程冷启动时投递 binder。
     * 如果本进程启动时 Shizuku 还没跑起来，就永远拿不到 binder —— 只能重启进程。
     */
    private fun reconnectShizuku() {
        log("⇢ 重新等待 Shizuku binder…")
        lifecycleScope.launch {
            val connected = withContext(Dispatchers.IO) {
                repeat(10) {
                    if (Shell.isBinderAlive()) return@withContext true
                    delay(400)
                }
                false
            }
            if (connected) {
                log("✓ 已连上 Shizuku 服务")
                refreshShizukuStatus()
                return@launch
            }
            log("✗ 本进程拿不到 binder：Shizuku 只在冷启动时投递，正在关闭并重新打开…")
            restartAppProcess()
        }
    }

    /**
     * 优雅重启：先把界面正常关掉（避免系统判定“应用运行异常”），
     * 再用闹钟拉起新进程。配置全部存在 SharedPreferences 里，不会丢。
     */
    private fun restartAppProcess() {
        val restart = packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ?: Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val pi = PendingIntent.getActivity(
            this,
            777,
            restart,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT
        )
        val alarm = getSystemService(ALARM_SERVICE) as android.app.AlarmManager
        alarm.set(android.app.AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 400, pi)

        // 1) 正常结束所有 Activity，让 App 退到后台
        finishAffinity()
        // 2) 等界面真正消失后再结束进程，这样系统不会弹出“应用运行异常”
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            android.os.Process.killProcess(android.os.Process.myPid())
        }, 1_200)
    }

    private fun openShizuku() {
        val intent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
        if (intent != null) {
            startActivity(intent)
        } else {
            Toast.makeText(this, "未检测到 Shizuku，请到 shizuku.rikka.app 下载", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- 权限 ----------

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    // ---------- 执行 ----------

    private fun runNow() {
        if (running) {
            Toast.makeText(this, "正在执行中…", Toast.LENGTH_SHORT).show()
            return
        }
        if (!A11y.connected()) {
            log("✗ 无障碍未启用，无法执行。请先点上方“启用无障碍取界面（通过 Shizuku）”")
            return
        }
        if (!Shell.hasPermission()) {
            log("✗ Shizuku 未授权，无法执行")
            requestShizuku()
            return
        }
        val cfg = Config.load(this)
        running = true
        refreshFunctionButtons()
        runJob = lifecycleScope.launch {
            val ok = try {
                withContext(Dispatchers.IO) {
                    CheckInEngine.runOnce(this@MainActivity, cfg) { msg -> log(msg) }
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                log("✗ 已取消本次执行")
                false
            }
            running = false
            runJob = null
            refreshFunctionButtons()
            log(if (ok) "=== 流程结束：完成 ===" else "=== 流程结束：未完成/已取消，请看上方日志 ===")
        }
    }

    private fun runDailyActivities() {
        if (running) {
            Toast.makeText(this, "正在执行中…", Toast.LENGTH_SHORT).show()
            return
        }
        if (!A11y.connected()) {
            log("✗ 无障碍未启用，无法执行。请先点上方“启用无障碍取界面（通过 Shizuku）”")
            return
        }
        if (!Shell.hasPermission()) {
            log("✗ Shizuku 未授权，无法执行")
            requestShizuku()
            return
        }
        val cfg = Config.load(this)
        running = true
        refreshFunctionButtons()
        runJob = lifecycleScope.launch {
            val ok = try {
                withContext(Dispatchers.IO) {
                    CheckInEngine.runDailyActivities(this@MainActivity, cfg) { msg -> log(msg) }
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                log("✗ 已取消本次执行")
                false
            }
            running = false
            runJob = null
            refreshFunctionButtons()
            log(if (ok) "=== 每日活动流程结束：完成 ===" else "=== 每日活动流程结束：未完成/已取消，请看上方日志 ===")
        }
    }

    // ---------- 自动搜索任务 ----------

    /** 次数下拉框：5 次 / 10 次 / 20 次 / 自定义 */
    private fun setupSearchCountDropdown() {
        spSearchCount.setSimpleItems(arrayOf("5 次", "10 次", "20 次", "自定义…"))
        restoreSearchCountText()
        spSearchCount.setOnItemClickListener { _, _, position, _ ->
            when (position) {
                0 -> applySearchCountMode(5)
                1 -> applySearchCountMode(10)
                2 -> applySearchCountMode(20)
                else -> promptCustomSearchCount()
            }
        }
    }

    private fun restoreSearchCountText() {
        val mode = Config.searchCountMode(this)
        spSearchCount.setText(
            if (mode == -1) "${Config.searchCountCustom(this)} 次" else "$mode 次",
            false
        )
    }

    private fun applySearchCountMode(mode: Int) {
        Config.setSearchCountMode(this, mode)
        spSearchCount.setText("$mode 次", false)
        log("✓ 自动搜索次数：$mode")
    }

    private fun promptCustomSearchCount() {
        val prevText = spSearchCount.text.toString()
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER
        input.setText(Config.searchCountCustom(this).toString())
        AlertDialog.Builder(this)
            .setTitle("自定义搜索次数（1~200）")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val n = input.text.toString().toIntOrNull()?.coerceIn(1, 200) ?: 20
                Config.setSearchCountCustom(this, n)
                Config.setSearchCountMode(this, -1)
                spSearchCount.setText("$n 次", false)
                log("✓ 自动搜索次数：$n（自定义）")
            }
            .setNegativeButton("取消", null)
            .setOnCancelListener { spSearchCount.setText(prevText, false) }
            .show()
    }

    private fun runAutoSearch() {
        if (running) {
            Toast.makeText(this, "正在执行中…", Toast.LENGTH_SHORT).show()
            return
        }
        if (!A11y.connected()) {
            log("✗ 无障碍未启用，无法执行。请先点上方“启用无障碍取界面（通过 Shizuku）”")
            return
        }
        if (!Shell.hasPermission()) {
            log("✗ Shizuku 未授权，无法执行")
            requestShizuku()
            return
        }
        val cfg = Config.load(this)
        val count = Config.searchCount(this)
        running = true
        refreshFunctionButtons()
        log("⇢ 自动搜索任务：共 $count 次")
        runJob = lifecycleScope.launch {
            val ok = try {
                withContext(Dispatchers.IO) {
                    CheckInEngine.runAutoSearch(this@MainActivity, cfg, count) { msg -> log(msg) }
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                log("✗ 已取消本次执行")
                false
            }
            running = false
            runJob = null
            refreshFunctionButtons()
            log(if (ok) "=== 自动搜索结束：完成 ===" else "=== 自动搜索结束：未完成/已取消，请看上方日志 ===")
        }
    }

    private fun diagnoseDump() {
        if (!Shell.hasPermission()) {
            log("✗ 需要先申请 Shizuku 权限")
            return
        }
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { Device.diagnose(this@MainActivity) }
            log("—— uiautomator 诊断 ——")
            items.forEach { (title, value) -> log("$title: ${value.ifBlank { "(空)" }}") }
        }
    }

    private fun detectForeground() {
        lifecycleScope.launch {
            val pkg = withContext(Dispatchers.IO) { Device.foregroundPackage(this@MainActivity) }
            tvForeground.text = "当前前台：${pkg ?: "未知"}"
            log("当前前台包名：$pkg")
        }
    }

    private fun scanNodes() {
        if (!Shell.hasPermission()) {
            log("✗ 需要先申请 Shizuku 权限才能扫描界面")
            return
        }
        lifecycleScope.launch {
            val nodes = withContext(Dispatchers.IO) { Device.nodes(this@MainActivity) }
            if (nodes == null) {
                log("✗ uiautomator 未取到界面（屏幕可能关闭或不在前台）")
                return@launch
            }
            log("共 ${nodes.size} 个节点，其中有文本的：")
            nodes.filter { it.label.isNotEmpty() }
                .take(80)
                .forEach { n -> log("  「${n.label}」 @(${n.centerX},${n.centerY})") }
        }
    }

    // ---------- 日志 ----------

    private fun log(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        runOnUiThread { tvLog.append("[$time] $msg\n") }
    }
}
