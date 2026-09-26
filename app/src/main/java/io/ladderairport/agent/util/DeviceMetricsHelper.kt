package io.ladderairport.agent.util

import android.app.ActivityManager
import android.content.Context
import android.net.TrafficStats
import android.os.Environment
import android.os.StatFs
import org.json.JSONObject
import java.io.RandomAccessFile

object DeviceMetricsHelper {

    private var prevTotalCpu: Long = 0
    private var prevIdleCpu: Long = 0
    private var prevRxBytes: Long = 0
    private var prevTxBytes: Long = 0
    private var prevSampleTime: Long = 0

    @Synchronized
    fun getMetricsJSON(context: Context): String {
        val json = JSONObject()
        val now = System.currentTimeMillis()

        // 1. CPU Percent
        val cpuUsage = sampleCpuUsage()
        json.put("cpu_percent", cpuUsage)

        // 2. Memory
        val actMgr = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        if (actMgr != null) {
            actMgr.getMemoryInfo(memInfo)
            json.put("memory_total_bytes", memInfo.totalMem)
            json.put("memory_used_bytes", memInfo.totalMem - memInfo.availMem)
        } else {
            val totalMem = Runtime.getRuntime().totalMemory()
            val freeMem = Runtime.getRuntime().freeMemory()
            json.put("memory_total_bytes", totalMem)
            json.put("memory_used_bytes", totalMem - freeMem)
        }

        // 3. Storage
        try {
            val statFs = StatFs(context.filesDir.absolutePath)
            val blockSize = statFs.blockSizeLong
            val totalBlocks = statFs.blockCountLong
            val freeBlocks = statFs.availableBlocksLong
            json.put("disk_total_bytes", totalBlocks * blockSize)
            json.put("disk_used_bytes", (totalBlocks - freeBlocks) * blockSize)
        } catch (_: Exception) {
            json.put("disk_total_bytes", 0)
            json.put("disk_used_bytes", 0)
        }

        // 4. Network Rate (bps)
        val curRx = TrafficStats.getTotalRxBytes()
        val curTx = TrafficStats.getTotalTxBytes()
        if (prevSampleTime > 0 && now > prevSampleTime) {
            val elapsedSecs = (now - prevSampleTime) / 1000.0
            if (elapsedSecs > 0 && curRx >= prevRxBytes && curTx >= prevTxBytes) {
                val rxBps = (((curRx - prevRxBytes) * 8) / elapsedSecs).toLong()
                val txBps = (((curTx - prevTxBytes) * 8) / elapsedSecs).toLong()
                json.put("downlink_bps", rxBps)
                json.put("uplink_bps", txBps)
            }
        } else {
            json.put("downlink_bps", 0)
            json.put("uplink_bps", 0)
        }
        prevRxBytes = curRx
        prevTxBytes = curTx
        prevSampleTime = now

        json.put("collected_at_unix", now / 1000)
        return json.toString()
    }

    @Volatile
    private var isProcStatReadable: Boolean = true

    private fun sampleCpuUsage(): Double {
        if (!isProcStatReadable) return 0.0
        try {
            val load = RandomAccessFile("/proc/stat", "r").use { reader ->
                reader.readLine()
            } ?: return 0.0

            val toks = load.split(" +".toRegex())
            if (toks.size < 5) return 0.0

            val idle = toks[4].toLong()
            var total: Long = 0
            for (i in 1 until toks.size.coerceAtMost(9)) {
                total += toks[i].toLongOrNull() ?: 0L
            }

            if (prevTotalCpu != 0L && total > prevTotalCpu) {
                val totalDiff = total - prevTotalCpu
                val idleDiff = idle - prevIdleCpu
                prevTotalCpu = total
                prevIdleCpu = idle
                val busy = (totalDiff - idleDiff).coerceAtLeast(0)
                return (busy * 100.0) / totalDiff
            }
            prevTotalCpu = total
            prevIdleCpu = idle
        } catch (_: Exception) {
            isProcStatReadable = false
        }
        return 0.0
    }
}
