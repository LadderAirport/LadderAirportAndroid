package io.ladderairport.agent.util

import android.content.Context
import android.content.SharedPreferences

class PreferencesHelper(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("ladder_agent_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_PANEL_URL = "panel_url"
        private const val KEY_NODE_ID = "node_id"
        private const val KEY_TOKEN = "token"
        private const val KEY_AUTO_START = "auto_start"
        private const val KEY_REPORT_SECS = "report_secs"
        private const val KEY_CONFIG_SECS = "config_secs"
        private const val KEY_ENROLLED = "enrolled"
    }

    var panelUrl: String
        get() = prefs.getString(KEY_PANEL_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PANEL_URL, value.trim().trimEnd('/')).apply()

    var nodeId: String
        get() = prefs.getString(KEY_NODE_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_NODE_ID, value.trim()).apply()

    var token: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TOKEN, value.trim()).apply()

    var autoStart: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_START, value).apply()

    var reportSecs: Int
        get() = prefs.getInt(KEY_REPORT_SECS, 15)
        set(value) = prefs.edit().putInt(KEY_REPORT_SECS, value).apply()

    var configSecs: Int
        get() = prefs.getInt(KEY_CONFIG_SECS, 60)
        set(value) = prefs.edit().putInt(KEY_CONFIG_SECS, value).apply()

    /** True after a successful enroll exchanged the one-time token for a control token. */
    var enrolled: Boolean
        get() = prefs.getBoolean(KEY_ENROLLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENROLLED, value).apply()

    fun isConfigured(): Boolean {
        return panelUrl.isNotBlank() && nodeId.isNotBlank() && token.isNotBlank()
    }
}
