package io.ladderairport.agent.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.ladderairport.agent.LadderApplication
import io.ladderairport.agent.service.AgentService

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }
        val prefs = LadderApplication.instance.prefs
        if (prefs.autoStart && prefs.isConfigured() && prefs.enrolled) {
            LadderApplication.appendLog("开机自启：启动 Agent")
            AgentService.start(context)
        }
    }
}
