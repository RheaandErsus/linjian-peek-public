package dev.linjian.healthbridge

import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private lateinit var serverInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var deviceInput: EditText
    private lateinit var permissionStatus: TextView
    private lateinit var syncStatus: TextView
    private lateinit var autoSync: CheckBox
    private lateinit var syncButton: Button

    private val healthPermissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) {
        refreshPermissionStatus()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Erses 健康桥"
        setContentView(buildUi())
        loadSettings()
        bindActions()
        refreshPermissionStatus()
        refreshLastSync()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStatus()
        refreshLastSync()
    }

    private fun buildUi(): ScrollView {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(28))
            setBackgroundColor(Color.rgb(244, 251, 247))
        }
        scroll.addView(root, ViewGroup.LayoutParams(-1, -2))

        root.addView(text("Erses 健康桥", 25, true))
        root.addView(text("只读取你授权的 Health Connect 数据，并把摘要发到自己的掌心窗后端。不是医疗诊断。", 13, false).apply {
            setTextColor(Color.rgb(80, 105, 96))
            setPadding(0, dp(6), 0, dp(18))
        })

        root.addView(sectionTitle("1. 连接掌心窗"))
        serverInput = input("后端地址，例如 https://…onrender.com")
        tokenInput = input("LINJIAN_TOKEN").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        deviceInput = input("设备 ID，例如 my-phone")
        root.addView(serverInput)
        root.addView(tokenInput)
        root.addView(deviceInput)
        root.addView(button("保存连接设置").apply {
            setOnClickListener {
                saveConnection()
                syncStatus.text = "连接设置已保存在本机。"
            }
        })

        root.addView(sectionTitle("2. 授权读取"))
        permissionStatus = text("正在检查 Health Connect…", 13, false)
        root.addView(permissionStatus)
        root.addView(button("选择允许读取的数据").apply {
            setOnClickListener { requestHealthPermissions() }
        })

        root.addView(sectionTitle("3. 同步与自动照看"))
        syncStatus = text("", 13, false)
        root.addView(syncStatus)
        syncButton = button("立即读取并上传").apply {
            setOnClickListener { syncNow() }
        }
        root.addView(syncButton)
        autoSync = CheckBox(this).apply {
            text = "每小时自动同步一次"
            textSize = 14f
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(autoSync)
        root.addView(text("自动同步需要“允许在后台读取健康数据”。系统若未提供该权限，仍可使用上面的手动同步。Token 只发送给你填写的 HTTPS 后端。", 12, false).apply {
            setTextColor(Color.rgb(95, 112, 106))
        })
        return scroll
    }

    private fun bindActions() {
        autoSync.setOnCheckedChangeListener { _, enabled ->
            BridgePrefs.setAutoSync(this, enabled)
            if (enabled) scheduleHourlySync() else WorkManager.getInstance(this).cancelUniqueWork(WORK_NAME)
            refreshLastSync()
        }
    }

    private fun loadSettings() {
        serverInput.setText(BridgePrefs.server(this))
        tokenInput.setText(BridgePrefs.token(this))
        deviceInput.setText(BridgePrefs.device(this))
        autoSync.isChecked = BridgePrefs.autoSync(this)
    }

    private fun saveConnection() {
        BridgePrefs.saveConnection(
            this,
            serverInput.text.toString(),
            tokenInput.text.toString(),
            deviceInput.text.toString()
        )
    }

    private fun requestHealthPermissions() {
        val status = HealthConnectClient.getSdkStatus(this)
        if (status != HealthConnectClient.SDK_AVAILABLE) {
            permissionStatus.text = "Health Connect 当前不可用，请确认“健康数据共享”已经安装并启用。"
            return
        }
        healthPermissionLauncher.launch(HealthPermissions.requested(this))
    }

    private fun refreshPermissionStatus() {
        val status = HealthConnectClient.getSdkStatus(this)
        if (status != HealthConnectClient.SDK_AVAILABLE) {
            permissionStatus.text = "Health Connect 不可用"
            syncButton.isEnabled = false
            return
        }
        lifecycleScope.launch {
            val foreground = HealthPermissions.hasForeground(this@MainActivity)
            val background = HealthPermissions.hasBackground(this@MainActivity)
            permissionStatus.text = when {
                foreground && background -> "已允许五类健康数据读取，也允许后台同步。"
                foreground -> "已允许五类健康数据读取；后台读取尚未允许。"
                else -> "尚未完整授权：步数、活动、睡眠、心率和血氧。"
            }
            syncButton.isEnabled = foreground
        }
    }

    private fun syncNow() {
        saveConnection()
        syncButton.isEnabled = false
        syncStatus.text = "正在读取 Health Connect 并上传…"
        lifecycleScope.launch {
            try {
                val response = HealthUploader.collectAndUpload(this@MainActivity)
                val today = response.getJSONObject("health").getJSONObject("today")
                syncStatus.text = "同步成功：今日 ${today.optLong("steps")} 步；最近数据已交给掌心窗。"
            } catch (e: Exception) {
                BridgePrefs.recordError(this@MainActivity, e.message ?: e.javaClass.simpleName)
                syncStatus.text = "同步失败：${e.message ?: e.javaClass.simpleName}"
            } finally {
                refreshPermissionStatus()
            }
        }
    }

    private fun scheduleHourlySync() {
        saveConnection()
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<HealthSyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    private fun refreshLastSync() {
        val ok = BridgePrefs.lastOk(this)
        val error = BridgePrefs.lastError(this)
        syncStatus.text = when {
            error.isNotBlank() -> "最近一次：$error"
            ok.isNotBlank() -> "最近同步成功：$ok"
            else -> "还没有上传过健康摘要。"
        }
    }

    private fun sectionTitle(value: String) = text(value, 17, true).apply {
        setPadding(0, dp(18), 0, dp(8))
    }

    private fun text(value: String, size: Int, bold: Boolean) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(Color.rgb(38, 61, 53))
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun input(hintValue: String) = EditText(this).apply {
        hint = hintValue
        textSize = 13f
        isSingleLine = true
        setPadding(dp(12), dp(10), dp(12), dp(10))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(8)
        }
    }

    private fun button(label: String) = Button(this).apply {
        text = label
        textSize = 13f
        isAllCaps = false
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(-1, dp(48)).apply {
            topMargin = dp(4)
            bottomMargin = dp(5)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    companion object {
        private const val WORK_NAME = "erses-health-hourly-sync"
    }
}
