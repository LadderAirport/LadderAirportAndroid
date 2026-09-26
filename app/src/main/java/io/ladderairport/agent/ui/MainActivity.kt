package io.ladderairport.agent.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.AttrRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.color.DynamicColors
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.ladderairport.agent.LadderApplication
import io.ladderairport.agent.R
import io.ladderairport.agent.databinding.ActivityMainBinding
import io.ladderairport.agent.databinding.PageConfigBinding
import io.ladderairport.agent.databinding.PageDashboardBinding
import io.ladderairport.agent.databinding.PageLogsBinding
import io.ladderairport.agent.mobile.Mobile
import io.ladderairport.agent.model.AgentStatus
import io.ladderairport.agent.service.AgentService
import io.ladderairport.agent.util.QrCodeParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var dashboardBinding: PageDashboardBinding
    private lateinit var configBinding: PageConfigBinding
    private lateinit var logsBinding: PageLogsBinding

    private val prefs by lazy { LadderApplication.instance.prefs }
    private var lastConfigHash: String = ""

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (!isGranted) {
            Toast.makeText(this, "通知权限未授予，前台服务通知将无法展示", Toast.LENGTH_SHORT).show()
        }
    }

    private val scanQrLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            handleScannedQrContent(result.contents)
        }
    }

    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            launchQrScanner()
        } else {
            Toast.makeText(this, "需要相机权限以扫描配对二维码", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemInsets()

        dashboardBinding = PageDashboardBinding.inflate(layoutInflater, binding.viewPager, false)
        configBinding = PageConfigBinding.inflate(layoutInflater, binding.viewPager, false)
        logsBinding = PageLogsBinding.inflate(layoutInflater, binding.viewPager, false)

        setupNavigation()
        initDashboard()
        initConfig()
        initLogs()
        observeData()
        checkPermissions()

        if (prefs.autoStart && prefs.isConfigured() && prefs.enrolled) {
            if (AgentService.isServiceRunning.value != true) {
                AgentService.start(this)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadCurrentConfigDisplay()
        if (prefs.autoStart && prefs.isConfigured() && prefs.enrolled) {
            if (AgentService.isServiceRunning.value != true) {
                AgentService.start(this)
            }
        }
    }

    private fun applySystemInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val imeVisible = ime.bottom > 0
            binding.root.updatePadding(left = bars.left, right = bars.right)
            binding.appBar.updatePadding(top = bars.top)
            binding.bottomNav.visibility = if (imeVisible) View.GONE else View.VISIBLE
            binding.bottomNav.updatePadding(bottom = bars.bottom)
            binding.viewPager.updatePadding(bottom = if (imeVisible) ime.bottom else 0)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun setupNavigation() {
        val adapter = MainPagerAdapter(dashboardBinding, configBinding, logsBinding)
        binding.viewPager.adapter = adapter
        binding.viewPager.offscreenPageLimit = 2

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_dashboard -> binding.viewPager.currentItem = 0
                R.id.nav_config -> binding.viewPager.currentItem = 1
                R.id.nav_logs -> binding.viewPager.currentItem = 2
            }
            true
        }

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                when (position) {
                    0 -> binding.bottomNav.selectedItemId = R.id.nav_dashboard
                    1 -> {
                        binding.bottomNav.selectedItemId = R.id.nav_config
                        loadCurrentConfigDisplay()
                    }
                    2 -> binding.bottomNav.selectedItemId = R.id.nav_logs
                }
            }
        })
    }

    private fun initDashboard() {
        try {
            val agentVer = Mobile.getVersion()
            val singboxVer = Mobile.getSingboxVersion()
            dashboardBinding.tvCoreVersion.text = "sing-box $singboxVer"
            binding.tvAppSubtitle.text = "Agent $agentVer"
        } catch (_: Exception) {
            dashboardBinding.tvCoreVersion.text = "sing-box runtime"
        }

        updateDashboardConfigDisplay()

        dashboardBinding.btnPower.setOnClickListener {
            val isRunning = AgentService.isServiceRunning.value == true
            if (isRunning) {
                AgentService.stop(this)
            } else {
                if (!validateInputs()) {
                    binding.viewPager.currentItem = 1
                    return@setOnClickListener
                }
                saveInputsToPrefs()
                if (!prefs.enrolled) {
                    AlertDialog.Builder(this)
                        .setTitle("节点尚未注册")
                        .setMessage("该节点尚未向 Panel 注册（未换取控制令牌）。\n\n是否立即一键注册并启动 Agent？")
                        .setPositiveButton("立即注册并启动") { _, _ ->
                            performEnrollment(autoStartAfter = true)
                        }
                        .setNegativeButton("直接尝试启动") { _, _ ->
                            AgentService.start(this)
                        }
                        .setNeutralButton("取消", null)
                        .show()
                } else {
                    AgentService.start(this)
                }
            }
        }

        dashboardBinding.tvBatteryStatus.setOnClickListener {
            requestBatteryOptimizationExemption()
        }
    }

    private fun initConfig() {
        configBinding.etPanelUrl.setText(prefs.panelUrl)
        configBinding.etNodeId.setText(prefs.nodeId)
        configBinding.etToken.setText(prefs.token)
        configBinding.switchAutoStart.isChecked = prefs.autoStart

        configBinding.switchAutoStart.setOnCheckedChangeListener { _, isChecked ->
            prefs.autoStart = isChecked
        }

        configBinding.btnScanQrCode.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
            ) {
                launchQrScanner()
            } else {
                requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        configBinding.btnSaveConfig.setOnClickListener {
            saveInputsToPrefs()
            updateDashboardConfigDisplay()
            Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show()
        }

        configBinding.btnEnroll.setOnClickListener {
            performEnrollment(autoStartAfter = false)
        }

        val resetEnrollment = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!prefs.enrolled) return
                val url = configBinding.etPanelUrl.text?.toString()?.trim().orEmpty()
                val nodeId = configBinding.etNodeId.text?.toString()?.trim().orEmpty()
                if (url != prefs.panelUrl || nodeId != prefs.nodeId) {
                    prefs.enrolled = false
                    refreshEnrollmentUi()
                }
            }
        }
        configBinding.etPanelUrl.addTextChangedListener(resetEnrollment)
        configBinding.etNodeId.addTextChangedListener(resetEnrollment)
        refreshEnrollmentUi()

        configBinding.layoutBatteryOpt.setOnClickListener {
            requestBatteryOptimizationExemption()
        }

        configBinding.btnRefreshConfig.setOnClickListener {
            loadCurrentConfigDisplay()
            Toast.makeText(this, "已刷新下发配置", Toast.LENGTH_SHORT).show()
        }

        configBinding.btnCopyConfig.setOnClickListener {
            val text = configBinding.tvCurrentConfigContent.text?.toString().orEmpty()
            if (text.isNotBlank() && !text.startsWith("暂未")) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("LadderAirport Config", text))
                Toast.makeText(this, "节点配置已复制到剪贴板", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "暂无配置可复制", Toast.LENGTH_SHORT).show()
            }
        }
        loadCurrentConfigDisplay()
    }

    private fun loadCurrentConfigDisplay() {
        lifecycleScope.launch(Dispatchers.IO) {
            val configFile = File(filesDir, "current.json")
            if (configFile.exists() && configFile.length() > 0) {
                try {
                    val raw = configFile.readText()
                    val pretty = JSONObject(raw).toString(2)
                    val lastMod = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(configFile.lastModified()))
                    val sizeKb = String.format(Locale.US, "%.1f KB", configFile.length() / 1024.0)
                    withContext(Dispatchers.Main) {
                        configBinding.tvConfigStatus.text = "已同步 ($lastMod, $sizeKb)"
                        configBinding.tvCurrentConfigContent.text = pretty
                    }
                } catch (_: Exception) {
                    val raw = configFile.readText()
                    withContext(Dispatchers.Main) {
                        configBinding.tvConfigStatus.text = "已同步 (原始格式)"
                        configBinding.tvCurrentConfigContent.text = raw
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    configBinding.tvConfigStatus.text = "暂无生效的下发配置"
                    configBinding.tvCurrentConfigContent.text = "暂未从 Panel 获取到下发的节点配置 (current.json)\n请确认 Agent 服务已启动并成功连接至 Panel。"
                }
            }
        }
    }

    private fun initLogs() {
        logsBinding.btnClearLogs.setOnClickListener {
            LadderApplication.clearLogs()
        }

        logsBinding.btnCopyLogs.setOnClickListener {
            val lines = LadderApplication.logLines.value ?: emptyList()
            if (lines.isEmpty()) {
                Toast.makeText(this, "当前无日志", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("LadderAirport Logs", lines.joinToString("\n")))
            Toast.makeText(this, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshEnrollmentUi() {
        val enrolled = prefs.enrolled
        configBinding.cardQrPairing.visibility = if (enrolled) View.GONE else View.VISIBLE
        configBinding.btnEnroll.visibility = if (enrolled) View.GONE else View.VISIBLE
        configBinding.etToken.hint = if (enrolled) "控制令牌" else getString(R.string.token_hint)
    }

    private fun updateDashboardConfigDisplay() {
        val nodeId = prefs.nodeId
        val panelUrl = prefs.panelUrl
        if (nodeId.isNotBlank()) {
            dashboardBinding.tvHeroNodeName.text = nodeId
            dashboardBinding.tvHeroPanelUrl.text = panelUrl
        } else {
            dashboardBinding.tvHeroNodeName.text = "未配置节点"
            dashboardBinding.tvHeroPanelUrl.text = "请切换至「配置」页面填入或扫码导入信息"
        }
    }

    private fun launchQrScanner() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("请对准 Panel 节点配对二维码")
            setCameraId(0)
            setBeepEnabled(true)
            setBarcodeImageEnabled(false)
            setOrientationLocked(false)
        }
        scanQrLauncher.launch(options)
    }

    private fun handleScannedQrContent(rawText: String) {
        val info = QrCodeParser.parse(rawText)
        if (info == null) {
            AlertDialog.Builder(this)
                .setTitle("二维码无效")
                .setMessage("未能识别有效的 LadderAirport 配对参数：\n\n$rawText")
                .setPositiveButton("确定", null)
                .show()
            return
        }

        // Fill inputs
        configBinding.etPanelUrl.setText(info.panelUrl)
        configBinding.etNodeId.setText(info.nodeId)
        configBinding.etToken.setText(info.token)
        saveInputsToPrefs()
        updateDashboardConfigDisplay()

        AlertDialog.Builder(this)
            .setTitle("扫码配对成功")
            .setMessage("已识别节点配置：\n\nPanel: ${info.panelUrl}\n节点 ID: ${info.nodeId}\n\n是否立即一键注册 (Enroll) 并启动 Agent？")
            .setPositiveButton("注册并启动") { _, _ ->
                performEnrollment(autoStartAfter = true)
            }
            .setNegativeButton("仅保存配置") { _, _ ->
                binding.viewPager.currentItem = 0
            }
            .show()
    }

    private fun observeData() {
        AgentService.isServiceRunning.observe(this) { isRunning ->
            updateRunningState(isRunning)
        }

        AgentService.currentStatus.observe(this) { status ->
            updateStatusDetails(status)
        }

        LadderApplication.logLines.observe(this) { lines ->
            logsBinding.tvTerminalOutput.text = if (lines.isEmpty()) {
                "等待日志输出..."
            } else {
                lines.joinToString("\n")
            }
            logsBinding.tvLogCount.text = "${lines.size} lines"
            logsBinding.scrollLogs.post {
                logsBinding.scrollLogs.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun updateRunningState(isRunning: Boolean) {
        if (isRunning) {
            dashboardBinding.btnPower.text = "停止 Agent 服务"
            dashboardBinding.btnPower.backgroundTintList =
                ColorStateList.valueOf(themeColor(androidx.appcompat.R.attr.colorError))
            dashboardBinding.btnPower.setTextColor(themeColor(com.google.android.material.R.attr.colorOnError))
            dashboardBinding.btnPower.iconTint =
                ColorStateList.valueOf(themeColor(com.google.android.material.R.attr.colorOnError))

            configBinding.etPanelUrl.isEnabled = false
            configBinding.etNodeId.isEnabled = false
            configBinding.etToken.isEnabled = false
            configBinding.btnEnroll.isEnabled = false
            configBinding.cardQrPairing.visibility = View.GONE
        } else {
            dashboardBinding.btnPower.text = getString(R.string.btn_start)
            dashboardBinding.btnPower.backgroundTintList =
                ColorStateList.valueOf(themeColor(androidx.appcompat.R.attr.colorPrimary))
            dashboardBinding.btnPower.setTextColor(themeColor(com.google.android.material.R.attr.colorOnPrimary))
            dashboardBinding.btnPower.iconTint =
                ColorStateList.valueOf(themeColor(com.google.android.material.R.attr.colorOnPrimary))

            configBinding.etPanelUrl.isEnabled = true
            configBinding.etNodeId.isEnabled = true
            configBinding.etToken.isEnabled = true
            configBinding.btnEnroll.isEnabled = true
            refreshEnrollmentUi()
        }
        refreshStatusChrome()
    }

    private fun updateStatusDetails(status: AgentStatus) {
        dashboardBinding.tvMetricUplink.text = formatBytes(status.uplinkBytes)
        dashboardBinding.tvMetricDownlink.text = formatBytes(status.downlinkBytes)
        dashboardBinding.tvMetricConns.text = status.connections.toString()
        dashboardBinding.tvMetricUptime.text = formatUptime(status.uptimeSecs)
        dashboardBinding.tvConfigHash.text = if (status.configHash.isNotBlank()) {
            if (status.configHash != lastConfigHash) {
                lastConfigHash = status.configHash
                loadCurrentConfigDisplay()
            }
            if (status.configHash.length > 12) status.configHash.take(12) else status.configHash
        } else {
            "未同步"
        }

        if (status.lastError.isNotBlank() && !status.running) {
            dashboardBinding.tvHeroError.visibility = View.VISIBLE
            dashboardBinding.tvHeroError.text = "异常: ${status.lastError}"
        } else {
            dashboardBinding.tvHeroError.visibility = View.GONE
        }
        refreshStatusChrome()
    }

    private fun refreshStatusChrome() {
        val running = AgentService.isServiceRunning.value == true
        val status = AgentService.currentStatus.value
        val kind = when {
            status != null && status.lastError.isNotBlank() && !running -> StatusKind.ERROR
            running -> StatusKind.RUNNING
            else -> StatusKind.STOPPED
        }
        val dashboardLabel = when (kind) {
            StatusKind.RUNNING -> "运行中"
            StatusKind.ERROR -> "运行异常"
            StatusKind.STOPPED -> getString(R.string.status_stopped)
        }
        val topLabel = when (kind) {
            StatusKind.RUNNING -> "在线"
            StatusKind.ERROR -> "异常"
            StatusKind.STOPPED -> getString(R.string.status_stopped)
        }
        applyStatus(dashboardBinding.pillStatus, dashboardBinding.dotStatus, dashboardBinding.tvStatusText, kind, dashboardLabel)
        applyStatus(binding.topStatusBadge, binding.topStatusDot, binding.topStatusText, kind, topLabel)
    }

    private fun applyStatus(badge: View, dot: View, label: TextView, kind: StatusKind, text: String) {
        val (bg, fg) = when (kind) {
            StatusKind.RUNNING -> getColor(R.color.status_running_bg) to getColor(R.color.status_running)
            StatusKind.STOPPED ->
                themeColor(com.google.android.material.R.attr.colorSurfaceContainerHigh) to
                    themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
            StatusKind.ERROR ->
                themeColor(com.google.android.material.R.attr.colorErrorContainer) to
                    themeColor(com.google.android.material.R.attr.colorOnErrorContainer)
        }
        badge.backgroundTintList = ColorStateList.valueOf(bg)
        dot.backgroundTintList = ColorStateList.valueOf(fg)
        label.text = text
        label.setTextColor(fg)
        badge.contentDescription = text
    }

    private fun themeColor(@AttrRes attr: Int): Int {
        val typedValue = TypedValue()
        theme.resolveAttribute(attr, typedValue, true)
        return if (typedValue.resourceId != 0) {
            ContextCompat.getColor(this, typedValue.resourceId)
        } else {
            typedValue.data
        }
    }

    private fun validateInputs(): Boolean {
        val url = configBinding.etPanelUrl.text?.toString()?.trim() ?: ""
        val nodeId = configBinding.etNodeId.text?.toString()?.trim() ?: ""
        val token = configBinding.etToken.text?.toString()?.trim() ?: ""

        if (url.isBlank()) {
            configBinding.etPanelUrl.error = "请输入 Panel 基础地址"
            return false
        }
        if (nodeId.isBlank()) {
            configBinding.etNodeId.error = "请输入节点 ID"
            return false
        }
        if (token.isBlank()) {
            configBinding.etToken.error = "请输入令牌"
            return false
        }
        return true
    }

    private fun saveInputsToPrefs() {
        prefs.panelUrl = configBinding.etPanelUrl.text?.toString()?.trim() ?: ""
        prefs.nodeId = configBinding.etNodeId.text?.toString()?.trim() ?: ""
        prefs.token = configBinding.etToken.text?.toString()?.trim() ?: ""
    }

    private fun performEnrollment(autoStartAfter: Boolean = false) {
        if (!validateInputs()) {
            binding.viewPager.currentItem = 1
            return
        }
        saveInputsToPrefs()
        updateDashboardConfigDisplay()

        val progress = AlertDialog.Builder(this)
            .setTitle("正在注册节点")
            .setMessage("正在向 Panel 交换控制令牌，不申请管理面证书...")
            .setCancelable(false)
            .create()
        progress.show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val enrollJson = JSONObject().apply {
                    put("panel_url", prefs.panelUrl)
                    put("node_id", prefs.nodeId)
                    put("enroll_token", prefs.token)
                    put("data_dir", filesDir.absolutePath)
                }.toString()

                val resJson = Mobile.enroll(enrollJson)
                val resObj = JSONObject(resJson)

                withContext(Dispatchers.Main) {
                    if (progress.isShowing) {
                        runCatching { progress.dismiss() }
                    }
                    if (isFinishing || isDestroyed) return@withContext

                    if (resObj.optBoolean("ok", false)) {
                        val issuedToken = resObj.optString("token", "")
                        if (issuedToken.isNotBlank()) {
                            prefs.token = issuedToken
                            configBinding.etToken.setText(issuedToken)
                        }
                        prefs.enrolled = true
                        refreshEnrollmentUi()
                        LadderApplication.appendLog("节点注册成功：已取得控制令牌，未初始化管理面 TLS")

                        if (autoStartAfter) {
                            Toast.makeText(this@MainActivity, "注册成功，正在启动 Agent...", Toast.LENGTH_SHORT).show()
                            binding.viewPager.currentItem = 0
                            AgentService.start(this@MainActivity)
                        } else {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("注册成功")
                                .setMessage("控制令牌已保存。该节点通过 HTTP 上报和 WebSocket 收配置，不使用管理面证书。现在可以启动 Agent。")
                                .setPositiveButton("立即前往仪表盘") { _, _ ->
                                    binding.viewPager.currentItem = 0
                                }
                                .setNegativeButton("关闭", null)
                                .show()
                        }
                    } else {
                        val errMsg = resObj.optString("error", "未知注册失败")
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("注册失败 (HTTP 400/500)")
                            .setMessage(errMsg)
                            .setPositiveButton("确定", null)
                            .show()
                        LadderApplication.appendLog("注册失败: $errMsg")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (progress.isShowing) {
                        runCatching { progress.dismiss() }
                    }
                    if (isFinishing || isDestroyed) return@withContext

                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("注册异常")
                        .setMessage(e.message ?: "网络连接异常")
                        .setPositiveButton("确定", null)
                        .show()
                    LadderApplication.appendLog("注册异常: ${e.message}")
                }
            }
        }
    }

    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (_: Exception) {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                startActivity(intent)
            }
        } else {
            Toast.makeText(this, "已获取忽略电池优化白名单", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(java.util.Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(java.util.Locale.US, "%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format(java.util.Locale.US, "%.2f GB", gb)
    }

    private fun formatUptime(seconds: Long): String {
        if (seconds <= 0) return "0s"
        val hrs = seconds / 3600
        val mins = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (hrs > 0) {
            String.format(java.util.Locale.US, "%dh %02dm %02ds", hrs, mins, secs)
        } else if (mins > 0) {
            String.format(java.util.Locale.US, "%dm %02ds", mins, secs)
        } else {
            "${secs}s"
        }
    }

}

private enum class StatusKind {
    RUNNING,
    STOPPED,
    ERROR,
}
