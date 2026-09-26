package io.ladderairport.agent.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.MutableLiveData
import com.google.gson.Gson
import io.ladderairport.agent.LadderApplication
import io.ladderairport.agent.R
import io.ladderairport.agent.mobile.Host
import io.ladderairport.agent.mobile.Mobile
import io.ladderairport.agent.mobile.Runner
import io.ladderairport.agent.model.AgentStatus
import io.ladderairport.agent.ui.MainActivity
import io.ladderairport.agent.util.DeviceMetricsHelper
import io.ladderairport.agent.util.NetworkInterfaceHelper
import kotlinx.coroutines.*
import org.json.JSONObject

class AgentService : Service(), Host {

    companion object {
        const val ACTION_START = "io.ladderairport.agent.action.START"
        const val ACTION_STOP = "io.ladderairport.agent.action.STOP"
        const val NOTIFICATION_ID = 1001

        val isServiceRunning = MutableLiveData(false)
        val currentStatus = MutableLiveData(AgentStatus())

        fun start(context: Context) {
            val intent = Intent(context, AgentService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, AgentService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private var runner: Runner? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val isStarting = java.util.concurrent.atomic.AtomicBoolean(false)
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var statusMonitorJob: Job? = null
    private val gson = Gson()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LadderAirport:AgentLock").apply {
            setReferenceCounted(false)
        }
        setupNetworkMonitoring()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopAgent()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                startAgent()
            }
        }
        return START_STICKY
    }

    private fun startAgent() {
        // Must call startForeground unconditionally to avoid ForegroundServiceDidNotStartInTimeException
        val notification = buildNotification(
            if (isServiceRunning.value == true) "Agent 运行中" else "正在启动 Agent..."
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (isServiceRunning.value == true || isStarting.getAndSet(true)) return

        wakeLock?.let {
            if (!it.isHeld) {
                it.acquire(24 * 60 * 60 * 1000L) // 24 hours max safety wake lock
            }
        }

        serviceScope.launch {
            try {
                val prefs = LadderApplication.instance.prefs
                val dataDir = filesDir.absolutePath

                val cfgJson = JSONObject().apply {
                    put("panel_url", prefs.panelUrl)
                    put("node_id", prefs.nodeId)
                    put("token", prefs.token)
                    put("data_dir", dataDir)
                    put("report_secs", prefs.reportSecs)
                    put("config_secs", prefs.configSecs)
                    put("uplink_ws", true)
                }.toString()

                LadderApplication.appendLog("创建 Agent Runner: panel=${prefs.panelUrl} node=${prefs.nodeId}")
                val newRunner = Mobile.newRunner(cfgJson, this@AgentService)
                newRunner.start()
                runner = newRunner
                isServiceRunning.postValue(true)
                LadderApplication.appendLog("Agent Runner 启动成功")

                startStatusMonitor()
            } catch (e: Exception) {
                LadderApplication.appendLog("Agent 启动失败: ${e.message}")
                val errStatus = AgentStatus(
                    running = false,
                    state = "error",
                    lastError = e.message ?: "未知异常"
                )
                currentStatus.postValue(errStatus)
                isServiceRunning.postValue(false)
                wakeLock?.let {
                    if (it.isHeld) it.release()
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } finally {
                isStarting.set(false)
            }
        }
    }

    private fun stopAgent() {
        statusMonitorJob?.cancel()
        statusMonitorJob = null

        val currentRunner = runner
        runner = null

        serviceScope.launch {
            try {
                currentRunner?.stop()
                LadderApplication.appendLog("Agent 已正常停止")
            } catch (e: Exception) {
                LadderApplication.appendLog("停止 Agent 出现异常: ${e.message}")
            } finally {
                withContext(NonCancellable) {
                    isServiceRunning.postValue(false)
                    currentStatus.postValue(AgentStatus(running = false, state = "stopped"))
                    wakeLock?.let {
                        if (it.isHeld) it.release()
                    }
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun startStatusMonitor() {
        statusMonitorJob?.cancel()
        statusMonitorJob = serviceScope.launch {
            while (isActive) {
                try {
                    runner?.let { r ->
                        val json = r.statusJSON()
                        val status = parseStatus(json)
                        currentStatus.postValue(status)

                        val notifText = "上行: ${formatBytes(status.uplinkBytes)} | 下行: ${formatBytes(status.downlinkBytes)} | 连接: ${status.connections}"
                        val notif = buildNotification(notifText)
                        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                        manager.notify(NOTIFICATION_ID, notif)
                    }
                } catch (_: Exception) {
                }
                delay(2000)
            }
        }
    }

    private fun parseStatus(json: String): AgentStatus {
        return try {
            val obj = JSONObject(json)
            AgentStatus(
                running = obj.optBoolean("running", false),
                state = obj.optString("state", "unknown"),
                agentVersion = obj.optString("agent_version", ""),
                singboxVersion = obj.optString("singbox_version", ""),
                panelUrl = obj.optString("panel_url", ""),
                nodeId = obj.optString("node_id", ""),
                startedAtUnix = obj.optLong("started_at_unix", 0),
                uptimeSecs = obj.optLong("uptime_secs", 0),
                uplinkBytes = obj.optLong("uplink_bytes", 0),
                downlinkBytes = obj.optLong("downlink_bytes", 0),
                connections = obj.optLong("connections", 0),
                configHash = obj.optString("config_hash", ""),
                lastError = obj.optString("last_error", "")
            )
        } catch (_: Exception) {
            AgentStatus()
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val prefs = LadderApplication.instance.prefs
        val activityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingActivity = PendingIntent.getActivity(
            this, 0, activityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, AgentService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val title = if (prefs.nodeId.isNotBlank()) {
            "LadderAirport Agent: ${prefs.nodeId}"
        } else {
            "LadderAirport Agent 运行中"
        }

        return NotificationCompat.Builder(this, LadderApplication.CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setContentIntent(pendingActivity)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "停止", pendingStop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun setupNetworkMonitoring() {
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                LadderApplication.appendLog("网络已连接")
            }

            override fun onLost(network: Network) {
                LadderApplication.appendLog("网络已断开")
            }
        }
        networkCallback?.let {
            connectivityManager?.registerDefaultNetworkCallback(it)
        }
    }

    override fun writeLog(line: String?) {
        if (!line.isNullOrBlank()) {
            LadderApplication.appendLog(line)
        }
    }

    override fun nodeMetricsJSON(): String {
        return DeviceMetricsHelper.getMetricsJSON(this)
    }

    override fun interfacesJSON(): String {
        return NetworkInterfaceHelper.getInterfacesJSON()
    }

    override fun onDestroy() {
        super.onDestroy()
        statusMonitorJob?.cancel()
        statusMonitorJob = null
        val currentRunner = runner
        runner = null
        if (currentRunner != null) {
            try {
                currentRunner.stop()
            } catch (_: Exception) {}
        }
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        networkCallback?.let {
            try {
                connectivityManager?.unregisterNetworkCallback(it)
            } catch (_: Exception) {}
        }
        serviceScope.cancel()
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
}
