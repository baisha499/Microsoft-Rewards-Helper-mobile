package com.baisha.MicrosoftRewardsHelper

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 签到配置（独立页面，不用悬浮窗） */
class StepConfigActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PICKED = MainActivity.EXTRA_PICKED
        const val EXTRA_PICKED_FIELD = MainActivity.EXTRA_PICKED_FIELD
    }

    private var pendingPick: Pair<String, String>? = null

    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (CoordinatePickerService.canOverlay(this)) {
            Toast.makeText(this, "已获得悬浮窗权限，请再点一次「选择」", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "未获得悬浮窗权限，无法取点", Toast.LENGTH_SHORT).show()
        }
    }

    private lateinit var etPackages: EditText
    private lateinit var etHomeXy: EditText
    private lateinit var etPoints: EditText
    private lateinit var etPointsXy: EditText
    private lateinit var etCheckin: EditText
    private lateinit var etCheckinXy: EditText
    private lateinit var etThreshold: EditText
    private lateinit var etPoll: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_step_config)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        val content = findViewById<View>(R.id.content)
        etPackages = content.findViewById(R.id.etPackages)
        etHomeXy = content.findViewById(R.id.etHomeXy)
        etPoints = content.findViewById(R.id.etStepPoints)
        etPointsXy = content.findViewById(R.id.etPointsXy)
        etCheckin = content.findViewById(R.id.etStepCheckin)
        etCheckinXy = content.findViewById(R.id.etCheckinXy)
        etThreshold = content.findViewById(R.id.etThreshold)
        etPoll = content.findViewById(R.id.etPoll)

        val cfg = Config.load(this)
        etPackages.setText(cfg.packages.joinToString("\n"))
        etHomeXy.setText(cfg.homeStep.fallbackRaw ?: "")
        etPoints.setText(cfg.pointsStep.keywords.joinToString("|"))
        etPointsXy.setText(cfg.pointsStep.fallbackRaw ?: "")
        etCheckin.setText(cfg.checkinStep.keywords.joinToString("|"))
        etCheckinXy.setText(cfg.checkinStep.fallbackRaw ?: "")
        etThreshold.setText(cfg.dayThreshold.toString())
        etPoll.setText(cfg.pollSec.toString())

        setupToggle(R.id.toggleTarget, R.id.bodyTarget)
        setupToggle(R.id.toggleStep1, R.id.bodyStep1)
        setupToggle(R.id.toggleStep2, R.id.bodyStep2)
        setupToggle(R.id.toggleStep3, R.id.bodyStep3)
        setupToggle(R.id.toggleOther, R.id.bodyOther)

        content.findViewById<MaterialButton>(R.id.btnPickPackage)
            .setOnClickListener { pickPackage() }
        content.findViewById<MaterialButton>(R.id.btnGrantApps)
            .setOnClickListener {
                if (!AppList.hasPermission(this)) {
                    androidx.core.app.ActivityCompat.requestPermissions(
                        this,
                        arrayOf(android.Manifest.permission.QUERY_ALL_PACKAGES),
                        101
                    )
                } else {
                    Toast.makeText(this, "已具备查看应用列表权限", Toast.LENGTH_SHORT).show()
                }
            }
        content.findViewById<MaterialButton>(R.id.btnPickHomeXy)
            .setOnClickListener { startPick("home_xy", etHomeXy) }
        content.findViewById<MaterialButton>(R.id.btnPickPointsXy)
            .setOnClickListener { startPick("points_xy", etPointsXy) }
        content.findViewById<MaterialButton>(R.id.btnPickCheckinXy)
            .setOnClickListener { startPick("checkin_xy", etCheckinXy) }

        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener { save() }

        intent?.let { readPickResult(it) }
        pendingPick?.let { (field, xy) -> applyPicked(field, xy) }
        pendingPick = null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readPickResult(intent)
        pendingPick?.let { (field, xy) -> applyPicked(field, xy) }
        pendingPick = null
    }

    private fun readPickResult(src: Intent) {
        val xy = src.getStringExtra(EXTRA_PICKED) ?: return
        val field = src.getStringExtra(EXTRA_PICKED_FIELD) ?: return
        src.removeExtra(EXTRA_PICKED)
        src.removeExtra(EXTRA_PICKED_FIELD)
        pendingPick = field to xy
    }

    private fun applyPicked(field: String, xy: String) {
        val target = when (field) {
            "home_xy" -> etHomeXy.also { openSection(R.id.toggleStep1, R.id.bodyStep1) }
            "points_xy" -> etPointsXy.also { openSection(R.id.toggleStep2, R.id.bodyStep2) }
            else -> etCheckinXy.also { openSection(R.id.toggleStep3, R.id.bodyStep3) }
        }
        if (target.text.isNullOrBlank()) target.setText(xy)
        Toast.makeText(this, "已填入坐标：$xy", Toast.LENGTH_SHORT).show()
    }

    private fun setupToggle(toggleId: Int, bodyId: Int) {
        val toggle = findViewById<ImageButton>(toggleId)
        val body = findViewById<View>(bodyId)
        toggle.setOnClickListener {
            val open = body.visibility != View.VISIBLE
            body.visibility = if (open) View.VISIBLE else View.GONE
            val anim = if (open) android.R.anim.fade_in else android.R.anim.fade_out
            body.startAnimation(AnimationUtils.loadAnimation(this, anim))
        }
    }

    private fun openSection(toggleId: Int, bodyId: Int) {
        val body = findViewById<View>(bodyId)
        if (body.visibility != View.VISIBLE) {
            body.visibility = View.VISIBLE
        }
    }

    private fun save(finishAfter: Boolean = true) {
        Config.save(this) {
            putString("packages", etPackages.text.toString().trim())
            putString("home_xy", etHomeXy.text.toString().trim())
            putString("points", etPoints.text.toString().trim())
            putString("points_xy", etPointsXy.text.toString().trim())
            putString("checkin", etCheckin.text.toString().trim())
            putString("checkin_xy", etCheckinXy.text.toString().trim())
            putInt(
                "day_threshold",
                etThreshold.text.toString().trim().toIntOrNull()?.coerceIn(1, 200)
                    ?: AppConfig.DEFAULT_DAY_THRESHOLD
            )
            putInt(
                "poll_sec",
                etPoll.text.toString().trim().toIntOrNull()?.coerceIn(1, 60)
                    ?: AppConfig.DEFAULT_POLL_SEC
            )
        }
        if (finishAfter) {
            val saved = Config.load(this)
            Toast.makeText(
                this,
                "✓ 配置已保存并生效（包名 ${saved.packages.size} 个，轮询 ${saved.pollSec}s）",
                Toast.LENGTH_SHORT
            ).show()
            finish()
        }
    }

    // ---------- 应用选择 ----------

    private fun pickPackage() {
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) { AppList.load(this@StepConfigActivity) }
            val list = apps.filter {
                it.pkg.contains("bing", true) || it.pkg.contains("microsoft", true) ||
                    it.pkg.contains("msn", true) || it.label.contains("必应") || it.label.contains("Bing", true)
            }.ifEmpty { apps }
            if (list.isEmpty()) {
                Toast.makeText(
                    this@StepConfigActivity,
                    "读不到应用列表，可先在上级设置里授权应用列表权限",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            AlertDialog.Builder(this@StepConfigActivity)
                .setTitle("选择目标应用")
                .setAdapter(
                    ArrayAdapter(
                        this@StepConfigActivity,
                        android.R.layout.simple_list_item_2,
                        android.R.id.text1,
                        list.map { it.display }
                    )
                ) { _, which -> etPackages.setText(list[which].pkg) }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    // ---------- 悬浮窗取点 ----------

    private fun startPick(field: String, target: EditText) {
        if (!CoordinatePickerService.canOverlay(this)) {
            Toast.makeText(this, "需要先授予悬浮窗权限", Toast.LENGTH_LONG).show()
            overlayLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }
        if (!Shell.hasPermission()) {
            Toast.makeText(this, "取点还需要 Shizuku 权限（用于拉起目标应用）", Toast.LENGTH_LONG).show()
            return
        }
        val pkg = Config.load(this).packages.firstOrNull()
        if (pkg == null) {
            Toast.makeText(this, "请先填写目标包名", Toast.LENGTH_SHORT).show()
            return
        }
        // 先保存（不关闭页面），再启动取点服务：页面要保持前台，否则前台服务会被系统拒绝启动
        save(finishAfter = false)
        // 取第 N 步的坐标时，先把前面的步骤跑完，进入对应界面再让用户点
        val upToStep = when (field) {
            "points_xy" -> 1
            "checkin_xy" -> 2
            else -> 0
        }
        Toast.makeText(
            this,
            if (upToStep > 0) "取点：先执行前 $upToStep 步进入界面，再点选位置"
            else "已进入取点模式：点屏幕选位置后确认",
            Toast.LENGTH_LONG
        ).show()
        runCatching {
            CoordinatePickerService.start(this, field, pkg, upToStep)
        }.onFailure {
            Toast.makeText(this, "取点服务启动失败：${it.message}", Toast.LENGTH_LONG).show()
        }
    }
}
