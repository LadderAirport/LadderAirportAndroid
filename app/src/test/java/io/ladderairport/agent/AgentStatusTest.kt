package io.ladderairport.agent

import io.ladderairport.agent.model.AgentStatus
import io.ladderairport.agent.model.parseAgentStatus
import io.ladderairport.agent.service.buildRunnerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentStatusTest {

    @Test
    fun testAgentStatusDefaults() {
        val status = AgentStatus()
        assertFalse(status.running)
        assertEquals("stopped", status.state)
        assertEquals(0L, status.uplinkBytes)
        assertEquals(0L, status.downlinkBytes)
    }

    @Test
    fun testAgentStatusParsing() {
        val json = """
            {
                "running": true,
                "state": "running",
                "agent_version": "v0.15.5",
                "singbox_version": "1.12.22",
                "panel_url": "https://panel.example.com",
                "node_id": "android-node-01",
                "started_at_unix": 1727140000,
                "uptime_secs": 120,
                "uplink_bytes": 1048576,
                "downlink_bytes": 2097152,
                "connections": 5,
                "config_hash": "abc123hash",
                "last_error": ""
            }
        """.trimIndent()

        val status = parseAgentStatus(json)

        assertTrue(status.running)
        assertEquals("running", status.state)
        assertEquals("v0.15.5", status.agentVersion)
        assertEquals(1048576L, status.uplinkBytes)
        assertEquals(2097152L, status.downlinkBytes)
        assertEquals(5L, status.connections)
        assertEquals("abc123hash", status.configHash)
        assertEquals("", status.clientVersion)
        assertEquals("", status.coreVersion)
    }

    @Test
    fun testDecoupledVersionFields() {
        val json = """
            {
                "running": true,
                "state": "running",
                "agent_version": "v0.15.9-3-g2ebfece",
                "core_version": "v0.15.9-3-g2ebfece",
                "core_commit": "2ebfece",
                "client_type": "android",
                "client_version": "1.1.0"
            }
        """.trimIndent()

        val status = parseAgentStatus(json)
        assertEquals("v0.15.9-3-g2ebfece", status.agentVersion)
        assertEquals(status.agentVersion, status.coreVersion)
        assertEquals("2ebfece", status.coreCommit)
        assertEquals("android", status.clientType)
        assertEquals("1.1.0", status.clientVersion)
        assertNotEquals(status.agentVersion, status.clientVersion)
    }

    @Test
    fun testRunnerConfigKeepsClientVersionOffCoreKeys() {
        val cfg = buildRunnerConfig(
            panelUrl = "https://panel.example",
            nodeId = "android-node-01",
            token = "tok",
            dataDir = "/data/data",
            reportSecs = 15,
            configSecs = 60,
            clientVersion = "1.1.0",
        )
        assertEquals("1.1.0", cfg["client_version"])
        assertEquals("android", cfg["client_type"])
        assertEquals(true, cfg["uplink_ws"])
        assertFalse(cfg.containsKey("version"))
        assertFalse(cfg.containsKey("agent_version"))
        assertFalse(cfg.containsKey("core_version"))
    }
}
