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
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 设置首页：只做导航和权限，不弹悬浮窗 */
class SettingsActivity : AppCompatActivity() {

    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshPermissionStatus() }

    /** 备份导出：弹出系统文件资源管理器，由用户自选保存位置 */
    private val createDocLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri == null) {
            toast("已取消备份")
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(Config.exportConfigText(this@SettingsActivity).toByteArray(Charsets.UTF_8))
                    }
                }.isSuccess
            }
            toast(if (ok) "✓ 配置已备份到所选位置" else "✗ 备份写入失败")
        }
    }

    /** 导入备份：弹出系统文件资源管理器，由用户自选备份文件 */
    private val openDocLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) {
            toast("已取消导入")
            return@registerForActivityResult
        }
        AlertDialog.Builder(this)
            .setTitle("导入备份")
            .setMessage("将用备份文件覆盖当前全部配置，确定继续吗？")
            .setNegativeButton("取消", null)
            .setPositiveButton("导入") { _, _ -> importFromUri(uri) }
            .show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        findViewById<android.view.View>(R.id.rowConfig).setOnClickListener {
            startActivity(android.content.Intent(this, StepConfigActivity::class.java))
        }

        findViewById<MaterialButton>(R.id.btnGrantApps).setOnClickListener { requestApps() }
        findViewById<MaterialButton>(R.id.btnGrantOverlay).setOnClickListener { requestOverlay() }
        findViewById<MaterialButton>(R.id.btnGrantNotify).setOnClickListener { requestNotify() }

        findViewById<MaterialButton>(R.id.btnClearCache).setOnClickListener { clearCache() }
        findViewById<MaterialButton>(R.id.btnBackup).setOnClickListener { exportBackup() }
        findViewById<MaterialButton>(R.id.btnImport).setOnClickListener {
            openDocLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
        }

        refreshPermissionStatus()
        refreshCacheSize()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStatus()
        refreshCacheSize()
    }

    // ---------- 数据管理 ----------

    private fun refreshCacheSize() {
        lifecycleScope.launch {
            val size = withContext(Dispatchers.IO) { Config.cacheSize(this@SettingsActivity) }
            findViewById<TextView>(R.id.tvCacheSize).text =
                "缓存大小：${Config.formatSize(size)}"
        }
    }

    private fun clearCache() {
        lifecycleScope.launch {
            val cleared = withContext(Dispatchers.IO) { Config.clearCache(this@SettingsActivity) }
            toast("✓ 已清理 ${Config.formatSize(cleared)} 缓存（用户配置未受影响）")
            refreshCacheSize()
        }
    }

    private fun exportBackup() {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        createDocLauncher.launch("microsoft_rewards_helper_config_$stamp.json")
    }

    private fun importFromUri(uri: Uri) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val text = runCatching {
                    contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                }.getOrNull()
                if (text.isNullOrBlank()) false
                else Config.importConfigText(this@SettingsActivity, text)
            }
            if (result) {
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("导入成功")
                    .setMessage("配置已恢复，需要重启应用后完全生效。")
                    .setCancelable(false)
                    .setPositiveButton("立即重启") { _, _ -> restartAppProcess() }
                    .show()
            } else {
                toast("✗ 导入失败：不是有效的备份文件")
            }
        }
    }

    /** 配置恢复后重启进程，保证所有界面与服务重新加载配置 */
    private fun restartAppProcess() {
        val restart = packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ?: Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val pi = PendingIntent.getActivity(
            this,
            888,
            restart,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT
        )
        (getSystemService(ALARM_SERVICE) as AlarmManager)
            .set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 400, pi)
        finishAffinity()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            android.os.Process.killProcess(android.os.Process.myPid())
        }, 800)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ---------- 权限 ----------

    private fun refreshPermissionStatus() {
        val tv = findViewById<TextView>(R.id.tvPermStatus)
        tv.text = buildString {
            append("应用列表：").append(yesNo(AppList.hasPermission(this@SettingsActivity))).append('\n')
            append("悬浮窗：").append(yesNo(CoordinatePickerService.canOverlay(this@SettingsActivity))).append('\n')
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                append("通知：").append(
                    yesNo(
                        ContextCompat.checkSelfPermission(
                            this@SettingsActivity,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) == PackageManager.PERMISSION_GRANTED
                    )
                )
            }
        }
    }

    private fun yesNo(ok: Boolean): String = if (ok) "已授予" else "未授予"

    private fun requestApps() {
        if (AppList.hasPermission(this)) {
            Toast.makeText(this, "已具备查看应用列表权限", Toast.LENGTH_SHORT).show()
            return
        }
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.QUERY_ALL_PACKAGES), 101)
        Toast.makeText(this, "若未弹窗通常会自动授予；部分系统需在设置里开启", Toast.LENGTH_LONG).show()
    }

    private fun requestOverlay() {
        if (CoordinatePickerService.canOverlay(this)) {
            Toast.makeText(this, "已具备悬浮窗权限", Toast.LENGTH_SHORT).show()
            return
        }
        overlayLauncher.launch(
            android.content.Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")
            )
        )
    }

    private fun requestNotify() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, "该系统版本无需动态申请", Toast.LENGTH_SHORT).show()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "已具备通知权限", Toast.LENGTH_SHORT).show()
            return
        }
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
    }
}
