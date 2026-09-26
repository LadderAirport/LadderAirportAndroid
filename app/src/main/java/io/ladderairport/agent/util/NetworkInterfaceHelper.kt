package io.ladderairport.agent.util

import org.json.JSONArray
import org.json.JSONObject
import java.net.NetworkInterface

object NetworkInterfaceHelper {

    fun getInterfacesJSON(): String {
        val array = JSONArray()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "[]"
            for (iface in interfaces) {
                try {
                    val obj = JSONObject()
                    obj.put("name", iface.name)
                    obj.put("up", runCatching { iface.isUp }.getOrDefault(false))
                    obj.put("loopback", runCatching { iface.isLoopback }.getOrDefault(false))
                    obj.put("mtu", runCatching { iface.mtu }.getOrDefault(0))

                    val hwAddr = try {
                        iface.hardwareAddress
                    } catch (_: Exception) {
                        null
                    }
                    if (hwAddr != null && hwAddr.isNotEmpty()) {
                        val sb = StringBuilder()
                        for (b in hwAddr) {
                            if (sb.isNotEmpty()) sb.append(":")
                            sb.append(String.format(java.util.Locale.US, "%02x", b))
                        }
                        obj.put("hardware_addr", sb.toString())
                    } else {
                        obj.put("hardware_addr", "")
                    }

                    val addrsArray = JSONArray()
                    val addrs = runCatching { iface.inetAddresses }.getOrNull()
                    if (addrs != null) {
                        for (addr in addrs) {
                            val hostAddress = addr.hostAddress
                            if (!hostAddress.isNullOrBlank()) {
                                // Strip interface index / zone suffix if present
                                val clean = hostAddress.substringBefore("%")
                                addrsArray.put(clean)
                            }
                        }
                    }
                    obj.put("addresses", addrsArray)
                    array.put(obj)
                } catch (_: Exception) {
                    // Skip single problematic interface without aborting entire list
                }
            }
        } catch (_: Exception) {
        }
        return array.toString()
    }
}
