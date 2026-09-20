package com.baisha.MicrosoftRewardsHelper

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 后台轮询前台包名：命中目标包名（必应）时自动执行签到流程。
 */
class MonitorService : Service() {

    companion object {
        private const val CHANNEL_ID = "monitor"
        private const val NOTI_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, MonitorService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitorService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private lateinit var notificationManager: NotificationManager
    private var lastLines: List<String> = emptyList()

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTI_ID,
            buildNotification("正在监控前台应用…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (loopJob?.isActive != true) {
            loopJob = scope.launch { loop() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        loopJob?.cancel()
        super.onDestroy()
    }

    private suspend fun loop() {
        while (true) {
            val cfg = Config.load(this@MonitorService)
            if (!Shell.hasPermission()) {
                updateNotification(listOf("Shizuku 未授权，等待授权"))
                delay(5_000)
                continue
            }
            if (!Device.isScreenOn(this@MonitorService)) {
                delay(cfg.pollSec * 1000L)
                continue
            }
            val pkg = Device.foregroundPackage(this@MonitorService)
            updateNotification(listOf("当前前台：${pkg ?: "未知"}"))
            if (pkg != null && cfg.packages.contains(pkg)) {
                val canRun = !cfg.oncePerDay || !Config.alreadyRanToday(this@MonitorService)
                if (canRun) {
                    log("检测到 $pkg，开始执行签到")
                    val ok = CheckInEngine.runOnce(this@MonitorService, cfg) { msg -> log(msg) }
                    if (ok) {
                        Config.markRanToday(this@MonitorService)
                        log("本次签到完成")
                    } else {
                        log("本次签到未完成，稍后重试")
                    }
                }
            }
            delay(cfg.pollSec * 1000L)
        }
    }

    private fun log(msg: String) {
        val lines = (lastLines + msg).takeLast(4)
        lastLines = lines
        if (lastLines.isNotEmpty()) {
            handler.post { updateNotification(lastLines) }
        }
    }

    private fun updateNotification(lines: List<String>) {
        handler.post {
            val notification = buildNotification(lines.joinToString("\n"))
            notificationManager.notify(NOTI_ID, notification)
        }
    }

    private fun buildNotification(text: String): android.app.Notification {
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("必应自动签到")
            .setContentText(text.replace('\n', ' '))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
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
        notificationManager.createNotificationChannel(channel)
    }
}
