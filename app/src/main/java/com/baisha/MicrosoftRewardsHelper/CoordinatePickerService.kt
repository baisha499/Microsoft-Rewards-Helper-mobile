package com.baisha.MicrosoftRewardsHelper

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 悬浮窗取点：
 * - 可选先执行前置步骤（例如要取第 2 步坐标，就先跑完第 1 步进入对应界面）
 * - 显示透明覆盖层，用户点击处显示红点，可微调，再确认/取消
 */
class CoordinatePickerService : Service() {

    companion object {
        const val EXTRA_FIELD = "field"
        const val EXTRA_PACKAGE = "package"
        /** 先跑到第几步再让用户取点：0=只打开目标 App，1=完成第1步，2=完成第2步 */
        const val EXTRA_UP_TO_STEP = "up_to_step"
        private const val NOTI_ID = 1002
        private const val CHANNEL_ID = "monitor"
        private const val NUDGE = 5

        fun canOverlay(context: Context): Boolean =
            Settings.canDrawOverlays(context)

        fun start(context: Context, field: String, pkg: String?, upToStep: Int) {
            val intent = Intent(context, CoordinatePickerService::class.java)
                .putExtra(EXTRA_FIELD, field)
                .putExtra(EXTRA_PACKAGE, pkg)
                .putExtra(EXTRA_UP_TO_STEP, upToStep)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private lateinit var windowManager: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var capture: View? = null
    private var hintView: View? = null
    private var dot: ImageView? = null
    private var panel: LinearLayout? = null
    private var coordinateText: TextView? = null
    private var currentX = 0
    private var currentY = 0
    private var fieldName = "home_xy"

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTI_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        fieldName = intent?.getStringExtra(EXTRA_FIELD) ?: "home_xy"
        val pkg = intent?.getStringExtra(EXTRA_PACKAGE)
        val upToStep = intent?.getIntExtra(EXTRA_UP_TO_STEP, 0) ?: 0

        if (upToStep > 0) {
            toast("前置步骤执行中…")
            scope.launch {
                val cfg = Config.load(this@CoordinatePickerService)
                val ok = try {
                    CheckInEngine.runSteps(this@CoordinatePickerService, cfg, { }, maxStep = upToStep)
                } catch (_: Throwable) {
                    false
                }
                handler.post {
                    toast(if (ok) "前置步骤完成，请点选位置" else "前置步骤未完成，仍可直接点选")
                    showCapture()
                    showHint()
                }
            }
        } else {
            showCapture()
            showHint()
            // Device.launch 会等待 Shizuku 绑定，必须在后台线程调用（绑定回调要主线程）
            handler.postDelayed({
                if (!pkg.isNullOrBlank()) {
                    thread(name = "launch-target") { Device.launch(this@CoordinatePickerService, pkg) }
                }
            }, 600)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        removeAll()
        super.onDestroy()
    }

    // ---------- 覆盖层 ----------

    private fun overlayType(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
    }

    /** 覆盖层窗口一律使用整屏坐标系，保证 lp.x/lp.y 与 MotionEvent.rawX/rawY 一致 */
    private fun baseFlags(): Int =
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

    private fun showCapture() {
        if (capture != null) return
        val view = View(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            baseFlags(),
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        view.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                onPicked(event.rawX.toInt(), event.rawY.toInt())
            }
            true
        }
        safeAdd(view, lp)
        capture = view
    }

    private fun showHint() {
        if (hintView != null) return
        val tv = TextView(this).apply {
            text = "请点击你想自定义的位置"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setBackgroundColor(Color.parseColor("#CC000000"))
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            baseFlags() or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(48) }
        safeAdd(tv, lp)
        hintView = tv
    }

    private fun onPicked(x: Int, y: Int) {
        currentX = x
        currentY = y
        showDot(x, y)
        showPanel(x, y)
    }

    private fun showDot(x: Int, y: Int) {
        dot?.let { runCatching { windowManager.removeView(it) } }
        val iv = ImageView(this).apply { setImageResource(R.drawable.picked_dot) }
        val half = dp(12)
        val lp = WindowManager.LayoutParams(
            dp(24), dp(24),
            overlayType(),
            baseFlags() or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x - half
            this.y = y - half
        }
        safeAdd(iv, lp)
        dot = iv
    }

    private fun showPanel(x: Int, y: Int) {
        panel?.let { runCatching { windowManager.removeView(it) } }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#E51F1F1F"))
            setPadding(dp(20), dp(14), dp(20), dp(14))
        }
        val text = TextView(this).apply {
            this.text = panelText(x, y)
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(0, 0, 0, dp(8))
        }
        coordinateText = text

        // 微调：上下左右各 5px
        val nudgeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        nudgeRow.addView(nudgeButton("←", -NUDGE, 0))
        nudgeRow.addView(nudgeButton("→", NUDGE, 0))
        nudgeRow.addView(nudgeButton("↑", 0, -NUDGE))
        nudgeRow.addView(nudgeButton("↓", 0, NUDGE))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val ok = Button(this).apply {
            this.text = "确认"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#FF2E7D32"))
            setOnClickListener { backToApp("$currentX,$currentY") }
        }
        val cancel = Button(this).apply {
            this.text = "取消"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#FF616161"))
            setOnClickListener { backToApp(null) }
        }
        row.addView(ok, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(
            View(this),
            LinearLayout.LayoutParams(dp(12), LinearLayout.LayoutParams.MATCH_PARENT)
        )
        row.addView(cancel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        container.addView(text)
        container.addView(nudgeRow)
        container.addView(row)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            baseFlags(),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            this.y = dp(80)
        }
        safeAdd(container, lp)
        panel = container
    }

    private fun nudgeButton(label: String, dx: Int, dy: Int): Button =
        Button(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#FF424242"))
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener {
                currentX += dx
                currentY += dy
                showDot(currentX, currentY)
                coordinateText?.text = panelText(currentX, currentY)
            }
        }

    private fun panelText(x: Int, y: Int): String {
        val size = Device.screenSize(this)
        return if (size != null) {
            "坐标：$x , $y（约 ${x * 100 / size.x}% , ${y * 100 / size.y}%）"
        } else {
            "坐标：$x , $y"
        }
    }

    /** 取消/确认都回到 App：xy 为 null 表示取消，不写入坐标 */
    private fun backToApp(xy: String?) {
        if (xy != null) {
            Config.setPicked(this, fieldName, xy)
        } else {
            Config.takePicked(this)
        }
        val back = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (xy != null) {
                putExtra(MainActivity.EXTRA_PICKED, xy)
                putExtra(MainActivity.EXTRA_PICKED_FIELD, fieldName)
            }
        }
        // 后台起 Activity 可能被系统限制，失败也不影响：坐标已存进 prefs，下次打开会自动填入
        runCatching { startActivity(back) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun removeAll() {
        listOfNotNull(capture, hintView, dot, panel).forEach {
            runCatching { windowManager.removeView(it) }
        }
        capture = null
        hintView = null
        dot = null
        panel = null
    }

    private fun safeAdd(view: View, lp: WindowManager.LayoutParams) {
        try {
            windowManager.addView(view, lp)
        } catch (_: Throwable) {
        }
    }

    private fun toast(msg: String) {
        handler.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    // ---------- 通知 ----------

    private fun buildNotification(): android.app.Notification {
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("坐标取点中")
            .setContentText("点击屏幕取点后确认")
            .setSmallIcon(R.drawable.ic_stat_check)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.monitor_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
