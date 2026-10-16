package io.ladderairport.agent.model

import com.google.gson.JsonObject
import com.google.gson.JsonParser

data class AgentStatus(
    val running: Boolean = false,
    val state: String = "stopped",
    val agentVersion: String = "",
    val coreVersion: String = "",
    val coreCommit: String = "",
    val clientType: String = "",
    val clientVersion: String = "",
    val singboxVersion: String = "",
    val panelUrl: String = "",
    val nodeId: String = "",
    val startedAtUnix: Long = 0,
    val uptimeSecs: Long = 0,
    val uplinkBytes: Long = 0,
    val downlinkBytes: Long = 0,
    val connections: Long = 0,
    val configHash: String = "",
    val lastError: String = ""
)

fun parseAgentStatus(json: String): AgentStatus {
    return try {
        val element = JsonParser.parseString(json)
        if (!element.isJsonObject) return AgentStatus()
        val obj = element.asJsonObject
        AgentStatus(
            running = obj.optBoolean("running", false),
            state = obj.optString("state", "unknown"),
            agentVersion = obj.optString("agent_version"),
            coreVersion = obj.optString("core_version"),
            coreCommit = obj.optString("core_commit"),
            clientType = obj.optString("client_type"),
            clientVersion = obj.optString("client_version"),
            singboxVersion = obj.optString("singbox_version"),
            panelUrl = obj.optString("panel_url"),
            nodeId = obj.optString("node_id"),
            startedAtUnix = obj.optLong("started_at_unix"),
            uptimeSecs = obj.optLong("uptime_secs"),
            uplinkBytes = obj.optLong("uplink_bytes"),
            downlinkBytes = obj.optLong("downlink_bytes"),
            connections = obj.optLong("connections"),
            configHash = obj.optString("config_hash"),
            lastError = obj.optString("last_error")
        )
    } catch (_: Exception) {
        AgentStatus()
    }
}

private fun JsonObject.optString(key: String, default: String = ""): String {
    if (!has(key) || get(key).isJsonNull) return default
    return get(key).asString
}

private fun JsonObject.optBoolean(key: String, default: Boolean = false): Boolean {
    if (!has(key) || get(key).isJsonNull) return default
    return get(key).asBoolean
}

private fun JsonObject.optLong(key: String, default: Long = 0L): Long {
    if (!has(key) || get(key).isJsonNull) return default
    return get(key).asLong
}
