package io.ladderairport.agent.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.lifecycle.MutableLiveData
import io.ladderairport.agent.LadderApplication
import io.ladderairport.agent.R
import io.ladderairport.agent.mobile.ClientRunner
import io.ladderairport.agent.mobile.Host
import io.ladderairport.agent.mobile.Mobile
import io.ladderairport.agent.mobile.TunHost
import io.ladderairport.agent.ui.MainActivity
import io.ladderairport.agent.util.DeviceMetricsHelper
import io.ladderairport.agent.util.NetworkInterfaceHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

class ClientVpnService : VpnService(), Host {

    companion object {
        const val ACTION_START = "io.ladderairport.agent.action.CLIENT_START"
        const val ACTION_STOP = "io.ladderairport.agent.action.CLIENT_STOP"
        const val NOTIFICATION_ID = 1002

        val isServiceRunning = MutableLiveData(false)
        val statusJSON = MutableLiveData("{}")
        val outboundsJSON = MutableLiveData("""{"tags":[],"selected":""}""")
        val lastError = MutableLiveData("")

        fun prepareIntent(context: Context): Intent? = prepare(context)

        fun start(context: Context) {
            val intent = Intent(context, ClientVpnService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ClientVpnService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private var runner: ClientRunner? = null
    private var tunPfd: ParcelFileDescriptor? = null
    private val isStarting = AtomicBoolean(false)
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var statusMonitorJob: Job? = null

    private val tunHost = object : TunHost {
        override fun openTun(optionsJSON: String?): Int = openVpnTun(optionsJSON)

        override fun protect(fd: Int) {
            if (!this@ClientVpnService.protect(fd)) {
                throw IllegalStateException("VpnService.protect($fd) 失败")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopClient()
                return START_NOT_STICKY
            }
            ACTION_START, null -> startClient()
        }
        return START_STICKY
    }

    private fun startClient() {
        val notification = buildNotification(
            if (isServiceRunning.value == true) "客户端运行中" else "正在启动客户端 VPN..."
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

        serviceScope.launch {
            try {
                val prefs = LadderApplication.instance.prefs
                if (prefs.clientSubUrl.isBlank()) {
                    throw IllegalStateException("请先填写订阅链接")
                }

                val cfgJson = JSONObject().apply {
                    put("sub_url", prefs.clientSubUrl)
                    put("data_dir", filesDir.absolutePath)
                    put("refresh_secs", 21600)
                }.toString()

                LadderApplication.appendLog("创建 ClientRunner: sub=${prefs.clientSubUrl}")
                val newRunner = Mobile.newClientRunner(cfgJson, this@ClientVpnService, tunHost)
                newRunner.start()
                runner = newRunner
                isServiceRunning.postValue(true)
                lastError.postValue("")
                LadderApplication.appendLog("客户端 VPN 启动成功")
                startStatusMonitor()
            } catch (e: Exception) {
                LadderApplication.appendLog("客户端启动失败: ${e.message}")
                lastError.postValue(e.message ?: "未知异常")
                isServiceRunning.postValue(false)
                closeTun()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } finally {
                isStarting.set(false)
            }
        }
    }

    private fun stopClient() {
        statusMonitorJob?.cancel()
        statusMonitorJob = null
        val current = runner
        runner = null
        serviceScope.launch {
            try {
                current?.stop()
                LadderApplication.appendLog("客户端 VPN 已停止")
            } catch (e: Exception) {
                LadderApplication.appendLog("停止客户端异常: ${e.message}")
            } finally {
                withContext(NonCancellable) {
                    closeTun()
                    isServiceRunning.postValue(false)
                    statusJSON.postValue("""{"running":false,"state":"stopped"}""")
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
                        statusJSON.postValue(r.statusJSON())
                        outboundsJSON.postValue(r.outboundsJSON())
                        val obj = JSONObject(r.statusJSON())
                        val selected = obj.optString("selected_tag", "")
                        val text = if (selected.isNotBlank()) {
                            "节点: $selected"
                        } else {
                            "客户端 VPN 运行中"
                        }
                        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                        manager.notify(NOTIFICATION_ID, buildNotification(text))
                    }
                } catch (_: Exception) {
                }
                delay(2000)
            }
        }
    }

    private fun openVpnTun(optionsJSON: String?): Int {
        val json = optionsJSON ?: throw IllegalArgumentException("tun options empty")
        val opts = JSONObject(json)
        closeTun()

        val builder = Builder()
            .setSession("LadderAirport")
            .setMtu(opts.optInt("mtu", 9000).coerceIn(1280, 9000))
            .setBlocking(true)

        addAddresses(builder, opts.optJSONArray("inet4_address"))
        addAddresses(builder, opts.optJSONArray("inet6_address"))

        if (opts.optBoolean("auto_route", true)) {
            addRoutes(builder, opts.optJSONArray("inet4_route_range"))
            addRoutes(builder, opts.optJSONArray("inet6_route_range"))
            if (opts.optJSONArray("inet4_route_range") == null ||
                opts.optJSONArray("inet4_route_range")!!.length() == 0
            ) {
                builder.addRoute("0.0.0.0", 0)
            }
        }

        val dns = opts.optString("dns_server", "")
        if (dns.isNotBlank()) {
            try {
                builder.addDnsServer(dns)
            } catch (e: Exception) {
                LadderApplication.appendLog("addDnsServer($dns) failed: ${e.message}")
            }
        }

        try {
            builder.addDisallowedApplication(packageName)
        } catch (_: Exception) {
        }

        val exclude = opts.optJSONArray("exclude_package")
        if (exclude != null) {
            for (i in 0 until exclude.length()) {
                val pkg = exclude.optString(i)
                if (pkg.isNotBlank() && pkg != packageName) {
                    try {
                        builder.addDisallowedApplication(pkg)
                    } catch (_: Exception) {
                    }
                }
            }
        }

        val include = opts.optJSONArray("include_package")
        if (include != null && include.length() > 0) {
            for (i in 0 until include.length()) {
                val pkg = include.optString(i)
                if (pkg.isNotBlank()) {
                    try {
                        builder.addAllowedApplication(pkg)
                    } catch (_: Exception) {
                    }
                }
            }
        }

        val pfd = builder.establish()
            ?: throw IllegalStateException("VpnService.Builder.establish() 返回 null（用户可能撤销了 VPN 授权）")
        tunPfd = pfd
        LadderApplication.appendLog("TUN 已建立 fd=${pfd.fd}")
        return pfd.fd
    }

    private fun addAddresses(builder: Builder, arr: JSONArray?) {
        if (arr == null) return
        for (i in 0 until arr.length()) {
            val prefix = arr.optString(i)
            val parts = prefix.split("/")
            if (parts.size != 2) continue
            val addr = parts[0]
            val prefixLen = parts[1].toIntOrNull() ?: continue
            try {
                builder.addAddress(addr, prefixLen)
            } catch (e: Exception) {
                LadderApplication.appendLog("addAddress($prefix) failed: ${e.message}")
            }
        }
    }

    private fun addRoutes(builder: Builder, arr: JSONArray?) {
        if (arr == null) return
        for (i in 0 until arr.length()) {
            val prefix = arr.optString(i)
            val parts = prefix.split("/")
            if (parts.size != 2) continue
            val addr = parts[0]
            val prefixLen = parts[1].toIntOrNull() ?: continue
            try {
                builder.addRoute(addr, prefixLen)
            } catch (e: Exception) {
                LadderApplication.appendLog("addRoute($prefix) failed: ${e.message}")
            }
        }
    }

    private fun closeTun() {
        try {
            tunPfd?.close()
        } catch (_: Exception) {
        }
        tunPfd = null
    }

    private fun buildNotification(contentText: String): Notification {
        val activityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingActivity = PendingIntent.getActivity(
            this, 0, activityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = Intent(this, ClientVpnService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this, 2, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, LadderApplication.CHANNEL_ID)
            .setContentTitle("LadderAirport 客户端")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setContentIntent(pendingActivity)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "断开", pendingStop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun writeLog(line: String?) {
        if (!line.isNullOrBlank()) {
            LadderApplication.appendLog(line)
        }
    }

    override fun nodeMetricsJSON(): String = DeviceMetricsHelper.getMetricsJSON(this)

    override fun interfacesJSON(): String = NetworkInterfaceHelper.getInterfacesJSON()

    override fun onRevoke() {
        LadderApplication.appendLog("系统撤销了 VPN 权限")
        stopClient()
    }

    override fun onDestroy() {
        statusMonitorJob?.cancel()
        statusMonitorJob = null
        val current = runner
        runner = null
        try {
            current?.stop()
        } catch (_: Exception) {
        }
        closeTun()
        isServiceRunning.postValue(false)
        serviceScope.cancel()
        super.onDestroy()
    }
}
